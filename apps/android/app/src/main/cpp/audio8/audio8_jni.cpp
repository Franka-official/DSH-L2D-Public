#include <jni.h>
#include "audio8_c_api.h"
#include <string>
#include <vector>
#include <limits>
#include <cstdio>
#include <sys/resource.h>
#include <sstream>
#include "onnxruntime_cxx_api.h"
#include <array>
#include <algorithm>
#include <cmath>
#include <stdexcept>

static void fail(JNIEnv* env,const char* text) {
  jclass cls=env->FindClass("java/lang/IllegalStateException");env->ThrowNew(cls,text);env->DeleteLocalRef(cls);
}
// Reference registration is a one-shot CPU session, independent of the speech decoder.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_prts_client_audio_audio8_Audio8Native_encodeReference(
    JNIEnv* env, jobject, jstring graphPath, jfloatArray samples) {
  const auto count = env->GetArrayLength(samples);
  if (count < 22050 || count > 354304 || count % 2048 != 0) {
    fail(env, "Unexpected Audio8 reference waveform length");
    return nullptr;
  }
  const char* path = env->GetStringUTFChars(graphPath, nullptr);
  if (!path) return nullptr;
  try {
    std::vector<float> pcm(count);
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());
    if (env->ExceptionCheck()) {
      env->ReleaseStringUTFChars(graphPath, path);
      return nullptr;
    }
    if (!std::all_of(pcm.begin(), pcm.end(), [](float value) { return std::isfinite(value); }))
      throw std::runtime_error("Invalid Audio8 waveform");

    const auto* api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (!api) throw std::runtime_error("Audio8 encoder requires ONNX Runtime");
    Ort::InitApi(api);
    Ort::Env runtime(ORT_LOGGING_LEVEL_WARNING, "audio8-reference-encoder");
    Ort::SessionOptions options;
    options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
    options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
    options.SetIntraOpNumThreads(2);
    options.SetInterOpNumThreads(1);
    options.DisableCpuMemArena();
    options.DisableMemPattern();
    options.AddConfigEntry("session.intra_op.allow_spinning", "0");
    Ort::Session session(runtime, path, options);
    if (session.GetInputCount() != 1 || session.GetOutputCount() != 1)
      throw std::runtime_error("Unexpected Audio8 encoder inputs/outputs");
    Ort::AllocatorWithDefaultOptions allocator;
    const auto inputName = session.GetInputNameAllocated(0, allocator);
    const auto outputName = session.GetOutputNameAllocated(0, allocator);
    const char* inputs[] = { inputName.get() };
    const char* outputs[] = { outputName.get() };
    const auto inputInfo = session.GetInputTypeInfo(0);
    const auto inputType = inputInfo.GetTensorTypeAndShapeInfo().GetElementType();
    auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
    const std::array<int64_t, 3> shape{1, 1, count};
    std::vector<Ort::Float16_t> half;
    Ort::Value input(nullptr);
    if (inputType == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
      input = Ort::Value::CreateTensor<float>(memory, pcm.data(), pcm.size(), shape.data(), shape.size());
    } else if (inputType == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16) {
      half.reserve(pcm.size());
      for (const float value : pcm) half.emplace_back(value);
      input = Ort::Value::CreateTensor<Ort::Float16_t>(memory, half.data(), half.size(), shape.data(), shape.size());
    } else {
      throw std::runtime_error("Unsupported Audio8 encoder input type");
    }
    const auto result = session.Run(Ort::RunOptions(), inputs, &input, 1, outputs, 1);
    const auto info = result.front().GetTensorTypeAndShapeInfo();
    const auto dimensions = info.GetShape();
    if (dimensions.size() != 3 || dimensions[0] != 1 || dimensions[1] != 10 ||
        dimensions[2] < 1 || dimensions[2] > count / 2048 + 1 ||
        info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64)
      throw std::runtime_error("Unexpected Audio8 reference code shape");
    const auto* codes = result.front().GetTensorData<int64_t>();
    const size_t size = info.GetElementCount();
    if (!std::all_of(codes, codes + size, [](int64_t value) { return value >= 0 && value <= 4095; }))
      throw std::runtime_error("Unexpected Audio8 reference codes");
    auto output = env->NewLongArray(static_cast<jsize>(size));
    if (output) env->SetLongArrayRegion(output, 0, static_cast<jsize>(size), reinterpret_cast<const jlong*>(codes));
    env->ReleaseStringUTFChars(graphPath, path);
    return output;
  } catch (const std::exception& error) {
    env->ReleaseStringUTFChars(graphPath, path);
    fail(env, error.what());
    return nullptr;
  }
}

struct Callback {
  JavaVM* vm; jobject sink;jmethodID pcm,event;bool failed=false;
  JNIEnv* attach(bool& attached) {
    JNIEnv* env=nullptr;attached=false;
    if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)==JNI_EDETACHED) {
      if(vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)return nullptr;attached=true;
    }
    return env;
  }
};
static int pcmCallback(const float* pcm,size_t count,int rate,int final,void* opaque) {
  auto& c=*static_cast<Callback*>(opaque);if(c.failed||count>static_cast<size_t>(std::numeric_limits<jsize>::max()))return 0;
  bool attached;JNIEnv* env=c.attach(attached);if(!env)return 0;
  jfloatArray array=env->NewFloatArray(static_cast<jsize>(count));
  if(array&&count)env->SetFloatArrayRegion(array,0,static_cast<jsize>(count),pcm);
  jboolean result=JNI_FALSE;
  if(!env->ExceptionCheck()&&array)result=env->CallBooleanMethod(c.sink,c.pcm,array,rate,final?JNI_TRUE:JNI_FALSE);
  if(env->ExceptionCheck())c.failed=true;
  if(array)env->DeleteLocalRef(array);
  if(attached) { if(env->ExceptionCheck())env->ExceptionClear();c.vm->DetachCurrentThread(); }
  return result==JNI_TRUE&&!c.failed;
}
static void eventCallback(const char* json,void* opaque) {
  auto& c=*static_cast<Callback*>(opaque);if(c.failed)return;
  bool attached;JNIEnv* env=c.attach(attached);if(!env)return;
  jstring text=env->NewStringUTF(json);if(text)env->CallVoidMethod(c.sink,c.event,text);
  if(env->ExceptionCheck())c.failed=true;if(text)env->DeleteLocalRef(text);
  if(attached) {if(env->ExceptionCheck())env->ExceptionClear();c.vm->DetachCurrentThread();}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_prts_client_audio_audio8_Audio8Native_create(JNIEnv* env,jclass,jstring directory,jint threads) {
  const char* dir=env->GetStringUTFChars(directory,nullptr);char error[2048]={};
  Audio8Handle* handle=audio8_create_incremental(dir,threads,error,sizeof(error));env->ReleaseStringUTFChars(directory,dir);
  if(!handle)fail(env,error);return reinterpret_cast<jlong>(handle);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_prts_client_audio_audio8_Audio8Native_generateNative(JNIEnv* env,jclass,jlong raw,jlongArray input,jint positions,jint prefix,jint maxFrames,jint chunk,jint context,jint guard,jlong seed,jobject sink) {
  auto* handle=reinterpret_cast<Audio8Handle*>(raw);
  if(!handle||positions<1||env->GetArrayLength(input)!=11*positions) {fail(env,"Invalid packed prompt shape");return nullptr;}
  std::vector<jlong> values(env->GetArrayLength(input));env->GetLongArrayRegion(input,0,values.size(),values.data());if(env->ExceptionCheck())return nullptr;
  Callback callback{};env->GetJavaVM(&callback.vm);callback.sink=env->NewGlobalRef(sink);
  jclass cls=env->GetObjectClass(sink);callback.pcm=env->GetMethodID(cls,"onPcm","([FIZ)Z");callback.event=env->GetMethodID(cls,"onNativeEvent","(Ljava/lang/String;)V");env->DeleteLocalRef(cls);
  if(env->ExceptionCheck()) {env->DeleteGlobalRef(callback.sink);return nullptr;}
  Audio8GenerateConfig config;audio8_default_config(&config);config.max_frames=maxFrames;config.chunk_frames=chunk;config.context_frames=context;config.guard_frames=guard;config.seed=seed;
  char error[2048]={};
  int result=audio8_generate(handle,reinterpret_cast<const int64_t*>(values.data()),positions,prefix,&config,pcmCallback,eventCallback,&callback,error,sizeof(error));
  env->DeleteGlobalRef(callback.sink);
  if(env->ExceptionCheck())return nullptr;
  if(result!=0||callback.failed) {fail(env,callback.failed?"Java PCM/event callback failed":error);return nullptr;}
  return env->NewStringUTF(audio8_last_result_json(handle));
}
extern "C" JNIEXPORT void JNICALL Java_com_prts_client_audio_audio8_Audio8Native_saveNative(JNIEnv* env,jclass,jlong raw,jstring prefix) {
  const char* path=env->GetStringUTFChars(prefix,nullptr);char error[2048]={};std::string wav=std::string(path)+".wav";int result=audio8_save_wav(reinterpret_cast<Audio8Handle*>(raw),wav.c_str(),error,sizeof(error));
  env->ReleaseStringUTFChars(prefix,path);if(result)fail(env,error);
}
extern "C" JNIEXPORT void JNICALL Java_com_prts_client_audio_audio8_Audio8Native_cancelNative(JNIEnv*,jclass,jlong raw) {audio8_cancel(reinterpret_cast<Audio8Handle*>(raw));}
extern "C" JNIEXPORT void JNICALL Java_com_prts_client_audio_audio8_Audio8Native_destroy(JNIEnv*,jclass,jlong raw) {audio8_destroy(reinterpret_cast<Audio8Handle*>(raw));}

extern "C" JNIEXPORT void JNICALL Java_com_prts_client_audio_audio8_Audio8Native_setPipeline(JNIEnv*,jobject,jlong raw,jboolean enabled) {
  audio8_set_pipeline(reinterpret_cast<Audio8Handle*>(raw),enabled?1:0);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_prts_client_audio_audio8_Audio8Native_runtimeInfo(JNIEnv* env,jobject) {
  const char* metadata=audio8_runtime_info_json();
  if(!metadata){fail(env,"Runtime metadata returned no result");return nullptr;}
  std::string json(metadata);
  if(json.size()<2||json.front()!='{'||json.back()!='}'){
    fail(env,"Runtime metadata is not a JSON object");return nullptr;
  }
  json.pop_back();struct rusage usage{};getrusage(RUSAGE_SELF,&usage);
  std::ostringstream out;out << json << (json.size()>1?",":"")
    << "\"maxrss_kib_process_highwater\":" << usage.ru_maxrss
    << ",\"cpu_user_s_process\":" << usage.ru_utime.tv_sec+usage.ru_utime.tv_usec/1e6
    << ",\"cpu_system_s_process\":" << usage.ru_stime.tv_sec+usage.ru_stime.tv_usec/1e6
    << ",\"minor_faults_process\":" << usage.ru_minflt << ",\"major_faults_process\":" << usage.ru_majflt << "}";
  return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT jlong JNICALL Java_com_prts_client_audio_audio8_Audio8Native_createTuned(JNIEnv* env,jclass,jstring directory,jint arThreads,jint codecThreads) {
  const char* dir=env->GetStringUTFChars(directory,nullptr);char error[2048]={};
  Audio8Handle* handle=audio8_create_incremental_tuned(dir,arThreads,codecThreads,error,sizeof(error));
  env->ReleaseStringUTFChars(directory,dir);
  if(!handle)fail(env,error);return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jlong JNICALL Java_com_prts_client_audio_audio8_Audio8Native_createProfiled(JNIEnv* env,jclass,jstring directory,jint arThreads,jint codecThreads,jboolean autoProfile) {
  const char* dir=env->GetStringUTFChars(directory,nullptr);char error[2048]={};
  Audio8Handle* handle=audio8_create_incremental_profiled(dir,arThreads,codecThreads,autoProfile?1:0,error,sizeof(error));
  env->ReleaseStringUTFChars(directory,dir);if(!handle)fail(env,error);return reinterpret_cast<jlong>(handle);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_prts_client_audio_audio8_Audio8Native_preparePrefix(JNIEnv* env,jclass,jlong raw,jlongArray input,jint positions,jint prefix) {
  auto* handle=reinterpret_cast<Audio8Handle*>(raw);
  if(!handle||positions<1||positions>=2048||prefix<1||prefix>positions||env->GetArrayLength(input)!=11*positions){fail(env,"Invalid reference prefix shape");return nullptr;}
  std::vector<jlong> values(env->GetArrayLength(input));env->GetLongArrayRegion(input,0,values.size(),values.data());
  if(env->ExceptionCheck())return nullptr;char error[2048]={};
  const int status=audio8_prepare_prefix(handle,reinterpret_cast<const int64_t*>(values.data()),positions,prefix,nullptr,nullptr,error,sizeof(error));
  if(status){fail(env,error);return nullptr;}return env->NewStringUTF(audio8_last_prefix_json(handle));
}
extern "C" JNIEXPORT void JNICALL Java_com_prts_client_audio_audio8_Audio8Native_resetNative(JNIEnv*,jclass,jlong raw) {audio8_reset(reinterpret_cast<Audio8Handle*>(raw));}

extern "C" JNIEXPORT jstring JNICALL Java_com_prts_client_audio_audio8_Audio8Native_affinityProbe(JNIEnv* env,jclass) {
  const char* json=audio8_affinity_probe_json();
  if(!json){fail(env,"Affinity probe returned no result");return nullptr;}
  return env->NewStringUTF(json);
}

extern "C" JNIEXPORT jlong JNICALL Java_com_prts_client_audio_audio8_Audio8Native_createMnn(JNIEnv* env,jclass,jstring directory,jstring modelPath,jint arThreads,jint codecThreads,jboolean useOpenCl,jboolean lowPrecision) {
  const char* dir=env->GetStringUTFChars(directory,nullptr);
  if(!dir)return 0;
  const char* path=env->GetStringUTFChars(modelPath,nullptr);
  if(!path){env->ReleaseStringUTFChars(directory,dir);return 0;}
  char error[2048]={};
  Audio8Handle* handle=audio8_create_mnn_incremental(dir,path,arThreads,codecThreads,useOpenCl?1:0,lowPrecision?1:0,error,sizeof(error));
  env->ReleaseStringUTFChars(modelPath,path);env->ReleaseStringUTFChars(directory,dir);
  if(!handle)fail(env,error);return reinterpret_cast<jlong>(handle);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_prts_client_audio_audio8_Audio8Native_mnnRuntimeInfo(JNIEnv* env,jclass) {
  const char* result=audio8_mnn_runtime_info_json();
  if(!result){fail(env,"MNN metadata returned no result");return nullptr;}
  return env->NewStringUTF(result);
}

extern "C" JNIEXPORT jlong JNICALL Java_com_prts_client_audio_audio8_Audio8Native_createQnnSplit(
    JNIEnv* env,jclass,jstring directory,jstring model_path,jstring plugin_path,jstring backend_path,
    jstring dsp_directory,jstring profile_path,jstring cache_directory,jint slow_threads,jint fast_threads) {
  const jstring inputs[]{directory,model_path,plugin_path,backend_path,dsp_directory,profile_path,cache_directory};
  const char* strings[7]{};
  int acquired=0;
  while(acquired<7 && inputs[acquired]) {
    strings[acquired]=env->GetStringUTFChars(inputs[acquired],nullptr);
    if(!strings[acquired])break;
    ++acquired;
  }
  char error[2048]{};
  Audio8Handle* handle=nullptr;
  if(acquired==7)handle=audio8_create_qnn_incremental_split_ar(
      strings[0],strings[1],strings[2],strings[3],strings[4],strings[5],strings[6],slow_threads,fast_threads,error,sizeof(error));
  while(acquired>0) {
    --acquired;
    env->ReleaseStringUTFChars(inputs[acquired],strings[acquired]);
  }
  if(!handle && !env->ExceptionCheck())fail(env,*error?error:"Missing QNN decoder configuration");
  return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jlong JNICALL Java_com_prts_client_audio_audio8_Audio8Native_createMnnSplit(JNIEnv* env,jclass,jstring directory,jstring modelPath,jint slowThreads,jint fastThreads,jint codecThreads,jboolean useOpenCl,jboolean lowPrecision,jboolean keepGpuState) {
  const char* dir=env->GetStringUTFChars(directory,nullptr);if(!dir)return 0;
  const char* path=env->GetStringUTFChars(modelPath,nullptr);if(!path){env->ReleaseStringUTFChars(directory,dir);return 0;}
  char error[2048]={};
  Audio8Handle* handle=audio8_create_mnn_incremental_split_ar_with_state_policy(dir,path,slowThreads,fastThreads,codecThreads,useOpenCl?1:0,lowPrecision?1:0,keepGpuState?1:0,error,sizeof(error));
  env->ReleaseStringUTFChars(modelPath,path);env->ReleaseStringUTFChars(directory,dir);
  if(!handle)fail(env,error);return reinterpret_cast<jlong>(handle);
}

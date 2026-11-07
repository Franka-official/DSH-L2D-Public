#include "audio8_c_api.h"
#include "audio8_engine.h"
#include "audio8_incremental_codec.h"
#include "MnnIncrementalCodec.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <stdexcept>

struct Audio8Handle {
  enum class DecoderKind {LegacyOrt,IncrementalOrt,IncrementalMnn,IncrementalQnn};
  std::shared_ptr<audio8::SchedulingProfile> scheduling;
  audio8::Engine engine;
  std::unique_ptr<audio8::IncrementalCodec> incremental;
  bool qnn_decoder=false;
  std::unique_ptr<audio8::MnnIncrementalCodec> mnn_incremental;
  audio8::Result last;
  std::string json="{}";
  std::string prefix_json="{}";
  bool pipeline=false;
  int ar_threads=1,fast_ar_threads=1,codec_threads=1;
  Audio8Handle(const char* directory,int threads,bool use_incremental=false):Audio8Handle(directory,threads,threads,use_incremental) {}
  Audio8Handle(const char* directory,int arThreads,int codecThreads,bool use_incremental,bool auto_profile=false):Audio8Handle(directory,arThreads,codecThreads,use_incremental?DecoderKind::IncrementalOrt:DecoderKind::LegacyOrt,auto_profile,false,false) {}
  Audio8Handle(const char* directory,int arThreads,int codecThreads,DecoderKind kind,bool auto_profile,bool use_opencl,bool low_precision,const char* codec_model_path=nullptr,int fastThreads=0,bool keep_gpu_state=true,const audio8::QnnDecoderOptions* qnn=nullptr):scheduling(audio8::DetectSchedulingProfile(auto_profile,std::max(1,arThreads),std::max(1,codecThreads))),engine(directory,std::max(1,arThreads),fastThreads>0?fastThreads:std::max(1,arThreads),true,kind==DecoderKind::LegacyOrt,scheduling),ar_threads(std::max(1,arThreads)),fast_ar_threads(fastThreads>0?fastThreads:std::max(1,arThreads)),codec_threads(kind==DecoderKind::LegacyOrt?std::max(1,arThreads):std::max(1,codecThreads)) {
    if(kind==DecoderKind::IncrementalOrt || kind==DecoderKind::IncrementalQnn) {
      qnn_decoder=kind==DecoderKind::IncrementalQnn;
      const std::string graph=qnn_decoder?codec_model_path:std::string(directory)+"/codec_incremental.onnx";
      incremental=std::make_unique<audio8::IncrementalCodec>(graph,codec_threads,scheduling,qnn);
      engine.SetDecoderBackend([this](const std::vector<int64_t>& codes,int64_t frames){return incremental->Decode(codes,frames);});
    } else if(kind==DecoderKind::IncrementalMnn) {
      audio8::MnnIncrementalCodecOptions options;options.backend=use_opencl?3:0;options.threads=codec_threads;options.low_precision=low_precision;options.keep_gpu_state=keep_gpu_state;
      if(!codec_model_path||!*codec_model_path)throw std::runtime_error("MNN codec model path is required");
      mnn_incremental=std::make_unique<audio8::MnnIncrementalCodec>(codec_model_path,options);
      engine.SetDecoderBackend([this](const std::vector<int64_t>& codes,int64_t frames){return mnn_incremental->Decode(codes.data(),static_cast<int>(frames));});
    }
  }
};
namespace {
void Error(char* buffer,size_t capacity,const char* text) {
  if(buffer && capacity) {std::strncpy(buffer,text,capacity-1);buffer[capacity-1]=0;}
}
std::string Metrics(const audio8::Result& value,int positions,int prefix,bool mnn_backend=false) {
  double squares=0,peak=0; for(float sample:value.pcm) {squares+=static_cast<double>(sample)*sample;peak=std::max(peak,static_cast<double>(std::abs(sample)));}
  std::ostringstream out;out << std::setprecision(12)
    << "{\"event\":\"result\",\"load_s\":" << value.load_s << ",\"generation_s\":" << value.generation_s
    << ",\"ar_s\":" << value.ar_s << ",\"decoder_s\":" << value.decoder_s << ",\"decoder_calls\":" << value.decoder_calls
    << ",\"first_float_pcm_s\":" << value.first_pcm_s << ",\"first_playable_500ms_s\":" << value.first_playable_500ms_s
    << ",\"first_payload_samples\":" << value.first_payload_samples << ",\"pcm_samples\":" << value.pcm.size()
    << ",\"actual_audio_s\":" << value.pcm.size()/44100.0 << ",\"generated_frames\":" << value.generated_frames
    << ",\"eos\":" << (value.eos?"true":"false") << ",\"capped\":" << (value.capped?"true":"false")
    << ",\"cancelled\":" << (value.cancelled?"true":"false") << ",\"prompt_positions\":" << positions
    << ",\"prefix_positions_supplied\":" << prefix << ",\"reused_prefix_positions\":" << value.reused_prefix_positions
    << ",\"cached_prefix_positions\":" << value.cached_prefix_positions << ",\"prefill_positions_executed\":" << value.prefill_positions_executed
    << ",\"prefix_restore_s\":" << value.prefix_restore_s
    << ",\"pipeline_enabled\":" << (value.pipeline_enabled?"true":"false")
    << ",\"pipeline_peak_queued_jobs\":" << value.pipeline_peak_queued_jobs << ",\"pipeline_ar_queue_wait_s\":" << value.pipeline_ar_queue_wait_s
    << ",\"codec_mode\":\"" << value.codec_mode << "\",\"codec_kv_window\":" << value.codec_kv_window << ",\"codec_state_tensors\":" << value.codec_state_tensors
    << ",\"decoder_context_replay_frames\":" << value.decoder_context_replay_frames << ",\"decoder_guard_frames_effective\":" << value.decoder_guard_frames_effective
    << ",\"decoder_state_reset_per_request\":" << (value.decoder_state_reset_per_request?"true":"false") << ",\"codec_load_s\":" << value.codec_load_s
    << ",\"ar_threads\":" << (value.ar_slow_threads==value.ar_fast_threads?std::to_string(value.ar_threads):"null") << ",\"ar_slow_threads\":" << value.ar_slow_threads << ",\"ar_fast_threads\":" << value.ar_fast_threads << ",\"codec_threads\":" << value.codec_threads << ",\"chunk_frames\":" << value.chunk_frames
    << ",\"ar_intra_op_allow_spinning\":false,\"codec_intra_op_allow_spinning\":" << (mnn_backend?"null":"false")
    << ",\"inter_op_allow_spinning\":" << (mnn_backend?"null":"false") << ",\"ort_ar_inter_op_allow_spinning\":false"
    << ",\"prefill_s\":" << value.prefill_s << ",\"step_s\":" << value.step_s << ",\"prefill_thread_cpu_s\":" << value.prefill_thread_cpu_s << ",\"step_thread_cpu_s\":" << value.step_thread_cpu_s
    << ",\"ar_thread_cpu_s\":" << value.ar_thread_cpu_s << ",\"slow_call_thread_cpu_s\":" << value.slow_call_thread_cpu_s << ",\"fast_call_thread_cpu_s\":" << value.fast_call_thread_cpu_s
    << ",\"native_slow_calls\":" << value.native_slow_calls << ",\"native_fast_calls\":" << value.native_fast_calls << ",\"native_codec_calls\":" << value.native_codec_calls
    << ",\"ar_slow_operation_end_cpu_counts\":" << audio8::CpuHistogramJson(value.slow_end_cpu_counts) << ",\"ar_fast_operation_end_cpu_counts\":" << audio8::CpuHistogramJson(value.fast_end_cpu_counts)
    << ",\"scheduling_profile\":" << value.scheduling_profile_json << ",\"ar_scheduling\":" << value.ar_scheduling_json << ",\"codec_scheduling\":" << value.codec_scheduling_json
    << ",\"pcm_rms\":" << (value.pcm.empty()?0:std::sqrt(squares/value.pcm.size())) << ",\"pcm_peak\":" << peak
    << ",\"sample_rate\":44100,\"rng\":\"std::mt19937_64\"}";
  return out.str();
}
}
extern "C" {
void audio8_default_config(Audio8GenerateConfig* config) {
  if(config) *config={32,12,128,1,1,42,0.7,0.9,50};
}
Audio8Handle* audio8_create(const char* directory,int threads,char* error,size_t size) {
  try {if(!directory) throw std::runtime_error("Missing model directory"); return new Audio8Handle(directory,threads);}
  catch(const std::exception& e) {Error(error,size,e.what());return nullptr;}
}
Audio8Handle* audio8_create_incremental(const char* directory,int threads,char* error,size_t size) {
  try {if(!directory) throw std::runtime_error("Missing model directory");return new Audio8Handle(directory,threads,true);}
  catch(const std::exception& e){Error(error,size,e.what());return nullptr;}
}
Audio8Handle* audio8_create_incremental_tuned(const char* directory,int arThreads,int codecThreads,char* error,size_t size) {
  try {
    if(!directory)throw std::runtime_error("Missing model directory");
    if(arThreads<1 || codecThreads<1)throw std::runtime_error("AR and codec thread counts must be positive");
    return new Audio8Handle(directory,arThreads,codecThreads,true);
  }catch(const std::exception& e){Error(error,size,e.what());return nullptr;}
}
Audio8Handle* audio8_create_incremental_profiled(const char* directory,int arThreads,int codecThreads,int auto_profile,char* error,size_t size) {
  try {
    if(!directory||arThreads<1||codecThreads<1||(auto_profile!=0&&auto_profile!=1))throw std::runtime_error("Invalid profiled codec configuration");
    return new Audio8Handle(directory,arThreads,codecThreads,true,auto_profile!=0);
  }catch(const std::exception& e){Error(error,size,e.what());return nullptr;}
}
Audio8Handle* audio8_create_mnn_incremental(const char* directory,const char* codec_model_path,int arThreads,int codecThreads,int use_opencl,int low_precision,char* error,size_t size) {
  try {
    if(!directory||!codec_model_path||!*codec_model_path||arThreads<1||codecThreads<1||(use_opencl!=0&&use_opencl!=1)||(low_precision!=0&&low_precision!=1))throw std::runtime_error("Invalid MNN incremental configuration");
    return new Audio8Handle(directory,arThreads,codecThreads,Audio8Handle::DecoderKind::IncrementalMnn,false,use_opencl!=0,low_precision!=0,codec_model_path);
  }catch(const std::exception& e){Error(error,size,e.what());return nullptr;}
}
Audio8Handle* audio8_create_mnn_incremental_split_ar(const char* directory,const char* codec_model_path,int slowThreads,int fastThreads,int codecThreads,int use_opencl,int low_precision,char* error,size_t size) {
  return audio8_create_mnn_incremental_split_ar_with_state_policy(directory,codec_model_path,slowThreads,fastThreads,codecThreads,use_opencl,low_precision,1,error,size);
}
Audio8Handle* audio8_create_mnn_incremental_split_ar_with_state_policy(const char* directory,const char* codec_model_path,int slowThreads,int fastThreads,int codecThreads,int use_opencl,int low_precision,int keep_gpu_state,char* error,size_t size) {
  try {
    if(!directory||!codec_model_path||!*codec_model_path||slowThreads<1||fastThreads<1||codecThreads<1||(use_opencl!=0&&use_opencl!=1)||(low_precision!=0&&low_precision!=1)||(keep_gpu_state!=0&&keep_gpu_state!=1))throw std::runtime_error("Invalid split AR MNN incremental configuration");
    return new Audio8Handle(directory,slowThreads,codecThreads,Audio8Handle::DecoderKind::IncrementalMnn,false,use_opencl!=0,low_precision!=0,codec_model_path,fastThreads,keep_gpu_state!=0);
  }catch(const std::exception& e){Error(error,size,e.what());return nullptr;}
}
int audio8_prepare_prefix(Audio8Handle* handle,const int64_t* packed,int positions,int prefix,Audio8EventCallback event,void* user,char* error,size_t size) {
  try {
    if(!handle||!packed||positions<1||positions>=2048||prefix<1||prefix>positions)throw std::runtime_error("Invalid immutable prefix input");
    audio8::IntArray input;input.shape={1,11,positions};input.data.assign(packed,packed+static_cast<size_t>(11)*positions);
    auto log=[&](const audio8::Event& value){if(event){std::ostringstream out;out<<std::setprecision(12)<<"{\"event\":\""<<value.type<<"\",\"elapsed_s\":"<<value.elapsed_s<<",\"generated_frames\":0,\"pcm_samples\":0}";event(out.str().c_str(),user);}};
    const auto result=handle->engine.PreparePrefix(input,prefix,log);
    std::ostringstream out;out<<std::setprecision(12)<<"{\"event\":\"prefix_prepared\",\"prefix_positions\":"<<result.prefix_positions<<",\"prefill_positions_executed\":"<<result.prefill_positions_executed<<",\"reused_prefix_positions\":"<<result.reused_prefix_positions
      <<",\"prefill_s\":"<<result.prefill_s<<",\"thread_cpu_s\":"<<result.thread_cpu_s<<",\"slow_calls\":"<<result.slow_calls<<",\"fast_calls\":"<<result.fast_calls<<",\"decoder_calls\":"<<result.decoder_calls
      <<",\"ar_slow_threads\":"<<result.ar_slow_threads<<",\"ar_fast_threads\":"<<result.ar_fast_threads<<",\"generated_frames\":0,\"pcm_samples\":0,\"ar_operation_end_cpu_counts\":"<<audio8::CpuHistogramJson(result.slow_end_cpu_counts)<<",\"scheduling_profile\":"<<result.scheduling_profile_json<<",\"ar_scheduling\":"<<result.ar_scheduling_json<<'}';
    handle->prefix_json=out.str();return 0;
  }catch(const std::exception& e){Error(error,size,e.what());if(handle)handle->prefix_json="{\"event\":\"prefix_error\"}";return 1;}
}
const char* audio8_last_prefix_json(const Audio8Handle* handle){return handle?handle->prefix_json.c_str():"{}";}
const char* audio8_affinity_probe_json(void){static thread_local std::string result;try{result=audio8::SelfAffinityProbeJson();}catch(const std::exception& e){result="{\"event\":\"affinity_probe_error\",\"passed\":false}";}return result.c_str();}
Audio8Handle* audio8_create_qnn_incremental_split_ar(const char* directory,const char* model_path,
    const char* plugin_path,const char* backend_path,const char* dsp_directory,const char* profile_path,const char* cache_directory,
    int slow_threads,int fast_threads,char* error,size_t size) {
  try {
    if(!directory || !*directory || !model_path || !*model_path || !plugin_path || !*plugin_path ||
       !backend_path || !*backend_path || !dsp_directory || !*dsp_directory || slow_threads<1 || fast_threads<1)
      throw std::runtime_error("Invalid split AR QNN decoder configuration");
#ifdef DSH_AUDIO8_QNN
    const audio8::QnnDecoderOptions qnn{plugin_path,backend_path,dsp_directory,profile_path?profile_path:"",cache_directory?cache_directory:""};
    return new Audio8Handle(directory,slow_threads,1,Audio8Handle::DecoderKind::IncrementalQnn,
        false,false,false,model_path,fast_threads,true,&qnn);
#else
    (void)profile_path;
    (void)cache_directory;
    throw std::runtime_error("QNN Audio8 decoder is disabled in this build");
#endif
  } catch(const std::exception& e) {
    Error(error,size,e.what());
    return nullptr;
  }
}
int audio8_generate(Audio8Handle* handle,const int64_t* packed,int positions,int prefix,
                    const Audio8GenerateConfig* requested,Audio8PcmCallback pcm,Audio8EventCallback event,
                    void* user,char* error,size_t size) {
  int status=0;
  try {
    if(!handle || !packed || positions<1 || positions>=2048 || prefix<0 || prefix>positions) throw std::runtime_error("Invalid generate input");
    Audio8GenerateConfig config;audio8_default_config(&config);if(requested) config=*requested;
    if(handle->mnn_incremental && (!config.streaming || config.chunk_frames!=handle->mnn_incremental->ChunkFrames()))throw std::runtime_error("MNN incremental codec requires streaming chunk_frames matching its fixed model");
    if(handle->qnn_decoder && (!config.streaming || config.chunk_frames!=12))
      throw std::runtime_error("QNN C12 incremental codec requires streaming chunk_frames=12");
    audio8::Options options;options.max_frames=config.max_frames;options.chunk_frames=config.chunk_frames;
    options.prefix_positions=prefix;
    options.pipeline_codec=handle->pipeline;
    options.incremental_codec=handle->incremental!=nullptr||handle->mnn_incremental!=nullptr;
    options.context_frames=config.context_frames;options.guard_frames=config.guard_frames;options.streaming=config.streaming!=0;
    options.seed=config.seed;options.temperature=config.temperature;options.top_p=config.top_p;options.top_k=config.top_k;
    audio8::IntArray prompt;prompt.shape={1,11,positions};prompt.data.assign(packed,packed+static_cast<size_t>(11)*positions);
    handle->last={};handle->json="{\"event\":\"running\"}";
    // Keep immutable AR prefix state, but never share codec history between
    // utterances. Reset also clears a preceding request's ORT termination.
    if(handle->incremental)handle->incremental->Reset();
    if(handle->mnn_incremental)handle->mnn_incremental->Reset();
    auto log=[&](const audio8::Event& value) {
      if(!event) return;std::ostringstream out;out << std::setprecision(12)
        << "{\"event\":\"" << value.type << "\",\"elapsed_s\":" << value.elapsed_s
        << ",\"stage_s\":" << value.stage_s << ",\"frames\":" << value.frames
        << ",\"pcm_samples\":" << value.samples << ",\"window_frames\":" << value.window_frames << "}";
      event(out.str().c_str(),user);
    };
    handle->last=handle->engine.Generate(prompt,options,log,[&](const float* data,size_t count,int rate) {
      return !pcm || pcm(data,count,rate,0,user)!=0;
    });
    handle->last.ar_slow_threads=handle->ar_threads;handle->last.ar_fast_threads=handle->fast_ar_threads;
    handle->last.ar_threads=handle->ar_threads==handle->fast_ar_threads?handle->ar_threads:0;handle->last.codec_threads=handle->codec_threads;
    if(handle->incremental) {
      if(handle->qnn_decoder)handle->last.codec_mode="qnn_incremental_kv_conv";
      handle->last.codec_load_s=handle->incremental->LoadSeconds();
      handle->last.load_s+=handle->last.codec_load_s;
      handle->last.decoder_state_reset_per_request=true;
      handle->last.codec_scheduling_json=handle->incremental->RuntimeJson();
      if(!handle->last.cancelled) {
        if(handle->incremental->FramesProcessed()!=handle->last.generated_frames || handle->last.pcm.size()!=static_cast<size_t>(handle->last.generated_frames)*2048)
          throw std::runtime_error("Incremental codec dropped/replayed frames or PCM samples");
        if(!handle->incremental->Flush().empty())throw std::runtime_error("Unexpected incremental codec flush tail");
      }
    }
    if(handle->mnn_incremental) {
      handle->last.codec_mode="mnn_incremental_kv_conv";
      handle->last.codec_load_s=handle->mnn_incremental->LoadSeconds();
      handle->last.load_s+=handle->last.codec_load_s;
      handle->last.decoder_state_reset_per_request=true;
      if(!handle->last.cancelled) {
        if(handle->mnn_incremental->FramesProcessed()!=handle->last.generated_frames || handle->last.pcm.size()!=static_cast<size_t>(handle->last.generated_frames)*2048)
          throw std::runtime_error("MNN incremental codec dropped/replayed frames or PCM samples");
        if(!handle->mnn_incremental->Flush().empty())throw std::runtime_error("Unexpected MNN incremental flush tail");
      }
    }
    handle->json=Metrics(handle->last,positions,prefix,handle->mnn_incremental!=nullptr);
    if(handle->mnn_incremental)handle->json.insert(handle->json.size()-1,",\"mnn_codec\":"+handle->mnn_incremental->RuntimeJson());
    if(handle->qnn_decoder)handle->json.insert(handle->json.size()-1,",\"qnn_codec\":"+handle->incremental->QnnRuntimeJson());
    status=handle->last.cancelled?130:0;
  } catch(const std::exception& e) {
    Error(error,size,e.what());if(handle) handle->json="{\"event\":\"error\"}";status=1;
  } catch(...) {Error(error,size,"Unknown native error");status=1;}
  if(pcm) pcm(nullptr,0,44100,1,user);
  return status;
}
void audio8_cancel(Audio8Handle* handle) {if(handle) {handle->engine.Cancel();if(handle->incremental)handle->incremental->Cancel();if(handle->mnn_incremental)handle->mnn_incremental->Cancel();}}
void audio8_set_pipeline(Audio8Handle* handle,int enabled) {if(handle) handle->pipeline=enabled!=0;}
void audio8_reset(Audio8Handle* handle) {if(handle) {handle->engine.Reset();if(handle->incremental)handle->incremental->Reset();if(handle->mnn_incremental)handle->mnn_incremental->Reset();handle->last={};handle->json="{}";handle->prefix_json="{}";}}
void audio8_destroy(Audio8Handle* handle) {delete handle;}
const char* audio8_last_result_json(const Audio8Handle* handle) {return handle?handle->json.c_str():"{}";}
int audio8_save_last(Audio8Handle* handle,const char* prefix,char* error,size_t size) {
  try {
    if(!handle || !prefix || handle->last.pcm.empty()) throw std::runtime_error("No generated PCM to save");
    const std::string out(prefix);
    audio8::SaveInt64Npy(out+".codes.npy",handle->last.codes,{10,handle->last.generated_frames});
    audio8::SaveFloatNpy(out+".float32.npy",handle->last.pcm);audio8::SavePcm16Wav(out+".wav",handle->last.pcm);return 0;
  } catch(const std::exception& e) {Error(error,size,e.what());return 1;}
}
int audio8_save_wav(Audio8Handle* handle,const char* path,char* error,size_t size) {
  try {
    if(!handle || !path || handle->last.pcm.empty()) throw std::runtime_error("No generated PCM to save");
    audio8::SavePcm16Wav(path,handle->last.pcm);return 0;
  } catch(const std::exception& e) {Error(error,size,e.what());return 1;}
}
}

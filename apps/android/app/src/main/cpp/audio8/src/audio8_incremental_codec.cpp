#include "audio8_incremental_codec.h"
#include "audio8_qnn_context_cache.h"
#include <onnxruntime_cxx_api.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <map>
#include <stdexcept>
#include <iterator>
#include <sched.h>
#include <sstream>
#include <iomanip>
#ifdef DSH_AUDIO8_QNN
#include <cstdlib>
#include <unordered_map>
#endif

namespace audio8 {
namespace {
size_t ElementBytes(ONNXTensorElementDataType type) {
  if(type==ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16)return 2;
  if(type==ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)return 4;
  if(type==ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64)return 8;
  if(type==ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL)return 1;
  throw std::runtime_error("Unsupported incremental codec tensor type");
}
bool Starts(const std::string& value,const char* prefix) {return value.rfind(prefix,0)==0;}

#ifdef DSH_AUDIO8_QNN
constexpr char kQnnLibrary[]="QNNExecutionProvider";
std::atomic_flag qnn_library_in_use=ATOMIC_FLAG_INIT;
class QnnLibraryLease {
 public:
  QnnLibraryLease() {
    if(qnn_library_in_use.test_and_set())throw std::runtime_error("A QNN decoder already owns the ORT plugin");
  }
  ~QnnLibraryLease(){qnn_library_in_use.clear();}
};

// ORT registers EP libraries on its process-wide Env. Keep the registration
// and DSP search path alive until the QNN session and its tensors are gone.
class QnnRegistration {
 public:
  QnnRegistration(Ort::Env& env,const QnnDecoderOptions& options)
      :env_(env) {
    if(const char* old=std::getenv("ADSP_LIBRARY_PATH")) {
      had_dsp_path_=true;
      old_dsp_path_=old;
    }
    if(setenv("ADSP_LIBRARY_PATH",options.dsp_directory.c_str(),1)!=0)
      throw std::runtime_error("Cannot set QNN DSP library path");
    try {
      env_.RegisterExecutionProviderLibrary(kQnnLibrary,options.plugin_path.c_str());
    } catch(...) {
      RestoreDspPath();
      throw;
    }
  }
  ~QnnRegistration() {
    try {env_.UnregisterExecutionProviderLibrary(kQnnLibrary);} catch(...) {}
    RestoreDspPath();
  }
 private:
  void RestoreDspPath() noexcept {
    if(had_dsp_path_)setenv("ADSP_LIBRARY_PATH",old_dsp_path_.c_str(),1);
    else unsetenv("ADSP_LIBRARY_PATH");
  }
  QnnLibraryLease lease_;
  Ort::Env& env_;
  bool had_dsp_path_=false;
  std::string old_dsp_path_;
};

void EnableStrictQnn(Ort::Env& env,Ort::SessionOptions& session,const QnnDecoderOptions& qnn) {
  const auto advertised=env.GetEpDevices();
  std::vector<Ort::ConstEpDevice> devices;
  for(const auto& device:advertised) {
    if(std::string(device.EpName())==kQnnLibrary &&
       device.Device().Type()==OrtHardwareDeviceType_NPU)devices.push_back(device);
  }
  if(devices.size()!=1) {
    std::ostringstream error;
    error<<"Expected exactly one QNN NPU device, matched "<<devices.size()
         <<" of "<<advertised.size()<<" advertised EP devices";
    for(const auto& device:advertised)error<<" "<<device.EpName()<<":"<<device.Device().Type();
    throw std::runtime_error(error.str());
  }
  session.AddConfigEntry("session.disable_cpu_ep_fallback","1");
  std::unordered_map<std::string,std::string> options{
    {"backend_path",qnn.backend_path},
    {"offload_graph_io_quantization","0"},
    {"htp_performance_mode","balanced"},
    {"profiling_level",qnn.profile_path.empty()?"off":"basic"},
  };
  if(!qnn.profile_path.empty())options["profiling_file_path"]=qnn.profile_path;
  session.AppendExecutionProvider_V2(env,devices,options);
}
#endif
}
struct IncrementalCodec::Impl {
  std::shared_ptr<SchedulingProfile> scheduling;
  struct Spec {std::string name;ONNXTensorElementDataType type;std::vector<int64_t> shape;int output_index=-1;bool kv=false;};
  Ort::Env env{nullptr};Ort::MemoryInfo memory{nullptr};Ort::RunOptions run{nullptr};
  std::unique_ptr<Ort::AllocatorWithDefaultOptions> allocator;
#ifdef DSH_AUDIO8_QNN
  std::unique_ptr<QnnRegistration> qnn_registration;
#endif
  std::unique_ptr<Ort::Session> session;
  std::vector<std::string> input_names,output_names;
  std::vector<const char*> input_pointers,output_pointers;
  std::vector<Spec> state_specs;std::vector<Ort::Value> states;
  std::map<std::string,size_t> state_index;
  std::atomic<bool> cancelled{false};bool failed=false;
  int64_t offset=0;size_t k0_index=0;int pcm_index=-1;double load_s=0;uint8_t empty_byte=0;
  int configured_threads=1;
  static constexpr int64_t kQnnChunk=12;
  static constexpr int64_t kKvWindow=127;
  bool qnn_enabled=false, qnn_profile=false, partial_final_step=false;
  QnnContextCacheResult qnn_cache;
  size_t state_update_calls=0;
  std::vector<double> graph_run_ms;
  std::vector<int> session_new_tids;
  std::map<int,double> worker_cpu_begin;
  std::map<int,int64_t> operation_end_cpus;
  double caller_thread_cpu_s=0;
  std::string creation_affinity="{}",creation_threads="[]",first_decode_before="{}",last_decode_affinity="{}";
  Impl(const std::string& graph,int threads,std::shared_ptr<SchedulingProfile> profile,const QnnDecoderOptions* qnn):scheduling(profile?std::move(profile):DetectSchedulingProfile(false,1,threads)),configured_threads(std::max(1,threads)) {
    const auto start=std::chrono::steady_clock::now();
    const auto* base=OrtGetApiBase();const auto* api=base->GetApi(ORT_API_VERSION);
    if(!api)throw std::runtime_error("ORT library does not support requested API");
    Ort::InitApi(api);allocator=std::make_unique<Ort::AllocatorWithDefaultOptions>();env=Ort::Env(ORT_LOGGING_LEVEL_WARNING,"audio8-incremental");
    memory=Ort::MemoryInfo::CreateCpu(OrtArenaAllocator,OrtMemTypeDefault);run=Ort::RunOptions();
    Ort::SessionOptions options;options.SetIntraOpNumThreads(std::max(1,threads));options.SetInterOpNumThreads(1);
    options.AddConfigEntry("session.intra_op.allow_spinning","0");
    options.AddConfigEntry("session.inter_op.allow_spinning","0");
    options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);options.SetLogSeverityLevel(3);
    if(qnn) {
#ifdef DSH_AUDIO8_QNN
      qnn_registration=std::make_unique<QnnRegistration>(env,*qnn);
      EnableStrictQnn(env,options,*qnn);
      qnn_enabled=true;
      qnn_profile=!qnn->profile_path.empty();
#else
      throw std::runtime_error("QNN Audio8 decoder is disabled in this build");
#endif
    }
    const auto before_tids=CurrentProcessThreads();
    {
      AffinityGuard creator_guard(scheduling,SchedulingRole::Codec);
#ifdef DSH_AUDIO8_QNN
      if(qnn)session=OpenQnnContextSession(env,options,graph,*qnn,qnn_cache);
      else
#endif
      session=std::make_unique<Ort::Session>(env,graph.c_str(),options);
      const auto after_tids=CurrentProcessThreads();
      std::set_difference(after_tids.begin(),after_tids.end(),before_tids.begin(),before_tids.end(),std::back_inserter(session_new_tids));
      creation_threads=ObserveThreadsJson(session_new_tids);creator_guard.Restore();creation_affinity=creator_guard.Json();
    }
    for(size_t i=0;i<session->GetOutputCount();++i)output_names.emplace_back(session->GetOutputNameAllocated(i,*allocator).get());
    for(size_t i=0;i<session->GetInputCount();++i) {
      std::string name=session->GetInputNameAllocated(i,*allocator).get();input_names.push_back(name);
      if(name=="codes" || name=="frame_offset" || name=="attention_mask") {
        if(qnn_enabled) {
          auto type_info=session->GetInputTypeInfo(i);
          auto type=type_info.GetTensorTypeAndShapeInfo();
          const auto shape=name=="codes"?std::vector<int64_t>{1,10,kQnnChunk}:
            name=="attention_mask"?std::vector<int64_t>{1,1,kQnnChunk,kKvWindow+kQnnChunk}:
            std::vector<int64_t>{};
          const auto dtype=name=="attention_mask"?ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL:ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64;
          if(type.GetShape()!=shape || type.GetElementType()!=dtype)
            throw std::runtime_error("Unexpected QNN C12 input: "+name);
        }
        continue;
      }
      auto type_info=session->GetInputTypeInfo(i);auto type=type_info.GetTensorTypeAndShapeInfo();
      Spec spec{name,type.GetElementType(),type.GetShape(),-1,Starts(name,"past_k_") || Starts(name,"past_v_")};
      if(spec.shape.empty())throw std::runtime_error("State unexpectedly scalar: "+name);
      spec.shape[0]=1;
      if(spec.kv) {
        if(spec.shape.size()!=4 || spec.shape[1]!=8 || spec.shape[3]!=64 ||
           (qnn_enabled && spec.shape[2]!=kKvWindow))throw std::runtime_error("Unexpected KV shape");
        spec.shape[2]=qnn_enabled?kKvWindow:0;
      }
      if(qnn_enabled && spec.type!=ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)
        throw std::runtime_error("Unexpected QNN state dtype: "+name);
      for(auto dim:spec.shape)if(dim<0)throw std::runtime_error("Unresolved state shape: "+name);
      std::string output=spec.kv?"next_"+name.substr(5):"next_"+name;
      auto found=std::find(output_names.begin(),output_names.end(),output);
      if(qnn_enabled && spec.kv && found==output_names.end())
        found=std::find(output_names.begin(),output_names.end(),"present_"+name.substr(5));
      if(found==output_names.end())throw std::runtime_error("Missing next state: "+output);
      spec.output_index=static_cast<int>(found-output_names.begin());
      state_index[name]=state_specs.size();state_specs.push_back(std::move(spec));
    }
    if(state_specs.size()!=36 || state_index.find("past_k_0")==state_index.end())throw std::runtime_error("Expected exact 36-state Audio8 graph");
    if(qnn_enabled && (input_names.size()!=39 || std::count_if(state_specs.begin(),state_specs.end(),
       [](const Spec& spec){return spec.kv;})!=16))
      throw std::runtime_error("Expected 16 KV and 20 convolution QNN states");
    k0_index=state_index.at("past_k_0");
    auto pcm=std::find(output_names.begin(),output_names.end(),"audio");
    if(pcm==output_names.end())throw std::runtime_error("Missing audio graph output");
    pcm_index=static_cast<int>(pcm-output_names.begin());
    for(const auto& name:input_names)input_pointers.push_back(name.c_str());
    for(const auto& name:output_names)output_pointers.push_back(name.c_str());
    Reset();load_s=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
  }
  Ort::Value Allocate(const std::vector<int64_t>& shape,ONNXTensorElementDataType type) {
    return Ort::Value::CreateTensor(*allocator,shape.data(),shape.size(),type);
  }
  void Reset() {
    run.UnsetTerminate();cancelled.store(false);failed=false;offset=0;
    partial_final_step=false;state_update_calls=0;graph_run_ms.clear();
    std::vector<Ort::Value> fresh;fresh.reserve(state_specs.size());
    for(const auto& spec:state_specs) {
      auto value=Allocate(spec.shape,spec.type);const size_t bytes=value.GetTensorTypeAndShapeInfo().GetElementCount()*ElementBytes(spec.type);
      if(bytes)std::memset(value.GetTensorMutableData<uint8_t>(),0,bytes);
      fresh.push_back(std::move(value));
    }
    states=std::move(fresh);
    worker_cpu_begin.clear();for(int tid:session_new_tids)worker_cpu_begin[tid]=ObserveThread(tid).cpu_s;
    operation_end_cpus.clear();caller_thread_cpu_s=0;first_decode_before="{}";last_decode_affinity="{}";
  }
  Ort::Value View(Ort::Value& value) {
    auto info=value.GetTensorTypeAndShapeInfo();auto shape=info.GetShape();size_t bytes=info.GetElementCount()*ElementBytes(info.GetElementType());
    void* data=bytes?value.GetTensorMutableData<uint8_t>():&empty_byte;
    return Ort::Value::CreateTensor(memory,data,bytes,shape.data(),shape.size(),info.GetElementType());
  }
  std::vector<float> Decode(const std::vector<int64_t>& codes,int64_t frames) {
    if(failed)throw std::runtime_error("Incremental codec needs Reset after a failed or cancelled step");
    if(frames<1 || codes.size()!=static_cast<size_t>(10*frames))throw std::runtime_error("Expected NEW codes [10,T]");
    if(cancelled.load())throw std::runtime_error("Incremental codec cancelled");
    if(qnn_enabled && (frames>kQnnChunk || partial_final_step))
      throw std::runtime_error("QNN decoder requires 12-frame steps before the final partial step");
    if(first_decode_before=="{}")first_decode_before=ObserveThread().Json();
    AffinityGuard caller_guard(scheduling,SchedulingRole::Codec);const double cpu_begin=CurrentThreadCpuSeconds();
    auto finish_call=[&](){caller_thread_cpu_s+=CurrentThreadCpuSeconds()-cpu_begin;++operation_end_cpus[sched_getcpu()];caller_guard.Restore();last_decode_affinity=caller_guard.Json();};
    try {
      const int64_t graph_frames=qnn_enabled?kQnnChunk:frames;
      const auto old_shape=states[k0_index].GetTensorTypeAndShapeInfo().GetShape();
      const int64_t past=old_shape[2],keys=past+graph_frames;
      std::vector<int64_t> padded_codes;
      const auto* graph_codes=&codes;
      if(graph_frames!=frames) {
        padded_codes.resize(10*graph_frames);
        for(int codebook=0;codebook<10;++codebook)
          std::copy_n(codes.data()+codebook*frames,frames,padded_codes.data()+codebook*graph_frames);
        graph_codes=&padded_codes;
      }
      std::vector<uint8_t> mask(static_cast<size_t>(graph_frames*keys));
      for(int64_t q=0;q<graph_frames;++q)for(int64_t k=0;k<keys;++k) {
        const auto absolute_q=offset+q,absolute_k=offset-past+k;
        mask[static_cast<size_t>(q*keys+k)]=absolute_k<=absolute_q && absolute_k>=std::max<int64_t>(absolute_q-127,0);
      }
      const std::array<int64_t,3> code_shape{1,10,graph_frames};const std::array<int64_t,4> mask_shape{1,1,graph_frames,keys};
      std::vector<Ort::Value> feeds;feeds.reserve(input_names.size());
      for(const auto& name:input_names) {
        if(name=="codes")feeds.push_back(Ort::Value::CreateTensor<int64_t>(memory,const_cast<int64_t*>(graph_codes->data()),graph_codes->size(),code_shape.data(),3));
        else if(name=="frame_offset")feeds.push_back(Ort::Value::CreateTensor<int64_t>(memory,&offset,1,nullptr,0));
        else if(name=="attention_mask")feeds.push_back(Ort::Value::CreateTensor(memory,mask.data(),mask.size(),mask_shape.data(),4,ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL));
        else feeds.push_back(View(states[state_index.at(name)]));
      }
      const auto graph_begin=std::chrono::steady_clock::now();
      auto values=session->Run(run,input_pointers.data(),feeds.data(),feeds.size(),output_pointers.data(),output_pointers.size());
      if(qnn_enabled)graph_run_ms.push_back(
        std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-graph_begin).count());
      auto pcm_info=values[pcm_index].GetTensorTypeAndShapeInfo();const size_t count=pcm_info.GetElementCount();
      if(pcm_info.GetElementType()!=ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || count!=static_cast<size_t>(graph_frames)*2048)throw std::runtime_error("Incremental codec output length/type mismatch");
      const float* pcm=values[pcm_index].GetTensorData<float>();std::vector<float> audio(pcm,pcm+frames*2048);
      for(float value:audio)if(!std::isfinite(value))throw std::runtime_error("Incremental codec produced nonfinite PCM");
      if(graph_frames!=frames) {
        partial_final_step=true;offset+=frames;finish_call();return audio;
      }
      std::vector<Ort::Value> next;next.reserve(state_specs.size());
      for(const auto& spec:state_specs) {
        auto& value=values[spec.output_index];auto info=value.GetTensorTypeAndShapeInfo();auto shape=info.GetShape();
        if(info.GetElementType()!=spec.type)throw std::runtime_error("Next-state dtype mismatch: "+spec.name);
        if(qnn_enabled) {
          auto expected=spec.shape;
          if(spec.kv)expected[2]=kKvWindow+kQnnChunk;
          if(shape!=expected)throw std::runtime_error("Unexpected QNN output state shape: "+spec.name);
        }
        if(spec.kv && shape[2]>127) {
          const int64_t total=shape[2];shape[2]=127;auto cropped=Allocate(shape,spec.type);
          const auto* source=value.GetTensorData<uint8_t>();auto* target=cropped.GetTensorMutableData<uint8_t>();
          const size_t elem=ElementBytes(spec.type),row=127*64*elem;
          for(int head=0;head<8;++head)std::memcpy(target+head*row,source+(static_cast<size_t>(head)*total+total-127)*64*elem,row);
          next.push_back(std::move(cropped));
        } else next.push_back(std::move(value));
      }
      feeds.clear();states=std::move(next);offset+=frames;
      if(qnn_enabled)++state_update_calls;
      finish_call();return audio;
    }catch(...) {finish_call();failed=true;throw;}
  }
  std::string RuntimeJson()const {
    double worker_cpu_s=0;bool masks_match=scheduling->Enabled()&&session_new_tids.size()==static_cast<size_t>(std::max(0,configured_threads-1));
    for(int tid:session_new_tids){auto value=ObserveThread(tid);auto found=worker_cpu_begin.find(tid);if(found!=worker_cpu_begin.end())worker_cpu_s+=std::max(0.0,value.cpu_s-found->second);masks_match=masks_match&&value.allowed==scheduling->codec_cpus&&value.error==0;}
    std::ostringstream out;out<<std::setprecision(12)<<"{\"profile\":"<<scheduling->Json()<<",\"session_creation_affinity\":"<<creation_affinity<<",\"session_creation_new_threads\":"<<creation_threads
      <<",\"current_session_new_thread_observations\":"<<ObserveThreadsJson(session_new_tids)<<",\"expected_intra_worker_count\":"<<std::max(0,configured_threads-1)<<",\"observed_new_thread_count\":"<<session_new_tids.size()
      <<",\"observed_worker_masks_match_requested\":"<<(masks_match?"true":"false")<<",\"observed_worker_cpu_delta_s\":"<<worker_cpu_s<<",\"decoder_caller_thread_cpu_s\":"<<caller_thread_cpu_s
      <<",\"decoder_operation_end_cpu_counts\":"<<CpuHistogramJson(operation_end_cpus)<<",\"first_decode_caller_before\":"<<first_decode_before<<",\"last_decode_affinity\":"<<last_decode_affinity<<'}';return out.str();
  }
  std::string QnnRuntimeJson()const {
    std::ostringstream out;out<<std::setprecision(12);
    if(qnn_enabled) {
      double total=0;
      out<<"{\"backend\":\"htp\",\"provider\":\"QNNExecutionProvider\",\"npu_device_selected\":true"
         <<",\"cpu_fallback_disabled\":true,\"strict_session_ready\":true,\"graph_calls\":"<<graph_run_ms.size()
         <<",\"state_update_calls\":"<<state_update_calls<<",\"state_tensors\":"<<state_specs.size()
         <<",\"chunk_frames\":"<<kQnnChunk<<",\"kv_window\":"<<kKvWindow<<",\"real_frames\":"<<offset
         <<",\"padded_final_step\":"<<(partial_final_step?"true":"false")
         <<",\"context_cache_status\":\""<<qnn_cache.status<<"\",\"context_cache_bytes\":"<<qnn_cache.cache_bytes
         <<",\"session_load_ms\":"<<qnn_cache.session_load_ms<<",\"cold_compile_ms\":"<<qnn_cache.cold_compile_ms
         <<",\"context_cache_load_ms\":"<<qnn_cache.context_cache_load_ms
         <<",\"rejected_cache_ms\":"<<qnn_cache.rejected_cache_ms
         <<",\"ort_version\":\""<<OrtGetApiBase()->GetVersionString()<<"\",\"profiling_level\":\""
         <<(qnn_profile?"basic":"off")<<"\",\"graph_run_ms\":[";
      for(size_t i=0;i<graph_run_ms.size();++i) {
        if(i)out<<',';
        out<<graph_run_ms[i];total+=graph_run_ms[i];
      }
      out<<"],\"graph_run_ms_total\":"<<total<<'}';
    } else out<<"{}";
    return out.str();
  }
};
IncrementalCodec::IncrementalCodec(const std::string& path,int threads,std::shared_ptr<SchedulingProfile> profile,const QnnDecoderOptions* qnn):impl_(std::make_unique<Impl>(path,threads,std::move(profile),qnn)){}
IncrementalCodec::~IncrementalCodec()=default;
std::vector<float> IncrementalCodec::Decode(const std::vector<int64_t>& codes,int64_t frames){return impl_->Decode(codes,frames);}
void IncrementalCodec::Reset(){impl_->Reset();}
void IncrementalCodec::Cancel(){impl_->cancelled.store(true);impl_->run.SetTerminate();}
int64_t IncrementalCodec::FramesProcessed()const{return impl_->offset;}
double IncrementalCodec::LoadSeconds()const{return impl_->load_s;}
std::string IncrementalCodec::RuntimeJson()const{return impl_->RuntimeJson();}
std::string IncrementalCodec::QnnRuntimeJson()const{return impl_->QnnRuntimeJson();}
std::vector<float> IncrementalCodec::Flush(){if(impl_->failed || impl_->cancelled.load())throw std::runtime_error("Cannot flush failed/cancelled codec");return {};}
}

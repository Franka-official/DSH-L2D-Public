#include "audio8_engine.h"
#include <onnxruntime_cxx_api.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <condition_variable>
#include <deque>
#include <exception>
#include <limits>
#include <mutex>
#include <numeric>
#include <random>
#include <stdexcept>
#include <thread>
#include <sched.h>

namespace audio8 {
namespace {
using Clock = std::chrono::steady_clock;
double Seconds(Clock::time_point begin) { return std::chrono::duration<double>(Clock::now()-begin).count(); }
constexpr int kBooks=10, kDim=512, kLayers=24, kHeads=2, kHeadDim=64, kSeq=2048, kHop=2048;
constexpr int kSemanticBegin=65537, kSemanticEnd=69632, kEos=4096;
void InitOrt() {
  const auto* base=OrtGetApiBase();
  const auto* api=base->GetApi(ORT_API_VERSION);
  if (!api) throw std::runtime_error(std::string("ORT headers API is unsupported by library ")+base->GetVersionString());
  Ort::InitApi(api);
}
Ort::SessionOptions SessionOptions(int threads) {
  Ort::SessionOptions options;
  options.SetIntraOpNumThreads(std::max(1,threads)); options.SetInterOpNumThreads(1);
  options.AddConfigEntry("session.intra_op.allow_spinning","0");
  options.AddConfigEntry("session.inter_op.allow_spinning","0");
  options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
  options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL); options.SetLogSeverityLevel(3);
  return options;
}
int Sample(const std::vector<float>& logits,double temperature,double top_p,int top_k,std::mt19937_64& rng) {
  if (logits.empty()) throw std::runtime_error("Empty logits");
  const size_t n=logits.size();
  std::vector<size_t> order(n); std::iota(order.begin(),order.end(),0);
  std::sort(order.begin(),order.end(),[&](size_t a,size_t b){return logits[a]>logits[b];});
  if (!std::isfinite(logits[order[0]])) throw std::runtime_error("No finite logits");
  temperature=std::max(temperature,1e-5); top_p=std::clamp(top_p,1e-5,1.0); top_k=std::clamp(top_k,1,static_cast<int>(n));
  double max=logits[order[0]], denominator=0;
  for (float x:logits) denominator+=std::exp(static_cast<double>(x)-max);
  std::vector<double> filtered(n,-std::numeric_limits<double>::infinity());
  double cumulative=0;
  for (size_t rank=0;rank<n;++rank) {
    const auto index=order[rank]; cumulative+=std::exp(static_cast<double>(logits[index])-max)/denominator;
    if (rank==0 || (rank<static_cast<size_t>(top_k) && cumulative<=top_p)) filtered[index]=logits[index]/temperature;
  }
  const double scaled_max=*std::max_element(filtered.begin(),filtered.end()); double sum=0;
  for (double x:filtered) sum+=std::exp(x-scaled_max);
  std::uniform_real_distribution<double> uniform(0.0,1.0);
  int result=0; double best=-std::numeric_limits<double>::infinity();
  for (size_t i=0;i<n;++i) {
    const double u=std::clamp(uniform(rng),1e-12,1.0-1e-12);
    const double p=std::exp(filtered[i]-scaled_max)/sum;
    const double score=std::log(std::max(p,1e-300))-std::log(-std::log(u));
    if (score>best) {best=score;result=static_cast<int>(i);}
  }
  return result;
}
template<size_t N> Ort::Value FloatTensor(Ort::MemoryInfo& memory,std::vector<float>& values,const std::array<int64_t,N>& shape) {
  return Ort::Value::CreateTensor<float>(memory,values.data(),values.size(),shape.data(),shape.size());
}
std::vector<int64_t> PackFrames(const std::vector<std::array<int64_t,kBooks>>& frames,int start,int end) {
  std::vector<int64_t> result(static_cast<size_t>(kBooks)*(end-start));
  for (int book=0;book<kBooks;++book) for(int t=start;t<end;++t) result[static_cast<size_t>(book)*(end-start)+t-start]=frames[t][book];
  return result;
}
}

struct Engine::Impl {
  std::shared_ptr<SchedulingProfile> scheduling;
  int configured_slow_threads=1,configured_fast_threads=1;
  Ort::Env env{nullptr}; Ort::MemoryInfo memory{nullptr}; Ort::RunOptions run{nullptr};
  std::unique_ptr<Ort::Session> slow,fast,decoder;
  std::vector<float> keys,values,conv,ssm;
  struct PrefixCache {
    int positions=0;
    std::vector<int64_t> prompt;
    std::vector<float> keys,values,conv,ssm,logits,hidden;
  } prefix;
  std::array<std::vector<float>,8> fast_cache;
  std::atomic<bool> cancelled{false}; DecoderBackend external_decoder;
  double load_s=0; std::string version;
  int64_t slow_calls=0,fast_calls=0,codec_calls=0;
  double slow_cpu_s=0,fast_cpu_s=0;
  std::map<int,int64_t> slow_end_cpus,fast_end_cpus;
  void ResetMetrics(){slow_calls=fast_calls=codec_calls=0;slow_cpu_s=fast_cpu_s=0;slow_end_cpus.clear();fast_end_cpus.clear();}
  Impl(const std::string& directory,int threads,bool load_ar,bool load_codec,std::shared_ptr<SchedulingProfile> profile,int fast_threads=0):scheduling(profile?std::move(profile):DetectSchedulingProfile(false,threads,threads)) {
    auto start=Clock::now(); InitOrt(); version=OrtGetApiBase()->GetVersionString();
    env=Ort::Env(ORT_LOGGING_LEVEL_WARNING,"audio8"); memory=Ort::MemoryInfo::CreateCpu(OrtArenaAllocator,OrtMemTypeDefault);
    run=Ort::RunOptions();
    configured_slow_threads=std::max(1,threads);configured_fast_threads=fast_threads>0?fast_threads:configured_slow_threads;
    auto options=SessionOptions(configured_slow_threads);auto fast_options=SessionOptions(configured_fast_threads);
    if (load_ar) {
      slow=std::make_unique<Ort::Session>(env,(directory+"/slow_ar_int8.onnx").c_str(),options);
      fast=std::make_unique<Ort::Session>(env,(directory+"/fast_ar_int8.onnx").c_str(),fast_options);
      keys.resize(static_cast<size_t>(kLayers)*kHeads*kSeq*kHeadDim); values.resize(keys.size());
      conv.resize(static_cast<size_t>(kLayers)*896*4); ssm.resize(static_cast<size_t>(kLayers)*24*32*64);
      for(auto& cache:fast_cache) cache.resize(2*10*64);
    }
    if (load_codec) decoder=std::make_unique<Ort::Session>(env,(directory+"/codec_decoder_fp16.onnx").c_str(),options);
    load_s=Seconds(start);
  }
  void CheckCancelled() const { if(cancelled.load()) throw std::runtime_error("audio8_cancelled"); }
  void ResetWorkingState() {
    cancelled.store(false); run.UnsetTerminate();
    for(auto* v:{&keys,&values,&conv,&ssm}) std::fill(v->begin(),v->end(),0.0f);
  }
  void Reset() { ResetWorkingState(); prefix=PrefixCache{}; }
  bool PrefixMatches(const IntArray& prompt,int positions) const {
    if(positions<=0 || prefix.positions!=positions) return false;
    const auto stride=static_cast<size_t>(prompt.shape.back());
    for(int row=0;row<11;++row)
      if(!std::equal(prefix.prompt.begin()+static_cast<size_t>(row)*positions,
                     prefix.prompt.begin()+static_cast<size_t>(row+1)*positions,
                     prompt.data.begin()+static_cast<size_t>(row)*stride)) return false;
    return true;
  }
  void SavePrefix(const IntArray& prompt,int positions,const std::vector<float>& logits,const std::vector<float>& hidden) {
    // Publish only after all copies succeed: cancellation/error cannot expose
    // a partially captured prefix to a later request.
    PrefixCache next; next.positions=positions;
    next.prompt.resize(static_cast<size_t>(11)*positions);
    const auto stride=static_cast<size_t>(prompt.shape.back());
    for(int row=0;row<11;++row)
      std::copy_n(prompt.data.data()+static_cast<size_t>(row)*stride,positions,
                  next.prompt.data()+static_cast<size_t>(row)*positions);
    const size_t per_head=static_cast<size_t>(positions)*kHeadDim;
    next.keys.resize(static_cast<size_t>(kLayers)*kHeads*per_head); next.values.resize(next.keys.size());
    for(int head=0;head<kLayers*kHeads;++head) {
      std::copy_n(keys.data()+static_cast<size_t>(head)*kSeq*kHeadDim,per_head,next.keys.data()+static_cast<size_t>(head)*per_head);
      std::copy_n(values.data()+static_cast<size_t>(head)*kSeq*kHeadDim,per_head,next.values.data()+static_cast<size_t>(head)*per_head);
    }
    next.conv=conv;next.ssm=ssm;next.logits=logits;next.hidden=hidden;
    CheckCancelled();prefix=std::move(next);
  }
  void RestorePrefix(std::vector<float>& logits,std::vector<float>& hidden) {
    const size_t per_head=static_cast<size_t>(prefix.positions)*kHeadDim;
    for(int head=0;head<kLayers*kHeads;++head) {
      std::copy_n(prefix.keys.data()+static_cast<size_t>(head)*per_head,per_head,keys.data()+static_cast<size_t>(head)*kSeq*kHeadDim);
      std::copy_n(prefix.values.data()+static_cast<size_t>(head)*per_head,per_head,values.data()+static_cast<size_t>(head)*kSeq*kHeadDim);
    }
    conv=prefix.conv;ssm=prefix.ssm;logits=prefix.logits;hidden=prefix.hidden;
  }
  void SlowStep(const std::array<int64_t,11>& column,int position,std::vector<float>& logits,std::vector<float>& hidden) {
    const double cpu_begin=CurrentThreadCpuSeconds();
    CheckCancelled(); if(position<0 || position>=kSeq) throw std::runtime_error("Slow position out of range");
    int64_t pos=position;
    const std::array<int64_t,3> column_shape{1,11,1}; const std::array<int64_t,1> position_shape{1};
    const std::array<int64_t,5> kv_shape{24,1,2,2048,64},ssm_shape{24,1,24,32,64};
    const std::array<int64_t,4> conv_shape{24,1,896,4};
    std::array<Ort::Value,6> inputs={
      Ort::Value::CreateTensor<int64_t>(memory,const_cast<int64_t*>(column.data()),11,column_shape.data(),3),
      Ort::Value::CreateTensor<int64_t>(memory,&pos,1,position_shape.data(),1),
      FloatTensor(memory,keys,kv_shape),FloatTensor(memory,values,kv_shape),FloatTensor(memory,conv,conv_shape),FloatTensor(memory,ssm,ssm_shape)};
    const char* names[]={"codes","position","cache_keys","cache_values","conv_states","ssm_states"};
    const char* outputs[]={"logits","hidden","key_delta","value_delta","next_conv_states","next_ssm_states"};
    auto result=slow->Run(run,names,inputs.data(),inputs.size(),outputs,6);
    logits.assign(result[0].GetTensorData<float>(),result[0].GetTensorData<float>()+4097);
    hidden.assign(result[1].GetTensorData<float>(),result[1].GetTensorData<float>()+512);
    const auto* kd=result[2].GetTensorData<float>(); const auto* vd=result[3].GetTensorData<float>();
    for(int layer=0;layer<24;++layer) for(int head=0;head<2;++head) {
      const size_t src=(static_cast<size_t>(layer)*2+head)*64,dst=((static_cast<size_t>(layer)*2+head)*2048+position)*64;
      std::copy_n(kd+src,64,keys.data()+dst); std::copy_n(vd+src,64,values.data()+dst);
    }
    std::copy_n(result[4].GetTensorData<float>(),conv.size(),conv.data());
    std::copy_n(result[5].GetTensorData<float>(),ssm.size(),ssm.data());
    ++slow_calls;slow_cpu_s+=CurrentThreadCpuSeconds()-cpu_begin;++slow_end_cpus[sched_getcpu()];
  }
  std::vector<float> FastStep(std::vector<float>& hidden,int token,bool use_hidden,int position) {
    const double cpu_begin=CurrentThreadCpuSeconds();
    CheckCancelled(); int64_t id=token,pos=position; bool use=use_hidden;
    const std::array<int64_t,3> hs{1,1,512}; const std::array<int64_t,2> ts{1,1}; const std::array<int64_t,1> scalar{1};
    const std::array<int64_t,4> cache_shape{1,2,10,64};
    std::vector<Ort::Value> inputs; inputs.reserve(12);
    inputs.push_back(FloatTensor(memory,hidden,hs));
    inputs.push_back(Ort::Value::CreateTensor<int64_t>(memory,&id,1,ts.data(),2));
    inputs.push_back(Ort::Value::CreateTensor<bool>(memory,&use,1,scalar.data(),1));
    inputs.push_back(Ort::Value::CreateTensor<int64_t>(memory,&pos,1,scalar.data(),1));
    for(auto& cache:fast_cache) inputs.push_back(FloatTensor(memory,cache,cache_shape));
    const char* names[]={"slow_hidden","token_id","use_slow_hidden","input_pos","cache_key_0","cache_value_0","cache_key_1","cache_value_1","cache_key_2","cache_value_2","cache_key_3","cache_value_3"};
    const char* outputs[]={"logits","key_delta_0","value_delta_0","key_delta_1","value_delta_1","key_delta_2","value_delta_2","key_delta_3","value_delta_3"};
    auto result=fast->Run(run,names,inputs.data(),inputs.size(),outputs,9);
    for(int i=0;i<8;++i) {
      const float* delta=result[i+1].GetTensorData<float>();
      for(int head=0;head<2;++head) std::copy_n(delta+head*64,64,fast_cache[i].data()+(head*10+position)*64);
    }
    ++fast_calls;fast_cpu_s+=CurrentThreadCpuSeconds()-cpu_begin;++fast_end_cpus[sched_getcpu()];
    return {result[0].GetTensorData<float>(),result[0].GetTensorData<float>()+4096};
  }
  std::vector<float> Decode(const std::vector<int64_t>& codes,int64_t frames) {
    CheckCancelled();
    ++codec_calls;
    if(external_decoder) return external_decoder(codes,frames);
    if(!decoder) throw std::runtime_error("No codec backend");
    const std::array<int64_t,3> shape{1,10,frames};
    auto tensor=Ort::Value::CreateTensor<int64_t>(memory,const_cast<int64_t*>(codes.data()),codes.size(),shape.data(),3);
    const char* input[]={"codes"}; const char* output[]={"audio"};
    auto result=decoder->Run(run,input,&tensor,1,output,1);
    const size_t count=result[0].GetTensorTypeAndShapeInfo().GetElementCount();
    const auto* data=result[0].GetTensorData<float>();
    return {data,data+count};
  }
};

Engine::Engine(const std::string& model_dir,int threads,bool load_ar,bool load_codec,std::shared_ptr<SchedulingProfile> profile):impl_(std::make_unique<Impl>(model_dir,threads,load_ar,load_codec,std::move(profile))) {}
Engine::Engine(const std::string& model_dir,int slow_threads,int fast_threads,bool load_ar,bool load_codec,std::shared_ptr<SchedulingProfile> profile):impl_(std::make_unique<Impl>(model_dir,slow_threads,load_ar,load_codec,std::move(profile),fast_threads)) {}
Engine::~Engine()=default;
void Engine::Cancel() { impl_->cancelled.store(true); impl_->run.SetTerminate(); }
void Engine::Reset() { impl_->Reset(); }
void Engine::SetDecoderBackend(DecoderBackend decoder) { impl_->external_decoder=std::move(decoder); }
std::string Engine::Version() const { return impl_->version; }
double Engine::LoadSeconds() const { return impl_->load_s; }
PrefixPreparation Engine::PreparePrefix(const IntArray& prompt,int prefix_positions,Logger logger) {
  if(!impl_->slow)throw std::runtime_error("Slow AR session was not loaded");
  if(prompt.shape.size()<2 || prompt.shape[prompt.shape.size()-2]!=11 || (prompt.shape.size()==3&&prompt.shape[0]!=1))throw std::runtime_error("Prefix must be [1,11,T]");
  const int length=static_cast<int>(prompt.shape.back());
  if(length<1||length>=2048||prefix_positions<1||prefix_positions>length||prompt.data.size()!=static_cast<size_t>(11*length))throw std::runtime_error("Invalid prefix shape/count");
  PrefixPreparation out;out.ar_slow_threads=impl_->configured_slow_threads;out.ar_fast_threads=impl_->configured_fast_threads;out.prefix_positions=prefix_positions;impl_->ResetWorkingState();impl_->ResetMetrics();
  AffinityGuard guard(impl_->scheduling,SchedulingRole::Ar);const auto start=Clock::now();const double cpu_begin=CurrentThreadCpuSeconds();
  std::vector<float> logits,hidden;
  if(impl_->PrefixMatches(prompt,prefix_positions))out.reused_prefix_positions=prefix_positions;
  else {
    impl_->prefix=Impl::PrefixCache{};
    for(int position=0;position<prefix_positions;++position) {
      std::array<int64_t,11> column{};for(int row=0;row<11;++row)column[row]=prompt.data[static_cast<size_t>(row)*length+position];
      impl_->SlowStep(column,position,logits,hidden);++out.prefill_positions_executed;
      if(logger&&(position==0||(position+1)%32==0))logger({"prefix_prefill",Seconds(start),0,0,0,0});
    }
    impl_->SavePrefix(prompt,prefix_positions,logits,hidden);
  }
  out.prefill_s=Seconds(start);out.thread_cpu_s=CurrentThreadCpuSeconds()-cpu_begin;
  out.slow_calls=impl_->slow_calls;out.fast_calls=impl_->fast_calls;out.decoder_calls=impl_->codec_calls;out.slow_end_cpu_counts=impl_->slow_end_cpus;
  guard.Restore();out.ar_scheduling_json=guard.Json();out.scheduling_profile_json=impl_->scheduling->Json();return out;
}
std::vector<float> Engine::Decode(const IntArray& codes) {
  impl_->cancelled.store(false); impl_->run.UnsetTerminate();
  if(codes.shape.size()<2 || codes.shape[codes.shape.size()-2]!=10) throw std::runtime_error("Codec codes must be [10,T] or [1,10,T]");
  return impl_->Decode(codes.data,codes.shape.back());
}
Result Engine::Generate(const IntArray& prompt,const Options& options,Logger logger,PcmCallback callback) {
  if(!impl_->slow || !impl_->fast) throw std::runtime_error("AR sessions were not loaded");
  if(prompt.shape.size()<2 || prompt.shape[prompt.shape.size()-2]!=11 || (prompt.shape.size()==3 && prompt.shape[0]!=1)) throw std::runtime_error("Prompt must be [1,11,T]");
  int length=static_cast<int>(prompt.shape.back());
  if(length<1 || length>=2048 || prompt.data.size()!=static_cast<size_t>(11*length)) throw std::runtime_error("Invalid prompt length");
  if(options.max_frames<1 || options.chunk_frames<1 || options.context_frames<0 || options.guard_frames<0 || options.prefix_positions<0 || options.prefix_positions>length) throw std::runtime_error("Invalid generation options");
  const int limit=std::min(options.max_frames,2048-length);
  Result out; out.load_s=impl_->load_s; out.ort_version=impl_->version;
  out.ar_slow_threads=impl_->configured_slow_threads;out.ar_fast_threads=impl_->configured_fast_threads;out.ar_threads=out.ar_slow_threads==out.ar_fast_threads?out.ar_slow_threads:0;
  out.pipeline_enabled=options.pipeline_codec;
  out.chunk_frames=options.chunk_frames;
  out.codec_mode=options.incremental_codec?"incremental_kv_conv":"legacy_rolling";
  out.codec_kv_window=options.incremental_codec?128:0;
  out.codec_state_tensors=options.incremental_codec?36:0;
  out.decoder_context_replay_frames=options.incremental_codec?0:options.context_frames;
  out.decoder_guard_frames_effective=options.incremental_codec?0:options.guard_frames;
  const auto start=Clock::now(); impl_->ResetWorkingState();impl_->ResetMetrics();
  std::vector<float> logits,hidden; std::vector<int> previous;
  std::vector<std::array<int64_t,10>> frames; frames.reserve(limit);
  std::mt19937_64 rng(options.seed); size_t emitted=0;
  struct DecodeJob {std::vector<int64_t> codes;int begin=0,end=0;bool flush=false;};
  std::mutex queue_mutex;std::condition_variable queue_ready;
  std::deque<DecodeJob> decode_jobs;std::deque<Event> pending_events;
  bool producer_done=false,pipeline_failure=false;std::exception_ptr producer_error;
  std::atomic<int64_t> progress_frames{0},pcm_samples{0};
  const auto caller_thread=std::this_thread::get_id();
  auto log=[&](const char* type,double stage=0,int window=0) {
    if(!logger) return;
    Event value{type,Seconds(start),stage,progress_frames.load(),pcm_samples.load(),window};
    if(options.pipeline_codec && std::this_thread::get_id()!=caller_thread) {
      {std::lock_guard<std::mutex> lock(queue_mutex);pending_events.push_back(value);}
      queue_ready.notify_one();
    } else logger(value);
  };
  auto flush_events=[&]() {
    std::deque<Event> events;
    {std::lock_guard<std::mutex> lock(queue_mutex);events.swap(pending_events);}
    for(const auto& value:events) if(logger) logger(value);
  };
  auto send=[&](const std::vector<float>& audio,size_t begin,size_t end) {
    if(end<=begin) return;
    if(end>audio.size()) throw std::runtime_error("Codec crop exceeds waveform");
    for(size_t i=begin;i<end;++i) if(!std::isfinite(audio[i])) throw std::runtime_error("Non-finite codec audio");
    const double elapsed=Seconds(start);
    if(out.first_pcm_s<0) { out.first_pcm_s=elapsed; out.first_payload_samples=static_cast<int64_t>(end-begin); }
    out.pcm.insert(out.pcm.end(),audio.begin()+begin,audio.begin()+end);
    pcm_samples.store(static_cast<int64_t>(out.pcm.size()));
    if(out.first_playable_500ms_s<0 && out.pcm.size()>=22050) out.first_playable_500ms_s=elapsed;
    emitted+=end-begin; log("pcm");
    if(callback && !callback(audio.data()+begin,end-begin,44100)) Cancel();
  };
  auto decode_codes=[&](const std::vector<int64_t>& codes,int begin,int end,bool flush) {
    log("codec_begin",0,end-begin); auto tick=Clock::now();
    auto audio=impl_->Decode(codes,end-begin);
    const double duration=Seconds(tick); out.decoder_s+=duration; ++out.decoder_calls;
    log("codec_end",duration,end-begin);
    const size_t absolute=static_cast<size_t>(begin)*kHop;
    size_t crop_begin=emitted>absolute?emitted-absolute:0;
    size_t crop_end=audio.size();
    if(!flush && !options.incremental_codec) crop_end=audio.size()>static_cast<size_t>(options.guard_frames*kHop)?audio.size()-options.guard_frames*kHop:0;
    crop_end=std::max(crop_begin,crop_end); send(audio,crop_begin,crop_end);
  };
  int dispatched_frames=0;
  auto dispatch_decode=[&](int begin,int end,bool flush) {
    if(options.incremental_codec) {
      if(end<dispatched_frames)throw std::runtime_error("Incremental codec frames moved backwards");
      begin=dispatched_frames;
      if(end==begin)return; // An exact-chunk EOS must not decode/replay it twice.
      dispatched_frames=end;
    }
    auto codes=PackFrames(frames,begin,end);
    if(!options.pipeline_codec) {decode_codes(codes,begin,end,flush);return;}
    auto wait_start=Clock::now();
    {
      std::unique_lock<std::mutex> lock(queue_mutex);
      queue_ready.wait(lock,[&]() {return decode_jobs.size()<2 || impl_->cancelled.load();});
      out.pipeline_ar_queue_wait_s+=Seconds(wait_start);impl_->CheckCancelled();
      decode_jobs.push_back({std::move(codes),begin,end,flush});
      out.pipeline_peak_queued_jobs=std::max(out.pipeline_peak_queued_jobs,static_cast<int>(decode_jobs.size()));
    }
    queue_ready.notify_one();
  };
  auto generate_ar=[&]() {
    AffinityGuard ar_guard(impl_->scheduling,SchedulingRole::Ar);
    const double ar_cpu_begin=CurrentThreadCpuSeconds();
    auto finish_ar=[&](){out.ar_thread_cpu_s=CurrentThreadCpuSeconds()-ar_cpu_begin;ar_guard.Restore();out.ar_scheduling_json=ar_guard.Json();};
    try {
    auto tick=Clock::now();
    double phase_cpu_begin=CurrentThreadCpuSeconds();
    int prefill_begin=0;
    if(impl_->PrefixMatches(prompt,options.prefix_positions)) {
      auto restore=Clock::now();impl_->RestorePrefix(logits,hidden);out.prefix_restore_s=Seconds(restore);
      prefill_begin=options.prefix_positions;out.reused_prefix_positions=prefill_begin;log("prefix_reused",out.prefix_restore_s);
    } else {
      // A different speaker/reference/system prefix must never reuse old state.
      impl_->prefix=Impl::PrefixCache{};
    }
    for(int position=prefill_begin;position<length;++position) {
      std::array<int64_t,11> column{};
      for(int row=0;row<11;++row) column[row]=prompt.data[static_cast<size_t>(row)*length+position];
      impl_->SlowStep(column,position,logits,hidden);
      ++out.prefill_positions_executed;
      if(position+1==options.prefix_positions) {impl_->SavePrefix(prompt,options.prefix_positions,logits,hidden);log("prefix_cached");}
      if(position==0 || (position+1)%32==0) log("prefill");
    }
    out.cached_prefix_positions=impl_->prefix.positions;
    out.prefill_s=Seconds(tick);out.prefill_thread_cpu_s=CurrentThreadCpuSeconds()-phase_cpu_begin;out.ar_s+=out.prefill_s;
    for(int step=0;step<limit;++step) {
      impl_->CheckCancelled(); tick=Clock::now();phase_cpu_begin=CurrentThreadCpuSeconds();auto scores=logits;
      if(previous.empty()) scores.back()=-std::numeric_limits<float>::infinity();
      int normal=Sample(scores,options.temperature,options.top_p,options.top_k,rng);
      int high=Sample(scores,1.0,0.9,options.top_k,rng);
      normal=normal==4096?kEos:kSemanticBegin+normal; high=high==4096?kEos:kSemanticBegin+high;
      int semantic=normal;
      if(normal>=kSemanticBegin && normal<=kSemanticEnd && std::find(previous.begin(),previous.end(),normal)!=previous.end()) semantic=high;
      if(semantic==kEos) { out.eos=true;const double duration=Seconds(tick);out.ar_s+=duration;out.step_s+=duration;out.step_thread_cpu_s+=CurrentThreadCpuSeconds()-phase_cpu_begin;break; }
      previous.push_back(semantic); if(previous.size()>10) previous.erase(previous.begin());
      for(auto& cache:impl_->fast_cache) std::fill(cache.begin(),cache.end(),0.0f);
      impl_->FastStep(hidden,0,true,0); std::array<int64_t,10> frame{}; frame[0]=semantic-kSemanticBegin;
      int token=static_cast<int>(frame[0]);
      for(int position=1;position<10;++position) {
        token=Sample(impl_->FastStep(hidden,token,false,position),options.temperature,options.top_p,options.top_k,rng);
        frame[position]=token;
      }
      frames.push_back(frame);{const double duration=Seconds(tick);out.ar_s+=duration;out.step_s+=duration;out.step_thread_cpu_s+=CurrentThreadCpuSeconds()-phase_cpu_begin;}
      progress_frames.store(static_cast<int64_t>(frames.size()));
      if(out.first_code_s<0) out.first_code_s=Seconds(start);
      if(frames.size()==1 || frames.size()%12==0) log("ar_progress");
      if(options.streaming && frames.size()%options.chunk_frames==0) dispatch_decode(std::max(0,static_cast<int>(frames.size())-options.context_frames-options.chunk_frames),static_cast<int>(frames.size()),false);
      if(step+1>=limit) break;
      tick=Clock::now();phase_cpu_begin=CurrentThreadCpuSeconds(); std::array<int64_t,11> column{}; column[0]=semantic;
      std::copy(frame.begin(),frame.end(),column.begin()+1);
      impl_->SlowStep(column,length+step,logits,hidden);{const double duration=Seconds(tick);out.ar_s+=duration;out.step_s+=duration;out.step_thread_cpu_s+=CurrentThreadCpuSeconds()-phase_cpu_begin;}
    }
    if(!frames.empty() && !impl_->cancelled.load()) {
      const int count=static_cast<int>(frames.size());
      if(options.streaming) dispatch_decode(std::max(0,count-options.context_frames-options.chunk_frames),count,true);
      else dispatch_decode(0,count,true);
    }
    }catch(...){finish_ar();throw;}
    finish_ar();
  };
  try {
    if(options.pipeline_codec) {
      std::thread producer([&]() {
        try {generate_ar();}
        catch(...) {producer_error=std::current_exception();}
        {std::lock_guard<std::mutex> lock(queue_mutex);producer_done=true;}
        queue_ready.notify_one();
      });
      try {
        while(true) {
          flush_events();DecodeJob job;bool have_job=false,finished=false;
          {
            std::unique_lock<std::mutex> lock(queue_mutex);
            queue_ready.wait(lock,[&]() {return !decode_jobs.empty() || producer_done || !pending_events.empty();});
            if(!decode_jobs.empty()) {job=std::move(decode_jobs.front());decode_jobs.pop_front();have_job=true;}
            else finished=producer_done;
          }
          queue_ready.notify_one();
          if(have_job) decode_codes(job.codes,job.begin,job.end,job.flush);
          else if(finished) break;
        }
        producer.join();flush_events();
        if(producer_error) std::rethrow_exception(producer_error);
      } catch(...) {
        // Stop and join the AR producer before unwinding any captured buffers.
        // A real codec/callback error stays an error, rather than being masked
        // as an ordinary user cancellation by the termination needed to join.
        pipeline_failure=!impl_->cancelled.load();Cancel();queue_ready.notify_all();
        if(producer.joinable()) producer.join();throw;
      }
    } else generate_ar();
  } catch(const std::exception&) { if(!impl_->cancelled.load() || pipeline_failure) throw; }
  out.generated_frames=static_cast<int>(frames.size()); out.codes=PackFrames(frames,0,out.generated_frames);
  out.cancelled=impl_->cancelled.load(); out.capped=!out.eos && !out.cancelled && out.generated_frames>=limit;
  out.native_slow_calls=impl_->slow_calls;out.native_fast_calls=impl_->fast_calls;out.native_codec_calls=impl_->codec_calls;
  out.slow_call_thread_cpu_s=impl_->slow_cpu_s;out.fast_call_thread_cpu_s=impl_->fast_cpu_s;out.slow_end_cpu_counts=impl_->slow_end_cpus;out.fast_end_cpu_counts=impl_->fast_end_cpus;
  out.scheduling_profile_json=impl_->scheduling->Json();
  out.generation_s=Seconds(start); return out;
}

std::string RunIntegerKernelProbe(const std::string& model_path,int threads) {
  InitOrt(); Ort::Env env(ORT_LOGGING_LEVEL_WARNING,"audio8-kernel"); auto options=SessionOptions(threads);
  Ort::Session session(env,model_path.c_str(),options); auto memory=Ort::MemoryInfo::CreateCpu(OrtArenaAllocator,OrtMemTypeDefault);
  std::vector<uint8_t> a(512,255); const std::array<int64_t,2> shape{1,512};
  auto input=Ort::Value::CreateTensor<uint8_t>(memory,a.data(),a.size(),shape.data(),shape.size());
  const char* names[]={"A"}; const char* outputs[]={"Y"};
  auto result=session.Run(Ort::RunOptions{nullptr},names,&input,1,outputs,1);
  const auto count=result[0].GetTensorTypeAndShapeInfo().GetElementCount(); const auto* values=result[0].GetTensorData<int32_t>();
  size_t mismatch=0; for(size_t i=0;i<count;++i) mismatch+=values[i]!=16581120;
  return std::string("{\"ort_version\":\"")+OrtGetApiBase()->GetVersionString()+"\",\"output_elements\":"+std::to_string(count)+",\"expected\":16581120,\"actual_first\":"+std::to_string(values[0])+",\"mismatch_elements\":"+std::to_string(mismatch)+"}";
}
std::string RuntimeInfo() {
  const auto* base=OrtGetApiBase();
  const auto* api=base->GetApi(ORT_API_VERSION);
  return std::string("{\"ort_version\":\"")+base->GetVersionString()+"\",\"api_requested\":"+std::to_string(ORT_API_VERSION)+",\"api_supported\":"+(api?"true":"false")+"}";
}
} // namespace audio8

#pragma once
#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>
#include "audio8_scheduling.h"

namespace audio8 {
struct IntArray { std::vector<int64_t> shape; std::vector<int64_t> data; };
IntArray LoadIntegerNpy(const std::string& path);
void SaveInt64Npy(const std::string& path, const std::vector<int64_t>& values, const std::vector<int64_t>& shape);
void SaveFloatNpy(const std::string& path, const std::vector<float>& values);
void SavePcm16Wav(const std::string& path, const std::vector<float>& pcm, int sample_rate = 44100);

struct Options {
  int max_frames = 32;
  // Exact packed prefix columns, reusable only within this Engine instance.
  int prefix_positions = 0;
  int chunk_frames = 12;
  int context_frames = 128;
  int guard_frames = 1;
  bool streaming = true;
  bool pipeline_codec = false;
  bool incremental_codec = false;
  double temperature = 0.7, top_p = 0.9;
  int top_k = 50;
  uint64_t seed = 42;
};
struct Event {
  std::string type;
  double elapsed_s = 0, stage_s = 0;
  int64_t frames = 0, samples = 0, window_frames = 0;
};
using Logger = std::function<void(const Event&)>;
// The callback owns no data after returning. Return false to cancel this request.
using PcmCallback = std::function<bool(const float*, size_t, int)>;
using DecoderBackend = std::function<std::vector<float>(const std::vector<int64_t>&, int64_t)>;
struct Result {
  std::vector<int64_t> codes; // [10, generated_frames], row-major.
  std::vector<float> pcm;
  int generated_frames = 0;
  bool eos = false, cancelled = false, capped = false;
  double load_s = 0, ar_s = 0, decoder_s = 0, generation_s = 0;
  double first_code_s = -1, first_pcm_s = -1, first_playable_500ms_s = -1;
  int64_t first_payload_samples = 0;
  int decoder_calls = 0;
  int reused_prefix_positions = 0, cached_prefix_positions = 0;
  int prefill_positions_executed = 0;
  double prefix_restore_s = 0;
  bool pipeline_enabled = false;
  int pipeline_peak_queued_jobs = 0;
  double pipeline_ar_queue_wait_s = 0;
  std::string codec_mode="legacy_rolling";
  int codec_kv_window=0,codec_state_tensors=0;
  int decoder_context_replay_frames=128,decoder_guard_frames_effective=1;
  bool decoder_state_reset_per_request=false;
  double codec_load_s=0;
  int ar_threads=0,ar_slow_threads=0,ar_fast_threads=0,codec_threads=0,chunk_frames=12;
  double prefill_s=0,step_s=0,prefill_thread_cpu_s=0,step_thread_cpu_s=0,ar_thread_cpu_s=0;
  double slow_call_thread_cpu_s=0,fast_call_thread_cpu_s=0;
  int64_t native_slow_calls=0,native_fast_calls=0,native_codec_calls=0;
  std::map<int,int64_t> slow_end_cpu_counts,fast_end_cpu_counts;
  std::string scheduling_profile_json="{}",ar_scheduling_json="{}",codec_scheduling_json="{}";
  std::string ort_version;
};
struct PrefixPreparation {
  int ar_slow_threads=0,ar_fast_threads=0;
  int prefix_positions=0,prefill_positions_executed=0,reused_prefix_positions=0;
  double prefill_s=0,thread_cpu_s=0;
  int64_t slow_calls=0,fast_calls=0,decoder_calls=0;
  std::map<int,int64_t> slow_end_cpu_counts;
  std::string scheduling_profile_json="{}",ar_scheduling_json="{}";
};

class Engine {
 public:
  Engine(const std::string& model_dir, int threads = 1, bool load_ar = true, bool load_codec = true,std::shared_ptr<SchedulingProfile> scheduling={});
  Engine(const std::string& model_dir,int slow_threads,int fast_threads,bool load_ar,bool load_codec,std::shared_ptr<SchedulingProfile> scheduling);
  ~Engine();
  Engine(const Engine&) = delete;
  Engine& operator=(const Engine&) = delete;
  Result Generate(const IntArray& prompt, const Options& options, Logger logger = {}, PcmCallback callback = {});
  PrefixPreparation PreparePrefix(const IntArray& packed_prefix,int prefix_positions,Logger logger={});
  std::vector<float> Decode(const IntArray& codes);
  void Cancel();
  void Reset();
  void SetDecoderBackend(DecoderBackend decoder);
  std::string Version() const;
  double LoadSeconds() const;
 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};
// Runs one supplied tiny integer graph, not a TTS model. Throws on mismatch.
std::string RunIntegerKernelProbe(const std::string& model_path, int threads = 1);
std::string RuntimeInfo();
} // namespace audio8

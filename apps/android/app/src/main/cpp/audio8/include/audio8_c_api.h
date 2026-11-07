#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct Audio8Handle Audio8Handle;
typedef struct Audio8GenerateConfig {
  int max_frames;
  int chunk_frames;
  int context_frames;
  int guard_frames;
  int streaming;
  uint64_t seed;
  double temperature;
  double top_p;
  int top_k;
} Audio8GenerateConfig;
// PCM is float32 mono at sample_rate, valid only during this call.
// Return 1 to continue, 0 to cancel. An empty final=1 callback always terminates
// delivery; eos/cap/cancel/error remain distinct in the result/status.
typedef int (*Audio8PcmCallback)(const float* pcm,size_t samples,int sample_rate,int final,void* user);
typedef void (*Audio8EventCallback)(const char* event_json,void* user);
void audio8_default_config(Audio8GenerateConfig* config);
// No model/session required; only probes/restores the calling thread's mask.
// Pointer remains valid until the next call on this thread.
const char* audio8_affinity_probe_json(void);
// Runtime provider registration and Android NNAPI device metadata only.
// No model Session, compilation, target generation or audio is created.
const char* audio8_runtime_info_json(void);
// Independent no-model MNN/OpenCL device query. No model Session or forward.
const char* audio8_mnn_runtime_info_json(void);
Audio8Handle* audio8_create(const char* model_dir,int threads,char* error,size_t error_size);
// Explicit stateful backend. Fails if codec_incremental.onnx is absent/invalid;
// never loads the legacy codec or silently falls back to its rolling replay.
Audio8Handle* audio8_create_incremental(const char* model_dir,int threads,char* error,size_t error_size);
// Explicit split thread pools; existing factories retain their previous ABI.
// Generation chunk length remains the per-request config.chunk_frames value.
Audio8Handle* audio8_create_incremental_tuned(const char* model_dir,int ar_threads,int codec_threads,char* error,size_t error_size);
// Optional own-thread capacity profile; unknown/unsupported hardware keeps OS scheduling.
Audio8Handle* audio8_create_incremental_profiled(const char* model_dir,int ar_threads,int codec_threads,int auto_capacity_profile,char* error,size_t error_size);
// Explicit MNN stateful decoder; same ORT AR/prefix path. Requires streaming
// generation with chunk_frames matching the fixed MNN model (12 or 24).
// No ORT decoder is instantiated in this path.
// use_opencl/low_precision are 0 or 1; requested OpenCL must not silently fall
// back to a CPU runtime. Actual per-op fallback remains separately reported.
Audio8Handle* audio8_create_qnn_incremental_split_ar(const char* model_dir,const char* model_path,
    const char* plugin_path,const char* backend_path,const char* dsp_directory,const char* profile_path,const char* cache_directory,
    int slow_threads,int fast_threads,char* error,size_t error_size);
Audio8Handle* audio8_create_mnn_incremental(const char* model_dir,const char* codec_model_path,int ar_threads,int codec_threads,int use_opencl,int low_precision,char* error,size_t error_size);
// Separate slow/fast AR pools, with the old factory retaining shared-count ABI.
Audio8Handle* audio8_create_mnn_incremental_split_ar(const char* model_dir,const char* codec_model_path,int slow_ar_threads,int fast_ar_threads,int codec_threads,int use_opencl,int low_precision,char* error,size_t error_size);
Audio8Handle* audio8_create_mnn_incremental_split_ar_with_state_policy(const char* model_dir,const char* codec_model_path,int slow_ar_threads,int fast_ar_threads,int codec_threads,int use_opencl,int low_precision,int keep_gpu_state,char* error,size_t error_size);
// Prepare immutable prefix only. No target sampling, fast AR, decoder or PCM callback.
int audio8_prepare_prefix(Audio8Handle*,const int64_t* packed_prefix,int positions,int prefix_positions,Audio8EventCallback,void* user,char* error,size_t error_size);
const char* audio8_last_prefix_json(const Audio8Handle*);
// packed_prompt is row-major int64 [11, positions]. No tokenization is done here.
// prefix_positions caches exactly matching prefix columns plus their KV,
// convolution and SSM states. Target suffix and generated states are not reused.
// audio8_reset clears this cache; generation alone retains its immutable copy.
// Only cancel may run concurrently. Join generation before reset/destroy.
int audio8_generate(Audio8Handle*,const int64_t* packed_prompt,int positions,int prefix_positions,
                    const Audio8GenerateConfig*,Audio8PcmCallback,Audio8EventCallback,void* user,
                    char* error,size_t error_size);
void audio8_cancel(Audio8Handle*);
// Set only while generation is stopped. Default is off. With it enabled, AR
// runs on a producer thread; PCM/event delivery stays on the generate caller.
// At most two codec windows wait in the native queue.
void audio8_set_pipeline(Audio8Handle*,int enabled);
void audio8_reset(Audio8Handle*);
void audio8_destroy(Audio8Handle*);
// Pointer valid until the next generate/reset/destroy call; copy before then.
const char* audio8_last_result_json(const Audio8Handle*);
// Save the already generated original PCM/codes. Does not run inference.
int audio8_save_last(Audio8Handle*,const char* prefix,char* error,size_t error_size);
// Production playback artifact only: no debug codes or float NPY files.
int audio8_save_wav(Audio8Handle*,const char* wav_path,char* error,size_t error_size);
#ifdef __cplusplus
}
#endif

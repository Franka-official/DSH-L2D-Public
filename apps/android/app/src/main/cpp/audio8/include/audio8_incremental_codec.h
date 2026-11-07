#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
#include "audio8_scheduling.h"

namespace audio8 {
struct QnnDecoderOptions {
  std::string plugin_path, backend_path, dsp_directory, profile_path, cache_directory;
};

// This graph-specific decoder consumes NEW code frames only. Reset at the
// start of each utterance; do not send the old rolling context again.
class IncrementalCodec {
 public:
  IncrementalCodec(const std::string& graph_path,int threads=1,std::shared_ptr<SchedulingProfile> scheduling={},const QnnDecoderOptions* qnn=nullptr);
  ~IncrementalCodec();
  IncrementalCodec(const IncrementalCodec&)=delete;
  IncrementalCodec& operator=(const IncrementalCodec&)=delete;
  std::vector<float> Decode(const std::vector<int64_t>& codes,int64_t frames);
  void Reset(); // Call only after Decode has stopped.
  void Cancel(); // May run concurrently with Decode.
  int64_t FramesProcessed() const;
  double LoadSeconds() const;
  std::string RuntimeJson()const;
  std::string QnnRuntimeJson()const;
  // Original graph already right-crops transposed-convolution tails. Flush
  // emits zero extra samples; every Decode returns frames*2048 stable samples.
  std::vector<float> Flush();
 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};
}

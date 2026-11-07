#pragma once

#include "audio8_incremental_codec.h"
#include <onnxruntime_cxx_api.h>

namespace audio8 {
struct QnnContextCacheResult {
  const char* status="disabled";
  double session_load_ms=0;
  double cold_compile_ms=0;
  double context_cache_load_ms=0;
  double rejected_cache_ms=0;
  uint64_t cache_bytes=0;
};

// The options already select QNN HTP with CPU fallback disabled.
std::unique_ptr<Ort::Session> OpenQnnContextSession(
    Ort::Env& env,const Ort::SessionOptions& options,const std::string& graph,
    const QnnDecoderOptions& qnn,QnnContextCacheResult& result);
}

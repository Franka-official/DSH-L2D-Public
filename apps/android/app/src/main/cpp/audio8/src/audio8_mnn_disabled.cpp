#include "MnnIncrementalCodec.h"
#include "audio8_mnn_metadata.h"
#include <stdexcept>

namespace {
[[noreturn]] void GpuUnavailable() {
    throw std::runtime_error("Audio8 MNN support was disabled at build time");
}
}  // namespace

namespace audio8 {
class MnnIncrementalCodec::Impl {};

MnnIncrementalCodec::MnnIncrementalCodec(const std::string&, const MnnIncrementalCodecOptions&) {
    GpuUnavailable();
}
MnnIncrementalCodec::~MnnIncrementalCodec() = default;
std::vector<float> MnnIncrementalCodec::Decode(const int64_t*, int) { GpuUnavailable(); }
void MnnIncrementalCodec::Reset() { GpuUnavailable(); }
void MnnIncrementalCodec::Cancel() { GpuUnavailable(); }
std::vector<float> MnnIncrementalCodec::Flush() { GpuUnavailable(); }
int64_t MnnIncrementalCodec::FramesProcessed() const { GpuUnavailable(); }
int MnnIncrementalCodec::ChunkFrames() const { GpuUnavailable(); }
double MnnIncrementalCodec::LoadSeconds() const { GpuUnavailable(); }
std::string MnnIncrementalCodec::RuntimeJson() const { GpuUnavailable(); }
}  // namespace audio8

extern "C" __attribute__((visibility("default"))) const char* audio8_mnn_runtime_info_json() {
    return R"({"event":"audio8_mnn_runtime_info","metadata_only":true,"model_sessions_created":0,"model_forward_calls":0,"opencl_available":false,"status":"opencl_disabled_at_build_time"})";
}

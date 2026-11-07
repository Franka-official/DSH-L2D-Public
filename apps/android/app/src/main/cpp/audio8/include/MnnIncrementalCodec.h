#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace audio8 {
struct MnnIncrementalCodecOptions {
    int backend = 0;  // MNN_FORWARD_CPU=0, MNN_FORWARD_OPENCL=3 (not host-validated).
    int threads = 1;
    bool low_precision = false;
    bool keep_gpu_state = true;
};

class MnnIncrementalCodec {
public:
    explicit MnnIncrementalCodec(const std::string& model_path,
                                 const MnnIncrementalCodecOptions& options = {});
    ~MnnIncrementalCodec();
    MnnIncrementalCodec(const MnnIncrementalCodec&) = delete;
    MnnIncrementalCodec& operator=(const MnnIncrementalCodec&) = delete;
    // NEW codes only, codebook-major [10, frames], at most ChunkFrames().
    // The model has a fixed chunk size of 12 or 24; a short step is EOS-only.
    std::vector<float> Decode(const int64_t* codes, int frames);
    void Reset();
    void Cancel();
    std::vector<float> Flush();
    int64_t FramesProcessed() const;
    int ChunkFrames() const;
    double LoadSeconds() const;
    std::string RuntimeJson() const;
private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};
}  // namespace audio8

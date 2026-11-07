// Diagnostic-only additions for the pinned official MNN Qwen3-TTS path.
// No extra tensor evaluation/readMap, operator callbacks, or sampling changes.
#ifndef PRTS_QWEN3_TTS_BENCH_DIAG_HPP
#define PRTS_QWEN3_TTS_BENCH_DIAG_HPP
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <sstream>
#include <string>
#include <MNN/expr/Expr.hpp>
#include "core/TensorUtils.hpp"

namespace PrtsQwenBench {
using Clock = std::chrono::steady_clock;
inline bool enabled() {
    const char* value = std::getenv("PRTS_QWEN_BENCH_DIAG");
    return value && std::string(value) == "1";
}
inline std::string quote(const std::string& value) {
    std::string result = "\"";
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') { result += '\\'; result += char(c); }
        else if (c < 32) { char escaped[7]; std::snprintf(escaped, sizeof(escaped), "\\u%04x", c); result += escaped; }
        else result += char(c);
    }
    return result + "\"";
}
inline std::string caseId() {
    const char* value = std::getenv("PRTS_QWEN_DIAG_CASE");
    return value ? value : "load";
}
inline void emit(const std::string& fields) {
    if (!enabled()) return;
    const std::string line = "{\"event\":\"qwen_native_diag\",\"case\":" + quote(caseId()) + "," + fields + "}\n";
    std::fwrite(line.data(), 1, line.size(), stderr);
    std::fflush(stderr);
}
inline void tensor(const char* component, const char* phase, const MNN::Express::VARP& value) {
    if (!enabled()) return;
    const MNN::Tensor* output = value.get() ? value->getTensor() : nullptr;
    int backendType = -1;
    bool deviceNonzero = false;
    std::ostringstream shape;
    shape << '[';
    if (output) {
        auto description = MNN::TensorUtils::getDescribeOrigin(output);
        auto backend = description ? description->getBackend() : nullptr;
        if (backend) backendType = static_cast<int>(backend->type());
        deviceNonzero = output->deviceId() != 0;
        for (int i = 0; i < output->dimensions(); ++i) {
            if (i) shape << ',';
            shape << output->length(i);
        }
    }
    shape << ']';
    emit("\"kind\":\"output_tensor\",\"component\":" + quote(component) +
         ",\"phase\":" + quote(phase) + ",\"tensorPresent\":" + (output ? "true" : "false") +
         ",\"outputBackendType\":" + std::to_string(backendType) +
         ",\"outputDeviceIdNonzero\":" + (deviceNonzero ? "true" : "false") +
         ",\"shape\":" + shape.str() +
         ",\"proofScope\":\"graph_output_allocation_not_every_internal_operator\"");
}
class Stage {
public:
    explicit Stage(const char* name) : name_(name), started_(Clock::now()) {}
    void finish() {
        if (finished_) return;
        finished_ = true;
        const double ms = std::chrono::duration<double, std::milli>(Clock::now() - started_).count();
        emit("\"kind\":\"stage_wall_time\",\"stage\":" + quote(name_) + ",\"wallMs\":" + std::to_string(ms));
    }
    ~Stage() { finish(); }
private:
    const char* name_;
    Clock::time_point started_;
    bool finished_ = false;
};
inline void generationEnd(int frames, int cap, bool observedCodecEos) {
    emit("\"kind\":\"codec_stop\",\"generatedFrames\":" + std::to_string(frames) +
         ",\"maxFrames\":" + std::to_string(cap) + ",\"codecEosObserved\":" +
         (observedCodecEos ? "true" : "false") + ",\"frameCapReached\":" + (frames >= cap ? "true" : "false"));
}
inline void cpuPredictorSchedule(int threads) {
    emit("\"kind\":\"code_predictor_schedule\",\"backendType\":0,\"precision\":\"high\",\"cpuThreads\":" + std::to_string(threads) +
         ",\"configuredScheduleOnly\":true");
}
} // namespace PrtsQwenBench
#endif

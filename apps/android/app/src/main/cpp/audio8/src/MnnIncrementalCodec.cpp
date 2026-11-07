#include "MnnIncrementalCodec.h"
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include "core/Execution.hpp"
#include "core/Schedule.hpp"
#include "core/Session.hpp"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <iomanip>
#include <map>
#include <mutex>
#include <sstream>
#include <stdexcept>
#ifdef __ANDROID__
#include <array>
#include "CLCache_generated.h"
#include "backend/opencl/core/OpenCLBackend.hpp"
#include "backend/opencl/core/OpenCLRunningUtils.hpp"
#include "core/Backend.hpp"
#include <cstdlib>
#include <fstream>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <thread>
#include <unistd.h>
#endif

namespace audio8 {
namespace {
constexpr int kPast = 127, kSamples = 2048;
using Clock = std::chrono::steady_clock;
#ifdef __ANDROID__
constexpr size_t kMaxOpenClCacheBytes = 64 * 1024 * 1024;
enum class CacheWrite { Idle, Queued, Stored, Failed };

std::string OpenClCachePath(const std::string& model, int gpu_mode, bool low_precision) {
    const auto separator = model.rfind('/');
    if (separator == std::string::npos) return {};
    const auto directory = model.substr(0, separator);
    const auto ends_with = [&](const std::string& suffix) {
        return directory.size() >= suffix.size() &&
               directory.compare(directory.size() - suffix.size(), suffix.size(), suffix) == 0;
    };
    if (directory.compare(0, 6, "/data/") != 0 ||
        (!ends_with("/files/audio8-mnn") && !ends_with("/files/audio8-gpu"))) return {};
    struct stat file{};
    if (stat(model.c_str(), &file) != 0 || !S_ISREG(file.st_mode) || file.st_uid != geteuid()) return {};
    char system_build[PROP_VALUE_MAX]{};
    char vendor_build[PROP_VALUE_MAX]{};
    if (__system_property_get("ro.build.fingerprint", system_build) <= 0) return {};
    __system_property_get("ro.vendor.build.fingerprint", vendor_build);
    std::ostringstream identity;
    identity << MNN::getVersion() << '\n' << system_build << '\n' << vendor_build << '\n'
             << gpu_mode << '\n' << low_precision << '\n' << file.st_size << '\n'
             << file.st_mtim.tv_sec << '\n' << file.st_mtim.tv_nsec << '\n' << file.st_ino;
    // Short deterministic filename key. MNN validates the actual device, driver and program ABI.
    uint64_t key = 14695981039346656037ull;
    for (unsigned char c : identity.str()) key = (key ^ c) * 1099511628211ull;
    std::ostringstream path;
    path << model << ".opencl-v1-" << std::hex << key << ".cache";
    return path.str();
}

bool HasOpenClPrograms(const uint8_t* data, size_t size) {
    flatbuffers::Verifier verifier(data, size);
    if (!CLCache::VerifyCacheBuffer(verifier)) return false;
    const auto* backends = CLCache::GetCache(data)->backends();
    if (!backends || backends->size() != 1) return false;
    const auto* backend = backends->Get(0);
    return backend && backend->deviceName() && backend->driverVersion() &&
           backend->programs() && backend->programs()->size() > 0;
}

bool StoreOpenClCache(const std::string& path, const std::vector<uint8_t>& bytes) {
    // Same directory for atomic publication. A killed writer cannot expose a partial cache.
    std::string temporary = path + ".tmp.XXXXXX";
    const int fd = mkstemp(temporary.data());
    if (fd < 0) return false;
    auto* stream = fdopen(fd, "wb");
    if (!stream) {
        close(fd);
        std::remove(temporary.c_str());
        return false;
    }
    const bool wrote = std::fwrite(bytes.data(), 1, bytes.size(), stream) == bytes.size();
    const bool closed = std::fclose(stream) == 0;
    if (wrote && closed && std::rename(temporary.c_str(), path.c_str()) == 0) return true;
    std::remove(temporary.c_str());
    return false;
}
#endif
std::string Quote(const std::string& text) {
    std::string out = "\"";
    for (unsigned char c : text) {
        if (c == '\\' || c == '"') { out += '\\'; out += char(c); }
        else if (c < 32) { char hex[7]; std::snprintf(hex, sizeof(hex), "\\u%04x", c); out += hex; }
        else out += char(c);
    }
    return out + '"';
}
std::vector<int> Shape(const MNN::Tensor* tensor) {
    std::vector<int> out;
    for (int i = 0; i < tensor->dimensions(); ++i) out.push_back(tensor->length(i));
    return out;
}
std::string TypeJson(const MNN::Tensor* tensor) {
    const auto t = tensor->getType();
    return "{\"code\":" + std::to_string(t.code) + ",\"bits\":" + std::to_string(t.bits)
           + ",\"lanes\":" + std::to_string(t.lanes) + "}";
}
bool SameType(const MNN::Tensor* a, const MNN::Tensor* b) {
    const auto x = a->getType(), y = b->getType();
    return x.code == y.code && x.bits == y.bits && x.lanes == y.lanes;
}
void Require(bool condition, const std::string& error) {
    if (!condition) throw std::runtime_error(error);
}
void WriteInteger(MNN::Tensor* tensor, size_t index, int64_t value, bool mask) {
    const auto t = tensor->getType();
    Require(t.lanes == 1, "Unsupported vector input dtype");
    if (t.code == halide_type_int && t.bits == 32) {
        Require(value >= INT32_MIN && value <= INT32_MAX, "Input does not fit actual int32 tensor");
        tensor->host<int32_t>()[index] = static_cast<int32_t>(value);
    } else if (t.code == halide_type_int && t.bits == 64) {
        tensor->host<int64_t>()[index] = value;
    } else if (mask && t.code == halide_type_uint && (t.bits == 1 || t.bits == 8)) {
        Require(value == 0 || value == 1, "Mask must contain only 0/1");
        tensor->host<uint8_t>()[index] = static_cast<uint8_t>(value);
    } else if (mask && t.code == halide_type_int && t.bits == 8) {
        tensor->host<int8_t>()[index] = static_cast<int8_t>(value);
    } else if (mask && t.code == halide_type_float && t.bits == 32) {
        tensor->host<float>()[index] = static_cast<float>(value);
    } else throw std::runtime_error("Unsupported actual integer/mask dtype: " + TypeJson(tensor));
}
float HalfToFloat(uint16_t h) {
    const uint32_t sign = uint32_t(h & 0x8000) << 16;
    uint32_t exp = (h >> 10) & 31, mant = h & 1023, bits;
    if (!exp) {
        if (!mant) bits = sign;
        else { int shift = 0; while (!(mant & 1024)) { mant <<= 1; ++shift; }
               bits = sign | uint32_t(127 - 14 - shift) << 23 | (mant & 1023) << 13; }
    } else if (exp == 31) bits = sign | 0x7f800000u | mant << 13;
    else bits = sign | (exp + 112) << 23 | mant << 13;
    float value; std::memcpy(&value, &bits, sizeof(value)); return value;
}
std::vector<std::string> TailNames() {
    return {"conv_tail_0", "conv_tail_1", "conv_tail_2", "transpose_tail_0",
            "conv_tail_3", "conv_tail_4", "conv_tail_5", "transpose_tail_1",
            "conv_tail_6", "conv_tail_7", "conv_tail_8", "transpose_tail_2",
            "conv_tail_9", "conv_tail_10", "conv_tail_11", "transpose_tail_3",
            "conv_tail_12", "conv_tail_13", "conv_tail_14", "conv_tail_15"};
}
}  // namespace

class MnnIncrementalCodec::Impl {
public:
    explicit Impl(const std::string& path, const MnnIncrementalCodecOptions& o)
        : options(o), net(nullptr, MNN::Interpreter::destroy) {
        const auto begin = Clock::now();
        Require(o.backend == MNN_FORWARD_CPU || o.backend == MNN_FORWARD_OPENCL, "Unsupported MNN backend");
        Require(o.threads > 0, "Invalid codec thread count");
        backend.precision = o.low_precision ? MNN::BackendConfig::Precision_Low : MNN::BackendConfig::Precision_High;
        MNN::ScheduleConfig schedule;
        schedule.type = static_cast<MNNForwardType>(o.backend);
        schedule.backupType = MNN_FORWARD_CPU;
        schedule.backendConfig = &backend;
        schedule.numThread = o.backend == MNN_FORWARD_OPENCL
            ? (MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST) : o.threads;
        MNN::ScheduleConfig cpu_schedule;
        cpu_schedule.type = MNN_FORWARD_CPU;
        cpu_schedule.backupType = MNN_FORWARD_CPU;
        cpu_schedule.numThread = o.threads;
        cpu_schedule.backendConfig = &backend;
        runtimes = MNN::Interpreter::createRuntime({schedule, cpu_schedule});
#ifdef __ANDROID__
        const auto gpu_runtime = runtimes.first.find(MNN_FORWARD_OPENCL);
        if (o.backend == MNN_FORWARD_OPENCL && gpu_runtime != runtimes.first.end() && gpu_runtime->second) {
            opencl_cache_path = OpenClCachePath(path, schedule.numThread, o.low_precision);
            if (!opencl_cache_path.empty() && !LoadOpenClCache(*gpu_runtime->second)) {
                // The loader can accept a subset of programs before rejecting the file.
                runtimes = MNN::Interpreter::createRuntime({schedule, cpu_schedule});
            }
        }
#endif
        runtime_opencl_available = runtimes.first.find(MNN_FORWARD_OPENCL) != runtimes.first.end()
                                   && bool(runtimes.first.at(MNN_FORWARD_OPENCL));
        Require(o.backend != MNN_FORWARD_OPENCL || runtime_opencl_available,
                "Requested OpenCL runtime could not be created; refusing CPU-only fallback");
        Require(runtimes.first.find(MNN_FORWARD_CPU) != runtimes.first.end()
                    && bool(runtimes.first.at(MNN_FORWARD_CPU)) && bool(runtimes.second),
                "Cannot create explicit CPU backup runtime");
        net.reset(MNN::Interpreter::createFromFile(path.c_str()));
        Require(bool(net), "Cannot parse MNN incremental codec");
        net->setSessionMode(MNN::Interpreter::Session_Backend_Fix);
        session = net->createSession(schedule, runtimes);
        Require(session != nullptr, "Cannot create MNN incremental session");
        Require(net->getSessionInfo(session, MNN::Interpreter::BACKENDS, &session_main_backend),
                "Cannot inspect the single-pipeline session backend");
        const bool main_matches = o.backend == MNN_FORWARD_OPENCL ? session_main_backend == MNN_FORWARD_OPENCL
            : (session_main_backend == MNN_FORWARD_CPU || session_main_backend == MNN_FORWARD_CPU_EXTENSION);
        Require(main_matches,
                "Session main backend " + std::to_string(session_main_backend) + " differs from requested "
                    + std::to_string(o.backend) + "; refusing fallback label");
        device_inputs = net->getSessionInputAll(session);
        device_outputs = net->getSessionOutputAll(session);
        Require(device_inputs.size() == 39 && device_outputs.size() == 37, "MNN codec IO count differs from 39/37");
        auto add_input = [&](const std::string& name) {
            const auto it = device_inputs.find(name);
            Require(it != device_inputs.end() && it->second, "Missing MNN input " + name);
            auto host = std::make_unique<MNN::Tensor>(it->second, MNN::Tensor::CAFFE);
            Require(host->host<void>() && host->elementSize() > 0, "Unallocated host input " + name);
            inputs.emplace(name, std::move(host));
        };
        auto add_output = [&](const std::string& name) {
            const auto it = device_outputs.find(name);
            Require(it != device_outputs.end() && it->second, "Missing MNN output " + name);
            auto host = std::make_unique<MNN::Tensor>(it->second, MNN::Tensor::CAFFE);
            Require(host->host<void>() && host->elementSize() > 0, "Unallocated host output " + name);
            outputs.emplace(name, std::move(host));
        };
        add_input("codes"); add_input("frame_offset"); add_input("attention_mask"); add_output("audio");
        for (int i = 0; i < 8; ++i) for (const char* kind : {"k", "v"}) {
            const auto in = "past_" + std::string(kind) + "_" + std::to_string(i);
            const auto out = "next_" + std::string(kind) + "_" + std::to_string(i);
            states.emplace_back(in, out); add_input(in); add_output(out);
        }
        for (const auto& name : TailNames()) {
            states.emplace_back(name, "next_" + name); add_input(name); add_output("next_" + name);
        }
        const auto code_shape = Shape(inputs.at("codes").get());
        Require(code_shape.size() == 3 && code_shape[0] == 1 && code_shape[1] == 10
                    && (code_shape[2] == 12 || code_shape[2] == 24),
                "MNN code shape must be [1,10,12] or [1,10,24]");
        chunk_frames = code_shape[2];
        key_frames = kPast + chunk_frames;
        Require(inputs.at("frame_offset")->elementSize() == 1, "Wrong MNN frame_offset shape");
        Require(Shape(inputs.at("attention_mask").get()) == std::vector<int>({1, 1, chunk_frames, key_frames}), "Wrong MNN mask shape");
        Require(outputs.at("audio")->elementSize() == chunk_frames * kSamples, "Wrong MNN audio shape");
        for (size_t i = 0; i < states.size(); ++i) {
            const auto* in = inputs.at(states[i].first).get();
            const auto* out = outputs.at(states[i].second).get();
            const auto t = in->getType();
            Require(t.code == halide_type_float && (t.bits == 16 || t.bits == 32) && t.lanes == 1,
                    "Unsupported actual state dtype: " + states[i].first);
            Require(SameType(in, out), "State input/output dtype mismatch: " + states[i].first);
            if (i < 16) {
                Require(Shape(in) == std::vector<int>({1, 8, kPast, 64}) && Shape(out) == std::vector<int>({1, 8, key_frames, 64}),
                        "Wrong fixed KV state shape: " + states[i].first);
            } else Require(Shape(in) == Shape(out), "Tail state shape mismatch: " + states[i].first);
        }
        state_on_gpu.resize(states.size(), false);
#ifdef __ANDROID__
        if (o.backend == MNN_FORWARD_OPENCL && o.keep_gpu_state) ConfigureGpuStateCopies();
#endif
        ResetLocked();
        load_seconds = std::chrono::duration<double>(Clock::now() - begin).count();
    }
    ~Impl() { if (net && session) net->releaseSession(session); }

    void Ready() const {
        Require(!cancelled.load(std::memory_order_acquire), "MNN incremental codec cancelled; Reset required");
        Require(!failed, "MNN incremental codec failed; Reset required");
    }
    void ResetLocked() {
        for (const auto& names : states) {
            auto* host = inputs.at(names.first).get();
            std::memset(host->host<void>(), 0, host->size());
        }
        std::fill(state_on_gpu.begin(), state_on_gpu.end(), false);
        profile = {};
        graph_offset = 0; real_frames = 0; short_tail = false; failed = false;
        backend_counts_valid = false;
        last_backend_commands.clear(); last_backend_op_types.clear(); last_unknown_backend_commands = 0;
        cancelled.store(false, std::memory_order_release);
    }
#ifdef __ANDROID__
    struct GpuStateCopy {
        const cl::Buffer* source = nullptr;
        const cl::Buffer* destination = nullptr;
        cl::CommandQueue* queue = nullptr;
        size_t bytes = 0;
    };

    void ConfigureGpuStateCopies() {
        gpu_state_copies.resize(states.size());
        const auto buffer_for = [](const MNN::Tensor* tensor, const MNN::Backend* backend) -> const cl::Buffer* {
            const auto* origin = MNN::TensorUtils::getDescribeOrigin(tensor);
            const auto* desc = MNN::TensorUtils::getDescribe(tensor);
            if (origin->getBackend() != backend || origin->offset || desc->memoryType != MNN::Tensor::InsideDescribe::MEMORY_BACKEND
                || tensor->host<void>() || !tensor->deviceId() || tensor->buffer().flags != MNN_FORWARD_CPU) {
                return nullptr;
            }
            return &MNN::OpenCL::openCLBuffer(tensor);
        };
        const auto standalone = [](const cl::Buffer& buffer, size_t bytes) {
            cl_int status = CL_SUCCESS;
            return buffer() && buffer.getInfo<CL_MEM_TYPE>(&status) == CL_MEM_OBJECT_BUFFER && status == CL_SUCCESS
                && !buffer.getInfo<CL_MEM_ASSOCIATED_MEMOBJECT>(&status)() && status == CL_SUCCESS
                && buffer.getInfo<CL_MEM_SIZE>(&status) >= bytes && status == CL_SUCCESS;
        };
        // The attention outputs need cropping. Tail states already have the input shape.
        for (size_t i = 16; i < states.size(); ++i) {
            const auto* dst = device_inputs.at(states[i].first);
            const auto* src = device_outputs.at(states[i].second);
            const auto* dst_desc = MNN::TensorUtils::getDescribe(dst);
            const auto* src_desc = MNN::TensorUtils::getDescribe(src);
            auto* owner = MNN::TensorUtils::getDescribeOrigin(dst)->getBackend();
            if (!owner || owner->type() != MNN_FORWARD_OPENCL
                || dst_desc->applyQuant || src_desc->applyQuant || !SameType(dst, src)
                || Shape(dst) != Shape(src) || dst_desc->dimensionFormat != src_desc->dimensionFormat
                || (dst_desc->dimensionFormat != MNN::MNN_DATA_FORMAT_NCHW
                    && dst_desc->dimensionFormat != MNN::MNN_DATA_FORMAT_NHWC)) {
                continue;
            }
            const auto* source = buffer_for(src, owner);
            const auto* destination = buffer_for(dst, owner);
            if (!source || !destination) continue;
            auto* cl_backend = static_cast<MNN::OpenCL::OpenCLBackend*>(owner);
            if (cl_backend->getBytes(src) != cl_backend->getBytes(dst) || dst->elementSize() <= 0) continue;
            const size_t bytes = static_cast<size_t>(dst->elementSize()) * static_cast<size_t>(cl_backend->getBytes(dst));
            if (!bytes || !standalone(*source, bytes) || !standalone(*destination, bytes)) continue;
            // MNN can recycle an input allocation for an output. Preserve every output until read back.
            const auto aliases_destination = [&](const auto& item) {
                if (item.second == dst) return false;
                const auto* other = buffer_for(item.second, owner);
                return other && (*other)() == (*destination)();
            };
            if (src == dst || std::any_of(device_inputs.begin(), device_inputs.end(), aliases_destination)
                || std::any_of(device_outputs.begin(), device_outputs.end(), aliases_destination)) continue;
            // The pinned MNN OpenCL runtime creates its main queue in-order.
            auto& queue = cl_backend->getOpenCLRuntime()->commandQueue();
            gpu_state_copies[i] = {source, destination, &queue, bytes};
            ++gpu_state_candidate_count;
        }
    }

    // Return false only when the SDK touched the runtime and rejected the cache.
    bool LoadOpenClCache(MNN::Runtime& runtime) {
        const auto started = Clock::now();
        std::ifstream file(opencl_cache_path, std::ios::binary | std::ios::ate);
        if (!file) {
            cache_read_status = "missing_or_unreadable";
            return true;
        }
        const auto length = static_cast<std::streamoff>(file.tellg());
        if (length < 4 || length > static_cast<std::streamoff>(kMaxOpenClCacheBytes)) {
            cache_read_status = "invalid_size";
            return true;
        }
        std::vector<uint8_t> bytes(static_cast<size_t>(length));
        file.seekg(0);
        if (!file.read(reinterpret_cast<char*>(bytes.data()), length) ||
            !HasOpenClPrograms(bytes.data(), bytes.size())) {
            cache_read_status = "invalid_payload";
            return true;
        }
        cache_read_bytes = bytes.size();
        // Use the actual OpenCL runtime. Interpreter's generic cache loader also tries CPU.
        cache_accepted = runtime.onSetCache(bytes.data(), bytes.size());
        cache_read_status = cache_accepted ? "runtime_accepted" : "runtime_rejected";
        cache_read_seconds = std::chrono::duration<double>(Clock::now() - started).count();
        return cache_accepted;
    }

    void QueueOpenClCache() {
        if (opencl_cache_path.empty() || cache_accepted || real_frames == 0 ||
            cache_write->load() != CacheWrite::Idle) return;
        const auto started = Clock::now();
        try {
            auto& runtime = *runtimes.first.at(MNN_FORWARD_OPENCL);
            const auto payload = runtime.onGetCache();
            const auto* data = static_cast<const uint8_t*>(payload.first);
            if (!data || payload.second < 4 || payload.second > kMaxOpenClCacheBytes ||
                !HasOpenClPrograms(data, payload.second)) {
                cache_write->store(CacheWrite::Failed);
                return;
            }
            std::vector<uint8_t> bytes(data, data + payload.second);
            cache_write_bytes = bytes.size();
            cache_write->store(CacheWrite::Queued);
            std::thread([path = opencl_cache_path, bytes = std::move(bytes), status = cache_write] {
                try {
                    status->store(StoreOpenClCache(path, bytes) ? CacheWrite::Stored : CacheWrite::Failed);
                } catch (...) {
                    status->store(CacheWrite::Failed);
                }
            }).detach();
        } catch (...) {
            cache_write->store(CacheWrite::Failed);
        }
        cache_snapshot_seconds = std::chrono::duration<double>(Clock::now() - started).count();
    }
#endif

    void InspectSuccessfulExecution() {
        std::map<int, int64_t> counts;
        std::map<int, std::map<std::string, int64_t>> op_types;
        int64_t unknown = 0;
        // A single ScheduleConfig creates exactly one pipeline. Read the same command
        // objects that Pipeline::execute invokes, not output tensor storage backends.
        const auto& pipeline = session->getPipelineInfo(0);
        for (const auto& cache : pipeline.second) {
            if (cache.type == MNN::Schedule::CONSTANT) continue;
            for (const auto& command : cache.executeBuffer.command) {
                if (!command || !command->execution || !command->execution->backend()) { ++unknown; continue; }
                const int kind = command->execution->backend()->type();
                ++counts[kind];
                const auto* op = command->op ? command->op : cache.op;
                const std::string name = op ? MNN::EnumNameOpType(op->type()) : "unknown";
                ++op_types[kind][name];
            }
        }
        Require(!counts.empty(), "No execution command backend information after successful run");
        Require(options.backend != MNN_FORWARD_OPENCL || counts[MNN_FORWARD_OPENCL] > 0,
                "OpenCL runtime initialized but no OpenCL execution commands ran; refusing CPU-only label");
        last_backend_commands = std::move(counts);
        last_backend_op_types = std::move(op_types);
        last_unknown_backend_commands = unknown;
        backend_counts_valid = true;
    }
    std::vector<float> Decode(const int64_t* codes, int frames) {
        std::lock_guard<std::mutex> lock(mutex);
        Ready();
        Require(!short_tail, "Short EOS step is terminal; Reset required before further Decode");
        Require(codes && frames > 0 && frames <= chunk_frames,
                "NEW codec frames must be 1.." + std::to_string(chunk_frames));
        Require(graph_offset <= INT32_MAX - chunk_frames, "Frame offset exceeds supported int32 range");
        try {
            auto* code_tensor = inputs.at("codes").get();
            for (int cb = 0; cb < 10; ++cb) for (int t = 0; t < chunk_frames; ++t) {
                const int64_t value = t < frames ? codes[cb * frames + t] : 0;
                Require(value >= 0 && value < 4096, "Codec ID outside 0..4095");
                WriteInteger(code_tensor, size_t(cb * chunk_frames + t), value, false);
            }
            WriteInteger(inputs.at("frame_offset").get(), 0, graph_offset, false);
            auto* mask = inputs.at("attention_mask").get();
            for (int q = 0; q < chunk_frames; ++q) for (int k = 0; k < key_frames; ++k) {
                const int64_t q_abs = graph_offset + q, k_abs = graph_offset - kPast + k;
                const bool allowed = k_abs >= 0 && k_abs <= q_abs && k_abs >= q_abs - kPast;
                WriteInteger(mask, size_t(q * key_frames + k), allowed ? 1 : 0, true);
            }
            const auto copy_begin = Clock::now();
            const auto copy_input = [&](const std::string& name) {
                Require(device_inputs.at(name)->copyFromHostTensor(inputs.at(name).get()), "MNN input copy failed: " + name);
            };
            for (const char* name : {"attention_mask", "codes", "frame_offset"}) copy_input(name);
            const auto state_input_begin = Clock::now();
            for (size_t i = 0; i < states.size(); ++i) {
                if (state_on_gpu[i]) ++profile.gpu_h2d_skipped;
                else copy_input(states[i].first);
            }
            const auto input_copied = Clock::now();
            last_input_copy_seconds = std::chrono::duration<double>(input_copied - copy_begin).count();
            profile.nonstate_input += std::chrono::duration<double>(state_input_begin - copy_begin).count();
            profile.state_input += std::chrono::duration<double>(input_copied - state_input_begin).count();
            Ready();
            const auto started = Clock::now();
            const auto status = net->runSession(session);
            const auto run_returned = Clock::now();
            Require(status == MNN::NO_ERROR, "MNN incremental forward failed: " + std::to_string(status));
            const auto copy_output = [&](const std::string& name) {
                Require(device_outputs.at(name)->copyToHostTensor(outputs.at(name).get()), "MNN output copy failed: " + name);
            };
            copy_output("audio");
            const auto audio_copied = Clock::now();
            profile.audio_copy_wait += std::chrono::duration<double>(audio_copied - run_returned).count();
            const bool has_next = frames == chunk_frames;
            std::fill(state_on_gpu.begin(), state_on_gpu.end(), false);
            if (has_next) {
                for (size_t i = 0; i < states.size(); ++i) {
#ifdef __ANDROID__
                    if (!gpu_state_copies.empty() && gpu_state_copies[i].queue) continue;
#endif
                    copy_output(states[i].second);
                }
            } else {
                profile.terminal_state_copies_elided += states.size();
            }
            auto output_copied = Clock::now();
            double state_output_seconds = std::chrono::duration<double>(output_copied - audio_copied).count();
#ifdef __ANDROID__
            if (has_next && !gpu_state_copies.empty()) {
                // The blocking audio read drained this session's execution queue. Use that same
                // in-order queue for tail feedback so the next forward observes the new states.
                for (size_t i = 16; i < states.size(); ++i) {
                    auto& copy = gpu_state_copies[i];
                    if (!copy.queue) continue;
                    const auto copy_start = Clock::now();
                    const auto copy_status = copy.queue->enqueueCopyBuffer(*copy.source, *copy.destination, 0, 0, copy.bytes);
                    profile.gpu_enqueue += std::chrono::duration<double>(Clock::now() - copy_start).count();
                    if (copy_status == CL_SUCCESS) {
                        state_on_gpu[i] = true;
                        ++profile.gpu_copies;
                        ++profile.gpu_d2h_skipped;
                        profile.gpu_bytes += copy.bytes;
                    } else {
                        // Preserve the original host feedback for this pair after a failed enqueue.
                        copy.queue = nullptr;
                        ++profile.gpu_copy_failures;
                        const auto fallback_start = Clock::now();
                        copy_output(states[i].second);
                        output_copied = Clock::now();
                        state_output_seconds += std::chrono::duration<double>(output_copied - fallback_start).count();
                    }
                }
            }
#endif
            last_run_session_seconds = std::chrono::duration<double>(run_returned - started).count();
            last_output_copy_seconds = std::chrono::duration<double>(audio_copied - run_returned).count() + state_output_seconds;
            last_decode_seconds = last_run_session_seconds + last_output_copy_seconds;
            profile.run_enqueue += last_run_session_seconds;
            profile.state_output += state_output_seconds;
            Ready();
            InspectSuccessfulExecution();
            auto* audio = outputs.at("audio").get();
            const auto dtype = audio->getType();
            Require(dtype.code == halide_type_float && dtype.lanes == 1 && (dtype.bits == 16 || dtype.bits == 32),
                    "Unsupported actual audio dtype: " + TypeJson(audio));
            std::vector<float> pcm(size_t(frames) * kSamples);
            for (size_t i = 0; i < pcm.size(); ++i) {
                pcm[i] = dtype.bits == 32 ? audio->host<float>()[i] : HalfToFloat(audio->host<uint16_t>()[i]);
                Require(std::isfinite(pcm[i]), "MNN incremental audio contains nonfinite samples");
            }
            const auto repack_begin = Clock::now();
            for (size_t i = 0; has_next && i < states.size(); ++i) {
                if (state_on_gpu[i]) continue;
                auto* dst = inputs.at(states[i].first).get();
                const auto* src = outputs.at(states[i].second).get();
                const size_t bytes_per_value = dst->getType().bytes();
                if (i < 16) {
                    const size_t row = size_t(64) * bytes_per_value;
                    for (int head = 0; head < 8; ++head)
                        std::memcpy(static_cast<char*>(dst->host<void>()) + head * kPast * row,
                                    static_cast<const char*>(src->host<void>()) + (head * key_frames + chunk_frames) * row,
                                    kPast * row);
                } else std::memcpy(dst->host<void>(), src->host<void>(), dst->size());
            }
            profile.state_cpu_repack += std::chrono::duration<double>(Clock::now() - repack_begin).count();
            ++profile.chunks;
            graph_offset += chunk_frames; real_frames += frames; short_tail = frames < chunk_frames; ++forward_calls;
            return pcm;
        } catch (...) { failed = true; throw; }
    }
    std::string JsonLocked() const {
        std::ostringstream out;
        out << std::setprecision(9) << "{\"implementation\":\"mnn_incremental_c" << chunk_frames << "\",\"backend_requested\":"
            << Quote(options.backend == MNN_FORWARD_OPENCL ? "opencl" : "cpu")
            << ",\"gpu_requested\":" << (options.backend == MNN_FORWARD_OPENCL ? "true" : "false")
            << ",\"opencl_runtime_probed\":" << (options.backend == MNN_FORWARD_OPENCL ? "true" : "false")
            << ",\"opencl_runtime_created\":" << (runtime_opencl_available ? "true" : "false")
            << ",\"session_main_backend\":" << session_main_backend
            << ",\"cpu_backup_threads\":" << options.threads
            << ",\"execution_backend_counts_after_successful_run\":" << (backend_counts_valid ? "true" : "false")
            << ",\"backend_count_unit\":\"execution_commands_not_onnx_nodes\",\"original_onnx_node_backend_verified\":false"
            << ",\"execution_command_backend_verified\":" << (backend_counts_valid && !last_unknown_backend_commands ? "true" : "false")
            << ",\"precision_requested\":" << Quote(options.low_precision ? "low" : "high")
            << ",\"weights_storage\":\"float16\",\"compute_precision_requested\":"
            << Quote(options.low_precision ? "mnn_low_device_dependent" : "float32")
            << ",\"threads_requested\":" << options.threads << ",\"chunk_frames\":" << chunk_frames
            << ",\"past_kv_frames\":127,\"key_frames\":" << key_frames
            << ",\"state_count\":36,\"host_layout\":\"CAFFE\",\"input_count\":" << inputs.size()
            << ",\"output_count\":" << outputs.size() << ",\"load_seconds\":" << load_seconds
            << ",\"frames_processed\":" << real_frames << ",\"graph_offset\":" << graph_offset
            << ",\"forward_calls\":" << forward_calls << ",\"last_decode_seconds\":" << last_decode_seconds
            << ",\"last_input_copy_seconds\":" << last_input_copy_seconds
            << ",\"last_run_session_seconds\":" << last_run_session_seconds
            << ",\"last_output_copy_seconds\":" << last_output_copy_seconds
            << ",\"timing_sync_policy\":\"normal_run_plus_output_host_copy_no_per_op_sync\""
            << ",\"profile_chunks\":" << profile.chunks
            << ",\"profile_nonstate_input_copy_seconds\":" << profile.nonstate_input
            << ",\"profile_state_input_copy_seconds\":" << profile.state_input
            << ",\"profile_run_enqueue_seconds\":" << profile.run_enqueue
            << ",\"profile_audio_copy_wait_seconds\":" << profile.audio_copy_wait
            << ",\"profile_state_output_copy_seconds\":" << profile.state_output
            << ",\"profile_state_cpu_repack_seconds\":" << profile.state_cpu_repack
            << ",\"profile_gpu_state_enqueue_seconds\":" << profile.gpu_enqueue
            << ",\"gpu_state_requested\":" << (options.backend == MNN_FORWARD_OPENCL && options.keep_gpu_state ? "true" : "false")
            << ",\"gpu_state_candidate_count\":" << gpu_state_candidate_count
            << ",\"gpu_state_copies\":" << profile.gpu_copies
            << ",\"gpu_state_bytes\":" << profile.gpu_bytes
            << ",\"gpu_state_h2d_skipped\":" << profile.gpu_h2d_skipped
            << ",\"gpu_state_d2h_skipped\":" << profile.gpu_d2h_skipped
            << ",\"gpu_state_copy_failures\":" << profile.gpu_copy_failures
            << ",\"terminal_state_copies_elided\":" << profile.terminal_state_copies_elided
#ifdef __ANDROID__
            << ",\"opencl_cache_read_status\":" << Quote(cache_read_status)
            << ",\"opencl_cache_read_bytes\":" << cache_read_bytes
            << ",\"opencl_cache_read_seconds\":" << cache_read_seconds
            << ",\"opencl_cache_snapshot_seconds\":" << cache_snapshot_seconds
            << ",\"opencl_cache_write_bytes\":" << cache_write_bytes
            << ",\"opencl_cache_write_status\":"
            << Quote(std::array<const char*, 4>{"idle", "queued", "stored", "failed"}
                         .at(static_cast<size_t>(cache_write->load())))
#endif
            << ",\"short_tail_terminal\":" << (short_tail ? "true" : "false")
            << ",\"cancelled\":" << (cancelled.load() ? "true" : "false") << ",\"failed\":" << (failed ? "true" : "false")
            << ",\"cancel_granularity\":\"session_boundary\",\"input_dtypes\":{";
        bool first = true;
        for (const auto& input : inputs) { if (!first) out << ','; first = false; out << Quote(input.first) << ':' << TypeJson(input.second.get()); }
        out << "},\"audio_dtype\":" << TypeJson(outputs.at("audio").get())
            << ",\"last_execution_backend_command_counts\":{";
        first = true;
        for (const auto& count : last_backend_commands) { if (!first) out << ','; first = false; out << Quote(std::to_string(count.first)) << ':' << count.second; }
        out << "},\"last_execution_backend_op_types\":{";
        first = true;
        for (const auto& entry : last_backend_op_types) {
            if (!first) out << ','; first = false; out << Quote(std::to_string(entry.first)) << ":{";
            bool first_op = true;
            for (const auto& op : entry.second) { if (!first_op) out << ','; first_op = false; out << Quote(op.first) << ':' << op.second; }
            out << '}';
        }
        out << "},\"last_unknown_execution_backend_commands\":" << last_unknown_backend_commands << '}';
        return out.str();
    }

#ifdef __ANDROID__
    std::string opencl_cache_path;
    const char* cache_read_status = "disabled";
    bool cache_accepted = false;
    size_t cache_read_bytes = 0, cache_write_bytes = 0;
    double cache_read_seconds = 0, cache_snapshot_seconds = 0;
    std::shared_ptr<std::atomic<CacheWrite>> cache_write =
        std::make_shared<std::atomic<CacheWrite>>(CacheWrite::Idle);
#endif
    MnnIncrementalCodecOptions options;
    MNN::BackendConfig backend;
    // Declared before net: explicit runtime ownership outlives every Session/net.
    MNN::RuntimeInfo runtimes;
    std::unique_ptr<MNN::Interpreter, void(*)(MNN::Interpreter*)> net;
    MNN::Session* session = nullptr;
    std::map<std::string, MNN::Tensor*> device_inputs, device_outputs;
    std::map<std::string, std::unique_ptr<MNN::Tensor>> inputs, outputs;
    std::vector<std::pair<std::string, std::string>> states;
    std::vector<bool> state_on_gpu;
#ifdef __ANDROID__
    std::vector<GpuStateCopy> gpu_state_copies;
#endif
    size_t gpu_state_candidate_count = 0;
    struct {
        double nonstate_input = 0, state_input = 0, run_enqueue = 0, audio_copy_wait = 0;
        double state_output = 0, state_cpu_repack = 0, gpu_enqueue = 0;
        uint64_t chunks = 0, gpu_copies = 0, gpu_bytes = 0, gpu_h2d_skipped = 0, gpu_d2h_skipped = 0;
        uint64_t gpu_copy_failures = 0, terminal_state_copies_elided = 0;
    } profile;
    mutable std::mutex mutex;
    std::atomic<bool> cancelled{false};
    bool failed = false, short_tail = false;
    bool runtime_opencl_available = false, backend_counts_valid = false;
    int32_t session_main_backend = -1;
    int chunk_frames = 0, key_frames = 0;
    std::map<int, int64_t> last_backend_commands;
    std::map<int, std::map<std::string, int64_t>> last_backend_op_types;
    int64_t last_unknown_backend_commands = 0;
    int64_t graph_offset = 0, real_frames = 0, forward_calls = 0;
    double load_seconds = 0, last_decode_seconds = 0;
    double last_input_copy_seconds = 0, last_run_session_seconds = 0, last_output_copy_seconds = 0;
};

MnnIncrementalCodec::MnnIncrementalCodec(const std::string& path, const MnnIncrementalCodecOptions& options)
    : impl_(std::make_unique<Impl>(path, options)) {}
MnnIncrementalCodec::~MnnIncrementalCodec() = default;
std::vector<float> MnnIncrementalCodec::Decode(const int64_t* codes, int frames) { return impl_->Decode(codes, frames); }
void MnnIncrementalCodec::Reset() { std::lock_guard<std::mutex> lock(impl_->mutex); impl_->ResetLocked(); }
void MnnIncrementalCodec::Cancel() { impl_->cancelled.store(true, std::memory_order_release); }
std::vector<float> MnnIncrementalCodec::Flush() {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->Ready();
#ifdef __ANDROID__
    impl_->QueueOpenClCache();
#endif
    return {};
}
int64_t MnnIncrementalCodec::FramesProcessed() const { std::lock_guard<std::mutex> lock(impl_->mutex); return impl_->real_frames; }
int MnnIncrementalCodec::ChunkFrames() const { return impl_->chunk_frames; }
double MnnIncrementalCodec::LoadSeconds() const { return impl_->load_seconds; }
std::string MnnIncrementalCodec::RuntimeJson() const { std::lock_guard<std::mutex> lock(impl_->mutex); return impl_->JsonLocked(); }
}  // namespace audio8

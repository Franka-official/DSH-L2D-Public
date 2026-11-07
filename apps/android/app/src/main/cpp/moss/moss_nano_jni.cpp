#include <jni.h>
#include <onnxruntime_cxx_api.h>
#include <sentencepiece_processor.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
// Tensor names and layouts follow OpenMOSS/MOSS-TTS-Nano's official Android example
// and the format-v1 ONNX manifests.
constexpr int kQuantizers = 16;
constexpr int kWidth = 17;
constexpr int kCodebook = 1024;
constexpr int kHidden = 768;
constexpr int kGlobalLayers = 12;
constexpr int kSamplesPerFrame = 3840;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void initOrt() {
    static const bool initialized = [] {
        const auto* api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
        require(api != nullptr, "MOSS ONNX Runtime ABI unavailable");
        Ort::InitApi(api);
        return true;
    }();
    (void)initialized;
}

struct Cancelled {};

std::string pathString(JNIEnv* jni, jstring input) {
    const char* path = jni->GetStringUTFChars(input, nullptr);
    require(path != nullptr, "Cannot read MOSS model path");
    const std::string value(path);
    jni->ReleaseStringUTFChars(input, path);
    return value;
}

std::vector<int32_t> integers(JNIEnv* jni, jintArray source, int maximum) {
    const auto count = jni->GetArrayLength(source);
    require(count > 0 && count <= maximum, "Invalid MOSS prompt array");
    std::vector<int32_t> values(count);
    jni->GetIntArrayRegion(source, 0, count, values.data());
    require(!jni->ExceptionCheck(), "Cannot read MOSS prompt");
    return values;
}

int64_t integerAt(const Ort::Value& tensor, size_t at) {
    const auto info = tensor.GetTensorTypeAndShapeInfo();
    require(at < info.GetElementCount(), "Unexpected MOSS integer tensor size");
    if (info.GetElementType() == ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64) return tensor.GetTensorData<int64_t>()[at];
    if (info.GetElementType() == ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32) return tensor.GetTensorData<int32_t>()[at];
    throw std::runtime_error("Unexpected MOSS integer tensor type");
}

void throwJava(JNIEnv* jni, const char* type, const char* message) {
    if (jni->ExceptionCheck()) return;
    const auto klass = jni->FindClass(type);
    if (klass) jni->ThrowNew(klass, message);
}

struct Session {
    Ort::Env environment{ORT_LOGGING_LEVEL_WARNING, "moss-nano"};
    Ort::MemoryInfo memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    sentencepiece::SentencePieceProcessor tokenizer;
    Ort::Session prefill{nullptr};
    Ort::Session decode{nullptr};
    Ort::Session local{nullptr};
    Ort::Session codec{nullptr};
    std::vector<std::string> stateOutputNames{"global_hidden"};
    std::vector<std::string> stepInputNames{"input_ids", "past_valid_lengths"};

    Session(const std::string& tts, const std::string& codecDir, int threads) {
        const auto status = tokenizer.Load(tts + "/tokenizer.model");
        if (!status.ok()) throw std::runtime_error(status.ToString());
        Ort::SessionOptions options;
        options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        options.SetIntraOpNumThreads(std::clamp(threads, 1, 4));
        options.SetInterOpNumThreads(1);
        options.AddConfigEntry("session.intra_op.allow_spinning", "0");
        options.AddConfigEntry("session.inter_op.allow_spinning", "0");
        prefill = Ort::Session(environment, (tts + "/moss_tts_prefill.onnx").c_str(), options);
        decode = Ort::Session(environment, (tts + "/moss_tts_decode_step.onnx").c_str(), options);
        local = Ort::Session(environment, (tts + "/moss_tts_local_fixed_sampled_frame.onnx").c_str(), options);
        codec = Ort::Session(environment, (codecDir + "/moss_audio_tokenizer_decode_full.onnx").c_str(), options);
        for (int layer = 0; layer < kGlobalLayers; ++layer) {
            const auto index = std::to_string(layer);
            stateOutputNames.emplace_back("present_key_" + index);
            stateOutputNames.emplace_back("present_value_" + index);
            stepInputNames.emplace_back("past_key_" + index);
            stepInputNames.emplace_back("past_value_" + index);
        }
    }

    template<typename T, size_t N>
    Ort::Value tensor(T* data, size_t size, const std::array<int64_t, N>& shape) const {
        return Ort::Value::CreateTensor<T>(memory, data, size, shape.data(), N);
    }

    static std::vector<const char*> names(const std::vector<std::string>& source) {
        std::vector<const char*> result;
        result.reserve(source.size());
        for (const auto& name : source) result.push_back(name.c_str());
        return result;
    }

    std::vector<float> generate(const std::string& text, const std::vector<int32_t>& prefix,
        const std::vector<int32_t>& afterReference, const std::vector<int32_t>& assistant,
        const std::vector<int32_t>& prompt, int maxFrames, uint64_t seed,
        int32_t& frames, int32_t& ended, JNIEnv* jni, jobject cancel, jmethodID cancellation) {
        const auto checkCancel = [&] {
            if (jni->CallBooleanMethod(cancel, cancellation) || jni->ExceptionCheck()) throw Cancelled{};
        };
        checkCancel();
        std::vector<int> tokens;
        const auto status = tokenizer.Encode(text, &tokens);
        if (!status.ok()) throw std::runtime_error(status.ToString());
        require(!tokens.empty() && tokens.size() <= 256, "MOSS 文本太长，请缩短后重试");
        require(prompt.size() % kQuantizers == 0, "Invalid MOSS reference shape");

        std::vector<int32_t> rows;
        auto textRow = [&rows](int token) {
            require(token >= 0 && token < 16384, "Invalid MOSS text token");
            rows.push_back(token);
            rows.insert(rows.end(), kQuantizers, kCodebook);
        };
        for (const auto token : prefix) textRow(token);
        textRow(6);
        for (size_t i = 0; i < prompt.size(); i += kQuantizers) {
            rows.push_back(8);
            rows.insert(rows.end(), prompt.begin() + i, prompt.begin() + i + kQuantizers);
        }
        textRow(7);
        for (const auto token : afterReference) textRow(token);
        for (const auto token : tokens) textRow(token);
        for (const auto token : assistant) textRow(token);
        textRow(6);

        int32_t positions = static_cast<int32_t>(rows.size() / kWidth);
        require(positions <= 512, "MOSS 提示上下文太长");
        std::vector<int32_t> mask(positions, 1);
        std::array<Ort::Value, 2> inputs{
            tensor(rows.data(), rows.size(), std::array<int64_t, 3>{1, positions, kWidth}),
            tensor(mask.data(), mask.size(), std::array<int64_t, 2>{1, positions})
        };
        const char* prefillInputs[] = {"input_ids", "attention_mask"};
        const auto stateNames = names(stateOutputNames);
        const auto stepNames = names(stepInputNames);
        auto state = prefill.Run(Ort::RunOptions{}, prefillInputs, inputs.data(), inputs.size(),
            stateNames.data(), stateNames.size());

        std::array<int32_t, kQuantizers * kCodebook> seen{};
        std::vector<int32_t> codes;
        codes.reserve(maxFrames * kQuantizers);
        std::mt19937_64 rng(seed);
        std::uniform_real_distribution<float> uniform(1e-6f, 1.f - 1e-6f);
        const char* localInputs[] = {"global_hidden", "repetition_seen_mask", "assistant_random_u", "audio_random_u"};
        const char* localOutputs[] = {"should_continue", "frame_token_ids"};

        for (int step = 0; step < maxFrames; ++step) {
            checkCancel();
            const auto info = state.front().GetTensorTypeAndShapeInfo();
            const auto shape = info.GetShape();
            require(info.GetElementType() == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT &&
                (shape.size() == 2 || shape.size() == 3) && shape.front() == 1 &&
                shape.back() == kHidden && info.GetElementCount() >= kHidden, "Invalid MOSS hidden state");
            const auto* last = state.front().GetTensorData<float>() + info.GetElementCount() - kHidden;
            std::array<float, kHidden> hidden;
            std::copy(last, last + kHidden, hidden.begin());
            float assistantRandom = uniform(rng);
            std::array<float, kQuantizers> random;
            for (auto& value : random) value = uniform(rng);
            std::array<Ort::Value, 4> frameInputs{
                tensor(hidden.data(), hidden.size(), std::array<int64_t, 2>{1, kHidden}),
                tensor(seen.data(), seen.size(), std::array<int64_t, 3>{1, kQuantizers, kCodebook}),
                tensor(&assistantRandom, 1, std::array<int64_t, 1>{1}),
                tensor(random.data(), random.size(), std::array<int64_t, 2>{1, kQuantizers})
            };
            auto frame = local.Run(Ort::RunOptions{}, localInputs, frameInputs.data(), frameInputs.size(),
                localOutputs, 2);
            if (integerAt(frame[0], 0) == 0) {
                ended = 1;
                break;
            }
            std::array<int32_t, kWidth> row{};
            row[0] = 9;
            for (int i = 0; i < kQuantizers; ++i) {
                const auto token = integerAt(frame[1], i);
                require(token >= 0 && token < kCodebook, "MOSS audio code is out of range");
                row[i + 1] = static_cast<int32_t>(token);
                codes.push_back(row[i + 1]);
                seen[i * kCodebook + token] = 1;
            }
            if (step + 1 == maxFrames) break;
            std::vector<Ort::Value> nextInputs;
            nextInputs.reserve(stepNames.size());
            nextInputs.push_back(tensor(row.data(), row.size(), std::array<int64_t, 3>{1, 1, kWidth}));
            nextInputs.push_back(tensor(&positions, 1, std::array<int64_t, 1>{1}));
            for (size_t i = 1; i < state.size(); ++i) nextInputs.push_back(std::move(state[i]));
            state = decode.Run(Ort::RunOptions{}, stepNames.data(), nextInputs.data(), nextInputs.size(),
                stateNames.data(), stateNames.size());
            ++positions;
        }

        checkCancel();
        frames = static_cast<int32_t>(codes.size() / kQuantizers);
        require(frames > 0, "MOSS 没有生成声码");
        std::array<Ort::Value, 2> codecInputs{
            tensor(codes.data(), codes.size(), std::array<int64_t, 3>{1, frames, kQuantizers}),
            tensor(&frames, 1, std::array<int64_t, 1>{1})
        };
        const char* codecInputNames[] = {"audio_codes", "audio_code_lengths"};
        const char* codecOutputNames[] = {"audio", "audio_lengths"};
        auto output = codec.Run(Ort::RunOptions{}, codecInputNames, codecInputs.data(), codecInputs.size(),
            codecOutputNames, 2);
        checkCancel();
        const auto info = output[0].GetTensorTypeAndShapeInfo();
        const auto shape = info.GetShape();
        require(info.GetElementType() == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT && shape.size() == 3 &&
            shape[0] == 1 && shape[1] == 2 && shape[2] > 0 &&
            shape[2] <= (frames + 1) * kSamplesPerFrame, "Invalid MOSS PCM shape");
        const auto samples = std::min(integerAt(output[1], 0), shape[2]);
        require(samples > 0, "MOSS 解码没有输出音频");
        const float* planar = output[0].GetTensorData<float>();
        std::vector<float> mono(samples);
        for (size_t i = 0; i < mono.size(); ++i) {
            const float sample = (planar[i] + planar[shape[2] + i]) * .5f;
            require(std::isfinite(sample), "Invalid MOSS PCM sample");
            mono[i] = std::clamp(sample, -1.f, 1.f);
        }
        return mono;
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_prts_client_audio_moss_MossNanoNative_open(JNIEnv* jni, jobject, jstring tts, jstring codec, jint threads) {
    try {
        initOrt();
        auto session = std::make_unique<Session>(pathString(jni, tts), pathString(jni, codec), threads);
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception& error) {
        throwJava(jni, "java/lang/IllegalStateException", error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_prts_client_audio_moss_MossNanoNative_generate(JNIEnv* jni, jobject, jlong handle, jbyteArray rawText,
    jintArray prefix, jintArray afterReference, jintArray assistant, jintArray prompt, jint maxFrames, jlong seed,
    jintArray report, jobject cancel) {
    try {
        require(handle != 0 && maxFrames >= 1 && maxFrames <= 160 &&
            jni->GetArrayLength(report) == 2, "Invalid MOSS request");
        const auto count = jni->GetArrayLength(rawText);
        require(count > 0 && count <= 8192, "Invalid MOSS text length");
        std::string text(count, '\0');
        jni->GetByteArrayRegion(rawText, 0, count, reinterpret_cast<jbyte*>(text.data()));
        require(!jni->ExceptionCheck(), "Cannot read MOSS text");
        const auto klass = jni->GetObjectClass(cancel);
        const auto cancellation = jni->GetMethodID(klass, "cancelled", "()Z");
        jni->DeleteLocalRef(klass);
        require(cancellation != nullptr, "Missing MOSS cancellation callback");

        jint metadata[2]{};
        auto* session = reinterpret_cast<Session*>(handle);
        const auto audio = session->generate(text, integers(jni, prefix, 128), integers(jni, afterReference, 128),
            integers(jni, assistant, 128), integers(jni, prompt, 256 * kQuantizers), maxFrames, seed,
            metadata[0], metadata[1], jni, cancel, cancellation);
        auto output = jni->NewFloatArray(static_cast<jsize>(audio.size()));
        if (output) {
            jni->SetFloatArrayRegion(output, 0, static_cast<jsize>(audio.size()), audio.data());
            if (!jni->ExceptionCheck()) jni->SetIntArrayRegion(report, 0, 2, metadata);
        }
        return output;
    } catch (const Cancelled&) {
        throwJava(jni, "java/util/concurrent/CancellationException", "MOSS synthesis cancelled");
    } catch (const std::exception& error) {
        throwJava(jni, "java/lang/IllegalStateException", error.what());
    }
    return nullptr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_prts_client_audio_moss_MossNanoNative_encodeVoice(JNIEnv* jni, jobject, jstring graph, jfloatArray mono) {
    try {
        constexpr int kRate = 48000;
        const int32_t count = jni->GetArrayLength(mono);
        require(count >= kRate / 2 && count <= kRate * 8, "Invalid MOSS reference WAV duration");
        std::vector<float> stereo(static_cast<size_t>(count) * 2);
        jni->GetFloatArrayRegion(mono, 0, count, stereo.data());
        require(!jni->ExceptionCheck(), "Cannot read MOSS reference WAV");
        std::copy_n(stereo.data(), count, stereo.data() + count);

        initOrt();
        Ort::Env environment{ORT_LOGGING_LEVEL_WARNING, "moss-voice-encoder"};
        Ort::SessionOptions options;
        options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        options.SetIntraOpNumThreads(2);
        options.SetInterOpNumThreads(1);
        options.DisableCpuMemArena();
        options.DisableMemPattern();
        options.AddConfigEntry("session.intra_op.allow_spinning", "0");
        Ort::Session encoder(environment, pathString(jni, graph).c_str(), options);
        const auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        const std::array<int64_t, 3> waveformShape{1, 2, count};
        const std::array<int64_t, 1> lengthShape{1};
        int32_t inputLength = count;
        std::array<Ort::Value, 2> input{
            Ort::Value::CreateTensor<float>(memory, stereo.data(), stereo.size(), waveformShape.data(), waveformShape.size()),
            Ort::Value::CreateTensor<int32_t>(memory, &inputLength, 1, lengthShape.data(), lengthShape.size())};
        const char* inputs[]{"waveform", "input_lengths"};
        const char* outputs[]{"audio_codes", "audio_code_lengths"};
        auto result = encoder.Run(Ort::RunOptions{}, inputs, input.data(), input.size(), outputs, 2);
        const auto shape = result[0].GetTensorTypeAndShapeInfo().GetShape();
        const auto frames = integerAt(result[1], 0);
        require(shape.size() == 3 && shape[0] == 1 && shape[2] == kQuantizers &&
            frames >= 1 && frames <= 256 && frames <= shape[1], "Unexpected MOSS voice code shape");
        std::vector<jint> codes(static_cast<size_t>(frames) * kQuantizers);
        for (size_t i = 0; i < codes.size(); ++i) {
            const auto value = integerAt(result[0], i);
            require(value >= 0 && value < kCodebook, "MOSS voice code outside codebook");
            codes[i] = static_cast<jint>(value);
        }
        const auto array = jni->NewIntArray(static_cast<jsize>(codes.size()));
        if (array) jni->SetIntArrayRegion(array, 0, static_cast<jsize>(codes.size()), codes.data());
        return array;
    } catch (const std::exception& error) {
        throwJava(jni, "java/lang/IllegalStateException", error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_prts_client_audio_moss_MossNanoNative_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}

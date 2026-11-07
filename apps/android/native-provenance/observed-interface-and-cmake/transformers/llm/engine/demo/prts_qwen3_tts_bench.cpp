// Thin benchmark of the official generateTTS path; no model/graph/precision changes.
#include "llm/llm.hpp"
#include <audio/audio.hpp>
#include <chrono>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include <fcntl.h>
#include <unistd.h>

using MNN::Transformer::Llm;
using MNN::Transformer::LlmContext;
using Clock = std::chrono::steady_clock;
static constexpr int kSampleRate = 24000;

static double ms(Clock::time_point a, Clock::time_point b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
}
static std::string quote(const std::string& s) {
    std::string r = "\"";
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { r += '\\'; r += char(c); }
        else if (c < 32) { char b[7]; std::snprintf(b, sizeof(b), "\\u%04x", c); r += b; }
        else r += char(c);
    }
    return r + "\"";
}
static std::string number(double value) {
    if (!std::isfinite(value)) return "null";
    std::ostringstream s; s << std::fixed << std::setprecision(6) << value; return s.str();
}
static std::string readSmallFile(const char* path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot read text file");
    std::string s((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (s.empty() || s.size() > 16384) throw std::runtime_error("text must be 1..16384 UTF-8 bytes");
    return s;
}
static bool exists(const std::string& path) { std::ifstream f(path, std::ios::binary); return bool(f); }
struct Counters {
    int64_t prefill = 0, decode = 0, audio = 0;
    explicit Counters(const LlmContext* c) {
        if (c) { prefill=c->prefill_us; decode=c->decode_us; audio=c->audio_us; }
    }
};
class JsonLines {
public:
    explicit JsonLines(const std::string& path) {
        int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_EXCL, 0600);
        if (fd < 0) throw std::runtime_error("result exists or output parent is unavailable");
        file_ = ::fdopen(fd, "w");
        if (!file_) { ::close(fd); throw std::runtime_error("cannot open report stream"); }
    }
    ~JsonLines() { if (file_) std::fclose(file_); }
    void emit(const std::string& line) {
        std::puts(line.c_str()); std::fflush(stdout);
        std::fprintf(file_, "%s\n", line.c_str()); std::fflush(file_);
    }
private:
    FILE* file_ = nullptr;
};

int main(int argc, char** argv) {
    const auto processStart = Clock::now();
    if (argc != 10) {
        std::fprintf(stderr, "Usage: %s <config.json> <utf8-text-file> <language> <reference.wav> <output-prefix> <label> <max-frames> <repeats> <tmp-dir>\n", argv[0]);
        return 2;
    }
    try {
        const std::string config=argv[1], text=readSmallFile(argv[2]), language=argv[3], refAudio=argv[4];
        const std::string prefix=argv[5], label=argv[6], tmpDir=argv[9];
        const int maxFrames=std::stoi(argv[7]), repeats=std::stoi(argv[8]);
        if (maxFrames < 1 || maxFrames > 4096 || repeats < 1 || repeats > 8) throw std::runtime_error("invalid frame/repeat bound");
        if (!exists(config) || !exists(refAudio)) throw std::runtime_error("config or reference file is absent");
        for (int i=0; i<repeats; ++i) if (exists(prefix + "-" + std::to_string(i+1) + ".wav")) throw std::runtime_error("wave output already exists");
        JsonLines report(prefix + ".jsonl");
        auto emit=[&](const std::string& fields) { report.emit("{\"label\":"+quote(label)+",\"pid\":"+std::to_string(::getpid())+","+fields+"}"); };
        ::setenv("PRTS_QWEN_BENCH_DIAG", "1", 1);
        ::setenv("PRTS_QWEN_DIAG_CASE", "load", 1);
        emit("\"event\":\"process_start\",\"textBytes\":"+std::to_string(text.size())+",\"language\":"+quote(language)+
             ",\"maxFrames\":"+std::to_string(maxFrames)+",\"repeats\":"+std::to_string(repeats)+
             ",\"callbackContract\":\"current_official_path_one_final_complete_waveform_not_streaming\",\"osCacheFlushed\":false");
        const auto loadStart=Clock::now();
        std::unique_ptr<Llm> llm(Llm::createLLM(config));
        if (!llm) throw std::runtime_error("createLLM failed");
        // Match the official demo's synchronous path. No backend/precision/thread override here.
        if (!llm->set_config("{\"tmp_path\":" + quote(tmpDir) + ",\"async\":false}")) throw std::runtime_error("set_config failed");
        if (!llm->load()) throw std::runtime_error("load failed");
        const auto loadEnd=Clock::now();
        emit("\"event\":\"loaded\",\"loadWallMs\":"+number(ms(loadStart,loadEnd))+
             ",\"processElapsedMs\":"+number(ms(processStart,loadEnd)));
        for (int iteration=1; iteration<=repeats; ++iteration) {
            const std::string caseId=label+"-"+std::to_string(iteration);
            ::setenv("PRTS_QWEN_DIAG_CASE", caseId.c_str(), 1);
            std::vector<float> waveform;
            int callbackCount=0;
            size_t firstPayload=0;
            bool sawFinal=false;
            Clock::time_point firstPcm, lastPcm;
            const Counters before(llm->getContext());
            emit("\"event\":\"case_start\",\"iteration\":"+std::to_string(iteration)+
                 ",\"phase\":"+quote(iteration==1 ? "new_process_first" : "same_process_repeat"));
            Clock::time_point generateStart;
            llm->setWavformCallback([&](const float* ptr, size_t size, bool lastChunk) {
                const auto arrived=Clock::now();
                ++callbackCount;
                if (!ptr || size==0) return false;
                if (waveform.empty()) { firstPcm=arrived; firstPayload=size; }
                lastPcm=arrived; sawFinal=sawFinal||lastChunk;
                waveform.insert(waveform.end(),ptr,ptr+size);
                if (callbackCount==1) emit("\"event\":\"first_pcm\",\"iteration\":"+std::to_string(iteration)+
                    ",\"fromGenerateMs\":"+number(ms(generateStart,arrived))+",\"fromProcessMs\":"+number(ms(processStart,arrived))+
                    ",\"samples\":"+std::to_string(size)+",\"lastChunk\":"+(lastChunk?"true":"false"));
                return true;
            });
            generateStart=Clock::now();
            const bool generated=llm->generateTTS(text,language,maxFrames,refAudio);
            const auto generateEnd=Clock::now();
            const auto* context=llm->getContext();
            const Counters after(context);
            double squareSum=0.0, peak=0.0;
            size_t nonfinite=0, aboveOne=0;
            for (float value : waveform) {
                if (!std::isfinite(value)) { ++nonfinite; continue; }
                squareSum+=double(value)*double(value);
                peak=std::max(peak,std::abs(double(value)));
                if (std::abs(value)>1.0f) ++aboveOne;
            }
            const double duration=double(waveform.size())/kSampleRate;
            const std::string wavPath=prefix+"-"+std::to_string(iteration)+".wav";
            const auto saveStart=Clock::now();
            bool saved=false;
            if (generated && nonfinite==0 && !waveform.empty()) {
                // Preserve raw model amplitudes. save() is the official WAV serialization path.
                auto audio=MNN::Express::_Const(waveform.data(), {static_cast<int>(waveform.size())},
                                                MNN::Express::NCHW, halide_type_of<float>());
                saved=MNN::AUDIO::save(wavPath,audio,kSampleRate);
            }
            const auto saveEnd=Clock::now();
            emit("\"event\":\"case_result\",\"iteration\":"+std::to_string(iteration)+
                 ",\"generated\":"+(generated?"true":"false")+",\"wavSaved\":"+(saved?"true":"false")+
                 ",\"wavPath\":"+quote(wavPath)+",\"sampleRate\":24000,\"sampleCount\":"+std::to_string(waveform.size())+
                 ",\"audioSeconds\":"+number(duration)+",\"generateWallMs\":"+number(ms(generateStart,generateEnd))+
                 ",\"firstPcmFromGenerateMs\":"+(waveform.empty()?"null":number(ms(generateStart,firstPcm)))+
                 ",\"lastPcmFromGenerateMs\":"+(waveform.empty()?"null":number(ms(generateStart,lastPcm)))+
                 ",\"completeWavFromGenerateMs\":"+number(ms(generateStart,saveEnd))+
                 ",\"wavSaveMs\":"+number(ms(saveStart,saveEnd))+
                 ",\"generationRtf\":"+(duration>0?number(ms(generateStart,generateEnd)/(1000*duration)):"null")+
                 ",\"callbackCount\":"+std::to_string(callbackCount)+",\"firstPayloadSamples\":"+std::to_string(firstPayload)+
                 ",\"sawFinalChunk\":"+(sawFinal?"true":"false")+",\"nonfiniteSamples\":"+std::to_string(nonfinite)+
                 ",\"samplesAboveOne\":"+std::to_string(aboveOne)+",\"rawPeak\":"+number(peak)+
                 ",\"rawRms\":"+(waveform.empty()?"null":number(std::sqrt(squareSum/waveform.size())))+
                 ",\"generatedFrames\":"+std::to_string(context?context->gen_seq_len:0)+
                 ",\"promptTokens\":"+std::to_string(context?context->prompt_len:0)+
                 ",\"outputCodeCount\":"+std::to_string(context?context->output_tokens.size():0)+
                 ",\"status\":"+std::to_string(context?static_cast<int>(context->status):-99)+
                 ",\"frameCapRisk\":"+((context && context->gen_seq_len>=maxFrames)?"true":"false")+
                 ",\"contextDeltaUs\":{\"prefill\":"+std::to_string(after.prefill-before.prefill)+
                 ",\"decode\":"+std::to_string(after.decode-before.decode)+",\"audio\":"+std::to_string(after.audio-before.audio)+"}"+
                 ",\"contextCountersAreOfficialNotGpuKernelTimers\":true,\"amplitudeNormalized\":false");
            if (!generated || !saved || nonfinite!=0 || waveform.empty()) return 10;
        }
        emit("\"event\":\"completed\",\"processWallMs\":"+number(ms(processStart,Clock::now())));
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "{\"event\":\"bench_error\",\"message\":%s}\n",quote(error.what()).c_str());
        return 11;
    }
}

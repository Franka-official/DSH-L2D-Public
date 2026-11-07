// Isolated adapter for pinned official MNN Qwen3-TTS. No CV3 MNN symbols are imported.
#include <jni.h>
#include <android/log.h>
#include "llm/llm.hpp"
#include <audio/audio.hpp>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include <sys/resource.h>

namespace {
using MNN::Transformer::Llm;
using Clock = std::chrono::steady_clock;
std::mutex runtimeMutex;
std::unique_ptr<Llm> runtime;
std::string loadedConfig;
int loadedThreads = 0;
// The low bit is the cancellation flag. CAS prevents a late cancellation
// from one request from cancelling a newer request that has already started.
std::atomic<int64_t> activeState{0};
constexpr int sampleRate = 24000;
double milliseconds(Clock::time_point a, Clock::time_point b) { return std::chrono::duration<double,std::milli>(b-a).count(); }
std::string number(double value) { if (!std::isfinite(value)) return "null"; std::ostringstream out; out<<std::fixed<<std::setprecision(6)<<value; return out.str(); }
std::string quote(const std::string& value) {
    std::string out="\"";
    for (unsigned char c:value) {
        if (c=='"'||c=='\\') { out+='\\'; out+=char(c); }
        else if(c<32) { char text[7]; std::snprintf(text,sizeof(text),"\\u%04x",c); out+=text; }
        else out+=char(c);
    }
    return out+'"';
}
// JNI's modified UTF-8 is not valid UTF-8 for supplementary code points.
std::string utf8(JNIEnv* env,jstring value) {
    if(!value) throw std::runtime_error("null native argument");
    const jchar* chars=env->GetStringChars(value,nullptr);
    if(!chars) throw std::runtime_error("cannot read native argument");
    const jsize count=env->GetStringLength(value);
    std::string out;
    for(jsize i=0;i<count;++i) {
        uint32_t code=chars[i];
        if(code>=0xd800&&code<=0xdbff&&i+1<count&&chars[i+1]>=0xdc00&&chars[i+1]<=0xdfff) code=0x10000+((code-0xd800)<<10)+(chars[++i]-0xdc00);
        else if(code>=0xd800&&code<=0xdfff) code=0xfffd;
        if(code<0x80) out+=char(code);
        else if(code<0x800) { out+=char(0xc0|(code>>6)); out+=char(0x80|(code&63)); }
        else if(code<0x10000) { out+=char(0xe0|(code>>12)); out+=char(0x80|((code>>6)&63)); out+=char(0x80|(code&63)); }
        else { out+=char(0xf0|(code>>18)); out+=char(0x80|((code>>12)&63)); out+=char(0x80|((code>>6)&63)); out+=char(0x80|(code&63)); }
    }
    env->ReleaseStringChars(value,chars);
    return out;
}
void log(const std::string& line) { __android_log_print(ANDROID_LOG_INFO,"PRTS-QwenTTS","%s",line.c_str()); }
void stage(int64_t id,const char* name,double value) { log("{\"event\":\"qwen_tts_stage\",\"request_id\":"+std::to_string(id)+",\"stage\":"+quote(name)+",\"duration_ms\":"+number(value)+"}"); }
struct Counters { int64_t prefill=0,decode=0,audio=0; explicit Counters(const MNN::Transformer::LlmContext* c) { if(c){prefill=c->prefill_us;decode=c->decode_us;audio=c->audio_us;} } };
int64_t counterDelta(int64_t before,int64_t after) { return after>=before?after-before:after; }
struct ActiveGuard { explicit ActiveGuard(int64_t id) {activeState=id*2;} ~ActiveGuard(){activeState=0;} };
}

extern "C" JNIEXPORT jstring JNICALL Java_com_prts_client_audio_qwen_QwenTtsNative_synthesize(
    JNIEnv* env,jobject,jstring configArg,jstring textArg,jstring languageArg,jstring referenceArg,
    jstring outputArg,jstring temporaryArg,jlong requestId,jint maxFrames,jobject cancelledArg,jobject pcmReceiver,jint chunkFrames,jint cpuThreads) {
    const auto entered=Clock::now();
    std::lock_guard<std::mutex> guard(runtimeMutex);
    ActiveGuard active(requestId);
    try {
        if (cpuThreads != 4 && cpuThreads != 6 && cpuThreads != 8) throw std::runtime_error("CPU threads must be 4, 6 or 8");
        if (!cancelledArg) throw std::runtime_error("Cancellation atomic is required");
        jclass cancelledClass = env->GetObjectClass(cancelledArg);
        jmethodID getCancelled = env->GetMethodID(cancelledClass, "get", "()Z");
        env->DeleteLocalRef(cancelledClass);
        if (!getCancelled || env->ExceptionCheck()) { env->ExceptionClear(); throw std::runtime_error("Cancellation API unavailable"); }
        const bool streaming = pcmReceiver != nullptr;
        if (streaming && (chunkFrames < 1 || chunkFrames > 64)) throw std::runtime_error("Invalid streaming chunk bound");
        jmethodID deliverPcm = nullptr;
        if (streaming) {
            jclass receiverClass = env->GetObjectClass(pcmReceiver);
            deliverPcm = env->GetMethodID(receiverClass, "onPcm", "([FZI)Z");
            env->DeleteLocalRef(receiverClass);
            if (!deliverPcm || env->ExceptionCheck()) { env->ExceptionClear(); throw std::runtime_error("PCM receiver API unavailable"); }
        }
        bool jniCallbackError = false;
        auto isCancelled = [&]() {
            const bool atomicCancelled = env->CallBooleanMethod(cancelledArg, getCancelled) == JNI_TRUE;
            if (env->ExceptionCheck()) { env->ExceptionClear(); jniCallbackError = true; return true; }
            return atomicCancelled || activeState.load() == requestId * 2 + 1;
        };
        const std::string config=utf8(env,configArg),text=utf8(env,textArg),language=utf8(env,languageArg),reference=utf8(env,referenceArg),output=utf8(env,outputArg),temporary=utf8(env,temporaryArg);
        if(text.empty()||text.size()>16384||maxFrames<1||maxFrames>2048) throw std::runtime_error("invalid speech input bound");
        if(language!="chinese"&&language!="japanese"&&language!="english") throw std::runtime_error("unsupported output language");
        if(std::ifstream(output).good()) throw std::runtime_error("wave output already exists");
        const bool reused=runtime&&loadedConfig==config&&loadedThreads==cpuThreads;
        const auto loadStart=Clock::now();
        if(!reused) {
            runtime.reset();loadedConfig.clear();loadedThreads=0;
            runtime.reset(Llm::createLLM(config));
            if(!runtime) throw std::runtime_error("createLLM failed");
            if(!runtime->set_config("{\"backend_type\":\"cpu\",\"thread_num\":"+std::to_string(cpuThreads)+",\"precision\":\"high\",\"qwen3_tts_cp_cpu_threads\":"+std::to_string(cpuThreads)+",\"async\":false,\"tmp_path\":"+quote(temporary)+"}")) throw std::runtime_error("CPU runtime configuration failed");
            if(!runtime->load()) throw std::runtime_error("CPU runtime load failed");
            loadedConfig=config;loadedThreads=cpuThreads;
        }
        const double loadMs=milliseconds(loadStart,Clock::now());stage(requestId,"load",loadMs);
        std::vector<float> waveform;
        int callbackCount=0, pcmChunks=0, observedFrames=0, firstPcmFrames=0;
        bool finalChunk=false, observedEos=false, firstPcmBeforeEos=false, callbackRejected=false;
        double firstPcmMs=-1;
        Clock::time_point generationStart;
        runtime->setTtsCancelCallback(isCancelled);
        runtime->configureTtsStreaming(streaming ? chunkFrames : 0, 25);
        runtime->setTtsProgressCallback([&](int frames, bool eos) { observedFrames=frames; observedEos=eos; });
        runtime->setWavformCallback([&](const float* samples,size_t count,bool last){
            ++callbackCount;
            if(isCancelled()) return false;
            if(count>24000u*120u||waveform.size()+count>24000u*120u||(!samples&&count)) {callbackRejected=true;return false;}
            for(size_t i=0;i<count;++i) if(!std::isfinite(samples[i])) {callbackRejected=true;return false;}
            if(count) {
                if(firstPcmMs<0) { firstPcmMs=milliseconds(generationStart,Clock::now()); firstPcmFrames=observedFrames; firstPcmBeforeEos=!observedEos; }
                waveform.insert(waveform.end(),samples,samples+count);
                ++pcmChunks;
            }
            if(streaming) {
                jfloatArray pcm=env->NewFloatArray(static_cast<jsize>(count));
                if(!pcm) {if(env->ExceptionCheck())env->ExceptionClear();jniCallbackError=true;return false;}
                if(count)env->SetFloatArrayRegion(pcm,0,static_cast<jsize>(count),samples);
                jboolean accepted=env->CallBooleanMethod(pcmReceiver,deliverPcm,pcm,last?JNI_TRUE:JNI_FALSE,observedFrames);
                env->DeleteLocalRef(pcm);
                if(env->ExceptionCheck()) {env->ExceptionClear();jniCallbackError=true;return false;}
                if(accepted!=JNI_TRUE) {callbackRejected=true;return false;}
                log("{\"event\":\"qwen_tts_pcm\",\"request_id\":"+std::to_string(requestId)+",\"chunk\":"+std::to_string(callbackCount)+",\"samples\":"+std::to_string(count)+",\"generated_frames\":"+std::to_string(observedFrames)+",\"codec_eos_seen\":"+(observedEos?"true":"false")+",\"final\":"+(last?"true":"false")+",\"elapsed_ms\":"+number(milliseconds(generationStart,Clock::now()))+"}");
            }
            finalChunk=finalChunk||last;
            return true;
        });
        const Counters before(runtime->getContext());
        generationStart=Clock::now();
        const bool generated=runtime->generateTTS(text,language,maxFrames,reference);
        const double generateMs=milliseconds(generationStart,Clock::now());stage(requestId,"generate",generateMs);
        runtime->setTtsCancelCallback({});
        runtime->setTtsProgressCallback({});
        runtime->setWavformCallback({});
        const auto* context=runtime->getContext();
        const Counters after(context);
        const int frames=context?context->gen_seq_len:0;
        const int status=context?static_cast<int>(context->status):-99;
        const bool codecEos=context&&context->tts_codec_eos;
        const bool cap=frames>=maxFrames&&!codecEos;
        const bool cancelObserved=context&&context->tts_cancel_observed;
        size_t nonfinite=0,aboveOne=0;double squares=0,peak=0;
        for(float value:waveform) {if(!std::isfinite(value)){++nonfinite;continue;}squares+=double(value)*value;peak=std::max(peak,std::abs(double(value)));if(std::abs(value)>1)++aboveOne;}
        const bool wasCancelled=isCancelled();
        const bool valid=generated&&!wasCancelled&&!cap&&nonfinite==0&&!waveform.empty()&&finalChunk&&status==1;
        bool saved=false;const auto saveStart=Clock::now();
        if(valid) {
            auto audio=MNN::Express::_Const(waveform.data(),{static_cast<int>(waveform.size())},MNN::Express::NCHW,halide_type_of<float>());
            saved=MNN::AUDIO::save(output,audio,sampleRate);
        }
        const double saveMs=milliseconds(saveStart,Clock::now());stage(requestId,"wav_save",saveMs);
        struct rusage usage{};getrusage(RUSAGE_SELF,&usage);
        const double duration=double(waveform.size())/sampleRate,totalMs=milliseconds(entered,Clock::now());
        std::string error=cap?"speech_frame_cap_reached_not_natural_eos":jniCallbackError?"jni_callback_failed":wasCancelled?"cancelled":callbackRejected?"pcm_receiver_rejected":!generated?"official_generateTTS_failed":!valid?"invalid_waveform_or_completion":!saved?"wave_save_failed":"";
        const std::string report="{\"event\":\"qwen_tts_native_result\",\"request_id\":"+std::to_string(requestId)+
            ",\"ok\":"+(valid&&saved?"true":"false")+",\"error\":"+quote(error)+",\"cancelled\":"+(wasCancelled?"true":"false")+
            ",\"total_ms\":"+number(totalMs)+",\"load_ms\":"+number(loadMs)+",\"generate_ms\":"+number(generateMs)+",\"wav_save_ms\":"+number(saveMs)+
            ",\"first_pcm_ms\":"+(firstPcmMs<0?"null":number(firstPcmMs))+",\"sample_rate\":24000,\"samples\":"+std::to_string(waveform.size())+",\"audio_seconds\":"+number(duration)+
            ",\"generation_rtf\":"+(duration>0?number(generateMs/(1000*duration)):"null")+",\"callback_count\":"+std::to_string(callbackCount)+",\"final_chunk\":"+(finalChunk?"true":"false")+
            ",\"nonfinite_samples\":"+std::to_string(nonfinite)+",\"samples_above_one\":"+std::to_string(aboveOne)+",\"raw_peak\":"+number(peak)+",\"raw_rms\":"+(waveform.empty()?"null":number(std::sqrt(squares/waveform.size())))+
            ",\"generated_frames\":"+std::to_string(frames)+",\"maximum_frames\":"+std::to_string(maxFrames)+",\"frame_cap_reached\":"+(cap?"true":"false")+",\"natural_eos_evidence\":"+(codecEos&&status==1?"true":"false")+
            ",\"official_status\":"+std::to_string(status)+",\"model_reused\":"+(reused?"true":"false")+",\"prefill_ms\":"+number(counterDelta(before.prefill,after.prefill)/1000.0)+
            ",\"decode_ms\":"+number(counterDelta(before.decode,after.decode)/1000.0)+",\"audio_ms\":"+number(counterDelta(before.audio,after.audio)/1000.0)+
            ",\"context_counter_scope\":\"official_counters_not_isolated_kernel_timers\",\"process_peak_rss_kib\":"+std::to_string(usage.ru_maxrss)+
            ",\"backend_configured\":\"cpu\",\"configured_cpu_threads\":"+std::to_string(cpuThreads)+",\"precision_configured\":\"high\",\"cpu_affinity_modified\":false,\"gpu_requested\":false,\"cache_hit\":false,\"amplitude_normalized\":false"+
            ",\"streaming_requested\":"+(streaming?"true":"false")+",\"stream_chunk_frames\":"+std::to_string(streaming?chunkFrames:0)+",\"stream_left_context_frames\":25"+
            ",\"pcm_chunks\":"+std::to_string(pcmChunks)+",\"first_pcm_generated_frames\":"+std::to_string(firstPcmFrames)+",\"first_pcm_before_eos\":"+(firstPcmBeforeEos?"true":"false")+
            ",\"cancel_observed_at_frame_boundary\":"+(cancelObserved?"true":"false")+",\"pcm_receiver_rejected\":"+(callbackRejected?"true":"false")+
            ",\"callback_scope\":"+quote(streaming?"incremental_codec_decode_before_eos":"complete_waveform")+"}";
        log(report);
        if(!generated||cap||nonfinite) {runtime.reset();loadedConfig.clear();loadedThreads=0;}
        return env->NewStringUTF(report.c_str());
    } catch(const std::exception& error) {
        runtime.reset();loadedConfig.clear();loadedThreads=0;
        const std::string report="{\"event\":\"qwen_tts_native_result\",\"request_id\":"+std::to_string(requestId)+",\"ok\":false,\"error\":"+quote(error.what())+",\"total_ms\":"+number(milliseconds(entered,Clock::now()))+"}";
        log(report);return env->NewStringUTF(report.c_str());
    }
}
extern "C" JNIEXPORT void JNICALL Java_com_prts_client_audio_qwen_QwenTtsNative_cancel(JNIEnv*,jobject,jlong id) { int64_t expected=id*2;activeState.compare_exchange_strong(expected,expected+1); }
extern "C" JNIEXPORT void JNICALL Java_com_prts_client_audio_qwen_QwenTtsNative_reset(JNIEnv*,jobject) {std::lock_guard<std::mutex> guard(runtimeMutex);runtime.reset();loadedConfig.clear();loadedThreads=0;}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*,void*) {return JNI_VERSION_1_6;}

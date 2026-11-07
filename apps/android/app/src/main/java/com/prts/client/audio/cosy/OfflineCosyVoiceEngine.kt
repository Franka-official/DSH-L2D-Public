// SPDX-License-Identifier: Apache-2.0
// Native pipeline adapted from Nian27/CosyVoice3-MNN, revision b4c2fafde21d1f138bfcd0ccaf7ddd9dcca2511a.
package com.prts.client.audio.cosy

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.legado.app.cosy.CosyVoiceFlowNative
import io.legado.app.cosy.CosyVoiceHiFTNative
import io.legado.app.cosy.CosyVoiceLlmNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Single-slot, on-device MNN synthesis. No HTTP/API fallback exists here.
 *
 * Cancellation is cooperative at native-stage boundaries: the upstream JNI has
 * no abort entry point. An in-flight native stage finishes, then its output is
 * discarded. Never reset an MNN session concurrently with a native run.
 */
class OfflineCosyVoiceEngine(
    context: Context,
    private val notice: (String) -> Unit = {},
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "cosyvoice3")
) : AutoCloseable {
    data class ModelState(val ready: Boolean, val installedBytes: Long,
                          val expectedBytes: Long, val missing: List<String>, val nativeReady: Boolean)
    data class Progress(val phase: String, val elapsedMs: Long, val stageMs: Map<String, Long>)
    data class Output(val wav: File, val sampleRate: Int, val totalMs: Long,
                      val audioSeconds: Double, val voiceId: String, val language: String,
                      val stageMs: Map<String, Long> = emptyMap(),
                      val referenceLanguage: String = "", val cacheHit: Boolean = false) {
        fun release() { wav.delete() }
    }
    class SynthesisHandle internal constructor() {
        internal val cancelled = AtomicBoolean(false)
        internal var future: Future<*>? = null
        fun cancel() { cancelled.set(true); future?.cancel(false) }
        internal fun check() { if (cancelled.get()) throw CancellationException("Speech cancelled") }
    }
    private data class Voice(val directory: File, val prefix: String, val tokens: Int, val frames: Int,
        val referenceVoiceId: String, val referenceLanguage: String, val watchedFiles: List<File>)
    private data class CachedVoice(val stamps: List<Pair<Long, Long>>, val voice: Voice)
    private val voiceCache = mutableMapOf<String, CachedVoice>() // Only inside NATIVE_LOCK.
    private val appContext = context.applicationContext
    private val flowPreferences = appContext.getSharedPreferences("cosyvoice_flow_backend", Context.MODE_PRIVATE)
    private val fixedSpeechCache = FixedSpeechCache(File(appContext.cacheDir, "cosyvoice-fixed-opening"))
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "PRTS-CosyVoice-CPU") }
    private val pending = CopyOnWriteArrayList<SynthesisHandle>()
    private val closed = AtomicBoolean(false)
    init { NATIVE_LEASES.acquire() }
    val modelDirectory: File get() = File(root, "models")
    val voicesDirectory: File get() = File(root, "voices")

    val available: Boolean get() = modelState().let { it.ready && it.nativeReady } && !closed.get()

    fun modelState(): ModelState {
        val absent = MODEL_FILES.filter { (name, bytes) -> File(modelDirectory, name).let { !it.isFile || it.length() != bytes } }.keys.toList()
        val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)
        val nativeReady = "arm64-v8a" in Build.SUPPORTED_ABIS && NATIVE_FILES.all { File(nativeDir, it).isFile }
        return ModelState(absent.isEmpty(), MODEL_FILES.keys.sumOf { File(modelDirectory, it).length() },
            MODEL_FILES.values.sum(), absent, nativeReady)
    }

    /** Explicit light diagnostic: resolves native/JNI/STL dependencies only.
     * Does not load models, create inference sessions, or generate audio.
     */
    fun checkNative(onResult: (Result<Unit>) -> Unit) {
        check(!closed.get()) { "Offline speech engine is closed" }
        executor.submit {
            val result = runCatching { synchronized(NATIVE_LOCK) {
                check("arm64-v8a" in Build.SUPPORTED_ABIS) { "离线语音需要ARM64" }
                listOf("CosyVoiceLlmNative", "CosyVoiceFlowNative", "CosyVoiceHiFTNative").forEach {
                    Class.forName("io.legado.app.cosy.$it", true, javaClass.classLoader)
                }
            } }
            main.post { if (!closed.get()) onResult(result) }
        }
    }

    /** language is the output language. EN uses the installed ZH reference;
     * the caller converts only JA body text to katakana. Cross-language quality is empirical.
     * Callback and progress notices are delivered on the main thread.
     * Missing Ellen profiles are errors: the builtin unrelated voice is never substituted.
     */
    fun synthesize(text: String, voiceId: String, language: String,
                   fixedCacheSlot: FixedSpeechCache.Slot? = null,
                   onProgress: ((Progress) -> Unit)? = null,
                   onResult: (Result<Output>) -> Unit): SynthesisHandle {
        check(!closed.get()) { "Offline speech engine is closed" }
        val handle = SynthesisHandle()
        pending.add(handle)
        handle.future = executor.submit {
            val outcome = runCatching {
                synchronized(NATIVE_LOCK) { handle.check(); synthesizeBlocking(text, voiceId, language, handle, onProgress, fixedCacheSlot) }
            }
            pending.remove(handle)
            if (!handle.cancelled.get() && !closed.get()) {
                val posted = main.post {
                    if (!handle.cancelled.get() && !closed.get()) onResult(outcome)
                    else outcome.getOrNull()?.release()
                }
                if (!posted) outcome.getOrNull()?.release()
            }
            else outcome.getOrNull()?.release()
        }
        return handle
    }

    fun cancelAll() { pending.forEach(SynthesisHandle::cancel) }

    private fun stage(handle: SynthesisHandle, message: String) {
        handle.check()
        main.post { if (!handle.cancelled.get() && !closed.get()) notice(message) }
    }

    private fun voice(voiceId: String, language: String): Voice {
        val referenceVoiceId = CosyVoiceLanguagePolicy.referenceVoiceId(language, voiceId)
        val directory = File(voicesDirectory, referenceVoiceId)
        installBundledVoiceIfOwned(directory, referenceVoiceId)
        val metadata = File(directory, "profile.json")
        check(metadata.isFile) { "所选角色的离线参考音色尚未就绪" }
        val watched = listOf(metadata, File(directory, "prompt-speech-tokens.csv"),
            File(directory, "prompt-cond.bin"), File(directory, "spks.bin"), File(directory, "rand-noise.bin"))
        val stamps = watched.map { it.length() to it.lastModified() }
        val cacheKey = directory.absolutePath
        voiceCache[cacheKey]?.takeIf { it.stamps == stamps }?.let { return it.voice }
        val json = JSONObject(metadata.readText(Charsets.UTF_8))
        val referenceLanguage = json.optString("language").takeIf { it in CosyVoiceLanguagePolicy.supported } ?: "unknown"
        val tokens = json.getInt("promptTokenCount")
        val frames = json.getInt("promptFrameCount")
        require(tokens in 1..125 && frames == tokens * 2) { "音色参考需为不超过5秒的已对齐特征" }
        val prefix = json.getString("promptPrefix")
        require(prefix.contains("<|endofprompt|>")) { "音色缺少模型提示分隔符" }
        val promptTokens = File(directory, "prompt-speech-tokens.csv").readText(Charsets.US_ASCII)
            .trim().split(',').filter(String::isNotBlank).map { it.trim().toInt() }
        require(promptTokens.size == tokens && promptTokens.all { it in 0..6560 }) { "音色语音Token不完整" }
        check(File(directory, "prompt-cond.bin").length() == 80L * frames * 4) { "音色Mel特征不完整" }
        check(File(directory, "spks.bin").length() == 80L * 4) { "音色说话人特征不完整" }
        if (!File(directory, "rand-noise.bin").isFile) {
            File(modelDirectory, "rand-noise.bin").copyTo(File(directory, "rand-noise.bin"))
        }
        return Voice(directory, prefix, tokens, frames, referenceVoiceId, referenceLanguage, watched).also {
            voiceCache[cacheKey] = CachedVoice(watched.map { file -> file.length() to file.lastModified() }, it)
        }
    }

    private fun profile(document: JSONObject?) = document?.let {
        OwnedVoiceInstallPolicy.Profile(it.optString("id"), it.optString("bundledVoiceOwner"),
            it.optString("voiceRevision"), it.optString("language"), it.optBoolean("builtIn", false),
            it.optString("referenceSourceUrl"))
    }

    private fun installBundledVoiceIfOwned(directory: File, referenceVoiceId: String) {
        val installed = runCatching { JSONObject(File(directory, "profile.json").readText(Charsets.UTF_8)) }.getOrNull()
        val bundled = runCatching { appContext.assets.open("voices/$referenceVoiceId/profile.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) } }.getOrNull()
        val action = OwnedVoiceInstallPolicy.action(referenceVoiceId, directory.exists(), profile(installed), profile(bundled))
        if (action == OwnedVoiceInstallPolicy.Action.KEEP) return
        check(action != OwnedVoiceInstallPolicy.Action.INVALID_BUNDLE) { "所选角色的内置音色不完整" }
        check(voicesDirectory.isDirectory || voicesDirectory.mkdirs()) { "无法准备角色音色目录" }
        val staging = File(voicesDirectory, ".$referenceVoiceId-install-${System.nanoTime()}")
        val previous = File(voicesDirectory, ".$referenceVoiceId-before-${System.nanoTime()}")
        check(staging.mkdirs()) { "无法准备少女音色" }
        var oldMoved = false
        var committed = false
        try {
            for (name in listOf("profile.json", "prompt-speech-tokens.csv", "prompt-cond.bin", "spks.bin")) {
                appContext.assets.open("voices/$referenceVoiceId/$name").use { input ->
                    File(staging, name).outputStream().use { input.copyTo(it) }
                }
            }
            val staged = JSONObject(File(staging, "profile.json").readText(Charsets.UTF_8))
            val tokens = staged.getInt("promptTokenCount")
            val frames = staged.getInt("promptFrameCount")
            require(tokens in 1..125 && frames == tokens * 2)
            require(staged.getString("promptPrefix").contains("<|endofprompt|>"))
            require(readTokens(File(staging, "prompt-speech-tokens.csv")).let { it.size == tokens && it.all { id -> id in 0..6560 } })
            require(File(staging, "prompt-cond.bin").length() == 80L * frames * 4 && File(staging, "spks.bin").length() == 320L)
            if (directory.exists()) { check(directory.renameTo(previous)); oldMoved = true }
            check(staging.renameTo(directory)) { "角色音色安装未完成" }
            committed = true
            if (oldMoved) previous.deleteRecursively()
            voiceCache.remove(directory.absolutePath)
            Log.i("PRTS-VoiceAssets", "action=$action voiceId=$referenceVoiceId revision=${staged.optString("voiceRevision")} referenceLanguage=${staged.optString("language")}")
        } finally {
            if (!committed && oldMoved && !directory.exists()) previous.renameTo(directory)
            staging.deleteRecursively()
        }
    }

    private fun synthesizeBlocking(text: String, voiceId: String, language: String,
                                    handle: SynthesisHandle, onProgress: ((Progress) -> Unit)?,
                                    fixedCacheSlot: FixedSpeechCache.Slot?): Output {
        check(available) { "离线语音模型或本地运行库尚未就绪" }
        val clean = text.replace(Regex("[\\r\\n]+"), " ").trim()
        require(clean.isNotBlank()) { "朗读文字为空" }
        val started = System.nanoTime()
        val voice = voice(voiceId, language)
        val timings = linkedMapOf<String, Long>()
        timings["reference"] = (System.nanoTime() - started) / 1_000_000
        var stageStarted = started
        fun phase(name: String) {
            val progress = Progress(name, (System.nanoTime() - started) / 1_000_000, timings.toMap())
            if (onProgress != null) main.post {
                if (!handle.cancelled.get() && !closed.get()) runCatching { onProgress(progress) }
            }
        }
        fun finishStage(name: String) {
            val at = System.nanoTime()
            timings[name] = (at - stageStarted) / 1_000_000
            stageStarted = at
        }
        val signature = fixedCacheSlot?.let {
            FixedSpeechCache.Signature(clean, voice.referenceVoiceId, voice.referenceLanguage,
                voice.directory.absolutePath, MODEL_ID, FixedSpeechCache.stamps(voice.watchedFiles))
        }
        val outputDir = File(appContext.cacheDir, "cosyvoice-output")
        if (fixedCacheSlot != null && signature != null) {
            val lookupStarted = System.nanoTime()
            val hit = fixedSpeechCache.load(fixedCacheSlot, signature, outputDir)
            timings["cache_lookup"] = (System.nanoTime() - lookupStarted) / 1_000_000
            if (hit != null) {
                try { handle.check() } catch (error: Exception) { hit.wav.delete(); throw error }
                val totalMs = (System.nanoTime() - started) / 1_000_000
                recordTiming(language, voice, clean, null, totalMs, hit.audioSeconds, timings, cacheHit = true)
                Log.i("PRTS-CosyLang", "language=$language referenceVoiceId=${voice.referenceVoiceId} cacheHit=true nativeInferencePerformed=false sampleRate=${hit.sampleRate} audioSeconds=${hit.audioSeconds} elapsedMs=$totalMs stageMs=$timings")
                phase("complete")
                return Output(hit.wav, hit.sampleRate, totalMs, hit.audioSeconds, voice.referenceVoiceId,
                    language, timings.toMap(), voice.referenceLanguage, cacheHit = true)
            }
        }
        stageStarted = System.nanoTime()
        val run = File(appContext.cacheDir, "cosyvoice-${System.nanoTime()}").apply { mkdirs() }
        try {
            val llmOut = File(run, "llm").apply { mkdirs() }
            val flowIn = File(run, "flow-in").apply { mkdirs() }
            val flowOut = File(run, "flow-out").apply { mkdirs() }
            val hiftIn = File(run, "hift-in").apply { mkdirs() }
            val hiftOut = File(run, "hift-out").apply { mkdirs() }
            val prompt = File(run, "prompts.txt").apply { writeText(voice.prefix + clean, Charsets.UTF_8) }
            val maximum = ((voice.prefix.substringAfter("<|endofprompt|>").length + clean.length) * 8 + 128).coerceIn(500, 1024)
            stage(handle, "正在手机本地生成语音 · 语义")
            phase("semantic")
            NATIVE_SESSION_DIRTY.set(true)
            check(CosyVoiceLlmNative.run(cpuConfig().absolutePath, prompt.absolutePath, maximum,
                File(voice.directory, "prompt-speech-tokens.csv").absolutePath, llmOut.absolutePath, true) == 0) { "本地语音语义生成失败" }
            handle.check()
            val raw = readTokens(File(llmOut, "raw-output-ids-0.csv"))
            check(raw.contains(158486)) { "本地语音未正常结束；请缩短句子后重试" }
            val tokens = readTokens(File(llmOut, "speech-tokens-0.csv"))
            check(tokens.isNotEmpty() && tokens.all { it in 0..6560 }) { "本地语音Token异常" }
            check(tokens.size < 20 || tokens.distinct().size > 4) { "本地语音发生重复退化" }
            var longest = 0; var count = 0; var previous = -1
            tokens.forEach { t -> count = if (t == previous) count + 1 else 1; longest = maxOf(longest, count); previous = t }
            check(longest < 64) { "本地语音发生长段重复" }
            val sequence = 2 * (voice.tokens + tokens.size)
            val bucket = FlowBackendPolicy.bucket(sequence)
            finishStage("semantic")
            stage(handle, "正在手机本地生成语音 · 音色")
            phase("conditioning")
            runConditioner(listOf(File(modelDirectory, "flow-conditioner.fp32.mnn").absolutePath,
                voice.directory.absolutePath, File(llmOut, "speech-tokens-0.csv").absolutePath,
                flowIn.absolutePath, voice.tokens.toString(), voice.frames.toString()), run, handle)
            finishStage("conditioning")
            stage(handle, "正在手机本地生成语音 · 声学")
            phase("acoustic")
            val flowManifest = File(run, "flow-manifest.txt").apply {
                writeText("${flowIn.absolutePath} ${flowOut.absolutePath} $sequence ${voice.frames} $bucket\n", Charsets.US_ASCII)
            }
            val flow = executeFlow(flowManifest, flowOut, run, tokens.size * 2 * 80, handle)
            handle.check()
            File(flowOut, "student_target_mel_android.bin").copyTo(File(hiftIn, "mel.bin"))
            listOf("source-linear-weight.bin", "source-linear-bias.bin").forEach { File(modelDirectory, it).copyTo(File(hiftIn, it)) }
            finishStage("acoustic")
            stage(handle, "正在手机本地生成语音 · 波形")
            phase("waveform")
            val hiftManifest = File(run, "hift-manifest.txt").apply {
                writeText("${hiftIn.absolutePath} ${hiftOut.absolutePath} ${tokens.size * 2}\n", Charsets.US_ASCII)
            }
            check(CosyVoiceHiFTNative.run(File(modelDirectory, "hift-f0.fp32.mnn").absolutePath,
                File(modelDirectory, "hift-core.fp32.mnn").absolutePath, hiftManifest.absolutePath, 4,
                File(run, "hift-report.jsonl").absolutePath, "cpu", "high",
                File(root, "hift-cpu.cache").absolutePath, 4) == 0) { "本地波形生成失败" }
            handle.check()
            val wav = File(hiftOut, "hift-android.wav")
            check(wav.length() > 44) { "本地语音未输出有效音频" }
            check(outputDir.isDirectory || outputDir.mkdirs())
            val output = File(outputDir, "speech-${System.nanoTime()}.wav")
            wav.copyTo(output)
            val report = File(run, "hift-report.jsonl").useLines { lines -> JSONObject(lines.first { it.isNotBlank() }) }
            finishStage("waveform")
            val audioSeconds = report.optDouble("audioSeconds", tokens.size / 25.0)
            if (fixedCacheSlot != null && signature != null) {
                val cacheStarted = System.nanoTime()
                val stored = fixedSpeechCache.store(fixedCacheSlot, signature, output, audioSeconds, 24000)
                timings["cache_write"] = (System.nanoTime() - cacheStarted) / 1_000_000
                Log.i("PRTS-CosyCache", "fixedOpeningStored=$stored language=$language segment=${fixedCacheSlot.segment}")
            }
            val totalMs = (System.nanoTime() - started) / 1_000_000
            // This is stage wall time, including an initial native load when
            // needed, not isolated kernel time or an asserted warm benchmark.
            recordTiming(language, voice, clean, tokens.size, totalMs, audioSeconds, timings, cacheHit = false, flow = flow)
            Log.i("PRTS-CosyLang", "language=$language referenceVoiceId=${voice.referenceVoiceId} cacheHit=false nativeInferencePerformed=true sampleRate=24000 audioSeconds=$audioSeconds elapsedMs=$totalMs stageMs=$timings")
            phase("complete")
            return Output(output, 24000, totalMs, audioSeconds, voice.referenceVoiceId, language,
                timings.toMap(), voice.referenceLanguage, cacheHit = false)
        } finally { run.deleteRecursively() }
    }

    private fun recordTiming(language: String, voice: Voice, clean: String, speechTokens: Int?,
        totalMs: Long, audioSeconds: Double, timings: Map<String, Long>, cacheHit: Boolean, flow: FlowEvidence? = null) {
        runCatching {
            val timing = JSONObject().put("version", 2).put("language", language)
                .put("referenceLanguage", voice.referenceLanguage).put("referenceVoiceId", voice.referenceVoiceId)
                .put("textCharacters", clean.codePointCount(0, clean.length)).put("totalMs", totalMs)
                .put("audioSeconds", audioSeconds).put("stageMs", JSONObject(timings as Map<*, *>))
                .put("cacheHit", cacheHit).put("nativeInferencePerformed", !cacheHit)
                .put("flowBackend", flow?.backend ?: "cached").put("flowGpuVerified", flow?.gpuVerified ?: false)
                .put("flowFallbackUsed", flow?.fallback ?: false).put("flowFallbackReason", flow?.reason ?: "")
                .put("stageTimesMayIncludeInitialLoad", !cacheHit).put("warmStateKnown", false)
                .put("nativeAudioStreaming", false).put("contentRecorded", false)
            if (speechTokens != null) timing.put("speechTokens", speechTokens)
            if (flow != null) timing.put("flowNativeReport", flow.report)
            val temporary = File(root, "last-synthesis-timing.json.writing")
            temporary.writeText(timing.toString(2), Charsets.UTF_8)
            val target = File(root, "last-synthesis-timing.json")
            if (!temporary.renameTo(target)) { temporary.copyTo(target, overwrite = true); temporary.delete() }
        }
    }

    private data class FlowEvidence(val backend: String, val gpuVerified: Boolean, val fallback: Boolean,
        val reason: String, val report: JSONObject)
    private class GpuFlowFailure(val reason: String) : RuntimeException(reason)

    private fun executeFlow(manifest: File, output: File, run: File, expectedFloats: Int,
        handle: SynthesisHandle): FlowEvidence {
        val failureKey = "${Build.FINGERPRINT}|$MODEL_ID|${FlowBackendPolicy.profileId}"
        val rememberedFailure = flowPreferences.getString("disabledFor", null) == failureKey
        fun execute(backend: String, fallback: Boolean, reason: String): FlowEvidence {
            handle.check()
            val gpu = backend == "opencl"
            val report = File(run, if (gpu) "flow-gpu-report.jsonl" else "flow-cpu-report.jsonl")
            val code = CosyVoiceFlowNative.run(File(modelDirectory, "flow.cfg-student-2step.batch1.fp16.mnn").absolutePath,
                manifest.absolutePath, backend, if (gpu) "high" else "normal", if (gpu) FlowBackendPolicy.GPU_MODE else 4,
                report.absolutePath, File(root, if (gpu) FlowBackendPolicy.gpuCacheName else "flow-cpu.cache").absolutePath)
            handle.check()
            if (gpu && FlowBackendPolicy.mayFallback(code)) throw GpuFlowFailure("native_$code")
            check(code == 0) { "本地声学生成失败，代码$code" }
            val finite = FlowBackendPolicy.finiteMel(File(output, "student_target_mel_android.bin"), expectedFloats)
            if (gpu && !finite) throw GpuFlowFailure("non_finite_output")
            check(finite) { "本地声学输出包含非有限值" }
            val native = report.useLines { lines -> JSONObject(lines.first { it.isNotBlank() }) }
            val backendAvailable = native.optBoolean("backendInfoAvailable", false)
            val actualBackend = native.optInt("actualBackend", -1)
            if (gpu && backendAvailable && actualBackend != 3) throw GpuFlowFailure("native_backend_not_opencl")
            val verified = gpu && FlowBackendPolicy.gpuVerified(backendAvailable, actualBackend, native.optBoolean("outputDeviceNonzero", false))
            check(!gpu || verified) { "无法确认手机GPU声学后端，未将配置标签当作执行成功" }
            val evidence = FlowEvidence(backend, verified, fallback, reason, native)
            runCatching {
                File(root, "last-flow-report.json").writeText(JSONObject().put("flowBackend", backend)
                    .put("flowGpuVerified", verified).put("flowFallbackUsed", fallback).put("flowFallbackReason", reason)
                    .put("gpuMode", if (gpu) FlowBackendPolicy.GPU_MODE else 0).put("native", native).toString(2), Charsets.UTF_8)
            }
            Log.i("PRTS-CosyFlow", "backend=$backend gpuVerified=$verified fallbackUsed=$fallback reason=$reason actualBackend=$actualBackend")
            return evidence
        }
        if (rememberedFailure) return execute("cpu", false, "remembered_gpu_failure")
        try {
            try { System.loadLibrary("MNN_CL") }
            catch (_: UnsatisfiedLinkError) { throw GpuFlowFailure("opencl_library_load") }
            return execute("opencl", false, "")
        } catch (failure: GpuFlowFailure) {
            flowPreferences.edit().putString("disabledFor", failureKey).putString("reason", failure.reason).commit()
            handle.check()
            File(output, "student_target_mel_android.bin").delete()
            Log.w("PRTS-CosyFlow", "GPU failed reason=${failure.reason}; one acoustic CPU fallback")
            return execute("cpu", true, failure.reason)
        }
    }

    private fun readTokens(file: File): List<Int> = file.readText(Charsets.US_ASCII).trim()
        .split(',').filter(String::isNotBlank).map { it.trim().toInt() }

    private fun cpuConfig(): File {
        val config = JSONObject(File(modelDirectory, "config-cpu-cosyvoice-ras.json").readText())
            .put("backend_type", "cpu").put("thread_num", 4).put("power", "high")
            .put("sampler_type", "mixed").put("temperature", 1.0).put("top_k", 25).put("top_p", 0.8)
            .put("mixed_samplers", JSONArray(listOf("penalty", "topK", "topP", "temperature")))
            .put("repetition_penalty", 1.15)
        return File(modelDirectory, "config-prts-cpu-runtime.json").apply {
            val content = config.toString(2)
            if (!isFile || readText() != content) writeText(content)
        }
    }

    private fun runConditioner(arguments: List<String>, run: File, handle: SynthesisHandle) {
        handle.check()
        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val process = ProcessBuilder(listOf(File(nativeDir, "libcosy_conditioner_exec.so").absolutePath) + arguments)
            .directory(run).redirectErrorStream(true).apply { environment()["LD_LIBRARY_PATH"] = nativeDir }.start()
        process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "本地音色条件生成失败" }
        handle.check()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelAll()
        executor.shutdown()
        val ticket = NATIVE_LEASES.release()
        NATIVE_CLEANER.schedule({ synchronized(NATIVE_LOCK) {
            NATIVE_LEASES.resetIfIdle(ticket) { if (NATIVE_SESSION_DIRTY.getAndSet(false)) {
                val cleared = listOf(runCatching { CosyVoiceLlmNative.reset() },
                    runCatching { CosyVoiceFlowNative.reset() }, runCatching { CosyVoiceHiFTNative.reset() }).all { it.isSuccess }
                Log.i("PRTS-CosyLifecycle", "idle native reset owners=0 success=$cleared")
                runCatching { File(root, "last-native-lifecycle.json").writeText(JSONObject()
                    .put("activeOwners", 0).put("resetCompleted", cleared).put("idleRetentionMs", 30000).toString(), Charsets.UTF_8) }
            } }
        } }, 30, TimeUnit.SECONDS)
    }

    companion object {
        private val NATIVE_LOCK = Any()
        private val NATIVE_LEASES = NativeSessionLeasePolicy()
        private val NATIVE_SESSION_DIRTY = AtomicBoolean(false)
        private val NATIVE_CLEANER = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "PRTS-CosyVoice-IdleCleanup").apply { isDaemon = true } }
        @JvmStatic fun activeNativeOwnersForDiagnostics(): Int = NATIVE_LEASES.activeOwners
        const val MODEL_ID = "Fun-CosyVoice3-0.5B-2512-RL-distilled-v1"
        private val NATIVE_FILES = listOf("libcosy_llm_jni.so", "libprts_cosy_flow_jni.so", "libcosy_hift_jni.so",
            "libcosy_conditioner_exec.so", "libllm.so", "libMNN.so", "libMNN_Express.so", "libMNN_CL.so",
            "libMNN_Vulkan.so", "libMNNOpenCV.so", "libMNNAudio.so")
        val MODEL_FILES = linkedMapOf("config-cpu-cosyvoice-ras.json" to 504L, "llm_config.json" to 456L,
            "llm.mnn" to 419640L, "llm.mnn.weight" to 352844666L, "embeddings_bf16.bin" to 284426240L,
            "tokenizer.mtok" to 4042638L, "flow.cfg-student-2step.batch1.fp16.mnn" to 1340608L,
            "flow.cfg-student-2step.batch1.fp16.mnn.weight" to 663253312L, "flow-conditioner.fp32.mnn" to 4403404L,
            "rand-noise.bin" to 4800000L, "hift-f0.fp32.mnn" to 13271724L, "hift-core.fp32.mnn" to 70218952L,
            "source-linear-weight.bin" to 36L, "source-linear-bias.bin" to 4L)
    }
}

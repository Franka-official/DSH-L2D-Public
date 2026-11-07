package com.prts.client.audio.audio8

import android.content.Context
import com.prts.client.BuildConfig
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.prts.client.audio.TtsBackendSettings
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

/** Local Audio8 AR + causal streaming codec. Shares the application's existing PCM contract. */
class OfflineAudio8TtsEngine(context: Context) : AutoCloseable {
    data class ModelState(val ready: Boolean, val installedBytes: Long, val expectedBytes: Long,
        val missing: List<String>, val nativeReady: Boolean, val rootPath: String,
        val modelPath: String, val nativeLibraryPath: String)
    /** PCM callback runs on the serialized TTS worker. Copy/enqueue quickly.
     * final is an EOS marker; its sample array may be empty at a chunk boundary.
     * Playback timing/alignment belongs to AudioTrack, not callback arrival time.
     */
    data class PcmChunk(val requestId: Long, val sequence: Int, val sampleRate: Int,
        val samples: FloatArray, val final: Boolean, val generatedFrames: Int,
        val eligibleForAudio8EarlyStart: Boolean = false, val eligibleForAudio8ShortStart: Boolean = false,
        val producedAtMs: Long? = null)
    data class Progress(val phase: String, val elapsedMs: Long, val stageMs: Map<String, Long>)
    data class Output(val wav: File, val sampleRate: Int, val totalMs: Long, val audioSeconds: Double,
        val voiceId: String, val language: String, val stageMs: Map<String, Long>,
        val referenceLanguage: String, val cacheHit: Boolean = false,
        val nativeReport: JSONObject = JSONObject()) : AutoCloseable {
        fun release() { close() }
        override fun close() { wav.delete() }
    }
    class SynthesisHandle internal constructor(internal val requestId: Long,
        private val removeCancelledHandle: (Long) -> Unit) {
        internal val cancelled = AtomicBoolean(false)
        internal var future: Future<*>? = null
        internal var nativeCancellation: (() -> Unit)? = null
        fun cancel() {
            cancelled.set(true)
            // A cancelled queued Future never enters its body/final cleanup.
            // Unregister here as well; removal by a running task is idempotent.
            removeCancelledHandle(requestId)
            future?.cancel(false)
            nativeCancellation?.invoke()
        }
        internal fun check() { if (cancelled.get()) throw CancellationException("Local speech cancelled") }
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val handles = ConcurrentHashMap<Long, SynthesisHandle>()
    private val layout = Audio8ModelLayout(app)
    val modelDirectory get() = layout.models
    val referencesDirectory get() = layout.reference
    init { OWNERS.incrementAndGet(); LEASE.incrementAndGet() }
    fun modelState(): ModelState = layout.inspect()
    val available get() = !closed.get() && modelState().ready
    fun synthesize(text: String, voiceId: String, language: String, onProgress: (Progress) -> Unit = {},
        cpuThreads: Int = 4, onPcm: ((PcmChunk) -> Boolean)? = null, callback: (Result<Output>) -> Unit): SynthesisHandle {
        check(!closed.get()) { "Audio8 speech owner is closed" }
        require(cpuThreads in 1..8)
        val clean = text.trim()
        require(clean.isNotEmpty() && clean.toByteArray(Charsets.UTF_8).size <= 16384)
        val tuning = app.getSharedPreferences("prts", Context.MODE_PRIVATE)
        val arThreads = tuning.getInt("audio8_ar_threads", 1).takeIf { it in 1..8 } ?: 1
        val codecThreads = tuning.getInt("audio8_codec_threads", 4).takeIf { it in 1..8 } ?: 4
        val requestChunkFrames = tuning.getInt("audio8_chunk_frames", 12).takeIf { it in 1..128 } ?: 12
        val queuedAt = SystemClock.elapsedRealtime()
        val handle = SynthesisHandle(REQUESTS.incrementAndGet()) { handles.remove(it) }
        handle.nativeCancellation = {
            synchronized(NATIVE_LOCK) {
                if (ACTIVE.get() == handle.requestId) NATIVE_HANDLE.get().takeIf { it != 0L }?.let(Audio8Native::cancelNative)
            }
        }
        handles[handle.requestId] = handle; LEASE.incrementAndGet()
        fun progress(phase: String, stages: Map<String, Long> = emptyMap()) {
            val value = Progress(phase, SystemClock.elapsedRealtime() - queuedAt, stages)
            main.post { if (!closed.get() && !handle.cancelled.get()) onProgress(value) }
        }
        handle.future = WORKER.submit {
            val started = SystemClock.elapsedRealtime()
            val cpuStarted = android.os.Process.getElapsedCpuTime()
            val outputDirectory = File(app.cacheDir, "audio8-output").apply { check(isDirectory || mkdirs()) }
            val output = File(outputDirectory, "request-${handle.requestId}-$started.wav")
            val outcome = runCatching {
                handle.check(); check(!closed.get())
                Audio8ModelStore.ensureInstalled(app)
                val readiness = modelState()
                check(readiness.ready) { "Audio8 model pack is not ready: ${readiness.missing.joinToString()}" }
                progress("preparing")
                val resolvedDirectory = Audio8ModelStore.referenceDirectory(app, voiceId)
                val preferred = codecSelection()
                var pcmDispatched = false
                var gpuFallback = false
                fun generate(selection: CodecSelection): Output {
                    val chunkFrames = if (selection.autoGpu || selection.backend == "qnn_htp") selection.chunkFrames else requestChunkFrames
                    require(selection.backend == "ort_cpu" || chunkFrames == selection.chunkFrames) {
                        "Audio8 decoder requires chunk_frames=${selection.chunkFrames}"
                    }
                    val loaded = acquireSession(arThreads, codecThreads, selection, resolvedDirectory)
                    val loadMs = loaded.loadMs
                    val session = loaded.session
                    val tokenizeAt = SystemClock.elapsedRealtime()
                    val prompt = session.tokenizer.buildPrompt(clean, session.referenceText, session.referenceCodes)
                    val tokenizeMs = SystemClock.elapsedRealtime() - tokenizeAt
                    handle.check(); progress("synthesizing")
                    val eligibleForEarlyStart = session.codecSelection.backend == "ort_cpu" &&
                        session.arThreads == 1 && session.codecThreads == 4 && chunkFrames == 12
                    var sequence = 0; var frames = 0; var firstPcmMs = -1L
                    synchronized(NATIVE_LOCK) { handle.check(); ACTIVE.set(handle.requestId) }
                    val generateStarted = SystemClock.elapsedRealtime()
                    val report = try {
                        JSONObject(Audio8Native.generateNative(session.handle, prompt.values, prompt.positions,
                            prompt.reusablePrefixPositions, 1024, chunkFrames, 128, 1, 42L, object : Audio8Native.Sink {
                                override fun onPcm(pcm: FloatArray, sampleRate: Int, finalBlock: Boolean): Boolean {
                                    if (closed.get() || handle.cancelled.get()) return false
                                    if (pcm.isNotEmpty() && firstPcmMs < 0) firstPcmMs = SystemClock.elapsedRealtime() - queuedAt
                                    if (onPcm != null) pcmDispatched = true
                                    return onPcm?.invoke(PcmChunk(handle.requestId, sequence++, sampleRate,
                                        pcm, finalBlock, frames, eligibleForAudio8EarlyStart = eligibleForEarlyStart,
                                        eligibleForAudio8ShortStart = session.codecSelection.backend == "qnn_htp" && chunkFrames == 12)) ?: true
                                }
                                override fun onNativeEvent(json: String) {
                                    if (handle.cancelled.get()) synchronized(NATIVE_LOCK) {
                                        if (ACTIVE.get() == handle.requestId && NATIVE_HANDLE.get() == session.handle) Audio8Native.cancelNative(session.handle)
                                    }
                                    frames = JSONObject(json).optInt("frames", frames)
                                }
                            }))
                    } finally { synchronized(NATIVE_LOCK) { ACTIVE.compareAndSet(handle.requestId, 0) } }
                    val generateMs = SystemClock.elapsedRealtime() - generateStarted
                    val expectedCodecMode = when (session.codecSelection.backend) {
                        "ort_cpu" -> "incremental_kv_conv"
                        "qnn_htp" -> "qnn_incremental_kv_conv"
                        else -> "mnn_incremental_kv_conv"
                    }
                    check(report.optString("codec_mode") == expectedCodecMode &&
                        report.optInt("decoder_context_replay_frames", -1) == 0 &&
                        report.optInt("codec_state_tensors", -1) == 36 &&
                        report.optBoolean("decoder_state_reset_per_request") &&
                        (if (session.codecSelection.backend == "ort_cpu") report.optInt("ar_threads", -1) == arThreads
                         else report.optInt("ar_slow_threads", -1) == session.codecSelection.slowArThreads &&
                              report.optInt("ar_fast_threads", -1) == session.codecSelection.fastArThreads) &&
                        (session.codecSelection.backend == "qnn_htp" || report.optInt("codec_threads", -1) == codecThreads) &&
                        report.optInt("chunk_frames", -1) == chunkFrames &&
                        !report.optBoolean("ar_intra_op_allow_spinning", true) &&
                        (session.codecSelection.backend != "ort_cpu" ||
                            (!report.optBoolean("codec_intra_op_allow_spinning", true) &&
                             !report.optBoolean("inter_op_allow_spinning", true)))) {
                        "Audio8 native codec evidence does not match the required incremental backend: $report"
                    }
                    val mnn = report.optJSONObject("mnn_codec")
                    if (session.codecSelection.backend.startsWith("mnn_")) check(mnn != null) { "Missing actual MNN backend evidence" }
                    if (session.codecSelection.backend == "qnn_htp") {
                        val qnn = report.getJSONObject("qnn_codec")
                        check(qnn.optString("backend") == "htp" && qnn.optBoolean("cpu_fallback_disabled") &&
                            qnn.optBoolean("strict_session_ready") && qnn.optInt("graph_calls") > 0) {
                            "Missing strict QNN execution evidence: $qnn"
                        }
                    }
                    val gpuCommands = mnn?.optJSONObject("last_execution_backend_command_counts")?.optLong("3", 0L) ?: 0L
                    if (session.codecSelection.backend == "mnn_opencl") check(gpuCommands > 0L && mnn != null && mnn.optInt("session_main_backend", -1) == 3 && mnn.optBoolean("opencl_runtime_created")) { "MNN GPU was requested but actual OpenCL execution was not confirmed: $mnn" }
                    report.put("gpu_auto_fallback", gpuFallback)
                        .put("requested_codec_backend", session.codecSelection.backend)
                        .put("requested_codec_precision", session.codecSelection.precision)
                        .put("requested_codec_model_path", session.codecSelection.modelPath)
                        .put("requested_ar_slow_threads", session.codecSelection.slowArThreads)
                        .put("requested_ar_fast_threads", session.codecSelection.fastArThreads)
                        .put("gpu_execution_confirmed", gpuCommands > 0L)
                    report.put("codec_variant", Audio8ModelLayout.CODEC_VARIANT)
                        .put("source_hf_revision", Audio8ModelLayout.REVISION)
                    val natural = report.optBoolean("eos") && !report.optBoolean("capped") && !report.optBoolean("cancelled")
                    val referenceId = referenceVoiceId(resolvedDirectory)
                    report.put("ok", natural).put("natural_eos_evidence", natural).put("frame_cap_reached", report.optBoolean("capped"))
                        .put("modelId", MODEL_ID).put("engine", when (session.codecSelection.backend) {
                            "ort_cpu" -> "audio8_ort_android_cpu"
                            "qnn_htp" -> "audio8_ort_ar_qnn_codec_android"
                            else -> "audio8_ort_ar_mnn_codec_android"
                        })
                        .put("referenceVoiceId", referenceId).put("requestedVoiceId", voiceId).put("referenceLanguage", "zh")
                        .put("language", language).put("request_id", handle.requestId).put("event", "audio8_tts_request")
                        .put("queue_ms", started - queuedAt).put("model_load_ms", loadMs).put("tokenize_ms", tokenizeMs)
                        .put("generate_ms", generateMs).put("first_pcm_ms", firstPcmMs)
                        .put("audio_seconds", report.optDouble("actual_audio_s"))
                        .put("process_cpu_ms", android.os.Process.getElapsedCpuTime() - cpuStarted)
                        .put("worker_wall_ms", SystemClock.elapsedRealtime() - started).put("worker_tid", android.os.Process.myTid())
                        .put("threads", codecThreads).put("legacy_router_cpu_threads_requested", cpuThreads).put("app_pid", android.os.Process.myPid()).put("app_uid", android.os.Process.myUid())
                        .put("native_library", File(app.applicationInfo.nativeLibraryDir, "libprts_audio8_tts.so").absolutePath)
                        .put("model_path", layout.models.absolutePath).put("runtime", JSONObject(Audio8Native.runtimeInfo()))
                        .put("session_generation_id", session.serial).put("resolved_reference_voice_id", referenceId)
                        .put("normal_reference_warmup", referenceWarmupStatus())
                        .put("worker_scheduling", workerScheduling(android.os.Process.myTid()))
                        .put("text_chars", clean.codePointCount(0, clean.length)).put("sample_rate", 44100)
                    handle.check()
                    val saveStarted = SystemClock.elapsedRealtime()
                    check(natural) { "Audio8 speech did not finish naturally" }
                    Audio8Native.saveNative(session.handle, output.absolutePath.removeSuffix(".wav"))
                    check(output.isFile && output.length() >= 44)
                    val totalMs = SystemClock.elapsedRealtime() - queuedAt
                    report.put("wav_save_ms", SystemClock.elapsedRealtime() - saveStarted).put("app_total_ms", totalMs)
                    Log.i(TAG, report.toString())
                    runCatching {
                        File(layout.root, "last-synthesis-timing.json").writeText(report.toString(2), Charsets.UTF_8)
                    }.onFailure { Log.w(TAG, "Timing write failed", it) }
                    val stages = mapOf("queue" to started - queuedAt, "load" to loadMs,
                        "tokenize" to tokenizeMs, "generate" to generateMs,
                        "official_prefill" to report.optDouble("prefill_s", 0.0).times(1000).roundToLong(),
                        "official_decode" to report.optDouble("decoder_s", 0.0).times(1000).roundToLong())
                    progress("ready", stages)
                    return Output(output, 44100, totalMs, report.getDouble("audio_seconds"), referenceId, language,
                        stages, "zh", report.optInt("reused_prefix_positions") > 0, report)
                }
                try {
                    generate(preferred)
                } catch (error: Exception) {
                    if (!preferred.autoGpu || closed.get() || handle.cancelled.get()) throw error
                    disableAutoGpu(error)
                    if (pcmDispatched) throw error
                    gpuFallback = true
                    handle.check()
                    generate(cpuSelection(arThreads))
                }
            }
            if (outcome.isFailure) { output.delete(); Log.e(TAG, "Audio8 request failed", outcome.exceptionOrNull()) }
            handles.remove(handle.requestId)
            main.post { if (closed.get() || handle.cancelled.get()) outcome.getOrNull()?.release() else callback(outcome) }
        }
        return handle
    }
    private data class LoadedSession(val session: Session, val loadMs: Long)
    private data class CodecSelection(val backend: String, val precision: String, val modelPath: String,
        val modelBytes: Long, val chunkFrames: Int, val slowArThreads: Int, val fastArThreads: Int, val keepGpuState: Boolean = false, val autoGpu: Boolean = false)
    private fun cpuSelection(arThreads: Int) = CodecSelection("ort_cpu", "high", "", 0L, 0, arThreads, arThreads)

    private fun disableAutoGpu(error: Exception) {
        AUTO_GPU_FAILED.set(true)
        dropSession()
        Log.w(TAG, "Audio8 OpenCL unavailable; use CPU for this process", error)
    }

    private fun codecSelection(): CodecSelection {
        val prefs = app.getSharedPreferences("prts", Context.MODE_PRIVATE)
        val configured = TtsBackendSettings.load(app).localCodecBackend
        if (configured == TtsBackendSettings.LOCAL_CODEC_NPU) {
            check(TtsBackendSettings.isNpuSupported()) { "当前设备的 NPU 解码不可用，请在语音设置中选择 CPU 或 GPU" }
            val graph = Audio8ModelStore.bundledNpuGraph(app)
            val slow = prefs.getInt("audio8_slow_ar_threads", 1).takeIf { it in 1..8 } ?: 1
            val fast = prefs.getInt("audio8_fast_ar_threads", 1).takeIf { it in 1..8 } ?: 1
            return CodecSelection("qnn_htp", "htp_fp16", graph.absolutePath, Audio8ModelStore.NPU_CODEC_BYTES, 12, slow, fast)
        }
        val requested = if (BuildConfig.AUDIO8_GPU_DEFAULT)
            prefs.getString("audio8_codec_backend", null) ?: configured else configured
        val auto = requested == TtsBackendSettings.LOCAL_CODEC_AUTO
        val autoGpu = auto && BuildConfig.AUDIO8_GPU_DEFAULT && !AUTO_GPU_FAILED.get()
        val backend = if (auto) (if (autoGpu) "mnn_opencl" else TtsBackendSettings.LOCAL_CODEC_CPU) else requested
        val precision = when {
            backend == "ort_cpu" -> "high"
            autoGpu -> "low"
            else -> prefs.getString("audio8_codec_precision", "high") ?: "high"
        }
        require(backend in setOf("ort_cpu", "mnn_cpu", "mnn_opencl")) { "Unsupported Audio8 codec backend" }
        require(precision in setOf("high", "low") && (precision == "high" || backend == "mnn_opencl")) { "Low precision is only enabled for the MNN OpenCL candidate" }
        val legacyAr = prefs.getInt("audio8_ar_threads", 1).takeIf { it in 1..8 } ?: 1
        if (backend == "ort_cpu") return cpuSelection(legacyAr)
        val slow = prefs.getInt("audio8_slow_ar_threads", legacyAr).takeIf { it in 1..8 } ?: legacyAr
        val fast = prefs.getInt("audio8_fast_ar_threads", legacyAr).takeIf { it in 1..8 } ?: legacyAr
        val directory = File(app.filesDir, if (autoGpu) "audio8-gpu" else "audio8-mnn").canonicalFile
        val fallback = File(directory, "codec_incremental_f32_c12.mnn")
        val selected = if (autoGpu) fallback else File(prefs.getString("audio8_codec_model_path", fallback.absolutePath) ?: fallback.absolutePath).canonicalFile
        require(selected.parentFile == directory) { "The candidate codec model must use the app-owned fixed directory" }
        val spec = when (selected.name) {
            "codec_incremental_f32_c12.mnn" -> 261764152L to 12
            "codec_incremental_f32_c24.mnn" -> 261764152L to 24
            else -> error("Unsupported candidate codec model basename")
        }
        return CodecSelection(backend, precision, selected.absolutePath, spec.first, spec.second, slow, fast,
            backend == "mnn_opencl" && prefs.getString("audio8_gpu_state_policy", "device") != "host", autoGpu)
    }
    private fun acquireSession(arThreads: Int, codecThreads: Int, selection: CodecSelection, referenceDirectory: File = layout.reference): LoadedSession {
        check(Audio8ModelStore.validReference(referenceDirectory)) { "Audio8 参考音未准备好" }
        val ref = JSONObject(File(referenceDirectory, "reference.json").readText(Charsets.UTF_8))
        val shape = ref.getJSONArray("shape")
        val bytes = ByteBuffer.wrap(File(referenceDirectory, "reference.codes.i64.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val codes = Array(shape.getInt(0)) { LongArray(shape.getInt(1)) { bytes.long } }
        check(codes.size == 10 && !bytes.hasRemaining())
        val referenceText = ref.getString("reference_text")
        val before = SESSION
        if (before == null || before.directory != layout.models.absolutePath || before.arThreads != arThreads || before.codecThreads != codecThreads || before.codecSelection != selection) {
            dropSession()
            val begin = SystemClock.elapsedRealtime()
            val native = if (selection.backend == "ort_cpu") {
                Audio8Native.createProfiled(layout.models.absolutePath, arThreads, codecThreads, false)
            } else if (selection.backend == "qnn_htp") {
                val libraries = File(app.applicationInfo.nativeLibraryDir)
                val model = Audio8ModelStore.ensureBundledNpuCodec(app)
                val contextCache = File(app.noBackupFilesDir, "audio8-qnn-context")
                Audio8Native.createQnnSplit(layout.models.absolutePath, model.absolutePath,
                    File(libraries, "libonnxruntime_providers_qnn.so").absolutePath,
                    File(libraries, "libQnnHtp.so").absolutePath, libraries.absolutePath, "", contextCache.absolutePath,
                    selection.slowArThreads, selection.fastArThreads)
            } else {
                val model = if (selection.autoGpu) checkNotNull(Audio8ModelStore.ensureBundledGpuCodec(app)) else File(selection.modelPath)
                check(model.isFile && model.canRead() && model.length() == selection.modelBytes) { "The staged MNN candidate model is missing or incomplete" }
                Audio8Native.createMnnSplit(layout.models.absolutePath, model.absolutePath, selection.slowArThreads,
                    selection.fastArThreads, codecThreads, selection.backend == "mnn_opencl", selection.precision == "low", selection.keepGpuState)
            }
            try {
                Audio8Native.setPipeline(native, true)
                val tokenizer = Audio8Tokenizer(File(layout.models, "tokenizer/tokenizer.json").absolutePath)
                val prefix = tokenizer.buildReferencePrefix(referenceText, codes)
                val created = Session(native, layout.models.absolutePath, arThreads, codecThreads, selection, tokenizer,
                    referenceText, codes, prefix, SESSION_IDS.incrementAndGet())
                synchronized(NATIVE_LOCK) { SESSION = created; NATIVE_HANDLE.set(native) }
                return LoadedSession(created, SystemClock.elapsedRealtime() - begin)
            } catch (error: Throwable) { Audio8Native.destroy(native); throw error }
        }
        val prefix = before.tokenizer.buildReferencePrefix(referenceText, codes)
        val referenceChanged = prefix.positions != before.referencePrefix.positions ||
            !prefix.values.contentEquals(before.referencePrefix.values)
        if (referenceChanged) synchronized(NATIVE_LOCK) {
            Audio8Native.resetNative(before.handle); WARMED_STAMP = null
        }
        // Voice changes reset the shared AR state when the actual reference prefix changes.
        val refreshed = before.copy(referenceText = referenceText, referenceCodes = codes, referencePrefix = prefix)
        SESSION = refreshed
        return LoadedSession(refreshed, 0)
    }
    /** Normal StartActivity calls this once the app-owned model pack is ready. */
    private fun referenceWarmupAllowed(expectedCodec: CodecSelection? = null): Boolean = !closed.get() && runCatching { TtsBackendSettings.load(app).mode == TtsBackendSettings.LOCAL &&
            (expectedCodec == null || codecSelection() == expectedCodec) }.getOrDefault(false)
    private fun skipReferenceWarmup(reason: String, requested: Long, native: JSONObject? = null) {
        if (OWNERS.get() == 0) dropSession()
        WARMUP_REPORT = JSONObject().put("phase", "skipped").put("reason", reason)
            .put("requested_elapsed_ms", requested).put("completed_elapsed_ms", SystemClock.elapsedRealtime())
            .put("target_text_supplied", false).apply { if (native != null) put("native", native) }.toString()
    }
    private fun referenceVoiceId(directory: File): String = when {
        directory.name.startsWith("reference-whale-") -> "local_whale_reference"
        directory.name.startsWith("reference-ellen-") -> "local_ellen_reference"
        else -> "local_reference"
    }

    fun prepareReference(voiceId: String? = null) {
        if (!referenceWarmupAllowed()) return
        val tuning = app.getSharedPreferences("prts", Context.MODE_PRIVATE)
        val ar = tuning.getInt("audio8_ar_threads", 1).takeIf { it in 1..8 } ?: 1
        val codec = tuning.getInt("audio8_codec_threads", 4).takeIf { it in 1..8 } ?: 4
        val selection = runCatching { codecSelection() }.getOrElse { error ->
            Log.w(TAG, "Audio8 backend is unavailable for warmup", error)
            return
        }
        val directory = Audio8ModelStore.referenceDirectory(app, voiceId)
        val refJson = File(directory, "reference.json")
        val refCodes = File(directory, "reference.codes.i64.bin")
        val stamp = "${layout.models.absolutePath}|$ar|$codec|${selection.slowArThreads}|${selection.fastArThreads}|${selection.backend}|${selection.precision}|${selection.modelPath}|${Audio8ModelLayout.CODEC_VARIANT}|${directory.absolutePath}|${refJson.lastModified()}:${refJson.length()}|${refCodes.lastModified()}:${refCodes.length()}"
        val pending = WARMUP_PENDING.get()
        if (pending?.stamp == stamp || pending == null && (WARMED_STAMP == stamp || FAILED_WARMUP_STAMP == stamp)) return
        val request = ReferenceWarmup(stamp)
        WARMUP_PENDING.set(request)
        val referenceId = referenceVoiceId(directory)
        val requested = SystemClock.elapsedRealtime()
        WARMUP_REPORT = JSONObject().put("phase", "queued").put("requested_elapsed_ms", requested)
            .put("started_epoch_ms", System.currentTimeMillis()).put("resolved_reference_voice_id", referenceId).toString()
        WORKER.execute {
            val workerStart = SystemClock.elapsedRealtime()
            try {
                if (WARMUP_PENDING.get() !== request) return@execute
                if (!referenceWarmupAllowed(selection)) { skipReferenceWarmup("route_or_owner_changed_before_start", requested); return@execute }
                if (WARMED_STAMP == stamp && SESSION != null) return@execute
                Audio8ModelStore.ensureInstalled(app)
                if (WARMUP_PENDING.get() !== request) return@execute
                if (!referenceWarmupAllowed(selection)) { skipReferenceWarmup("route_or_owner_changed_before_load", requested); return@execute }
                WARMUP_REPORT = JSONObject(WARMUP_REPORT).put("phase", "preparing")
                    .put("worker_started_elapsed_ms", workerStart).toString()
                val loaded = try {
                    acquireSession(ar, codec, selection, directory)
                } catch (error: Exception) {
                    if (selection.autoGpu && referenceWarmupAllowed(selection)) disableAutoGpu(error)
                    throw error
                }
                val session = loaded.session
                val prefix = session.referencePrefix
                check(prefix.positions == prefix.reusablePrefixPositions)
                if (WARMUP_PENDING.get() !== request) return@execute
                if (!referenceWarmupAllowed(selection)) { skipReferenceWarmup("route_or_owner_changed_before_prefix", requested); return@execute }
                val native = JSONObject(Audio8Native.preparePrefix(session.handle, prefix.values, prefix.positions, prefix.reusablePrefixPositions))
                check(native.optInt("prefix_positions", -1) == prefix.positions &&
                    native.optInt("generated_frames", -1) == 0 && native.optInt("pcm_samples", -1) == 0 &&
                    native.optInt("decoder_calls", -1) == 0 && native.optInt("fast_calls", -1) == 0) {
                    "Reference preparation performed unexpected work: $native"
                }
                if (!referenceWarmupAllowed(selection)) { skipReferenceWarmup("route_or_owner_changed_during_prefix", requested, native); return@execute }
                val completed = SystemClock.elapsedRealtime()
                val result = JSONObject().put("phase", "ready").put("event", "audio8_reference_warmup")
                    .put("requested_elapsed_ms", requested).put("worker_started_elapsed_ms", workerStart)
                    .put("completed_elapsed_ms", completed).put("queue_ms", workerStart-requested)
                    .put("total_ms", completed-requested).put("model_load_ms", loaded.loadMs)
                    .put("prefix_positions", prefix.positions).put("resolved_reference_voice_id", referenceId)
                    .put("session_generation_id", session.serial).put("ar_threads", if (selection.backend == "ort_cpu") ar else JSONObject.NULL)
                    .put("ar_slow_threads", selection.slowArThreads).put("ar_fast_threads", selection.fastArThreads)
                    .put("codec_threads", codec)
                    .put("target_text_supplied", false).put("codec_backend", selection.backend).put("codec_precision", selection.precision)
                    .put("codec_model_path", selection.modelPath).put("native", native)
                    .put("worker_scheduling_after_prepare", workerScheduling(android.os.Process.myTid()))
                WARMUP_REPORT = result.toString(); WARMED_STAMP = stamp; FAILED_WARMUP_STAMP = null
                Log.i(TAG, result.toString())
            } catch (error: Throwable) {
                FAILED_WARMUP_STAMP = stamp
                WARMUP_REPORT = JSONObject().put("phase", "failed").put("requested_elapsed_ms", requested)
                    .put("completed_elapsed_ms", SystemClock.elapsedRealtime()).put("error", error.toString())
                    .put("target_text_supplied", false).toString()
                Log.e(TAG, "Reference warmup failed", error)
            } finally { WARMUP_PENDING.compareAndSet(request, null) }
        }
    }
    private fun workerScheduling(tid: Int): JSONObject = JSONObject().apply {
        runCatching {
            val value = File("/proc/self/task/$tid/status").readLines()
            put("cpus_allowed_list", value.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim() ?: JSONObject.NULL)
            put("process_threads", File("/proc/self/task").list()?.size ?: JSONObject.NULL)
            put("linux_nice", android.os.Process.getThreadPriority(tid))
            put("java_thread_priority", Thread.currentThread().priority)
            val fields = File("/proc/self/task/$tid/stat").readText().substringAfterLast(") ").trim().split(Regex("\\s+"))
            put("last_processor_after_native", fields.getOrNull(36)?.toIntOrNull() ?: JSONObject.NULL)
        }.onFailure { put("read_error", it.javaClass.simpleName) }
        put("java_thread_factory_sets_affinity", false)
    }
    fun cancelAll() { val current = handles.values.toList(); handles.clear(); current.forEach(SynthesisHandle::cancel) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelAll(); OWNERS.decrementAndGet(); val lease = LEASE.incrementAndGet()
        CLEANER.schedule({ WORKER.execute {
            if (OWNERS.get() == 0 && LEASE.get() == lease) {
                dropSession()
            }
        } }, 30, TimeUnit.SECONDS)
    }
    private data class Session(val handle: Long, val directory: String, val arThreads: Int, val codecThreads: Int,
        val codecSelection: CodecSelection, val tokenizer: Audio8Tokenizer, val referenceText: String, val referenceCodes: Array<LongArray>,
        val referencePrefix: Audio8Tokenizer.Prompt, val serial: Long)
    private class ReferenceWarmup(val stamp: String)

    companion object {
        const val MODEL_ID = "Audio8-0.1B-ONNX-317c12d4"
        private const val TAG = "PRTS-Audio8TTS"
        private val NATIVE_LOCK = Any()
        private val SESSION_IDS = AtomicLong()
        private val AUTO_GPU_FAILED = AtomicBoolean(false)
        private val WARMUP_PENDING = AtomicReference<ReferenceWarmup?>()
        @Volatile private var WARMED_STAMP: String? = null
        @Volatile private var FAILED_WARMUP_STAMP: String? = null
        @Volatile private var WARMUP_REPORT = "{\"phase\":\"idle\"}"
        fun referenceWarmupStatus(): JSONObject = JSONObject(WARMUP_REPORT)
        internal fun retireReference(directory: File) {
            WORKER.execute { directory.deleteRecursively() }
        }
        internal fun registerReference(graph: File, samples: FloatArray, checkReady: () -> Unit): LongArray =
            WORKER.submit<LongArray> {
                checkReady()
                dropSession()
                checkReady()
                Audio8Native.encodeReference(graph.absolutePath, samples)
            }.get()
        private fun dropSession() { synchronized(NATIVE_LOCK) {
            val old = SESSION; SESSION = null; NATIVE_HANDLE.set(0); ACTIVE.set(0)
            WARMED_STAMP = null
            if (old != null) WARMUP_REPORT = JSONObject().put("phase", "invalidated")
                .put("observed_elapsed_ms", SystemClock.elapsedRealtime()).toString()
            old?.let { Audio8Native.destroy(it.handle) }
        } }
        private val REQUESTS = AtomicLong(1_000_000)
        private val NATIVE_HANDLE = AtomicLong()
        private val ACTIVE = AtomicLong()
        private val OWNERS = AtomicInteger()
        private val LEASE = AtomicLong()
        private var SESSION: Session? = null
        private val WORKER = Executors.newSingleThreadExecutor { Thread(it, "PRTS-Audio8TTS") }
        private val CLEANER = Executors.newSingleThreadScheduledExecutor { Thread(it, "PRTS-Audio8TTS-Idle").apply { isDaemon = true } }
    }
}

package com.prts.client.voicecheck

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import com.prts.client.BuildConfig
import com.prts.client.audio.LocalVoiceSettings
import com.prts.client.audio.TtsBackendSettings
import com.prts.client.audio.audio8.OfflineAudio8TtsEngine
import com.prts.client.audio.audio8.Audio8ModelStore
import com.prts.client.audio.moss.MossModelStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Observes fresh edition defaults and imports an explicitly supplied Audio8 package in a separate test app.
 * An imported reference enables the existing 103-character local synthesis check.
 */
class TtsEditionInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var report: JSONObject
    private var resultFile: File? = null
    private var foregroundObserver: Application.ActivityLifecycleCallbacks? = null
    private val foregroundSeen = AtomicBoolean(false)
    private var ownsPackageOperation = false
    private val root: File get() = Audio8ModelStore.root(targetContext)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val mode = args.getString("mode") ?: "edition_default"
        val label = args.getString("result_label") ?: "tts-edition-$mode"
        report = JSONObject().put("schema", "dsh-tts-edition-instrumentation-v1")
            .put("mode", mode).put("passed", false).put("phase", "preflight")
            .put("automatic_failure_retries", 0).put("test_changed_tts_preferences", false)
            .put("acoustic_continuity_measured", false).put("human_listening_verified", false)
        var passed = false
        try {
            val expected = args.getString("expected_target_package") ?: "com.prts.client.deploytest"
            check(targetContext.packageName == expected && expected != "com.prts.client") {
                "SeparateOwnedTestApplicationRequired"
            }
            check(mode in setOf("edition_default", "import_package")) { "UnsupportedMode" }
            check(label.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "InvalidResultLabel" }
            val installedInfo = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0)
            val installedVersion = if (android.os.Build.VERSION.SDK_INT >= 28) installedInfo.longVersionCode else installedInfo.versionCode.toLong()
            val expectedVersion = args.getString("expected_version")?.toLong() ?: BuildConfig.VERSION_CODE.toLong()
            check(installedVersion == expectedVersion) { "UnexpectedTargetVersion" }
            report.put("target_package", targetContext.packageName).put("version_code", installedVersion)
                .put("edition", BuildConfig.TTS_EDITION).put("default_backend", BuildConfig.DEFAULT_TTS_BACKEND)
            val directory = File(requireNotNull(targetContext.getExternalFilesDir(null)), "acceptance/$label")
            check(!directory.exists() && directory.mkdirs()) { "UniqueResultDirectoryRequired" }
            resultFile = File(directory, "results.json")
            check(!File(targetContext.noBackupFilesDir, "tts-backend-v1.json").exists()) {
                "FreshTtsSettingsRequiredNoSettingsChanged"
            }
            verifyDefault()
            if (args.getString("headless") != "true") startForeground()
            if (mode == "edition_default") {
                if (BuildConfig.MOSS_MODEL_BUNDLED && args.getString("headless") != "true") verifyMossPreparation()
                report.put("synthesis_requests", 0)
            } else {
                check(BuildConfig.TTS_EDITION == "external") { "ExternalEditionRequiredForMissingModelFixture" }
                check(Audio8ModelStore.status(targetContext).phase == "idle") { "MissingModelsRequiredBeforeImport" }
                check(!File(root, "active-reference").exists() && !File(root, "inputs").exists()) { "MissingReferenceRequiredBeforeImport" }
                val state = importPackage()
                verifyInstalledPackage()
                if (state.ready) runPoem(directory) else report.put("synthesis_requests", 0)
            }
            passed = true
        } catch (error: Throwable) {
            report.put("error_type", error.javaClass.simpleName)
                .put("error", error.message?.take(512) ?: "EditionCheckFailed")
        } finally {
            if (!passed && ownsPackageOperation) {
                runCatching { onMain { Audio8ModelStore.cancel(targetContext) } }
                report.put("owned_package_cancel_requested", true)
            }
            val removed = runCatching {
                onMain {
                    foregroundObserver?.let {
                        (targetContext.applicationContext as Application).unregisterActivityLifecycleCallbacks(it)
                    }
                    foregroundObserver = null
                }
            }.isSuccess
            if (!removed) passed = false
            report.put("foreground_observer_removed", removed).put("normal_start_resumed", foregroundSeen.get())
                .put("passed", passed).put("phase", if (passed) "completed" else "failed")
            runCatching { writeResult() }.onFailure { passed = false; report.put("passed", false) }
            finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putBoolean("passed", passed); putString("mode", mode); putString("report", report.toString())
                resultFile?.let { putString("result_file", it.absolutePath) }
            })
        }
    }

    private fun modelEntries(): Map<String, Long> {
        val manifest = targetContext.assets.open("audio8/model-manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val files = manifest.getJSONArray("files")
        val entries = (0 until files.length()).associate { index ->
            val row = files.getJSONObject(index)
            val path = row.getString("path")
            check(path.startsWith("model/") && path.split('/').all { it.isNotBlank() && it != "." && it != ".." })
            path to row.getLong("bytes")
        }
        check(files.length() == 10 && entries.size == 10 && entries.values.sum() == 443_266_885L) { "Audio8ModelManifestMismatch" }
        return entries
    }

    private fun verifyDefault() {
        val edition = BuildConfig.TTS_EDITION
        check(edition in setOf("local", "external")) { "UnknownEdition" }
        args.getString("expected_edition")?.let { check(it == edition) { "UnexpectedEdition" } }
        val expected = if (edition == "external") TtsBackendSettings.API else TtsBackendSettings.LOCAL
        check(BuildConfig.DEFAULT_TTS_BACKEND == expected) { "BuildDefaultBackendMismatch" }
        val settings = TtsBackendSettings.load(targetContext)
        check(settings.mode == expected) { "FreshSettingsDefaultMismatch" }
        val expectedEngine = if (BuildConfig.MOSS_MODEL_BUNDLED) TtsBackendSettings.LOCAL_ENGINE_MOSS
            else TtsBackendSettings.LOCAL_ENGINE_AUDIO8
        check(settings.localEngine == expectedEngine) { "FreshSettingsEngineMismatch" }
        if (BuildConfig.MOSS_MODEL_BUNDLED) {
            check(edition == "local" && settings.localCodecBackend == TtsBackendSettings.LOCAL_CODEC_CPU)
        }
        val mossFolders = listOf("MOSS-TTS-Nano-100M-ONNX", "MOSS-Audio-Tokenizer-Nano-ONNX")
        val mossFiles = mossFolders.sumOf { targetContext.assets.list("moss/payload/$it").orEmpty().size }
        val mossVoices = targetContext.assets.list("moss/payload/voices").orEmpty().toSet()
        if (BuildConfig.MOSS_MODEL_BUNDLED) {
            check(mossFiles == 9 && mossVoices == setOf("whale.json", "ellen.json")) { "BundledMossPayloadMismatch" }
        } else check(mossFiles == 0 && mossVoices.isEmpty()) { "UnexpectedBundledMossPayload" }
        report.put("effective_local_engine", settings.localEngine).put("effective_local_codec", settings.localCodecBackend)
            .put("bundled_moss_files", mossFiles).put("bundled_moss_roles", mossVoices.size)
        val entries = modelEntries()
        val bundled = entries.keys.count { path ->
            val file = File(path)
            targetContext.assets.list("audio8/payload/${file.parent}").orEmpty().contains(file.name)
        }
        val installed = entries.keys.count { File(root, it).isFile }
        val state = Audio8ModelStore.status(targetContext)
        report.put("bundled_model_files", bundled).put("installed_model_files_before", installed)
            .put("effective_default_backend", expected).put("manager_before", stateJson(state))
        if (edition == "external") {
            check(bundled == 0 && installed == 0 && !state.ready) { "FreshExternalEditionMustHaveNoModels" }
        } else if (BuildConfig.MOSS_MODEL_BUNDLED) {
            check(bundled == 0 && installed == 0) { "FreshMossEditionMustHaveNoAudio8Models" }
        } else check(bundled == entries.size) { "LocalAudio8EditionMustBundleAudio8Model" }
        writeResult()
    }

    private fun verifyMossPreparation() {
        val deadline = SystemClock.elapsedRealtime() + 120_000L
        while (SystemClock.elapsedRealtime() < deadline && !MossModelStore.ready(targetContext)) {
            val state = MossModelStore.status(targetContext)
            check(state.phase !in setOf("downloading", "failed", "cancelled")) { "MossPreparation:${state.phase}:${state.error}" }
            SystemClock.sleep(200)
        }
        check(MossModelStore.ready(targetContext)) { "MossPreparationDeadline" }
        val model = MossModelStore.root(targetContext)
        check(File(model, "voices/whale.json").length() == BuildConfig.MOSS_WHALE_VOICE_BYTES)
        check(File(model, "voices/ellen.json").length() == BuildConfig.MOSS_ELLEN_VOICE_BYTES)
        report.put("moss_offline_prepared", true)
    }

    private fun importPackage(): Audio8ModelStore.State {
        val provided = requireNotNull(args.getString("model_package_path")) { "ExplicitModelPackagePathRequired" }
        val expectedBytes = requireNotNull(args.getString("model_package_bytes")?.toLongOrNull()) { "ExplicitModelPackageBytesRequired" }
        val owned = requireNotNull(targetContext.getExternalFilesDir(null)).canonicalFile
        val file = File(provided).canonicalFile
        check(expectedBytes > 0 && file.toPath().startsWith(owned.toPath()) && file.isFile && file.length() == expectedBytes) {
            "OwnedExactModelPackageRequired"
        }
        val hasReference = ZipFile(file).use { archive ->
            val metadata = archive.getEntry("inputs/reference.json") != null
            val codes = archive.getEntry("inputs/reference.codes.i64.bin") != null
            check(metadata == codes) { "IncompletePackageReference" }
            metadata
        }
        val expectedPhase = if (hasReference) "ready" else "needs_reference"
        val seconds = (args.getString("package_timeout_seconds")?.toLongOrNull() ?: 600L).coerceIn(30L, 1800L)
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
        report.put("package_bytes", expectedBytes).put("expected_import_phase", expectedPhase)
            .put("package_timeout_seconds", seconds)
        val callbacks = AtomicInteger()
        val mainThreadCallbacks = AtomicBoolean(true)
        var subscription: AutoCloseable? = null
        onMain {
            subscription = Audio8ModelStore.observe(targetContext) {
                callbacks.incrementAndGet()
                if (Looper.myLooper() != Looper.getMainLooper()) mainThreadCallbacks.set(false)
            }
        }
        val history = JSONArray()
        var lastRecorded = 0L
        var lastPhase = ""
        try {
            mark("importing_package")
            onMain { Audio8ModelStore.importPackage(targetContext, Uri.fromFile(file)) }
            ownsPackageOperation = true
            while (SystemClock.elapsedRealtime() < deadline) {
                val state = Audio8ModelStore.status(targetContext)
                val now = SystemClock.elapsedRealtime()
                if (state.phase != lastPhase || now - lastRecorded >= 1000L) {
                    if (history.length() < 256) history.put(stateJson(state))
                    report.put("manager", stateJson(state)).put("package_progress", history)
                    writeResult(); lastRecorded = now; lastPhase = state.phase
                }
                check(state.phase !in setOf("failed", "cancelled")) { "PackageOperation:${state.phase}:${state.error}" }
                if (state.phase == "ready" || state.phase == "needs_reference") {
                    ownsPackageOperation = false
                    check(state.phase == expectedPhase) { "PackageReferenceStateMismatch:${state.phase}" }
                    check(callbacks.get() > 0 && mainThreadCallbacks.get()) { "ObserveMainThreadContractFailed" }
                    report.put("manager_final", stateJson(state)).put("observer_callbacks", callbacks.get())
                        .put("observer_main_thread", mainThreadCallbacks.get())
                    return state
                }
                SystemClock.sleep(200)
            }
            error("PackageObservationDeadline")
        } finally { onMain { subscription?.close() } }
    }

    private fun verifyInstalledPackage() {
        val entries = modelEntries()
        for ((path, expectedBytes) in entries) {
            val file = File(root, path)
            check(file.isFile && file.canRead() && file.length() == expectedBytes) { "InstalledModelSizeMismatch:$path" }
        }
        val manifest = JSONObject(File(root, "model/runtime_manifest.json").readText(Charsets.UTF_8))
        check(manifest.getInt("sample_rate") == 44_100) { "Audio8RuntimeSampleRateMismatch" }
        report.put("verified_model_files", entries.size).put("verified_model_bytes", entries.values.sum())
            .put("runtime_sample_rate", 44_100)
        writeResult()
    }

    private fun runPoem(directory: File) {
        check(POEM.codePointCount(0, POEM.length) == 103) { "OriginalPoemFixtureChanged" }
        val engine = OfflineAudio8TtsEngine(targetContext)
        val done = CountDownLatch(1)
        val output = AtomicReference<OfflineAudio8TtsEngine.Output?>()
        val failure = AtomicReference<Throwable?>()
        val progress = AtomicReference<String>("queued")
        val frames = AtomicLong()
        val chunks = AtomicInteger()
        val finals = AtomicInteger()
        val sequence = AtomicInteger()
        val valid = AtomicBoolean(true)
        val finite = AtomicBoolean(true)
        val firstPcm = AtomicLong(-1L)
        val began = SystemClock.elapsedRealtime()
        var handle: OfflineAudio8TtsEngine.SynthesisHandle? = null
        mark("synthesizing_original103")
        try {
            check(engine.modelState().ready) { "ActualNativeModelNotReady" }
            handle = engine.synthesize(POEM, LocalVoiceSettings.WHALE_VOICE, "zh",
                onProgress = { progress.set(it.phase) }, cpuThreads = 4,
                onPcm = { chunk ->
                    if (chunk.sequence != sequence.getAndIncrement() || chunk.sampleRate != 44_100) valid.set(false)
                    if (chunk.samples.any { !it.isFinite() }) finite.set(false)
                    if (chunk.samples.isNotEmpty()) firstPcm.compareAndSet(-1L, SystemClock.elapsedRealtime())
                    chunks.incrementAndGet()
                    if (chunk.final) finals.incrementAndGet()
                    frames.addAndGet(chunk.samples.size.toLong()) <= 1_966_080L
                }) { result ->
                result.onSuccess { output.set(it) }.onFailure { failure.set(it) }
                done.countDown()
            }
            val deadline = SystemClock.elapsedRealtime() + 240_000L
            while (!done.await(1, TimeUnit.SECONDS)) {
                report.put("native_progress", progress.get()).put("pcm_frames", frames.get())
                    .put("pcm_chunks", chunks.get()).put("synthesis_elapsed_ms", SystemClock.elapsedRealtime() - began)
                writeResult()
                check(SystemClock.elapsedRealtime() < deadline) { "OriginalPoemSynthesisDeadline" }
            }
            failure.get()?.let { throw it }
            val result = checkNotNull(output.get()) { "NoNativeOutput" }
            val native = result.nativeReport
            val wave = waveInfo(result.wav)
            val safe = JSONObject()
            listOf("ok", "natural_eos_evidence", "frame_cap_reached", "generated_frames", "audio_seconds",
                "load_ms", "generate_ms", "app_total_ms", "process_cpu_ms", "worker_wall_ms").forEach {
                if (native.has(it)) safe.put(it, native.get(it))
            }
            report.put("synthesis_requests", 1).put("fixture", "yueyanglou-original103").put("text_chars", 103)
                .put("voice_id", LocalVoiceSettings.WHALE_VOICE).put("requested_cpu_threads", 4)
                .put("pcm_frames", frames.get()).put("pcm_chunks", chunks.get()).put("final_chunks", finals.get())
                .put("pcm_sequence_valid", valid.get()).put("pcm_finite", finite.get())
                .put("first_pcm_ms", firstPcm.get().takeIf { it >= 0 }?.minus(began) ?: JSONObject.NULL)
                .put("synthesis_wall_ms", SystemClock.elapsedRealtime() - began)
                .put("audio_seconds", result.audioSeconds).put("wave", wave).put("native", safe)
                .put("direct_production_engine", true).put("audio_track_playback_checked", false)
            check(native.optBoolean("ok") && native.optBoolean("natural_eos_evidence") &&
                !native.optBoolean("frame_cap_reached")) { "OriginalPoemDidNotEndNaturally" }
            check(valid.get() && finite.get() && frames.get() > 0 && chunks.get() >= 2 && finals.get() == 1) {
                "PcmSequenceOrFiniteCheckFailed"
            }
            check(wave.getLong("frames") == frames.get() &&
                kotlin.math.abs(result.audioSeconds - frames.get() / 44100.0) <= 2.0 / 44100.0) { "WaveAndStreamCountsDiffer" }
            result.wav.copyTo(File(directory, "poem103-native.wav"), overwrite = false)
            report.put("saved_wave", "poem103-native.wav")
            writeResult()
        } finally {
            if (done.count != 0L) handle?.cancel()
            output.get()?.close()
            engine.close()
        }
    }

    private fun waveInfo(file: File): JSONObject = RandomAccessFile(file, "r").use {
        check(it.length() in 46L..4_000_000L) { "NativeWaveSizeInvalid" }
        val bytes = ByteArray(44); it.readFully(bytes)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE" &&
            data.getShort(20).toInt() == 1 && data.getShort(22).toInt() == 1 &&
            data.getInt(24) == 44_100 && data.getShort(34).toInt() == 16 &&
            String(bytes, 36, 4, Charsets.US_ASCII) == "data") { "NativeWaveFormatInvalid" }
        val payload = data.getInt(40).toLong() and 0xffffffffL
        check(payload % 2L == 0L && payload + 44L == it.length()) { "NativeWaveCountInvalid" }
        JSONObject().put("sample_rate", 44_100).put("channels", 1).put("bits_per_sample", 16)
            .put("frames", payload / 2L).put("bytes", it.length())
    }

    private fun startForeground() {
        val resumed = CountDownLatch(1)
        onMain {
            foregroundObserver = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity.javaClass.name == "com.prts.client.ui.StartActivity") {
                        foregroundSeen.set(true); resumed.countDown()
                    }
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            (targetContext.applicationContext as Application).registerActivityLifecycleCallbacks(requireNotNull(foregroundObserver))
        }
        report.put("phase", "opening_start_activity"); writeResult()
        sendStatus(0, Bundle().apply { putString("phase", "launch_StartActivity") })
        val began = SystemClock.elapsedRealtime()
        while (!resumed.await(1, TimeUnit.SECONDS)) {
            report.put("foreground_wait_elapsed_ms", SystemClock.elapsedRealtime() - began); writeResult()
            check(SystemClock.elapsedRealtime() - began < 120_000L) { "NormalSystemLauncherRequired" }
        }
    }

    private fun onMain(block: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        runOnMainSync { try { block() } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }
    }

    private fun stateJson(state: Audio8ModelStore.State): JSONObject =
        JSONObject().put("phase", state.phase).put("ready", state.ready)
            .put("processed_bytes", state.processedBytes).put("total_bytes", state.totalBytes)
            .put("bytes_per_second", state.bytesPerSecond).put("eta_seconds", state.etaSeconds ?: JSONObject.NULL)
            .put("error", state.error ?: JSONObject.NULL)

    private fun mark(phase: String) {
        report.put("phase", phase); writeResult()
        sendStatus(0, Bundle().apply { putString("phase", phase) })
    }

    private fun writeResult() {
        val file = resultFile ?: return
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write((report.toString(2) + "\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    companion object {
        private const val POEM = "至若春和景明，波澜不惊，上下天光，一碧万顷。沙鸥翔集，锦鳞游泳；岸芷汀兰，郁郁青青。而或长烟一空，皓月千里，浮光跃金，静影沉璧，渔歌互答，此乐何极！登斯楼也，则有心旷神怡，宠辱偕忘，把酒临风，其喜洋洋者矣。"
    }
}

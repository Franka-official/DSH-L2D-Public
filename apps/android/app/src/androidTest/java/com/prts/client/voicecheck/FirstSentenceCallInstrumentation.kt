package com.prts.client.voicecheck

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import com.prts.client.BuildConfig
import com.prts.client.audio.LocalVoiceSettings
import com.prts.client.audio.SpeechPlayer
import com.prts.client.audio.SpeechSynthesisRouter
import com.prts.client.audio.SpeechTempo
import com.prts.client.audio.TtsBackendSettings
import com.prts.client.audio.audio8.OfflineAudio8TtsEngine
import com.prts.client.audio.audio8.Audio8ModelStore
import com.prts.client.localdsh.LocalDshHub
import com.prts.client.net.InAudio
import com.prts.client.net.Proto
import com.prts.client.net.VoiceProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Fixed text-delivery experiment in the separate deployment test application.
 * Hub, router, Audio8 synthesis and AudioTrack are production classes. No LLM/API call.
 * Baseline and early have identical text and a fixed 10-second done deadline.
 */
class FirstSentenceCallInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var report: JSONObject
    private lateinit var directory: File
    private var observer: Application.ActivityLifecycleCallbacks? = null
    private var player: SpeechPlayer? = null
    private var router: SpeechSynthesisRouter? = null
    private var fixture: HubFixture? = null
    private var feeder: Thread? = null
    private val failure = AtomicReference<Throwable?>()
    private val guard = Any()
    private val events = ArrayList<JSONObject>()
    private val segments = linkedMapOf(FIRST to Segment(FIRST), TAIL to Segment(TAIL))
    @Volatile private var stopping = false
    @Volatile private var submittedAt = 0L
    @Volatile private var doneSentAt = 0L

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments); args = arguments ?: Bundle(); start()
    }

    override fun onStart() {
        val mode = args.getString("mode") ?: "early"
        val label = args.getString("result_label") ?: "first-sentence-$mode"
        report = JSONObject().put("schema", "dsh-first-sentence-call-v1").put("mode", mode)
            .put("phase", "preflight").put("passed", false).put("automatic_failure_retries", 0)
            .put("llm_requests", 0).put("api_requests", 0).put("preferences_changed", false)
            .put("acoustic_continuity_measured", false).put("human_listening_verified", false)
            .put("experiment_scope", "simulated text delivery; production Hub/router/PCM player")
            .put("baseline_scope", "delayed text into the same new Hub; two native segments in both modes")
            .put("compares_old_whole_answer_native_path", false)
        var passed = false
        try {
            check(targetContext.packageName == "com.prts.client.deploytest") { "SeparateDeploymentTestAppRequired" }
            check(mode == "baseline" || mode == "early") { "UnsupportedMode" }
            check(label.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "InvalidResultLabel" }
            val info = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0)
            val version = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            check(version == (args.getString("expected_version")?.toLong() ?: BuildConfig.VERSION_CODE.toLong())) { "UnexpectedTargetVersion" }
            directory = File(requireNotNull(targetContext.getExternalFilesDir(null)), "acceptance/$label")
            check(!directory.exists() && directory.mkdirs()) { "UniqueResultDirectoryRequired" }
            report.put("target_package", targetContext.packageName).put("version_code", version)
                .put("fixture_text", FIRST + TAIL).put("first_sentence", FIRST).put("tail", TAIL)
                .put("done_delay_ms", DONE_DELAY).put("early_text_delay_ms", EARLY_DELAY)
            check(TtsBackendSettings.load(targetContext).mode == TtsBackendSettings.LOCAL) { "LocalAudio8SettingRequired" }
            val tuning = targetContext.getSharedPreferences("prts", 0)
            val arThreads = tuning.getInt("audio8_ar_threads", 1)
            val codecThreads = tuning.getInt("audio8_codec_threads", 4)
            val chunkFrames = tuning.getInt("audio8_chunk_frames", 12)
            val speed = SpeechTempo.speed(targetContext)
            check(arThreads == 1 && codecThreads == 4 && chunkFrames == 12 && abs(speed - SpeechTempo.SPEED) < 0.001f) {
                "ExistingAudio8Cpu1x4C12ElegantRequired"
            }
            report.put("requested_ar_threads", arThreads).put("requested_codec_threads", codecThreads)
                .put("requested_chunk_frames", chunkFrames).put("requested_playback_speed", speed.toDouble())
            foreground()
            mark("waiting_existing_model_preparation")
            Audio8ModelStore.prepare(targetContext)
            val preparationDeadline = SystemClock.elapsedRealtime() + 180_000L
            while (true) {
                val state = Audio8ModelStore.status(targetContext)
                report.put("model_phase", state.phase); writeResult()
                if (state.ready) break
                check(state.phase !in setOf("needs_reference", "failed", "cancelled")) { "Audio8NotReady:${state.phase}" }
                check(SystemClock.elapsedRealtime() < preparationDeadline) { "ExistingModelsNotReady" }
                SystemClock.sleep(500)
            }
            mark("checking_hub_lifecycle")
            report.put("hub_lifecycle", lifecycleChecks()); writeResult()
            configurePlayback()
            val own = HubFixture { raw -> handleHubEvent(raw) }
            fixture = own
            submittedAt = SystemClock.elapsedRealtime()
            own.open()
            report.put("submitted_elapsed_ms", submittedAt).put("request_id", own.requestId)
            mark("awaiting_fixed_text_delivery")
            feeder = Thread({
                try {
                    if (mode == "early") { until(submittedAt + EARLY_DELAY); own.text(FIRST) }
                    until(submittedAt + DONE_DELAY)
                    if (!stopping) {
                        own.text(if (mode == "early") TAIL else FIRST + TAIL)
                        doneSentAt = SystemClock.elapsedRealtime()
                        note("done_submitted", JSONObject().put("scheduled_elapsed_ms", submittedAt + DONE_DELAY))
                        own.done(true)
                    }
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }, "PRTS-FirstSentence-TextFixture").apply { start() }
            val deadline = submittedAt + 240_000L
            var lastProgress = 0L
            while (true) {
                failure.get()?.let { throw it }
                val completed = synchronized(guard) { segments.values.all { it.nativeComplete && it.playbackComplete } }
                if (doneSentAt > 0 && completed) break
                val now = SystemClock.elapsedRealtime()
                check(now < deadline) { "SynthesisPlaybackObservationTimedOut" }
                if (now - lastProgress >= 1_000L) {
                    report.put("phase", if (doneSentAt == 0L) "before_fixed_done" else "draining_production_playback")
                        .put("elapsed_since_submit_ms", now - submittedAt)
                    snapshot(); writeResult(); lastProgress = now
                }
                SystemClock.sleep(20)
            }
            feeder?.join(2_000)
            val messages = own.byType("audio")
            check(messages.size == 2) { "ExpectedExactlyFirstSentenceAndTail" }
            check(messages.joinToString("") { it.getJSONObject("display_text").getString("spoken_text") } == FIRST + TAIL) { "TextSpansChangedOrDuplicated" }
            check(messages.all { it.getJSONObject("actions").getString("reply_id") == own.requestId }) { "ReplyIdChangedAcrossSegments" }
            check(own.byType("backend-synth-complete").size == 1) { "ExpectedExactlyOneBackendComplete" }
            val first = synchronized(guard) { segments.getValue(FIRST) }
            check(first.audioStartedAt > 0L && doneSentAt > 0L) { "NoActualAudioStart" }
            val early = first.audioStartedAt < doneSentAt
            report.put("first_audio_before_done", early).put("done_submitted_elapsed_ms", doneSentAt)
            if (mode == "early") check(early) { "FirstSentenceDidNotStartBeforeFixedDone" }
            else check(first.hubAudioAt >= submittedAt + DONE_DELAY - 100L) { "BaselineTextWasDeliveredEarly" }
            synchronized(guard) { segments.values.forEach { it.validate() } }
            passed = true
        } catch (error: Throwable) {
            report.put("error_type", error.javaClass.simpleName).put("error", error.message?.take(512) ?: "FirstSentenceCheckFailed")
        } finally {
            stopping = true
            feeder?.interrupt(); runCatching { feeder?.join(2_000) }
            val cleaned = runCatching { onMain {
                fixture?.close(); fixture = null
                router?.close(); router = null
                player?.close(); player = null
                observer?.let { (targetContext.applicationContext as Application).unregisterActivityLifecycleCallbacks(it) }
                observer = null
            } }.isSuccess
            if (!cleaned) passed = false
            report.put("cleanup_completed", cleaned).put("passed", passed).put("phase", if (passed) "completed" else "failed")
            runCatching { snapshot(); writeResult() }.onFailure { passed = false }
            finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putBoolean("passed", passed); putString("mode", mode); putString("report", report.toString())
                if (::directory.isInitialized) putString("result_file", File(directory, "results.json").absolutePath)
            })
        }
    }

    private fun configurePlayback() = onMain {
        player = SpeechPlayer({ message ->
            synchronized(guard) { segment(message).onStartCount++ }; noteFor("on_start", message)
        }, { message ->
            synchronized(guard) { segment(message).apply {
                audioStartCount++; if (audioStartedAt == 0L) audioStartedAt = SystemClock.elapsedRealtime()
            } }
            noteFor("audio_start", message)
        }, {}, { note("player_idle") }, { reason ->
            failure.compareAndSet(null, IllegalStateException("PlayerError:$reason"))
        }, onAudioEnded = { message, complete ->
            synchronized(guard) { segment(message).apply { endCount++; playbackComplete = complete; endedAt = SystemClock.elapsedRealtime() } }
            if (!complete && !stopping) failure.compareAndSet(null, IllegalStateException("PlaybackEndedIncomplete"))
            noteFor("audio_end", message, JSONObject().put("success", complete))
        }, onPlaybackProgress = { message, played, written, final ->
            synchronized(guard) { segment(message).apply {
                playedMs = maxOf(playedMs, played); writtenMs = maxOf(writtenMs, written); finalProgress = finalProgress || final
            } }
        }, playbackSpeed = { SpeechTempo.speed(targetContext) })
        router = SpeechSynthesisRouter(targetContext,
            VoiceProfile(LocalVoiceSettings.MODE_ID, LocalVoiceSettings.WHALE_VOICE, "zh"),
            { payload -> requireNotNull(player).enqueue(payload) },
            { notice -> note("router_notice", JSONObject().put("message", notice.take(256))) }, {},
            onLocalPayload = { local -> requireNotNull(player).enqueueLocal(local) },
            onStreamingPcm = { source, pcm ->
                synchronized(guard) { segment(source).observe(pcm) }
                requireNotNull(player).enqueuePcm(source, pcm).also { accepted ->
                    if (!accepted && !stopping) failure.compareAndSet(null, IllegalStateException("ProductionPlayerRejectedPcm"))
                }
            }, onStreamingComplete = { source, result ->
                try {
                    result.fold(onSuccess = { output ->
                        val trace = synchronized(guard) { segment(source) }
                        val saved = File(directory, if (trace.text == FIRST) "first.wav" else "tail.wav")
                        output.wav.copyTo(saved, overwrite = false)
                        synchronized(guard) {
                            trace.nativeReport = JSONObject(output.nativeReport.toString())
                            trace.nativeSamples = readWavSamples(saved)
                            trace.nativeComplete = true; trace.nativeFinishedAt = SystemClock.elapsedRealtime()
                            trace.wav = saved.name; trace.wavBytes = saved.length()
                        }
                        noteFor("native_complete", source)
                    }, onFailure = { error -> failure.compareAndSet(null, error) })
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { requireNotNull(player).completeStreaming(source, result) }
            })
        check(requireNotNull(router).usesLocalModel && requireNotNull(router).available) { "ProductionLocalRouterNotReady" }
    }

    private fun handleHubEvent(raw: String) {
        try {
            val event = JSONObject(raw)
            if (event.optString("type") == "audio") {
                val message = Proto.json.decodeFromString(InAudio.serializer(), raw)
                synchronized(guard) { segment(message).hubAudioAt = SystemClock.elapsedRealtime() }
                noteFor("hub_audio", message)
                requireNotNull(router).enqueue(message)
            } else note("hub_" + event.optString("type"))
        } catch (error: Throwable) { failure.compareAndSet(null, error) }
    }

    private fun lifecycleChecks(): JSONArray {
        val result = JSONArray()
        fun scenario(name: String, test: (HubFixture) -> Unit) {
            val h = HubFixture {}
            try { h.open(); test(h); result.put(JSONObject().put("name", name).put("passed", true)) }
            finally { h.close() }
        }
        scenario("complete_sentence_then_tail_once") { h ->
            h.text("你好，"); onMain {}; check(h.byType("audio").isEmpty()) { "CommaEmittedEarlyAudio" }
            h.text("很高兴见到你。"); onMain {}; check(h.byType("audio").size == 1 && h.byType("backend-synth-complete").isEmpty())
            h.text(TAIL); h.done(true); onMain {}
            check(h.byType("audio").size == 2 && h.byType("backend-synth-complete").size == 1)
            check(h.byType("audio").joinToString("") { it.getJSONObject("display_text").getString("spoken_text") } == FIRST + TAIL)
            check(h.byType("audio").all { it.getJSONObject("actions").getString("reply_id") == h.requestId })
        }
        scenario("duplicate_done_and_late_text_suppressed") { h ->
            h.text(FIRST); h.done(true); onMain {}; h.done(true); h.text(TAIL, requireConsumed = false); onMain {}
            check(h.byType("audio").size == 1 && h.byType("backend-synth-complete").size == 1)
        }
        scenario("queued_audio_and_complete_cancelled_before_main_delivery") { h ->
            onMain {
                val producerFailure = AtomicReference<Throwable?>()
                val t = Thread { try { h.text(FIRST); h.done(true) } catch (e: Throwable) { producerFailure.set(e) } }
                t.start(); t.join(2_000); check(!t.isAlive) { "HubProducerBlockedOnMain" }; producerFailure.get()?.let { throw it }
                h.interrupt()
            }
            onMain {}; check(h.byType("audio").isEmpty() && h.byType("backend-synth-complete").isEmpty())
            h.text(TAIL, requireConsumed = false); h.done(true); onMain {}; check(h.byType("audio").isEmpty() && h.byType("backend-synth-complete").isEmpty())
        }
        scenario("cancel_after_first_suppresses_tail") { h ->
            h.text(FIRST); onMain {}; check(h.byType("audio").size == 1)
            h.interrupt(); h.text(TAIL); h.done(true); onMain {}
            check(h.byType("audio").size == 1 && h.byType("backend-synth-complete").isEmpty())
        }
        scenario("failed_done_after_first_suppresses_tail_and_late_success") { h ->
            h.text(FIRST); onMain {}; h.text(TAIL); h.done(false); onMain {}; h.done(true); onMain {}
            check(h.byType("audio").size == 1 && h.byType("backend-synth-complete").isEmpty())
            check(h.byType("control").any { it.optString("text") == "interrupt" }) { "PartialFailureDidNotInterruptPlayback" }
        }
        return result
    }

    private inner class HubFixture(private val consumer: (String) -> Unit) {
        private val identity = Any()
        private val received = ArrayList<JSONObject>()
        lateinit var requestId: String
        fun open() {
            onMain {
                LocalDshHub.shutdown()
                LocalDshHub.attach(identity, { raw ->
                    synchronized(received) { received.add(JSONObject(raw)) }; consumer(raw)
                }, {})
                check(LocalDshHub.fromFrontend(identity, JSONObject().put("type", "text-input")
                    .put("text", "请朗读这段固定测试文字").put("reply_language", "zh").toString()))
            }
            val frames = LocalDshHub.poll()
            val requests = (0 until frames.length()).map { frames.getJSONObject(it) }.filter { it.optString("type") == "request" }
            check(requests.size == 1) { "ExpectedOneOwnedHubRequest" }; requestId = requests.single().getString("request_id")
        }
        fun text(value: String, requireConsumed: Boolean = true): Boolean {
            val consumed = LocalDshHub.connectorEvent(JSONObject().put("type", "event")
                .put("request_id", requestId).put("event", JSONObject().put("type", "text").put("text", value)))
            if (requireConsumed) check(consumed) { "TextEventNotConsumed" }
            return consumed
        }
        fun done(success: Boolean) { check(LocalDshHub.connectorEvent(JSONObject().put("type", "done")
            .put("request_id", requestId).put("success", success))) { "DoneNotConsumed" } }
        fun interrupt() = onMain { check(LocalDshHub.fromFrontend(identity, JSONObject().put("type", "interrupt-signal").toString())) }
        fun byType(type: String): List<JSONObject> = synchronized(received) { received.filter { it.optString("type") == type }.toList() }
        fun close() = onMain { LocalDshHub.detach(identity); LocalDshHub.shutdown() }
    }

    private class Segment(val text: String) {
        var hubAudioAt = 0L; var firstPcmAt = 0L; var audioStartedAt = 0L; var endedAt = 0L; var nativeFinishedAt = 0L
        var lastSequence = -1; var pcmChunks = 0; var finalCount = 0; var samples = 0L; var nativeSamples = 0L
        var sampleRate = 0; var requestId = -1L; var playedMs = 0L; var writtenMs = 0L; var finalProgress = false
        var onStartCount = 0; var audioStartCount = 0; var endCount = 0
        var nativeComplete = false; var playbackComplete = false; var nativeReport: JSONObject? = null
        var wav = ""; var wavBytes = 0L
        fun observe(pcm: OfflineAudio8TtsEngine.PcmChunk) {
            check(finalCount == 0) { "PcmAfterFinal" }
            check(pcm.samples.all { it.isFinite() }) { "NonfinitePcm" }
            if (lastSequence >= 0) check(pcm.sequence == lastSequence + 1) { "PcmSequenceGap" }
            else { check(pcm.sequence == 0 || pcm.sequence == 1); requestId = pcm.requestId; sampleRate = pcm.sampleRate }
            check(pcm.requestId == requestId && pcm.sampleRate == sampleRate && sampleRate == 44_100) { "PcmIdentityOrRateChanged" }
            lastSequence = pcm.sequence; pcmChunks++; samples += pcm.samples.size
            if (pcm.samples.isNotEmpty() && firstPcmAt == 0L) firstPcmAt = SystemClock.elapsedRealtime()
            if (pcm.final) finalCount++
        }
        fun validate() {
            check(nativeComplete && playbackComplete && finalCount == 1 && samples > 0L && samples == nativeSamples) { "IncompleteNativeOrPlaybackSamples" }
            check(onStartCount == 1 && audioStartCount == 1 && endCount == 1) { "PlaybackCallbackCountMismatch" }
            check(firstPcmAt > 0L && audioStartedAt >= firstPcmAt && endedAt >= audioStartedAt) { "InvalidPlaybackTiming" }
            val n = checkNotNull(nativeReport).optJSONObject("native") ?: checkNotNull(nativeReport)
            check(n.optBoolean("ok", false) && n.optBoolean("natural_eos_evidence", false)
                && n.has("frame_cap_reached") && !n.getBoolean("frame_cap_reached")) { "NativeNaturalEosEvidenceMissing" }
            check(n.getLong("pcm_samples") == samples) { "NativeReportSamplesMismatch" }
        }
        fun json() = JSONObject().put("text", text).put("hub_audio_elapsed_ms", hubAudioAt).put("first_pcm_elapsed_ms", firstPcmAt)
            .put("audio_start_elapsed_ms", audioStartedAt).put("audio_end_elapsed_ms", endedAt).put("native_complete_elapsed_ms", nativeFinishedAt)
            .put("pcm_chunks", pcmChunks).put("final_markers", finalCount).put("pcm_samples", samples).put("wav_samples", nativeSamples)
            .put("sample_rate", sampleRate).put("request_id", requestId).put("last_sequence", lastSequence)
            .put("playback_completed", playbackComplete).put("native_completed", nativeComplete)
            .put("on_start_count", onStartCount).put("audio_start_count", audioStartCount).put("audio_end_count", endCount)
            .put("played_ms_last_observed", playedMs).put("written_ms_last_observed", writtenMs)
            .put("wav", wav).put("wav_bytes", wavBytes).put("native_report", nativeReport ?: JSONObject.NULL)
    }

    private fun segment(message: InAudio): Segment {
        val text = message.display_text?.spoken_text ?: message.display_text?.text ?: error("ProductionSpokenTextMissing")
        return segments[text] ?: error("UnexpectedProductionTextSpan")
    }
    private fun noteFor(type: String, message: InAudio, extra: JSONObject = JSONObject()) {
        note(type, extra.put("text", segment(message).text))
    }
    private fun note(type: String, extra: JSONObject = JSONObject()) = synchronized(guard) {
        if (events.size < 1024) events.add(extra.put("event", type).put("elapsed_ms", SystemClock.elapsedRealtime()))
    }
    private fun snapshot() = synchronized(guard) {
        report.put("events", JSONArray(events)).put("segments", JSONArray(segments.values.map { it.json() }))
    }
    private fun until(at: Long) {
        while (!stopping) {
            val left = at - SystemClock.elapsedRealtime(); if (left <= 0L) return
            Thread.sleep(minOf(left, 100L))
        }
        throw InterruptedException("FixtureStopped")
    }
    private fun readWavSamples(file: File): Long {
        java.io.RandomAccessFile(file, "r").use { f ->
            check(f.length() >= 44L); val header = ByteArray(12); f.readFully(header)
            check(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE")
            var align = 0
            while (f.filePointer + 8L <= f.length()) {
                val tag = ByteArray(4); f.readFully(tag); val size = Integer.reverseBytes(f.readInt()).toLong() and 0xffffffffL
                val begin = f.filePointer
                check(size <= f.length() - begin) { "TruncatedWav" }
                when (String(tag, Charsets.US_ASCII)) {
                    "fmt " -> { check(size >= 16); val fmt = ByteArray(16); f.readFully(fmt)
                        fun u16(n: Int) = (fmt[n].toInt() and 255) or ((fmt[n + 1].toInt() and 255) shl 8)
                        val rate = u16(4) or (u16(6) shl 16)
                        check(u16(0) == 1 && u16(2) == 1 && u16(14) == 16 && rate == 44_100)
                        align = u16(12); check(align == 2)
                    }
                    "data" -> { check(align == 2 && size % align == 0L); return size / align }
                }
                f.seek(begin + size + (size and 1L))
            }
        }
        error("MissingWavData")
    }
    private fun foreground() {
        val resumed = CountDownLatch(1)
        onMain {
            observer = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity.packageName == targetContext.packageName && activity.javaClass.name == "com.prts.client.ui.StartActivity") resumed.countDown()
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            (targetContext.applicationContext as Application).registerActivityLifecycleCallbacks(requireNotNull(observer))
        }
        mark("opening_start_activity")
        sendStatus(0, Bundle().apply { putString("phase", "launch_StartActivity") })
        val began = SystemClock.elapsedRealtime()
        while (!resumed.await(1, TimeUnit.SECONDS)) {
            report.put("foreground_wait_elapsed_ms", SystemClock.elapsedRealtime() - began); writeResult()
            check(SystemClock.elapsedRealtime() - began < 120_000L) { "NormalSystemLauncherRequired" }
        }
        report.put("normal_start_resumed", true)
    }
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val error = AtomicReference<Throwable?>()
        runOnMainSync { try { block() } catch (e: Throwable) { error.set(e) } }; error.get()?.let { throw it }
    }
    private fun mark(phase: String) { report.put("phase", phase); writeResult(); sendStatus(0, Bundle().apply { putString("phase", phase) }) }
    private fun writeResult() {
        if (!::directory.isInitialized) return
        val atomic = AtomicFile(File(directory, "results.json")); val stream = atomic.startWrite()
        try { stream.write((report.toString(2) + "\n").toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Throwable) { atomic.failWrite(stream); throw e }
    }
    companion object {
        private const val FIRST = "你好，很高兴见到你。"
        private const val TAIL = "我们今天一起聊聊身边有趣的事情。"
        private const val DONE_DELAY = 10_000L
        private const val EARLY_DELAY = 200L
    }
}

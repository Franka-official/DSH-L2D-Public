package com.prts.client.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.prts.client.audio.audio8.OfflineAudio8TtsEngine
import com.prts.client.net.InAudio
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlin.math.abs

/** Bounded PCM streaming. One writer owns AudioTrack; the main thread observes played frames. */
class PcmSpeechPlayer(
    private val onStart: (InAudio) -> Unit,
    private val onAudioStarted: (InAudio) -> Unit,
    private val onMouth: (Float) -> Unit,
    private val onProgress: (InAudio, Long, Long, Boolean) -> Unit,
    private val onEnded: (InAudio, Boolean) -> Unit,
    private val onIdle: () -> Unit,
    private val onError: (String) -> Unit,
    private val onPerformance: (Boolean, Long) -> Unit,
    private val playbackSpeed: () -> Float = { SpeechTempo.SPEED },
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val ticket = AtomicLong(0)
    private val lock = Any()
    private val queue = LinkedBlockingQueue<Item>()
    private val slots = Semaphore(8, true)
    private val sessions = IdentityHashMap<InAudio, Session>()
    @Volatile private var closed = false
    @Volatile private var current: Owner? = null
    private sealed class Item {
        data class Data(val session: Session, val chunk: OfflineAudio8TtsEngine.PcmChunk, val arrivedAt: Long) : Item()
        data class End(val session: Session, val success: Boolean) : Item()
        object Wake : Item()
    }
    private class Session(val message: InAudio, val generation: Long, val requestId: Long) {
        @Volatile var failed = false
    }
    private data class MouthWindow(val end: Long, val value: Float)
    private class Owner(val session: Session, val track: AudioTrack, val rate: Int,
                        val requestedPlaybackSpeed: Float, val playbackSpeed: Float, val playbackPitch: Float,
                        val buffering: PcmBufferPolicy) {
        @Volatile var written = 0L
        @Volatile var finalReceived = false
        @Volatile var started = false
        @Volatile var uiReady = false
        @Volatile var finished = false
        @Volatile var lastAdvancedAt = SystemClock.elapsedRealtime()
        @Volatile var lastPlayed = 0L
        @Volatile var playing = false
        @Volatile var pendingFrames = 0L
        var authorized = false
        @Volatile var startPolicy = "waiting"
        var resumes = 0
        val pending = ArrayDeque<OfflineAudio8TtsEngine.PcmChunk>()
        val mouth = ArrayDeque<MouthWindow>()
    }
    val isBusy: Boolean get() = synchronized(lock) { sessions.isNotEmpty() } || current != null

    private val tick = object : Runnable {
        override fun run() {
            val owner = current ?: return
            if (closed || !owner.uiReady || owner.session.generation != ticket.get() || owner.finished) return
            val played = played(owner)
            if (played > owner.lastPlayed) { owner.lastPlayed = played; owner.lastAdvancedAt = SystemClock.elapsedRealtime() }
            if (played > 0 && !owner.started) {
                owner.started = true
                trace("playback_start", owner.session, owner)
                onStart(owner.session.message); onPerformance(true, 0L); onAudioStarted(owner.session.message)
            }
            if (owner.started) {
                var energy = 0f
                synchronized(owner.mouth) {
                    while (owner.mouth.size > 1 && owner.mouth.first.end < played) owner.mouth.removeFirst()
                    energy = owner.mouth.firstOrNull()?.value ?: 0f
                }
                if (SystemClock.elapsedRealtime() - owner.lastAdvancedAt > 120) energy = 0f
                onMouth(energy)
                onProgress(owner.session.message, played * 1000L / owner.rate,
                    owner.written * 1000L / owner.rate, owner.finalReceived)
            }
            main.postDelayed(this, 35)
        }
    }

    private val writer = Thread({ writeLoop() }, "PRTS-StreamingAudio").apply { start() }

    /** Called on the TTS worker. Backpressure bounds queued PCM without blocking the UI. */
    fun accept(message: InAudio, chunk: OfflineAudio8TtsEngine.PcmChunk): Boolean {
        val arrivedAt = chunk.producedAtMs ?: SystemClock.elapsedRealtime()
        val generation = ticket.get()
        if (closed || chunk.sampleRate !in 8000..48000 || chunk.samples.any { !it.isFinite() }) return false
        val session = synchronized(lock) { sessions.getOrPut(message) { Session(message, generation, chunk.requestId) } }
        if (session.failed || session.generation != generation) return false
        while (!closed && generation == ticket.get()) {
            if (slots.tryAcquire(80, TimeUnit.MILLISECONDS)) {
                if (closed || generation != ticket.get()) { slots.release(); return false }
                queue.put(Item.Data(session, chunk, arrivedAt)); return true
            }
        }
        return false
    }

    fun finish(message: InAudio, success: Boolean) {
        val session = synchronized(lock) { sessions[message] } ?: run {
            trace("end_without_pcm", success = success)
            return
        }
        queue.offer(Item.End(session, success))
    }

    fun cancel() {
        val owner = current
        trace("cancel", owner?.session, owner, success = false)
        ticket.incrementAndGet()
        val old = mutableListOf<Item>(); queue.drainTo(old)
        old.forEach { if (it is Item.Data) slots.release() }
        synchronized(lock) { sessions.clear() }
        runCatching { owner?.track?.pause(); owner?.track?.flush() }
        queue.offer(Item.Wake)
        main.removeCallbacks(tick); onMouth(0f)
        if (owner?.started == true && !owner.finished) {
            owner.finished = true
            onPerformance(false, 0L); onEnded(owner.session.message, false)
        }
    }

    private fun played(owner: Owner): Long = runCatching {
        (owner.track.playbackHeadPosition.toLong() and 0xffffffffL).coerceAtMost(owner.written)
    }.getOrDefault(0L)

    private fun create(session: Session, rate: Int): Owner {
        val requestedSpeed = playbackSpeed()
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minimum > 0)
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, rate / 5 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        var fallback = false
        val tempo = try {
            check(track.state == AudioTrack.STATE_INITIALIZED)
            fun apply(speed: Float): PlaybackParams {
                track.playbackParams = PlaybackParams().allowDefaults().setSpeed(speed).setPitch(SpeechTempo.PITCH)
                    .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
                return track.playbackParams.also { actual ->
                    check(actual.speed.isFinite() && abs(actual.speed - speed) < 0.005f &&
                        actual.pitch.isFinite() && abs(actual.pitch - SpeechTempo.PITCH) < 0.005f)
                }
            }
            try { apply(requestedSpeed) }
            catch (_: Exception) { fallback = true; apply(1.0f) }
        } catch (error: Exception) { runCatching { track.release() }; throw error }
        val display = session.message.display_text
        val text = display?.spoken_text?.takeIf { it.isNotBlank() } ?: display?.text.orEmpty()
        return Owner(session, track, rate, requestedSpeed, tempo.speed, tempo.pitch,
            PcmBufferPolicy(rate, text, tempo.speed.toDouble())).also { owner ->
            current = owner
            trace("create", session, owner)
            trace("tempo_configured", session, owner)
            main.post { if (!closed && current === owner && session.generation == ticket.get()) {
                if (fallback) onError("这台设备暂不支持保音调慢播，已使用正常语速")
                owner.uiReady = true; main.removeCallbacks(tick); main.post(tick)
            } }
        }
    }

    private fun closeOwner(owner: Owner, success: Boolean) {
        val notify = !owner.finished
        owner.finished = true
        owner.pending.clear(); owner.pendingFrames = 0
        trace("end", owner.session, owner, success)
        runCatching { owner.track.stop() }; runCatching { owner.track.release() }
        if (current === owner) current = null
        synchronized(lock) { if (sessions[owner.session.message] === owner.session) sessions.remove(owner.session.message) }
        main.post {
            if (!closed && owner.session.generation == ticket.get()) {
                if (current == null) { main.removeCallbacks(tick); onMouth(0f) }
                if (owner.started && notify) { onPerformance(false, 0L); onEnded(owner.session.message, success) }
                onIdle()
            }
        }
    }

    private fun play(owner: Owner) {
        if (!owner.playing) { owner.track.play(); owner.playing = true }
    }

    /** Drain the soft queue only after a timing decision; AudioTrack stays sample-identical. */
    private fun flushPending(owner: Owner, force: Boolean = false) {
        if (!owner.authorized) {
            val playedFrames = played(owner)
            val buffered = owner.pendingFrames + (owner.written - playedFrames).coerceAtLeast(0)
            val standardReady = force || owner.buffering.ready(buffered, playedFrames, owner.resumes)
            val shortReady = !standardReady && owner.buffering.readyForAudio8ShortStart(buffered, owner.resumes)
            if (!standardReady && !shortReady && !owner.buffering.readyForAudio8EarlyStart(buffered, owner.resumes)) return
            if (owner.resumes == 0) {
                owner.startPolicy = when {
                    owner.buffering.finalSeen -> "final_pcm"
                    force -> "stream_end"
                    standardReady -> "adaptive_buffer"
                    shortReady -> "audio8_npu_c12_short_two_chunks"
                    else -> "audio8_cpu_c12_three_chunks"
                }
            }
            owner.authorized = true
            trace(if (owner.resumes == 0) "buffer_ready" else "buffer_resume", owner.session, owner)
            if (owner.written > played(owner)) play(owner)
        }
        while (owner.pending.isNotEmpty() && !closed && owner.session.generation == ticket.get()) {
            val chunk = owner.pending.removeFirst()
            owner.pendingFrames -= chunk.samples.size
            var offset = 0
            while (offset < chunk.samples.size && !closed && owner.session.generation == ticket.get()) {
                val count = minOf(1024, chunk.samples.size - offset)
                val written = owner.track.write(chunk.samples, offset, count, AudioTrack.WRITE_BLOCKING)
                check(written > 0)
                var square = 0.0
                for (i in offset until offset + written) square += chunk.samples[i] * chunk.samples[i]
                owner.written += written
                val rms = sqrt(square / written).toFloat()
                synchronized(owner.mouth) { owner.mouth.addLast(MouthWindow(owner.written,
                    (sqrt(rms) * 2.2f).coerceIn(0f, 1f))) }
                offset += written
                // Prime at most 85 ms before play; the configured device buffer is >=200 ms.
                if (!owner.playing && owner.written - played(owner) >= owner.rate * 80L / 1000L) play(owner)
            }
            if (chunk.final) owner.finalReceived = true
        }
        if ((force || owner.buffering.finalSeen) && owner.written > played(owner)) play(owner)
    }

    private fun writeLoop() {
        try {
            while (!closed) {
                val active = current
                val item = if (active?.playing == true && !active.finalReceived)
                    queue.poll(25, TimeUnit.MILLISECONDS) else queue.take()
                if (item is Item.Data) slots.release()
                val session = when (item) { is Item.Data -> item.session; is Item.End -> item.session
                    null -> current?.session; else -> null }
                if (session == null || session.generation != ticket.get()) {
                    current?.takeIf { it.session.generation != ticket.get() }?.let { closeOwner(it, false) }
                    continue
                }
                try {
                    when (item) {
                        is Item.Data -> {
                            if (session.failed) continue
                            val owner = current ?: create(session, item.chunk.sampleRate)
                            check(owner.session === session && owner.rate == item.chunk.sampleRate)
                            owner.pending.addLast(item.chunk); owner.pendingFrames += item.chunk.samples.size
                            owner.buffering.observe(item.chunk.samples.size, item.arrivedAt, item.chunk.final,
                                item.chunk.eligibleForAudio8EarlyStart, item.chunk.eligibleForAudio8ShortStart)
                            flushPending(owner)
                        }
                        is Item.End -> {
                            val owner = current?.takeIf { it.session === session }
                            if (owner != null) {
                                if (item.success) flushPending(owner, force = true)
                                trace("drain_start", session, owner, item.success)
                                if (!item.success) { runCatching { owner.track.pause(); owner.track.flush() } }
                                var last = played(owner); var advancedAt = SystemClock.elapsedRealtime()
                                while (item.success && !closed && session.generation == ticket.get() && played(owner) < owner.written) {
                                    val position = played(owner)
                                    if (position != last) { last = position; advancedAt = SystemClock.elapsedRealtime() }
                                    check(SystemClock.elapsedRealtime() - advancedAt < 3000) { "AudioTrackDrainStalled" }
                                    Thread.sleep(12)
                                }
                                trace("drain_complete", session, owner, item.success && owner.finalReceived)
                                closeOwner(owner, item.success && owner.finalReceived)
                            } else {
                                synchronized(lock) { if (sessions[session.message] === session) sessions.remove(session.message) }
                                main.post { if (!closed && session.generation == ticket.get()) onIdle() }
                            }
                        }
                        null -> {
                            val owner = current?.takeIf { it.session === session } ?: continue
                            if (owner.playing && !owner.buffering.finalSeen &&
                                owner.written - played(owner) <= owner.rate * 120L / 1000L) {
                                owner.track.pause(); owner.playing = false; owner.authorized = false; owner.resumes++
                                trace("buffer_pause", session, owner)
                            }
                        }
                        else -> Unit
                    }
                } catch (error: Exception) {
                    session.failed = true
                    val owner = current?.takeIf { it.session === session }
                    trace("error", session, owner, false,
                        if (error.message == "AudioTrackDrainStalled") "drain_stalled"
                        else "${if (item is Item.End) "end" else "data"}_${error.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"), "_")}")
                    owner?.let { closeOwner(it, false) }
                    synchronized(lock) {
                        if (item is Item.End) {
                            // This End is already consumed: retaining its tombstone
                            // would keep isBusy true forever, with no later End to clear it.
                            if (sessions[session.message] === session) sessions.remove(session.message)
                        } else if (session.generation == ticket.get()) {
                            // Data failure still needs the producer's End; refuse later chunks.
                            sessions[session.message] = session
                        }
                    }
                    if (item is Item.End && owner == null) main.post {
                        if (!closed && session.generation == ticket.get()) onIdle()
                    }
                    main.post { if (!closed && session.generation == ticket.get()) onError("这段语音播放中断了") }
                }
            }
        } catch (_: InterruptedException) {
            // Only this owned playback thread is interrupted on close.
        } finally { current?.let { closeOwner(it, false) } }
    }
    /** Bounded per-session lifecycle records; never log text, reply IDs or PCM. */
    private fun trace(event: String, session: Session? = null, owner: Owner? = null,
                      success: Boolean? = null, reason: String? = null) {
        Log.i("PRTS-PcmAudio", "{\"event\":\"pcm_$event\",\"at_ms\":${SystemClock.elapsedRealtime()}," +
            "\"request_id\":${session?.requestId ?: -1},\"generation\":${session?.generation ?: ticket.get()}," +
            "\"written_frames\":${owner?.written ?: 0},\"played_frames\":${owner?.let(::played) ?: 0}," +
            "\"started\":${owner?.started ?: false},\"final_received\":${owner?.finalReceived ?: false}," +
            "\"soft_buffer_frames\":${owner?.pendingFrames ?: 0},\"buffer_resumes\":${owner?.resumes ?: 0}," +
            "\"raw_underruns\":${owner?.let { runCatching { it.track.underrunCount }.getOrDefault(-1) } ?: -1}," +
            "\"production_rtf_observed\":${owner?.buffering?.productionRtf ?: 0.0}," +
            "\"rtf_observation_intervals\":${owner?.buffering?.observedIntervals ?: 0}," +
            "\"buffer_target_ms\":${owner?.let { it.buffering.targetMs(played(it), it.resumes) } ?: 0}," +
            "\"estimated_audio_ms\":${owner?.buffering?.estimatedAudioMs ?: 0}," +
            "\"start_policy\":\"${owner?.startPolicy ?: "none"}\"," +
            "\"requested_playback_speed\":${owner?.requestedPlaybackSpeed ?: 0f},\"playback_speed\":${owner?.playbackSpeed ?: 0f}," +
            "\"playback_pitch\":${owner?.playbackPitch ?: 0f}," +
            "\"success\":${success?.toString() ?: "null"},\"reason\":${reason?.let { "\"$it\"" } ?: "null"}}")
    }
    override fun close() { closed = true; cancel(); writer.interrupt() }
}

package com.prts.client.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

/** Capture locally, so a cancelled utterance never remains in the server's microphone buffer. */
class MicRecorder(private val onFinished: (FloatArray) -> Unit, private val onError: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    @Volatile private var recording = false
    @Volatile private var keep = true
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    val isRecording get() = recording
    val isBusy get() = worker != null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (worker != null) return false
        val ticket = generation.incrementAndGet()
        try {
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0)
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minimum.coerceAtLeast(6400))
            recorder = audio
            require(audio.state == AudioRecord.STATE_INITIALIZED)
            audio.startRecording()
            require(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            keep = true
            recording = true
            worker = Thread({
                val parts = ArrayList<ShortArray>()
                val buffer = ShortArray(1600)
                var total = 0
                var failure = false
                try {
                    while (recording && total < RATE * 60) {
                        val count = audio.read(buffer, 0, buffer.size)
                        if (count < 0) { if (recording) failure = true; break }
                        if (count > 0) { parts.add(buffer.copyOf(count)); total += count }
                    }
                } catch (_: Exception) { if (recording) failure = true }
                finally {
                    recording = false
                    try { audio.stop() } catch (_: Exception) { }
                    audio.release()
                    recorder = null
                }
                val samples = if (ticket == generation.get() && keep) FloatArray(total) else FloatArray(0)
                if (samples.isNotEmpty()) {
                    var offset = 0
                    for (part in parts) for (sample in part) samples[offset++] = sample / 32768f
                }
                handler.post {
                    worker = null
                    if (ticket != generation.get() || !keep) return@post
                    when {
                        failure -> onError("没录到声音，请再开启麦克风试一次")
                        samples.size < RATE / 5 -> onError("这段太短了，再说一遍吧")
                        else -> onFinished(samples)
                    }
                }
            }, "PRTS-Microphone").apply { start() }
            return true
        } catch (_: Exception) {
            recording = false
            try { recorder?.release() } catch (_: Exception) { }
            recorder = null
            onError("麦克风暂时打不开，请检查权限或其他录音应用")
            return false
        }
    }

    fun stop(send: Boolean) {
        keep = send
        if (!send) generation.incrementAndGet()
        recording = false
        try { recorder?.stop() } catch (_: Exception) { }
    }

    fun close() { stop(false); handler.removeCallbacksAndMessages(null) }
    companion object { const val RATE = 16000 }
}

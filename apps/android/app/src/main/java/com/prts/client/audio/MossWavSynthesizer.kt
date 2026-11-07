package com.prts.client.audio

import android.content.Context
import com.prts.client.audio.moss.MossModelStore
import com.prts.client.audio.moss.MossNanoEngine
import com.prts.client.audio.moss.MossVoiceLibrary
import com.prts.client.net.VoiceProfile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** Uses the existing complete-WAV playback path; no Audio8 or network fallback. */
internal class MossWavSynthesizer(context: Context, profile: VoiceProfile, character: String? = null) : AutoCloseable {
    private val app = context.applicationContext
    private val root = MossModelStore.root(app)
    private val voice = MossVoiceLibrary.resolve(app, character, profile.voiceId, profile.language)
    private var engine: MossNanoEngine? = null
    val available: Boolean get() = MossNanoEngine.available && MossModelStore.ready(app)

    fun synthesize(text: String, output: File, cancelled: () -> Boolean, onFirstPcm: (() -> Unit)? = null) {
        check(available) { "MOSS 模型未就绪" }
        if (cancelled()) throw CancellationException()
        val runtime = engine ?: MossNanoEngine(root).also { engine = it }
        val result = runtime.synthesize(text, voice.voiceId, privateVoice = voice.file, cancelled = cancelled)
        check(result.finishReason == "eos" && result.pcm.isNotEmpty()) { "MOSS 未完整结束" }
        require(result.sampleRate == 48_000 && result.pcm.size <= 48_000 * 13)
        onFirstPcm?.invoke()
        val byteCount = result.pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + byteCount).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(result.sampleRate).putInt(result.sampleRate * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(byteCount)
        output.outputStream().buffered().use { stream ->
            stream.write(header.array())
            val block = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in result.pcm) {
                require(sample.isFinite())
                if (!block.hasRemaining()) {
                    if (cancelled()) throw CancellationException()
                    stream.write(block.array()); block.clear()
                }
                block.putShort((sample.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            }
            stream.write(block.array(), 0, block.position())
        }
    }

    override fun close() { engine?.close(); engine = null }
}

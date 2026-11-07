package com.prts.client.localdsh

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Canonical microphone input WAV only. Never modifies the TTS audio path. */
internal object LocalDshPcmWav {
    const val RATE = 16000
    const val MAX_SAMPLES = RATE * 60
    fun encode(samples: FloatArray): ByteArray {
        require(samples.isNotEmpty() && samples.size <= MAX_SAMPLES) { "MicDurationOutsideLimit" }
        require(samples.all { it.isFinite() }) { "NonfiniteMicSample" }
        val bytes = samples.size * 2
        val out = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + bytes)
        out.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        out.putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        out.put("data".toByteArray(Charsets.US_ASCII)).putInt(bytes)
        // MicRecorder starts as signed PCM16 / 32768; this exactly restores that grid.
        samples.forEach { out.putShort((it.coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-32768, 32767).toShort()) }
        return out.array()
    }
}

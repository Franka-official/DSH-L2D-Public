package com.prts.client.audio

import android.content.Context

/** Playback only. The TTS input, sampling parameters and generated PCM remain unchanged. */
internal object SpeechTempo {
    const val SPEED = 0.65f
    const val FAST_SPEED = 1.0f
    const val PITCH = 1.0f
    const val ELEGANT = "elegant"
    const val FAST = "fast"
    private const val KEY = "speech_playback_mode"

    fun preset(context: Context): String =
        if (context.getSharedPreferences("prts", Context.MODE_PRIVATE).getString(KEY, ELEGANT) == FAST) FAST else ELEGANT

    fun speed(context: Context): Float = if (preset(context) == FAST) FAST_SPEED else SPEED

    fun savePreset(context: Context, preset: String) {
        require(preset == ELEGANT || preset == FAST)
        context.getSharedPreferences("prts", Context.MODE_PRIVATE).edit().putString(KEY, preset).apply()
    }
}

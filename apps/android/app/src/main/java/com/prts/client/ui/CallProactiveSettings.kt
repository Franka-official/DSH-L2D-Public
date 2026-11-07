package com.prts.client.ui

import android.content.Context

internal object CallProactiveSettings {
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 600
    private const val DEFAULT_SECONDS = 30
    private const val ENABLED = "proactive_call_speech"
    private const val WAIT_SECONDS = "proactive_call_wait_seconds"

    private fun prefs(context: Context) = context.getSharedPreferences("prts", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, true)

    fun waitSeconds(context: Context): Int = prefs(context).getInt(WAIT_SECONDS, DEFAULT_SECONDS)
        .coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun save(context: Context, enabled: Boolean, seconds: Int) {
        require(seconds in MIN_SECONDS..MAX_SECONDS)
        prefs(context).edit().putBoolean(ENABLED, enabled).putInt(WAIT_SECONDS, seconds).apply()
    }

    fun saveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply()
    }
}

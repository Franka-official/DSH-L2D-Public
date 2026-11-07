package com.prts.client.ui

import android.content.Context

object CharacterPresentationStore {
    private const val FILE = "character_presentation"
    fun load(context: Context, character: String): PresentationTransform {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return PresentationTransform(prefs.getFloat("$character:scale", 1f),
            prefs.getFloat("$character:x", 0f), prefs.getFloat("$character:y", 0f)).sanitized()
    }
    fun save(context: Context, character: String, value: PresentationTransform) {
        val safe = value.sanitized()
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat("$character:scale", safe.scale).putFloat("$character:x", safe.offsetX)
            .putFloat("$character:y", safe.offsetY).apply()
    }
}

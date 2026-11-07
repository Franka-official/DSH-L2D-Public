package com.prts.client.audio.cosy

import android.content.Context
import com.prts.client.net.ConnectionSettings
import com.prts.client.net.VoiceProfile

/** One speech engine; this setting chooses reply language and its fixed reference.
 * Connection/provider keys, endpoints, prompts and profiles are never modified.
 */
object CosyVoiceSettings {
    private const val FILE = "cosyvoice3_language"
    private const val LANGUAGE = "reply_language"

    fun loadReplyLanguage(context: Context): String {
        val saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LANGUAGE, null)
        if (saved in CosyVoiceLanguagePolicy.supported) return saved!!
        // Read-only migration: an existing JA reference means JA unless the user
        // has already chosen the new explicit setting. No provider is changed.
        val legacy = ConnectionSettings.loadVoiceProfile(context)
        return if (legacy.language == "en") "en" else if (legacy.language == "ja" || legacy.voiceId.endsWith("_ja")) "ja" else "zh"
    }

    fun saveReplyLanguage(context: Context, language: String) {
        require(language in CosyVoiceLanguagePolicy.supported) { "回复语言仅支持中文、日文或英文" }
        check(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(LANGUAGE, language).commit()) { "回复语言没有保存成功，请重试" }
    }

    fun voiceProfile(context: Context, character: String = "whale"): VoiceProfile {
        val language = loadReplyLanguage(context)
        return VoiceProfile(modeId = "cosyvoice3_phone",
            voiceId = CharacterVoicePolicy.defaultVoiceId(character, language), language = language)
    }
}

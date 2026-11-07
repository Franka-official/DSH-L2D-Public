package com.prts.client.audio

import android.content.Context
import com.prts.client.audio.audio8.Audio8VoiceLibrary
import com.prts.client.net.ConnectionSettings
import com.prts.client.net.VoiceProfile

/** Local speech selection and legacy character voice identifiers.
 * Existing reply-language choices are read without modifying saved providers or credentials.
 */
object LocalVoiceSettings {
    const val MODE_ID = "audio8_phone"
    const val WHALE_VOICE = "whale_girl"
    const val ELLEN_VOICE = "ellen"
    val supportedLanguages = setOf("zh", "ja", "en")
    private const val FILE = "local_voice_language"
    private const val LANGUAGE = "reply_language"
    private val acceptedVoices = setOf("whale", "whale_girl", "ellen", "ellen_zh", "ellen_ja")

    fun loadReplyLanguage(context: Context): String {
        val saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LANGUAGE, null)
        if (saved in supportedLanguages) return saved!!
        val previous = context.getSharedPreferences("cosyvoice3_language", Context.MODE_PRIVATE)
            .getString(LANGUAGE, null)
        if (previous in supportedLanguages) return previous!!
        val legacy = ConnectionSettings.loadVoiceProfile(context)
        return when {
            legacy.language in supportedLanguages -> legacy.language
            legacy.voiceId.endsWith("_ja") -> "ja"
            else -> "zh"
        }
    }

    fun saveReplyLanguage(context: Context, language: String) {
        require(language in supportedLanguages) { "回复语言仅支持中文、日文或英文" }
        check(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(LANGUAGE, language).commit()) { "回复语言没有保存成功，请重试" }
    }

    fun voiceProfile(context: Context, character: String = "whale"): VoiceProfile =
        VoiceProfile(modeId = MODE_ID,
            voiceId = Audio8VoiceLibrary.resolve(context, character),
            language = loadReplyLanguage(context))

    fun outputLanguage(profile: VoiceProfile): String? =
        profile.language.takeIf { it in supportedLanguages &&
            (profile.voiceId in acceptedVoices || Audio8VoiceLibrary.isCustomId(profile.voiceId)) }
}

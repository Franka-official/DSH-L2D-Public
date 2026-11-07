package com.prts.client.audio.cosy

/** Output language and installed reference voice are separate; no Ellen EN assets exist. */
object CosyVoiceLanguagePolicy {
    val supported = setOf("zh", "ja", "en")
    val supportedVoices = setOf("ellen", "whale", "ellen_zh", "ellen_ja", "whale_zh", "whale_ja", "whale_girl")
    fun referenceLanguage(outputLanguage: String, requestedVoiceId: String? = null,
                          declaredReferenceLanguage: String? = null): String {
        require(outputLanguage in supported) { "Supported offline languages: zh, ja, en" }
        require(requestedVoiceId == null || requestedVoiceId in supportedVoices) { "Unknown offline voice" }
        if (declaredReferenceLanguage in supported) return declaredReferenceLanguage!!
        if (requestedVoiceId == "whale_girl") return "unknown" // Read its actual profile language at runtime.
        return if (outputLanguage == "ja") "ja" else "zh"
    }
    fun referenceVoiceId(outputLanguage: String, requestedVoiceId: String? = null): String =
        if (requestedVoiceId == "whale_girl") {
            referenceLanguage(outputLanguage, requestedVoiceId)
            "whale_girl"
        } else "ellen_${referenceLanguage(outputLanguage, requestedVoiceId)}"
    fun outputLanguage(profileLanguage: String, voiceId: String): String? {
        if (voiceId !in supportedVoices) return null
        if (profileLanguage in supported) return profileLanguage
        return when (voiceId) { "ellen_zh", "whale_zh", "whale_girl" -> "zh"; "ellen_ja", "whale_ja" -> "ja"; else -> null }
    }
}

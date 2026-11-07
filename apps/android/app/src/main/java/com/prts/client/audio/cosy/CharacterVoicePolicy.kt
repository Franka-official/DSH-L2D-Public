package com.prts.client.audio.cosy

/** Character identity chooses the reference; output language remains independent. */
object CharacterVoicePolicy {
    const val WHALE_GIRL = "whale_girl"

    fun defaultVoiceId(character: String, outputLanguage: String): String {
        require(outputLanguage in CosyVoiceLanguagePolicy.supported)
        return if (character == "whale") WHALE_GIRL
            else CosyVoiceLanguagePolicy.referenceVoiceId(outputLanguage)
    }
}

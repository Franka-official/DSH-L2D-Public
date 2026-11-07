package com.prts.client.audio

data class SpeechCaption(val text: String, val transcript: String? = null,
    val translation: String? = null, val translationLanguage: String? = null, val spokenText: String? = null)
data class SpeechTextPlan(val spokenText: String?, val caption: SpeechCaption,
    val bilingual: Boolean, val missingSpeechNotice: String? = null)

/** Typed field selection, never translation or language guessing. Foreign speech never falls back to Chinese. */
object BilingualSpeechPolicy {
    fun select(language: String?, text: String?, transcript: String?, translation: String?,
               translationLanguage: String?, spokenText: String?): SpeechTextPlan {
        fun clean(value: String?) = LocalSpeechText.forSpeech(value.orEmpty()).trim()
        val body = clean(text)
        val oldTranscript = transcript?.let(::clean)
        val visible = body.ifBlank { oldTranscript.orEmpty() }
        if (language == "zh") return SpeechTextPlan(visible.takeIf(String::isNotBlank),
            SpeechCaption(visible, oldTranscript), bilingual = false)
        val target = clean(translation).takeIf(String::isNotBlank)
        val declared = translationLanguage?.trim()?.lowercase()
        val spoken = clean(spokenText).takeIf(String::isNotBlank)
        val caption = SpeechCaption(visible, oldTranscript, target, declared, spoken)
        if (language !in setOf("ja", "en")) return SpeechTextPlan(null, caption, true,
            "所选语音语言不受支持，已保留文字")
        val label = if (language == "ja") "日文" else "英文"
        if (body.isBlank() || target == null || declared != language || spoken == null)
            return SpeechTextPlan(null, caption, true, "这轮没有完整的${label}语音正文，已保留文字")
        return SpeechTextPlan(spoken, caption, bilingual = true)
    }

    fun captionForSlice(plan: SpeechTextPlan, part: String, index: Int): SpeechCaption {
        require(index >= 0)
        if (plan.bilingual) return if (index == 0) plan.caption else SpeechCaption("")
        return SpeechCaption(part, plan.caption.transcript?.let { part })
    }
}

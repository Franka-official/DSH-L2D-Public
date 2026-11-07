package com.prts.client.ui

data class CallOpeningLine(val text: String, val translation: String? = null,
    val translationLanguage: String? = null, val spokenText: String? = null)

/** A local welcome belongs to this call, never to a websocket connection. */
class CallOpeningPolicy(consumed: Boolean = false) {
    var consumed = consumed
        private set

    fun consume() { consumed = true }

    fun claim(character: String, language: String, foreground: Boolean,
              providerReady: Boolean, localVoiceReady: Boolean, userBusy: Boolean): CallOpeningLine? {
        if (consumed || character != "ellen" || !foreground || !providerReady || !localVoiceReady) return null
        // A real interaction wins over the welcome, including one that began
        // while the initial connection was still being prepared.
        consumed = true
        if (userBusy) return null
        val chinese = "隔壁那条鲸鱼又吃白饭去了，我来顶班。……好困，想回去睡觉。"
        val translated = when (language) {
            "ja" -> "隣のクジラ、またサボりに行ったから、私が代わり。……眠い。帰って寝たい。"
            "en" -> "The whale next door is freeloading again, so I'm covering her shift. ...So sleepy. I want to go home and sleep."
            else -> null
        }
        return CallOpeningLine(chinese, translated,
            language.takeIf { translated != null }, translated)
    }
}

package com.prts.client.audio

/** Sentence-level pipeline policy, not native token/PCM streaming. */
internal object LocalSpeechText {
    private val hidden = Regex("<(think|analysis|tool_call|tool_result|function_call|function_result)\\b[^>]*>.*?(?:</\\1\\s*>|$)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val controls = Regex("\\[(?:neutral|happy|sad|angry|surprised|thinking|shy|gesture:[a-z][a-z_-]{0,30})]", RegexOption.IGNORE_CASE)

    /** Backend typed tool filtering is primary; retain a bounded local guard. */
    fun forSpeech(text: String): String = controls.replace(hidden.replace(text, ""), "")

    /** Short first CN clause, then longer chunks to avoid one native run per comma.
     * JA keeps the original 60-code-point ceiling so new small cuts do not split
     * Kanji compounds before offline pronunciation. No surrogate pair is cut.
     */
    fun split(text: String, maximum: Int = 60, firstMaximum: Int = 28, eagerFirst: Boolean = true): List<String> {
        require(maximum > 0 && firstMaximum > 0)
        val result = mutableListOf<String>()
        val buffer = StringBuilder()
        var count = 0
        var offset = 0
        fun flush() {
            buffer.toString().trim().takeIf { it.isNotEmpty() }?.let(result::add)
            buffer.setLength(0); count = 0
        }
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            buffer.appendCodePoint(cp); count++
            offset += Character.charCount(cp)
            val first = eagerFirst && result.isEmpty()
            val limit = if (first) minOf(firstMaximum, maximum) else maximum
            val strong = cp in listOf(0x3002, 0xff01, 0xff1f, 0xff1b, 33, 63, 59, 10) ||
                (cp == 46 && !(offset < text.length && text[offset].isDigit() && buffer.length > 1 && buffer[buffer.length - 2].isDigit()))
            val firstPause = first && count >= 8 && cp in listOf(0xff0c, 0x3001, 44)
            val next = if (offset < text.length) text.codePointAt(offset) else -1
            val insideAsciiWord = cp in 65..90 || cp in 97..122
            val nextAsciiWord = next in 65..90 || next in 97..122
            val reached = count >= limit && !(insideAsciiWord && nextAsciiWord && count < maximum)
            if (strong || firstPause || count >= maximum || reached) flush()
        }
        flush()
        return result
    }
}

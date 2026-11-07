package com.prts.client.localdsh

/** Only the first plain Chinese sentence may leave before done. All returned spans
 * are unchanged UTF-16 substrings; later text is returned once by finish(). A text
 * stream cannot announce future formatting: guards apply to the prefix seen so far.
 */
internal class LocalReplySpeechSegments(language: String) {
    private val pending = StringBuilder()
    private var eager = language == "zh"
    private var earlySent = false
    private var ended = false
    var hasSegments = false
        private set

    fun append(delta: String): String? {
        if (ended) return null
        pending.append(delta)
        if (!eager || earlySent) return null
        val end = firstBoundary() ?: return null
        earlySent = true
        hasSegments = true
        return pending.substring(0, end).also { pending.delete(0, end) }
    }

    fun finish(success: Boolean): String? {
        if (ended) return null
        ended = true
        val tail = if (success && pending.isNotEmpty()) pending.toString() else null
        pending.setLength(0)
        if (tail != null) hasSegments = true
        return tail
    }

    fun cancel() { ended = true; pending.setLength(0) }

    private fun firstBoundary(): Int? {
        // Lists, markup, quoted/bracketed material and bilingual labels retain the
        // previous whole-reply path. Do not reinterpret them as spoken Chinese.
        if (listPrefix.containsMatchIn(pending)) { eager = false; return null }
        var offset = 0
        var hasHan = false
        var boundary: Int? = null
        while (offset < pending.length) {
            val ch = pending[offset]
            if (Character.isHighSurrogate(ch)) {
                if (offset + 1 == pending.length) return null // split emoji delta
                if (!Character.isLowSurrogate(pending[offset + 1])) { eager = false; return null }
            } else if (Character.isLowSurrogate(ch)) { eager = false; return null }
            val cp = Character.codePointAt(pending, offset)
            val script = Character.UnicodeScript.of(cp)
            if (cp in guarded || script == Character.UnicodeScript.HIRAGANA ||
                script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) {
                eager = false
                return null
            }
            if (script == Character.UnicodeScript.HAN) hasHan = true
            val next = offset + Character.charCount(cp)
            if (cp in endings && hasHan && (boundary == null || boundary == offset)) boundary = next
            offset = next
        }
        return boundary
    }

    private companion object {
        // ASCII '.' deliberately is not a boundary: decimals, versions and URLs.
        val endings = setOf('。', '！', '？', '!', '?').map { it.code }.toSet()
        val guarded = "\n\r\t`<>{}[]#|*_~\\:：\"'“”‘’「」『』（）()【】〔〕《》〈〉".map { it.code }.toSet()
        val listPrefix = Regex("^\\s*(?:[-+•]|[0-9]+[.)、])(?:\\s|$)")
    }
}

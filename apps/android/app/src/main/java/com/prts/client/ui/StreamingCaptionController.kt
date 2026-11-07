package com.prts.client.ui

import java.util.IdentityHashMap

/** Playback-paced typography. Progress is an estimate, not word-level forced alignment. */
class StreamingCaptionController(private val update: (Frame) -> Unit) {
    data class Frame(val replyKey: String, val who: String, val text: String,
                     val translation: String?, val language: String?)
    private data class Reply(val key: String, val who: String, val segments: MutableList<Segment> = mutableListOf())
    private data class Segment(val reply: Reply, val text: String,
                               val translation: String?, val language: String?,
                               var visible: Int = 0, var translated: Int = 0,
                               var target: Int = 0, var translatedTarget: Int = 0)
    private val replies = LinkedHashMap<String, Reply>()
    private val active = IdentityHashMap<Any, Segment>()

    fun begin(source: Any, replyKey: String, who: String, text: String,
              translation: String?, language: String?) {
        if (active.containsKey(source)) return
        val reply = replies.getOrPut(replyKey) { Reply(replyKey, who) }
        if (replies.size > 64) replies.remove(replies.keys.first())
        val item = Segment(reply, text, translation?.takeIf { it.isNotBlank() }, language)
        reply.segments.add(item); active[source] = item
        // No bubble or characters before actual playback progress.
    }

    fun progress(source: Any, playedMs: Long, totalMs: Long, totalKnown: Boolean) {
        val item = active[source] ?: return
        if (playedMs <= 0) return
        val spoken = if (item.language in setOf("ja", "en")) item.translation ?: item.text else item.text
        val estimate = (codePoints(spoken) * when (item.language) { "en" -> 65L; "ja" -> 155L; else -> 210L }).coerceAtLeast(650L)
        val duration = if (totalKnown && totalMs > 0) totalMs else estimate
        val fraction = (playedMs.toDouble() / duration.coerceAtLeast(1)).coerceIn(0.0, if (totalKnown) 1.0 else .92)
        val visible = (codePoints(item.text) * fraction).toInt().coerceAtLeast(1)
        val translated = (codePoints(item.translation.orEmpty()) * fraction).toInt()
        val next = visible.coerceAtMost(codePoints(item.text))
        val nextTranslation = translated.coerceAtMost(codePoints(item.translation.orEmpty()))
        item.target = maxOf(item.target, next); item.translatedTarget = maxOf(item.translatedTarget, nextTranslation)
    }

    fun finish(source: Any, completed: Boolean) {
        val item = active.remove(source) ?: return
        if (completed) {
            item.target = codePoints(item.text); item.translatedTarget = codePoints(item.translation.orEmpty())
        } else {
            item.target = item.visible; item.translatedTarget = item.translated
        }
    }

    /** An interrupted answer keeps only the characters already shown. */
    fun cancel() {
        active.clear()
        replies.values.forEach { reply -> reply.segments.forEach {
            it.target = it.visible; it.translatedTarget = it.translated
        } }
    }

    val hasPending: Boolean get() = replies.values.any { reply -> reply.segments.any {
        it.visible < it.target || it.translated < it.translatedTarget
    } }

    /** One Unicode code point per animation step, even if a late audio update advances far. */
    fun advanceOne() {
        replies.values.forEach { reply ->
            var changed = false
            reply.segments.firstOrNull { it.visible < it.target }?.let { it.visible++; changed = true }
            reply.segments.firstOrNull { it.translated < it.translatedTarget }?.let { it.translated++; changed = true }
            if (changed) emit(reply)
        }
    }

    private fun emit(reply: Reply) {
        val primary = StringBuilder(); val secondary = StringBuilder(); var language: String? = null
        reply.segments.forEach { item ->
            primary.append(prefix(item.text, item.visible))
            secondary.append(prefix(item.translation.orEmpty(), item.translated))
            if (!item.translation.isNullOrEmpty()) language = item.language
        }
        if (primary.isEmpty() && secondary.isEmpty()) return
        update(Frame(reply.key, reply.who, primary.toString(), secondary.toString().takeIf { it.isNotEmpty() }, language))
    }
    private fun codePoints(value: String) = value.codePointCount(0, value.length)
    private fun prefix(value: String, count: Int): String = value.substring(0,
        value.offsetByCodePoints(0, count.coerceIn(0, codePoints(value))))
}

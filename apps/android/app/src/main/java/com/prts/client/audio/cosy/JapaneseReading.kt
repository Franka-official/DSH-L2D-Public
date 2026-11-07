package com.prts.client.audio.cosy

import com.atilika.kuromoji.ipadic.Tokenizer
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** Offline Japanese text -> katakana pronunciation for CosyVoice3.
 * Dependency: com.atilika.kuromoji:kuromoji-ipadic:0.9.0 (+ kuromoji-core:0.9.0).
 * First dictionary load is substantial: call on the synthesis worker, never UI.
 * Unknown Kanji is reported instead of being silently given an invented reading.
 */
object JapaneseReading {
    private val tokenizer: Tokenizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Tokenizer() }
    private val customReadings = ConcurrentHashMap<String, String>()
    private val kanji = Regex("[\\u3400-\\u9fff\\uf900-\\ufaff]")

    /** Optional caller-supplied readings for character names and other proper nouns. */
    fun setCustomReadings(readings: Map<String, String>) {
        require(readings.keys.none(String::isBlank)) { "日文自定义读音的词不能为空" }
        require(readings.values.all { it.isNotBlank() && !kanji.containsMatchIn(it) }) { "请用假名填写日文读音" }
        customReadings.clear()
        readings.forEach { (word, reading) -> customReadings[word] = hiraganaToKatakana(reading) }
    }

    @JvmStatic
    fun toKatakana(text: String): String {
        var normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        customReadings.entries.sortedByDescending { it.key.length }.forEach { (word, reading) ->
            normalized = normalized.replace(word, reading)
        }
        if (normalized.isBlank()) return normalized
        val unresolved = linkedSetOf<String>()
        val result = synchronized(tokenizer) {
            buildString {
                tokenizer.tokenize(normalized).forEach { token ->
                    val pronunciation = token.pronunciation?.takeUnless { it.isBlank() || it == "*" }
                    val reading = pronunciation ?: token.reading?.takeUnless { it.isBlank() || it == "*" }
                    val surface = token.surface
                    val spoken = hiraganaToKatakana(reading ?: surface)
                    if (kanji.containsMatchIn(spoken)) unresolved.add(surface)
                    append(spoken)
                }
            }
        }
        require(unresolved.isEmpty()) { "日文词语缺少读音：${unresolved.joinToString("、")}，请添加自定义假名读音" }
        return result
    }

    private fun hiraganaToKatakana(text: String): String = buildString(text.length) {
        text.forEach { c -> append(if (c in '\u3041'..'\u3096') (c.code + 0x60).toChar() else c) }
    }
}

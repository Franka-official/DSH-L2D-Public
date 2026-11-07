package com.prts.client.localdsh

/** Standalone JVM assertions: compile with the production helper, no Android or
 * test framework dependency. This checks text/lifecycle rules, not device audio.
 */
object LocalReplySpeechSegmentsTest {
    private fun expect(value: Boolean, message: String) { check(value) { message } }
    private fun verify(text: String, parts: List<String>, language: String = "zh", eager: Boolean = true) {
        val segments = LocalReplySpeechSegments(language)
        val output = mutableListOf<String>()
        parts.forEach { segments.append(it)?.let(output::add) }
        val earlyCount = output.size
        segments.finish(true)?.let(output::add)
        expect(output.joinToString("") == text, "span loss/duplication for $parts")
        expect(earlyCount <= 1 && output.size <= 2, "more than first + tail")
        expect(eager || earlyCount == 0, "guarded input emitted early")
        expect(segments.finish(true) == null && segments.append("迟到。") == null, "terminal emitted again")
        output.forEach { span ->
            var at = 0
            while (at < span.length) {
                val c = span[at]
                if (Character.isHighSurrogate(c)) {
                    expect(at + 1 < span.length && Character.isLowSurrogate(span[at + 1]), "split surrogate")
                    at += 2
                } else { expect(!Character.isLowSurrogate(c), "orphan low surrogate"); at++ }
            }
        }
    }
    private fun everyBoundary(text: String, language: String = "zh", eager: Boolean = true) {
        verify(text, listOf(text), language, eager)
        verify(text, text.map { it.toString() }, language, eager)
        for (a in 0..text.length) for (b in a..text.length) {
            verify(text, listOf(text.substring(0, a), text.substring(a, b), text.substring(b)), language, eager)
        }
    }
    @JvmStatic fun main(args: Array<String>) {
        everyBoundary(" 你好，今天天气不错。我们可以慢慢聊！尾句还没结束  ")
        everyBoundary("价格是3.14元，版本v1.2可用。下一句。")
        everyBoundary("你好🙂，欢迎回来！我们继续🚀。")
        everyBoundary("没有句号，只有逗号，仍等完成", eager = false)
        everyBoundary("。！？", eager = false)
        for (text in listOf("“你好！”她说。", "他说：你好。再见。", "[中文]你好。[English]Hello.",
            "{\"text\":\"你好。\"}", "```text\n你好。\n```", "1. 你好。下一项。", "你好\n再见。",
            "こんにちは。中文。", "안녕하세요。中文。")) everyBoundary(text, eager = false)
        everyBoundary("你好。Hello, world!", language = "en", eager = false)
        everyBoundary("中文。日文译文。", language = "ja", eager = false)

        val first = LocalReplySpeechSegments("zh")
        expect(first.append("你好，今天不错") == null, "comma/length cut")
        expect(first.append("。后文") == "你好，今天不错。", "first natural sentence not early")
        expect(first.append("继续。再一句。") == null, "second eager segment")
        expect(first.finish(true) == "后文继续。再一句。", "done repeated first sentence")
        expect(first.hasSegments, "submitted speech flag")

        val pendingCancel = LocalReplySpeechSegments("zh")
        pendingCancel.append("尚未结束")
        pendingCancel.cancel()
        expect(pendingCancel.finish(true) == null && pendingCancel.append("迟到。") == null, "cancel tail/late delta")
        val partialCancel = LocalReplySpeechSegments("zh")
        expect(partialCancel.append("你好。未说尾部") == "你好。", "cancel setup")
        partialCancel.cancel()
        expect(partialCancel.finish(true) == null, "cancelled partial tail escaped")
        val failed = LocalReplySpeechSegments("zh")
        failed.append("你好。未说尾部")
        expect(failed.finish(false) == null && failed.finish(true) == null, "failure flushed tail")
        val futureFormat = LocalReplySpeechSegments("zh")
        expect(futureFormat.append("先说一句。") == "先说一句。", "plain prefix")
        expect(futureFormat.append("\n```正文后才出现的代码。```") == null, "late format was split")
        expect(futureFormat.finish(true) == "\n```正文后才出现的代码。```", "late-format tail changed")
        println("LocalReplySpeechSegments: all boundary/lifecycle assertions passed")
    }
}

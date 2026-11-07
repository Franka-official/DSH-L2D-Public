package com.prts.client.audio

import java.net.URI

/** Provider settings only. Credentials are a separate, redacted per-call snapshot. */
data class SpeechApiOptions(val endpoint: String = "", val model: String = "tts-1", val voice: String = "alloy") {
    fun normalized(): SpeechApiOptions = copy(endpoint = SpeechApiProtocol.endpoint(endpoint),
        model = model.trim().ifEmpty { "tts-1" }, voice = voice.trim().ifEmpty { "alloy" }).also {
        require(it.model.length <= 160 && it.voice.length <= 160 &&
            (it.model + it.voice).none { c -> c == '\r' || c == '\n' }) { "语音模型或音色名称不正确" }
    }
}

class SpeechApiCredentials(val options: SpeechApiOptions, internal val apiKey: String) {
    override fun toString() = "SpeechApiCredentials(redacted)"
}

/** Pure request/format rules, independent of an Android model or a provider SDK. */
object SpeechApiProtocol {
    fun endpoint(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException("请填写完整的语音服务网址") }
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.port in -1..65535 && uri.port != 0) { "语音网址仅接受HTTP/HTTPS，不含Key、查询参数或片段" }
        val base = uri.rawPath.orEmpty().trimEnd('/')
        val path = when {
            base.endsWith("/audio/speech") -> base
            base.isEmpty() -> "/v1/audio/speech"
            else -> "$base/audio/speech"
        }
        return URI(uri.scheme.lowercase() + "://" + uri.rawAuthority + path).toASCIIString()
    }

    fun body(options: SpeechApiOptions, input: String): String {
        require(input.isNotBlank() && input.codePointCount(0, input.length) <= 4096) { "单段语音文字过长或为空" }
        return "{\"model\":" + quote(options.model) + ",\"voice\":" + quote(options.voice) +
            ",\"input\":" + quote(input) + ",\"response_format\":\"wav\",\"speed\":1.0}"
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20 || c.isSurrogate()) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }

    private fun u16(b: ByteArray, n: Int) = (b[n].toInt() and 255) or ((b[n + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, n: Int) = u16(b, n).toLong() or (u16(b, n + 2).toLong() shl 16)
    private fun put32(b: ByteArray, n: Int, value: Int) { repeat(4) { b[n + it] = (value ushr (it * 8)).toByte() } }
    private fun tag(b: ByteArray, n: Int) = String(b, n, 4, Charsets.US_ASCII)

    /** Finalize only unknown RIFF/data lengths after HTTP EOF; PCM bytes are never changed. */
    fun completeWave(bytes: ByteArray): ByteArray {
        require(bytes.size in 44..MAX_WAVE_BYTES && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE")
        val result = bytes.copyOf()
        val unknownRiff = u32(result, 4) == 0xffffffffL
        val limit = if (unknownRiff) result.size.toLong() else u32(result, 4) + 8
        require(limit in 44..result.size.toLong())
        var at = 12; var format = false; var data = false
        while (at.toLong() + 8 <= limit) {
            val id = tag(result, at)
            val declared = u32(result, at + 4)
            val length = if (id == "data" && declared == 0xffffffffL) limit - at - 8 else declared
            require(length >= 0 && at.toLong() + 8 + length <= limit)
            if (id == "fmt ") {
                require(length >= 16)
                var codec = u16(result, at + 8)
                val channels = u16(result, at + 10)
                val rate = u32(result, at + 12)
                val bits = u16(result, at + 22)
                if (codec == 0xfffe) { require(length >= 40); codec = u16(result, at + 32) }
                require(channels in 1..8 && rate in 8000..192000 &&
                    ((codec == 1 && bits in setOf(8, 16, 24, 32)) || (codec == 3 && bits == 32)))
                format = true
            }
            if (id == "data") {
                require(!data && length > 0)
                if (declared == 0xffffffffL) put32(result, at + 4, length.toInt())
                data = true
            }
            at = (at.toLong() + 8 + length + (length and 1)).toInt()
        }
        require(format && data)
        if (unknownRiff) put32(result, 4, result.size - 8)
        return result
    }

    const val MAX_WAVE_BYTES = 32 * 1024 * 1024
}

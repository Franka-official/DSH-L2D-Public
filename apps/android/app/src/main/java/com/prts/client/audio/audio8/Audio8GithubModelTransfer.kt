package com.prts.client.audio.audio8

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

internal class Audio8GithubModelSource private constructor(
    val uri: URI,
    val bytes: Long,
    val sha256: String,
) {
    companion object {
        private val releasePath = Regex(
            "/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\\.zip"
        )
        private val digest = Regex("[a-f0-9]{64}")

        fun parse(json: JSONObject): Audio8GithubModelSource? {
            require(json.getString("schema") == "dsh-audio8-github-model-source-v1")
            if (json.isNull("source")) return null
            val value = json.getJSONObject("source")
            val uri = URI(value.getString("url"))
            val bytes = value.getLong("bytes")
            val sha256 = value.getString("sha256")
            require(Audio8GithubModelTransfer.allowedUri(uri) && uri.host == "github.com" &&
                uri.rawQuery == null && releasePath.matches(uri.rawPath) &&
                uri.rawPath.split('/').none { it == "." || it == ".." })
            require(value.getString("source_hf_revision") == Audio8ModelLayout.REVISION &&
                value.getString("codec_variant") == Audio8ModelLayout.CODEC_VARIANT)
            require(bytes in 1..(512L * 1024 * 1024) && digest.matches(sha256))
            return Audio8GithubModelSource(uri, bytes, sha256)
        }
    }
}

/** Downloads only a pinned model release asset. A partial file is kept for a later explicit retry. */
internal object Audio8GithubModelTransfer {
    private val hosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    private val redirectCodes = setOf(301, 302, 303, 307, 308)
    private const val bufferBytes = 128 * 1024

    fun allowedUri(uri: URI): Boolean = uri.scheme == "https" && uri.host in hosts &&
        (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null && uri.rawFragment == null

    /** A ranged response can append only when it proves the complete remaining byte interval. */
    fun responseOffset(code: Int, requested: Long, total: Long, range: String?, length: String?): Long {
        val offset = when (code) {
            HttpURLConnection.HTTP_OK -> {
                require(range == null) { "GitHub 模型包的长度响应无效" }
                0L
            }
            HttpURLConnection.HTTP_PARTIAL -> {
                require(requested in 0 until total && range == "bytes $requested-${total - 1}/$total") {
                    "GitHub 模型包的续传范围不匹配"
                }
                requested
            }
            else -> error("GitHub 模型包暂不可访问（$code）")
        }
        require(length == null || length.toLongOrNull() == total - offset) { "GitHub 模型包长度不匹配" }
        return offset
    }

    private fun open(initial: URI, offset: Long, checkCancelled: () -> Unit): HttpURLConnection {
        var uri = initial
        repeat(5) {
            checkCancelled()
            require(allowedUri(uri)) { "GitHub 模型包跳转地址无效" }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "DSH-L2D-Android")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            try {
                if (connection.responseCode !in redirectCodes) return connection
                val location = checkNotNull(connection.getHeaderField("Location")) { "GitHub 模型包跳转缺少地址" }
                uri = uri.resolve(location)
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        error("GitHub 模型包跳转次数过多")
    }

    fun fetch(source: Audio8GithubModelSource, partial: File, checkCancelled: () -> Unit,
        progress: (processed: Long, transferred: Long) -> Unit) {
        checkCancelled()
        val initial = partial.length()
        check(initial <= source.bytes) { "本地 Audio8 下载记录大小无效" }
        progress(initial, 0)
        if (initial < source.bytes) {
            val connection = open(source.uri, initial, checkCancelled)
            try {
                val encoding = connection.getHeaderField("Content-Encoding")
                require(encoding == null || encoding.equals("identity", ignoreCase = true)) {
                    "GitHub 模型包返回了不支持的传输编码"
                }
                val offset = responseOffset(connection.responseCode, initial, source.bytes,
                    connection.getHeaderField("Content-Range"), connection.getHeaderField("Content-Length"))
                checkCancelled()
                // A full 200 response cannot be appended to a partial file.
                var processed = offset
                progress(processed, 0)
                connection.inputStream.use { input ->
                    FileOutputStream(partial, offset != 0L).use { out ->
                        val buffer = ByteArray(bufferBytes)
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(count <= source.bytes - processed) { "GitHub 模型包超过发布大小" }
                            out.write(buffer, 0, count)
                            processed += count
                            progress(processed, processed - offset)
                        }
                    }
                }
                check(processed == source.bytes) { "下载未完成，再次点击可以续传" }
            } catch (error: Exception) {
                checkCancelled()
                throw error
            } finally {
                connection.disconnect()
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { input ->
            val buffer = ByteArray(bufferBytes)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (actual != source.sha256) {
            partial.delete()
            error("GitHub 模型包完整性校验失败")
        }
    }
}

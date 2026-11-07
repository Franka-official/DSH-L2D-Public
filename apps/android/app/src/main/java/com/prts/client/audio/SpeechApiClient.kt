package com.prts.client.audio

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Complete WAV per accepted speech segment; not incremental PCM playback. No automatic POST retry. */
class SpeechApiClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()) {
    class Failure(val safeMessage: String) : Exception(safeMessage)

    fun synthesize(credentials: SpeechApiCredentials, text: String, target: File,
                   cancelled: AtomicBoolean, onCall: (Call) -> Unit): File {
        val options = credentials.options
        val body = SpeechApiProtocol.body(options, text)
        val request = Request.Builder().url(options.endpoint).header("Accept", "audio/wav")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { if (credentials.apiKey.isNotEmpty()) header("Authorization", "Bearer " + credentials.apiKey) }.build()
        val call = client.newCall(request)
        onCall(call)
        fun checkCancelled() { if (cancelled.get() || call.isCanceled()) throw CancellationException("Speech request cancelled") }
        try {
            checkCancelled()
            call.execute().use { response ->
                checkCancelled()
                if (!response.isSuccessful) {
                    val message = when (response.code) {
                        401, 403 -> "语音服务未授权，请检查语音API Key"
                        429 -> "语音服务繁忙或额度不足，本轮没有自动重试"
                        in 300..399 -> "语音服务返回重定向，请直接填写最终语音接口地址"
                        else -> "语音服务返回错误（HTTP ${response.code}），本轮没有自动重试"
                    }
                    throw Failure(message)
                }
                val audio = response.body ?: throw Failure("语音服务没有返回音频")
                if (audio.contentLength() > SpeechApiProtocol.MAX_WAVE_BYTES) throw Failure("语音服务返回的音频过大")
                audio.byteStream().use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(16384); var total = 0
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        if (total > SpeechApiProtocol.MAX_WAVE_BYTES) throw Failure("语音服务返回的音频过大")
                        output.write(buffer, 0, count)
                    }
                } }
            }
            checkCancelled()
            val normalized = try { SpeechApiProtocol.completeWave(target.readBytes()) }
                catch (_: Exception) { throw Failure("语音服务未返回支持的完整WAV音频") }
            checkCancelled(); target.writeBytes(normalized); checkCancelled()
            return target
        } catch (error: Exception) {
            target.delete()
            if (cancelled.get() || call.isCanceled() || error is CancellationException) throw CancellationException("Speech request cancelled")
            if (error is Failure) throw error
            // Transport exception strings can contain URLs/credentials. Never propagate or log them.
            throw Failure("语音服务暂时无法连接，本轮没有自动重试或切换后端")
        }
    }
}

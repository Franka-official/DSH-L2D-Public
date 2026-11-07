package com.prts.client.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Device-code pairing. Provider credentials remain with the computer plugin. */
object ServicePairingClient {
    data class State(val code: String = "", val message: String = "", val paired: Boolean = false,
        val expiresAtMillis: Long = 0L, val busy: Boolean = false)
    interface Handle { fun cancel() }
    private val jobs = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    fun current(ctx: Context, mode: ConnectionMode, relayUrl: String): State? {
        if (mode == ConnectionMode.DIRECT_API) return null
        val url = runCatching { ConnectionSettings.normalizeRelayUrl(relayUrl) }.getOrNull() ?: return null
        ConnectionSettings.loadPairing(ctx, mode, false)?.takeIf { it.relayUrl == url && it.expiresAtMillis > System.currentTimeMillis() }?.let {
            return State(it.code, "将此码填入电脑插件", expiresAtMillis = it.expiresAtMillis)
        }
        ConnectionSettings.loadPairing(ctx, mode, true)?.takeIf { it.relayUrl == url }?.let {
            return State(message = "已配对，电脑插件上线后即可通话", paired = true)
        }
        return null
    }

    fun start(ctx: Context, mode: ConnectionMode, relayUrl: String, refresh: Boolean = false,
        onState: (State) -> Unit): Handle {
        val app = ctx.applicationContext
        val cancelled = AtomicBoolean(false)
        val activeCall = AtomicReference<Call?>(null)
        fun emit(value: State) { main.post { if (!cancelled.get()) onState(value) } }
        val future = jobs.submit {
            try {
                require(mode != ConnectionMode.DIRECT_API) { "API 接入不需要插件配对" }
                val root = ConnectionSettings.normalizeRelayUrl(relayUrl)
                fun request(path: String, body: JSONObject? = null, token: String? = null): JSONObject {
                    if (cancelled.get()) throw InterruptedException()
                    val builder = Request.Builder().url(root + path)
                    if (token != null) builder.header("Authorization", "Bearer $token")
                    if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
                    val call = http.newCall(builder.build()); activeCall.set(call)
                    return call.execute().use { response ->
                        require(response.isSuccessful) { when (response.code) {
                            401, 403 -> "配对凭据无效，请刷新配对码"
                            404 -> "服务尚未安装配对接口"
                            429 -> "配对请求较多，请稍后再试"
                            else -> "配对服务暂不可用（${response.code}）"
                        } }
                        val input = response.body?.byteStream() ?: throw IllegalStateException("配对服务没有返回内容")
                        val bytes = input.readBytesBounded(16384)
                        val text = bytes.toString(Charsets.UTF_8)
                        JSONObject(text)
                    }
                }
                var binding = if (refresh) null else ConnectionSettings.loadPairing(app, mode, false)?.takeIf {
                    it.relayUrl == root && it.expiresAtMillis > System.currentTimeMillis()
                }
                val active = if (refresh) null else ConnectionSettings.loadPairing(app, mode, true)?.takeIf { it.relayUrl == root }
                if (binding == null && active != null) { emit(State(message = "已配对，电脑插件上线后即可通话", paired = true)); return@submit }
                if (binding == null) {
                    emit(State(message = "正在生成配对码", busy = true))
                    val data = request("/mobile-call/v1/pair/start", JSONObject().put("mode", mode.name.lowercase()))
                    binding = PairingBinding(mode, root, data.getString("binding_id"), data.getString("client_token"),
                        data.getString("verification_code"), data.getLong("expires_at_ms"), false)
                    if (cancelled.get()) return@submit
                    ConnectionSettings.savePairing(app, binding)
                }
                val pending = binding
                while (!cancelled.get() && pending.expiresAtMillis > System.currentTimeMillis()) {
                    emit(State(pending.code, "在电脑插件填写此码，等待配对", expiresAtMillis = pending.expiresAtMillis))
                    val data = request("/mobile-call/v1/pair/status", token = pending.clientToken)
                    if (data.optString("status") == "paired") {
                        val result = pending.copy(paired = true, code = "", supportsImages = data.optJSONObject("capabilities")?.optBoolean("images") == true)
                        if (cancelled.get()) return@submit
                        ConnectionSettings.savePairing(app, result)
                        emit(State(message = if (data.optBoolean("connector_online")) "已配对，可以开始通话" else "已配对，等待电脑插件上线", paired = true))
                        return@submit
                    }
                    if (data.optString("status") == "expired") break
                    Thread.sleep(1500)
                }
                if (!cancelled.get()) emit(State(message = "配对码已过期，点击刷新"))
            } catch (_: InterruptedException) {
                // Closing settings stops polling, never submits a chat turn.
            } catch (error: Exception) {
                if (!cancelled.get()) emit(State(message = error.message?.take(160) ?: "配对未完成，请重试"))
            } finally { activeCall.set(null) }
        }
        return object : Handle { override fun cancel() { cancelled.set(true); activeCall.get()?.cancel(); future.cancel(true) } }
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "配对响应过大" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

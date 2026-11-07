package com.prts.client.localdsh

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** No LLM proxy: only a scoped connector to the in-process phone UI. IPv4 loopback only. */
object LocalDshBridge {
    const val PORT = 47651
    const val BASE = "http://127.0.0.1:47651"
    const val SOCKET_URL = "ws://127.0.0.1:47651/mobile-call/v1/ws"
    const val PROTOCOL = "live2d.local.v1"
    private const val PATH = "/local-dsh/v1"
    private const val TTL = 30 * 60 * 1000L
    private val guard = Any()
    private var app: Context? = null
    private var listener: ServerSocket? = null
    private var grant: JSONObject? = null
    private var pending: String? = null
    private var pairingUntil = 0L
    private val clients = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    private val random = SecureRandom()
    private val workers = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
        { task -> Thread(task, "PRTS-LocalDsh-HTTP").apply { isDaemon = true } })
    data class Frontend(val endpoint: String, val bindingId: String, val token: String)

    private fun fresh() = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun same(left: String, right: String) = MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
    private fun id(secret: String) = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun proof(secret: String, text: String): String = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    }.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun start(ctx: Context) = synchronized(guard) {
        if (app == null) { app = ctx.applicationContext; grant = LocalDshGrantStore.load(ctx) }
        if (listener != null) return@synchronized
        val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4) }
        listener = socket
        Thread({
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                if (!client.inetAddress.isLoopbackAddress) { client.close(); continue }
                clients.add(client)
                try { workers.execute { try { serve(client) } finally { clients.remove(client) } } }
                catch (_: Exception) { clients.remove(client); client.close() }
            }
        }, "PRTS-LocalDsh-Accept").apply { isDaemon = true; start() }
    }
    fun stop() = synchronized(guard) {
        runCatching { listener?.close() }; listener = null
        clients.forEach { runCatching { it.close() } }; clients.clear()
        pending = null; pairingUntil = 0L; LocalDshHub.shutdown()
    }
    fun hasSavedGrant(ctx: Context): Boolean = synchronized(guard) {
        grant != null || (app == null && LocalDshGrantStore.load(ctx) != null)
    }
    private fun pairingOpen(): Boolean = SystemClock.elapsedRealtime() < pairingUntil
    fun awaitingLocalPair(): Boolean = synchronized(guard) { pairingOpen() }
    fun armLocalPairing(ctx: Context) = synchronized(guard) {
        start(ctx)
        check(grant == null)
        pending = null
        pairingUntil = SystemClock.elapsedRealtime() + 90_000L
    }
    private fun beginLocalPair(challenge: String): Pair<Int, JSONObject> = synchronized(guard) {
        require(challenge.matches(Regex("[a-f0-9]{64}")))
        if (!LocalDshBridgeService.running || grant != null || !pairingOpen())
            return 403 to JSONObject().put("error", "pairing_not_open")
        val existing = pending
        if (existing != null && !same(existing, challenge))
            return 409 to JSONObject().put("error", "pairing_already_pending")
        pending = challenge
        202 to JSONObject().put("status", "pending")
    }
    private fun fingerprint(challenge: String) = id(challenge).take(8).uppercase(java.util.Locale.ROOT)
    fun pendingFingerprint(): String? = synchronized(guard) {
        pending?.takeIf { pairingOpen() }?.let(::fingerprint)
    }
    fun approveLocalPair(displayedFingerprint: String) = synchronized(guard) {
        val challenge = checkNotNull(pending)
        check(pairingOpen() && LocalDshBridgeService.running && same(fingerprint(challenge), displayedFingerprint))
        val value = JSONObject().put("challenge", challenge).put("binding_id", UUID.randomUUID().toString())
            .put("connector_token", fresh()).put("frontend_token", fresh()).put("expires_at_ms", System.currentTimeMillis() + TTL)
        LocalDshGrantStore.save(checkNotNull(app), value)
        grant = value; pending = null; pairingUntil = 0L
    }
    private fun refreshLocked() {
        val value = checkNotNull(grant) { "请先在DSHGo插件设置中连接本机" }
        if (value.optLong("expires_at_ms") <= System.currentTimeMillis()) {
            value.put("connector_token", fresh()).put("frontend_token", fresh()).put("expires_at_ms", System.currentTimeMillis() + TTL)
            LocalDshGrantStore.save(checkNotNull(app), value)
        }
    }
    fun frontend(ctx: Context): Frontend = synchronized(guard) {
        check(LocalDshBridgeService.running) { "本机连接已停止，请从DSHGo插件设置重新连接" }
        start(ctx); refreshLocked(); val value = checkNotNull(grant)
        Frontend(BASE, value.getString("binding_id"), value.getString("frontend_token"))
    }
    fun frontendAuthorized(token: String): Boolean = synchronized(guard) {
        if (!LocalDshBridgeService.running) return false
        val value = grant ?: return false
        value.optLong("expires_at_ms") > System.currentTimeMillis() && same(token, value.optString("frontend_token"))
    }
    private fun connectorAuthorized(header: String?): Boolean = synchronized(guard) {
        if (!LocalDshBridgeService.running) return false
        val value = grant ?: return false
        val token = header?.takeIf { it.startsWith("Bearer ") }?.substring(7) ?: return false
        if (value.optLong("expires_at_ms") <= System.currentTimeMillis() || !same(token, value.optString("connector_token"))) return false
        // Short role credentials remain live only while this authorized connector is active.
        value.put("expires_at_ms", System.currentTimeMillis() + TTL)
        true
    }
    val connectorReady: Boolean get() = LocalDshBridgeService.running && LocalDshHub.connectorReady
    val asrReady: Boolean get() = connectorReady && LocalDshHub.asrReady
    private fun discovery(query: Map<String, String>): JSONObject = synchronized(guard) {
        val result = JSONObject().put("protocol", PROTOCOL).put("app", "com.prts.client")
            .put("transport", "http-long-poll").put("images", false).put("asr", asrReady)
            .put("asr_protocol", "live2d.asr.v1")
            .put("proactive_protocol", LocalDshHub.PROACTIVE_PROTOCOL)
            .put("call_diagnostics", "live2d.call-options.v1")
            .put("presentation", "live2d.presentation.v1")
            .put("early_speech", "live2d.early-speech.v1")
        val pairId = query["challenge_id"] ?: return result
        val nonce = query["nonce"] ?: return result
        require(pairId.matches(Regex("[a-f0-9]{64}")) && nonce.matches(Regex("[a-f0-9]{32}")))
        val secrets = listOfNotNull(grant?.optString("challenge"), pending?.takeIf { pairingOpen() })
        val secret = secrets.firstOrNull { same(id(it), pairId) } ?: return result
        result.put("nonce", nonce).put("bootstrap_proof", proof(secret, "$PROTOCOL|discover|$pairId|$nonce"))
    }
    private fun claim(challenge: String): Pair<Int, JSONObject> = synchronized(guard) {
        require(challenge.matches(Regex("[a-f0-9]{64}")))
        val value = grant
        if (value != null && same(value.optString("challenge"), challenge)) {
            if (!LocalDshBridgeService.running) return 202 to JSONObject().put("status", "pending_service")
            refreshLocked()
            return 200 to JSONObject().put("binding_id", value.getString("binding_id"))
                .put("connector_token", value.getString("connector_token")).put("expires_at_ms", value.getLong("expires_at_ms"))
        }
        if (pending?.let { same(it, challenge) } == true && pairingOpen())
            202 to JSONObject().put("status", "pending") else 403 to JSONObject().put("error", "authorization_required")
    }
    private fun serve(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5000
            try {
                val input = BufferedInputStream(client.getInputStream())
                fun line(): String {
                    val bytes = ByteArrayOutputStream()
                    while (true) { val b = input.read(); check(b >= 0); if (b == 10) break; check(bytes.size() < 8192); if (b != 13) bytes.write(b) }
                    return bytes.toString("US-ASCII")
                }
                val request = line().split(' '); require(request.size == 3)
                val headers = mutableMapOf<String, String>(); var headerBytes = 0
                while (true) {
                    val row = line(); headerBytes += row.length; require(headerBytes <= 8192); if (row.isEmpty()) break
                    val split = row.indexOf(':'); require(split > 0); val key = row.substring(0, split).lowercase()
                    require(!headers.containsKey(key)); headers[key] = row.substring(split + 1).trim()
                }
                require(headers["transfer-encoding"] == null)
                val length = headers["content-length"]?.toInt() ?: 0; require(length in 0..65536)
                val body = ByteArray(length); var offset = 0
                while (offset < length) { val read = input.read(body, offset, length - offset); check(read > 0); offset += read }
                val uri = URI(request[1]); require(!uri.isAbsolute && uri.host == null)
                val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.associate { item ->
                    val split = item.split('=', limit = 2); URLDecoder.decode(split[0], "UTF-8") to URLDecoder.decode(split.getOrElse(1) { "" }, "UTF-8") }
                val method = request[0]; val path = uri.path; val data = if (length == 0) JSONObject() else JSONObject(String(body, Charsets.UTF_8))
                val reply = when {
                    method == "GET" && path == "$PATH/discover" -> 200 to discovery(query)
                    method == "POST" && path == "$PATH/pair/begin" -> beginLocalPair(data.getString("challenge"))
                    method == "POST" && path == "$PATH/pair/claim" -> claim(data.getString("challenge"))
                    path.startsWith("$PATH/connector/") && !connectorAuthorized(headers["authorization"]) -> 401 to JSONObject().put("error", "unauthorized")
                    method == "GET" && path == "$PATH/connector/status" -> {
                        if (query["attach"] == "1") LocalDshHub.connectorAttached()
                        else LocalDshHub.connectorSeen(heartbeat = query["heartbeat"] == "1")
                        200 to synchronized(guard) { JSONObject().put("binding_id", grant?.optString("binding_id"))
                            .put("connected", true).put("expires_at_ms", grant?.optLong("expires_at_ms")) }
                    }
                    method == "GET" && path == "$PATH/connector/poll" -> { 200 to JSONObject().put("frames", LocalDshHub.poll()) }
                    method == "POST" && path == "$PATH/connector/event" -> {
                        LocalDshHub.connectorSeen(); val accepted = LocalDshHub.connectorEvent(data)
                        (if (accepted) 200 else 409) to JSONObject().put("ok", accepted)
                    }
                    else -> 404 to JSONObject().put("error", "not_found")
                }
                respond(client, reply.first, reply.second)
            } catch (_: Exception) { runCatching { respond(client, 400, JSONObject().put("error", "invalid_request")) } }
        }
    }
    private fun respond(socket: Socket, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 $code Response\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(bytes); flush()
        }
    }
}

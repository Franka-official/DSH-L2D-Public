package com.prts.client.live2d

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One app-wide sandbox, one isolate per owner; never a WebView or network loader.
 * JS asset contract: globalThis.DshPerformance.handle(JSON-string) -> JSON-string.
 * The renderer consumes snapshots asynchronously and retains parameter ownership.
 */
class SoullinkJsEngine(
    context: Context,
    private val onSnapshot: (JSONObject) -> Unit,
    private val onState: (JSONObject) -> Unit = {},
    callbackExecutor: Executor? = null,
) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = callbackExecutor ?: Executor { main.post(it) }
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "PRTS-SoullinkJS") }
    private val closed = AtomicBoolean(false)
    private val diagnostic = AtomicReference("{\"state\":\"new\",\"jsActive\":false}")
    private val events = ArrayDeque<String>()
    private var lease: SandboxLease? = null
    private var isolate: JavaScriptIsolate? = null
    private var state = "new"
    private var active = true
    private var inFlight = false
    private var latestFrame: Frame? = null
    private var wake: ScheduledFuture<*>? = null
    private var lastFrameAt = -50L
    private var lastJsNowMs: Double? = null
    private var startedAt = 0L
    private var readyAt = 0L
    private var lastResultAt = 0L
    private var nextReportAt = 0L
    private var sequence = 0L
    private var frameCount = 0L
    private var overwrittenFrames = 0L
    private var droppedEvents = 0L
    private var totalEvalMs = 0.0
    private var maxEvalMs = 0.0
    private val features = JSONObject()
    private data class Frame(val json: String, val offeredAt: Long)

    /** Asynchronous local-asset initialization; call once per engine instance. */
    fun initialize(bundleAsset: String, init: JSONObject) {
        val payload = JSONObject(init.toString()).put("type", "init").toString()
        post {
            if (state != "new") return@post
            startedAt = SystemClock.elapsedRealtime()
            publish("initializing")
            try {
                val supported = JavaScriptSandbox.isSupported()
                features.put("supported", supported)
                if (!supported) { fail("sandbox_unsupported", null); return@post }
                // AssetManager has no remote URL path. No JS, profile or credentials are logged.
                val source = app.assets.open(bundleAsset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val acquired = SharedSandbox.acquire(app)
                lease = acquired
                listen(acquired.future) { sandbox ->
                    listOf("JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT", "JS_FEATURE_ISOLATE_TERMINATION",
                        "JS_FEATURE_EVALUATE_FROM_FD", "JS_FEATURE_PROMISE_RETURN", "JS_FEATURE_MESSAGE_PORTS")
                        .forEach { features.put(it, sandbox.isFeatureSupported(it)) }
                    if (!features.optBoolean("JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT") &&
                        source.toByteArray(Charsets.UTF_8).size > 512 * 1024) {
                        throw IllegalStateException("BundledScriptExceedsSupportedTransactionSize")
                    }
                    val js = sandbox.createIsolate()
                    isolate = js
                    js.addOnTerminatedCallback(Executor { runnable -> post { runnable.run() } }) {
                        if (!closed.get()) fail("isolate_terminated", null)
                    }
                    listen(js.evaluateJavaScriptAsync(source + "\n;typeof globalThis.DshPerformance === 'object' ? 'ready' : 'missing';")) { marker ->
                        check(marker == "ready") { "BundledPerformanceEntryMissing" }
                        evaluate(payload, "init", startedAt) { snapshot ->
                            check(snapshot.optBoolean("ready")) { "BundledPerformanceInitNotReady" }
                            publish("ready")
                            if (active) deliver(snapshot)
                            pump()
                        }
                    }
                }
            } catch (error: Exception) { fail("initialize_failed", error) }
        }
    }

    /** Latest-wins input; at most 20 frame evaluations/second, never a growing queue. */
    fun offerFrame(input: JSONObject) {
        val encoded = JSONObject(input.toString()).put("type", "step").toString()
        val offered = SystemClock.elapsedRealtime()
        post {
            if (state == "failed" || !active) return@post
            if (latestFrame != null) overwrittenFrames++
            latestFrame = Frame(encoded, offered)
            pump()
        }
    }

    /** emotion / speech_start / speech_end are serialized with frame updates. */
    fun sendEvent(input: JSONObject) {
        val encoded = input.toString()
        post {
            if (state == "failed") return@post
            if (events.size == 32) { events.removeFirst(); droppedEvents++ }
            events.addLast(encoded)
            pump()
        }
    }

    fun setActive(value: Boolean) = post {
        active = value
        if (!value) { latestFrame = null; lastJsNowMs = null; wake?.cancel(false); wake = null }
        else pump()
        if (state == "ready") publish("ready")
    }

    fun diagnostics(): JSONObject = JSONObject(diagnostic.get()).also { value ->
        val at = value.optLong("lastResultAtMs", 0L)
        if (at != 0L) value.put("lastResultAgeMs", SystemClock.elapsedRealtime() - at)
    }

    private fun pump() {
        if (state != "ready" || inFlight || closed.get()) return
        if (events.isNotEmpty()) {
            evaluate(events.removeFirst(), "event", SystemClock.elapsedRealtime()) { snapshot ->
                if (active) deliver(snapshot)
                pump()
            }
            return
        }
        if (!active) return
        val frame = latestFrame ?: return
        val now = SystemClock.elapsedRealtime()
        val wait = 50L - (now - lastFrameAt)
        if (wait > 0) {
            if (wake == null || wake?.isDone == true) wake = worker.schedule({ wake = null; pump() }, wait, TimeUnit.MILLISECONDS)
            return
        }
        latestFrame = null
        lastFrameAt = now
        val step = JSONObject(frame.json)
        val jsNow = step.optDouble("nowMs", Double.NaN)
        if (jsNow.isFinite()) {
            lastJsNowMs?.takeIf { jsNow >= it }?.let { step.put("dtMs", jsNow - it) }
            lastJsNowMs = jsNow
        }
        evaluate(step.toString(), "step", frame.offeredAt) { snapshot ->
            frameCount++
            if (active) deliver(snapshot)
            if (SystemClock.elapsedRealtime() >= nextReportAt) {
                nextReportAt = SystemClock.elapsedRealtime() + 5000
                publish("ready")
            }
            pump()
        }
    }

    private fun evaluate(payload: String, operation: String, offeredAt: Long, result: (JSONObject) -> Unit) {
        val js = isolate ?: return fail("isolate_missing", null)
        inFlight = true
        val seq = ++sequence
        val submittedAt = SystemClock.elapsedRealtime()
        // Quote the JSON string as data, never interpolate raw message text into JS code.
        val code = "globalThis.DshPerformance.handle(" + JSONObject.quote(payload) + ")"
        try {
            listen(js.evaluateJavaScriptAsync(code)) { raw ->
                val finishedAt = SystemClock.elapsedRealtime()
                lastResultAt = finishedAt
                val elapsed = (finishedAt - submittedAt).toDouble()
                totalEvalMs += elapsed; maxEvalMs = maxOf(maxEvalMs, elapsed)
                val value = JSONObject(raw)
                val timing = JSONObject().put("operation", operation).put("sequence", seq)
                    .put("queueMs", submittedAt - offeredAt).put("roundTripMs", elapsed)
                    .put("offeredToResultMs", finishedAt - offeredAt)
                value.put("androidBridge", timing)
                inFlight = false
                result(value)
            }
        } catch (error: Exception) { fail("evaluation_failed", error) }
    }

    /** get() is used only after its future completed, on this worker. */
    private fun <T> listen(future: ListenableFuture<T>, result: (T) -> Unit) {
        future.addListener(Runnable {
            if (closed.get()) return@Runnable
            try { result(future.get()) } catch (error: Exception) { fail("async_failed", error.cause ?: error) }
        }, Executor { runnable -> post { runnable.run() } })
    }

    private fun deliver(value: JSONObject) {
        val text = value.toString()
        callbacks.execute { if (!closed.get()) onSnapshot(JSONObject(text)) }
    }

    private fun publish(next: String, failure: String? = null, error: Throwable? = null) {
        if (next == "ready" && state == "initializing") readyAt = SystemClock.elapsedRealtime()
        state = next
        val value = JSONObject().put("state", next).put("jsActive", next == "ready" && active).put("active", active)
            .put("engine", "androidx.javascriptengine").put("transport", "async_evaluate")
            .put("maxFrameHz", 20).put("frames", frameCount).put("evaluations", sequence)
            .put("overwrittenFrames", overwrittenFrames).put("droppedEvents", droppedEvents)
            .put("meanRoundTripMs", if (sequence > 0) totalEvalMs / sequence else 0.0)
            .put("maxRoundTripMs", maxEvalMs).put("features", features)
            .put("lastResultAtMs", lastResultAt)
        if (startedAt != 0L) value.put("sinceInitializeMs", SystemClock.elapsedRealtime() - startedAt)
        if (readyAt != 0L) value.put("startupMs", readyAt - startedAt)
        if (failure != null) value.put("failure", failure)
        if (error != null) value.put("errorType", error.javaClass.simpleName)
        val encoded = value.toString()
        diagnostic.set(encoded)
        callbacks.execute { if (!closed.get()) onState(JSONObject(encoded)) }
    }

    private fun fail(reason: String, error: Throwable?) {
        if (closed.get() || state == "failed") return
        latestFrame = null; events.clear(); inFlight = false
        wake?.cancel(false); wake = null
        runCatching { isolate?.close() }; isolate = null
        lease?.close(); lease = null
        publish("failed", reason, error)
    }

    private fun post(block: () -> Unit) {
        if (!closed.get()) runCatching { worker.execute { if (!closed.get()) block() } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.execute {
            wake?.cancel(false); latestFrame = null; events.clear()
            runCatching { isolate?.close() }; isolate = null
            lease?.close(); lease = null
            diagnostic.set("{\"state\":\"closed\",\"jsActive\":false}")
            worker.shutdown()
        }
    }

    private class SandboxLease(val future: ListenableFuture<JavaScriptSandbox>, val release: () -> Unit) : AutoCloseable {
        private val released = AtomicBoolean(false)
        override fun close() { if (released.compareAndSet(false, true)) release() }
    }

    private object SharedSandbox {
        private val lock = Any()
        private var shared: ListenableFuture<JavaScriptSandbox>? = null
        private var owners = 0
        fun acquire(context: Context): SandboxLease = synchronized(lock) {
            val future = shared ?: JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).also { shared = it }
            owners++
            SandboxLease(future) {
                synchronized(lock) { owners-- }
                // Keep a pending connection valid if another owner arrives first.
                future.addListener(Runnable {
                    synchronized(lock) {
                        if (owners == 0 && shared === future) {
                            runCatching { future.get().close() }
                            shared = null
                        }
                    }
                }, Executor { it.run() })
            }
        }
    }
}

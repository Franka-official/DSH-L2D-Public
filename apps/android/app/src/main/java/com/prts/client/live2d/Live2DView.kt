package com.prts.client.live2d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import android.view.TextureView
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import com.prts.client.ui.Prefs

data class CallGesture(val id: String, val title: String, val durationMillis: Long)
data class CallExpression(val id: String, val title: String, val description: String = "")
private data class FaceEmotionWeights(val happy: Float, val sad: Float, val angry: Float, val surprised: Float, val shy: Float)

/** Transparent GPU producer: no per-frame Bitmap allocation or GL readback. */
class Live2DView(context: Context, private val modelDirName: String = "ellen") : TextureView(context), TextureView.SurfaceTextureListener {
    companion object {
        private const val TAG = "PRTS-Live2D"
        private const val ACTIVE_FRAME_MS = 50L
        private const val IDLE_FRAME_MS = 83L
        private val nativeLock = Any() // One owner for JNI model / Cubism shader cache at a time.
        private val callGestures = listOf(
            CallGesture("call:nod", "轻点头", 1400),
            CallGesture("call:shake", "轻摇头", 1800),
            CallGesture("call:bow", "微微倾身", 2000),
            CallGesture("call:sway", "轻轻摇摆", 2500),
        )
        val available = try { System.loadLibrary("prts_live2d"); true }
            catch (error: Throwable) { Log.e(TAG, "Live2D library unavailable", error); false }
    }

    @Volatile private var renderThread: RenderThread? = null
    @Volatile private var wantsRendering = false
    @Volatile private var canvasQuality = Prefs.getCanvasQuality(context)
    private class SurfaceOwner(val texture: SurfaceTexture) {
        @Volatile var retired = false
        private val released = AtomicBoolean(false)
        private val producerClaimed = AtomicBoolean(false)
        fun claimProducer() { check(!retired && producerClaimed.compareAndSet(false, true)) }
        fun releaseProducer() { producerClaimed.set(false); releaseIfRetired() }
        fun releaseIfRetired() {
            if (retired && !producerClaimed.get() && released.compareAndSet(false, true)) texture.release()
        }
    }
    @Volatile private var surfaceOwner: SurfaceOwner? = null
    private data class RenderExtent(val width: Int, val height: Int)
    private data class VerifiedSurfaceExtent(val owner: SurfaceOwner, val extent: RenderExtent)
    private data class TouchBounds(val owner: SurfaceOwner, val extent: RenderExtent, val sampledAt: Long,
        val bounds: FloatArray)
    private val touchBounds = AtomicReference<TouchBounds?>(null)
    private val verifiedSurfaceExtent = AtomicReference<VerifiedSurfaceExtent?>(null)
    private val viewExtent = AtomicReference(RenderExtent(0, 0))
    private val surfaceRevision = AtomicLong(0)
    private val activeUntilNanos = AtomicLong(0)
    private val presentedFrames = AtomicLong(0)
    @Volatile private var maximumTextureSize = 0
    @Volatile private var decodedTextureBytes = 0L
    @Volatile private var lastRenderFailure: String? = null
    private val pendingExpression = AtomicReference<String?>(null)
    private val pendingMotion = AtomicReference<String?>(null)
    private val pendingArmPose = AtomicReference<Boolean?>(null)
    private val pendingMotionReset = AtomicBoolean(false)
    private val pendingDrag = AtomicReference<Pair<Float, Float>?>(null)
    private val emotionState = AtomicReference(FaceEmotionWeights(0f, 0f, 0f, 0f, 0f))
    private val performanceOwner = AtomicLong(0)
    private val latestJsParameters = AtomicReference<FloatArray?>(null)
    private val performanceIntent = AtomicReference<JSONObject?>(null)
    private val nativePerformance = AtomicReference("{\"active\":false}")
    private val performanceState = AtomicReference("{\"state\":\"new\",\"jsActive\":false}")
    private val manualPerformanceFace = AtomicBoolean(false)
    @Volatile private var performanceEngine: SoullinkJsEngine? = null
    @Volatile private var performanceSpeaking = false
    @Volatile private var performanceSpeechDuration = 0L
    private val presentation = AtomicReference(PresentationTransform())
    @Volatile var isModelReady: Boolean = false
        private set
    @Volatile var continuousEmotionAvailable: Boolean = false
        private set
    @Volatile private var mouth = 0f
    @Volatile private var expressionNames: List<String> = emptyList()
    @Volatile private var motionNames: Set<String> = emptySet()
    @Volatile private var trainedCallMotionCount = 0
    @Volatile private var armGestures: List<CallGesture> = emptyList()
    @Volatile var currentArmState: String = "down"
        private set
    val isArmPoseBusy: Boolean get() = currentArmState != "down" || pendingArmPose.get() != null
    @Volatile private var emotionAliases: Map<String, String> = emptyMap()
    @Volatile private var blockedExpressions: Set<String> = emptySet()
    init { isOpaque = false; surfaceTextureListener = this }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); startRender() }
    override fun onDetachedFromWindow() { stopRender(); super.onDetachedFromWindow() }
    private fun animateFor(milliseconds: Long = 1500) {
        val until = System.nanoTime() + milliseconds * 1_000_000
        while (true) {
            val old = activeUntilNanos.get()
            if (old >= until || activeUntilNanos.compareAndSet(old, until)) return
        }
    }
    fun setExpression(name: String) { pendingExpression.set(name); animateFor() }
    fun clearExpression() { pendingExpression.set(""); animateFor() }

    /** Visual framing only; fractions of the viewport, +X right / +Y down. Safe to persist per character. */
    fun setPresentationTransform(scale: Float, offsetX: Float, offsetY: Float) {
        val value = PresentationPolicy.transform(scale, offsetX, offsetY)
        presentation.set(value)
        animateFor(1500)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        canvasQuality = Prefs.getCanvasQuality(context)
        texture.setDefaultBufferSize(canvasQuality.width, canvasQuality.height)
        surfaceOwner = SurfaceOwner(texture)
        viewExtent.set(RenderExtent(width, height))
        surfaceRevision.incrementAndGet()
        fitSurface(width, height)
        if (wantsRendering) startRender()
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(canvasQuality.width, canvasQuality.height)
        viewExtent.set(RenderExtent(width, height))
        surfaceRevision.incrementAndGet()
        fitSurface(width, height)
    }
    private fun fitSurface(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val source = renderExtent()
        val viewport = PresentationPolicy.fitViewport(width.toFloat(), height.toFloat(), source.width.toFloat(), source.height.toFloat())
        setTransform(Matrix().apply {
            setScale(viewport.width / width, viewport.height / height, width / 2f, height / 2f)
        })
    }

    private fun renderExtent(): RenderExtent = verifiedSurfaceExtent.get()
        ?.takeIf { it.owner === surfaceOwner && !it.owner.retired }?.extent
        ?: RenderExtent(canvasQuality.width, canvasQuality.height)

    /** View-local fitted bounds, shared by placement editing on Start and Call. */
    fun presentationViewport(): android.graphics.RectF {
        val source = renderExtent()
        val viewport = PresentationPolicy.fitViewport(width.toFloat(), height.toFloat(), source.width.toFloat(), source.height.toFloat())
        return android.graphics.RectF(viewport.left, viewport.top, viewport.left + viewport.width, viewport.top + viewport.height)
    }

    /** View-local pointer, matched to a recent projection of the built-in model's visible head/torso.
     * Bounds are rectangular approximations of the named mesh regions, not per-pixel alpha tests.
     */
    fun characterTouchRegion(localX: Float, localY: Float): String? {
        if (!isModelReady) return null
        val snapshot = touchBounds.get()?.takeIf {
            it.owner === surfaceOwner && !it.owner.retired && it.extent == renderExtent() &&
                android.os.SystemClock.elapsedRealtime() - it.sampledAt in 0L..650L
        } ?: return null
        val viewport = PresentationPolicy.fitViewport(width.toFloat(), height.toFloat(),
            snapshot.extent.width.toFloat(), snapshot.extent.height.toFloat())
        return PresentationPolicy.touchRegion(viewport, localX, localY, snapshot.bounds)
    }

    /** Actual renderer observations only; no readback or claim about visible mouth shape. */
    fun renderingDiagnostics(): JSONObject {
        val verified = verifiedSurfaceExtent.get()?.takeIf { it.owner === surfaceOwner && !it.owner.retired }
        return JSONObject().put("model", modelDirName).put("modelReady", isModelReady)
            .put("selectedQuality", canvasQuality.key).put("requestedWidth", canvasQuality.width)
            .put("requestedHeight", canvasQuality.height).put("actualWidth", verified?.extent?.width ?: JSONObject.NULL)
            .put("actualHeight", verified?.extent?.height ?: JSONObject.NULL)
            .put("surfaceVerifiedForCurrentOwner", verified != null).put("presentedFrames", presentedFrames.get())
            .put("renderThreadAlive", renderThread?.isAlive == true).put("requestedMouthLevel", mouth)
            .put("trainedCallMotionCount", trainedCallMotionCount)
            .put("performanceRuntime", JSONObject(performanceState.get()))
            .put("performanceNative", JSONObject(nativePerformance.get()))
            .put("glMaximumTextureSize", maximumTextureSize).put("decodedTextureBytes", decodedTextureBytes)
            .put("renderFailure", lastRenderFailure ?: JSONObject.NULL)
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val thread = renderThread
        val owner = surfaceOwner?.takeIf { it.texture === texture }
            ?: thread?.owner?.takeIf { it.texture === texture }
        if (owner == null) { texture.release(); return false }
        owner.retired = true
        if (surfaceOwner === owner) surfaceOwner = null
        if (thread?.owner === owner) { thread.running = false; thread.interrupt() }
        owner.releaseIfRetired()
        // The render thread releases it only after disconnecting its EGL producer.
        return false
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    /** Automatic state only. Manual expressions temporarily take priority without discarding this snapshot. */
    fun setEmotionWeights(happy: Float, sad: Float, angry: Float, surprised: Float, shy: Float) {
        fun safe(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        emotionState.set(FaceEmotionWeights(safe(happy), safe(sad), safe(angry), safe(surprised), safe(shy)))
    }
    fun clearEmotionState() {
        setEmotionWeights(0f, 0f, 0f, 0f, 0f)
        setPerformanceEmotion("neutral",0.0,1.0,0.0,0.0,0.0)
    }
    fun setPerformanceEmotion(emotion: String, intensity: Double, confidence: Double,
                              valence: Double?, arousal: Double?, dominance: Double?) {
        val event=JSONObject().put("type","emotion").put("emotion",emotion.take(40))
            .put("intensity",if(intensity.isFinite())intensity.coerceIn(0.0,1.0) else 0.0)
            .put("confidence",if(confidence.isFinite())confidence.coerceIn(0.0,1.0) else 0.0)
        listOf("valence" to valence,"arousal" to arousal,"dominance" to dominance).forEach { (name,value) ->
            if(value?.isFinite()==true)event.put(name,value.coerceIn(-1.0,1.0))
        }
        performanceIntent.set(event)
        performanceEngine?.sendEvent(event)
        animateFor(3500)
    }
    fun setPerformanceSpeech(active: Boolean, durationMs: Long) {
        performanceSpeaking=active;performanceSpeechDuration=if(active)durationMs.coerceIn(0,120000) else 0
        performanceEngine?.sendEvent(JSONObject().put("type",if(active)"speech_start" else "speech_end")
            .put("durationMs",performanceSpeechDuration).put("nowMs",android.os.SystemClock.elapsedRealtime()))
        if(active)animateFor(performanceSpeechDuration+250)
    }
    /** GL owner binds real parameters; callbacks only publish immutable snapshots. */
    private fun createPerformanceEngine(token: Long): SoullinkJsEngine? {
        if(modelDirName !in setOf("whale","ellen")) return null
        return try {
            val composite=context.assets.open("soullink/profiles/$modelDirName.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val parameters=composite.getJSONObject("native").getJSONArray("parameters")
            val ids=Array(parameters.length()){parameters.getJSONObject(it).getString("id")}
            val defaults=FloatArray(ids.size){parameters.getJSONObject(it).getDouble("default").toFloat()}
            val bounds=FloatArray(ids.size*3){index ->
                parameters.getJSONObject(index/3).getDouble(arrayOf("min","default","max")[index%3]).toFloat()
            }
            check(nativeConfigureJsPerformance(ids,bounds)){"PerformanceProfileDoesNotMatchLoadedRig"}
            val engine=SoullinkJsEngine(context,
                onSnapshot={ snapshot ->
                    if(performanceOwner.get()==token) {
                        val values=snapshot.optJSONObject("parameters")
                        if(values!=null&&values.length()>0) {
                            val targets=FloatArray(ids.size){index ->values.optDouble(ids[index],defaults[index].toDouble()).toFloat()}
                            if(targets.all{it.isFinite()}) latestJsParameters.set(targets)
                        }
                    }
                },onState={ status ->
                    if(performanceOwner.get()==token) {
                        performanceState.set(status.toString())
                        if(!status.optBoolean("jsActive",false))latestJsParameters.set(null)
                        Log.i("PRTS-Soullink",status.toString())
                    }
                },callbackExecutor=java.util.concurrent.Executor{it.run()})
            performanceEngine=engine
            engine.initialize("soullink/performance.bundle.js",JSONObject().put("profile",composite.getJSONObject("profile"))
                .put("parameterMetadata",parameters).put("character",modelDirName)
                .put("seed",android.os.SystemClock.elapsedRealtime().and(0x7fffffffL)))
            performanceIntent.get()?.let{engine.sendEvent(it)}
            if(performanceSpeaking)engine.sendEvent(JSONObject().put("type","speech_start").put("durationMs",performanceSpeechDuration)
                .put("nowMs",android.os.SystemClock.elapsedRealtime()))
            engine
        } catch(error: Exception) {
            val status=JSONObject().put("state","binding_failed").put("jsActive",false).put("reason",error.message?:error.javaClass.simpleName)
            performanceState.set(status.toString());Log.e("PRTS-Soullink",status.toString());null
        }
    }
    fun playMotion(name: String) { pendingMotion.set(name); animateFor(3500) }
    /** The model must be loaded before exposing a gesture as available. */
    fun availableGestures(): List<CallGesture> = callGestures.filter { it.id in motionNames } + armGestures

    fun setArmPose(raised: Boolean): Boolean {
        if (armGestures.isEmpty() || raised && isArmPoseBusy) return false
        pendingArmPose.set(raised)
        animateFor(5000)
        return true
    }

    /** Reset only application-owned posture; retain the selected facial expression. */
    fun resetMotionState() {
        pendingMotion.set(null)
        pendingArmPose.set(null)
        pendingMotionReset.set(true)
    }

    /** Only reviewed emotion aliases; model-specific prop / costume toggles stay private. */
    fun availableExpressions(): List<CallExpression> {
        if (expressionNames.isEmpty()) return emptyList()
        val result = mutableListOf(CallExpression("mobile:neutral", "自然", "恢复自然表情"))
        for ((tag, title) in listOf("happy" to "开心", "sad" to "难过", "angry" to "生气",
                "surprised" to "惊讶", "thinking" to "思考", "shy" to "害羞")) {
            val target = emotionAliases[tag]
            if (!target.isNullOrBlank() && target in expressionNames && target !in blockedExpressions)
                result.add(CallExpression("mobile:$tag", title))
        }
        return result
    }

    fun playGesture(name: String, extended: Boolean = false): Boolean {
        val value = name.trim().lowercase(Locale.ROOT)
        val canonical = if (value.startsWith("call:")) value else "call:$value"
        if (canonical == "call:raise-hand") return setArmPose(true)
        if (canonical == "call:lower-hand") return setArmPose(false)
        if (callGestures.none { it.id == canonical }) return false
        val target = canonical + if (extended) "-wide" else ""
        if (target !in motionNames) return false
        pendingMotion.set(target)
        animateFor(3500)
        return true
    }

    /** Prefer the rig's wide authored motion for automatic gestures. */
    fun playPerformanceGesture(id: String): Boolean =
        playGesture(id, extended = true) || playGesture(id)

    /** Suggestions only: the caller decides cadence and whether a reply needs motion. */
    fun suggestedGestureForEmotion(emotion: String): String? {
        val tag = emotion.trim().lowercase(Locale.ROOT).removePrefix("mobile:")
        val suggestion = when (tag) {
            "happy", "joy" -> "call:nod"
            "angry", "anger", "disgust" -> "call:shake"
            "shy" -> "call:bow"
            "smirk" -> "call:sway"
            else -> null
        }
        return suggestion?.takeIf { it in motionNames }
    }
    fun setDrag(x: Float, y: Float) { pendingDrag.set(x to y); animateFor() }
    fun setMouth(value: Float) {
        mouth = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        if (mouth > .01f) animateFor(750)
    }

    /** Apply the shared preference to a loaded preview as well as a new call. */
    fun refreshCanvasQuality() {
        val next = Prefs.getCanvasQuality(context)
        if (next == canvasQuality) return
        val shouldRender = wantsRendering
        renderThread?.let { it.running = false; it.interrupt() }
        isModelReady = false
        canvasQuality = next
        verifiedSurfaceExtent.set(null)
        surfaceOwner?.takeUnless { it.retired }?.texture?.setDefaultBufferSize(next.width, next.height)
        surfaceRevision.incrementAndGet()
        val view = viewExtent.get()
        fitSurface(view.width, view.height)
        Log.i(TAG, "canvas selection model=$modelDirName quality=${next.key} requested=${next.width}x${next.height}")
        if (shouldRender) startRender()
    }

    fun startRender() {
        wantsRendering = true
        refreshCanvasQuality()
        if (!available || !isAttachedToWindow || renderThread?.isAlive == true) return
        val owner = surfaceOwner?.takeUnless { it.retired } ?: return
        animateFor(2000)
        lastRenderFailure = null
        owner.claimProducer()
        RenderThread(owner).also {
            renderThread = it
            try { it.start() }
            catch (error: Throwable) {
                renderThread = null; wantsRendering = false; owner.releaseProducer()
                Log.e(TAG, "render thread could not start", error)
            }
        }
    }

    fun stopRender() {
        wantsRendering = false
        mouth = 0f
        pendingMotion.set(null)
        pendingArmPose.set(null)
        pendingMotionReset.set(true)
        armGestures = emptyList()
        currentArmState = "down"
        continuousEmotionAvailable = false
        isModelReady = false
        renderThread?.let { it.running = false; it.interrupt() }
        // No UI-thread join: a slow GL driver must not freeze navigation.
    }

    private inner class RenderThread(val owner: SurfaceOwner) : Thread("PRTS-Live2D-GL") {
        @Volatile var running = true
        private fun queryExtent(display: android.opengl.EGLDisplay, surface: EGLSurface, phase: String): RenderExtent {
            val queriedWidth = IntArray(1)
            val queriedHeight = IntArray(1)
            check(EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, queriedWidth, 0)
                && EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, queriedHeight, 0)
                && queriedWidth[0] > 0 && queriedHeight[0] > 0) { "EGL surface has no valid drawing extent" }
            val extent = RenderExtent(queriedWidth[0], queriedHeight[0])
            val requested = canvasQuality
            CanvasRenderPolicy.requireActualExtent(requested, extent.width, extent.height)
            verifiedSurfaceExtent.set(VerifiedSurfaceExtent(owner, extent))
            val view = viewExtent.get()
            Log.i(TAG, "surface extent model=$modelDirName phase=$phase quality=${requested.key} requested=${requested.width}x${requested.height} actual=${extent.width}x${extent.height} view=${view.width}x${view.height}")
            post {
                if (surfaceOwner === owner && !owner.retired) {
                    val currentView = viewExtent.get()
                    fitSurface(currentView.width, currentView.height)
                }
            }
            return extent
        }
        override fun run() {
            try { synchronized(nativeLock) {
                if (!running || owner.retired) return
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                var surface: EGLSurface = EGL14.EGL_NO_SURFACE
                var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
                var current = false
                var window: Surface? = null
                val performanceToken = performanceOwner.incrementAndGet()
                var jsRuntime: SoullinkJsEngine? = null
                try {
                    check(EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) { "EGL initialize failed" }
                    val configurations = arrayOfNulls<EGLConfig>(1)
                    val count = IntArray(1)
                    val attributes = intArrayOf(
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
                    check(EGL14.eglChooseConfig(display, attributes, 0, configurations, 0, 1, count, 0) && count[0] > 0) { "No EGL window config" }
                    window = Surface(owner.texture)
                    surface = EGL14.eglCreateWindowSurface(display, configurations[0], window,
                        intArrayOf(EGL14.EGL_NONE), 0)
                    check(surface != EGL14.EGL_NO_SURFACE) { "Window surface creation failed" }
                    eglContext = EGL14.eglCreateContext(display, configurations[0], EGL14.EGL_NO_CONTEXT,
                        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                    check(eglContext != EGL14.EGL_NO_CONTEXT && EGL14.eglMakeCurrent(display, surface, surface, eglContext)) { "EGL context failed" }
                    current = true
                    var appliedSurfaceRevision = surfaceRevision.get()
                    var drawingExtent = queryExtent(display, surface, "created")
                    EGL14.eglSwapInterval(display, 1)
                    nativeInit(ensureShaders().absolutePath)
                    loadModel()
                    latestJsParameters.set(null)
                    jsRuntime = createPerformanceEngine(performanceToken)
                    if (!running || owner.retired) return
                    isModelReady = true
                    Log.i(TAG, "ready model=$modelDirName render=${drawingExtent.width}x${drawingExtent.height} quality=${canvasQuality.key} requested=${canvasQuality.width}x${canvasQuality.height} mode=direct-gpu activeFps=20 idleFps=12 readback=false")
                    var previousTime = System.nanoTime()
                    var statsAt = previousTime
                    var frames = 0L
                    var workNanos = 0L
                    var swapNanos = 0L
                    var performanceSampleAt = 0L
                    var touchSampleAt = 0L
                    while (running && !owner.retired) {
                        val revision = surfaceRevision.get()
                        if (revision != appliedSurfaceRevision) {
                            drawingExtent = queryExtent(display, surface, "resize")
                            appliedSurfaceRevision = revision
                        }
                        val started = System.nanoTime()
                        val dt = ((started - previousTime) / 1e9).toFloat().coerceIn(0f, .1f)
                        previousTime = started
                        if (pendingMotionReset.getAndSet(false)) nativeResetMotionState()
                        presentation.get().let { nativeSetPresentationTransform(it.scale, it.offsetX, it.offsetY) }
                        pendingExpression.getAndSet(null)?.let { value ->
                            val name = resolveExpression(value)
                            if (name.isEmpty()) {
                                manualPerformanceFace.set(false)
                                nativeClearExpression()
                                Log.i(TAG, "expression cleared: model=$modelDirName request=$value")
                            } else {
                                manualPerformanceFace.set(true)
                                nativeSetExpression(name)
                            }
                        }
                        pendingMotion.getAndSet(null)?.let { name ->
                            if (name in motionNames) nativePlayMotion(name)
                            else Log.w(TAG, "motion ignored, not registered: model=$modelDirName name=$name")
                        }
                        pendingArmPose.getAndSet(null)?.let { nativeRequestArmPose(it) }
                        pendingDrag.getAndSet(null)?.let { nativeSetDrag(it.first, it.second) }
                        if (continuousEmotionAvailable) emotionState.get().let {
                            nativeSetEmotionWeights(it.happy, it.sad, it.angry, it.surprised, it.shy)
                        }
                        nativeSetMouth(mouth)
                        jsRuntime?.offerFrame(JSONObject().put("nowMs",android.os.SystemClock.elapsedRealtime())
                            .put("dtMs",dt*1000.0).put("mouthLevel",mouth)
                            .put("speaking",performanceSpeaking).put("manualFace",manualPerformanceFace.get())
                            .put("armBusy",isArmPoseBusy))
                        latestJsParameters.getAndSet(null)?.let{nativeSetJsParameters(it)}
                        currentArmState = when (nativeUpdate(dt)) {
                            1 -> "raising"
                            2 -> "holding"
                            3 -> "lowering"
                            else -> "down"
                        }
                        val sampleNow=android.os.SystemClock.elapsedRealtime()
                        if(sampleNow>=performanceSampleAt) {
                            nativePerformance.set(nativeJsDiagnostics())
                            performanceSampleAt=sampleNow+1000
                        }
                        check(nativeDrawSurface(drawingExtent.width, drawingExtent.height)) { "Live2D surface draw failed" }
                        if (!running || owner.retired) break
                        val drawnAt = System.nanoTime()
                        check(EGL14.eglSwapBuffers(display, surface)) { "Live2D surface swap failed" }
                        if (sampleNow >= touchSampleAt && modelDirName in setOf("whale", "ellen")) {
                            nativeTouchBounds(drawingExtent.width, drawingExtent.height)?.let { bounds ->
                                touchBounds.set(TouchBounds(owner, drawingExtent, sampleNow, bounds))
                            }
                            touchSampleAt = sampleNow + 250L
                        }
                        presentedFrames.incrementAndGet()
                        val swappedAt = System.nanoTime()
                        frames++; workNanos += drawnAt - started; swapNanos += swappedAt - drawnAt
                        if (swappedAt - statsAt >= 10_000_000_000L) {
                            val fps = frames * 1_000_000_000.0 / (swappedAt - statsAt)
                            Log.i(TAG, "render stats mode=direct-gpu frames=$frames fps=${String.format(Locale.ROOT, "%.1f", fps)} work_ms=${workNanos / frames / 1_000_000} swap_ms=${swapNanos / frames / 1_000_000} bitmap_frames=0 readback_frames=0")
                            statsAt = swappedAt; frames = 0; workNanos = 0; swapNanos = 0
                        }
                        val frameMs = if (started < activeUntilNanos.get() || currentArmState != "down") ACTIVE_FRAME_MS else IDLE_FRAME_MS
                        val delay = frameMs - (System.nanoTime() - started) / 1_000_000
                        if (delay > 0) try { sleep(delay) } catch (_: InterruptedException) { if (!running) break }
                    }
                } catch (error: Throwable) {
                    if (running && !owner.retired) {
                        wantsRendering = false // Explicit retry only after a real producer/render failure.
                        lastRenderFailure = error.message ?: error.javaClass.simpleName
                        Log.e(TAG, "model=$modelDirName stopped: ${error.message}", error)
                    }
                } finally {
                    if(performanceOwner.compareAndSet(performanceToken,performanceToken+1)) {
                        performanceEngine=null
                        latestJsParameters.set(null)
                        performanceState.set("{\"state\":\"stopped\",\"jsActive\":false}")
                        nativePerformance.set("{\"active\":false}")
                    }
                    jsRuntime?.close()
                    isModelReady = false
                    if (current) nativeDispose()
                    if (display != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                        EGL14.eglTerminate(display)
                    }
                    EGL14.eglReleaseThread()
                    window?.release()
                }
            } } finally {
                owner.releaseProducer()
                post {
                        if (renderThread === this@RenderThread) {
                            renderThread = null
                            if (wantsRendering && isAttachedToWindow && surfaceOwner != null) startRender()
                        }
                    }
            }
        }
    }

    private class ModelFiles(val diskRoot: File?, val assetRoot: String, val context: Context) {
        fun open(relative: String): InputStream {
            require(!relative.startsWith('/') && relative.split('/').none { it == ".." }) { "Invalid model path" }
            return if (diskRoot != null) File(diskRoot, relative).inputStream() else context.assets.open("$assetRoot/$relative")
        }
        fun bytes(relative: String): ByteArray = open(relative).use { it.readBytes() }
    }

    private fun loadModel() {
        val disk = File(context.filesDir, "live2d/$modelDirName")
        val modelFile = File(disk, "model.model3.json").takeIf { it.isFile }
            ?: disk.listFiles()?.firstOrNull { it.name.endsWith(".model3.json") }
        val assetRoot = "live2d/$modelDirName"
        val modelName = modelFile?.name ?: context.assets.list(assetRoot)?.firstOrNull { it.endsWith(".model3.json") }
            ?: error("No model3 configuration for $modelDirName")
        val files = ModelFiles(if (modelFile != null) disk else null, assetRoot, context)
        val modelBytes = files.bytes(modelName)
        val references = JSONObject(String(modelBytes, Charsets.UTF_8)).getJSONObject("FileReferences")
        val physics = references.optString("Physics").takeIf { it.isNotBlank() }?.let { files.bytes(it) } ?: ByteArray(0)
        val profile = File(disk, "profile.json").takeIf { modelFile != null && it.isFile }?.let { JSONObject(it.readText()) }
        val hidden = profile?.optString("watermarkPartId", "") ?: if (modelDirName == "ellen") "Part17" else ""
        check(nativeSetup(files.bytes(references.getString("Moc")), modelBytes, physics, hidden)) { "Native setup failed" }
        if (modelDirName in setOf("whale", "ellen")) nativeConfigureTouchRegions(modelDirName == "whale")
        val callRigDescription = nativePrepareCallGestureRig(modelDirName == "whale")
        val actualCallRig = callRigDescription.takeIf { it.isNotBlank() }?.let(::JSONObject)
        val callRig = actualCallRig?.takeIf { modelDirName == "whale" }
        val textures = references.getJSONArray("Textures")
        val glMaximum = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, glMaximum, 0)
        maximumTextureSize = glMaximum[0]
        val textureLimit = CanvasRenderPolicy.textureLimit(maximumTextureSize)
        decodedTextureBytes = 0L
        Log.i(TAG, "texture limit model=$modelDirName requestedCap=4096 effectiveCap=$textureLimit glMaximum=$maximumTextureSize")
        var uploadBuffer: ByteBuffer? = null
        for (index in 0 until textures.length()) {
            val path = textures.getString(index)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            files.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Texture $index unreadable: $path" }
            val sample = CanvasRenderPolicy.textureSample(bounds.outWidth, bounds.outHeight, maximumTextureSize)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = true
            }
            val bitmap = files.open(path).use { BitmapFactory.decodeStream(it, null, options) }
                ?: error("Texture $index decode failed: $path")
            try {
                check(bitmap.width <= textureLimit && bitmap.height <= textureLimit) { "Decoded texture exceeds the verified OpenGL texture limit" }
                decodedTextureBytes += bitmap.byteCount.toLong()
                Log.i(TAG, "texture decode model=$modelDirName index=$index source=${bounds.outWidth}x${bounds.outHeight} sample=$sample decoded=${bitmap.width}x${bitmap.height} bytes=${bitmap.byteCount} effectiveCap=$textureLimit")
                if ((uploadBuffer?.capacity() ?: 0) < bitmap.byteCount) {
                    uploadBuffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
                }
                val pixels = uploadBuffer!!
                pixels.clear()
                bitmap.copyPixelsToBuffer(pixels); pixels.rewind()
                check(nativeBindTexture(index, pixels, bitmap.width, bitmap.height)) { "Texture upload failed index=$index" }
            } finally { bitmap.recycle() }
        }
        Log.i(TAG, "texture upload complete model=$modelDirName count=${textures.length()} decodedBytes=$decodedTextureBytes reusableUploadBufferBytes=${uploadBuffer?.capacity() ?: 0}")
        uploadBuffer = null // No Java pixel array collection is retained after sequential upload.
        val expressions = references.optJSONArray("Expressions")
        val names = ArrayList<String>()
        var reviewedFaceMask = 0
        for (index in 0 until (expressions?.length() ?: 0)) {
            val item = expressions!!.getJSONObject(index)
            val name = item.getString("Name")
            val data = files.bytes(item.getString("File"))
            nativeAddExpressionFile(name, data); names.add(name)
            if (modelDirName == "whale") reviewedFaceMask = reviewedFaceMask or reviewedFaceOverlay(name, data)
        }
        expressionNames = names
        loadEmotionAliases(disk.takeIf { modelFile != null }, assetRoot)
        continuousEmotionAvailable = nativeConfigureContinuousFace(modelDirName == "whale", reviewedFaceMask)
        if (modelDirName == "ellen" && continuousEmotionAvailable) {
            // Native registers these recipes only after validating the actual rig parameter ranges.
            val emotions = listOf("happy", "sad", "angry", "surprised", "thinking", "shy")
            expressionNames = (expressionNames + emotions.map { "app-$it" }).distinct()
            emotionAliases = emotionAliases + emotions.associateWith { "app-$it" }
            Log.i(TAG, "Ellen dynamic facial expressions enabled: ${emotions.joinToString { "app-$it" }}")
        }
        var motionCount = 0
        val loadedMotions = linkedSetOf<String>()
        val armCandidates = mutableMapOf<String, ByteArray>()
        armGestures = emptyList()
        references.optJSONObject("Motions")?.let { groups ->
            for (group in groups.keys()) {
                val entries = groups.getJSONArray(group)
                for (index in 0 until entries.length()) {
                    val item = entries.getJSONObject(index)
                    val path = item.getString("File")
                    val name = item.optString("Name").ifBlank { path.substringAfterLast('/').removeSuffix(".motion3.json") }
                    val data = files.bytes(path)
                    if (modelDirName == "whale" && path.substringAfterLast('/') in setOf("默认手切举手.motion3.json", "举手切默认手.motion3.json"))
                        armCandidates[path.substringAfterLast('/')] = data
                    if (nativeAddMotionFile(name, data, group.equals("Idle", ignoreCase = true))) {
                        loadedMotions.add(name); motionCount++
                    }
                }
            }
        }
        if (modelDirName == "whale" && armCandidates.size == 2) {
            try {
                val up = sanitizedArmMotion(armCandidates.getValue("默认手切举手.motion3.json"))
                val down = sanitizedArmMotion(armCandidates.getValue("举手切默认手.motion3.json"))
                check(nativeConfigureArmPoses(up, down)) { "Native SDK rejected safe arm pair" }
                armGestures = listOf(
                    CallGesture("call:raise-hand", "抬手致意", Math.round(JSONObject(String(up, Charsets.UTF_8)).getJSONObject("Meta").getDouble("Duration") * 1000)),
                    CallGesture("call:lower-hand", "放下手", Math.round(JSONObject(String(down, Charsets.UTF_8)).getJSONObject("Meta").getDouble("Duration") * 1000)),
                )
            } catch (error: Exception) {
                Log.w(TAG, "arm pair unavailable: model=$modelDirName", error)
            }
        }
        // Shared gestures are opt-in, never Idle; imported rigs keep only their own motions.
        trainedCallMotionCount = 0
        val trainedCallMotions = loadTrainedCallMotions(actualCallRig)
        if (modelDirName == "ellen" || modelDirName == "whale" && callRig != null) {
            for (gesture in callGestures) {
                for (suffix in listOf("", "-wide")) {
                    val id = gesture.id + suffix
                    val asset = "call-motions/${id.substringAfter(':')}.motion3.json"
                    try {
                        val trained = trainedCallMotions[id]
                        val data = trained ?: context.assets.open(asset).use { it.readBytes() }.let { original ->
                            if (callRig != null) remapCallGesture(original, callRig, id) else original
                        }
                        check(nativeAddMotionFile(id, data, false)) { "Native SDK rejected application motion $id" }
                        loadedMotions.add(id)
                        motionCount++
                        if (trained != null) trainedCallMotionCount++
                    } catch (error: Exception) {
                        Log.w(TAG, "call gesture unavailable: model=$modelDirName gesture=$id", error)
                    }
                }
            }
        }
        motionNames = loadedMotions
        if (trainedCallMotionCount > 0) Log.i(TAG, "trained rig motions registered model=$modelDirName count=$trainedCallMotionCount remap=false runtime=baked-curves")
        Log.i(TAG, "resources model=$modelDirName textures=${textures.length()} expressions=${expressionNames.joinToString()} motions=$motionCount hiddenPart=$hidden")
    }

    /** Only built-in identities, plus exact loaded-rig ID/range agreement; imported rigs never opt in. */
    private fun loadTrainedCallMotions(actualRig: JSONObject?): Map<String, ByteArray> {
        if (modelDirName !in setOf("ellen", "whale")) return emptyMap()
        return try {
            check(actualRig != null) { "No actual rig ranges" }
            val manifest = context.assets.open("trained-rigs/manifest.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            check(manifest.getString("schema") == "dsh-trained-rigs-1") { "Unknown trained rig schema" }
            val model = manifest.getJSONObject("models").getJSONObject(modelDirName)
            val expected = model.getJSONObject("channels")
            check(expected.length() == 6 && actualRig.length() == 6) { "Incomplete trained rig channels" }
            val ranges = mutableMapOf<String, JSONObject>()
            for (source in expected.keys()) {
                val wanted = expected.getJSONObject(source)
                val loaded = actualRig.getJSONObject(source)
                val id = wanted.getString("id")
                check(id == loaded.getString("id")) { "Trained rig parameter mismatch: $source" }
                for (field in listOf("minimum", "default", "maximum")) {
                    val value = loaded.getDouble(field)
                    check(value.isFinite() && kotlin.math.abs(value - wanted.getDouble(field)) <= .0001) { "Trained rig range mismatch: $source/$field" }
                }
                check(loaded.getDouble("minimum") <= loaded.getDouble("default") && loaded.getDouble("default") <= loaded.getDouble("maximum") && loaded.getDouble("minimum") < loaded.getDouble("maximum"))
                ranges[id] = loaded
            }
            val motions = model.getJSONObject("motions")
            val result = linkedMapOf<String, ByteArray>()
            for (gesture in callGestures) for (suffix in listOf("", "-wide")) {
                val id = gesture.id + suffix
                val entry = motions.getJSONObject(id)
                val path = "$modelDirName/${id.substringAfter(':')}.motion3.json"
                check(entry.getString("file") == path) { "Trained rig asset identity mismatch" }
                val data = context.assets.open("trained-rigs/$path").use { it.readBytes() }
                check(data.size.toLong() == entry.getLong("bytes")) { "Trained rig asset size mismatch" }
                validateTrainedCallMotion(data, entry, ranges, gesture.durationMillis)
                result[id] = data
            }
            Log.i(TAG, "trained rig contract verified model=$modelDirName revision=${manifest.getString("revision")} channels=${ranges.keys.joinToString()} motions=${result.size}")
            result
        } catch (error: Exception) {
            Log.w(TAG, "trained rig unavailable: model=$modelDirName; keeping prior gesture policy", error)
            emptyMap()
        }
    }

    private fun validateTrainedCallMotion(data: ByteArray, entry: JSONObject, ranges: Map<String, JSONObject>, durationMillis: Long) {
        val document = JSONObject(String(data, Charsets.UTF_8))
        val meta = document.getJSONObject("Meta")
        val duration = meta.getDouble("Duration")
        check(document.getInt("Version") == 3 && duration.isFinite() && kotlin.math.abs(duration * 1000 - durationMillis) < .01 && !meta.getBoolean("Loop"))
        val expected = entry.getJSONArray("parameter_ids").let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() }
        val curves = document.getJSONArray("Curves")
        check(curves.length() == expected.size && meta.getInt("CurveCount") == curves.length())
        val seen = mutableSetOf<String>()
        var totalSegments = 0
        var totalPoints = 0
        for (index in 0 until curves.length()) {
            val curve = curves.getJSONObject(index)
            val id = curve.getString("Id")
            check(curve.getString("Target") == "Parameter" && id in expected && seen.add(id))
            val range = ranges.getValue(id)
            val segments = curve.getJSONArray("Segments")
            check(segments.length() >= 5 && (segments.length() - 2) % 3 == 0 && segments.getDouble(0) == 0.0)
            val count = (segments.length() - 2) / 3
            var previousTime = 0.0
            for (point in 0..count) {
                val timeIndex = if (point == 0) 0 else 3 * point
                if (point > 0) check(segments.getInt(timeIndex - 1) == 0) { "Trained rig requires linear segments" }
                val time = segments.getDouble(timeIndex)
                val value = segments.getDouble(timeIndex + 1)
                check(time.isFinite() && time >= previousTime && time <= duration + .000001)
                check(value.isFinite() && value >= range.getDouble("minimum") - .000001 && value <= range.getDouble("maximum") + .000001)
                previousTime = time
            }
            check(kotlin.math.abs(previousTime - duration) < .000001)
            totalSegments += count
            totalPoints += count + 1
        }
        check(seen == expected && meta.getInt("TotalSegmentCount") == totalSegments && meta.getInt("TotalPointCount") == totalPoints)
    }

    private fun sanitizedArmMotion(original: ByteArray): ByteArray {
        val document = JSONObject(String(original, Charsets.UTF_8))
        val allowed = setOf("Movement2", "Param62", "Param59", "Param60", "Param61")
        val excluded = setOf("Param56", "Param57") // Author's light animation, not hand posture.
        val source = document.getJSONArray("Curves")
        val ids = (0 until source.length()).map { source.getJSONObject(it).getString("Id") }
        check(ids.size == 7 && ids.toSet() == allowed + excluded) { "Arm pair resource changed: review required" }
        val curves = JSONArray()
        var totalSegments = 0
        var totalPoints = 0
        val meta = document.getJSONObject("Meta")
        val duration = meta.getDouble("Duration")
        check(duration > 0 && duration <= 3 && meta.optInt("UserDataCount") == 0)
        for (index in 0 until source.length()) {
            val curve = source.getJSONObject(index)
            check(curve.getString("Target") == "Parameter")
            if (curve.getString("Id") !in allowed) continue
            val segments = curve.getJSONArray("Segments")
            check(segments.length() >= 5 && segments.getDouble(0) == 0.0)
            totalPoints++
            var cursor = 2
            while (cursor < segments.length()) {
                val type = segments.getInt(cursor)
                check(type in 0..3)
                val width = if (type == 1) 7 else 3
                check(cursor + width <= segments.length())
                totalPoints += if (type == 1) 3 else 1
                totalSegments++
                cursor += width
            }
            check(cursor == segments.length() && kotlin.math.abs(segments.getDouble(segments.length() - 2) - duration) < .0001)
            // Preserve authored times/values, while avoiding the SDK parser's exponent limitation.
            for (point in 0 until segments.length()) {
                val number = segments.getDouble(point)
                check(number.isFinite())
                segments.put(point, BigDecimal.valueOf(number).setScale(6, RoundingMode.HALF_UP))
            }
            curves.put(curve)
        }
        meta.put("Loop", false)
        meta.put("CurveCount", curves.length())
        meta.put("TotalSegmentCount", totalSegments)
        meta.put("TotalPointCount", totalPoints)
        // These authored curves contain their own easing; a second 1s SDK fade causes pose jumps.
        meta.put("FadeInTime", 0)
        meta.put("FadeOutTime", 0)
        document.put("Curves", curves)
        return (document.toString(2) + "\n").toByteArray(Charsets.UTF_8)
    }

    /** Only the four audited facial effects, never props / clothing / loading / heart-eye toggles. */
    private fun reviewedFaceOverlay(name: String, data: ByteArray): Int {
        if (name !in setOf("＞＜", "流泪", "黑脸", "脸红")) return 0
        return try {
            val parameters = JSONObject(String(data, Charsets.UTF_8)).getJSONArray("Parameters")
            val values = (0 until parameters.length()).map { index ->
                val parameter = parameters.getJSONObject(index)
                FacialEffectParameter(parameter.getString("Id"), parameter.getDouble("Value"), parameter.optString("Blend", "Add"))
            }
            PresentationPolicy.reviewedFaceOverlay(name, values)
        } catch (error: Exception) {
            Log.w(TAG, "automatic facial effect rejected: model=$modelDirName name=$name")
            0
        }
    }

    private fun remapCallGesture(original: ByteArray, rig: JSONObject, gestureId: String): ByteArray {
        val document = JSONObject(String(original, Charsets.UTF_8))
        val curves = document.getJSONArray("Curves")
        for (index in 0 until curves.length()) {
            val curve = curves.getJSONObject(index)
            check(curve.getString("Target") == "Parameter")
            val sourceId = curve.getString("Id")
            val channel = rig.getJSONObject(sourceId)
            val minimum = channel.getDouble("minimum")
            val center = channel.getDouble("default")
            val maximum = channel.getDouble("maximum")
            val negativeSource = channel.getDouble("sourceDefault") - channel.getDouble("sourceMinimum")
            val positiveSource = channel.getDouble("sourceMaximum") - channel.getDouble("sourceDefault")
            check(minimum <= center && center <= maximum && minimum < maximum)
            val segments = curve.getJSONArray("Segments")
            check((segments.length() - 2) % 7 == 0)
            var curvePeak = kotlin.math.abs(segments.getDouble(1))
            for (point in 2 until segments.length()) {
                if ((point - 2) % 7 in listOf(2, 4, 6))
                    curvePeak = maxOf(curvePeak, kotlin.math.abs(segments.getDouble(point)))
            }
            check(curvePeak > 0)
            for (point in 0 until segments.length()) {
                // Application motions contain only cubic marker + three time/value pairs.
                if (point == 1 || point >= 2 && (point - 2) % 7 in listOf(2, 4, 6)) {
                    val value = segments.getDouble(point)
                    check(value.isFinite())
                    if (value == 0.0) segments.put(point, center)
                    else {
                        val sourceSpan = if (value > 0) positiveSource else negativeSource
                        val targetSpan = if (value > 0) maximum - center else center - minimum
                        check(sourceSpan > 0 && targetSpan > 0) {
                            "Gesture direction unavailable: $sourceId -> ${channel.getString("id")}"
                        }
                        // The SDK numeric parser accepts plain decimals, not exponent notation.
                        val mapped = PresentationPolicy.mapGestureValue(value, curvePeak, minimum, center, maximum, gestureId, sourceId)
                        segments.put(point, BigDecimal.valueOf(mapped).setScale(6, RoundingMode.HALF_UP))
                    }
                } else if (point >= 2 && (point - 2) % 7 == 0) check(segments.getInt(point) == 1)
            }
            curve.put("Id", channel.getString("id"))
            Log.i(TAG, "gesture curve mapped: model=$modelDirName gesture=$gestureId output=$sourceId driver=${channel.getString("id")} peak_fraction=${PresentationPolicy.gesturePeakFraction(gestureId, sourceId)}")
        }
        // Cubism 5-r.4 ParseNumeric terminates at a comma/newline, not a closing ]/}.
        // Keep one value per line; JSONObject's compact form fails on the last array value.
        return (document.toString(2) + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun loadEmotionAliases(disk: File?, assetRoot: String) {
        // A model-local file may override packaged mappings for imported versions.
        // The model3 list is authoritative: no arbitrary paths or expression indices.
        val supplied = try {
            val file = disk?.let { File(it, "emotion_aliases.json") }?.takeIf { it.isFile }
            val text = file?.readText() ?: context.assets.open("$assetRoot/emotion_aliases.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(text)
        } catch (_: java.io.FileNotFoundException) { null }
          catch (error: Exception) {
            Log.w(TAG, "emotion aliases unavailable: model=$modelDirName", error); null
        }
        val blocked = mutableSetOf<String>()
        // These two resources are explicit toggles, never an initialization step.
        if (modelDirName == "ellen") blocked.add("shuiyin")
        if (modelDirName == "whale") blocked.add("加载")
        supplied?.optJSONArray("blocked_expressions")?.let { list ->
            for (index in 0 until list.length()) blocked.add(list.getString(index))
        }
        blockedExpressions = blocked
        val aliases = supplied?.optJSONObject("aliases")
        val resolved = linkedMapOf<String, String>()
        for (tag in listOf("neutral", "happy", "sad", "angry", "surprised", "thinking", "shy")) {
            val name = aliases?.optString(tag, "") ?: ""
            resolved[tag] = if (name.isEmpty() || name in expressionNames && name !in blocked) name else {
                Log.w(TAG, "emotion alias reset to neutral: model=$modelDirName tag=$tag missing=$name"); ""
            }
        }
        resolved["neutral"] = ""
        emotionAliases = resolved
        Log.i(TAG, "emotion aliases model=$modelDirName supported=${resolved.filterValues { it.isNotEmpty() }}")
    }

    private fun resolveExpression(request: String): String {
        val value = request.trim()
        if (value.isEmpty()) return ""
        if (value.startsWith("mobile:", ignoreCase = true)) {
            val incoming = value.substringAfter(':').lowercase(Locale.ROOT)
            val tag = when (incoming) {
                "joy", "smirk" -> "happy"
                "sadness" -> "sad"
                "anger", "disgust" -> "angry"
                "surprise", "fear" -> "surprised"
                else -> incoming
            }
            if (tag !in emotionAliases) Log.w(TAG, "unknown mobile emotion reset: $incoming")
            val name = emotionAliases[tag] ?: ""
            Log.i(TAG, "emotion mapped: model=$modelDirName tag=$tag expression=${name.ifEmpty { "neutral" }}")
            return name
        }
        // Legacy indices are only resolved inside the selected model's own ordered list.
        val index = value.toIntOrNull()
        val name = if (index != null) expressionNames.getOrNull(index) else value
        if (name == null || name !in expressionNames || name in blockedExpressions) {
            Log.w(TAG, "expression reset, invalid or blocked: model=$modelDirName request=$value")
            return ""
        }
        return name
    }

    private fun ensureShaders(): File {
        val directory = File(context.filesDir, "FrameworkShaders").apply { mkdirs() }
        val names = context.assets.list("shaders") ?: error("No Cubism shaders packaged")
        check(names.isNotEmpty()) { "Cubism shader directory empty" }
        for (name in names) context.assets.open("shaders/$name").use { input ->
            File(directory, name).outputStream().use { output -> input.copyTo(output) }
        }
        return directory
    }

    private external fun nativeInit(shaderDir: String)
    private external fun nativeSetup(moc: ByteArray, model3: ByteArray, physics: ByteArray, hiddenPart: String): Boolean
    private external fun nativeBindTexture(index: Int, rgba: ByteBuffer, width: Int, height: Int): Boolean
    private external fun nativeAddExpressionFile(name: String, data: ByteArray)
    private external fun nativeAddMotionFile(name: String, data: ByteArray, idle: Boolean): Boolean
    private external fun nativeSetExpression(name: String)
    private external fun nativeConfigureJsPerformance(names: Array<String>, bounds: FloatArray): Boolean
    private external fun nativeSetJsParameters(values: FloatArray): Boolean
    private external fun nativeJsDiagnostics(): String
    private external fun nativeClearExpression()
    private external fun nativeConfigureContinuousFace(whale: Boolean, reviewedOverlayMask: Int): Boolean
    private external fun nativeSetPresentationTransform(scale: Float, offsetX: Float, offsetY: Float)
    private external fun nativeSetEmotionWeights(happy: Float, sad: Float, angry: Float, surprised: Float, shy: Float)
    private external fun nativePrepareCallGestureRig(useWhale: Boolean): String
    private external fun nativeConfigureArmPoses(up: ByteArray, down: ByteArray): Boolean
    private external fun nativeRequestArmPose(raised: Boolean)
    private external fun nativeResetMotionState()
    private external fun nativePlayMotion(name: String)
    private external fun nativeSetDrag(x: Float, y: Float)
    private external fun nativeSetMouth(value: Float)
    private external fun nativeUpdate(delta: Float): Int
    private external fun nativeRenderFrame(pixels: ByteBuffer, width: Int, height: Int): Boolean
    private external fun nativeConfigureTouchRegions(whale: Boolean)
    private external fun nativeTouchBounds(width: Int, height: Int): FloatArray?
    private external fun nativeDrawSurface(width: Int, height: Int): Boolean
    private external fun nativeDispose()
}

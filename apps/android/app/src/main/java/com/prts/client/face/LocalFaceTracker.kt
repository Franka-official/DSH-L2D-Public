package com.prts.client.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/** Explicit foreground camera consumer, never a camera owner or uploader.
 * Camera owner must keep Image valid until onComplete and then close it. Exactly one completion
 * occurs per call, including rejected/busy frames. Results survive at most 3 seconds in memory.
 */
class LocalFaceTracker(context: Context, private val onNotice: (String) -> Unit) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "PRTS-Face")
    }
    private val gate = FaceFrameGate()
    private val budget = FaceWorkBudget()
    private val yuvWorkspace = Yuv420Sampler.Workspace() // worker only; never crosses the camera/SDK boundary
    private val filter = FaceObservationFilter(SystemClock::elapsedRealtime)
    private val lock = Any()
    private var task: FaceLandmarker? = null // worker only
    private var taskEpoch = -1L
    private var taskTimestamp = -1L
    private var modelBuffer: ByteBuffer? = null // one immutable direct buffer, retained for task life
    private var failedEpoch = -1L
    private var lastOptionsRotation = -1
    private var imageOptions: ImageProcessingOptions? = null // immutable; worker only
    private var offers = 0L
    private var admitted = 0L
    private var processed = 0L
    private var rateDrops = 0L
    private var busyDrops = 0L
    private var otherDrops = 0L
    private var processingTotalMs = 0.0
    private var processingPeakMs = 0.0
    private var lastStatsMs = 0L
    private var lastStatsMode = ""

    fun process(
        frame: Image,
        rotationDegrees: Int,
        frontCamera: Boolean,
        generation: Long,
        capturedElapsedMs: Long,
        onComplete: () -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        fun complete() { if (completed.compareAndSet(false, true)) onComplete() }
        val plan = budget.plan()
        val admission = synchronized(lock) {
            offers++
            gate.enter(frontCamera, generation, capturedElapsedMs, plan.intervalMs).also {
                if (it.clearObservation) filter.reset()
                if (it.ticket != null) admitted++ else when (it.dropReason) {
                    "rate" -> rateDrops++
                    "busy" -> busyDrops++
                    else -> otherDrops++
                }
            }
        }
        logStats(plan)
        val ticket = admission.ticket ?: run { complete(); return }
        try {
            worker.execute {
                var bitmap: Bitmap? = null
                try {
                    if (!gate.valid(ticket) || failedEpoch == ticket.epoch) return@execute
                    val frameStarted = SystemClock.elapsedRealtimeNanos()
                    require(rotationDegrees in setOf(0, 90, 180, 270))
                    val planes = frame.planes
                    require(frame.format == ImageFormat.YUV_420_888 && planes.size == 3)
                    val crop = frame.cropRect
                    require(crop.width() > 0 && crop.height() > 0 && frame.width <= 4096 && frame.height <= 4096)
                    val scale = plan.maxSide.toDouble() / max(crop.width(), crop.height()).coerceAtLeast(plan.maxSide)
                    val width = (crop.width() * scale).roundToInt().coerceIn(1, plan.maxSide)
                    val height = (crop.height() * scale).roundToInt().coerceIn(1, plan.maxSide)
                    val p = planes.map { Yuv420Sampler.Plane(it.buffer.duplicate(), it.rowStride, it.pixelStride) }
                    val pixels = Yuv420Sampler.argb(p[0], p[1], p[2], crop.left, crop.top,
                        crop.width(), crop.height(), width, height, yuvWorkspace)
                    bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                    val warmed = taskEpoch == ticket.epoch && task != null
                    val detector = ensureTask(ticket.epoch)
                    val timestamp = max(capturedElapsedMs, taskTimestamp + 1)
                    taskTimestamp = timestamp
                    val image = BitmapImageBuilder(bitmap).build()
                    val result = try {
                        detector.detectForVideo(image, optionsFor(rotationDegrees), timestamp)
                    } finally { image.close() }
                    if (!gate.valid(ticket)) return@execute
                    val count = result.faceLandmarks().size
                    val scores = result.faceBlendshapes().orElse(emptyList()).firstOrNull()
                        ?.associate { it.categoryName() to it.score().toDouble() } ?: emptyMap()
                    val matrix = result.facialTransformationMatrixes().orElse(emptyList()).firstOrNull()
                    synchronized(lock) {
                        if (gate.valid(ticket)) {
                            filter.accept(capturedElapsedMs, count, scores, matrix)
                            val elapsed = (SystemClock.elapsedRealtimeNanos() - frameStarted) / 1_000_000.0
                            processed++; processingTotalMs += elapsed
                            processingPeakMs = max(processingPeakMs, elapsed)
                            budget.recordProcessing(elapsed, warmed)
                        }
                    }
                    logStats(budget.plan())
                } catch (_: Exception) {
                    fail(ticket)
                } catch (_: LinkageError) {
                    fail(ticket)
                } finally {
                    bitmap?.takeUnless { it.isRecycled }?.recycle()
                    gate.release()
                    complete()
                }
            }
        } catch (_: RejectedExecutionException) {
            gate.release()
            complete()
        }
    }

    fun takeFreshObservation(maxAgeMs: Long = 3000): FaceObservation? = synchronized(lock) {
        if (gate.isClosed()) null else filter.snapshot(maxAgeMs)
    }

    /** Caller reports concurrent local CPU work, e.g. recording or local speech synthesis. */
    fun setCallBusy(busy: Boolean) {
        budget.setCallBusy(busy)
        logStats(budget.plan())
    }

    fun reset() = synchronized(lock) {
        gate.reset()
        filter.reset()
        budget.resetTiming()
    }

    private fun optionsFor(rotation: Int): ImageProcessingOptions {
        if (imageOptions == null || lastOptionsRotation != rotation) {
            imageOptions = ImageProcessingOptions.builder().setRotationDegrees(rotation).build()
            lastOptionsRotation = rotation
        }
        return imageOptions!!
    }

    private fun logStats(plan: FaceWorkBudget.Plan) {
        val message = synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (gate.isClosed() || (plan.mode == lastStatsMode && now - lastStatsMs < 10_000)) null else {
                lastStatsMode = plan.mode; lastStatsMs = now
                String.format(Locale.US, "capture budget mode=%s interval_ms=%d max_side=%d offers=%d admitted=%d processed=%d rate_drop=%d busy_drop=%d other_drop=%d processing_mean_ms=%.2f processing_peak_ms=%.2f",
                    plan.mode, plan.intervalMs, plan.maxSide, offers, admitted, processed,
                    rateDrops, busyDrops, otherDrops, if (processed > 0) processingTotalMs / processed else 0.0,
                    processingPeakMs)
            }
        }
        if (message != null) Log.i(TAG, message)
    }

    private fun fail(ticket: FaceFrameGate.Ticket) {
        synchronized(lock) { if (gate.valid(ticket)) filter.reset() }
        failedEpoch = ticket.epoch
        Log.w(TAG, "local detector unavailable; current observation cleared")
        main.post { if (gate.valid(ticket)) onNotice("本机面部观察暂不可用，通话和拍照仍可使用") }
    }

    private fun ensureTask(ticket: Long): FaceLandmarker {
        if (taskEpoch == ticket && task != null) return task!!
        task?.close(); task = null
        val buffer = modelBuffer ?: loadModelBuffer(app).also { modelBuffer = it }
        task = createDetector(app, buffer)
        taskEpoch = ticket
        taskTimestamp = -1
        Log.i(TAG, "local detector ready delegate=CPU max_faces=2 input_unmirrored=true snapshot_max_age_ms=3000")
        return task!!
    }

    override fun close() {
        synchronized(lock) { if (!gate.close()) return; filter.reset() }
        worker.execute { task?.close(); task = null; modelBuffer = null }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "PRTS-Face"
        const val MODEL_ASSET = "face/face_landmarker.task"
        internal fun loadModelBuffer(context: Context): ByteBuffer = context.assets.open(MODEL_ASSET).use { input ->
            val bytes = input.readBytes()
            require(bytes.size in 1..8_000_000)
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }
        internal fun createDetector(context: Context, buffer: ByteBuffer): FaceLandmarker {
            buffer.rewind()
            return FaceLandmarker.createFromOptions(context, FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(2) // detect ambiguous multi-face frames, then withhold metadata
                .setMinFaceDetectionConfidence(.6f).setMinFacePresenceConfidence(.6f)
                .setMinTrackingConfidence(.6f).setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(true).build())
        }
    }
}

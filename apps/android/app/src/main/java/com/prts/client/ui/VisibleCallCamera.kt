package com.prts.client.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.util.Range
import android.view.Surface
import android.view.TextureView
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Selection only; never promises a frame rate the camera did not advertise. */
internal object CallCameraBudget {
    fun dimensions(sizes: List<Pair<Int, Int>>, photo: Boolean): Pair<Int, Int>? {
        val bounded = sizes.filter { it.first in 320..1280 && it.second in 240..960 }
        val target = if (photo) 960 * 720 else 640 * 480
        return bounded.minWithOrNull(compareBy<Pair<Int, Int>> { kotlin.math.abs(it.first * it.second - target) }
            .thenBy { kotlin.math.abs(it.first.toDouble() / it.second - 4.0 / 3.0) })
    }

    fun previewFps(ranges: List<Pair<Int, Int>>): Pair<Int, Int>? = ranges
        .filter { it.first > 0 && it.second >= it.first && it.second <= 15 }
        .maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
}

/** Camera2 owns every image until the local detector invokes completion.
 * No frame from this class has a transport or file-system destination.
 * A JPEG is produced only after takePhoto(), never on tracking callbacks.
 */
class VisibleCallCamera(
    private val activity: Activity,
    val preview: TextureView,
    private val shouldObserve: () -> Boolean,
    private val observe: (Image, Int, Boolean, Long, Long, () -> Unit) -> Unit,
    private val onJpeg: (ByteArray, Int) -> Unit,
    private val onState: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("call-visible-camera").apply { start() }
    private val worker = Handler(thread.looper)
    private val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val generation = AtomicLong(0)
    private val inFlight = AtomicBoolean(false)
    private val photoRequested = AtomicBoolean(false)
    private val retiredReaders = mutableSetOf<ImageReader>()
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    private var streamSize = Size(640, 480)
    private var sensorRotation = 90
    @Volatile private var frameRotation = 90
    private var perfStartedMs = 0L
    private var acquiredFrames = 0L
    private var offeredFrames = 0L
    private var disabledDrops = 0L
    private var backpressureDrops = 0L
    private var photoFrames = 0L
    @Volatile private var visiblePreview = true
    @Volatile private var requested = false
    @Volatile private var closed = false
    @Volatile var frontCamera = true
        private set
    @Volatile var ready = false
        private set
    @Volatile var photoPreview = false
        private set
    val isRequested: Boolean get() = requested && !closed

    init {
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                if (requested && !closed) openRequested()
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = transformPreview()
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                if (visiblePreview) closeSession()
                return true
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    fun start(front: Boolean, photo: Boolean = false, visible: Boolean = true) {
        if (closed) return
        stop()
        requested = true
        frontCamera = front
        photoPreview = photo
        visiblePreview = visible
        onState(if (front) "正在打开前置相机" else "正在打开后置相机")
        if (!visible || preview.isAvailable) openRequested()
    }

    fun stop() {
        requested = false
        photoRequested.set(false)
        closeSession()
    }

    private fun closeSession() {
        generation.incrementAndGet()
        ready = false
        worker.post {
            runCatching { session?.stopRepeating() }
            runCatching { session?.abortCaptures() }
            runCatching { session?.close() }; session = null
            runCatching { device?.close() }; device = null
            val old = reader; reader = null
            old?.setOnImageAvailableListener(null, null)
            if (old != null) synchronized(retiredReaders) {
                if (inFlight.get()) retiredReaders.add(old) else old.close()
            }
            surface?.release(); surface = null
        }
    }

    private fun openRequested() {
        if (!requested || closed || (visiblePreview && !preview.isAvailable)) return
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stop(); onError("相机权限未开启"); return
        }
        val ticket = generation.incrementAndGet()
        val front = frontCamera
        val texture = if (visiblePreview) preview.surfaceTexture ?: return else null
        worker.post {
            try {
                if (!valid(ticket)) return@post
                val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                val id = manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
                } ?: error(if (front) "没有可用前置相机" else "没有可用后置相机")
                val characteristics = manager.getCameraCharacteristics(id)
                sensorRotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val configurations = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: error("No camera stream configurations")
                val previewSizes = configurations.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
                val sizes = configurations.getOutputSizes(ImageFormat.YUV_420_888)?.filter { texture == null || it in previewSizes }.orEmpty()
                val dimensions = CallCameraBudget.dimensions(sizes.map { it.width to it.height }, photoPreview)
                    ?: error("No bounded camera stream")
                streamSize = Size(dimensions.first, dimensions.second)
                frameRotation = rotationDegrees(front)
                val fps = CallCameraBudget.previewFps(characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.map { it.lower to it.upper }.orEmpty())
                val autofocus = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
                val autofocusMode = if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in autofocus)
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CaptureRequest.CONTROL_AF_MODE_OFF
                val target = texture?.let {
                    it.setDefaultBufferSize(streamSize.width, streamSize.height)
                    Surface(it)
                }
                surface = target
                val images = ImageReader.newInstance(streamSize.width, streamSize.height, ImageFormat.YUV_420_888, 2)
                reader = images
                images.setOnImageAvailableListener({ source -> consume(source, ticket, front) }, worker)
                main.post { if (valid(ticket)) transformPreview() }
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!valid(ticket)) { camera.close(); return }
                        device = camera
                        camera.createCaptureSession(listOfNotNull(target, images.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(capture: CameraCaptureSession) {
                                if (!valid(ticket)) { capture.close(); return }
                                session = capture
                                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    if (target != null) addTarget(target)
                                    addTarget(images.surface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AF_MODE, autofocusMode)
                                    if (fps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps.first, fps.second))
                                }.build()
                                try {
                                    capture.setRepeatingRequest(request, null, worker)
                                    ready = true
                                    perfStartedMs = SystemClock.elapsedRealtime()
                                    acquiredFrames = 0; offeredFrames = 0; disabledDrops = 0; backpressureDrops = 0; photoFrames = 0
                                    main.post { if (valid(ticket)) onState(if (target == null) "本机面部观察" else if (front) "前置预览" else "后置预览") }
                                    Log.i("PRTS-Camera", "camera session started visible=${target != null} front=$front photo=$photoPreview width=${streamSize.width} height=${streamSize.height} advertised_ae_fps=${fps?.let { "${it.first}-${it.second}" } ?: "default"}")
                                } catch (_: Exception) { fail(ticket, "相机预览无法启动，请重新开启") }
                            }
                            override fun onConfigureFailed(capture: CameraCaptureSession) = fail(ticket, "当前相机不支持这个预览，请重试")
                        }, worker)
                    }
                    override fun onDisconnected(camera: CameraDevice) { camera.close(); fail(ticket, "相机已断开") }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close(); fail(ticket, "相机暂时不可用") }
                }, worker)
            } catch (_: Exception) { fail(ticket, "无法打开相机，请检查权限或稍后再试") }
        }
    }

    private fun consume(source: ImageReader, ticket: Long, front: Boolean) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        acquiredFrames++
        reportCameraPerf()
        if (!valid(ticket)) { image.close(); return }
        // The detector alone owns 3 Hz / busy 1 Hz admission. Do not add a second
        // camera time gate, which could halve useful cadence at boundary times.
        if (!photoRequested.get() && (!front || !shouldObserve())) { disabledDrops++; image.close(); return }
        if (!inFlight.compareAndSet(false, true)) { backpressureDrops++; image.close(); return }
        val returned = AtomicBoolean(false)
        val complete = {
            if (returned.compareAndSet(false, true)) {
                runCatching { image.close() }
                synchronized(retiredReaders) {
                    retiredReaders.forEach { runCatching { it.close() } }
                    retiredReaders.clear()
                    // Do not admit a new reader's Image until the old reader cleanup ends.
                    inFlight.set(false)
                }
            }
            Unit
        }
        val captured = SystemClock.elapsedRealtime()
        val rotation = frameRotation
        if (photoRequested.compareAndSet(true, false)) {
            try {
                val nv21 = toNv21(image)
                val crop = image.cropRect
                val output = ByteArrayOutputStream()
                check(YuvImage(nv21, ImageFormat.NV21, crop.width(), crop.height(), null)
                    .compressToJpeg(Rect(0, 0, crop.width(), crop.height()), 92, output))
                val jpeg = output.toByteArray()
                photoFrames++
                main.post { if (valid(ticket)) onJpeg(jpeg, rotation) }
            } catch (_: Exception) { main.post { if (valid(ticket)) onError("照片没有拍好，请重新拍摄") } }
            finally { complete() }
            return
        }
        if (!front || !shouldObserve()) { disabledDrops++; complete(); return }
        offeredFrames++
        try { observe(image, rotation, front, ticket, captured, complete) }
        catch (_: Exception) { complete() }
    }

    fun takePhoto(): Boolean {
        if (!ready || !requested || closed || !photoRequested.compareAndSet(false, true)) return false
        val ticket = generation.get()
        main.postDelayed({
            if (valid(ticket) && photoRequested.compareAndSet(true, false)) onError("没有收到照片，请重新拍摄")
        }, 3500)
        return true
    }

    private fun valid(ticket: Long) = requested && !closed && ticket == generation.get()

    private fun reportCameraPerf() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - perfStartedMs
        if (perfStartedMs == 0L || elapsed < 10_000) return
        Log.i("PRTS-CameraPerf", "window_ms=$elapsed acquired=$acquiredFrames face_offers=$offeredFrames disabled_drops=$disabledDrops backpressure_drops=$backpressureDrops photos=$photoFrames width=${streamSize.width} height=${streamSize.height} offered_not_processed=true")
        perfStartedMs = now
        acquiredFrames = 0; offeredFrames = 0; disabledDrops = 0; backpressureDrops = 0; photoFrames = 0
    }

    private fun fail(ticket: Long, message: String) {
        main.post { if (valid(ticket)) { stop(); onError(message) } }
    }

    @Suppress("DEPRECATION")
    private fun rotationDegrees(front: Boolean): Int {
        val device = when (activity.windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
        }
        return if (front) (sensorRotation + device) % 360 else (sensorRotation - device + 360) % 360
    }

    @Suppress("DEPRECATION")
    private fun transformPreview() {
        if (preview.width == 0 || preview.height == 0) return
        // TextureView accounts for sensor orientation; correct aspect/device rotation here.
        val rotated = rotationDegrees(frontCamera) % 180 != 0
        frameRotation = rotationDegrees(frontCamera)
        val bufferWidth = if (rotated) streamSize.height else streamSize.width
        val bufferHeight = if (rotated) streamSize.width else streamSize.height
        val viewAspect = preview.width.toFloat() / preview.height
        val bufferAspect = bufferWidth.toFloat() / bufferHeight
        val cx = preview.width / 2f; val cy = preview.height / 2f
        val matrix = Matrix()
        if (bufferAspect > viewAspect) matrix.postScale(bufferAspect / viewAspect, 1f, cx, cy)
        else matrix.postScale(1f, viewAspect / bufferAspect, cx, cy)
        val displayRotation = activity.windowManager.defaultDisplay.rotation
        if (displayRotation == Surface.ROTATION_90) matrix.postRotate(-90f, cx, cy)
        if (displayRotation == Surface.ROTATION_180) matrix.postRotate(180f, cx, cy)
        if (displayRotation == Surface.ROTATION_270) matrix.postRotate(90f, cx, cy)
        preview.setTransform(matrix)
    }

    fun close() {
        if (closed) return
        stop(); closed = true
        worker.post { thread.quitSafely() }
    }

    companion object {
        internal fun toNv21(image: Image): ByteArray {
            val crop = image.cropRect
            require(image.format == ImageFormat.YUV_420_888 && crop.width() % 2 == 0 && crop.height() % 2 == 0
                && crop.left % 2 == 0 && crop.top % 2 == 0)
            val width = crop.width(); val height = crop.height()
            val result = ByteArray(width * height * 3 / 2)
            val planes = image.planes
            fun sample(plane: Image.Plane, x: Int, y: Int): Byte =
                plane.buffer.get(plane.buffer.position() + y * plane.rowStride + x * plane.pixelStride)
            for (y in 0 until height) for (x in 0 until width)
                result[y * width + x] = sample(planes[0], crop.left + x, crop.top + y)
            var offset = width * height
            for (y in 0 until height / 2) for (x in 0 until width / 2) {
                result[offset++] = sample(planes[2], crop.left / 2 + x, crop.top / 2 + y)
                result[offset++] = sample(planes[1], crop.left / 2 + x, crop.top / 2 + y)
            }
            return result
        }
    }
}

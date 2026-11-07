package com.prts.client.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.prts.client.R
import com.prts.client.face.LocalFaceTracker
import com.prts.client.face.FaceObservation

/** A prepared image is a local draft, never permission to send it. */
data class PreparedCallImage(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val text: String = "",
    val mimeType: String = "image/jpeg",
)

data class CallCameraUi(
    val card: View,
    val host: FrameLayout,
    val status: TextView,
    val tracking: SwitchCompat,
    val photo: View,
    val flip: View,
    val close: View,
)

class CallCameraController(
    private val activity: AppCompatActivity,
    private val onImagePrepared: (PreparedCallImage) -> Unit,
    private val onNotice: (String) -> Unit,
    private val onBeforeExternalActivity: () -> Unit = {},
    private val ui: CallCameraUi,
) : DefaultLifecycleObserver {
    var externalActivityPending = false
        private set
    private val directory = File(activity.cacheDir, "call-camera").apply { mkdirs() }
    private var processing = false
    private var capturePending = false
    private var dialog: BottomSheetDialog? = null
    private var album: BottomSheetDialog? = null
    private val preferences = activity.getSharedPreferences("prts", android.content.Context.MODE_PRIVATE)
    private var observationPermissionPrompted = preferences.getBoolean("call_observation_permission_prompted", false)
    private var wantsObservation = preferences.getBoolean("call_local_face_observation", true)
    private val photoAlbum = CallPhotoAlbum(activity)
    @Volatile private var foreground = false
    @Volatile private var trackingEnabled = false
    private var reflectingToggle = false
    @Volatile private var front = true
    @Volatile private var tracker: LocalFaceTracker? = null
    @Volatile private var callBusy = false
    @Volatile private var destroyed = false
    val isPhotoProcessing: Boolean get() = processing || capturePending
    val isPhotoInteractionPending: Boolean get() = isPhotoProcessing || externalActivityPending || dialog != null || album != null
    private val photoWorker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), { job ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); job.run() }, "call-photo-worker")
        })
    private var permissionAction: String? = null
    private var grantedAction: String? = null
    private var panelHost: FrameLayout? = null
    private var panelStatus: TextView? = null
    private val preview = TextureView(activity).apply { contentDescription = "可见相机预览，仅在本机处理" }
    private val visibleCamera = VisibleCallCamera(activity, preview,
        shouldObserve = { trackingEnabled && foreground && front },
        observe = { image, rotation, isFront, generation, at, complete ->
            val local = tracker
            if (trackingEnabled && foreground && local != null)
                local.process(image, rotation, isFront, generation, at, complete)
            else complete()
        },
        onJpeg = { bytes, rotation ->
            capturePending = false
            submitPhoto { decodeCapture(bytes, rotation) }
        },
        onState = { state -> ui.status.text = state; panelStatus?.text = state },
        onError = ::onCameraError,
    )
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        externalActivityPending = false
        val action = permissionAction; permissionAction = null
        if (granted && action != null) {
            if (foreground) completePermissionAction(action) else grantedAction = action
        } else {
            disableTracking()
            onNotice("相机权限未开启，仍可从相册选择图片")
        }
    }
    private var reopenAlbum = false
    private val albumPermission = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        externalActivityPending = false
        if (foreground) openPicker() else reopenAlbum = true
    }
    private val pickImage = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        externalActivityPending = false
        if (uri != null) importImage(uri)
    }

    init {
        ui.card.visibility = View.GONE
        ui.tracking.isChecked = wantsObservation
        ui.tracking.contentDescription = "本机面部观察开关"
        ui.tracking.setOnCheckedChangeListener { _, enabled ->
            if (!reflectingToggle) {
                wantsObservation = enabled
                preferences.edit().putBoolean("call_local_face_observation", enabled).apply()
                if (enabled) requestCamera("tracking") else {
                    disableTracking()
                    if (dialog == null) stopPreview()
                }
            }
        }
        ui.photo.setOnClickListener { openPhotoPanel() }
        ui.flip.setOnClickListener { openPhotoPanel(startFront = !front) }
        ui.close.setOnClickListener { disableTracking(); dialog?.dismiss(); stopPreview() }
        activity.lifecycle.addObserver(this)
    }

    fun openPicker() {
        if (processing || capturePending) { onNotice("照片正在准备，请稍等"); return }
        if (externalActivityPending) return
        if (activity.isFinishing || activity.isDestroyed) return
        if (album != null || dialog != null) return
        album = photoAlbum.open(
            onCamera = { requestCamera("photo") },
            onPhoto = ::importImage,
            onSystemPicker = ::launchPicker,
            onRequestAccess = {
                beginExternalActivity()
                try { albumPermission.launch(photoAlbum.permissions()) }
                catch (_: Exception) { externalActivityPending = false; launchPicker() }
            },
            onDismiss = { album = null },
        )
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) {
            disableTracking(); album?.dismiss(); dialog?.dismiss(); stopPreview()
        } else {
            val granted = grantedAction
            grantedAction = null
            if (granted != null) completePermissionAction(granted)
            else if (wantsObservation && !externalActivityPending) {
                val hasPermission = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) completePermissionAction("tracking")
                else if (!observationPermissionPrompted) {
                    observationPermissionPrompted = true
                    preferences.edit().putBoolean("call_observation_permission_prompted", true).apply()
                    requestCamera("tracking")
                }
            }
            if (reopenAlbum && !externalActivityPending) { reopenAlbum = false; openPicker() }
        }
    }

    fun takeFreshObservation(maxAgeMs: Long = 3000): FaceObservation? =
        if (foreground && trackingEnabled && front && visibleCamera.ready)
            tracker?.takeFreshObservation(maxAgeMs) else null

    fun setCallBusy(value: Boolean) {
        if (callBusy == value) return
        callBusy = value
        tracker?.setCallBusy(value)
    }

    private fun onCameraError(text: String) {
        // Only the photo worker completion owns `processing`; a camera failure
        // cannot make a still-running decode look idle or admit a second job.
        capturePending = false
        ui.status.text = text; panelStatus?.text = text
        if (!visibleCamera.ready) { disableTracking(); stopPreview() }
        onNotice(text)
    }

    private fun disableTracking() {
        trackingEnabled = false
        tracker?.reset()
        tracker?.close(); tracker = null
        reflectingToggle = true; ui.tracking.isChecked = false; reflectingToggle = false
        ui.tracking.contentDescription = "本机面部观察开关，已关闭"
    }

    private fun requestCamera(action: String) {
        if (!foreground || activity.isFinishing || activity.isDestroyed) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            completePermissionAction(action)
        else {
            permissionAction = action
            beginExternalActivity()
            try { permission.launch(Manifest.permission.CAMERA) }
            catch (_: Exception) { externalActivityPending = false; permissionAction = null; disableTracking(); onNotice("无法请求相机权限") }
        }
    }

    private fun completePermissionAction(action: String) {
        if (!foreground) return
        if (action == "tracking") {
            if (!wantsObservation || trackingEnabled) return
            tracker = tracker ?: LocalFaceTracker(activity, onNotice)
            tracker?.setCallBusy(callBusy)
            trackingEnabled = true
            reflectingToggle = true; ui.tracking.isChecked = true; reflectingToggle = false
            ui.tracking.contentDescription = "本机面部观察，前置相机已开启"
            if (dialog == null) startObservation()
            onNotice("前置相机开启 · 本机面部观察")
        } else openPhotoPanel()
    }

    private fun startObservation() {
        if (!foreground || !trackingEnabled) return
        visibleCamera.stop()
        tracker?.reset()
        (preview.parent as? ViewGroup)?.removeView(preview)
        front = true
        ui.card.visibility = View.GONE
        visibleCamera.start(front = true, visible = false)
    }

    private fun startPreview(host: FrameLayout, useFront: Boolean) {
        val photo = host !== ui.host
        if (preview.parent === host && front == useFront && visibleCamera.isRequested && visibleCamera.photoPreview == photo) return
        visibleCamera.stop()
        tracker?.reset()
        front = useFront
        (preview.parent as? ViewGroup)?.removeView(preview)
        host.addView(preview, FrameLayout.LayoutParams(-1, -1))
        ui.card.visibility = if (host === ui.host) View.VISIBLE else View.GONE
        visibleCamera.start(useFront, photo)
    }

    private fun stopPreview() {
        visibleCamera.stop(); tracker?.reset()
        capturePending = false
        ui.card.visibility = View.GONE
        ui.status.text = "相机已关闭"
    }

    private fun openPhotoPanel(startFront: Boolean = front) {
        if (!foreground || processing || capturePending || externalActivityPending) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCamera("photo"); return
        }
        if (dialog != null) return
        val sheet = BottomSheetDialog(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
            setBackgroundResource(R.drawable.ui_sheet_surface)
        }
        content.addView(TextView(activity).apply {
            text = "拍照后可加上文字，一起发送"; textSize = 18f
            setTextColor(ContextCompat.getColor(activity, R.color.ui_text))
        })
        val host = FrameLayout(activity).apply { setBackgroundColor(0xff101827.toInt()) }
        panelHost = host
        content.addView(host, LinearLayout.LayoutParams(-1, minOf(dp(320), activity.resources.displayMetrics.heightPixels / 3)).apply { topMargin = dp(14) })
        panelStatus = TextView(activity).apply {
            text = "预览只在本机，拍摄不会自动发送"; textSize = 11f
            setPadding(0, dp(10), 0, dp(10)); gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.ui_text_secondary))
        }
        content.addView(panelStatus)
        fun button(label: String, description: String, block: () -> Unit): TextView = TextView(activity).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER; contentDescription = description
            setTextColor(ContextCompat.getColor(activity, R.color.ui_text)); setBackgroundResource(R.drawable.ui_chip)
            setPadding(dp(9), dp(14), dp(9), dp(14)); isClickable = true; isFocusable = true
            setOnClickListener { block() }
        }
        content.addView(button("拍照，加入输入区", "拍摄照片加入草稿，不自动发送") {
            if (capturePending || processing) onNotice("照片正在准备，请稍等")
            else if (visibleCamera.takePhoto()) {
                capturePending = true
                panelStatus?.text = "正在拍摄，仅加入输入草稿"
            }
            else onNotice("相机还在准备，请稍等")
        }, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("切换前后摄像头", "切换前置或后置摄像头") {
            startPreview(host, !front)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        row.addView(button("从相册选择", "从相册选择照片加入草稿") { sheet.dismiss(); launchPicker() }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        sheet.setContentView(ScrollView(activity).apply { addView(content) })
        dialog = sheet
        sheet.setOnDismissListener {
            dialog = null; panelHost = null; panelStatus = null
            if (foreground && trackingEnabled) startObservation()
            else {
                stopPreview()
                if (foreground && wantsObservation &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    completePermissionAction("tracking")
            }
        }
        sheet.setOnShowListener {
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            startPreview(host, startFront)
        }
        sheet.show()
    }

    private fun beginExternalActivity() {
        // Set this before launch: onPause may run before a result callback returns.
        externalActivityPending = true
        onBeforeExternalActivity()
    }

    private fun launchPicker() {
        disableTracking(); stopPreview()
        beginExternalActivity()
        try { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: Exception) {
            externalActivityPending = false
            onNotice("无法打开系统相册，请稍后再试")
        }
    }

    private fun importImage(uri: Uri) {
        submitPhoto {
            val file = File(directory, "picked-${UUID.randomUUID()}.jpg")
            try {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        val block = ByteArray(16384)
                        var total = 0L
                        while (true) {
                            val count = input.read(block)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_SOURCE_BYTES)
                            output.write(block, 0, count)
                        }
                    }
                } ?: error("unreadable image")
                decodeAndCompress(file)
            } finally { file.delete() }
        }
    }

    private fun submitPhoto(work: () -> PreparedCallImage) {
        if (processing || destroyed) return
        processing = true
        onNotice("正在准备照片")
        try {
            photoWorker.execute {
                val started = android.os.SystemClock.elapsedRealtime()
                val result = runCatching { work() }
                android.util.Log.i("PRTS-Camera", "photo prepared processing_ms=${android.os.SystemClock.elapsedRealtime() - started} success=${result.isSuccess} worker=single")
                activity.runOnUiThread {
                    processing = false
                    if (!destroyed) showResult(result)
                }
            }
        } catch (_: RejectedExecutionException) {
            processing = false
            if (!destroyed) onNotice("照片处理尚未结束，请稍后再试")
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        var sample = 1
        while (maxOf(width, height) / sample > MAX_EDGE * 2) sample *= 2
        return sample
    }

    private fun decodeCapture(bytes: ByteArray, rotationDegrees: Int): PreparedCallImage {
        require(bytes.size.toLong() in 1..MAX_SOURCE_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("image decode failed")
        return transformAndCompress(bitmap, Matrix().apply { setRotate(rotationDegrees.toFloat()) })
    }

    private fun decodeAndCompress(file: File): PreparedCallImage {
        require(file.length() in 1..MAX_SOURCE_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("image decode failed")
        val orientation = runCatching {
                ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
        }
        return transformAndCompress(bitmap, transform)
    }

    private fun transformAndCompress(initial: Bitmap, transform: Matrix): PreparedCallImage {
        var bitmap = initial
        try {
            val scale = (MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
            if (scale < 1f) transform.postScale(scale, scale)
            if (!transform.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
                if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
            }
            var quality = 88
            val output = ByteArrayOutputStream()
            repeat(12) {
                output.reset()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                if (output.size() <= MAX_JPEG_BYTES) return PreparedCallImage(output.toByteArray(), bitmap.width, bitmap.height)
                if (quality > 58) quality -= 10 else {
                    val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width * .8f).toInt().coerceAtLeast(1), (bitmap.height * .8f).toInt().coerceAtLeast(1), true)
                    bitmap.recycle(); bitmap = smaller
                }
            }
            error("image too large")
        } finally { bitmap.recycle() }
    }

    private fun showResult(result: Result<PreparedCallImage>) {
        if (activity.isFinishing || activity.isDestroyed) return
        result.onSuccess { image ->
            dialog?.dismiss()
            onImagePrepared(image)
            android.util.Log.i("PRTS-Camera", "image draft prepared width=${image.width} height=${image.height} bytes=${image.jpeg.size}; not sent")
            onNotice("照片已放入输入区，可加上文字再发送")
        }.onFailure { onNotice("照片无法读取或过大，请换一张图片") }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        album?.dismiss()
        photoAlbum.close()
        dialog?.dismiss()
        dialog = null
        disableTracking()
        visibleCamera.close()
        photoWorker.shutdownNow()
        activity.lifecycle.removeObserver(this)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_EDGE = 1600
        private const val MAX_JPEG_BYTES = 700 * 1024
        private const val MAX_SOURCE_BYTES = 40L * 1024 * 1024
    }
}

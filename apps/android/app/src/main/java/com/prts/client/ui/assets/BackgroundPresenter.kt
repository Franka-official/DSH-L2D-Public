package com.prts.client.ui.assets

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.prts.client.R
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One decode in flight and only the newest pending background choice. UI methods run on main. */
class BackgroundPresenter(context: Context, private val image: ImageView) {
    private val ctx = context.applicationContext
    private val library = BackgroundLibrary(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    @Volatile private var closed = false
    private var foreground = true
    private var item: BgItem? = null
    private var options = BackgroundOptions()
    private var selectedRequest: String? = null
    private var selectedLoaded = false
    private var previewRevision = 0
    private var video: TextureView? = null
    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var failedVideo: String? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), { job -> Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); job.run()
        }, "call-background") }, ThreadPoolExecutor.DiscardOldestPolicy())
    private val onLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPlacement() }

    init {
        image.scaleType = ImageView.ScaleType.MATRIX
        image.addOnLayoutChangeListener(onLayout)
        applyPlacement()
    }

    fun showSelected() {
        val prefs = ctx.getSharedPreferences("prts", Context.MODE_PRIVATE)
        val id = prefs.getString("bg", null)
        val current = item
        if (selectedLoaded && id == selectedRequest && current != null) {
            preview(library.options(current))
            return
        }
        load({
            val migrated = library.migratedSelection(id)
            if (migrated != id && prefs.getString("bg", null) == id) prefs.edit().putString("bg", migrated).apply()
            library.selected(migrated)
        }, selected = true, requestId = id)
    }

    fun show(item: BgItem) = load({ item })

    private fun load(resolve: () -> BgItem, selected: Boolean = false, requestId: String? = null) {
        if (closed) return
        val ticket = generation.incrementAndGet()
        val revision = previewRevision
        selectedLoaded = false
        item = null
        failedVideo = null
        stopVideo()
        val width = image.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        val height = image.height.takeIf { it > 0 } ?: ctx.resources.displayMetrics.heightPixels
        worker.execute {
            val chosen = resolve()
            val state = library.options(chosen)
            val bitmap = library.loadBitmap(chosen, width, height)
                ?: library.loadBitmap(library.selected(null), width, height)
            main.post {
                if (closed || generation.get() != ticket) { bitmap?.recycle(); return@post }
                item = chosen
                selectedRequest = requestId
                selectedLoaded = selected
                if (previewRevision == revision) options = state
                if (bitmap != null) image.setImageBitmap(bitmap) else image.setImageResource(R.drawable.ui_bg_aurora)
                image.visibility = View.VISIBLE
                applyPlacement()
                showVideo()
            }
        }
    }

    fun preview(value: BackgroundOptions) {
        if (closed) return
        previewRevision++
        options = value
        applyPlacement()
        if (prepared) player?.let(::applySound)
    }

    fun setForeground(value: Boolean) {
        if (closed || foreground == value) return
        foreground = value
        if (value) showVideo() else stopVideo()
    }

    private fun applyPlacement() {
        val drawable = image.drawable
        if (drawable != null) options.placement.transform(image.width, image.height,
            drawable.intrinsicWidth, drawable.intrinsicHeight)?.let { frame ->
            image.imageMatrix = Matrix().apply {
                setScale(frame.width / drawable.intrinsicWidth, frame.height / drawable.intrinsicHeight)
                postTranslate(frame.left, frame.top)
            }
        }
        val view = video ?: return
        options.placement.transform(view.width, view.height, videoWidth, videoHeight)?.let { frame ->
            view.setTransform(Matrix().apply {
                setScale(frame.width / view.width, frame.height / view.height)
                postTranslate(frame.left, frame.top)
            })
        }
    }

    private fun showVideo() {
        val chosen = item?.takeIf { it.video && it.id != failedVideo } ?: return
        val parent = image.parent as? FrameLayout ?: return
        if (!foreground || closed || video != null) return
        val view = TextureView(image.context).apply {
            isOpaque = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        video = view
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                if (view === video && foreground && !closed) prepareVideo(chosen, texture)
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = applyPlacement()
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                if (view === video) { releasePlayer(); image.visibility = View.VISIBLE }
                return true
            }
        }
        parent.addView(view, parent.indexOfChild(image) + 1, FrameLayout.LayoutParams(-1, -1))
    }

    private fun prepareVideo(chosen: BgItem, texture: SurfaceTexture) {
        releasePlayer()
        try {
            val media = MediaPlayer()
            player = media
            surface = Surface(texture)
            media.setSurface(surface)
            media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            File(chosen.path.removePrefix("file://")).inputStream().use { media.setDataSource(it.fd) }
            media.setOnVideoSizeChangedListener { source, width, height ->
                if (player === source) { videoWidth = width; videoHeight = height; applyPlacement() }
            }
            media.setOnInfoListener { source, what, _ ->
                if (player === source && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) image.visibility = View.INVISIBLE
                false
            }
            media.setOnErrorListener { source, _, _ ->
                if (player === source) videoError(chosen)
                true
            }
            media.setOnPreparedListener { source ->
                if (player === source) try {
                    prepared = true
                    source.isLooping = true
                    applySound(source)
                    videoWidth = source.videoWidth
                    videoHeight = source.videoHeight
                    applyPlacement()
                    source.start()
                } catch (_: Exception) { videoError(chosen) }
            }
            media.prepareAsync()
        } catch (_: Exception) { videoError(chosen) }
    }

    private fun applySound(media: MediaPlayer) {
        val volume = if (options.sound) 1f else 0f
        media.setVolume(volume, volume)
    }

    private fun videoError(chosen: BgItem) {
        failedVideo = chosen.id
        Log.w("PRTS-Background", "Video background playback failed; displaying poster")
        stopVideo()
    }

    private fun releasePlayer() {
        val old = player
        player = null
        prepared = false
        old?.release()
        surface?.release()
        surface = null
        videoWidth = 0
        videoHeight = 0
    }

    private fun stopVideo() {
        releasePlayer()
        val view = video
        video = null
        if (view != null) {
            view.surfaceTextureListener = null
            (view.parent as? FrameLayout)?.removeView(view)
        }
        image.visibility = View.VISIBLE
    }

    fun close() {
        if (closed) return
        closed = true
        generation.incrementAndGet()
        stopVideo()
        image.removeOnLayoutChangeListener(onLayout)
        worker.shutdownNow()
    }
}

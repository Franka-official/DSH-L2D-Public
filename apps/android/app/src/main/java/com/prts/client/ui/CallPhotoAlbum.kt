package com.prts.client.ui

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.prts.client.R
import java.util.concurrent.Executors

internal class CallPhotoAlbum(private val activity: AppCompatActivity) {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "call-album")
    }

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES,
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasAccess() = permissions().any {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    fun open(onCamera: () -> Unit, onPhoto: (Uri) -> Unit, onSystemPicker: () -> Unit,
             onRequestAccess: () -> Unit, onDismiss: () -> Unit): BottomSheetDialog {
        val sheet = BottomSheetDialog(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
            setBackgroundResource(R.drawable.ui_sheet_surface)
        }
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(rows) }
        content.addView(TextView(activity).apply {
            text = "相册"; textSize = 18f
            setTextColor(ContextCompat.getColor(activity, R.color.ui_text))
        })
        content.addView(scroll, LinearLayout.LayoutParams(-1,
            minOf(dp(320), activity.resources.displayMetrics.heightPixels / 2)))
        val tiles = mutableListOf<View>()
        fun renderRows() {
            rows.removeAllViews()
            tiles.chunked(3).forEach { group ->
                val row = LinearLayout(activity)
                repeat(3) { index ->
                    val tile = group.getOrNull(index) ?: View(activity)
                    row.addView(tile, LinearLayout.LayoutParams(0, dp(92), 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                }
                rows.addView(row)
            }
        }
        fun button(label: String, action: () -> Unit) = TextView(activity).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER
            contentDescription = label
            setTextColor(ContextCompat.getColor(activity, R.color.ui_text))
            setBackgroundResource(R.drawable.ui_chip)
            isFocusable = true
            setOnClickListener { sheet.dismiss(); action() }
        }
        tiles.add(button("拍照", onCamera))
        renderRows()
        val canRead = hasAccess()
        if (!canRead) content.addView(button("显示相册照片", onRequestAccess),
            LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(button("系统相册", onSystemPicker), LinearLayout.LayoutParams(-1, dp(44)))
        sheet.setContentView(content)
        var job: java.util.concurrent.Future<*>? = null
        sheet.setOnDismissListener { job?.cancel(true); onDismiss() }
        sheet.show()
        if (canRead) job = worker.submit {
            val images = recentImages()
            val thumbs = images.map { (id, uri) ->
                if (Thread.currentThread().isInterrupted) return@submit
                uri to thumbnail(id, uri)
            }
            activity.runOnUiThread {
                if (!sheet.isShowing) return@runOnUiThread
                thumbs.forEachIndexed { index, (uri, bitmap) ->
                    val item = FrameLayout(activity).apply {
                        setBackgroundResource(R.drawable.ui_chip)
                        contentDescription = "照片 ${index + 1}"
                        isFocusable = true
                        setOnClickListener { sheet.dismiss(); onPhoto(uri) }
                    }
                    if (bitmap != null) item.addView(ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(bitmap)
                    }, FrameLayout.LayoutParams(-1, -1))
                    else item.addView(TextView(activity).apply {
                        text = "照片 ${index + 1}"; gravity = Gravity.CENTER
                        setTextColor(ContextCompat.getColor(activity, R.color.ui_text))
                    }, FrameLayout.LayoutParams(-1, -1))
                    tiles.add(item)
                }
                renderRows()
            }
        }
        return sheet
    }

    private fun recentImages(): List<Pair<Long, Uri>> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return runCatching {
            activity.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), null,
                null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                buildList {
                    while (size < 24 && cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        add(id to ContentUris.withAppendedId(collection, id))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    @Suppress("DEPRECATION")
    private fun thumbnail(id: Long, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) activity.contentResolver.loadThumbnail(uri, Size(192, 192), null)
        else MediaStore.Images.Thumbnails.getThumbnail(activity.contentResolver, id,
            MediaStore.Images.Thumbnails.MINI_KIND, null)
    }.getOrNull()

    fun close() { worker.shutdownNow() }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}

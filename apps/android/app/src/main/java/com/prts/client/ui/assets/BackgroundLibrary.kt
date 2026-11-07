package com.prts.client.ui.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.net.Uri
import androidx.core.content.ContextCompat
import com.prts.client.R
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

data class BgItem(val id: String, val name: String, val path: String, val builtin: Boolean, val video: Boolean = false)

/** Local backgrounds. Caller imports/decodes off the UI thread. */
class BackgroundLibrary(context: Context) {
    private val ctx = context.applicationContext
    private val dir = File(ctx.filesDir, "backgrounds").apply { mkdirs() }
    private val metaFile = File(dir, "library.json")
    private val display = ctx.getSharedPreferences("background-display", Context.MODE_PRIVATE)

    fun options(item: BgItem) = BackgroundOptions(BackgroundPlacement(
        display.getFloat("${item.id}:scale", 1f), display.getFloat("${item.id}:x", 0f),
        display.getFloat("${item.id}:y", 0f),
    ).bounded(), item.video && display.getBoolean("${item.id}:sound", false))

    fun saveOptions(item: BgItem, options: BackgroundOptions) {
        val placement = options.placement.bounded()
        display.edit().putFloat("${item.id}:scale", placement.scale).putFloat("${item.id}:x", placement.x)
            .putFloat("${item.id}:y", placement.y).putBoolean("${item.id}:sound", item.video && options.sound).apply()
    }

    private fun poster(id: String) = File(dir, ".$id.poster.jpg")
    private val candidates = listOf(
        BgItem("builtin:sea", "海边日落", "asset:bg/sea.jpg", true),
        BgItem("builtin:sakura", "樱花小径", "asset:bg/sakura.jpg", true),
        BgItem("builtin:city", "城市夜色", "asset:bg/city.jpg", true),
        BgItem("builtin:aurora", "柔光", "drawable:ui_bg_aurora", true),
    )
    private fun builtins(): List<BgItem> {
        val files = runCatching { ctx.assets.list("bg")?.toSet() }.getOrNull().orEmpty()
        val available = candidates.filter { !it.path.startsWith("asset:") || it.path.substringAfterLast('/') in files }
        return if ("sea.jpg" in files) available.filter { it.id != "builtin:aurora" } else available
    }
    fun all(): List<BgItem> = builtins() + imported()
    fun migratedSelection(id: String?): String? = if (id in setOf("builtin:aurora", "builtin:deep", "builtin:ellen-portrait") &&
        builtins().any { it.id == "builtin:sea" }) "builtin:sea" else id
    fun selected(id: String?): BgItem = all().firstOrNull { it.id == migratedSelection(id) }
        ?: builtins().firstOrNull { it.id == "builtin:sea" }
        ?: candidates.first { it.id == "builtin:aurora" }

    @Synchronized private fun titles(): JSONObject = runCatching {
        JSONObject(metaFile.readText(Charsets.UTF_8))
    }.getOrElse { JSONObject() }

    @Synchronized fun imported(): List<BgItem> {
        val names = titles()
        return dir.listFiles { f -> f.isFile && !f.name.startsWith('.') &&
            f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "video") }
            ?.sortedByDescending { it.lastModified() }?.map { file ->
                BgItem(file.nameWithoutExtension, names.optString(file.nameWithoutExtension).ifBlank { "自定义背景" },
                    "file://" + file.absolutePath, false, file.extension == "video")
            }.orEmpty()
    }

    fun importFromUri(name: String, uri: Uri): BgItem {
        val mime = ctx.contentResolver.getType(uri).orEmpty().lowercase()
        if (mime.startsWith("video/") || (!mime.startsWith("image/") &&
                name.substringAfterLast('.', "").lowercase() in setOf("mp4", "webm", "mkv", "3gp", "mov", "m4v"))) {
            return importVideo(name, uri)
        }
        val temporary = File.createTempFile("background-import-", ".image", ctx.cacheDir)
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { BackgroundImagePolicy.copyBounded(input, it) }
            } ?: error("无法读取这张图片")
            return importFile(name, temporary)
        } finally { temporary.delete() }
    }

    private fun importVideo(name: String, uri: Uri): BgItem {
        val id = UUID.randomUUID().toString()
        val staged = File(dir, ".$id.import")
        val stagedPoster = File(dir, ".$id.poster.import")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { BackgroundImagePolicy.copyBounded(input, it, limit = 128L * 1024 * 1024,
                    limitMessage = "视频不能超过128 MiB") }
            } ?: error("无法读取这段视频")
            val thumbnail = videoPoster(staged)
            try {
                stagedPoster.outputStream().use { check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 82, it)) }
            } finally { thumbnail.recycle() }
            return commitVideo(id, name, staged, stagedPoster)
        } finally { staged.delete(); stagedPoster.delete() }
    }

    private fun videoPoster(source: File): Bitmap {
        val metadata = MediaMetadataRetriever()
        try {
            try { source.inputStream().use { metadata.setDataSource(it.fd) } }
            catch (cause: RuntimeException) { throw IllegalArgumentException("无法读取视频，请选择可播放的 MP4 或 WebM", cause) }
            val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            require(width > 0 && height > 0 && duration > 0) { "没有可播放的视频轨道" }
            require(maxOf(width, height) <= 1920 && minOf(width, height) <= 1080) { "背景视频最高支持1080P" }
            require(duration <= 120_000L) { "背景视频不能超过2分钟" }
            val scale = minOf(1f, 640f / maxOf(width, height))
            val w = (width * scale).toInt().coerceAtLeast(1)
            val h = (height * scale).toInt().coerceAtLeast(1)
            val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            val sideways = rotation == 90 || rotation == 270
            val time = minOf(500_000L, duration * 500)
            val frame = if (Build.VERSION.SDK_INT >= 27) metadata.getScaledFrameAtTime(time,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC, if (sideways) h else w, if (sideways) w else h)
            else metadata.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            return frame ?: throw IllegalArgumentException("手机无法解码这段视频")
        } finally { metadata.release() }
    }

    @Synchronized private fun commitVideo(id: String, name: String, staged: File, stagedPoster: File): BgItem {
        val output = File(dir, "$id.video")
        val cover = poster(id)
        val displayName = name.ifBlank { "自定义视频" }.take(80)
        var committed = false
        try {
            check(stagedPoster.renameTo(cover) && staged.renameTo(output)) { "无法保存背景视频" }
            saveTitles(titles().put(id, displayName))
            committed = true
            return BgItem(id, displayName, "file://" + output.absolutePath, false, video = true)
        } finally {
            if (!committed) { output.delete(); cover.delete() }
        }
    }

    /** Compatibility entry; URI import avoids retaining the whole source in memory. */
    fun import(name: String, bytes: ByteArray): BgItem? {
        require(bytes.size.toLong() <= BackgroundImagePolicy.MAX_IMPORT_BYTES) { "图片不能超过32 MiB" }
        val temporary = File.createTempFile("background-import-", ".image", ctx.cacheDir)
        return try { temporary.writeBytes(bytes); importFile(name, temporary) }
        finally { temporary.delete() }
    }

    @Synchronized private fun importFile(name: String, source: File): BgItem {
        val bitmap = decodeSampled({ source.inputStream() }, BackgroundImagePolicy.MAX_SIDE, BackgroundImagePolicy.MAX_SIDE)
            ?: error("图片格式无法读取，请选择JPEG、PNG或WebP")
        val id = UUID.randomUUID().toString()
        val extension = if (bitmap.hasAlpha()) "png" else "jpg"
        val output = File(dir, "$id.$extension")
        val staged = File(dir, ".$id.import")
        var committed = false
        try {
            staged.outputStream().use { stream ->
                check(bitmap.compress(if (extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, stream))
            }
            check(staged.renameTo(output)) { "无法保存背景" }
            val displayName = name.ifBlank { "自定义背景" }.take(80)
            saveTitles(titles().put(id, displayName))
            committed = true
            return BgItem(id, displayName, "file://" + output.absolutePath, false)
        } finally {
            bitmap.recycle(); staged.delete()
            if (!committed) output.delete()
        }
    }

    @Synchronized private fun saveTitles(value: JSONObject) {
        val staged = File(dir, ".library-${UUID.randomUUID()}.json")
        try {
            staged.writeText(value.toString(), Charsets.UTF_8)
            check(staged.renameTo(metaFile)) { "无法保存背景信息" }
        } finally { staged.delete() }
    }

    @Synchronized fun delete(item: BgItem): Boolean {
        if (item.builtin) return false
        val file = File(item.path.removePrefix("file://"))
        if (file.parentFile?.canonicalFile != dir.canonicalFile) return false
        val deleted = file.delete()
        if (deleted) {
            if (item.video) poster(item.id).delete()
            display.edit().remove("${item.id}:scale").remove("${item.id}:x").remove("${item.id}:y")
                .remove("${item.id}:sound").apply()
            runCatching { saveTitles(titles().apply { remove(item.id) }) }
        }
        return deleted
    }

    fun loadBitmap(item: BgItem, reqW: Int, reqH: Int): Bitmap? = try {
        when {
            item.path == "drawable:ui_bg_aurora" -> {
                val scale = (1920f / maxOf(reqW, reqH).coerceAtLeast(1)).coerceAtMost(1f)
                val width = (reqW * scale).toInt().coerceAtLeast(1)
                val height = (reqH * scale).toInt().coerceAtLeast(1)
                val drawable = ContextCompat.getDrawable(ctx, R.drawable.ui_bg_aurora)
                drawable?.let { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    it.setBounds(0, 0, width, height); it.draw(Canvas(bitmap))
                } }
            }
            item.video -> poster(item.id).takeIf { it.isFile }?.let { file -> decodeSampled({ file.inputStream() }, reqW, reqH) }
            item.path.startsWith("asset:") -> decodeSampled({ ctx.assets.open(item.path.removePrefix("asset:")) }, reqW, reqH)
            else -> File(item.path.removePrefix("file://")).takeIf { it.isFile }?.let { file ->
                decodeSampled({ file.inputStream() }, reqW, reqH)
            }
        }
    } catch (_: Exception) { null }

    private fun decodeSampled(open: () -> InputStream, w: Int, h: Int): Bitmap? {
        // Independent opens: provider/file streams are not required to support reset().
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = BackgroundImagePolicy.sampleSize(bounds.outWidth, bounds.outHeight, maxOf(w, h).coerceAtLeast(1))
        val bitmap = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val orientation = runCatching { open().use { ExifInterface(it).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix().apply { when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        } }
        if (transform.isIdentity) return bitmap
        return try { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true).also {
            if (it !== bitmap) bitmap.recycle()
        } } catch (error: Exception) { bitmap.recycle(); throw error }
    }
}

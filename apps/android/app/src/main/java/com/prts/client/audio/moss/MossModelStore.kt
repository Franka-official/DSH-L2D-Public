package com.prts.client.audio.moss

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.prts.client.BuildConfig
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object MossModelStore {
    const val TOTAL_BYTES = 243_053_118L
    private const val ttsRepo = "MOSS-TTS-Nano-100M-ONNX"
    private const val codecRepo = "MOSS-Audio-Tokenizer-Nano-ONNX"
    private const val ttsRevision = "23fd19793be20a4f3f6aacd4e3a2130c8e3d2519"
    private const val codecRevision = "ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae"

    data class State(
        val phase: String = "idle",
        val processedBytes: Long = 0,
        val totalBytes: Long = TOTAL_BYTES,
        val copiedBytes: Long = 0,
        val elapsedMs: Long = 0,
        val error: String? = null,
    ) {
        val ready get() = phase == "ready"
        val bytesPerSecond get() = if (elapsedMs > 0) copiedBytes * 1000.0 / elapsedMs else 0.0
        val etaSeconds get() = if (bytesPerSecond > 0) {
            ((totalBytes - processedBytes).coerceAtLeast(0) / bytesPerSecond).toLong()
        } else null
    }

    private data class Asset(val repo: String, val revision: String, val name: String, val bytes: Long) {
        val source get() = if (repo == ttsRepo) "REALBITS/MOSS-TTS-Nano-100M-ONNX-int8" else "OpenMOSS-Team/$repo"
        val url get() = "https://huggingface.co/$source/resolve/$revision/$name"
        fun file(root: File) = File(root, "$repo/$name")
        fun partial(root: File) = File(root, "$repo/$name.part")
    }

    private val assets = (mapOf(
        "browser_poc_manifest.json" to 503_354L,
        "moss_tts_decode_step.onnx" to 489_413L,
        "moss_tts_global_shared_int8.data" to 110_512_128L,
        "moss_tts_local_fixed_sampled_frame.onnx" to 744_178L,
        "moss_tts_local_fixed_sampled_frame_int8.data" to 84_974_592L,
        "moss_tts_prefill.onnx" to 477_742L,
        "tokenizer.model" to 470_897L,
    ).map { (name, size) -> Asset(ttsRepo, ttsRevision, name, size) } + mapOf(
        "moss_audio_tokenizer_decode_full.onnx" to 681_902L,
        "moss_audio_tokenizer_decode_shared.data" to 44_198_912L,
    ).map { (name, size) -> Asset(codecRepo, codecRevision, name, size) }).also {
        check(it.sumOf(Asset::bytes) == TOTAL_BYTES)
    }

    private val bundledFiles = assets.associate { "${it.repo}/${it.name}" to it.bytes } + mapOf(
        "voices/whale.json" to BuildConfig.MOSS_WHALE_VOICE_BYTES,
        "voices/ellen.json" to BuildConfig.MOSS_ELLEN_VOICE_BYTES,
    )
    private val bundledBytes = bundledFiles.values.sum()
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val inFlight = AtomicReference<Call?>(null)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "PRTS-MOSS-Install") }
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<(State) -> Unit>()
    private val http = OkHttpClient.Builder().followRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    @Volatile private var state = State()

    fun root(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "moss_tts_int8_cpu_v1")
    fun ready(context: Context): Boolean = inventory(root(context)).ready

    fun prepareBundled(context: Context, retry: Boolean = false) {
        if (!BuildConfig.MOSS_MODEL_BUNDLED || (!retry && state.phase != "idle") || ready(context)) return
        val app = context.applicationContext
        start(app, "installing") { directory, started -> installBundled(app, directory, started) }
    }

    fun status(context: Context): State {
        if (running.get()) return state
        val actual = inventory(root(context))
        return if (actual.ready || state.phase !in setOf("failed", "cancelled")) actual
        else actual.copy(phase = state.phase, error = state.error)
    }

    fun observe(context: Context, observer: (State) -> Unit): AutoCloseable {
        observers.add(observer)
        observer(status(context))
        return AutoCloseable { observers.remove(observer) }
    }

    fun download(context: Context) {
        if (BuildConfig.MOSS_MODEL_BUNDLED) prepareBundled(context, retry = true)
        else start(context, "downloading", ::install)
    }

    private fun start(context: Context, phase: String, operation: (File, Long) -> Unit) {
        val directory = root(context.applicationContext)
        if (!running.compareAndSet(false, true)) return
        cancelled.set(false)
        publish(inventory(directory).copy(phase = phase))
        worker.execute {
            val started = SystemClock.elapsedRealtime()
            val result = try {
                operation(directory, started)
                inventory(directory).also { requireDownload(it.ready, "MOSS 模型文件不完整") }
            } catch (error: Exception) {
                if (cancelled.get() || error is CancellationException) inventory(directory).copy(phase = "cancelled")
                else inventory(directory).copy(phase = "failed",
                    error = (error as? DownloadFailure)?.message ?:
                        if (phase == "installing") "内置 MOSS 模型未准备完成，可以重试" else "下载未完成，可以继续下载")
            } finally {
                inFlight.set(null)
                running.set(false)
            }
            publish(result)
        }
    }

    fun cancel() {
        if (running.get()) {
            cancelled.set(true)
            inFlight.get()?.cancel()
        }
    }

    private fun publish(value: State) {
        state = value
        main.post { if (state === value) observers.forEach { it(value) } }
    }

    private fun exact(file: File, size: Long) = file.isFile && file.canRead() && file.length() == size

    private fun partialBytes(asset: Asset, directory: File): Long {
        val file = asset.partial(directory)
        return if (file.isFile && file.length() in 0..asset.bytes) file.length() else 0
    }

    private fun inventory(directory: File): State {
        if (BuildConfig.MOSS_MODEL_BUNDLED) {
            val present = bundledFiles.entries.sumOf { (path, size) ->
                if (exact(File(directory, path), size)) size else 0L
            }
            return State(if (present == bundledBytes) "ready" else "idle", present, bundledBytes)
        }
        val complete = assets.all { exact(it.file(directory), it.bytes) }
        val processed = assets.sumOf {
            if (exact(it.file(directory), it.bytes)) it.bytes else partialBytes(it, directory)
        }
        return State(if (complete) "ready" else "idle", processed)
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw CancellationException()
    }

    private class DownloadFailure(message: String) : Exception(message)

    private fun requireDownload(value: Boolean, message: String) {
        if (!value) throw DownloadFailure(message)
    }

    private fun installBundled(context: Context, directory: File, started: Long) {
        checkCancelled()
        requireDownload(directory.isDirectory || directory.mkdirs(), "无法建立 MOSS 模型目录")
        val missing = bundledFiles.filter { (path, size) -> !exact(File(directory, path), size) }
        for (path in missing.keys) Files.deleteIfExists(File(directory, "$path.bundled.tmp").toPath())
        val remaining = missing.values.sum()
        requireDownload(directory.usableSpace >= remaining, "空间不足，无法准备内置 MOSS 模型")
        val initial = bundledBytes - remaining
        var copied = 0L
        var lastUpdate = -250L
        val buffer = ByteArray(128 * 1024)
        for ((path, size) in missing) {
            checkCancelled()
            val target = File(directory, path)
            val partial = File(directory, "$path.bundled.tmp")
            val parent = checkNotNull(partial.parentFile)
            requireDownload(parent.isDirectory || parent.mkdirs(), "无法建立 MOSS 模型目录")
            try {
                context.assets.open("moss/payload/$path").use { input ->
                    FileOutputStream(partial).use { output ->
                        var count = 0L
                        while (true) {
                            checkCancelled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            requireDownload(read <= size - count, "内置 MOSS 文件大于清单")
                            output.write(buffer, 0, read)
                            count += read
                            val elapsed = SystemClock.elapsedRealtime() - started
                            if (elapsed - lastUpdate >= 250 || count == size) {
                                lastUpdate = elapsed
                                publish(State("installing", initial + copied + count, bundledBytes, copied + count, elapsed))
                            }
                        }
                    }
                }
                checkCancelled()
                requireDownload(exact(partial, size), "内置 MOSS 文件不完整")
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                copied += size
            } finally {
                partial.delete()
            }
        }
    }

    private fun install(directory: File, started: Long) {
        checkCancelled()
        requireDownload(directory.isDirectory || directory.mkdirs(), "无法建立 MOSS 模型目录")
        val before = inventory(directory)
        requireDownload(directory.usableSpace >= TOTAL_BYTES - before.processedBytes, "空间不足，无法下载 MOSS 模型")
        var transferred = 0L
        var lastUpdate = -250L
        var processed = before.processedBytes
        for (asset in assets) {
            checkCancelled()
            val target = asset.file(directory)
            if (exact(target, asset.bytes)) continue
            val partial = asset.partial(directory)
            val parent = checkNotNull(partial.parentFile)
            requireDownload(parent.isDirectory || parent.mkdirs(), "无法建立 MOSS 模型目录")
            requireDownload(!partial.exists() || partial.isFile, "MOSS 下载文件路径异常")
            if (partial.length() > asset.bytes) requireDownload(partial.delete(), "无法清理异常的 MOSS 续传文件")
            val existing = partialBytes(asset, directory)
            if (existing < asset.bytes) {
                val base = processed - existing
                var written = 0L
                transfer(asset, partial, existing) { size, copied ->
                    written = copied
                    processed = base + size
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (elapsed - lastUpdate >= 250 || size == asset.bytes) {
                        lastUpdate = elapsed
                        publish(State("downloading", processed, TOTAL_BYTES, transferred + copied, elapsed))
                    }
                }
                transferred += written
            }
            checkCancelled()
            requireDownload(exact(partial, asset.bytes), "MOSS 文件未下载完整")
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun transfer(asset: Asset, partial: File, existing: Long, progress: (Long, Long) -> Unit) {
        open(asset.url, existing).use { response ->
            try {
                val encoding = response.header("Content-Encoding")
                requireDownload(encoding == null || encoding.equals("identity", ignoreCase = true), "MOSS 下载编码不受支持")
                val offset = responseOffset(response.code, existing, asset.bytes,
                    response.header("Content-Range"), response.header("Content-Length"))
                val body = response.body ?: throw DownloadFailure("MOSS 下载没有返回文件")
                checkCancelled()
                var size = offset
                progress(size, 0)
                body.byteStream().use { input ->
                    FileOutputStream(partial, offset != 0L).use { out ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            requireDownload(count <= asset.bytes - size, "MOSS 文件大于官方清单")
                            out.write(buffer, 0, count)
                            size += count
                            progress(size, size - offset)
                        }
                    }
                }
                requireDownload(size == asset.bytes, "MOSS 文件未下载完整，可以继续下载")
            } finally {
                inFlight.set(null)
            }
        }
    }

    internal fun responseOffset(code: Int, requested: Long, total: Long, range: String?, length: String?): Long {
        val offset = when (code) {
            200 -> {
                requireDownload(range == null, "MOSS 下载长度响应无效")
                0L
            }
            206 -> {
                requireDownload(requested in 0 until total && range == "bytes $requested-${total - 1}/$total",
                    "MOSS 续传范围不匹配")
                requested
            }
            else -> throw DownloadFailure("MOSS 模型暂不可访问（$code）")
        }
        requireDownload(length == null || length.toLongOrNull() == total - offset, "MOSS 下载长度不匹配")
        return offset
    }

    private fun open(initial: String, offset: Long): Response {
        var url = initial.toHttpUrl()
        repeat(5) {
            checkCancelled()
            val host = url.host
            val official = host == "huggingface.co" || host == "cas-bridge.xethub.hf.co" ||
                host == "cdn-lfs.huggingface.co" || host.startsWith("cdn-lfs") && host.endsWith(".hf.co") ||
                host.endsWith(".cdn.hf.co")
            requireDownload(url.isHttps && url.port == 443 && official &&
                url.username.isEmpty() && url.password.isEmpty(), "MOSS 模型跳转地址无效")
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
            if (offset > 0) request.header("Range", "bytes=$offset-")
            val call = http.newCall(request.build())
            inFlight.set(call)
            checkCancelled()
            val response = call.execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            response.use {
                url = response.header("Location")?.let(url::resolve)
                    ?: throw DownloadFailure("MOSS 模型跳转地址无效")
            }
            inFlight.set(null)
        }
        throw DownloadFailure("MOSS 模型跳转次数过多")
    }
}

package com.prts.client.audio.audio8

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import com.prts.client.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

object Audio8ModelStore {
    data class State(val phase: String = "idle", val processedBytes: Long = 0,
        val totalBytes: Long = 0, val copiedBytes: Long = 0, val elapsedMs: Long = 0,
        val error: String? = null) {
        val ready get() = phase == "ready"
        val bytesPerSecond get() = if (elapsedMs > 0) copiedBytes * 1000.0 / elapsedMs else 0.0
        val etaSeconds get() = if (bytesPerSecond > 0) ((totalBytes - processedBytes).coerceAtLeast(0) / bytesPerSecond).toLong() else null
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "PRTS-Audio8-Install") }
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<(State) -> Unit>()
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var state = State()
    private val referenceNames = setOf("reference.json", "reference.codes.i64.bin")
    private val bundledReferenceRoles = listOf("ellen", "whale")
    private const val GPU_CODEC_NAME = "codec_incremental_f32_c12.mnn"
    private const val GPU_CODEC_BYTES = 261_764_152L
    internal const val NPU_CODEC_BYTES = 532_566L
    private val npuPayload = mapOf("decoder.onnx" to NPU_CODEC_BYTES, "weights_f32.data" to 521_520_664L)

    internal fun bundledNpuGraph(context: Context) = File(context.noBackupFilesDir, "audio8-qnn-c12-v1/decoder.onnx")

    fun root(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "audio8")
    fun status() = state
    fun status(context: Context): State = if (state.phase == "idle") inventory(context) else state

    private fun publish(value: State): State {
        state = value
        main.post { if (state === value) observers.forEach { it(value) } }
        return value
    }

    fun observe(context: Context, observer: (State) -> Unit): AutoCloseable {
        observers.add(observer)
        observer(status(context))
        return AutoCloseable { observers.remove(observer) }
    }

    private fun entries(context: Context): Map<String, Long> {
        val manifest = context.assets.open("audio8/model-manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val files = manifest.getJSONArray("files")
        return (0 until files.length()).associate { index ->
            val item = files.getJSONObject(index)
            item.getString("path") to item.getLong("bytes")
        }.filterKeys { it.startsWith("model/") }
    }

    private fun marker(root: File) = AtomicFile(File(root, "installed.json"))
    private fun referencePointer(root: File, role: String) =
        AtomicFile(File(root, if (role == "default") "active-reference" else "active-reference-$role"))

    private fun activeReference(root: File, role: String): File? {
        val name = runCatching { String(referencePointer(root, role).readFully(), Charsets.UTF_8) }.getOrNull()
        return name?.takeIf { it.matches(Regex("reference-[a-zA-Z0-9_-]+")) }
            ?.let { File(root, it) }?.takeIf { validReference(it) }
    }

    internal fun legacyRoleReference(context: Context, role: String): File? =
        activeReference(root(context), role)

    fun importReferenceWav(context: Context, uri: Uri, name: String, transcript: String) {
        val title = Audio8VoiceLibrary.checkNewName(context, name)
        val text = transcript.trim().replace(Regex("\\s+"), " ")
        require(text.isNotBlank() && text.codePointCount(0, text.length) <= 240) { "请输入 WAV 中实际说出的台词" }
        check(BuildConfig.AUDIO8_ENCODER_BUNDLED && Audio8Native.available) { "当前版本未安装 WAV 编码器" }
        // Encoding queues on the shared TTS worker, which can itself use the model-store lock.
        start(context, "preparing", serializeAction = false) { app ->
            val samples = checkNotNull(app.contentResolver.openInputStream(uri)) { "无法读取 WAV" }
                .use(Audio8ReferenceWav::read)
            val graph = synchronized(lock) { prepareReferenceEncoder(app) }
            val codes = OfflineAudio8TtsEngine.registerReference(graph, samples, ::checkCancelled)
            checkCancelled()
            val root = root(app)
            check(root.isDirectory || root.mkdirs())
            val staging = java.nio.file.Files.createTempDirectory(root.toPath(), ".audio8-wav-").toFile()
            try {
                val frames = codes.size / 10
                check(frames in 1..8192 && codes.size == frames * 10)
                val metadata = JSONObject()
                    .put("shape", org.json.JSONArray().put(10).put(frames))
                    .put("dtype", "int64_le").put("semantic_begin_id", 65537).put("reference_text", text)
                File(staging, "reference.json").writeText(metadata.toString(), Charsets.UTF_8)
                val raw = ByteBuffer.allocate(codes.size * 8).order(ByteOrder.LITTLE_ENDIAN)
                codes.forEach { raw.putLong(it) }
                File(staging, "reference.codes.i64.bin").writeBytes(raw.array())
                synchronized(lock) {
                    checkCancelled()
                    Audio8VoiceLibrary.add(app, staging, title)
                    publish(inventory(app))
                }
            } finally { staging.deleteRecursively() }
        }
    }

    private fun prepareReferenceEncoder(context: Context): File {
        val graphName = "codec_encoder_fp16.onnx"
        val directory = File(context.noBackupFilesDir, "audio8-reference-encoder-v1")
        check(directory.isDirectory || directory.mkdirs()) { "无法建立 Audio8 编码器目录" }
        for ((name, size) in listOf(graphName to 940_787L, "$graphName.data" to 414_425_088L)) {
            val target = File(directory, name)
            if (!fileMatches(target, size)) {
                val partial = File(directory, "$name.installing")
                try {
                    val count = context.assets.open("audio8/payload/encoder/$name").use {
                        copyBounded(it, partial, size) {}
                    }
                    check(count == size && partial.renameTo(target)) { "无法安装 WAV 编码器" }
                } finally { partial.delete() }
            }
        }
        return File(directory, graphName)
    }

    internal fun referenceDirectory(context: Context, voiceId: String? = null): File {
        val root = root(context)
        val requested = when (voiceId) {
            "whale", "whale_girl" -> "whale"
            "ellen", "ellen_zh", "ellen_ja" -> "ellen"
            else -> null
        }
        val legacy = File(root, "inputs")
        fun roleReference(role: String) = bundledReferenceDirectory(root, role).takeIf { validReference(it) }
            ?: File(root, "reference-$role-bundled").takeIf { role == "whale" && validReference(it) }
        return voiceId?.let { Audio8VoiceLibrary.directory(context, it) } ?: requested?.let(::roleReference)
            ?: activeReference(root, "default") ?: legacy.takeIf { validReference(it) }
            ?: roleReference("ellen") ?: roleReference("whale") ?: Audio8VoiceLibrary.directory(context, null) ?: legacy
    }

    private fun fileMatches(file: File, bytes: Long) = file.isFile && file.canRead() && file.length() == bytes

    private fun bundledReferenceDirectory(root: File, role: String) =
        File(root, if (role == "whale") "reference-whale-bundled-v2" else "reference-$role-bundled")

    private fun pendingBundledReferenceRoles(context: Context, root: File): List<String> =
        bundledReferenceRoles.filter { role ->
            !validReference(bundledReferenceDirectory(root, role)) &&
                context.assets.list("audio8/payload/roles/$role")?.toSet() == referenceNames
        }

    fun ensureBundledGpuCodec(context: Context): File? = synchronized(lock) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val directory = File(context.filesDir, "audio8-gpu")
        val target = File(directory, GPU_CODEC_NAME)
        if (fileMatches(target, GPU_CODEC_BYTES)) return@synchronized target
        if (!BuildConfig.AUDIO8_GPU_DEFAULT) return@synchronized null
        check(directory.isDirectory || directory.mkdirs()) { "无法建立 Audio8 GPU 模型目录" }
        val partial = File(directory, "$GPU_CODEC_NAME.installing")
        try {
            val count = context.assets.open("audio8/payload/gpu/$GPU_CODEC_NAME").use { input ->
                copyBounded(input, partial, GPU_CODEC_BYTES, honorCancellation = false) {}
            }
            check(count == GPU_CODEC_BYTES && partial.renameTo(target)) { "无法安装 Audio8 GPU 解码器" }
            target
        } finally { partial.delete() }
    }

    internal fun ensureBundledNpuCodec(context: Context): File = synchronized(lock) {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(BuildConfig.AUDIO8_NPU_BUNDLED) { "当前版本没有内置 NPU 解码器" }
        val graph = bundledNpuGraph(context)
        val directory = checkNotNull(graph.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "无法建立 Audio8 NPU 模型目录" }
        npuPayload.forEach { (name, bytes) ->
            val target = File(directory, name)
            if (!fileMatches(target, bytes)) {
                val partial = File(directory, "$name.installing")
                try {
                    val count = context.assets.open("audio8/payload/qnn/$name").use { input ->
                        copyBounded(input, partial, bytes, honorCancellation = false) {}
                    }
                    check(count == bytes && partial.renameTo(target)) { "无法安装 Audio8 NPU 解码文件 $name" }
                } finally { partial.delete() }
            }
        }
        graph
    }

    internal fun validReference(directory: File, checkCodes: Boolean = false): Boolean = runCatching {
        val metadata = File(directory, "reference.json")
        require(metadata.length() in 1..16_384)
        val json = JSONObject(metadata.readText(Charsets.UTF_8))
        val shape = json.getJSONArray("shape")
        val frames = shape.getInt(1)
        require(shape.length() == 2 && shape.getInt(0) == 10 && frames in 1..8192)
        require(json.getString("dtype") == "int64_le" && json.getInt("semantic_begin_id") == 65537)
        val transcript = json.getString("reference_text")
        require(transcript.isNotBlank() && transcript.toByteArray(Charsets.UTF_8).size <= 8192)
        val codes = File(directory, "reference.codes.i64.bin")
        require(fileMatches(codes, frames * 80L))
        if (checkCodes) {
            val data = ByteBuffer.wrap(codes.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            while (data.hasRemaining()) require(data.long in 0L..4095L)
        }
        true
    }.getOrDefault(false)

    private fun inventory(context: Context): State {
        val entries = entries(context)
        val root = root(context)
        val total = entries.values.sum()
        val present = entries.entries.sumOf { (name, size) -> if (fileMatches(File(root, name), size)) size else 0L }
        val installed = runCatching { JSONObject(String(marker(root).readFully(), Charsets.UTF_8)) }.getOrNull()
        val compatible = installed?.optBoolean("complete") == true &&
            installed.optString("revision") == Audio8ModelLayout.REVISION &&
            installed.optString("codec_variant") == Audio8ModelLayout.CODEC_VARIANT
        val phase = when {
            present != total || !compatible -> "idle"
            !validReference(referenceDirectory(context)) -> "needs_reference"
            else -> "ready"
        }
        return State(phase, present, total)
    }

    fun installed(context: Context): Boolean = runCatching { inventory(context).ready }.getOrDefault(false)

    fun prepare(context: Context) {
        if (state.phase in setOf("failed", "cancelled", "needs_reference")) return
        if (!running.get() && BuildConfig.AUDIO8_MODEL_BUNDLED &&
            (!installed(context) || pendingBundledReferenceRoles(context, root(context)).isNotEmpty())) {
            start(context, "installing", alreadyRunningIsError = false) { ensureInstalled(it) }
        }
    }

    fun retryLocalPreparation(context: Context) {
        start(context, "installing") { ensureInstalled(it) }
    }

    fun importPackage(context: Context, uri: Uri) {
        start(context, "importing") { app ->
            importLocked(app) {
                checkNotNull(app.contentResolver.openInputStream(uri)) { "无法读取 Audio8 ZIP" }
            }
        }
    }

    private fun githubSource(context: Context) = runCatching {
        context.assets.open("audio8/github-model-source.json").bufferedReader().use {
            Audio8GithubModelSource.parse(JSONObject(it.readText()))
        }
    }.getOrNull()

    fun hasGithubModelSource(context: Context): Boolean = githubSource(context) != null

    fun hasCompatibleModel(context: Context): Boolean =
        runCatching { inventory(context).phase in setOf("ready", "needs_reference") }.getOrDefault(false)

    fun downloadGithubPackage(context: Context) {
        val source = checkNotNull(githubSource(context)) { "暂无可直接下载的 GitHub 模型包" }
        if (hasCompatibleModel(context)) return
        // Keep synthesis and an installed model usable during the transfer.
        start(context, "downloading", serializeAction = false) { app ->
            val directory = File(root(app), ".github-download")
            check(directory.isDirectory || directory.mkdirs()) { "无法创建 Audio8 下载目录" }
            val partial = File(directory, source.sha256 + ".part")
            val remaining = (source.bytes - partial.length()).coerceAtLeast(0)
            check(directory.usableSpace >= remaining + entries(app).values.sum()) { "空间不足，无法下载并安装 Audio8 模型" }
            val began = SystemClock.elapsedRealtime()
            var lastUpdate = -250L
            Audio8GithubModelTransfer.fetch(source, partial, ::checkCancelled) { processed, transferred ->
                val elapsed = SystemClock.elapsedRealtime() - began
                if (elapsed - lastUpdate >= 250 || processed == source.bytes) {
                    lastUpdate = elapsed
                    publish(State("downloading", processed, source.bytes, transferred, elapsed))
                }
            }
            synchronized(lock) {
                checkCancelled()
                if (!hasCompatibleModel(app)) {
                    publish(State("importing", totalBytes = entries(app).values.sum()))
                    importLocked(app, modelOnly = true) { partial.inputStream() }
                } else publish(inventory(app))
            }
            partial.delete()
        }
    }

    fun cancel(context: Context) {
        if (running.get()) {
            cancelled.set(true)
            publish(state.copy(phase = "cancelling"))
        }
    }

    private fun start(context: Context, phase: String, alreadyRunningIsError: Boolean = true,
        serializeAction: Boolean = true, action: (Context) -> Unit) {
        if (!running.compareAndSet(false, true)) {
            check(!alreadyRunningIsError) { "Audio8 资源正在处理" }
            return
        }
        cancelled.set(false)
        publish(State(phase))
        worker.execute {
            try {
                if (serializeAction) synchronized(lock) { action(context.applicationContext) }
                else action(context.applicationContext)
            } catch (_: CancellationException) { publish(inventory(context).copy(phase = "cancelled")) }
            catch (error: Exception) {
                val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
                if (cause is CancellationException) publish(inventory(context).copy(phase = "cancelled"))
                else publish(state.copy(phase = "failed", error = cause.message ?: "Audio8 资源处理失败"))
            }
            finally { running.set(false) }
        }
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw CancellationException("Audio8 model installation cancelled")
    }

    private fun writeAtomic(file: AtomicFile, value: String) {
        val output = file.startWrite()
        try { output.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    private fun writeMarker(root: File) {
        writeAtomic(marker(root), JSONObject().put("schema", "dsh-audio8-layout-v1").put("complete", true)
            .put("revision", Audio8ModelLayout.REVISION).put("source_hf_revision", Audio8ModelLayout.REVISION)
            .put("codec_variant", Audio8ModelLayout.CODEC_VARIANT).put("installed_by_uid", android.os.Process.myUid()).toString())
    }

    private fun copyBounded(input: InputStream, target: File, limit: Long,
        honorCancellation: Boolean = true, progress: (Long) -> Unit): Long {
        target.parentFile?.mkdirs()
        var copied = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                if (honorCancellation) checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                require(copied <= limit) { "Audio8 文件超过允许大小" }
                out.write(buffer, 0, count)
                progress(copied)
            }
        }
        return copied
    }

    fun ensureInstalled(context: Context): State = synchronized(lock) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val before = inventory(context)
        val root = root(context)
        val pendingRoles = pendingBundledReferenceRoles(context, root)
        if (before.ready && pendingRoles.isEmpty()) return@synchronized publish(before)
        if (!running.get()) cancelled.set(false)
        val begin = SystemClock.elapsedRealtime()
        var processed = 0L
        var copied = 0L
        if (before.phase == "idle") {
            if (!BuildConfig.AUDIO8_MODEL_BUNDLED) return@synchronized publish(before)
            check(root.isDirectory || root.mkdirs()) { "无法建立 Audio8 模型目录" }
            val entries = entries(context)
            for ((name, size) in entries) {
                val target = File(root, name)
                if (!fileMatches(target, size)) {
                    val partial = File(target.parentFile, target.name + ".installing")
                    try {
                        val count = context.assets.open("audio8/payload/$name").use { input ->
                            copyBounded(input, partial, size) { current ->
                                publish(State("installing", processed + current, before.totalBytes, copied + current, SystemClock.elapsedRealtime() - begin))
                            }
                        }
                        check(count == size && partial.renameTo(target)) { "无法安装 Audio8 模型文件 $name" }
                        copied += count
                    } finally { partial.delete() }
                }
                processed += size
            }
            writeMarker(root)
        }
        pendingRoles.forEach { role ->
            val staging = stageBundledReference(context, root, "audio8/payload/roles/$role")
            try {
                check(validReference(staging, checkCodes = true)) { "Audio8 内置参考音格式不正确" }
                val target = bundledReferenceDirectory(root, role)
                check(!target.exists() || target.deleteRecursively()) { "无法更新 Audio8 内置参考音" }
                check(staging.renameTo(target)) { "无法保存 Audio8 内置参考音" }
            } finally { staging.deleteRecursively() }
        }
        val hasSharedReference = activeReference(root, "default") != null || validReference(File(root, "inputs"))
        if (!hasSharedReference && context.assets.list("audio8/payload/inputs")?.toSet() == referenceNames) {
            val staging = stageBundledReference(context, root, "audio8/payload/inputs")
            try { installReference(root, staging, "default") }
            finally { staging.deleteRecursively() }
        }
        publish(inventory(context).copy(copiedBytes = copied, elapsedMs = SystemClock.elapsedRealtime() - begin))
    }

    private fun stageBundledReference(context: Context, root: File, assetDirectory: String): File {
        val staging = java.nio.file.Files.createTempDirectory(root.toPath(), ".audio8-reference-").toFile()
        try {
            referenceNames.forEach { name ->
                context.assets.open("$assetDirectory/$name").use { input ->
                    copyBounded(input, File(staging, name), if (name.endsWith(".json")) 16_384 else 655_360) {}
                }
            }
            return staging
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun installReference(root: File, staging: File, role: String) {
        check(validReference(staging, checkCodes = true)) { "Audio8 参考音文本或声码格式不正确" }
        val target = File(root, "reference-$role-${java.util.UUID.randomUUID()}")
        check(staging.renameTo(target)) { "无法保存 Audio8 参考音" }
        try { writeAtomic(referencePointer(root, role), target.name) }
        catch (error: Exception) { target.deleteRecursively(); throw error }
    }

    private fun importLocked(context: Context, modelOnly: Boolean = false, openInput: () -> InputStream) {
        val expected = entries(context)
        val total = expected.values.sum()
        val root = root(context)
        check(root.isDirectory || root.mkdirs())
        val staging = java.nio.file.Files.createTempDirectory(root.toPath(), ".audio8-import-").toFile()
        val sizes = linkedMapOf<String, Long>()
        val seen = hashSetOf<String>()
        val begin = SystemClock.elapsedRealtime()
        var copied = 0L
        try {
            ZipInputStream(openInput().buffered()).use { zip ->
                while (true) {
                    checkCancelled()
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(seen.add(name)) { "ZIP 含重复条目" }
                    if (entry.isDirectory) {
                        require(name in setOf("model/", "model/tokenizer/", "inputs/"))
                        require(zip.read() == -1)
                        continue
                    }
                    val expectedSize = expected[name]
                    val limit = expectedSize ?: when (name) {
                        "model-manifest.json", "inputs/reference.json" -> 16_384L
                        "LICENSE", "NOTICE" -> 32_768L
                        "inputs/reference.codes.i64.bin" -> 655_360L
                        else -> error("ZIP 包含不受支持的文件")
                    }
                    val size = copyBounded(zip, File(staging, name), limit) { current ->
                        if (expectedSize != null) publish(State("importing", copied + current, total, copied + current, SystemClock.elapsedRealtime() - begin))
                    }
                    if (expectedSize != null) {
                        require(size == expectedSize) { "Audio8 模型文件长度不符 $name" }
                        copied += size
                    }
                    if (name in expected || name.startsWith("inputs/")) sizes[name] = size
                }
            }
            val manifest = JSONObject(File(staging, "model-manifest.json").readText(Charsets.UTF_8))
            val schema = manifest.optString("schema")
            val referenceOnly = schema == "dsh-audio8-reference-pack-v1"
            require((referenceOnly || schema == "dsh-audio8-model-pack-v1") &&
                manifest.optString("source_hf_revision") == Audio8ModelLayout.REVISION &&
                manifest.optString("codec_variant") == Audio8ModelLayout.CODEC_VARIANT) { "Audio8 模型包版本不匹配" }
            val role = manifest.optString("reference_voice", "default")
            require(role in setOf("default", "ellen", "whale")) { "未知 Audio8 角色参考音" }
            require(if (referenceOnly) sizes.keys.none { it in expected } else
                expected.all { (name, size) -> sizes[name] == size }) { "Audio8 模型包文件不完整" }
            val files = manifest.getJSONArray("files")
            val declared = (0 until files.length()).associate { index ->
                val item = files.getJSONObject(index)
                item.getString("path") to item.getLong("bytes")
            }
            require(files.length() == declared.size && declared == sizes) { "Audio8 包清单与内容不一致" }
            val reference = File(staging, "inputs")
            require(!modelOnly || (!referenceOnly && !reference.exists())) { "GitHub 模型包不能包含参考音" }
            require((!referenceOnly && !reference.exists()) || validReference(reference, checkCodes = true)) { "参考音 ZIP 必须同时含有效文本和声码" }
            checkCancelled()
            if (!referenceOnly) {
                for ((name, size) in expected) {
                    val target = File(root, name)
                    if (!fileMatches(target, size)) {
                        target.parentFile?.mkdirs()
                        check(File(staging, name).renameTo(target)) { "无法安装 Audio8 模型文件 $name" }
                    }
                }
                writeMarker(root)
            }
            if (reference.exists()) {
                if (role == "default" && !referenceOnly) installReference(root, reference, role)
                else Audio8VoiceLibrary.add(context, reference, when (role) {
                    "whale" -> "鲸鱼娘参考音"
                    "ellen" -> "艾莲参考音"
                    else -> "导入参考音"
                }, assignRole = role.takeUnless { it == "default" }, autoName = true)
            }
            publish(inventory(context).copy(copiedBytes = copied, elapsedMs = SystemClock.elapsedRealtime() - begin))
        } finally { staging.deleteRecursively() }
    }
}

package com.prts.client.audio.audio8

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File

/** Audio8 model files and the locally selected reference. */
internal class Audio8ModelLayout(private val context: Context) {
    val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "audio8")
    val models = File(root, "model")
    val reference get() = Audio8ModelStore.referenceDirectory(context)
    private val entries: List<Pair<String, Long>> by lazy {
        val manifest = context.assets.open("audio8/model-manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val files = manifest.getJSONArray("files")
        (0 until files.length()).map { index ->
            val value = files.getJSONObject(index)
            val path = value.getString("path")
            require(!File(path).isAbsolute && path.split('/').none { it.isBlank() || it == ".." })
            path to value.getLong("bytes")
        }.filter { (path, _) -> path.startsWith("model/") }
    }
    fun inspect(): OfflineAudio8TtsEngine.ModelState {
        val errors = mutableListOf<String>()
        val files = runCatching { entries }.getOrElse { errors.add("manifest:${it.javaClass.simpleName}"); emptyList() }
        var installed = 0L
        files.forEach { (path, bytes) ->
            val file = File(root, path)
            if (!file.isFile || !file.canRead() || file.length() != bytes) errors.add(path)
            if (file.isFile) installed += file.length()
        }
        val marker = runCatching { JSONObject(File(root, "installed.json").readText()) }.getOrNull()
        if (marker?.optBoolean("complete") != true || marker.optString("revision") != REVISION || marker.optString("codec_variant") != CODEC_VARIANT)
            errors.add("installed.json")
        if (errors.isNotEmpty()) Audio8ModelStore.prepare(context)
        if (!Audio8ModelStore.validReference(reference)) errors.add("Audio8 reference")
        val native = "arm64-v8a" in Build.SUPPORTED_ABIS && Audio8Native.available
        if (!native) errors.add("native:${Audio8Native.loadFailure ?: "arm64_required"}")
        return OfflineAudio8TtsEngine.ModelState(errors.isEmpty(), installed, files.sumOf { it.second },
            errors, native, root.absolutePath, models.absolutePath, context.applicationInfo.nativeLibraryDir)
    }
    companion object {
        const val REVISION = "317c12d4e0da83847b594fcf8bd74bf2c76615ec"
        const val CODEC_VARIANT = "incremental-causal-state-v1"
    }
}

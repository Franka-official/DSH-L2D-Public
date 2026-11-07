package com.prts.client.audio.moss

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.prts.client.audio.LocalVoiceSettings
import com.prts.client.audio.audio8.Audio8VoiceLibrary
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** MOSS prompts and selections are independent of the Audio8 reference library. */
internal object MossVoiceLibrary {
    const val DEFAULT = "default"
    private const val PREFS = "moss_voice_choices"
    private const val MAX_PROMPT_BYTES = 65_536
    private val idPattern = Regex("[A-Za-z0-9_-]{1,80}")
    private val roleNames = linkedMapOf("whale" to "鲸鱼娘 · Bright", "ellen" to "艾莲 · 内置")

    data class Choice(val key: String, val name: String, val voiceId: String, val file: File? = null)

    fun choices(context: Context): List<Choice> {
        val model = MossModelStore.root(context)
        val roles = roleNames.mapNotNull { (id, name) ->
            File(model, "voices/$id.json").takeIf(File::isFile)?.let { Choice("role:$id", name, id, it) }
        }
        val builtin = runCatching { MossNanoEngine.voices(model) }.getOrDefault(emptyList()).map {
            Choice("builtin:${it.id}", it.name, it.id)
        }
        val custom = importDirectory(context).listFiles()?.filter { it.isFile && it.extension == "json" }
            ?.sortedBy { it.name }?.mapNotNull { file ->
                runCatching {
                    val prompt = readPrompt(file)
                    val id = file.nameWithoutExtension
                    require(prompt.getString("voice") == id && idPattern.matches(id))
                    Choice("import:$id", prompt.getString("display_name"), id, file)
                }.getOrNull()
            }.orEmpty()
        return roles + builtin + custom
    }

    fun selected(context: Context, character: String): String =
        preferences(context).getString(Audio8VoiceLibrary.roleId(character), DEFAULT) ?: DEFAULT

    fun observe(context: Context, onChange: () -> Unit): AutoCloseable {
        val prefs = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun select(context: Context, character: String, key: String) {
        require(key == DEFAULT || choices(context).any { it.key == key }) { "该 MOSS 音色不可用" }
        check(preferences(context).edit()
            .putString(Audio8VoiceLibrary.roleId(character), key).commit()) { "音色没有保存成功" }
    }

    fun resolve(context: Context, character: String?, legacyVoice: String, language: String): Choice {
        val role = character?.let(Audio8VoiceLibrary::roleId) ?: when (legacyVoice) {
            LocalVoiceSettings.WHALE_VOICE -> "whale"
            LocalVoiceSettings.ELLEN_VOICE -> "ellen"
            else -> null
        }
        val options = choices(context)
        val chosen = character?.let { selected(context, it) }
        val fallback = when (language) { "en" -> "Ava"; "ja" -> "Saki"; else -> "Xiaoyu" }
        return options.firstOrNull { it.key == chosen }
            ?: options.firstOrNull { it.key == "role:$role" }
            ?: Choice("builtin:$fallback", fallback, fallback)
    }

    fun importPrompt(context: Context, uri: Uri, character: String): String {
        val prompt = context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArray(MAX_PROMPT_BYTES + 1)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count <= 0) break
                size += count
            }
            require(size in 1..MAX_PROMPT_BYTES) { "MOSS 音色文件大小无效" }
            JSONObject(String(bytes, 0, size, Charsets.UTF_8))
        } ?: error("无法读取 MOSS 音色文件")
        val name = prompt.optString("display_name").trim().ifBlank { prompt.getString("voice").trim() }
        return save(context, character, name, prompt.getJSONArray("prompt_audio_codes"))
    }

    fun saveWavPrompt(context: Context, character: String, name: String, frames: List<IntArray>): String =
        save(context, character, name, JSONArray().apply {
            frames.forEach { frame -> put(JSONArray().apply { frame.forEach { put(it) } }) }
        })

    fun removeImported(context: Context, key: String) {
        val id = key.removePrefix("import:")
        require(key.startsWith("import:") && idPattern.matches(id)) { "只能删除导入的音色" }
        val file = File(importDirectory(context), "$id.json")
        check(file.isFile && file.delete()) { "音色没有删除成功" }
        val prefs = preferences(context)
        val edit = prefs.edit()
        prefs.all.filterValues { it == key }.keys.forEach { edit.remove(it) }
        edit.apply()
    }

    private fun save(context: Context, character: String, name: String, frames: JSONArray): String {
        require(name.length in 1..80 && name.none(Char::isISOControl)) { "MOSS 音色名称无效" }
        val id = "voice_" + UUID.randomUUID().toString().replace("-", "")
        val data = JSONObject().put("voice", id).put("display_name", name)
            .put("prompt_audio_codes", frames)
        checkPrompt(data)
        val folder = importDirectory(context)
        check(folder.isDirectory || folder.mkdirs()) { "无法创建 MOSS 音色库" }
        val file = File(folder, "$id.json")
        val pending = File.createTempFile("pending-", ".tmp", folder)
        try {
            pending.writeText(data.toString(), Charsets.UTF_8)
            check(pending.renameTo(file)) { "MOSS 音色没有保存成功" }
            try { select(context, character, "import:$id") }
            catch (error: Exception) { file.delete(); throw error }
        } finally { pending.delete() }
        return name
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun importDirectory(context: Context): File = File(context.filesDir, "moss-voice-library")

    private fun readPrompt(file: File): JSONObject {
        require(file.length() in 1..MAX_PROMPT_BYTES.toLong())
        return JSONObject(file.readText(Charsets.UTF_8)).also(::checkPrompt)
    }

    private fun checkPrompt(prompt: JSONObject) {
        require(prompt.opt("voice") is String) { "MOSS 音色缺少标识" }
        val frames = prompt.getJSONArray("prompt_audio_codes")
        require(frames.length() in 1..256) { "MOSS 参考声码长度无效" }
        for (i in 0 until frames.length()) {
            val frame = frames.getJSONArray(i)
            require(frame.length() == 16) { "MOSS 参考声码帧格式无效" }
            for (j in 0 until 16) {
                val token = frame.get(j)
                require(token is Int && token in 0..1023) { "MOSS 参考声码无效" }
            }
        }
    }
}

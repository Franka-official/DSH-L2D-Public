package com.prts.client.audio.audio8

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

internal object Audio8VoiceLibrary {
    const val DEFAULT = "default"
    const val WHALE = "whale_girl"
    const val ELLEN = "ellen"
    private const val MAX_VOICES = 64
    private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val voiceId = Regex("voice-$UUID_PATTERN")
    private val imported = Regex("import-$UUID_PATTERN")
    private val legacy = Regex("reference-(whale|ellen)-($UUID_PATTERN)")
    private val lock = Any()

    data class Voice(val id: String, val name: String, val directory: String)
    private data class Catalog(val voices: List<Voice>, val assignments: Map<String, String>)

    fun isCustomId(id: String) = voiceId.matches(id)
    fun roleId(character: String): String {
        val name = character.substringAfterLast('/')
        return when {
            imported.matches(name) -> name
            name.startsWith("ellen") -> "ellen"
            else -> "whale"
        }
    }

    fun voices(context: Context): List<Voice> = synchronized(lock) {
        read(context).voices.filter { reference(context, it) != null }
    }

    fun selected(context: Context, character: String): String = synchronized(lock) {
        val catalog = read(context)
        val id = catalog.assignments[roleId(character)] ?: return@synchronized DEFAULT
        if (exists(context, catalog, id)) id else DEFAULT
    }

    fun resolve(context: Context, character: String): String {
        val id = runCatching { selected(context, character) }.getOrDefault(DEFAULT)
        if (id != DEFAULT) return id
        return if (roleId(character) == "ellen") ELLEN else WHALE
    }

    fun select(context: Context, character: String, id: String) = synchronized(lock) {
        val catalog = read(context)
        val role = roleId(character)
        require(id == DEFAULT || exists(context, catalog, id)) { "找不到此音色" }
        val assignments = if (id == DEFAULT) catalog.assignments - role else catalog.assignments + (role to id)
        save(context, catalog.copy(assignments = assignments))
    }

    fun checkNewName(context: Context, name: String): String = synchronized(lock) {
        val catalog = read(context)
        val title = validName(name)
        require(catalog.voices.size < MAX_VOICES) { "音色库已满" }
        require(catalog.voices.none { it.name.equals(title, ignoreCase = true) }) { "音色名称已存在" }
        title
    }

    fun add(context: Context, staging: File, name: String, assignRole: String? = null, autoName: Boolean = false) =
        synchronized(lock) {
            val catalog = read(context)
            require(catalog.voices.size < MAX_VOICES) { "音色库已满" }
            val title = validName(name)
            val chosen = if (autoName) generateSequence(1) { it + 1 }
                .map { if (it == 1) title else "$title $it" }
                .first { candidate -> catalog.voices.none { it.name.equals(candidate, ignoreCase = true) } }
                else title
            require(catalog.voices.none { it.name.equals(chosen, ignoreCase = true) }) { "音色名称已存在" }
            check(Audio8ModelStore.validReference(staging, checkCodes = true)) { "参考音文本或声码格式不正确" }
            val id = "voice-" + UUID.randomUUID()
            val target = File(Audio8ModelStore.root(context), "reference-$id")
            check(staging.renameTo(target)) { "无法保存音色" }
            try {
                val assignments = if (assignRole == null) catalog.assignments
                    else catalog.assignments + (roleId(assignRole) to id)
                save(context, Catalog(catalog.voices + Voice(id, chosen, target.name), assignments))
            } catch (error: Exception) {
                target.deleteRecursively()
                throw error
            }
        }

    fun rename(context: Context, id: String, name: String) = synchronized(lock) {
        val catalog = read(context)
        val title = validName(name)
        require(catalog.voices.any { it.id == id }) { "找不到此音色" }
        require(catalog.voices.none { it.id != id && it.name.equals(title, ignoreCase = true) }) { "音色名称已存在" }
        save(context, catalog.copy(voices = catalog.voices.map { if (it.id == id) it.copy(name = title) else it }))
    }

    fun remove(context: Context, id: String) {
        val retired = synchronized(lock) {
            val catalog = read(context)
            val voice = catalog.voices.firstOrNull { it.id == id } ?: error("找不到此音色")
            save(context, Catalog(catalog.voices.filterNot { it.id == id }, catalog.assignments.filterValues { it != id }))
            if (catalog.voices.any { it.id != id && it.directory == voice.directory }) null
            else File(Audio8ModelStore.root(context), voice.directory)
        }
        retired?.let { OfflineAudio8TtsEngine.retireReference(it) }
    }

    internal fun directory(context: Context, id: String?): File? = synchronized(lock) {
        if (id != null && !isCustomId(id)) return@synchronized null
        runCatching {
            read(context).voices.firstNotNullOfOrNull {
                if (id == null || it.id == id) reference(context, it) else null
            }
        }.getOrNull()
    }

    private fun exists(context: Context, catalog: Catalog, id: String) =
        id == WHALE || id == ELLEN || catalog.voices.any { it.id == id && reference(context, it) != null }

    private fun reference(context: Context, voice: Voice) =
        File(Audio8ModelStore.root(context), voice.directory).takeIf { Audio8ModelStore.validReference(it) }

    private fun validName(name: String): String {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.codePointCount(0, clean.length) <= 40 &&
            clean.none { it.isISOControl() }) { "请输入 1–40 字的音色名称" }
        return clean
    }

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "audio8-voice-library-v1.json"))

    private fun read(context: Context): Catalog {
        val bytes = try { file(context).readFully() } catch (_: FileNotFoundException) { return fromLegacy(context) }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1) { "音色库版本不受支持" }
        val raw = json.getJSONArray("voices")
        require(raw.length() <= MAX_VOICES)
        val voices = (0 until raw.length()).map { index ->
            val row = raw.getJSONObject(index)
            val id = row.getString("id")
            val directory = row.getString("directory")
            require(isCustomId(id) && (directory == "reference-$id" || legacy.matches(directory)))
            Voice(id, validName(row.getString("name")), directory)
        }
        val assignments = json.getJSONObject("assignments")
        return Catalog(voices, assignments.keys().asSequence().associateWith { assignments.getString(it) })
    }

    private fun fromLegacy(context: Context): Catalog {
        val voices = mutableListOf<Voice>()
        val assignments = mutableMapOf<String, String>()
        for ((role, title) in listOf("whale" to "鲸鱼娘旧音色", "ellen" to "艾莲旧音色")) {
            val directory = Audio8ModelStore.legacyRoleReference(context, role)?.name ?: continue
            val match = legacy.matchEntire(directory) ?: continue
            val id = "voice-" + match.groupValues[2]
            if (voices.none { it.id == id }) voices.add(Voice(id, title, directory))
            assignments[role] = id
        }
        return Catalog(voices, assignments)
    }

    private fun save(context: Context, catalog: Catalog) {
        val voices = JSONArray()
        catalog.voices.forEach { voice -> voices.put(JSONObject().put("id", voice.id)
            .put("name", voice.name).put("directory", voice.directory)) }
        val bytes = JSONObject().put("version", 1).put("voices", voices)
            .put("assignments", JSONObject(catalog.assignments)).toString().toByteArray(Charsets.UTF_8)
        val target = file(context)
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) }
        catch (error: Exception) { target.failWrite(output); throw error }
    }
}

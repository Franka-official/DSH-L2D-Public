package com.prts.client.ui.assets

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CharacterItem(
    val name: String,
    val displayName: String,
    val localDir: String,
    val builtin: Boolean,
    val modelJson: String,
)

/** APK 内置角色和用户明确选择的本地 ZIP，均从 FileReferences 解析模型文件。 */
class CharacterLibrary(private val ctx: Context) {
    private val root = File(ctx.filesDir, "live2d").apply { mkdirs() }

    fun all(): List<CharacterItem> {
        synchronized(builtinLock) { BUILTINS.forEach { ensureBuiltin(it) } }
        return root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.mapNotNull { readItem(it, it.name in BUILTINS) }
            ?.sortedWith(compareBy<CharacterItem> { BUILTINS.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.displayName }) ?: emptyList()
    }

    fun current(): String {
        val selected = ctx.getSharedPreferences("prts", Context.MODE_PRIVATE)
            .getString("character", DEFAULT) ?: DEFAULT
        val items = all()
        return items.firstOrNull { it.name == selected }?.name ?: items.firstOrNull()?.name ?: DEFAULT
    }

    fun resolve(name: String): CharacterItem? = all().firstOrNull { it.name == name }

    fun setCurrent(name: String) {
        if (resolve(name) == null) return
        ctx.getSharedPreferences("prts", Context.MODE_PRIVATE).edit()
            .putString("character", name).apply()
    }

    /** ZIP 路径限制在临时目录；导入不覆盖内置或已有角色。 */
    fun importFromZip(zipFile: File): CharacterItem? {
        val token = UUID.randomUUID().toString()
        val unpacked = File(ctx.cacheDir, "live2d-import-$token")
        val staged = File(root, ".import-$token")
        val target = File(root, "import-$token")
        try {
            check(unpacked.mkdirs())
            val model = CharacterArchive.extractOneModel(zipFile, unpacked)
            normalizeReferences(model, write = true)
            model.parentFile!!.copyRecursively(staged, overwrite = false)
            val standard = File(staged, MODEL_FILE)
            if (model.name != MODEL_FILE) File(staged, model.name).copyTo(standard, overwrite = true)
            val oldProfile = readProfile(staged)
            val displayName = oldProfile?.optString("displayName")?.takeIf { it.isNotBlank() }
                ?: model.name.removeSuffix(".model3.json")
            File(staged, PROFILE_FILE).writeText(JSONObject().apply {
                put("id", target.name)
                put("displayName", displayName.take(80))
                put("version", 1)
                put("modelJson", MODEL_FILE)
            }.toString(2), Charsets.UTF_8)
            check(readItem(staged, false) != null) { "Model references are incomplete" }
            check(staged.renameTo(target)) { "Unable to install imported model" }
            return readItem(target, false)
        } catch (error: Exception) {
            if (error is IllegalArgumentException) throw error
            throw IllegalStateException("角色导入失败，请检查资源是否完整及可用空间", error)
        } finally {
            unpacked.deleteRecursively()
            staged.deleteRecursively()
        }
    }

    private fun ensureBuiltin(name: String) {
        val assetBase = "live2d/$name"
        val assetProfile = try {
            ctx.assets.open("$assetBase/$PROFILE_FILE").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } catch (_: Exception) { return }
        val dest = File(root, name)
        val installed = readProfile(dest)
        if (installed?.optString("version") == assetProfile.optString("version") && readItem(dest, true) != null) return
        val staged = File(root, ".install-$name-${UUID.randomUUID()}")
        val previous = File(root, ".previous-$name-${UUID.randomUUID()}")
        var movedPrevious = false
        try {
            copyAssetTree(assetBase, staged)
            check(readItem(staged, true) != null) { "Built-in model references are incomplete" }
            if (dest.exists()) {
                check(dest.renameTo(previous))
                movedPrevious = true
            }
            check(staged.renameTo(dest))
            if (movedPrevious) previous.deleteRecursively()
        } catch (_: Exception) {
            if (movedPrevious && !dest.exists()) previous.renameTo(dest)
        } finally {
            staged.deleteRecursively()
        }
    }

    private fun readItem(dir: File, builtin: Boolean): CharacterItem? = try {
        val profile = readProfile(dir)
        val manifest = profile?.optString("modelJson")?.takeIf { it.isNotBlank() }?.let { safeChild(dir, it) }
            ?: File(dir, MODEL_FILE).takeIf { it.isFile }
            ?: dir.listFiles { f -> f.isFile && f.name.endsWith(".model3.json") }?.singleOrNull()
        if (manifest == null || !manifest.isFile) null else {
            validateReferences(manifest)
            CharacterItem(dir.name, profile?.optString("displayName")?.takeIf { it.isNotBlank() } ?: labelOf(dir.name),
                dir.absolutePath, builtin, manifest.absolutePath)
        }
    } catch (_: Exception) { null }

    private fun validateReferences(manifest: File) {
        normalizeReferences(manifest, write = false)
    }

    private fun normalizeReferences(manifest: File, write: Boolean) {
        val document = try { JSONObject(manifest.readText(Charsets.UTF_8).removePrefix("\uFEFF")) }
            catch (_: Exception) { throw IllegalArgumentException("模型描述文件不是有效的UTF-8 JSON") }
        val refs = document.optJSONObject("FileReferences")
            ?: throw IllegalArgumentException("模型描述缺少FileReferences")
        val base = manifest.parentFile!!
        val caseIndex by lazy { base.walkTopDown().filter { it.isFile }.groupBy {
            it.relativeTo(base).invariantSeparatorsPath.lowercase()
        } }
        fun requireFile(relative: String): String {
            val normal = CharacterArchive.normalized(relative)
            val exact = safeChild(base, normal)
            val found = if (exact.isFile) exact else {
                val candidates = caseIndex[normal.lowercase()].orEmpty()
                require(candidates.size == 1) { "缺少或无法唯一匹配模型资源：${normal.take(80)}" }
                candidates.single()
            }
            return found.canonicalFile.relativeTo(base.canonicalFile).invariantSeparatorsPath
        }
        refs.put("Moc", requireFile(refs.optString("Moc")))
        val textures = refs.optJSONArray("Textures") ?: throw IllegalArgumentException("模型缺少纹理列表")
        require(textures.length() > 0) { "模型纹理列表为空" }
        for (i in 0 until textures.length()) textures.put(i, requireFile(textures.getString(i)))
        for (key in listOf("Physics", "Pose", "UserData", "DisplayInfo")) {
            refs.optString(key).takeIf { it.isNotBlank() }?.let { refs.put(key, requireFile(it)) }
        }
        refs.optJSONArray("Expressions")?.let { expressions ->
            for (i in 0 until expressions.length()) {
                val expression = expressions.getJSONObject(i)
                expression.put("File", requireFile(expression.getString("File")))
            }
        }
        refs.optJSONObject("Motions")?.let { groups ->
            val names = groups.keys()
            while (names.hasNext()) {
                val motions = groups.getJSONArray(names.next())
                for (i in 0 until motions.length()) {
                    val motion = motions.getJSONObject(i)
                    motion.put("File", requireFile(motion.getString("File")))
                    motion.optString("Sound").takeIf { it.isNotBlank() }?.let { motion.put("Sound", requireFile(it)) }
                }
            }
        }
        if (write) manifest.writeText(document.toString(2), Charsets.UTF_8)
    }

    private fun readProfile(dir: File): JSONObject? = try {
        JSONObject(File(dir, PROFILE_FILE).readText(Charsets.UTF_8))
    } catch (_: Exception) { null }

    private fun safeChild(base: File, relative: String): File {
        return CharacterArchive.child(base, relative)
    }

    private fun copyAssetTree(relative: String, dest: File) {
        check(dest.isDirectory || dest.mkdirs())
        for (name in ctx.assets.list(relative) ?: emptyArray()) {
            val assetPath = "$relative/$name"
            val children = ctx.assets.list(assetPath)
            if (!children.isNullOrEmpty()) copyAssetTree(assetPath, File(dest, name)) else {
                ctx.assets.open(assetPath).use { input -> File(dest, name).outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun labelOf(name: String): String = when (name) {
        "ellen" -> "艾莲"
        "whale" -> "鲸鱼娘"
        else -> name
    }

    companion object {
        const val DEFAULT = "whale"
        const val MODEL_FILE = "model.model3.json"
        const val PROFILE_FILE = "profile.json"
        private val BUILTINS = listOf("whale", "ellen")
        private val builtinLock = Any()
    }
}

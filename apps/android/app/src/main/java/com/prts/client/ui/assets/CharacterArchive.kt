package com.prts.client.ui.assets

import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** Real ZIP extraction policy, shared with offline fixtures. No model files are executed. */
object CharacterArchive {
    const val MAX_ENTRIES = 4096
    const val MAX_BYTES = 1024L * 1024 * 1024

    fun normalized(relative: String): String {
        val path = relative.replace('\\', '/')
        require(path.isNotBlank() && !path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path) && '\u0000' !in path) {
            "压缩包包含绝对路径或无效文件名"
        }
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.isNotEmpty() && parts.none { it == ".." }) { "模型文件路径不能跳出角色目录" }
        return parts.joinToString("/")
    }
    fun ignored(relative: String): Boolean {
        val parts = relative.replace('\\', '/').split('/')
        return parts.any { it == "__MACOSX" } || parts.lastOrNull()?.let { it.startsWith("._") || it == ".DS_Store" } == true
    }
    fun child(base: File, relative: String): File {
        val result = File(base, normalized(relative)).canonicalFile
        require(result.path.startsWith(base.canonicalPath + File.separator)) { "模型路径超出角色目录" }
        return result
    }
    private fun charset(zip: File): Charset {
        for (name in listOf("UTF-8", "GBK")) {
            val candidate = Charset.forName(name)
            try {
                ZipFile(zip, candidate).use { archive ->
                    val entries = archive.entries(); var count = 0
                    while (entries.hasMoreElements()) {
                        entries.nextElement().name
                        require(++count <= MAX_ENTRIES) { "压缩包文件数量过多" }
                    }
                }
                return candidate
            } catch (error: Exception) {
                if (error.message == "压缩包文件数量过多") throw error
            }
        }
        throw IllegalArgumentException("压缩包无法读取，请使用UTF-8或GBK文件名重新打包")
    }
    fun extractOneModel(zip: File, destination: File): File {
        check(destination.isDirectory) { "角色临时目录无法创建" }
        var bytes = 0L; val files = mutableSetOf<String>()
        ZipFile(zip, charset(zip)).use { archive ->
            val entries = archive.entries(); var count = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement(); require(++count <= MAX_ENTRIES) { "压缩包文件数量过多" }
                if (ignored(entry.name)) continue
                val name = normalized(entry.name); val output = child(destination, name)
                if (entry.isDirectory || entry.name.endsWith('\\')) { check(output.isDirectory || output.mkdirs()); continue }
                require(files.add(name)) { "压缩包包含重复文件路径" }
                check(output.parentFile?.isDirectory == true || output.parentFile?.mkdirs() == true)
                archive.getInputStream(entry).use { input -> output.outputStream().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        bytes += n; require(bytes <= MAX_BYTES) { "解压后的角色不能超过1 GiB" }
                        stream.write(buffer, 0, n)
                    }
                } }
            }
        }
        val models = destination.walkTopDown().filter { it.isFile && it.name.lowercase().endsWith(".model3.json") }.toList()
        require(models.isNotEmpty()) { "没有找到.model3.json模型描述文件" }
        require(models.size == 1) { "压缩包内有多套模型，请只保留要导入的一套" }
        return models.single()
    }
}

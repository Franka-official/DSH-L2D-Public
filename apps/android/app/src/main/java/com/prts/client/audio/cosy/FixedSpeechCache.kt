package com.prts.client.audio.cosy

import java.io.File
import java.util.Properties
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Bounded fixed welcome only. No ordinary conversation text enters this store. */
class FixedSpeechCache(private val root: File) {
    data class Slot internal constructor(val language: String, val segment: Int)
    data class Stamp(val name: String, val bytes: Long, val modified: Long)
    data class Signature(val text: String, val referenceVoiceId: String, val referenceLanguage: String,
        val referenceDirectory: String, val modelId: String, val referenceFiles: List<Stamp>)
    data class Hit(val wav: File, val audioSeconds: Double, val sampleRate: Int)

    private fun signature(value: Signature) = Properties().apply {
        setProperty("version", VERSION.toString())
        setProperty("text", value.text)
        setProperty("referenceVoiceId", value.referenceVoiceId)
        setProperty("referenceLanguage", value.referenceLanguage)
        setProperty("referenceDirectory", value.referenceDirectory)
        setProperty("modelId", value.modelId)
        setProperty("referenceFiles", value.referenceFiles.joinToString("\n") { "${it.name}\t${it.bytes}\t${it.modified}" })
    }

    private fun directory(slot: Slot): File {
        require(slot.language in LANGUAGES && slot.segment in 1..MAX_SEGMENTS)
        return File(root, slot.language)
    }

    /** Returned audio is always a disposable copy, never the persistent cache file. */
    fun load(slot: Slot, value: Signature, outputDirectory: File): Hit? = runCatching {
        val directory = directory(slot)
        val metadata = File(directory, "segment-${slot.segment}.properties")
        val audio = File(directory, "segment-${slot.segment}.wav")
        if (!metadata.isFile || !audio.isFile || !validWave(audio)) return null
        val stored = Properties().apply { metadata.reader(Charsets.UTF_8).use(::load) }
        val expected = signature(value)
        if (expected.stringPropertyNames().any { stored.getProperty(it) != expected.getProperty(it) }) return null
        val bytes = stored.getProperty("wavBytes")?.toLongOrNull() ?: return null
        val seconds = stored.getProperty("audioSeconds")?.toDoubleOrNull() ?: return null
        val rate = stored.getProperty("sampleRate")?.toIntOrNull() ?: return null
        if (bytes != audio.length() || !seconds.isFinite() || seconds <= 0 || rate != 24000) return null
        check(outputDirectory.isDirectory || outputDirectory.mkdirs())
        val copy = File(outputDirectory, "opening-${System.nanoTime()}.wav")
        try { audio.copyTo(copy); Hit(copy, seconds, rate) }
        catch (error: Exception) { copy.delete(); throw error }
    }.getOrNull()

    fun store(slot: Slot, value: Signature, wav: File, audioSeconds: Double, sampleRate: Int): Boolean = runCatching {
        require(validWave(wav) && audioSeconds.isFinite() && audioSeconds > 0 && sampleRate == 24000)
        val directory = directory(slot)
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "segment-${slot.segment}.wav")
        val metadata = File(directory, "segment-${slot.segment}.properties")
        val temporaryAudio = File(directory, "segment-${slot.segment}.wav.writing")
        val temporaryMetadata = File(directory, "segment-${slot.segment}.properties.writing")
        try {
            wav.copyTo(temporaryAudio, overwrite = true)
            check(temporaryAudio.length() == wav.length())
            signature(value).apply {
                setProperty("wavBytes", wav.length().toString())
                setProperty("audioSeconds", audioSeconds.toString())
                setProperty("sampleRate", sampleRate.toString())
            }.also { props -> temporaryMetadata.writer(Charsets.UTF_8).use { props.store(it, "Fixed Ellen opening; not chat history") } }
            Files.move(temporaryAudio.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(temporaryMetadata.toPath(), metadata.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } finally { temporaryAudio.delete(); temporaryMetadata.delete() }
    }.getOrDefault(false)

    companion object {
        const val VERSION = 1
        const val MAX_SEGMENTS = 8
        private val LANGUAGES = setOf("zh", "ja", "en")
        private const val MAX_WAV_BYTES = 32L * 1024 * 1024

        fun slotFor(forwarded: Boolean, replyId: String?, language: String, segment: Int): Slot? =
            if (forwarded && replyId == "local-opening-ellen-v1" && language in LANGUAGES && segment in 1..MAX_SEGMENTS)
                Slot(language, segment) else null

        fun stamps(files: List<File>): List<Stamp> = files.map { Stamp(it.name, it.length(), it.lastModified()) }

        private fun validWave(file: File): Boolean {
            if (!file.isFile || file.length() !in 45..MAX_WAV_BYTES) return false
            val bytes = ByteArray(12)
            return file.inputStream().use { stream ->
                stream.read(bytes) == bytes.size && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"
            }
        }
    }
}

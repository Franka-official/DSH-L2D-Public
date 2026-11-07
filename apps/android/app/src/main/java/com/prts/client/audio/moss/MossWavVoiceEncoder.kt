package com.prts.client.audio.moss

import android.content.Context
import android.net.Uri
import com.prts.client.audio.audio8.Audio8ReferenceWav
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Bundled reference encoder. Loads neither model weights nor an inference session at startup. */
internal object MossWavVoiceEncoder {
    private const val ASSETS = "moss_encoder"
    private const val GRAPH = "moss_audio_tokenizer_encode.onnx"
    private const val WEIGHTS = "moss_audio_tokenizer_encode.data"
    private val expected = mapOf(GRAPH to 815_775L, WEIGHTS to 44_507_136L)

    fun ready(context: Context): Boolean = MossNanoNative.available &&
        context.assets.list(ASSETS).orEmpty().toSet().containsAll(expected.keys)

    /** Call on a worker. Returns one 16-code prompt row per audio frame. */
    @Synchronized
    fun encode(context: Context, source: Uri): List<IntArray> {
        check(ready(context)) { "此版本未包含 MOSS WAV 编码器" }
        val waveform = context.contentResolver.openInputStream(source)?.use(Audio8ReferenceWav::readForMoss)
            ?: error("无法打开 WAV 文件")
        val graph = materialize(context)
        val codes = MossNanoNative.encodeVoice(graph.absolutePath, waveform)
        check(codes.size in 16..4096 && codes.size % 16 == 0) { "MOSS 音色编码结果无效" }
        return List(codes.size / 16) { frame -> codes.copyOfRange(frame * 16, (frame + 1) * 16) }
    }

    private fun materialize(context: Context): File {
        val directory = File(context.filesDir, "moss_voice_encoder_v1")
        check(directory.isDirectory || directory.mkdirs()) { "无法建立 MOSS 编码器目录" }
        for ((name, size) in expected) {
            val destination = File(directory, name)
            if (destination.isFile && destination.length() == size) continue
            check(directory.usableSpace >= size) { "空间不足，无法安装 MOSS 编码器" }
            val staged = File.createTempFile("encoder-", ".part", directory)
            try {
                val copied = context.assets.open("$ASSETS/$name").use { input ->
                    staged.outputStream().use { output -> input.copyTo(output) }
                }
                check(copied == size) { "MOSS 编码器文件不完整" }
                Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } finally {
                if (staged.exists()) staged.delete()
            }
        }
        return File(directory, GRAPH)
    }
}

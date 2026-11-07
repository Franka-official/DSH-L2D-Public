package com.prts.client.audio.moss

import androidx.annotation.Keep
import java.io.Closeable
import java.io.File
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** CPU runtime for the two pinned OpenMOSS Nano ONNX releases. Model assets are installed separately. */
internal class MossNanoEngine(modelRoot: File, cpuThreads: Int = 2) : Closeable {
    data class Result(val pcm: FloatArray, val generatedFrames: Int, val finishReason: String,
                      val sampleRate: Int = 48_000)
    data class Voice(val id: String, val name: String)

    private val tts = File(modelRoot, "MOSS-TTS-Nano-100M-ONNX")
    private val codec = File(modelRoot, "MOSS-Audio-Tokenizer-Nano-ONNX")
    private val manifest = loadManifest(tts)
    private val templates = manifest.getJSONObject("prompt_templates")
    private val prefix = templates.getJSONArray("user_prompt_prefix_token_ids").ints()
    private val afterReference = templates.getJSONArray("user_prompt_after_reference_token_ids").ints()
    private val assistant = templates.getJSONArray("assistant_prompt_prefix_token_ids").ints()
    private val builtinVoices = manifest.getJSONArray("builtin_voices")
    private var handle: Long

    init {
        check(available) { "此版本未包含 MOSS CPU 运行时" }
        val config = manifest.getJSONObject("tts_config")
        require(config.getInt("n_vq") == 16 && config.getInt("vocab_size") == 16_384 &&
            config.getInt("audio_pad_token_id") == 1024 && config.getInt("audio_start_token_id") == 6 &&
            config.getInt("audio_end_token_id") == 7 && config.getInt("audio_user_slot_token_id") == 8 &&
            config.getInt("audio_assistant_slot_token_id") == 9) { "MOSS 模型版本不匹配" }
        handle = MossNanoNative.open(tts.absolutePath, codec.absolutePath, cpuThreads.coerceIn(1, 4))
        check(handle != 0L) { "无法加载 MOSS CPU 模型" }
    }

    @Synchronized
    fun synthesize(text: String, voice: String? = null, maxFrames: Int = 160,
                   privateVoice: File? = null,
                   cancelled: () -> Boolean = { false }): Result {
        check(handle != 0L) { "MOSS 引擎已关闭" }
        if (cancelled()) throw CancellationException()
        val bytes = text.trim().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..8192) { "MOSS 文本长度无效" }
        val voiceId = voice ?: "Xiaoyu"
        val item = if (privateVoice != null) {
            require(privateVoice.isFile && privateVoice.length() in 1..65_536) { "MOSS 音色文件无效" }
            JSONObject(privateVoice.readText(Charsets.UTF_8)).also {
                require(it.getString("voice") == voiceId) { "MOSS 音色不匹配" }
            }
        } else (0 until builtinVoices.length()).asSequence().map(builtinVoices::getJSONObject)
            .firstOrNull { it.getString("voice") == voiceId } ?: error("找不到 MOSS 内置音色")
        val prompt = item.getJSONArray("prompt_audio_codes")
        require(prompt.length() in 1..256) { "MOSS 参考声码长度无效" }
        val codes = IntArray(prompt.length() * 16)
        for (i in 0 until prompt.length()) {
            val frame = prompt.getJSONArray(i)
            require(frame.length() == 16)
            for (j in 0 until 16) {
                codes[i * 16 + j] = frame.getInt(j).also { require(it in 0..1023) }
            }
        }
        val report = IntArray(2)
        val audio = MossNanoNative.generate(handle, bytes, prefix, afterReference, assistant, codes,
            maxFrames.coerceIn(1, 160), System.nanoTime(), report, MossNanoNative.Cancellation(cancelled))
        if (cancelled()) throw CancellationException()
        check(audio.isNotEmpty()) { "MOSS 没有生成语音" }
        return Result(audio, report[0], if (report[1] != 0) "eos" else "length")
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) MossNanoNative.close(handle)
        handle = 0
    }

    companion object {
        val available: Boolean get() = MossNanoNative.available

        fun voices(modelRoot: File): List<Voice> {
            val entries = loadManifest(File(modelRoot, "MOSS-TTS-Nano-100M-ONNX")).getJSONArray("builtin_voices")
            return (0 until entries.length()).map { entries.getJSONObject(it) }.map {
                Voice(it.getString("voice"), it.optString("display_name").ifBlank { it.getString("voice") })
            }
        }

        private fun loadManifest(tts: File): JSONObject {
            val manifest = File(tts, "browser_poc_manifest.json")
            require(manifest.isFile && manifest.length() in 1..1_048_576) { "MOSS 模型清单未就绪" }
            return JSONObject(manifest.readText(Charsets.UTF_8)).also {
                require(it.getInt("format_version") == 1) { "MOSS 模型版本不匹配" }
            }
        }

        private fun JSONArray.ints(): IntArray {
            require(length() in 1..128)
            return IntArray(length()) { getInt(it).also { token -> require(token in 0..16_383) } }
        }
    }
}

@Keep
internal object MossNanoNative {
    val available = runCatching { System.loadLibrary("prts_moss_tts") }.isSuccess

    @Keep
    fun interface Cancellation {
        fun cancelled(): Boolean
    }

    external fun open(ttsDir: String, codecDir: String, threads: Int): Long
    external fun generate(handle: Long, text: ByteArray, prefix: IntArray, afterReference: IntArray,
                          assistant: IntArray, codes: IntArray, maxFrames: Int, seed: Long,
                          report: IntArray, cancel: Cancellation): FloatArray
    external fun encodeVoice(graph: String, mono: FloatArray): IntArray
    external fun close(handle: Long)
}

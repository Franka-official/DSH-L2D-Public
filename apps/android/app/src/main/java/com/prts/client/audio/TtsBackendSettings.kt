package com.prts.client.audio

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.prts.client.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate from LLM/relay credentials. The key and settings do not enter Android backups. */
object TtsBackendSettings {
    const val LOCAL = "local_audio8"
    private const val LEGACY_LOCAL = "local_qwen"
    const val API = "openai_speech_api"
    const val LOCAL_ENGINE_AUDIO8 = "audio8"
    const val LOCAL_ENGINE_MOSS = "moss_nano"
    const val LOCAL_CODEC_AUTO = "auto" // Legacy stored GPU/automatic setting.
    private const val LOCAL_ENGINE_KEY = "local_engine"
    const val LOCAL_CODEC_CPU = "ort_cpu"
    const val LOCAL_CODEC_NPU = "qnn_htp"
    private const val LOCAL_CODEC_KEY = "local_codec_backend"
    private const val ALIAS = "prts.tts-api.key.v1"
    private fun defaultMode(): String = BuildConfig.DEFAULT_TTS_BACKEND.takeIf { it in setOf(LOCAL, API) } ?: LOCAL
    private fun defaultLocalEngine(): String = BuildConfig.DEFAULT_LOCAL_TTS_ENGINE
    data class Options(val mode: String = defaultMode(), val api: SpeechApiOptions = SpeechApiOptions(),
                       val hasApiKey: Boolean = false, val localCodecBackend: String = defaultLocalCodec(),
                       val localEngine: String = defaultLocalEngine())

    fun isNpuSupported(engine: String = LOCAL_ENGINE_AUDIO8): Boolean {
        if (engine != LOCAL_ENGINE_AUDIO8 || !BuildConfig.AUDIO8_NPU_BUNDLED || !Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            !Build.DEVICE.equals("fuxi", ignoreCase = true) || "arm64-v8a" !in Build.SUPPORTED_ABIS) return false
        return Build.BOARD.equals("kalama", ignoreCase = true) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL.equals("SM8550", ignoreCase = true))
    }

    private fun defaultLocalCodec(): String = if (isNpuSupported()) LOCAL_CODEC_NPU else LOCAL_CODEC_CPU

    private fun readLocalEngine(value: JSONObject): String =
        value.optString(LOCAL_ENGINE_KEY).takeIf { it in setOf(LOCAL_ENGINE_AUDIO8, LOCAL_ENGINE_MOSS) } ?: defaultLocalEngine()

    private fun readLocalCodecBackend(value: JSONObject): String =
        if (value.optString(LOCAL_CODEC_KEY) == LOCAL_CODEC_CPU) LOCAL_CODEC_CPU else defaultLocalCodec()

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "tts-backend-v1.json"))
    private fun read(context: Context): JSONObject = try {
        val store = file(context)
        if (!store.baseFile.exists()) JSONObject() else JSONObject(String(store.readFully(), Charsets.UTF_8)).also {
            require(it.optInt("schema") == 1)
        }
    } catch (_: Exception) { throw IllegalStateException("语音设置无法读取，请重新保存语音配置") }

    @Synchronized fun load(context: Context): Options {
        val value = read(context)
        val savedMode = value.optString("mode", defaultMode())
        val mode = if (savedMode == LEGACY_LOCAL) LOCAL else savedMode
        val engine = readLocalEngine(value)
        return Options(mode.takeIf { it in setOf(LOCAL, API) } ?: defaultMode(),
            SpeechApiOptions(value.optString("endpoint"), value.optString("model", "tts-1"), value.optString("voice", "alloy")),
            value.optString("key_cipher").isNotEmpty(),
            if (engine == LOCAL_ENGINE_MOSS) LOCAL_CODEC_CPU else readLocalCodecBackend(value), engine)
    }

    @Synchronized fun forCall(context: Context): SpeechApiCredentials {
        val value = read(context)
        val options = SpeechApiOptions(value.optString("endpoint"), value.optString("model", "tts-1"),
            value.optString("voice", "alloy")).normalized()
        val saved = value.optString("key_cipher")
        val key = if (saved.isEmpty()) "" else try { decrypt(saved, options.endpoint) }
            catch (_: Exception) { throw IllegalStateException("保存的语音Key无法读取，请在设置中重新填写") }
        return SpeechApiCredentials(options, key)
    }

    @Synchronized fun save(context: Context, mode: String, api: SpeechApiOptions,
                           newKey: String? = null, clearKey: Boolean = false, localCodecBackend: String? = null,
                           localEngine: String? = null) {
        require(mode in setOf(LOCAL, API))
        require(localCodecBackend == null || localCodecBackend in setOf(LOCAL_CODEC_AUTO, LOCAL_CODEC_CPU, LOCAL_CODEC_NPU))
        require(localEngine == null || localEngine in setOf(LOCAL_ENGINE_AUDIO8, LOCAL_ENGINE_MOSS))
        require(newKey == null || (newKey.length <= 8192 && newKey.none { it == '\r' || it == '\n' })) { "语音Key格式不正确" }
        val old = runCatching { read(context) }.getOrElse { JSONObject() }
        val engine = localEngine ?: readLocalEngine(old)
        require(localCodecBackend != LOCAL_CODEC_NPU || isNpuSupported(engine)) { "所选语音模型的 NPU 路线尚未验证" }
        val audio8Codec = if (engine == LOCAL_ENGINE_AUDIO8 && localCodecBackend != null)
            localCodecBackend.takeUnless { it == LOCAL_CODEC_AUTO } ?: defaultLocalCodec()
            else readLocalCodecBackend(old)
        // Selecting local must not be blocked by unfinished API edits.
        val normalized = if (mode == API) api.normalized() else SpeechApiOptions(old.optString("endpoint"),
            old.optString("model", "tts-1"), old.optString("voice", "alloy"))
        // Never carry a saved credential to a changed endpoint without an explicit new key.
        val cipher = when {
            clearKey -> ""
            mode == API && !newKey.isNullOrBlank() -> encrypt(newKey, normalized.endpoint)
            old.optString("endpoint") == normalized.endpoint -> old.optString("key_cipher")
            else -> ""
        }
        val value = JSONObject().put("schema", 1).put("mode", mode).put("endpoint", normalized.endpoint)
            .put("model", normalized.model).put("voice", normalized.voice).put("key_cipher", cipher)
            .put(LOCAL_ENGINE_KEY, engine).put(LOCAL_CODEC_KEY, audio8Codec)
        val store = file(context)
        val output = store.startWrite()
        try { output.write(value.toString().toByteArray(Charsets.UTF_8)); store.finishWrite(output) }
        catch (_: Exception) { store.failWrite(output); throw IllegalStateException("语音设置没有保存成功，请重试") }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun encrypt(value: String, endpoint: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(endpoint.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(value: String, endpoint: String): String {
        val parts = value.split('.', limit = 2); require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.updateAAD(endpoint.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}

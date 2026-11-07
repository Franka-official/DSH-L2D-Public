package com.prts.client.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

enum class ConnectionMode(val title: String) {
    DIRECT_API("API 接入"),
    DEEPSEEK_HARNESS("DeepSeek Harness"),
    ASTRBOT("AstrBot"),
    LOCAL_DSH("本机 DSHGo")
}

data class ConnectionPreset(val id: String, val title: String, val description: String,
    val mode: ConnectionMode, val endpoint: String = "", val model: String = "")
data class VoiceProfile(val modeId: String = "backend", val voiceId: String = "current", val language: String = "auto")
data class VoiceChoice(val id: String, val title: String, val description: String,
    val available: Boolean = true, val unavailableReason: String = "")

data class ConnectionProfile(
    val mode: ConnectionMode,
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
    val prompt: String = "",
    val profileId: String = "",
    val isPaired: Boolean = false,
    val requiresPairing: Boolean = false,
) {
    val usesLegacyCallSocket: Boolean
        get() = !isPaired && !requiresPairing && mode == ConnectionMode.ASTRBOT &&
            endpoint.isBlank()

    fun validationError(): String? {
        if (mode == ConnectionMode.LOCAL_DSH) return if (isPaired && apiKey.length >= 32 &&
            endpoint == com.prts.client.localdsh.LocalDshBridge.BASE) null else "请在DSHGo插件设置连接本机"
        if (requiresPairing) return "请先把手机显示的配对码填入电脑插件"
        if (isPaired) return if (profileId.isNotBlank() && apiKey.length >= 32) null else "配对尚未完成"
        if (mode == ConnectionMode.ASTRBOT && endpoint.isBlank())
            return if (apiKey.isBlank()) null else "请填写 AstrBot 地址后再设置它的 Key"
        val uri = try { URI(endpoint.trim()) } catch (_: Exception) { return "请填写完整的连接地址" }
        val allowed = setOf("http", "https")
        if (uri.scheme?.lowercase() !in allowed || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null)
            return "请填写完整的服务地址，账号和 Key 单独填写"
        if (uri.rawQuery != null) return "连接地址不需要带查询参数，Key 请填在下方"
        if (apiKey.any { it == '\r' || it == '\n' }) return "Key 中不能包含换行"
        if (prompt.toByteArray(Charsets.UTF_8).size > 65536) return "行为准则请控制在 64 KB 以内"
        return null
    }
}

data class PairingBinding(val mode: ConnectionMode, val relayUrl: String, val bindingId: String,
    val clientToken: String, val code: String, val expiresAtMillis: Long, val paired: Boolean,
    val supportsImages: Boolean = false)

/** Saved endpoints are separate per mode; keys stay encrypted with an Android Keystore key. */
object ConnectionSettings {
    private const val FILE = "call_connections"
    private const val ALIAS = "prts-call-connection-keys-v1"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun presets(ctx: Context): List<ConnectionPreset> = buildList {
        add(ConnectionPreset("current_astrbot", "当前 AstrBot", "沿用已保存的人格和通话接入", ConnectionMode.ASTRBOT))
        for (mode in listOf(ConnectionMode.DIRECT_API, ConnectionMode.DEEPSEEK_HARNESS)) {
            val p = load(ctx, mode)
            if (p.endpoint.isNotBlank()) add(ConnectionPreset("saved_${mode.name.lowercase()}",
                "已保存的 ${mode.title}", p.model.ifBlank { "使用之前保存的接入设置" }, mode, p.endpoint, p.model))
        }
        add(ConnectionPreset("custom", "自行配置", "展开高级设置可填写地址、密钥和行为准则", selectedMode(ctx)))
    }
    fun selectedPresetId(ctx: Context): String {
        prefs(ctx).getString("selected_preset", null)?.let { return it }
        val mode = selectedMode(ctx)
        return if (mode == ConnectionMode.ASTRBOT) "current_astrbot"
            else if (!prefs(ctx).getString("${mode.name.lowercase()}_endpoint", "").isNullOrBlank()) "saved_${mode.name.lowercase()}"
            else "custom"
    }
    fun saveSelectedPresetId(ctx: Context, id: String) { prefs(ctx).edit().putString("selected_preset", id).apply() }
    fun loadVoiceProfile(ctx: Context) = VoiceProfile(prefs(ctx).getString("voice_mode", "backend")!!,
        prefs(ctx).getString("voice_id", "current")!!, prefs(ctx).getString("voice_language", "auto")!!)
    fun saveVoiceProfile(ctx: Context, profile: VoiceProfile) {
        require(profile.modeId in setOf("backend", "cosyvoice3_phone")) { "未知语音模型" }
        require(voiceChoices(ctx, profile.modeId).any { it.id == profile.voiceId && it.available }) { "音色尚未在本机就绪" }
        check(prefs(ctx).edit().putString("voice_mode", profile.modeId).putString("voice_id", profile.voiceId)
            .putString("voice_language", profile.language).commit()) { "音色设置未保存" }
    }
    private fun offlineVoiceReady(ctx: Context, lang: String): Boolean {
        val root = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "cosyvoice3")
        return com.prts.client.audio.cosy.OfflineCosyVoiceEngine.MODEL_FILES.all { (name, bytes) ->
            File(root, "models/$name").let { it.isFile && it.length() == bytes }
        } && File(root, "voices/ellen_$lang/profile.json").isFile &&
            prefs(ctx).getBoolean("cosy_mnn_validated_$lang", false)
    }
    fun markOfflineVoiceValidated(ctx: Context, lang: String) {
        require(lang in setOf("zh", "ja"))
        prefs(ctx).edit().putBoolean("cosy_mnn_validated_$lang", true).apply()
    }
    fun voiceModelChoices(ctx: Context) = listOf(
        VoiceChoice("backend", "当前通话后端", "沿用已能正常通话的声音"),
        VoiceChoice("cosyvoice3_phone", "CosyVoice 3 · 手机离线 MNN", "语音在手机生成，无网络语音回退",
            offlineVoiceReady(ctx, "zh") || offlineVoiceReady(ctx, "ja"), "等待模型、参考音色和设备验证"))
    fun voiceChoices(ctx: Context, modeId: String = loadVoiceProfile(ctx).modeId): List<VoiceChoice> =
        if (modeId == "backend") listOf(VoiceChoice("current", "当前声音", "使用后端已配置的音色"))
        else listOf(VoiceChoice("ellen_zh", "艾莲 · 中文", "鲸鱼娘暂时共用这套音色", offlineVoiceReady(ctx, "zh"), "等待本机验证"),
            VoiceChoice("ellen_ja", "艾莲 · 日文", "日文音色，需日文读音前处理", offlineVoiceReady(ctx, "ja"), "等待本机验证"))

    fun selectedMode(ctx: Context): ConnectionMode =
        runCatching { ConnectionMode.valueOf(prefs(ctx).getString("selected_mode", ConnectionMode.ASTRBOT.name)!!) }
            .getOrDefault(ConnectionMode.ASTRBOT)

    fun load(ctx: Context, mode: ConnectionMode = selectedMode(ctx)): ConnectionProfile {
        if (mode == ConnectionMode.LOCAL_DSH) {
            val local = com.prts.client.localdsh.LocalDshBridge.frontend(ctx)
            return ConnectionProfile(mode, local.endpoint, local.token, profileId = local.bindingId, isPaired = true)
        }
        if (mode != ConnectionMode.DIRECT_API) {
            val paired = loadPairing(ctx, mode, true)
            val url = simpleUrl(ctx, mode)
            if (paired != null && paired.paired && paired.relayUrl == url)
                return ConnectionProfile(mode, paired.relayUrl, paired.clientToken, profileId = paired.bindingId, isPaired = true)
            val saved = prefs(ctx)
            if (saved.getBoolean("simple_${mode.name}_configured", false) &&
                !saved.getBoolean("simple_${mode.name}_legacy_allowed", false))
                return ConnectionProfile(mode, requiresPairing = true)
        }
        return loadLegacy(ctx, mode)
    }

    private fun loadLegacy(ctx: Context, mode: ConnectionMode): ConnectionProfile {
        val saved = prefs(ctx)
        val name = mode.name.lowercase()
        val endpoint = saved.getString("${name}_endpoint", null)
            ?: ""
        val encoded = saved.getString("${name}_key", null)
        return ConnectionProfile(mode, endpoint, encoded?.let { decrypt(it) } ?: "",
            saved.getString("${name}_model", "") ?: "",
            saved.getString("${name}_prompt", "") ?: "",
            saved.getString("${name}_profile_id", "") ?: "")
    }

    fun save(ctx: Context, profile: ConnectionProfile, select: Boolean = true) {
        profile.validationError()?.let { throw IllegalArgumentException(it) }
        val name = profile.mode.name.lowercase()
        val encrypted = profile.apiKey.takeIf { it.isNotEmpty() }?.let { encrypt(it) }
        val edit = prefs(ctx).edit()
            .putString("${name}_endpoint", profile.endpoint.trim().trimEnd('/'))
            .putString("${name}_model", profile.model.trim())
            .putString("${name}_prompt", profile.prompt)
            .putString("${name}_profile_id", profile.profileId.trim())
        if (encrypted == null) edit.remove("${name}_key") else edit.putString("${name}_key", encrypted)
        if (select) edit.putString("selected_mode", profile.mode.name)
        check(edit.commit()) { "连接设置没有保存成功，请重试" }
    }

    fun loadVoiceKey(ctx: Context): String = prefs(ctx).getString("voice_key", null)?.let { decrypt(it) } ?: ""

    fun saveVoiceKey(ctx: Context, value: String) {
        require(value.none { it == '\r' || it == '\n' }) { "Key 中不能包含换行" }
        val edit = prefs(ctx).edit()
        if (value.isEmpty()) edit.remove("voice_key") else edit.putString("voice_key", encrypt(value))
        check(edit.commit()) { "通话服务 Key 没有保存成功，请重试" }
    }

    fun normalizeRelayUrl(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme?.lowercase() in setOf("http", "https", "ws", "wss") &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "请填写完整的服务网址" }
        val scheme = when (uri.scheme.lowercase()) { "wss" -> "https"; "ws" -> "http"; else -> uri.scheme.lowercase() }
        var path = (uri.path ?: "").trimEnd('/')
        for (ending in listOf("/mobile-call/v1/ws", "/client-ws")) if (path.endsWith(ending)) path = path.removeSuffix(ending)
        return URI(scheme, null, uri.host, uri.port, path, null, null).toString().trimEnd('/')
    }

    fun normalizeCallSocket(value: String): String {
        val root = normalizeRelayUrl(value)
        val scheme = if (root.startsWith("https://")) "wss" else "ws"
        return scheme + "://" + root.substringAfter("://") + "/client-ws"
    }

    fun activateLocal(ctx: Context) {
        com.prts.client.localdsh.LocalDshBridge.frontend(ctx)
        check(prefs(ctx).edit().putString("selected_mode", ConnectionMode.LOCAL_DSH.name).commit())
    }

    fun simpleUrl(ctx: Context, mode: ConnectionMode): String {
        if (mode == ConnectionMode.LOCAL_DSH) return ""
        if (mode == ConnectionMode.DIRECT_API) return loadLegacy(ctx, mode).endpoint
        return prefs(ctx).getString("simple_${mode.name}_url", null) ?: runCatching {
            normalizeRelayUrl(com.prts.client.ui.Prefs.get(ctx).first)
        }.getOrDefault("")
    }

    fun hasSimpleApiKey(ctx: Context): Boolean = loadLegacy(ctx, ConnectionMode.DIRECT_API).apiKey.isNotEmpty()

    fun saveSimple(ctx: Context, mode: ConnectionMode, url: String, newApiKey: String? = null, clearApiKey: Boolean = false) {
        if (mode == ConnectionMode.LOCAL_DSH) { activateLocal(ctx); return }
        if (mode == ConnectionMode.DIRECT_API) {
            val old = loadLegacy(ctx, mode)
            val key = if (clearApiKey) "" else newApiKey?.takeIf { it.isNotBlank() } ?: old.apiKey
            val endpoint = url.trim().trimEnd('/')
            save(ctx, old.copy(endpoint = endpoint, apiKey = key,
                model = if (endpoint == old.endpoint) old.model else ""))
        } else {
            val normalized = normalizeRelayUrl(url)
            val old = loadLegacy(ctx, mode)
            val previous = simpleUrl(ctx, mode)
            val allowLegacy = normalized == previous && old.validationError() == null &&
                !prefs(ctx).getBoolean("simple_${mode.name}_configured", false)
            check(prefs(ctx).edit().putString("simple_${mode.name}_url", normalized)
                .putBoolean("simple_${mode.name}_configured", true)
                .putBoolean("simple_${mode.name}_legacy_allowed", allowLegacy ||
                    (normalized == previous && prefs(ctx).getBoolean("simple_${mode.name}_legacy_allowed", false)))
                .putString("selected_mode", mode.name).commit()) { "连接设置未保存" }
        }
    }

    fun loadPairing(ctx: Context, mode: ConnectionMode, paired: Boolean): PairingBinding? {
        val raw = prefs(ctx).getString("pair_${mode.name}_${if (paired) "active" else "pending"}", null) ?: return null
        val d = JSONObject(decrypt(raw))
        return PairingBinding(mode, d.getString("relay_url"), d.getString("binding_id"), d.getString("client_token"),
            d.optString("code"), d.optLong("expires_at_ms"), d.optBoolean("paired"), d.optBoolean("supports_images"))
    }

    fun savePairing(ctx: Context, value: PairingBinding) {
        val data = JSONObject().put("relay_url", value.relayUrl).put("binding_id", value.bindingId)
            .put("client_token", value.clientToken).put("code", value.code).put("expires_at_ms", value.expiresAtMillis)
            .put("paired", value.paired).put("supports_images", value.supportsImages)
        val edit = prefs(ctx).edit().putString("pair_${value.mode.name}_${if (value.paired) "active" else "pending"}", encrypt(data.toString()))
        if (value.paired) {
            edit.putString("simple_${value.mode.name}_url", value.relayUrl)
            edit.putBoolean("simple_${value.mode.name}_configured", true)
            edit.putBoolean("simple_${value.mode.name}_legacy_allowed", false)
            edit.remove("pair_${value.mode.name}_pending")
        }
        check(edit.commit()) { "配对信息没有保存成功" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val bytes = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv.$bytes"
    }

    private fun decrypt(encoded: String): String {
        try {
            val pieces = encoded.split('.', limit = 2)
            require(pieces.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
            return String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            throw IllegalStateException("保存的 Key 无法读取，请在设置中重新填写")
        }
    }
}

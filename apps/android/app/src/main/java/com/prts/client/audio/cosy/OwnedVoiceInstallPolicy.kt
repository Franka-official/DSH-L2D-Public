package com.prts.client.audio.cosy

object OwnedVoiceInstallPolicy {
    const val OWNER = "dsh-l2d"
    const val LEGACY_WHALE_URL = "https://github.com/QwenAudio/CosyVoice/blob/main/asset/zero_shot_prompt.wav"
    data class Profile(val id: String?, val owner: String?, val revision: String?, val language: String?,
        val builtIn: Boolean = false, val referenceSourceUrl: String? = null)
    enum class Action { KEEP, INSTALL, UPDATE, INVALID_BUNDLE }
    fun action(id: String, installedExists: Boolean, installed: Profile?, bundled: Profile?): Action {
        val owned = installed?.id == id && installed.owner == OWNER && !installed.revision.isNullOrBlank()
        val legacy = id == "whale_girl" && installed?.id == id && installed.builtIn &&
            installed.referenceSourceUrl == LEGACY_WHALE_URL && installed.owner.isNullOrBlank()
        if (installedExists && !owned && !legacy) return Action.KEEP
        val validBundle = bundled?.id == id && bundled.owner == OWNER && !bundled.revision.isNullOrBlank() &&
            bundled.language in setOf("zh", "ja", "en")
        if (!validBundle) return if (installedExists) Action.KEEP else Action.INVALID_BUNDLE
        return if (!installedExists) Action.INSTALL
            else if (owned && installed?.revision == bundled?.revision) Action.KEEP else Action.UPDATE
    }
}

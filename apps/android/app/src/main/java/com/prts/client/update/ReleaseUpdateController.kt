package com.prts.client.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.prts.client.BuildConfig
import com.prts.client.localdsh.LocalDshHub
import com.prts.client.ui.UpdateManagementPanel
import kotlin.concurrent.thread

internal class ReleaseUpdateController(private val activity: Activity) {
    private var panel: UpdateManagementPanel? = null
    private var catalog: ReleaseCatalog? = null
    private var checking = false
    private var status: String? = null

    fun attach(panel: UpdateManagementPanel) {
        this.panel = panel
        render()
    }

    fun detach(panel: UpdateManagementPanel) {
        if (this.panel === panel) this.panel = null
    }

    fun refresh() {
        if (checking) return
        checking = true
        status = null
        render()
        thread(name = "L2D-release-check") {
            val result = runCatching(GitHubReleaseCatalog::load)
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                checking = false
                result.onSuccess {
                    catalog = it
                    status = when {
                        !it.latest.version.newerThan(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) -> "应用已是最新版本"
                        it.latest.apk == null -> "最新版本暂未提供 APK"
                        else -> "有新版本可下载"
                    }
                }.onFailure { status = "无法读取 GitHub 版本，请稍后重试" }
                render()
            }
        }
    }

    fun update() {
        val latest = catalog?.latest ?: return
        if (checking || !latest.version.newerThan(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) return
        val url = latest.apk ?: return
        open(url, "APK 下载已打开，下载后由系统确认安装")
    }

    fun goUpdate() {
        if (checking) return
        val plugin = catalog?.pairedGoPlugin ?: return
        val installed = installedGo()
        if (installed != null && installed >= plugin.version) return
        open(plugin.url, "配套插件下载已打开，请在本机 Go 中安装")
    }

    private fun open(url: String, success: String) {
        status = try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            success
        } catch (_: ActivityNotFoundException) {
            "设备上没有可打开下载链接的浏览器"
        }
        render()
    }

    private fun installedGo(): ReleaseVersion? =
        LocalDshHub.connectedPluginVersion()?.let(ReleaseVersion::parse)

    private fun render() {
        val latest = catalog?.latest
        val go = catalog?.pairedGoPlugin
        val installed = installedGo()
        val goStatus = when {
            go == null && latest == null -> null
            go == null -> "本版本没有配套下载"
            installed == null -> "配套 ${go.version.display} · 未读取到本机版本"
            installed < go.version -> "${installed.display} → ${go.version.display}"
            else -> "${installed.display} · 已是最新"
        }
        panel?.render(UpdateManagementPanel.State(
            latestVersion = latest?.version?.display,
            notes = catalog?.history.orEmpty().map { UpdateManagementPanel.ReleaseNote(it.version.display, it.notes) },
            goStatus = goStatus,
            checking = checking,
            status = status,
            canUpdate = latest?.apk != null && latest.version.newerThan(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            canUpdateGo = go != null && (installed == null || installed < go.version),
        ))
    }
}

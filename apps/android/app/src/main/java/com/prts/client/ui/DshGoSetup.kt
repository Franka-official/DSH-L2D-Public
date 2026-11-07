package com.prts.client.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** The Go package is the Android applicationId used by the supported upstream host. */
internal object DshGoSetup {
    private const val PACKAGE = "io.github.ccch1mneyyy.dsh_app"
    private const val PLUGIN = "https://github.com/Franka-official/DSH-L2D-Public/releases/latest"
    private const val GO = "https://github.com/ccch1mneyyy/dsh-go/releases/latest"

    fun installed(activity: Activity): Boolean =
        activity.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    fun open(activity: Activity): Boolean {
        val launch = activity.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return runCatching { activity.startActivity(launch) }.isSuccess
    }

    fun showPluginHelp(activity: Activity) {
        val installed = installed(activity)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("DSH Go 本机插件")
            .setMessage(
                (if (installed) "" else "先在同一部手机安装并初始化 DSH Go。\n\n") +
                    "下载 DSH Go 插件 ZIP 并解压。\n" +
                    "在 Go 终端进入解压目录，运行 node install.mjs --install，再重载 Go 引擎。\n" +
                    "在 Go 的「设置」→「DSH L2D桥接」点连接。"
            )
            .setPositiveButton("插件下载") { _, _ -> openPage(activity, PLUGIN) }
            .setNegativeButton("返回", null)
        if (installed) dialog.setNeutralButton("打开 DSH Go") { _, _ ->
            if (!open(activity)) unavailable(activity)
        } else dialog.setNeutralButton("下载 DSH Go") { _, _ -> openPage(activity, GO) }
        dialog.show()
    }

    private fun openPage(activity: Activity, url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { unavailable(activity) }
    }

    private fun unavailable(activity: Activity) {
        Toast.makeText(activity, "无法打开，请在手机上手动操作", Toast.LENGTH_SHORT).show()
    }
}

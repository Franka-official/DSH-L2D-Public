package com.prts.client.ui

import android.content.Context
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.prts.client.localdsh.LocalDshBridge
import com.prts.client.localdsh.LocalDshHub

/** Go owns the model files. This view reads the authenticated bridge's in-process snapshot. */
internal class AsrPreparationSettingsPanel(context: Context) : LinearLayout(context) {
    private val status = TextView(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val prepare = Button(context)
    private val refresh = object : Runnable {
        override fun run() {
            render()
            postDelayed(this, 500L)
        }
    }

    init {
        orientation = VERTICAL
        addView(TextView(context).apply { text = "本机语音识别（STT）" })
        addView(status)
        addView(progress)
        addView(prepare)
        prepare.setOnClickListener { LocalDshHub.prepareAsr(); render() }
        render()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Window visibility may be dispatched during construction, before the view is attached.
        if (!isAttachedToWindow) return
        removeCallbacks(refresh)
        if (visibility == View.VISIBLE) post(refresh)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(refresh)
        if (windowVisibility == View.VISIBLE) post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    private fun render() {
        val connected = LocalDshBridge.connectorReady
        val snapshot = if (connected) LocalDshHub.asrPreparation() else null
        val downloading = snapshot?.phase == "downloading"
        val totalBytes = snapshot?.totalBytes ?: 0L
        val completedBytes = snapshot?.completedBytes ?: 0L
        val determinate = downloading && totalBytes > 0
        progress.visibility = if (downloading) View.VISIBLE else View.GONE
        progress.isIndeterminate = !determinate
        if (determinate) progress.progress = (completedBytes.toDouble() / totalBytes * 100).toInt()
        prepare.visibility = if (snapshot?.canPrepare == true) View.VISIBLE else View.GONE
        prepare.text = if (snapshot?.phase == "unprepared") "下载语音识别模型" else "重试下载与准备"
        status.text = when {
            !connected -> "连接 DSH Go 后可管理识别模型"
            snapshot == null -> if (LocalDshBridge.asrReady) "已就绪" else "请在 DSH Go 语音输入中准备模型，或更新桥接插件"
            downloading -> {
                val received = Formatter.formatShortFileSize(context, completedBytes)
                if (determinate) "下载中 · $received / ${Formatter.formatShortFileSize(context, totalBytes)}"
                else "下载中 · $received"
            }
            snapshot.phase in setOf("ready", "standby", "waking") -> "已就绪"
            snapshot.phase in setOf("checking", "loading", "starting") -> "正在准备模型"
            snapshot.phase == "cancelling" -> "正在取消下载"
            snapshot.phase == "failed" -> "下载或准备失败，可重试"
            snapshot.phase == "cancelled" -> "下载已取消，可重试"
            snapshot.phase == "unprepared" -> if (snapshot.canPrepare) "模型待下载" else "请在 DSH Go 的语音输入中准备模型"
            else -> "请先在 DSH Go 启用本机语音输入"
        }
    }
}

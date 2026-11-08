package com.prts.client.ui

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.prts.client.BuildConfig
import com.prts.client.R
import com.prts.client.databinding.SectionUpdateManagementBinding

/** Displays update state as plain text in the settings drawer's scroll view. */
internal class UpdateManagementPanel private constructor(private val binding: SectionUpdateManagementBinding) {
    data class ReleaseNote(val version: String, val body: String)

    data class State(
        val latestVersion: String? = null,
        val notes: List<ReleaseNote> = emptyList(),
        val goStatus: String? = null,
        val checking: Boolean = false,
        val status: String? = null,
        val canUpdate: Boolean = false,
        val canUpdateGo: Boolean = false,
    )

    val view: View get() = binding.root
    private var renderedNotes: List<ReleaseNote>? = null

    fun render(state: State) {
        val waiting = state.checking
        val latest = state.latestVersion?.takeIf { it.isNotBlank() } ?: "尚未检查"
        val go = state.goStatus?.takeIf { it.isNotBlank() } ?: "尚未检查"
        binding.latestAppVersion.text = "最新版本：$latest"
        binding.goPluginStatus.text = "Go 桥接插件：$go"
        binding.btnCheckUpdate.text = if (state.checking) "正在检查" else "检查更新"
        binding.btnCheckUpdate.isEnabled = !waiting
        binding.btnApplyUpdate.isVisible = state.canUpdate
        binding.btnApplyUpdate.isEnabled = state.canUpdate && !waiting
        binding.btnApplyGoUpdate.isVisible = state.canUpdateGo
        binding.btnApplyGoUpdate.isEnabled = !waiting
        binding.updateState.text = state.status.orEmpty()
        binding.updateState.isVisible = !state.status.isNullOrBlank()
        if (state.notes != renderedNotes) {
            renderedNotes = state.notes.toList()
            renderNotes(state.notes)
        }
    }

    private fun renderNotes(notes: List<ReleaseNote>) {
        val rows = binding.updateNotes
        rows.removeAllViews()
        if (notes.isEmpty()) {
            rows.addView(noteText("暂无版本说明", heading = false, marginDp = 8))
            return
        }
        notes.forEach { note ->
            rows.addView(noteText(note.version, heading = true, marginDp = 14))
            if (note.body.isNotBlank()) {
                rows.addView(noteText(note.body, heading = false, marginDp = 5))
            }
        }
    }

    private fun noteText(value: String, heading: Boolean, marginDp: Int) = TextView(view.context).apply {
        text = value
        textSize = if (heading) 15f else 14f
        if (heading) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, if (heading) R.color.ui_text else R.color.ui_text_secondary))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = (marginDp * resources.displayMetrics.density).toInt()
        }
    }

    companion object {
        fun create(context: Context, onRefresh: () -> Unit, onUpdate: () -> Unit, onGoUpdate: () -> Unit): UpdateManagementPanel {
            val binding = SectionUpdateManagementBinding.inflate(LayoutInflater.from(context))
            binding.currentAppVersion.text = "当前版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"
            binding.btnCheckUpdate.setOnClickListener { onRefresh() }
            binding.btnApplyUpdate.setOnClickListener { onUpdate() }
            binding.btnApplyGoUpdate.setOnClickListener { onGoUpdate() }
            return UpdateManagementPanel(binding).apply { render(State()) }
        }
    }
}

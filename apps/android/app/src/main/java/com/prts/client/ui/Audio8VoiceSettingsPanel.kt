package com.prts.client.ui

import android.content.Context
import android.net.Uri
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.prts.client.BuildConfig
import com.prts.client.R
import com.prts.client.audio.audio8.Audio8ModelStore
import com.prts.client.audio.audio8.Audio8VoiceLibrary

internal object Audio8VoiceSettingsPanel {
    class Panel(val view: View, val render: (Boolean) -> Unit)

    fun create(context: Context, characters: () -> List<Pair<String, String>>, activeCharacter: () -> String,
               onImportWav: (String) -> Unit, onVoiceChanged: () -> Unit): Panel {
        val library = Audio8VoiceLibrary
        val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val roleButton = Button(context)
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val voiceScroll = HorizontalScrollView(context).apply {
            isFillViewport = false
            addView(chips, android.widget.FrameLayout.LayoutParams(-2, -2))
        }
        val importWav = Button(context).apply {
            text = "导入 WAV"
            visibility = if (BuildConfig.AUDIO8_ENCODER_BUNDLED) View.VISIBLE else View.GONE
        }
        val manage = Button(context).apply { text = "管理音色" }
        section.addView(roleButton)
        section.addView(voiceScroll, LinearLayout.LayoutParams(-1, -2))
        section.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(importWav, LinearLayout.LayoutParams(0, -2, 1f))
            addView(manage, LinearLayout.LayoutParams(0, -2, 1f))
        })
        val fallback = listOf("whale" to "鲸鱼娘", "ellen" to "艾莲")
        var lastActive = library.roleId(activeCharacter())
        var character = lastActive
        fun dp(n: Int) = (n * context.resources.displayMetrics.density + .5f).toInt()
        var busy = false
        fun roleChoices() = characters().ifEmpty { fallback }
        fun voiceChoices() = listOf(library.DEFAULT to "角色默认", library.WHALE to "鲸鱼娘 · 内置",
            library.ELLEN to "艾莲 · 内置") + runCatching { library.voices(context) }.getOrDefault(emptyList())
            .map { it.id to it.name }
        fun render() {
            val active = library.roleId(activeCharacter())
            if (active != lastActive) {
                lastActive = active
                character = active
            }
            val roles = roleChoices()
            if (roles.none { it.first == character }) character = roles.first().first
            val roleName = roles.first { it.first == character }.second
            roleButton.text = "角色 · $roleName"
            val selected = runCatching { library.selected(context, character) }.getOrDefault(library.DEFAULT)
            val scrollPosition = voiceScroll.scrollX
            chips.removeAllViews()
            voiceChoices().forEach { (id, title) ->
                val chip = RadioButton(context).apply {
                    text = title
                    buttonDrawable = null
                    gravity = Gravity.CENTER
                    isChecked = selected == id
                    isEnabled = !busy
                    maxWidth = dp(230)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(14), 0, dp(14), 0)
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.ui_text))
                    setBackgroundResource(if (isChecked) R.drawable.ui_chip_selected else R.drawable.ui_chip)
                    setOnClickListener {
                        runCatching { library.select(context, character, id) }
                            .onSuccess { render(); onVoiceChanged() }
                            .onFailure { render(); showError(context, it) }
                    }
                }
                chips.addView(chip, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(6) })
            }
            voiceScroll.scrollTo(scrollPosition, 0)
            roleButton.isEnabled = !busy
            importWav.isEnabled = !busy
            manage.isEnabled = !busy
        }
        roleButton.setOnClickListener {
            val roles = roleChoices()
            val checked = roles.indexOfFirst { it.first == character }
            AlertDialog.Builder(context).setTitle("角色")
                .setSingleChoiceItems(roles.map { it.second }.toTypedArray(), checked) { dialog, which ->
                    character = roles[which].first
                    dialog.dismiss()
                    render()
                }.show()
        }
        section.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (library.roleId(activeCharacter()) != lastActive) render()
        }
        importWav.setOnClickListener { onImportWav(character) }
        manage.setOnClickListener {
            runCatching {
                val voices = library.voices(context)
                if (voices.isEmpty()) {
                    AlertDialog.Builder(context).setTitle("管理音色").setMessage("暂无自定义音色")
                        .setPositiveButton("关闭", null).show()
                } else {
                    AlertDialog.Builder(context).setTitle("管理音色")
                        .setItems(voices.map { it.name }.toTypedArray()) { _, which ->
                            editVoice(context, voices[which]) { render(); onVoiceChanged() }
                        }.setNegativeButton("关闭", null).show()
                }
            }.onFailure { showError(context, it) }
        }
        render()
        return Panel(section) { inProgress -> busy = inProgress; render() }
    }

    private fun editVoice(context: Context, voice: Audio8VoiceLibrary.Voice, onChanged: () -> Unit) {
        AlertDialog.Builder(context).setTitle(voice.name).setItems(arrayOf("重命名", "删除")) { _, which ->
            if (which == 0) {
                val name = nameField(context).apply { setText(voice.name); setSelection(length()) }
                val dialog = AlertDialog.Builder(context).setTitle("重命名音色").setView(fields(context, name))
                    .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        runCatching { Audio8VoiceLibrary.rename(context, voice.id, name.text.toString()) }
                            .onSuccess { dialog.dismiss(); onChanged() }
                            .onFailure { name.error = it.message ?: "音色重命名失败" }
                    }
                }
                dialog.show()
            } else {
                AlertDialog.Builder(context).setTitle("删除音色").setMessage(voice.name)
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        runCatching { Audio8VoiceLibrary.remove(context, voice.id) }
                            .onSuccess { onChanged() }.onFailure { showError(context, it) }
                    }.show()
            }
        }.setNegativeButton("取消", null).show()
    }

    fun confirmWavImport(context: Context, uri: Uri, role: String) {
        val name = nameField(context)
        val transcript = EditText(context).apply {
            hint = "WAV 原文"
            filters = arrayOf(InputFilter.LengthFilter(480))
            maxLines = 4
            isSaveEnabled = false
        }
        val dialog = AlertDialog.Builder(context).setTitle("导入 WAV 到音色库")
            .setView(fields(context, name, transcript))
            .setNegativeButton("取消", null)
            .setPositiveButton("导入", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = runCatching { Audio8VoiceLibrary.checkNewName(context, name.text.toString()) }
                    .onFailure { name.error = it.message ?: "音色名称无效" }.getOrNull()
                    ?: return@setOnClickListener
                runCatching { Audio8ModelStore.importReferenceWav(context, uri, title, transcript.text.toString()) }
                    .onSuccess { dialog.dismiss() }
                    .onFailure { transcript.error = it.message ?: "WAV 导入失败" }
            }
        }
        dialog.show()
    }

    private fun nameField(context: Context) = EditText(context).apply {
        hint = "音色名称"
        filters = arrayOf(InputFilter.LengthFilter(80))
        setSingleLine(true)
        isSaveEnabled = false
    }

    private fun fields(context: Context, vararg items: EditText) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val spacing = (24 * resources.displayMetrics.density).toInt()
        setPadding(spacing, 0, spacing, 0)
        items.forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun showError(context: Context, error: Throwable) {
        Toast.makeText(context, error.message ?: "音色库操作失败", Toast.LENGTH_SHORT).show()
    }
}

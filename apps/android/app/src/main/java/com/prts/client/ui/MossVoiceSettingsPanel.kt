package com.prts.client.ui

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.prts.client.audio.audio8.Audio8VoiceLibrary
import com.prts.client.audio.moss.MossVoiceLibrary
import com.prts.client.audio.moss.MossWavVoiceEncoder

internal object MossVoiceSettingsPanel {
    class Panel(val view: View, val render: (Boolean) -> Unit)

    fun create(context: Context, characters: () -> List<Pair<String, String>>, activeCharacter: () -> String,
               onImport: (String) -> Unit, onVoiceChanged: () -> Unit): Panel {
        val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val roleButton = Button(context)
        val voiceButton = Button(context)
        val importWavButton = Button(context).apply { text = "导入人声 WAV" }
        val importJsonButton = Button(context).apply { text = "导入 MOSS 音色 JSON" }
        val manage = Button(context).apply { text = "管理导入音色" }
        listOf(roleButton, voiceButton, importWavButton, importJsonButton, manage).forEach { section.addView(it) }
        val fallback = listOf("whale" to "鲸鱼娘", "ellen" to "艾莲")
        var lastActive = Audio8VoiceLibrary.roleId(activeCharacter())
        var role = lastActive
        var busy = false
        fun roles() = characters().ifEmpty { fallback }
        fun choices() = listOf(MossVoiceLibrary.DEFAULT to "角色默认") +
            MossVoiceLibrary.choices(context).map { it.key to it.name }
        fun render() {
            val active = Audio8VoiceLibrary.roleId(activeCharacter())
            if (active != lastActive) { lastActive = active; role = active }
            val currentRoles = roles()
            if (currentRoles.none { it.first == role }) role = currentRoles.first().first
            val name = currentRoles.first { it.first == role }.second
            val selected = MossVoiceLibrary.selected(context, role)
            val items = choices()
            val voice = items.firstOrNull { it.first == selected }?.second ?: "角色默认"
            roleButton.text = "角色 · $name"
            voiceButton.text = "切换音色 · $voice"
            roleButton.isEnabled = !busy
            voiceButton.isEnabled = !busy
            importWavButton.isEnabled = !busy && MossWavVoiceEncoder.ready(context)
            importJsonButton.isEnabled = !busy
            manage.isEnabled = !busy && items.any { it.first.startsWith("import:") }
        }
        roleButton.setOnClickListener {
            val items = roles()
            AlertDialog.Builder(context).setTitle("角色").setSingleChoiceItems(
                items.map { it.second }.toTypedArray(), items.indexOfFirst { it.first == role }
            ) { dialog, which -> role = items[which].first; dialog.dismiss(); render() }.show()
        }
        voiceButton.setOnClickListener {
            val items = choices()
            val selected = MossVoiceLibrary.selected(context, role)
            AlertDialog.Builder(context).setTitle("MOSS 音色").setSingleChoiceItems(
                items.map { it.second }.toTypedArray(), items.indexOfFirst { it.first == selected }
            ) { dialog, which ->
                runCatching { MossVoiceLibrary.select(context, role, items[which].first) }
                    .onSuccess { dialog.dismiss(); render(); onVoiceChanged() }
                    .onFailure { notice(context, "音色未保存，请重试") }
            }.show()
        }
        importWavButton.setOnClickListener { onImport("moss-wav:$role") }
        importJsonButton.setOnClickListener { onImport("moss-json:$role") }
        manage.setOnClickListener {
            val custom = MossVoiceLibrary.choices(context).filter { it.key.startsWith("import:") }
            if (custom.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(context).setTitle("管理导入音色")
                .setItems(custom.map { it.name }.toTypedArray()) { _, which ->
                    val voice = custom[which]
                    AlertDialog.Builder(context).setTitle("删除音色").setMessage(voice.name)
                        .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                            runCatching { MossVoiceLibrary.removeImported(context, voice.key) }
                                .onSuccess { render(); onVoiceChanged() }
                                .onFailure { notice(context, "音色没有删除成功") }
                        }.show()
                }.setNegativeButton("取消", null).show()
        }
        var observation: AutoCloseable? = null
        section.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                observation = MossVoiceLibrary.observe(context, ::render)
                render()
            }
            override fun onViewDetachedFromWindow(view: View) {
                observation?.close()
                observation = null
            }
        })
        section.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (Audio8VoiceLibrary.roleId(activeCharacter()) != lastActive) render()
        }
        render()
        return Panel(section) { inProgress -> busy = inProgress; render() }
    }

    fun importPrompt(context: Context, uri: Uri, role: String) {
        runCatching { MossVoiceLibrary.importPrompt(context, uri, role) }
            .onSuccess { notice(context, "已导入并选用 $it") }
            .onFailure { notice(context, "导入失败，请选择有效的 MOSS 声码 JSON") }
    }

    fun importWav(context: Context, uri: Uri, role: String) {
        val app = context.applicationContext
        val name = EditText(context).apply {
            hint = "音色名称"
            filters = arrayOf(InputFilter.LengthFilter(80))
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(context).setTitle("导入人声 WAV").setView(name)
            .setNegativeButton("取消", null).setPositiveButton("导入", null).create()
        dialog.setOnShowListener {
            val start = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            start.setOnClickListener {
                val title = name.text.toString().trim()
                if (title.isEmpty() || title.any(Char::isISOControl)) {
                    name.error = "请输入音色名称"
                    return@setOnClickListener
                }
                start.isEnabled = false
                name.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.setCancelable(false)
                start.text = "正在编码"
                Thread({
                    val result = runCatching {
                        val frames = MossWavVoiceEncoder.encode(app, uri)
                        MossVoiceLibrary.saveWavPrompt(app, role, title, frames)
                    }
                    Handler(Looper.getMainLooper()).post {
                        result.onSuccess {
                            dialog.dismiss()
                            notice(app, "已导入并选用 $it")
                        }.onFailure {
                            name.error = it.message ?: "人声导入失败"
                            name.isEnabled = true
                            start.isEnabled = true
                            start.text = "导入"
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            dialog.setCancelable(true)
                        }
                    }
                }, "MOSS-WAV-Import").start()
            }
        }
        dialog.show()
    }

    private fun notice(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

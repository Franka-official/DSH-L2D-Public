package com.prts.client.ui

import android.content.Context
import android.text.InputType
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.prts.client.R
import com.prts.client.BuildConfig
import com.prts.client.audio.SpeechApiOptions
import com.prts.client.audio.TtsBackendSettings
import com.prts.client.audio.audio8.Audio8ModelStore
import com.prts.client.audio.moss.MossModelStore
import kotlin.math.roundToInt

/** Independent TTS settings; no model-provider or chat credential is read here. */
internal object TtsBackendSettingsPanel {
    fun create(context: Context, referenceInput: EditText, onSaved: () -> Unit,
               onDownloadModels: () -> Unit, onImportModels: () -> Unit,
               onAbandonLocalInstall: () -> Unit,
               onImportWav: (String) -> Unit,
               voiceCharacters: () -> List<Pair<String, String>> = { listOf("whale" to "鲸鱼娘", "ellen" to "艾莲") },
               currentVoiceCharacter: () -> String = { "whale" },
               mossModelStatus: () -> String? = { "MOSS 模型未安装" },
               onDownloadMossModels: (() -> Unit)? = null,
               onImportMossModels: (() -> Unit)? = null,
               onDownloadAudio8Models: (() -> Unit)? = null): View {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        fun text(value: String, secondary: Boolean = false) = TextView(context).apply {
            text = value; textSize = if (secondary) 12f else 15f
            setTextColor(ContextCompat.getColor(context, if (secondary) R.color.ui_text_secondary else R.color.ui_text))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        fun input(hintText: String, initial: String = "", password: Boolean = false) = EditText(context).apply {
            hint = hintText; setText(initial); textSize = 14f; setSingleLine(true)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(referenceInput.currentTextColor); setHintTextColor(referenceInput.currentHintTextColor)
            background = referenceInput.background?.constantState?.newDrawable()?.mutate()
            setPadding(dp(12), dp(10), dp(12), dp(10)); minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            if (password) { isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO }
        }
        val loaded = runCatching { TtsBackendSettings.load(context) }
        val saved = loaded.getOrElse { TtsBackendSettings.Options() }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) }
        }
        panel.addView(text("语音生成"))
        val group = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val buttons = linkedMapOf<String, RadioButton>()
        for ((mode, label) in listOf(TtsBackendSettings.LOCAL to "手机语音", TtsBackendSettings.API to "TTS API")) {
            val button = RadioButton(context).apply {
                id = View.generateViewId(); text = label; tag = mode; buttonDrawable = null; gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.ui_text)); textSize = 13f
                layoutParams = RadioGroup.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) }
            }
            buttons[mode] = button; group.addView(button)
        }
        panel.addView(group)
        val localPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun radioButton(label: String) = RadioButton(context).apply {
            id = View.generateViewId(); text = label; buttonDrawable = null; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.ui_text)); textSize = 13f
            layoutParams = RadioGroup.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) }
        }

        val audio8 = TtsBackendSettings.LOCAL_ENGINE_AUDIO8
        val moss = TtsBackendSettings.LOCAL_ENGINE_MOSS
        var engine = saved.localEngine.takeIf { it == audio8 || it == moss } ?: audio8
        val engineGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val engineButtons = mapOf(audio8 to radioButton("Audio8"), moss to radioButton("MOSS"))
        engineButtons.values.forEach { engineGroup.addView(it) }
        localPanel.addView(text("本地语音模型")); localPanel.addView(engineGroup)

        val cpu = TtsBackendSettings.LOCAL_CODEC_CPU
        val npu = TtsBackendSettings.LOCAL_CODEC_NPU
        val codecDraft = mutableMapOf(engine to saved.localCodecBackend)
        val computeGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val computeButtons = mapOf(cpu to radioButton("CPU 推理"), npu to radioButton("NPU 解码"))
        computeButtons.values.forEach { computeGroup.addView(it) }
        localPanel.addView(text("手机语音计算方式")); localPanel.addView(computeGroup)

        fun paintRadios(controls: Collection<RadioButton>) {
            controls.forEach { it.setBackgroundResource(if (it.isChecked) R.drawable.ui_chip_selected else R.drawable.ui_chip) }
        }
        fun renderCompute() {
            val allowed = TtsBackendSettings.isNpuSupported(engine)
            val npuButton = computeButtons.getValue(npu)
            npuButton.isEnabled = allowed
            npuButton.alpha = if (allowed) 1f else 0.5f
            val backend = if (allowed && codecDraft[engine] != cpu) npu else cpu
            computeGroup.check(computeButtons.getValue(backend).id)
            paintRadios(computeButtons.values)
        }
        computeGroup.setOnCheckedChangeListener { _, checked ->
            computeButtons.entries.firstOrNull { it.value.id == checked && it.value.isEnabled }?.let {
                codecDraft[engine] = it.key
            }
            paintRadios(computeButtons.values)
        }

        val audio8Panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val mossPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val mossVoices = MossVoiceSettingsPanel.create(context, voiceCharacters, currentVoiceCharacter,
            onImportWav, onSaved)
        mossPanel.addView(mossVoices.view)
        val mossStatus = text("")
        fun mossProblem() = runCatching(mossModelStatus).getOrElse { "无法读取 MOSS 模型状态" }
        val mossDetails = text("", true)
        val mossProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) }
        }
        val mossDownload = onDownloadMossModels?.let { download -> Button(context).apply {
            text = if (BuildConfig.MOSS_MODEL_BUNDLED) "准备 MOSS 模型" else "下载 MOSS 模型"; setOnClickListener { download() }
        } }
        val mossCancel = Button(context).apply {
            text = "取消"; setOnClickListener { isEnabled = false; MossModelStore.cancel() }
        }
        fun renderMoss(state: MossModelStore.State = MossModelStore.status(context)) {
            val busy = state.phase in setOf("downloading", "installing")
            mossVoices.render(busy)
            val problem = if (busy) null else mossProblem()
            mossStatus.text = when {
                state.phase == "installing" -> "正在准备 MOSS 模型"
                busy -> "正在下载 MOSS 模型"
                problem == null -> "MOSS 已就绪"
                state.phase == "failed" -> state.error ?: problem
                state.phase == "cancelled" -> "已取消"
                else -> problem
            }
            val total = state.totalBytes.coerceAtLeast(1)
            val done = state.processedBytes.coerceIn(0, total)
            mossProgress.progress = (done * 1000 / total).toInt()
            val bytes = "${Formatter.formatFileSize(context, done)} / ${Formatter.formatFileSize(context, total)}"
            mossDetails.text = "$bytes · ${done * 100 / total}%"
            mossDetails.visibility = if (busy) View.VISIBLE else View.GONE
            mossProgress.visibility = mossDetails.visibility
            mossCancel.visibility = mossDetails.visibility
            if (!busy) mossCancel.isEnabled = true
            mossDownload?.visibility = if (!busy && problem != null) View.VISIBLE else View.GONE
            mossDownload?.text = when {
                BuildConfig.MOSS_MODEL_BUNDLED -> "准备 MOSS 模型"
                done > 0 && done < total -> "继续下载 MOSS 模型"
                else -> "下载 MOSS 模型"
            }
        }
        mossPanel.addView(mossStatus); mossPanel.addView(mossDetails); mossPanel.addView(mossProgress)
        mossDownload?.let { mossPanel.addView(it) }; mossPanel.addView(mossCancel)
        onImportMossModels?.let { importModel -> mossPanel.addView(Button(context).apply {
            text = "导入 MOSS 模型"; setOnClickListener { importModel() }
        }) }
        localPanel.addView(audio8Panel); localPanel.addView(mossPanel)

        fun renderEngine() {
            val useMoss = engine == moss
            audio8Panel.visibility = if (useMoss) View.GONE else View.VISIBLE
            mossPanel.visibility = if (useMoss) View.VISIBLE else View.GONE
            if (useMoss) renderMoss()
            renderCompute()
            paintRadios(engineButtons.values)
        }
        engineGroup.check(engineButtons.getValue(engine).id)
        renderEngine()
        val roleVoices = Audio8VoiceSettingsPanel.create(context, voiceCharacters, currentVoiceCharacter, onImportWav, onSaved)
        audio8Panel.addView(roleVoices.view)
        val modelStatus = text("")
        val modelDetails = text("", true)
        val modelProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) }
        }
        val download = Button(context).apply {
            text = "准备本地模型"; setOnClickListener { onDownloadModels() }
        }
        val importModels = Button(context).apply {
            text = "导入 Audio8 模型或参考音"; setOnClickListener { onImportModels() }
        }
        val cancelModels = Button(context).apply {
            text = "取消"
            setOnClickListener { onAbandonLocalInstall(); Audio8ModelStore.cancel(context) }
        }
        val downloadAudio8 = onDownloadAudio8Models?.let { fetch -> Button(context).apply {
            text = "GitHub 下载模型"; setOnClickListener { fetch() }
        } }
        audio8Panel.addView(modelStatus); audio8Panel.addView(modelDetails); audio8Panel.addView(modelProgress)
        audio8Panel.addView(download)
        downloadAudio8?.let { audio8Panel.addView(it) }
        audio8Panel.addView(importModels); audio8Panel.addView(cancelModels)
        var currentModels = Audio8ModelStore.status(context)
        fun renderModels(state: Audio8ModelStore.State) {
            currentModels = state
            val busy = state.phase in setOf("checking", "downloading", "importing", "installing", "preparing", "cancelling")
            modelStatus.text = when {
                state.ready -> "Audio8 已就绪"
                state.phase == "needs_reference" -> "缺少参考音"
                state.phase == "checking" -> "正在检查手机语音"
                state.phase == "downloading" -> "正在下载手机语音"
                state.phase == "importing" -> "正在读取模型包"
                state.phase == "installing" -> "正在安装手机语音"
                state.phase == "preparing" -> "正在准备手机语音"
                state.phase == "cancelling" -> "正在取消"
                state.phase == "cancelled" -> "已取消"
                state.phase == "failed" -> state.error ?: "手机语音未安装完成，请检查网络、存储或ZIP后重试"
                else -> "尚未安装手机语音模型"
            }
            modelDetails.visibility = if (busy) View.VISIBLE else View.GONE
            val done = Formatter.formatFileSize(context, state.processedBytes.coerceAtLeast(0))
            val total = if (state.totalBytes > 0) Formatter.formatFileSize(context, state.totalBytes) else "大小待确认"
            val rate = if (state.bytesPerSecond.isFinite() && state.bytesPerSecond > 0)
                " · " + Formatter.formatFileSize(context, state.bytesPerSecond.toLong()) + "/s" else ""
            val eta = state.etaSeconds?.takeIf { it >= 0 }?.let {
                val minutes = (it + 59) / 60
                if (it < 60) " · 预计剩余" + it + "秒" else " · 预计剩余" + minutes + "分钟"
            }.orEmpty()
            modelDetails.text = "$done / $total$rate$eta"
            modelProgress.visibility = if (busy) View.VISIBLE else View.GONE
            modelProgress.isIndeterminate = state.totalBytes <= 0
            modelProgress.progress = if (state.totalBytes > 0)
                ((state.processedBytes.toDouble() / state.totalBytes) * 1000).toInt().coerceIn(0, 1000) else 0
            download.visibility = if (BuildConfig.AUDIO8_MODEL_BUNDLED && !state.ready && state.phase != "needs_reference") View.VISIBLE else View.GONE
            importModels.visibility = View.VISIBLE
            importModels.text = if (state.ready) "导入参考音 ZIP" else "导入 Audio8 模型或参考音"
            roleVoices.render(busy)
            download.isEnabled = !busy; importModels.isEnabled = !busy
            downloadAudio8?.visibility = if (state.ready || busy) View.GONE else View.VISIBLE
            download.text = if (BuildConfig.AUDIO8_MODEL_BUNDLED) "重新准备手机语音"
                else if (state.phase in setOf("failed", "cancelled") && state.processedBytes > 0)
                    "继续下载并使用手机语音" else "下载并使用手机语音"
            cancelModels.visibility = if (busy && state.phase != "preparing") View.VISIBLE else View.GONE
            cancelModels.isEnabled = state.phase != "cancelling"
        }
        renderModels(currentModels)
        var modelObserver: AutoCloseable? = null
        var mossObserver: AutoCloseable? = null
        val owner = context as? LifecycleOwner
        val modelLifecycle = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { if (engine == moss) renderMoss() }
        }
        panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                modelObserver?.close()
                modelObserver = Audio8ModelStore.observe(context) { if (view.isAttachedToWindow) renderModels(it) }
                mossObserver?.close()
                mossObserver = MossModelStore.observe(context) { if (view.isAttachedToWindow) renderMoss(it) }
                owner?.lifecycle?.addObserver(modelLifecycle)
            }
            override fun onViewDetachedFromWindow(view: View) {
                owner?.lifecycle?.removeObserver(modelLifecycle)
                modelObserver?.close(); modelObserver = null
                mossObserver?.close(); mossObserver = null
            }
        })
        panel.addView(localPanel)
        val apiPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val url = input("语音 API 地址", saved.api.endpoint)
        val key = input("语音 API Key", password = true)
        var hasKey = saved.hasApiKey
        var clearKey = false
        fun updateKeyHint() {
            key.hint = when {
                clearKey -> "语音 API Key · 待清除"
                hasKey -> "语音 API Key · 已保存"
                else -> "语音 API Key"
            }
        }
        updateKeyHint()
        key.doAfterTextChanged {
            if (!it.isNullOrBlank()) clearKey = false
            updateKeyHint()
        }
        apiPanel.addView(url); apiPanel.addView(key)
        apiPanel.addView(Button(context).apply {
            text = "清除语音 API Key"
            setOnClickListener { clearKey = true; key.setText(""); updateKeyHint() }
        })
        val advanced = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val model = input("语音模型", saved.api.model)
        val voice = input("服务端音色ID", saved.api.voice)
        advanced.addView(model); advanced.addView(voice)
        apiPanel.addView(Button(context).apply {
            text = "模型与音色"
            setOnClickListener { advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        })
        apiPanel.addView(advanced)
        panel.addView(apiPanel)
        val error = text(if (loaded.isFailure) "语音设置读取失败，可在这里重新保存。" else "", true)
        error.visibility = if (loaded.isFailure) View.VISIBLE else View.GONE
        panel.addView(error)
        engineGroup.setOnCheckedChangeListener { _, checked ->
            val next = engineButtons.entries.firstOrNull { it.value.id == checked }?.key
            if (next != null && next != engine) {
                engine = next
                if (engine == moss) onAbandonLocalInstall()
                error.visibility = View.GONE
                renderEngine()
            }
        }
        fun render() {
            val api = group.checkedRadioButtonId == buttons.getValue(TtsBackendSettings.API).id
            apiPanel.visibility = if (api) View.VISIBLE else View.GONE
            localPanel.visibility = if (api) View.GONE else View.VISIBLE
            buttons.values.forEach { it.setBackgroundResource(if (it.isChecked) R.drawable.ui_chip_selected else R.drawable.ui_chip) }
        }
        group.check(buttons.getValue(saved.mode).id); render()
        group.setOnCheckedChangeListener { _, checked ->
            if (checked == buttons.getValue(TtsBackendSettings.API).id) onAbandonLocalInstall()
            render()
        }
        panel.addView(Button(context).apply {
            text = "保存语音设置"
            setOnClickListener {
                val mode = if (group.checkedRadioButtonId == buttons.getValue(TtsBackendSettings.API).id) TtsBackendSettings.API else TtsBackendSettings.LOCAL
                if (mode == TtsBackendSettings.LOCAL) {
                    val problem = if (engine == moss) mossProblem()
                        else if (currentModels.ready) null else "请先准备 Audio8 模型和参考音"
                    if (problem != null) {
                        if (engine == moss) mossStatus.text = problem
                        error.text = problem; error.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                }
                val localCodecBackend = if (mode == TtsBackendSettings.LOCAL)
                    computeButtons.entries.firstOrNull { it.value.isChecked && it.value.isEnabled }?.key else null
                runCatching { TtsBackendSettings.save(context, mode, SpeechApiOptions(url.text.toString(),
                    model.text.toString(), voice.text.toString()), key.text.toString().takeIf { it.isNotBlank() },
                    clearKey, localCodecBackend = localCodecBackend,
                    localEngine = engine.takeIf { mode == TtsBackendSettings.LOCAL }) }
                    .onSuccess {
                        key.setText(""); clearKey = false; error.visibility = View.GONE
                        val current = TtsBackendSettings.load(context)
                        url.setText(current.api.endpoint)
                        hasKey = current.hasApiKey
                        updateKeyHint()
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show(); onSaved()
                    }.onFailure {
                        error.text = if (it is IllegalArgumentException) it.message ?: "请检查语音设置" else "语音设置未保存，请检查输入后重试"
                        error.visibility = View.VISIBLE
                    }
            }
        })
        return panel
    }
}

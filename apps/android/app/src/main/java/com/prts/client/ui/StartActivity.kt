package com.prts.client.ui

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import com.prts.client.localdsh.LocalDshBridge
import com.prts.client.localdsh.LocalDshBridgeService
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.prts.client.R
import com.prts.client.BuildConfig
import com.prts.client.audio.LocalVoiceSettings
import com.prts.client.audio.SpeechApiOptions
import com.prts.client.audio.SpeechTempo
import com.prts.client.audio.TtsBackendSettings
import com.prts.client.audio.audio8.Audio8ModelStore
import com.prts.client.audio.audio8.OfflineAudio8TtsEngine
import com.prts.client.audio.moss.MossModelStore
import com.prts.client.audio.moss.MossNanoEngine
import com.prts.client.databinding.ActivityStartBinding
import com.prts.client.databinding.SheetSettingsBinding
import com.prts.client.live2d.Live2DView
import com.prts.client.live2d.CanvasQuality
import com.prts.client.net.ConnectionMode
import com.prts.client.net.ConnectionSettings
import com.prts.client.net.ServicePairingClient
import com.prts.client.ui.assets.BackgroundEditor
import com.prts.client.ui.assets.BackgroundLibrary
import com.prts.client.ui.assets.BackgroundPresenter
import com.prts.client.ui.assets.CharacterItem
import com.prts.client.ui.assets.CharacterLibrary
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.random.Random

class StartActivity : AppCompatActivity() {
    private lateinit var b: ActivityStartBinding
    private lateinit var bgLib: BackgroundLibrary
    private lateinit var background: BackgroundPresenter
    private var backgroundEditor: Dialog? = null
    private lateinit var charLib: CharacterLibrary
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { job -> Thread({
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); job.run()
    }, "start-import") }
    private var foreground = false
    private var bundledVoiceReady = false
    private var localVoiceSelected = true
    private var localVoiceEngine = TtsBackendSettings.LOCAL_ENGINE_AUDIO8
    private var mossPreparation: AutoCloseable? = null
    private var modelPreparation: AutoCloseable? = null
    private var useLocalAfterModelInstall = false
    private var importingBackground = false
    private var importingCharacter = false
    private var characters = emptyList<CharacterItem>()
    private var preview: Live2DView? = null
    private var directPlacement: DirectCharacterPlacementController? = null
    private var previewCharacter = ""
    private var previewReadyShown = false
    private var nextIdleAt = 0L
    private var lastIdle: String? = null
    private var editingPlacement = false
    private var placement = PresentationTransform()
    private var placementEditor: CharacterPlacementEditor.Handle? = null
    private var sheet: BottomSheetDialog? = null
    private val firstRunGuide = FirstRunGuide(this) {
        showSettings(initialPage = SettingsDrawerController.Page.CONNECTION, preferredMode = ConnectionMode.LOCAL_DSH)
    }
    private var bridgeSettingsTick: Runnable? = null
    private var settings: SheetSettingsBinding? = null
    private var activeDraft: SettingsDraft? = null
    private var pairingHandle: ServicePairingClient.Handle? = null
    private var pairingGeneration = 0L
    private var pairingState: ServicePairingClient.State? = null
    @Volatile private var backgroundStripGeneration = 0L

    private data class SettingsDraft(
        var mode: ConnectionMode,
        val urls: MutableMap<ConnectionMode, String>,
        var apiKeyInput: String = "", var clearApiKey: Boolean = false,
        var callRelayUrl: String = "", var callRelayKey: String = "",
        var language: String = "zh",
    )

    private val pickSpeechPackage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            useLocalAfterModelInstall = true
            runCatching { Audio8ModelStore.importPackage(this, uri) }
                .onFailure { useLocalAfterModelInstall = false; toast("模型包未能开始导入，请重新选择ZIP") }
        }
    }

    private val pickCharacter = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || importingCharacter) return@registerForActivityResult
        importingCharacter = true; settings?.btnAddChar?.isEnabled = false
        toast("正在导入角色")
        worker.execute {
            var archive: File? = null
            val result = runCatching {
                val file = File.createTempFile("character-", ".zip", cacheDir); archive = file
                contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ?: error("无法读取角色文件")
                charLib.importFromZip(file)?.also { charLib.setCurrent(it.name) } ?: error("模型文件不完整")
                charLib.all()
            }
            archive?.delete()
            runOnUiThread {
                importingCharacter = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                settings?.btnAddChar?.isEnabled = true
                result.onSuccess { characters = it; applyCharacterPreview(); settings?.let(::renderCharacters); toast("角色已导入") }
                    .onFailure { toast(it.message ?: "导入失败，请选择包含一个完整Live2D模型的ZIP") }
            }
        }
    }
    private var pendingVoiceRole: String? = null
    private val pickVoice = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val role = pendingVoiceRole.also { pendingVoiceRole = null }
        if (uri != null && role != null) {
            when {
                role.startsWith("moss-wav:") -> MossVoiceSettingsPanel.importWav(this, uri, role.removePrefix("moss-wav:"))
                role.startsWith("moss-json:") -> MossVoiceSettingsPanel.importPrompt(this, uri, role.removePrefix("moss-json:"))
                else -> Audio8VoiceSettingsPanel.confirmWavImport(this, uri, role)
            }
        }
    }
    private val pickBackground = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || importingBackground) return@registerForActivityResult
        importingBackground = true; settings?.btnAddBg?.isEnabled = false
        toast("正在准备背景")
        worker.execute {
            val result = runCatching {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "自定义背景"
                bgLib.importFromUri(name, uri)
            }
            runOnUiThread {
                importingBackground = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                settings?.btnAddBg?.isEnabled = true
                result.onSuccess { item ->
                    Prefs.setSelectedBg(this, item.id); background.showSelected(); settings?.let(::renderBackgrounds)
                    toast("背景已导入")
                }.onFailure { error ->
                    toast(error.message ?: "背景导入失败")
                }
            }
        }
    }

    private val previewTick = object : Runnable {
        override fun run() {
            if (!foreground || !::b.isInitialized) return
            val view = preview
            if (view?.isModelReady == true) {
                if (!bundledVoiceReady) refreshSpeechPreparation()
                else if (localVoiceSelected && localVoiceEngine == TtsBackendSettings.LOCAL_ENGINE_AUDIO8) showLocalVoicePreparation()
                else if (!previewReadyShown) { previewReadyShown = true; b.statusText.text = "准备好了就开始吧" }
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val pairingTick = object : Runnable {
        override fun run() {
            if (!foreground || sheet?.isShowing != true) return
            settings?.let { s -> activeDraft?.let { renderPairing(s, it, pairingState) } }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useLocalAfterModelInstall = savedInstanceState?.getBoolean("pending_phone_voice_install") ?: false
        pendingVoiceRole = savedInstanceState?.getString("pending_voice_role")
        b = ActivityStartBinding.inflate(layoutInflater); setContentView(b.root)
        bgLib = BackgroundLibrary(this); background = BackgroundPresenter(this, b.bgImage)
        charLib = CharacterLibrary(this); background.showSelected()
        b.btnStart.isEnabled = false; b.btnSettings.isEnabled = false
        worker.execute {
            val ready = runCatching { charLib.all() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                characters = ready; b.btnSettings.isEnabled = true
                b.btnStart.isEnabled = ready.isNotEmpty() && bundledVoiceReady
                applyCharacterPreview()
            }
        }
        b.btnStart.setOnClickListener {
            if (characters.isEmpty() || !bundledVoiceReady) return@setOnClickListener
            startActivity(Intent(this, CallActivity::class.java))
        }
        b.btnSettings.setOnClickListener { if (editingPlacement) finishPlacement() else showSettings() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (editingPlacement) finishPlacement() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    private fun currentCharacter(): CharacterItem? {
        val stored = getSharedPreferences("prts", MODE_PRIVATE).getString("character", CharacterLibrary.DEFAULT)
        return characters.firstOrNull { it.name == stored } ?: characters.firstOrNull()
    }
    private fun applyCharacterPreview() {
        val character = currentCharacter()
        if (character == null) { b.statusText.text = "还没有角色，可在设置中导入"; return }
        b.titleName.text = character.displayName
        placement = CharacterPresentationStore.load(this, character.name)
        if (previewCharacter != character.name || preview == null) {
            directPlacement?.close(); directPlacement = null
            preview?.stopRender(); b.charPreviewHost.removeAllViews()
            previewCharacter = character.name; previewReadyShown = false
            b.statusText.text = "正在准备${character.displayName}"
            preview = Live2DView(this, character.name).also { b.charPreviewHost.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1)) }
            directPlacement = DirectCharacterPlacementController(preview!!, character.name,
                enabled = { foreground && !editingPlacement && sheet?.isShowing != true },
                blockedViews = { listOf(b.startHeader, b.startControls) },
                onInteraction = { nextIdleAt = SystemClock.elapsedRealtime() + Random.nextLong(24000, 48001) },
            ).also { it.setForeground(foreground) }
        }
        publishPlacement()
        if (foreground) {
            preview?.startRender()
            if (bundledVoiceReady) prepareSelectedVoice()
        } else preview?.stopRender()
        nextIdleAt = SystemClock.elapsedRealtime() + Random.nextLong(24000, 48001)
    }
    private fun publishPlacement() {
        placement = placement.sanitized()
        preview?.setPresentationTransform(placement.scale, placement.offsetX, placement.offsetY)
    }
    private fun beginPlacement(draft: SettingsDraft) {
        val view = preview ?: return
        val character = currentCharacter() ?: return
        directPlacement?.finishGesture()
        activeDraft = draft; sheet?.dismiss(); editingPlacement = true
        b.startControls.visibility = View.GONE
        placementEditor = CharacterPlacementEditor.show(this, character.name, view) {
            placementEditor = null; editingPlacement = false; b.startControls.visibility = View.VISIBLE
            placement = CharacterPresentationStore.load(this, character.name); publishPlacement()
            directPlacement?.reload()
            if (foreground) showSettings(activeDraft, SettingsDrawerController.Page.MODEL)
        }
    }
    private fun finishPlacement() {
        placementEditor?.close()
    }

    private fun showSettings(restore: SettingsDraft? = null,
        initialPage: SettingsDrawerController.Page = SettingsDrawerController.Page.HOME,
        preferredMode: ConnectionMode? = null) {
        if (sheet?.isShowing == true) return
        val draft = restore ?: runCatching { SettingsDraft(preferredMode ?: ConnectionSettings.selectedMode(this),
            ConnectionMode.values().associateWith { ConnectionSettings.simpleUrl(this, it) }.toMutableMap(),
            callRelayUrl = Prefs.get(this).first,
            language = LocalVoiceSettings.loadReplyLanguage(this)) }.getOrElse { toast("连接设置无法读取"); return }
        activeDraft = draft
        val dialog = BottomSheetDialog(this); val s = SheetSettingsBinding.inflate(layoutInflater)
        sheet = dialog; settings = s; dialog.setContentView(s.root)
        val drawer = SettingsDrawerController(s, dialog)
        s.btnSettingsGuide.setOnClickListener {
            dialog.dismiss()
            firstRunGuide.show()
        }
        s.proactiveSpeechSwitch.isChecked = CallProactiveSettings.enabled(this)
        s.editProactiveWaitSeconds.setText(CallProactiveSettings.waitSeconds(this).toString())
        var rendering = false
        val buttons = mutableMapOf<ConnectionMode, RadioButton>()
        val bridgeButton = android.widget.Button(this)
        val stopLocal = android.widget.Button(this).apply { text = "停止本机连接" }
        val goHelp = android.widget.Button(this).apply {
            text = "安装或查找 DSH Go 插件"
            setOnClickListener { DshGoSetup.showPluginHelp(this@StartActivity) }
        }
        var displayedFingerprint: String? = null
        var activateAfterReconnect = false
        fun renderLocalBridge() {
            val connected = LocalDshBridge.connectorReady
            if (activateAfterReconnect && connected) {
                activateAfterReconnect = false
                runCatching { ConnectionSettings.activateLocal(this) }
                    .onFailure { toast("本机连接设置未能保存") }
            }
            val running = LocalDshBridgeService.running
            val saved = LocalDshBridge.hasSavedGrant(this)
            val awaiting = LocalDshBridge.awaitingLocalPair()
            val fingerprint = LocalDshBridge.pendingFingerprint()
            displayedFingerprint = fingerprint
            val goInstalled = DshGoSetup.installed(this)
            goHelp.visibility = View.VISIBLE
            stopLocal.visibility = if (running) View.VISIBLE else View.GONE
            bridgeButton.visibility = if (!running || !saved || goInstalled) View.VISIBLE else View.GONE
            bridgeButton.isEnabled = fingerprint != null || !awaiting || goInstalled
            bridgeButton.text = when {
                fingerprint != null -> "允许本机配对"
                goInstalled && (awaiting || running && saved) -> "打开 DSH Go"
                saved && goInstalled -> "恢复桥接并打开 DSH Go"
                saved -> "恢复本机桥接"
                awaiting -> "等待 DSH Go"
                goInstalled -> "开启配对并打开 DSH Go"
                else -> "开启本机配对"
            }
            s.connectionModeHint.text = when {
                fingerprint != null -> "核对 Go 的标识 · $fingerprint"
                connected -> if (LocalDshBridge.asrReady) "已连接" else "已连接 · 语音识别未就绪"
                awaiting || running && saved -> "在 Go 的「设置」→「DSH L2D桥接」点连接，再返回确认"
                else -> "首次使用请先安装 Go 插件"
            }
            s.connectionModeHint.visibility = if (s.connectionModeHint.text.isNotEmpty()) View.VISIBLE else View.GONE
        }
        bridgeButton.setOnClickListener {
            runCatching {
                val fingerprint = displayedFingerprint
                val saved = LocalDshBridge.hasSavedGrant(this)
                if (fingerprint != null) {
                    LocalDshBridge.approveLocalPair(fingerprint)
                    LocalDshBridgeService.rememberForOpen(this, true)
                    ConnectionSettings.activateLocal(this)
                } else {
                    val listening = LocalDshBridge.awaitingLocalPair() || LocalDshBridgeService.running && saved
                    if (!listening) {
                        if (saved) LocalDshBridgeService.rememberForOpen(this, true)
                        else LocalDshBridge.armLocalPairing(this)
                        LocalDshBridgeService.startFromVisible(this)
                    }
                    activateAfterReconnect = saved
                    if ((listening || DshGoSetup.installed(this)) && !DshGoSetup.open(this)) {
                        toast("请手动打开 DSH Go")
                    }
                }
            }.onFailure { toast("本机桥接未连接") }
            renderLocalBridge()
        }
        stopLocal.setOnClickListener {
            activateAfterReconnect = false
            LocalDshBridgeService.stop(this)
            renderLocalBridge()
        }
        bridgeSettingsTick = object : Runnable {
            override fun run() {
                if (!foreground || !dialog.isShowing || draft.mode != ConnectionMode.LOCAL_DSH) return
                renderLocalBridge()
                handler.postDelayed(this, 1_000L)
            }
        }
        (s.connectionModeHint.parent as? android.view.ViewGroup)?.let { parent ->
            val row = parent.indexOfChild(s.connectionModeHint) + 1
            parent.addView(bridgeButton, row)
            parent.addView(stopLocal, row + 1)
            parent.addView(goHelp, row + 2)
        }
        fun capture() = captureSettings(s, draft)
        fun updateApiKeyHint() {
            s.editApiKey.hint = when {
                draft.clearApiKey -> "API Key · 待清除"
                ConnectionSettings.hasSimpleApiKey(this) -> "API Key · 已保存"
                else -> "API Key"
            }
        }
        fun renderMode() {
            cancelPairing(); rendering = true
            s.editConnectionUrl.setText(draft.urls[draft.mode].orEmpty())
            s.editApiKey.setText(draft.apiKeyInput)
            s.editCallRelayUrl.setText(draft.callRelayUrl)
            s.editCallRelayKey.setText(draft.callRelayKey)
            val api = draft.mode == ConnectionMode.DIRECT_API
            val local = draft.mode == ConnectionMode.LOCAL_DSH
            bridgeSettingsTick?.let { handler.removeCallbacks(it) }
            if (local) {
                renderLocalBridge()
                bridgeSettingsTick?.let { handler.postDelayed(it, 1_000L) }
            } else {
                activateAfterReconnect = false
                bridgeButton.visibility = View.GONE
                stopLocal.visibility = View.GONE
                goHelp.visibility = View.GONE
                s.connectionModeHint.visibility = View.GONE
            }
            s.editConnectionUrl.visibility = if (local) View.GONE else View.VISIBLE
            s.connectionUrlLabel.visibility = if (local) View.GONE else View.VISIBLE
            s.apiKeyRow.visibility = if (api) View.VISIBLE else View.GONE
            s.callRelayFields.visibility = if (api) View.VISIBLE else View.GONE
            s.pairingRow.visibility = if (api || local) View.GONE else View.VISIBLE
            s.connectionUrlLabel.text = if (api) "模型 API 地址" else "服务地址"
            s.editConnectionUrl.hint = s.connectionUrlLabel.text
            if (api) updateApiKeyHint()
            buttons.forEach { (mode, button) -> button.setBackgroundResource(if (mode == draft.mode) R.drawable.ui_chip_selected else R.drawable.ui_chip) }
            pairingState = if (api || local) null else runCatching { ServicePairingClient.current(this, draft.mode, draft.urls[draft.mode].orEmpty()) }.getOrNull()
            renderPairing(s, draft, pairingState); s.settingsError.visibility = View.GONE
            rendering = false
            if (dialog.isShowing) resumePairing(s, draft)
        }
        listOf(ConnectionMode.DIRECT_API, ConnectionMode.ASTRBOT, ConnectionMode.DEEPSEEK_HARNESS, ConnectionMode.LOCAL_DSH).forEachIndexed { index, mode ->
            val button = RadioButton(this).apply {
                id = View.generateViewId(); tag = mode
                text = when (mode) { ConnectionMode.DIRECT_API -> "API"; ConnectionMode.ASTRBOT -> "AstrBot"; ConnectionMode.DEEPSEEK_HARNESS -> "DSH"; ConnectionMode.LOCAL_DSH -> "本机" }
                gravity = Gravity.CENTER; textSize = 13f; buttonDrawable = null
                setTextColor(ContextCompat.getColor(this@StartActivity, R.color.ui_text))
                layoutParams = RadioGroup.LayoutParams(0, -1, 1f).apply { if (index < 2) marginEnd = dp(7) }
            }
            buttons[mode] = button; s.groupConnectionMode.addView(button)
        }
        s.groupConnectionMode.check(buttons.getValue(draft.mode).id)
        s.groupReplyLanguage.check(replyLanguageButton(draft.language))
        var renderingLanguage = false
        s.groupReplyLanguage.setOnCheckedChangeListener { _, checked ->
            if (!renderingLanguage && checked in setOf(R.id.replyJapanese, R.id.replyChinese, R.id.langEn)) {
                val language = replyLanguageValue(checked)
                if (language != draft.language) {
                    val previous = draft.language
                    runCatching { LocalVoiceSettings.saveReplyLanguage(this, language) }
                        .onSuccess { draft.language = language }
                        .onFailure {
                            renderingLanguage = true
                            s.groupReplyLanguage.check(replyLanguageButton(previous))
                            renderingLanguage = false
                            s.settingsError.text = "语言没有保存，请再选一次"; s.settingsError.visibility = View.VISIBLE
                        }
                }
            }
        }
        addSpeechSpeedSettings(s)
        val voiceParent = s.groupReplyLanguage.parent as LinearLayout
        voiceParent.addView(TtsBackendSettingsPanel.create(this, s.editConnectionUrl,
            onSaved = { useLocalAfterModelInstall = false; if (foreground) refreshSpeechPreparation() },
            onDownloadModels = {
                useLocalAfterModelInstall = true
                runCatching {
                    if (Audio8ModelStore.hasGithubModelSource(this) && !Audio8ModelStore.hasCompatibleModel(this))
                        Audio8ModelStore.downloadGithubPackage(this)
                    else Audio8ModelStore.retryLocalPreparation(this)
                }.onFailure { useLocalAfterModelInstall = false; toast("语音准备尚未开始，请稍后重试") }
            },
            onImportModels = {
                runCatching { pickSpeechPackage.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                    .onFailure { toast("无法打开文件选择器，请稍后重试") }
            },
            onAbandonLocalInstall = { useLocalAfterModelInstall = false },
            onImportWav = { role ->
                pendingVoiceRole = role
                val mime = if (role.startsWith("moss-json:")) {
                    arrayOf("application/json", "text/plain", "application/octet-stream")
                } else {
                    arrayOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "application/octet-stream")
                }
                runCatching { pickVoice.launch(mime) }
                    .onFailure { pendingVoiceRole = null; toast("无法打开 WAV 文件选择器") }
            },
            voiceCharacters = { characters.map { it.name to it.displayName } },
            currentVoiceCharacter = { currentCharacter()?.name ?: "whale" },
            mossModelStatus = { mossStatus() },
            onDownloadMossModels = { confirmMossDownload() },
            onDownloadAudio8Models = if (Audio8ModelStore.hasGithubModelSource(this)) ({
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("下载 Audio8 模型")
                    .setMessage("从已配置的 GitHub Release 下载语音模型包，支持续传。")
                    .setNegativeButton("取消", null).setPositiveButton("下载") { _, _ ->
                        runCatching { Audio8ModelStore.downloadGithubPackage(this) }
                            .onFailure { toast("下载尚未开始，请稍后重试") }
                    }.show()
            }) else null
        ), voiceParent.indexOfChild(s.groupReplyLanguage) + 2)
        voiceParent.addView(AsrPreparationSettingsPanel(this), voiceParent.indexOfChild(s.groupReplyLanguage) + 2)
        renderMode()
        s.groupConnectionMode.setOnCheckedChangeListener { group, id ->
            val mode = group.findViewById<RadioButton>(id)?.tag as? ConnectionMode
            if (mode != null && mode != draft.mode) { capture(); draft.mode = mode; renderMode() }
        }
        s.editConnectionUrl.doAfterTextChanged {
            if (!rendering) { cancelPairing(); pairingState = null; renderPairing(s, draft, null) }
        }
        s.btnClearApiKey.setOnClickListener {
            draft.apiKeyInput = ""; draft.clearApiKey = true
            s.editApiKey.setText(""); updateApiKeyHint()
        }
        s.editApiKey.doAfterTextChanged { text ->
            if (!rendering) {
                if (!text.isNullOrBlank()) draft.clearApiKey = false
                updateApiKeyHint()
            }
        }
        s.btnGeneratePairingCode.setOnClickListener {
            capture(); val old = pairingState
            startPairing(s, draft, refresh = old?.paired == true || !old?.code.isNullOrBlank())
        }
        s.btnSaveSettings.setOnClickListener {
            if (drawer.page == SettingsDrawerController.Page.CALL) {
                val seconds = s.editProactiveWaitSeconds.text.toString().trim().toIntOrNull()
                if (seconds == null || seconds !in CallProactiveSettings.MIN_SECONDS..CallProactiveSettings.MAX_SECONDS) {
                    s.settingsError.text = "请输入 ${CallProactiveSettings.MIN_SECONDS}–${CallProactiveSettings.MAX_SECONDS} 秒"
                    s.settingsError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                CallProactiveSettings.save(this, s.proactiveSpeechSwitch.isChecked, seconds)
                dialog.dismiss(); activeDraft = null; toast("通话设置已保存")
                return@setOnClickListener
            }
            capture()
            runCatching {
                val previousRelay = Prefs.get(this)
                val socketUrl = if (draft.mode == ConnectionMode.DIRECT_API)
                    ConnectionSettings.normalizeCallSocket(draft.callRelayUrl) else null
                require(draft.callRelayKey.none { it == '\r' || it == '\n' }) { "通话中继 Key 中不能包含换行" }
                ConnectionSettings.saveSimple(this, draft.mode, draft.urls[draft.mode].orEmpty(),
                    newApiKey = draft.apiKeyInput.takeIf { it.isNotBlank() }, clearApiKey = draft.clearApiKey)
                if (socketUrl != null) {
                    if (draft.callRelayKey.isNotBlank() || socketUrl != previousRelay.first)
                        ConnectionSettings.saveVoiceKey(this, draft.callRelayKey)
                    Prefs.set(this, socketUrl, previousRelay.second)
                }
            }.onSuccess { dialog.dismiss(); activeDraft = null; toast("设置已保存") }
                .onFailure {
                    drawer.show(SettingsDrawerController.Page.CONNECTION)
                    s.settingsError.text = it.message ?: "无法保存，请检查网址"
                    s.settingsError.visibility = View.VISIBLE
                }
        }
        s.btnAddChar.isEnabled = !importingCharacter
        s.btnAddBg.isEnabled = !importingBackground
        s.btnAddChar.setOnClickListener { capture(); if (!importingCharacter) pickCharacter.launch(arrayOf("application/zip", "application/octet-stream")) }
        s.btnAddBg.setOnClickListener { capture(); if (!importingBackground) pickBackground.launch(arrayOf("image/*", "video/*")) }
        s.btnAdjustBg.setOnClickListener {
            capture()
            if (backgroundEditor?.isShowing != true) {
                backgroundEditor = BackgroundEditor.show(this, bgLib.selected(Prefs.getSelectedBg(this)),
                    beforeOpen = { background.setForeground(false) },
                    afterClose = {
                        backgroundEditor = null
                        background.setForeground(foreground && !isFinishing && !isDestroyed)
                        background.showSelected()
                    })
            }
        }
        s.btnAdjustCharacter.setOnClickListener { capture(); beginPlacement(draft) }
        addCanvasQualitySettings(s)
        renderCharacters(s); renderBackgrounds(s)
        dialog.setOnDismissListener {
            cancelPairing()
            if (sheet === dialog) {
                bridgeSettingsTick?.let { handler.removeCallbacks(it) }; bridgeSettingsTick = null
                sheet = null; settings = null
            }
        }
        dialog.setOnShowListener {
            val panel = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            panel?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            panel?.layoutParams = panel?.layoutParams?.apply { height = (resources.displayMetrics.heightPixels * .88f).toInt() }
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            handler.removeCallbacks(pairingTick); handler.post(pairingTick)
            resumePairing(s, draft)
        }
        dialog.show()
        drawer.show(initialPage)
    }

    private fun addSpeechSpeedSettings(s: SheetSettingsBinding) {
        val parent = s.groupReplyLanguage.parent as LinearLayout
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        }
        panel.addView(TextView(this).apply {
            text = "语速"; textSize = 15f
            setTextColor(ContextCompat.getColor(this@StartActivity, R.color.ui_text))
        })
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }
        }
        val buttons = linkedMapOf<String, RadioButton>()
        listOf(SpeechTempo.ELEGANT to "优雅（${SpeechTempo.SPEED}×）", SpeechTempo.FAST to "快速（1×）").forEachIndexed { index, (mode, label) ->
            val button = RadioButton(this).apply {
                id = View.generateViewId(); tag = mode; text = label
                buttonDrawable = null; gravity = Gravity.CENTER; textSize = 13f
                setTextColor(ContextCompat.getColor(this@StartActivity, R.color.ui_text))
                layoutParams = RadioGroup.LayoutParams(0, -1, 1f).apply { if (index == 0) marginEnd = dp(8) }
            }
            buttons[mode] = button; group.addView(button)
        }
        fun render() { buttons.values.forEach { it.setBackgroundResource(if (it.isChecked) R.drawable.ui_chip_selected else R.drawable.ui_chip) } }
        group.check(buttons.getValue(SpeechTempo.preset(this)).id); render()
        group.setOnCheckedChangeListener { _, checked ->
            val mode = buttons.entries.firstOrNull { it.value.id == checked }?.key ?: return@setOnCheckedChangeListener
            SpeechTempo.savePreset(this, mode); render()
        }
        panel.addView(group)
        parent.addView(panel, parent.indexOfChild(s.groupReplyLanguage) + 2)
    }

    private fun addCanvasQualitySettings(s: SheetSettingsBinding) {
        val parent = s.btnAdjustCharacter.parent as LinearLayout
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) }
        }
        panel.addView(TextView(this).apply {
            text = "角色画布清晰度"; textSize = 13f
            setTextColor(ContextCompat.getColor(this@StartActivity, R.color.ui_text_secondary))
        })
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }
        }
        val selected = Prefs.getCanvasQuality(this)
        val buttons = CanvasQuality.values().associateWith { quality ->
            RadioButton(this).apply {
                id = View.generateViewId(); tag = quality
                text = quality.title; textSize = 13f; gravity = Gravity.CENTER; buttonDrawable = null
                setTextColor(ContextCompat.getColor(this@StartActivity, R.color.ui_text))
                layoutParams = RadioGroup.LayoutParams(0, -1, 1f).apply { if (quality != CanvasQuality.P1080) marginEnd = dp(7) }
                group.addView(this)
            }
        }
        fun renderSelection(quality: CanvasQuality) {
            buttons.forEach { (value, button) ->
                button.setBackgroundResource(if (value == quality) R.drawable.ui_chip_selected else R.drawable.ui_chip)
            }
        }
        group.check(buttons.getValue(selected).id)
        renderSelection(selected)
        group.setOnCheckedChangeListener { view, checked ->
            val quality = view.findViewById<RadioButton>(checked)?.tag as? CanvasQuality ?: return@setOnCheckedChangeListener
            if (quality != Prefs.getCanvasQuality(this)) {
                Prefs.setCanvasQuality(this, quality)
                previewReadyShown = false
                b.statusText.text = "正在切换角色清晰度"
                preview?.refreshCanvasQuality()
                toast("已切换至 ${quality.title}")
            }
            renderSelection(quality)
        }
        panel.addView(group)
        parent.addView(panel, parent.indexOfChild(s.btnAdjustCharacter) + 1)
    }

    private fun cancelPairing() { pairingGeneration++; pairingHandle?.cancel(); pairingHandle = null; handler.removeCallbacks(pairingTick) }
    private fun captureSettings(s: SheetSettingsBinding, draft: SettingsDraft) {
        draft.urls[draft.mode] = s.editConnectionUrl.text.toString().trim()
        draft.apiKeyInput = s.editApiKey.text.toString().trim()
        if (draft.mode == ConnectionMode.DIRECT_API) {
            draft.callRelayUrl = s.editCallRelayUrl.text.toString().trim()
            draft.callRelayKey = s.editCallRelayKey.text.toString().trim()
        }
        if (draft.apiKeyInput.isNotBlank()) draft.clearApiKey = false
        draft.language = replyLanguageValue(s.groupReplyLanguage.checkedRadioButtonId)
    }
    private fun resumePairing(s: SheetSettingsBinding, draft: SettingsDraft) {
        if (draft.mode in setOf(ConnectionMode.DIRECT_API, ConnectionMode.LOCAL_DSH) || !foreground) return
        val current = runCatching { ServicePairingClient.current(this, draft.mode, draft.urls[draft.mode].orEmpty()) }.getOrNull()
        pairingState = current; renderPairing(s, draft, current)
        if (current != null && !current.paired && current.expiresAtMillis > System.currentTimeMillis()) startPairing(s, draft, false)
        else { handler.removeCallbacks(pairingTick); handler.post(pairingTick) }
    }
    private fun startPairing(s: SheetSettingsBinding, draft: SettingsDraft, refresh: Boolean) {
        if (draft.mode == ConnectionMode.LOCAL_DSH) return
        cancelPairing(); val generation = pairingGeneration; val mode = draft.mode; val url = draft.urls[mode].orEmpty()
        runCatching { ServicePairingClient.start(this, mode, url, refresh) { state -> runOnUiThread {
            if (foreground && generation == pairingGeneration && settings === s && activeDraft?.mode == mode && s.editConnectionUrl.text.toString().trim() == url) {
                pairingState = state; renderPairing(s, draft, state)
            }
        } } }.onSuccess { pairingHandle = it }.onFailure {
            pairingState = ServicePairingClient.State(message = it.message ?: "连接码生成失败，请重试")
            renderPairing(s, draft, pairingState)
        }
        handler.post(pairingTick)
    }
    private fun renderPairing(s: SheetSettingsBinding, draft: SettingsDraft, state: ServicePairingClient.State?) {
        if (draft.mode in setOf(ConnectionMode.DIRECT_API, ConnectionMode.LOCAL_DSH)) return
        val expired = state != null && !state.paired && state.expiresAtMillis > 0 && state.expiresAtMillis <= System.currentTimeMillis()
        s.pairingCode.text = if (expired || state?.code.isNullOrBlank()) "— — — — — —" else state?.code
        s.pairingStatus.text = when {
            state?.paired == true -> "已配对"
            expired || state?.message == "配对码已过期，点击刷新" -> "已过期"
            state?.busy == true -> "生成中"
            !state?.code.isNullOrBlank() -> "待配对"
            else -> state?.message.orEmpty()
        }
        s.pairingStatus.visibility = if (s.pairingStatus.text.isNotEmpty()) View.VISIBLE else View.GONE
        s.btnGeneratePairingCode.isEnabled = state?.busy != true
        s.btnGeneratePairingCode.text = when { state?.paired == true -> "重新配对"; !state?.code.isNullOrBlank() -> "刷新连接码"; else -> "生成连接码" }
    }

    private fun renderCharacters(s: SheetSettingsBinding) {
        s.charStrip.removeAllViews()
        characters.forEach { item ->
            s.charStrip.addView(TextView(this).apply {
                text = item.displayName; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@StartActivity, R.color.ui_text))
                setBackgroundResource(if (item.name == currentCharacter()?.name) R.drawable.ui_chip_selected else R.drawable.ui_chip)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) }
                setOnClickListener { charLib.setCurrent(item.name); applyCharacterPreview(); renderCharacters(s) }
            })
        }
    }
    private fun renderBackgrounds(s: SheetSettingsBinding) {
        val generation = ++backgroundStripGeneration
        s.bgStrip.removeAllViews()
        val selected = getSharedPreferences("prts", MODE_PRIVATE).getString("bg", null)
        val selectedId = bgLib.selected(selected).id
        bgLib.all().forEach { item ->
            val view = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                contentDescription = item.name; setBackgroundResource(R.drawable.ui_chip)
                alpha = if (selectedId == item.id) 1f else .65f
                layoutParams = LinearLayout.LayoutParams(dp(108), -1).apply { marginEnd = dp(10) }
                setOnClickListener { Prefs.setSelectedBg(this@StartActivity, item.id); this@StartActivity.background.showSelected(); renderBackgrounds(s) }
            }
            s.bgStrip.addView(view)
            worker.execute {
                if (generation != backgroundStripGeneration) return@execute
                val bitmap = bgLib.loadBitmap(item, dp(108), dp(88))
                runOnUiThread { if (!isDestroyed && generation == backgroundStripGeneration && view.isAttachedToWindow) view.setImageBitmap(bitmap) else bitmap?.recycle() }
            }
        }
    }

    private fun watchSpeechPackages() {
        modelPreparation?.close()
        modelPreparation = Audio8ModelStore.observe(this) { state ->
            if (!foreground) return@observe
            if (state.phase in setOf("failed", "cancelled", "cancelling")) useLocalAfterModelInstall = false
            if (useLocalAfterModelInstall && state.ready) {
                useLocalAfterModelInstall = false
                runCatching { TtsBackendSettings.save(this, TtsBackendSettings.LOCAL, SpeechApiOptions(),
                    localEngine = TtsBackendSettings.LOCAL_ENGINE_AUDIO8) }
                    .onSuccess { toast("Audio8 手机语音已启用") }
                    .onFailure { toast("模型已安装，请在设置中选择手机语音并保存") }
            }
            refreshSpeechPreparation(state)
        }
    }
    private var audio8WarmupOwner: com.prts.client.audio.audio8.OfflineAudio8TtsEngine? = null
    private fun releaseAudio8WarmupOwner() { audio8WarmupOwner?.close(); audio8WarmupOwner = null }
    private fun preparingVoiceAssets(): Boolean =
        Audio8ModelStore.status().phase in setOf("installing", "importing", "preparing", "cancelling")

    private fun prepareSelectedVoice() {
        if (preparingVoiceAssets()) return
        val character = currentCharacter() ?: return
        val options = runCatching { TtsBackendSettings.load(this) }.getOrNull() ?: return
        if (options.mode != TtsBackendSettings.LOCAL || options.localEngine != TtsBackendSettings.LOCAL_ENGINE_AUDIO8) return
        val voice = LocalVoiceSettings.voiceProfile(this, character.name)
        val owner = audio8WarmupOwner ?: com.prts.client.audio.audio8.OfflineAudio8TtsEngine(applicationContext)
            .also { audio8WarmupOwner = it }
        owner.prepareReference(voice.voiceId)
    }

    private fun showLocalVoicePreparation() {
        val status = if (preparingVoiceAssets()) "preparing-assets"
            else OfflineAudio8TtsEngine.referenceWarmupStatus().optString("phase")
        val label = when (status) {
            "preparing-assets" -> "正在准备手机语音"
            "ready" -> "准备好了就开始吧"
            "failed" -> "语音预热未完成"
            else -> "正在预热手机语音"
        }
        if (b.statusText.text.toString() != label) b.statusText.text = label
    }

    private fun mossStatus(): String? {
        if (!MossNanoEngine.available) return "MOSS 运行时未安装"
        val state = MossModelStore.status(this)
        return when {
            state.phase == "downloading" -> "MOSS 下载中 · ${state.processedBytes * 100 / state.totalBytes.coerceAtLeast(1)}%"
            state.phase == "installing" -> "正在准备 MOSS · ${state.processedBytes * 100 / state.totalBytes.coerceAtLeast(1)}%"
            state.ready -> null
            state.error != null -> state.error
            BuildConfig.MOSS_MODEL_BUNDLED -> "MOSS 模型待准备"
            else -> "MOSS 模型待下载"
        }
    }

    private fun confirmMossDownload() {
        if (BuildConfig.MOSS_MODEL_BUNDLED) {
            MossModelStore.prepareBundled(this, retry = true)
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("下载 MOSS 模型")
            .setMessage("下载约 243 MB 的 MOSS INT8 CPU 模型，支持续传。")
            .setNegativeButton("取消", null).setPositiveButton("下载") { _, _ ->
                runCatching { MossModelStore.download(this) }.onFailure { toast("下载尚未开始，请稍后重试") }
            }.show()
    }

    private fun refreshSpeechPreparation(observed: Audio8ModelStore.State? = null) {
        val selected = runCatching { TtsBackendSettings.load(this) }
        localVoiceSelected = selected.getOrNull()?.mode == TtsBackendSettings.LOCAL
        localVoiceEngine = selected.getOrNull()?.localEngine ?: TtsBackendSettings.LOCAL_ENGINE_AUDIO8
        if (localVoiceSelected && localVoiceEngine == TtsBackendSettings.LOCAL_ENGINE_MOSS) {
            releaseAudio8WarmupOwner()
            MossModelStore.prepareBundled(this)
            val status = mossStatus()
            bundledVoiceReady = status == null
            b.btnStart.isEnabled = bundledVoiceReady && characters.isNotEmpty()
            b.statusText.text = status ?: "MOSS 已就绪"
            previewReadyShown = true
            return
        }
        if (!localVoiceSelected) {
            releaseAudio8WarmupOwner()
            bundledVoiceReady = selected.getOrNull()?.let { runCatching { it.api.normalized() }.isSuccess } == true
            b.btnStart.isEnabled = bundledVoiceReady && characters.isNotEmpty()
            if (!bundledVoiceReady) b.statusText.text = "请在设置中填写语音API地址，Key可留空"
            else { previewReadyShown = false; b.statusText.text = "语音API已选择" }
            return
        }
        val progress = observed ?: Audio8ModelStore.status(this)
        bundledVoiceReady = Audio8ModelStore.installed(this)
        b.btnStart.isEnabled = bundledVoiceReady && characters.isNotEmpty()
        if (bundledVoiceReady) {
            Audio8ModelStore.prepare(this)
            prepareSelectedVoice()
            previewReadyShown = false
            showLocalVoicePreparation()
        } else {
            releaseAudio8WarmupOwner()
            b.statusText.text = when (progress.phase) {
                "needs_reference" -> "请在设置中导入含参考音的 Audio8 模型包"
                "importing", "installing" -> "正在准备 Audio8 · ${progress.processedBytes / 1048576}/${progress.totalBytes / 1048576} MB"
                "cancelling" -> "正在取消 Audio8 资源准备"
                "cancelled", "failed" -> "请在设置中继续准备 Audio8 或选择语音 API"
                else -> if (BuildConfig.AUDIO8_MODEL_BUNDLED) "正在准备 Audio8" else "请导入 Audio8 模型包或设置语音 API"
            }
            if (progress.phase == "idle") Audio8ModelStore.prepare(this)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_phone_voice_install", useLocalAfterModelInstall)
        outState.putString("pending_voice_role", pendingVoiceRole)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume(); foreground = true
        if (!::b.isInitialized) return
        background.setForeground(backgroundEditor?.isShowing != true)
        if (ConnectionSettings.selectedMode(this) == ConnectionMode.LOCAL_DSH &&
            !LocalDshBridgeService.running && LocalDshBridgeService.canResumeOnOpen(this)) {
            runCatching { LocalDshBridgeService.startFromVisible(this) }
        }
        bridgeSettingsTick?.let { handler.removeCallbacks(it); handler.post(it) }
        watchSpeechPackages()
        mossPreparation?.close()
        mossPreparation = MossModelStore.observe(this) { if (foreground && localVoiceEngine == TtsBackendSettings.LOCAL_ENGINE_MOSS) refreshSpeechPreparation() }
        refreshSpeechPreparation()
        if (preview != null && preview?.isModelReady != true) { previewReadyShown = false; b.statusText.text = "正在准备角色" }
        preview?.startRender(); nextIdleAt = SystemClock.elapsedRealtime() + Random.nextLong(24000, 48001)
        directPlacement?.setForeground(true)
        handler.removeCallbacks(previewTick); handler.post(previewTick)
        firstRunGuide.onResume()
        val draft = activeDraft; val s = settings
        if (sheet?.isShowing == true && draft != null && s != null && draft.mode !in setOf(ConnectionMode.DIRECT_API, ConnectionMode.LOCAL_DSH)) {
            resumePairing(s, draft)
        }
    }
    override fun onPause() {
        modelPreparation?.close(); modelPreparation = null
        mossPreparation?.close(); mossPreparation = null
        settings?.let { s -> activeDraft?.let { captureSettings(s, it) } }
        bridgeSettingsTick?.let { handler.removeCallbacks(it) }
        foreground = false; background.setForeground(false)
        directPlacement?.setForeground(false); handler.removeCallbacks(previewTick); cancelPairing(); preview?.stopRender(); super.onPause()
    }
    override fun onDestroy() {
        firstRunGuide.close()
        releaseAudio8WarmupOwner()
        foreground = false; directPlacement?.close(); directPlacement = null; placementEditor?.close(); cancelPairing(); handler.removeCallbacksAndMessages(null); preview?.stopRender(); preview = null
        backgroundEditor?.dismiss(); backgroundEditor = null
        if (::background.isInitialized) background.close(); worker.shutdownNow(); super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun replyLanguageButton(language: String): Int = when (language) {
        "ja" -> R.id.replyJapanese; "en" -> R.id.langEn; else -> R.id.replyChinese
    }
    private fun replyLanguageValue(button: Int): String = when (button) {
        R.id.replyJapanese -> "ja"; R.id.langEn -> "en"; else -> "zh"
    }
}

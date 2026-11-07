package com.prts.client.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.view.View
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.prts.client.R
import com.prts.client.audio.MicRecorder
import com.prts.client.audio.SpeechPlayer
import com.prts.client.audio.SpeechTempo
import com.prts.client.audio.SpeechSynthesisRouter
import com.prts.client.audio.TtsBackendSettings
import com.prts.client.audio.audio8.Audio8ModelStore
import com.prts.client.audio.LocalVoiceSettings
import com.prts.client.databinding.ActivityCallBinding
import com.prts.client.live2d.Live2DView
import com.prts.client.net.InAudio
import com.prts.client.net.Actions
import com.prts.client.net.DisplayText
import com.prts.client.net.InEmotionUpdate
import com.prts.client.net.EmotionIntent
import com.prts.client.net.Proto
import com.prts.client.net.WsClient
import com.prts.client.net.ConnectionProfile
import com.prts.client.net.ConnectionSettings
import com.prts.client.net.FaceObservationMetadata
import com.prts.client.ui.assets.BackgroundPresenter
import com.prts.client.ui.assets.CharacterLibrary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject

class CallActivity : AppCompatActivity() {
    private lateinit var b: ActivityCallBinding
    private lateinit var ws: WsClient
    private lateinit var adapter: BubbleAdapter
    private lateinit var live2D: Live2DView
    private val handler = Handler(Looper.getMainLooper())
    private var buttonsHidden = false
    private lateinit var speech: SpeechPlayer
    private lateinit var mic: MicRecorder
    private lateinit var serverUrl: String
    private var connectionProfile: ConnectionProfile? = null
    private var voiceAccessToken = ""
    private var replyLanguage = "zh"
    private var callCharacter = "whale"
    private var opening = CallOpeningPolicy()
    private var background: BackgroundPresenter? = null
    private var placementEditor: CharacterPlacementEditor.Handle? = null
    private var placementEditing = false
    private var directPlacement: DirectCharacterPlacementController? = null
    private var providerReady = false
    private var localSpeech: SpeechSynthesisRouter? = null
    private lateinit var camera: CallCameraController
    private var pendingImage: PreparedCallImage? = null
    private var imageSending = false
    private var imageSendUncertain = false
    private var imageAttempt = 0L
    private var draftThumbnail: android.graphics.Bitmap? = null
    private var draftThumbnailImage: PreparedCallImage? = null
    private lateinit var motionDirector: AutoMotionDirector
    private var actionPanelOpen = false
    private var manualGestureExtended = false
    private var connectionSettingsError: String? = null
    @Volatile private var foreground = false
    private var modelPreparation: AutoCloseable? = null
    private var captionTickScheduled = false
    private val captions = StreamingCaptionController { frame ->
        if (foreground && ::adapter.isInitialized && !isDestroyed) {
            val manager = b.callChat.layoutManager as? LinearLayoutManager
            val follow = adapter.itemCount == 0 || (manager?.findLastVisibleItemPosition() ?: -1) >= adapter.itemCount - 2
            val position = adapter.upsertReply(frame.replyKey, Bubble(frame.who, frame.text, frame.translation, frame.language))
            if (!buttonsHidden) b.callChat.visibility = View.VISIBLE
            if (follow) b.callChat.scrollToPosition(position)
        }
    }
    private val captionTick = object : Runnable {
        override fun run() {
            if (!foreground) { captionTickScheduled = false; return }
            captions.advanceOne()
            if (captions.hasPending) handler.postDelayed(this, 24) else captionTickScheduled = false
        }
    }
    private fun animateCaptions() {
        if (foreground && captions.hasPending && !captionTickScheduled) {
            captionTickScheduled = true; handler.post(captionTick)
        }
    }
    // A picker result may clear the controller flag before onResume runs.
    private var mediaSuspended = false
    private var connectionState = "未连接"
    private var activityState = ""
    private var startedAt = 0L
    private var playbackCompletePending = false
    private val localOpeningPlayback = LocalOpeningPlaybackState()
    private var ackAttempt = 0
    private val proactive by lazy {
        CallProactivePolicy(SystemClock::elapsedRealtime, CallProactiveSettings.waitSeconds(this) * 1000L)
    }
    private var proactiveEnabled = true
    private var remoteProactiveSupported = false
    private var remoteProactiveId: String? = null
    private var proactivePlaybackComplete = false
    private var submittedSequence = 0L
    private var activeLocalRequest: String? = null
    private val replyToLocalRequest = LinkedHashMap<String, String>()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notice(if (granted) "麦克风权限已开启，点击麦克风开始录音" else "没有麦克风权限，仍可用文字聊天")
    }
    private val clockTick = object : Runnable {
        override fun run() {
            if (!foreground) return
            renderStatus()
            camera.setCallBusy(speech.isBusy || localSpeech?.isBusy == true || mic.isRecording || imageSending || camera.isPhotoProcessing)
            maybeSpeakProactively()
            handler.postDelayed(this, 1000)
        }
    }
    private val emotionTick = object : Runnable {
        override fun run() {
            if (!foreground) return
            motionDirector.updateEmotionFrame(manualUiOpen = actionPanelOpen || placementEditing || directPlacement?.isManipulating == true || mic.isRecording)
            handler.postDelayed(this, 100)
        }
    }
    private val playbackAck = object : Runnable {
        override fun run() {
            if (!foreground || !playbackCompletePending || speech.isBusy || localSpeech?.isBusy == true || !ws.isReady) return
            ws.sendSignal("frontend-playback-complete")
            if (++ackAttempt < 4) handler.postDelayed(this, longArrayOf(250, 1000, 3000)[ackAttempt - 1])
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        opening = CallOpeningPolicy(savedInstanceState?.getBoolean("localOpeningConsumed", false) == true)
        proactiveEnabled = CallProactiveSettings.enabled(this)
        b = ActivityCallBinding.inflate(layoutInflater)
        setContentView(b.root)
        camera = CallCameraController(this, { image ->
            pendingImage = image
            imageSendUncertain = false
            renderImageDraft()
        }, ::notice, {
            mic.stop(false)
            interruptReply()
            activityState = ""
            renderStatus()
        }, CallCameraUi(b.cameraPreviewCard, b.cameraPreviewHost, b.cameraPreviewStatus,
            b.faceTrackingToggle, b.btnPreviewPhoto, b.btnPreviewFlip, b.btnPreviewClose))

        background = BackgroundPresenter(this, b.bgImage).also { it.showSelected() }
        val charLib = CharacterLibrary(this)
        val character = charLib.current()
        callCharacter = character
        replyLanguage = LocalVoiceSettings.loadReplyLanguage(this)
        readConnectionSettings()
        b.callName.text = charLib.resolve(character)?.displayName ?: character
        startedAt = SystemClock.elapsedRealtime()

        // Live2D（Cubism 可用时真渲染；否则保持立绘占位）
        if (Live2DView.available) {
            live2D = Live2DView(this, character)
            val transform = CharacterPresentationStore.load(this, character)
            live2D.setPresentationTransform(transform.scale, transform.offsetX, transform.offsetY)
            b.live2dHost.addView(live2D, android.widget.FrameLayout.LayoutParams(-1, -1))
        } else {
            val iv = android.widget.ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.ic_avatar_placeholder)
            }
            b.live2dHost.addView(iv)
        }
        motionDirector = AutoMotionDirector(
            clock = SystemClock::elapsedRealtime,
            expressions = { if (::live2D.isInitialized) live2D.availableExpressions().map { it.id }.toSet() else emptySet() },
            gestures = { if (::live2D.isInitialized) live2D.availableGestures().map { it.id }.toSet() else emptySet() },
            showEmotionWeights = { weights -> if (::live2D.isInitialized) live2D.setEmotionWeights(
                weights.happy.toFloat(), weights.sad.toFloat(), weights.angry.toFloat(),
                weights.surprised.toFloat(), weights.shy.toFloat()) },
            clearAutoEmotion = { if (::live2D.isInitialized) live2D.clearEmotionState() },
            releaseManualExpression = { if (::live2D.isInitialized) live2D.clearExpression() },
            playGesture = { id -> ::live2D.isInitialized && live2D.playPerformanceGesture(id) },
            report = { Log.i("PRTS-AutoMotion", it) },
            emotionReport = { Log.i("PRTS-Emotion", it) },
            onPerformanceIntent = { emotion, signal ->
                if (::live2D.isInitialized) live2D.setPerformanceEmotion(emotion,
                    signal?.intensity?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: .35,
                    signal?.confidence?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 1.0,
                    signal?.valence?.takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0),
                    signal?.arousal?.takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0),
                    signal?.dominance?.takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0))
            },
        )
        motionDirector.setEnabled(getSharedPreferences("prts", MODE_PRIVATE).getBoolean("automatic_character_motion", true))

        adapter = BubbleAdapter()
        b.callChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.callChat.adapter = adapter
        (b.callChat.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        b.callChat.visibility = View.GONE

        speech = SpeechPlayer(::onSpeechStart, { message ->
            traceCall("audio_start", message)
            startReplyPerformance(message)
            if (!message.forwarded) ws.playbackStarted(message.display_text)
            activityState = "正在说话"
            renderStatus()
        }, { value ->
            if (::live2D.isInitialized) live2D.setMouth(value)
        }, {
            finishLocalOpeningIfIdle()
            schedulePlaybackAck()
        }, ::notice,
            onAudioEnded = { message, completed ->
                traceCall("audio_end", message, completed = completed)
                captions.finish(message, completed); animateCaptions()
            },
            onPerformanceAudio = { speaking, durationMs ->
                if (::live2D.isInitialized) live2D.setPerformanceSpeech(speaking, durationMs)
            },
            onPlaybackProgress = { message, playedMs, totalMs, known ->
                captions.progress(message, playedMs, totalMs, known); animateCaptions()
            },
            playbackSpeed = { SpeechTempo.speed(this) })
        val voiceProfile = LocalVoiceSettings.voiceProfile(this, callCharacter)
        Log.i("PRTS-Language", "call language=$replyLanguage local_voice_language=${voiceProfile.language} local_voice_id=${voiceProfile.voiceId} legacy=${connectionProfile?.usesLegacyCallSocket == true}")
        localSpeech = SpeechSynthesisRouter(this, voiceProfile,
            { payload -> if (foreground) speech.enqueue(payload) }, ::notice,
            { finishLocalOpeningIfIdle(); renderStatus(); schedulePlaybackAck() },
            onLocalPayload = { payload -> if (foreground) speech.enqueueLocal(payload) else payload.close() },
            onProgress = { progress ->
                if (foreground && !speech.isBusy) {
                    activityState = when (progress.phase) {
                        "preparing" -> "正在准备语音"
                        "synthesizing", "semantic", "conditioning", "acoustic" -> "正在生成语音"
                        "ready" -> "正在准备播放"
                        "waveform" -> "正在整理语音"
                        "failed" -> ""
                        else -> activityState
                    }
                    renderStatus()
                }
            },
            onStreamingPcm = { source, pcm -> foreground && speech.enqueuePcm(source, pcm) },
            onStreamingComplete = { source, result ->
                speech.completeStreaming(source, result)
                finishLocalOpeningIfIdle(); renderStatus(); schedulePlaybackAck()
            }, mossCharacter = callCharacter)
        mic = MicRecorder({ samples ->
            b.btnMic.isPressed = false
            activityState = "正在发送语音"
            renderStatus()
            ws.sendMic(samples, { faceObservation() }, replyLanguage) { ok ->
                val localDsh = connectionProfile?.mode == com.prts.client.net.ConnectionMode.LOCAL_DSH
                activityState = if (ok) "正在听清你说的话" else if (localDsh) "本机语音识别未能开始" else "语音没发出去"
                renderStatus()
                if (!ok) notice(if (localDsh) "本机语音识别未能开始，请检查DSHGo语音设置或稍后重试" else "语音没发出去，请等连接恢复再试")
            }
        }, { message -> b.btnMic.isPressed = false; activityState = ""; renderStatus(); notice(message) })
        b.btnMic.setOnClickListener {
            prioritizeCallInput()
            motionDirector.userInteraction()
            if (mic.isRecording) {
                mic.stop(true)
                activityState = "正在结束录音"
                renderStatus()
            } else if (mic.isBusy) {
                notice("正在整理上一段录音，请稍等")
            } else if (!ws.isReady || !providerReady) {
                notice(connectionSettingsError ?: connectionState)
            } else if (connectionProfile?.mode == com.prts.client.net.ConnectionMode.LOCAL_DSH && !com.prts.client.localdsh.LocalDshBridge.asrReady) {
                notice("DSHGo本机语音识别尚未就绪，请先检查语音设置")
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                interruptReply()
                if (mic.start()) {
                    activityState = "麦克风已开，再点一次关闭并发送"
                    renderStatus()
                }
            }
        }

        b.inputText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                if (count > 0 || before > 0) prioritizeCallInput()
            }
            override fun afterTextChanged(text: android.text.Editable?) = Unit
        })
        b.inputText.setOnEditorActionListener { v, _, _ ->
            sendTypedMessage()
            true
        }
        b.btnSend.setOnClickListener { sendTypedMessage() }
        b.btnCamera.setOnClickListener {
            prioritizeCallInput()
            motionDirector.userInteraction()
            camera.openPicker()
        }
        b.btnRemovePhoto.setOnClickListener { if (!imageSending) { pendingImage = null; renderImageDraft() } }
        b.btnAction.setOnClickListener { showExpressionActions() }

        if (::live2D.isInitialized) {
            directPlacement = DirectCharacterPlacementController(live2D, callCharacter,
                enabled = { foreground && !placementEditing && !actionPanelOpen && !mic.isRecording },
                blockedViews = { listOfNotNull(b.inputBar, b.buttonCluster, b.callChat, b.photoDraft,
                    b.cameraPreviewCard, b.faceTrackingToggle, b.callHint,
                    (b.callName.parent as? View)?.parent as? View) },
                onInteraction = { motionDirector.userInteraction() },
                onTap = ::recordScreenTap,
                onNonTap = ::cancelCharacterTap,
                onManipulationChanged = {
                    if (it) cancelCharacterTap()
                    motionDirector.manualInteraction()
                },
            )
        }

        b.btnHangup.setOnClickListener { finish() }
    }

    private fun showExpressionActions() {
        if (!::live2D.isInitialized) { notice("角色还在准备，请稍等"); return }
        val expressions = live2D.availableExpressions()
        val gestures = live2D.availableGestures().filter { it.id in setOf("call:nod", "call:shake", "call:bow", "call:sway", "call:raise-hand", "call:lower-hand") }
        actionPanelOpen = true
        motionDirector.manualInteraction()
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(actionDp(22), actionDp(12), actionDp(22), actionDp(24))
            setBackgroundResource(R.drawable.ui_sheet_surface)
        }
        content.addView(View(this).apply {
            setBackgroundResource(R.drawable.ui_sheet_handle)
        }, LinearLayout.LayoutParams(actionDp(36), actionDp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = actionDp(18)
        })
        content.addView(TextView(this).apply {
            text = "表情与动作"; textSize = 21f
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "平时会跟着对话表达，也可以在这里手动调整。"; textSize = 12f
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text_muted))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = actionDp(7); bottomMargin = actionDp(18) })
        content.addView(SwitchCompat(this).apply {
            text = "自动表情与动作"
            textSize = 14f
            contentDescription = "自动表情与动作开关"
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            isChecked = motionDirector.enabled
            setOnCheckedChangeListener { _, checked ->
                motionDirector.setEnabled(checked)
                if (!checked && ::live2D.isInitialized) live2D.resetMotionState()
                getSharedPreferences("prts", MODE_PRIVATE).edit()
                    .putBoolean("automatic_character_motion", checked).apply()
            }
        }, LinearLayout.LayoutParams(-1, actionDp(48)))
        content.addView(SwitchCompat(this).apply {
            text = "主动聊天"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            isChecked = proactiveEnabled
            setOnCheckedChangeListener { _, checked ->
                proactiveEnabled = checked
                prioritizeCallInput()
                CallProactiveSettings.saveEnabled(this@CallActivity, checked)
            }
        }, LinearLayout.LayoutParams(-1, actionDp(48)))
        content.addView(TextView(this).apply {
            text = "调整角色位置与大小"; contentDescription = "调整角色位置与大小"
            textSize = 13f; gravity = Gravity.CENTER_VERTICAL; setPadding(actionDp(14), 0, actionDp(14), 0)
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            setBackgroundResource(R.drawable.ui_chip); isClickable = true; isFocusable = true
            setOnClickListener { sheet.dismiss(); openCharacterPlacement() }
        }, LinearLayout.LayoutParams(-1, actionDp(46)).apply { bottomMargin = actionDp(16) })

        fun label(text: String) = content.addView(TextView(this).apply {
            this.text = text; textSize = 12f
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text_secondary))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = actionDp(9) })

        fun chip(text: String, description: String = text, clicked: () -> Unit) = TextView(this).apply {
            this.text = text; textSize = 13f; gravity = Gravity.CENTER
            contentDescription = description
            minHeight = actionDp(47); maxLines = 2
            setPadding(actionDp(6), actionDp(9), actionDp(6), actionDp(9))
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text))
            setBackgroundResource(R.drawable.ui_chip)
            isClickable = true; isFocusable = true
            setOnClickListener { clicked() }
        }

        fun grid(buttons: List<View>, columns: Int) {
            for (group in buttons.chunked(columns)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (index in 0 until columns) {
                    val item = group.getOrNull(index) ?: View(this)
                    row.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (index < columns - 1) marginEnd = actionDp(8)
                    })
                }
                content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = actionDp(8) })
            }
        }

        label("表情")
        grid(expressions.map { expression ->
            chip(expression.title, "表情：${expression.title}") {
                motionDirector.manualInteraction(manualExpression = true)
                live2D.setExpression(expression.id)
                if (expression.id == "mobile:neutral") live2D.resetMotionState()
                Log.i("PRTS-Call", "manual expression selected: ${expression.id}")
                sheet.dismiss()
            }
        }, 3)

        if (gestures.isNotEmpty()) {
            content.addView(View(this), LinearLayout.LayoutParams(1, actionDp(10)))
            label("手动动作幅度")
            lateinit var gentle: TextView
            lateinit var extended: TextView
            fun refreshIntensity() {
                for ((button, selected) in listOf(gentle to !manualGestureExtended, extended to manualGestureExtended)) {
                    button.isSelected = selected
                    button.setBackgroundResource(if (selected) R.drawable.ui_chip_selected else R.drawable.ui_chip)
                    button.setTextColor(ContextCompat.getColor(this, if (selected) R.color.ui_text else R.color.ui_text_secondary))
                    button.contentDescription = "手动动作幅度：${button.text}，${if (selected) "已选择" else "未选择"}"
                }
            }
            gentle = chip("轻柔") { manualGestureExtended = false; refreshIntensity() }
            extended = chip("舒展") { manualGestureExtended = true; refreshIntensity() }
            grid(listOf(gentle, extended), 2)
            refreshIntensity()
            content.addView(View(this), LinearLayout.LayoutParams(1, actionDp(10)))
            label("动作")
            grid(gestures.map { gesture ->
                val title = when (gesture.id) {
                    "call:nod" -> "点头"
                    "call:shake" -> "摇头"
                    "call:bow" -> "欠身"
                    "call:sway" -> "摇摆"
                    "call:raise-hand" -> "举手"
                    "call:lower-hand" -> "放下"
                    else -> gesture.title
                }
                chip(title, "动作：$title") {
                    motionDirector.manualInteraction()
                    if (live2D.playGesture(gesture.id, extended = manualGestureExtended)) {
                        Log.i("PRTS-Call", "manual gesture queued: ${gesture.id} extended=$manualGestureExtended")
                        sheet.dismiss()
                    } else notice("这个动作还在准备，请稍等")
                }
            }, 2)
        }
        content.addView(TextView(this).apply {
            text = "手动表情优先15秒，再回到当前心情；自动动作使用轻柔幅度。"
            textSize = 10f
            setTextColor(ContextCompat.getColor(this@CallActivity, R.color.ui_text_muted))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = actionDp(9) })
        sheet.setContentView(ScrollView(this).apply { isFillViewport = false; addView(content) })
        sheet.setOnShowListener {
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            sheet.behavior.maxHeight = (resources.displayMetrics.heightPixels * .78f).toInt()
            sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            sheet.window?.setDimAmount(.12f)
        }
        sheet.setOnDismissListener { actionPanelOpen = false; motionDirector.userInteraction() }
        sheet.show()
    }

    private fun openCharacterPlacement() {
        if (!::live2D.isInitialized || placementEditing) return
        directPlacement?.finishGesture()
        if (mic.isRecording) { mic.stop(false); b.btnMic.isPressed = false; activityState = ""; renderStatus() }
        placementEditing = true
        motionDirector.manualInteraction()
        placementEditor = CharacterPlacementEditor.show(this, callCharacter, live2D) {
            placementEditor = null; placementEditing = false
            directPlacement?.reload()
            if (foreground) motionDirector.manualInteraction()
        }
    }

    private fun actionDp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private var lastTap = 0L
    private var characterTap: Runnable? = null

    private fun cancelCharacterTap() {
        characterTap?.let(handler::removeCallbacks)
        characterTap = null
        lastTap = 0L
    }

    private fun recordScreenTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val doubleTap = lastTap != 0L && now - lastTap < 320L
        cancelCharacterTap()
        if (doubleTap) {
            toggleButtons()
            return
        }
        lastTap = now
        val region = live2D.characterTouchRegion(x, y) ?: return
        // Keep the existing double-tap shortcut exclusive of character reactions.
        characterTap = Runnable {
            characterTap = null
            if (foreground && !actionPanelOpen && !placementEditing && !mic.isRecording &&
                directPlacement?.isManipulating != true && motionDirector.onCharacterTouch(region)) {
                live2D.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                Log.i("PRTS-Touch", "character_reaction region=$region")
            }
        }.also { handler.postDelayed(it, 320L) }
    }
    private fun renderImageDraft() {
        val image = pendingImage
        if (image !== draftThumbnailImage) {
            // Android may still retain a displayed bitmap; release references instead of
            // recycling it under a display list. Only a new draft needs a small decode.
            b.photoDraftThumb.setImageDrawable(null)
            draftThumbnail = null
            draftThumbnailImage = image
            if (image != null) {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
                draftThumbnail = android.graphics.BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                b.photoDraftThumb.setImageBitmap(draftThumbnail)
            }
        }
        b.photoDraft.visibility = if (image != null && !buttonsHidden) View.VISIBLE else View.GONE
        b.photoDraftLabel.text = if (imageSendUncertain) "发送结果未确认\n不会自动重发" else "照片待发送\n可在下方加上文字"
        (b.callChat.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
            val margin = actionDp(if (image != null) 248 else 184)
            if (it.bottomMargin != margin) { it.bottomMargin = margin; b.callChat.layoutParams = it }
        }
        b.btnSend.contentDescription = if (image != null) "发送待发送照片和输入文字" else "发送输入文字"
    }

    private fun faceObservation(): FaceObservationMetadata? {
        val observed = camera.takeFreshObservation(3000) ?: return null
        if (observed.version != 1 || observed.faceCount != 1 || observed.ageMs !in 0..3000) return null
        val probabilities = listOf(observed.confidence, observed.smileProbability,
            observed.leftEyeOpenProbability, observed.rightEyeOpenProbability, observed.mouthOpen)
        val angles = listOf(observed.headYawDeg, observed.headPitchDeg, observed.headRollDeg)
        if (probabilities.any { it != null && (!it.isFinite() || it !in 0.0..1.0) }
            || angles.any { it != null && (!it.isFinite() || it !in -90.0..90.0) }) return null
        Log.i("PRTS-Camera", "observation piggyback prepared age_ms=${observed.ageMs} face_count=1")
        return FaceObservationMetadata(age_ms = observed.ageMs, face_count = observed.faceCount,
            confidence = observed.confidence, smile_probability = observed.smileProbability,
            left_eye_open_probability = observed.leftEyeOpenProbability,
            right_eye_open_probability = observed.rightEyeOpenProbability, mouth_open = observed.mouthOpen,
            head_yaw_deg = observed.headYawDeg, head_pitch_deg = observed.headPitchDeg, head_roll_deg = observed.headRollDeg)
    }

    private fun sendPendingImage(text: String) {
        if (imageSending) return
        if (!foreground || !::ws.isInitialized || !ws.isReady || !providerReady) {
            if (foreground && pendingImage != null) notice("照片留在输入区，连接就绪后请按发送")
            return
        }
        val image = pendingImage ?: return
        if (text.length > 500) { notice("照片附带的文字最多500字，请缩短后发送"); return }
        imageSending = true
        imageSendUncertain = false
        val attempt = ++imageAttempt
        b.btnSend.isEnabled = false
        interruptReply()
        activityState = "正在发送图片"
        ws.sendImage(image.jpeg, text, faceObservation(), replyLanguage) { sent ->
            if (attempt != imageAttempt || isDestroyed) return@sendImage
            imageSending = false
            b.btnSend.isEnabled = true
            if (sent) {
                if (pendingImage === image) pendingImage = null
                if (b.inputText.text.toString().trim() == text) b.inputText.setText("")
                renderImageDraft()
                if (text.isNotBlank()) motionDirector.onUserInput(text)
                addBubble("我", "[图片] " + text.ifBlank { "看看这张图片" })
                activityState = "正在看图片"
            } else { activityState = "图片未发送"; notice("照片仍在输入区，请等连接恢复后再按发送") }
            renderStatus()
        }
        renderStatus()
    }

    private fun finishUnconfirmedImageSend(showNotice: Boolean) {
        if (!imageSending) return
        imageSending = false
        imageAttempt++ // Invalidates a delayed completion from the disconnected socket.
        imageSendUncertain = true
        b.btnSend.isEnabled = true
        renderImageDraft()
        activityState = "照片发送结果未确认"
        Log.i("PRTS-Camera", "image send result unknown after connection/lifecycle change; draft retained; no automatic replay")
        if (showNotice) notice("照片发送结果未确认，仍保留在输入区，不会自动重发")
    }

    private fun providerConfigured() {
        val local = localSpeech
        if (local == null) {
            providerReady = true; connectionState = "已连接"; activityState = ""
            renderImageDraft()
        } else if (!local.available) {
            providerReady = false
            if (local.usesLocalModel) {
                val preparation = Audio8ModelStore.status(this)
                connectionState = if (preparation.phase == "needs_reference") "请导入 Audio8 参考音" else "正在准备手机语音"
                if (preparation.error != null) notice("手机语音准备失败，请检查可用存储后重试")
            } else { connectionState = "请检查语音API设置"; notice(local.unavailableReason) }
        } else {
            providerReady = false; connectionState = "正在启用手机语音"
            if (!ws.configureVoice(true)) notice("手机语音模式没有发送成功")
        }
        renderStatus()
    }
    private fun prioritizeCallInput() {
        if (proactive.userInput()) {
            interruptReply()
            activityState = ""
            renderStatus()
        }
    }

    private fun maybeSpeakProactively() {
        if (proactive.finishAfterPlayback(proactivePlaybackComplete,
                playbackCompletePending || speech.isBusy || localSpeech?.isBusy == true)) {
            remoteProactiveId = null
            proactivePlaybackComplete = false
        }
        if (!proactiveEnabled || !::ws.isInitialized || !ws.isReady || !providerReady ||
            (!remoteProactiveSupported && connectionProfile?.mode != com.prts.client.net.ConnectionMode.LOCAL_DSH) ||
            !window.decorView.hasWindowFocus() || actionPanelOpen || placementEditing || mic.isBusy ||
            camera.isPhotoInteractionPending || speech.isBusy || localSpeech?.isBusy == true ||
            activityState.isNotBlank() || playbackCompletePending || pendingImage != null ||
            b.inputText.text.isNotBlank() || !proactive.canStart()) return
        val observation = faceObservation()
        val hint = when {
            (observation?.smile_probability ?: 0.0) >= .7 -> "smile"
            observation != null -> "present"
            else -> null
        }
        val sent = if (remoteProactiveSupported) {
            val id = java.util.UUID.randomUUID().toString().replace("-", "")
            ws.sendRemoteProactive(id, hint, replyLanguage).also {
                if (it) {
                    remoteProactiveId = id
                    proactivePlaybackComplete = false
                }
            }
        } else {
            val context = when (hint) {
                "smile" -> "前置相机本机分析发现对方可能在微笑；只能当成不确定的线索。"
                "present" -> "前置相机本机分析检测到一张脸；表情含义不确定。"
                else -> ""
            }
            val prompt = "【通话应用的安静提醒，非用户发言】现在通话暂时安静。$context" +
                "用当前角色自然地主动说一句短话，衔接最近话题或轻声开个新话头。" +
                "不要读出提醒，不要凭空描述你看到的画面，不要调用工具或执行操作。"
            ws.sendProactiveText(prompt, replyLanguage)
        }
        if (sent) {
            proactive.started()
            activeLocalRequest = "call-proactive-${SystemClock.elapsedRealtime()}-${++submittedSequence}"
            traceCall("proactive_submit", requestId = activeLocalRequest)
            activityState = "正在回复"
            renderStatus()
        }
    }

    private fun sendTypedMessage() {
        prioritizeCallInput()
        val text = b.inputText.text.toString().trim()
        if (pendingImage != null) { sendPendingImage(text); return }
        if (text.isEmpty()) return
        if (!ws.isReady || !providerReady) { notice("${connectionSettingsError ?: connectionState} · 文字未发送"); return }
        interruptReply()
        if (ws.sendText(text, faceObservation(), replyLanguage)) {
            activeLocalRequest = "call-${SystemClock.elapsedRealtime()}-${++submittedSequence}"
            traceCall("call_submit", requestId = activeLocalRequest, inputChars = text.codePointCount(0, text.length))
            motionDirector.onUserInput(text)
            addBubble("我", text)
            activityState = "正在回复"
            b.inputText.setText("")
            renderStatus()
        } else notice("文字没发出去，请稍后再试")
    }

    private fun toggleButtons() {
        buttonsHidden = !buttonsHidden
        b.micControl.visibility = if (buttonsHidden) View.INVISIBLE else View.VISIBLE
        b.inputBar.visibility = if (buttonsHidden) View.GONE else View.VISIBLE
        b.callChat.visibility = if (buttonsHidden || adapter.itemCount == 0) View.GONE else View.VISIBLE
        b.callHint.visibility = View.GONE
        b.photoDraft.visibility = if (!buttonsHidden && pendingImage != null) View.VISIBLE else View.GONE
        // 挂断键常驻，不隐藏
    }

    private fun callTime(): String {
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun renderStatus() {
        b.callTimer.text = "${callTime()} · $connectionState" + if (activityState.isBlank()) "" else " · $activityState"
        val ready = ::ws.isInitialized && ws.isReady && providerReady
        b.connectionDot.alpha = if (ready) 1f else 0.4f
        val microphoneOn = ::mic.isInitialized && mic.isRecording
        b.micLabel.text = if (microphoneOn) "麦克风 · 开" else "麦克风 · 关"
        b.btnMic.isSelected = microphoneOn
        b.btnMic.isEnabled = !::mic.isInitialized || microphoneOn || !mic.isBusy
        b.btnMic.setBackgroundResource(if (microphoneOn) R.drawable.ui_chip_selected else R.drawable.ui_glass_button)
        b.btnMic.contentDescription = if (microphoneOn) "麦克风已开启，点击关闭并发送录音，每段最多60秒" else "麦克风已关闭，点击开启录音"
        if (android.os.Build.VERSION.SDK_INT >= 30) b.btnMic.stateDescription = if (microphoneOn) "开启" else "关闭"
    }

    private fun notice(text: String) {
        if (!isFinishing && !isDestroyed) Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun addBubble(name: String, text: String, translation: String? = null, translationLanguage: String? = null) {
        adapter.add(Bubble(name, text, translation, translationLanguage))
        if (!buttonsHidden) b.callChat.visibility = View.VISIBLE
        b.callChat.smoothScrollToPosition(adapter.itemCount - 1)
    }

    private fun onSpeechStart(message: InAudio) {
        // One reply row. Characters advance with actual playback, never with queued PCM.
        traceCall("caption_ready", message)
        val display = message.display_text
        val text = display?.text?.ifBlank { null } ?: display?.transcript?.ifBlank { null }
        if (!text.isNullOrBlank()) captions.begin(message,
            message.actions?.reply_id?.let { "reply:$it" }
                ?: activeLocalRequest?.let { "request:$it" }
                ?: "voice:${System.identityHashCode(message)}",
            b.callName.text.toString(), text, display?.translation, display?.translation_language)
        activityState = if (message.audio.isNullOrBlank()) "正在回复" else "准备语音"
        renderStatus()
    }

    private fun startReplyPerformance(message: InAudio) {
        val display = message.display_text
        val text = display?.text?.ifBlank { null } ?: display?.transcript?.ifBlank { null }
        if (actionPanelOpen || mic.isRecording) motionDirector.manualInteraction()
        motionDirector.onSpeech(text.orEmpty(), AutoMotionDirector.Cue(
            expression = message.actions?.expressions?.firstOrNull()?.content,
            gesture = message.actions?.motions?.firstOrNull(),
            replyId = message.actions?.reply_id,
            emotionIntent = emotionSignal(message),
        ))
    }

    /** Timings only: no input/reply text, endpoints, prompts or credentials.
     * request_id is local sequence association, not a server-echoed correlation ID.
     */
    private fun traceCall(event: String, message: InAudio? = null, requestId: String? = null,
                          completed: Boolean? = null, inputChars: Int? = null) {
        val replyId = message?.actions?.reply_id
        val associated = requestId ?: if (message?.forwarded == true) null
            else replyId?.let { replyToLocalRequest[it] } ?: activeLocalRequest
        val record = JSONObject().put("event", event).put("at_ms", SystemClock.elapsedRealtime())
            .put("engine", localSpeech?.engineId ?: LocalVoiceSettings.MODE_ID)
            .put("character", callCharacter).put("language", replyLanguage)
            .put("correlation", if (message?.forwarded == true) "local_opening" else "local_sequence_association")
        associated?.let { record.put("request_id", it) }
        replyId?.let { record.put("reply_id", it.take(160)) }
        completed?.let { record.put("completed", it) }
        inputChars?.let { record.put("input_chars", it) }
        message?.let { record.put("forwarded", it.forwarded) }
        Log.i("PRTS-LocalSpeech", record.toString())
    }

    private fun emotionSignal(message: InAudio): EmotionSignal? = emotionSignal(message.actions?.emotion_intent)

    private fun emotionSignal(intent: EmotionIntent?): EmotionSignal? = intent?.let {
        EmotionSignal(it.emotion, it.intensity, it.valence, it.arousal, it.dominance,
            it.confidence, it.source, it.analysis_ms, it.intent, it.intent_confidence,
            it.gesture, it.gesture_intensity)
    }

    private fun schedulePlaybackAck() {
        if (!foreground || !playbackCompletePending || speech.isBusy || localSpeech?.isBusy == true) return
        handler.removeCallbacks(playbackAck)
        ackAttempt = 0
        // Coalesce queued payloads; bounded retries cover an ACK reaching the server before its waiter exists.
        handler.postDelayed(playbackAck, 100)
    }

    private fun interruptReply() {
        val id = remoteProactiveId
        remoteProactiveId = null
        proactivePlaybackComplete = false
        proactive.finished()
        captions.cancel(); handler.removeCallbacks(captionTick); captionTickScheduled = false
        localOpeningPlayback.cancel()
        opening.consume()
        playbackCompletePending = false
        handler.removeCallbacks(playbackAck)
        speech.cancel()
        localSpeech?.cancel()
        if (::motionDirector.isInitialized) motionDirector.cancelReply()
        else if (::live2D.isInitialized) live2D.clearExpression()
        if (::ws.isInitialized && ws.isReady) {
            if (id == null) ws.sendInterrupt() else ws.sendRemoteProactiveCancel(id)
        }
    }

    private fun onWsEvent(raw: String) {
        try {
            if (!foreground && !mediaSuspended) return
            val obj = Proto.json.parseToJsonElement(raw) as? JsonObject ?: return
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            val proactiveId = obj["proactive_id"]?.jsonPrimitive?.contentOrNull
            if (proactiveId != null && proactiveId != remoteProactiveId) return
            if (!foreground && type !in setOf("mobile-provider-ready", "mobile-provider-error",
                    "voice-profile-ready", "voice-profile-error")) {
                // A reply interrupted before opening the picker must not leave an ACK waiter.
                if (type == "backend-synth-complete") ws.sendInterrupt()
                return
            }
            when (type) {
                "mobile-provider-ready" -> {
                    remoteProactiveSupported = obj["proactive"]?.jsonPrimitive?.contentOrNull == "mobile-proactive-v1" &&
                        connectionProfile?.mode == com.prts.client.net.ConnectionMode.DIRECT_API
                    providerConfigured()
                }
                "mobile-proactive-rejected" -> {
                    if (proactiveId != null) {
                        remoteProactiveId = null
                        proactive.finished()
                        activityState = ""
                        renderStatus()
                    }
                }
                "voice-profile-ready" -> {
                    if (localSpeech != null && obj["mode"]?.jsonPrimitive?.contentOrNull == "client-local") {
                        providerReady = true; connectionState = "已连接 · 手机发声"; activityState = ""
                        renderImageDraft(); renderStatus()
                        playOpeningIfReady()
                    }
                }
                "voice-profile-error" -> {
                    providerReady = false; connectionState = "手机语音启用失败"; renderStatus()
                    notice(obj["message"]?.jsonPrimitive?.contentOrNull ?: "无法启用手机语音，请返回设置")
                }
                "image-input-accepted" -> { activityState = "正在看图片"; renderStatus() }
                "image-input-error" -> {
                    activityState = "图片未能处理"; renderStatus()
                    notice(obj["message"]?.jsonPrimitive?.contentOrNull ?: "图片未能处理，请稍后再试")
                }
                "mobile-provider-error" -> {
                    providerReady = false
                    connectionState = "回复来源配置失败"
                    activityState = "请返回设置检查"
                    renderStatus()
                    notice(obj["message"]?.jsonPrimitive?.contentOrNull ?: "来源配置未成功，未切换到其他模型")
                }
                "mobile-provider-status" -> {
                    activityState = when (obj["status"]?.jsonPrimitive?.contentOrNull) {
                        "looking_at_image" -> "正在看图片"
                        "needs_interaction" -> "等待你在原界面确认"
                        "running" -> "正在处理"
                        else -> ""
                    }
                    renderStatus()
                }
                "early-speech-preview", "early-speech-cancel" -> {
                    if (connectionProfile?.mode == com.prts.client.net.ConnectionMode.LOCAL_DSH) {
                        val id = obj["reply_id"]?.jsonPrimitive?.contentOrNull ?: return
                        if (type == "early-speech-cancel") localSpeech?.cancelPreview(id)
                        else obj["text"]?.jsonPrimitive?.contentOrNull?.let { localSpeech?.preparePreview(id, it) }
                    }
                }
                "audio" -> {
                    val msg = Proto.json.decodeFromString(InAudio.serializer(), raw)
                    if (!msg.forwarded) msg.actions?.reply_id?.let { replyId ->
                        activeLocalRequest?.let { requestId ->
                            if (!replyToLocalRequest.containsKey(replyId)) {
                                if (replyToLocalRequest.size >= 64) replyToLocalRequest.remove(replyToLocalRequest.keys.first())
                                replyToLocalRequest[replyId] = requestId
                            }
                        }
                    }
                    traceCall("backend_reply", msg)
                    motionDirector.stageCue(AutoMotionDirector.Cue(
                        expression = msg.actions?.expressions?.firstOrNull()?.content,
                        gesture = msg.actions?.motions?.firstOrNull(), replyId = msg.actions?.reply_id,
                        emotionIntent = emotionSignal(msg),
                    ))
                    handler.removeCallbacks(playbackAck)
                    localSpeech?.enqueue(msg) ?: speech.enqueue(msg)
                }
                "emotion-update" -> {
                    // Optional metadata never blocks or enters the speech queue. The director
                    // requires the current active reply ID already seen on its audio envelope.
                    val update = runCatching { Proto.json.decodeFromString(InEmotionUpdate.serializer(), raw) }.getOrNull()
                    if (update != null) emotionSignal(update.emotion_intent)?.let {
                        motionDirector.onEmotionUpdate(update.reply_id, it)
                    }
                }
                "backend-synth-complete" -> {
                    traceCall("backend_complete")
                    playbackCompletePending = true
                    schedulePlaybackAck()
                }
                "control" -> when (obj["text"]?.jsonPrimitive?.contentOrNull) {
                    "conversation-chain-start" -> {
                        motionDirector.beginReply()
                        playbackCompletePending = false
                        handler.removeCallbacks(playbackAck)
                        activityState = "正在回复"
                        renderStatus()
                    }
                    "conversation-chain-end" -> {
                        if (proactive.isActive) proactivePlaybackComplete = true
                        motionDirector.endReply()
                        playbackCompletePending = false
                        handler.removeCallbacks(playbackAck)
                        activityState = ""
                        renderStatus()
                    }
                    "interrupt" -> {
                        remoteProactiveId = null
                        proactivePlaybackComplete = false
                        proactive.finished()
                        captions.cancel(); handler.removeCallbacks(captionTick); captionTickScheduled = false
                        localOpeningPlayback.cancel()
                        motionDirector.cancelReply()
                        playbackCompletePending = false; handler.removeCallbacks(playbackAck)
                        speech.cancel(); localSpeech?.cancel(); activityState = ""; renderStatus()
                    }
                }
                "local-asr-error" -> {
                    activityState = "语音识别未完成"; renderStatus()
                    notice(obj["message"]?.jsonPrimitive?.contentOrNull ?: "本机语音识别失败，请重新说一次")
                }
                "user-input-transcription" -> {
                    obj["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        motionDirector.onUserInput(it)
                        addBubble("我", it)
                    }
                    activityState = "正在回复"
                    renderStatus()
                }
                "error" -> {
                    if (proactive.isActive) {
                        interruptReply()
                        activityState = ""
                        renderStatus()
                        return
                    }
                    proactive.finished()
                    localOpeningPlayback.cancel()
                    motionDirector.cancelReply()
                    activityState = "后端处理失败"
                    renderStatus()
                    notice("后端处理失败，未收到完整回复")
                }
            }
        } catch (error: Exception) {
            Log.w("PRTS-Call", "Cannot decode backend event", error)
            notice("收到一条无法读取的回复，请稍后再试")
        }
    }

    private fun readConnectionSettings() {
        val mode = ConnectionSettings.selectedMode(this)
        connectionSettingsError = null
        connectionProfile = null
        voiceAccessToken = ""
        serverUrl = Prefs.get(this).first
        try {
            val profile = ConnectionSettings.load(this, mode)
            connectionProfile = profile
            if (profile.isPaired) {
                val relay = java.net.URI(profile.endpoint)
                require(relay.scheme?.lowercase() in setOf("http", "https") && !relay.host.isNullOrBlank())
                serverUrl = profile.endpoint.trimEnd('/').replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                    .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://") + "/mobile-call/v1/ws"
                voiceAccessToken = profile.apiKey
            } else {
                connectionSettingsError = when {
                    profile.requiresPairing -> profile.validationError()
                    serverUrl.isBlank() -> "请先在连接设置填写服务地址"
                    else -> null
                }
                if (connectionSettingsError == null) voiceAccessToken = ConnectionSettings.loadVoiceKey(this)
            }
        } catch (_: Exception) {
            connectionSettingsError = if (mode == com.prts.client.net.ConnectionMode.LOCAL_DSH)
                "本机桥未开启，请回到 L2D 设置恢复连接"
            else "连接设置无法读取，请返回设置检查"
        }
    }

    private fun openConnection() {
        providerReady = false
        val localDsh = connectionProfile?.mode == com.prts.client.net.ConnectionMode.LOCAL_DSH
        ws = WsClient(serverUrl, ::onWsEvent, voiceAccessToken) { state ->
            // Keep configuration state current if the socket reconnects inside the picker.
            if (!foreground && !mediaSuspended) return@WsClient
            connectionState = when {
                state == "open" -> "已连接"
                localDsh && state == "unauthorized" -> "本机授权已失效，请到 DSH Go 的「全部功能」→「DSH L2D桥接」重连"
                localDsh -> "等待 DSH Go 本机连接"
                state == "connecting" -> "连接中"
                state == "invalid-url" -> "服务器地址不正确"
                state == "unauthorized" -> "通话服务 Key 不正确或未获授权"
                state.startsWith("retry ") -> "连接断开，重连 ${state.removePrefix("retry ")}"
                else -> "连接失败，请返回检查服务器地址"
            }
            if (state == "open") {
                providerReady = false
                val profile = connectionProfile
                if (profile == null) {
                    connectionState = "连接设置需要重新填写"
                } else if (profile.usesLegacyCallSocket) {
                    val config = Prefs.getConfig(this)
                    if (config.isNotBlank() && config != "default") ws.switchConfig(config)
                    providerConfigured()
                } else {
                    connectionState = "正在配置回复来源"
                    if (!ws.configureProvider(profile, replyLanguage)) connectionState = "来源配置未发送"
                    handler.postDelayed({
                        if (foreground && !providerReady && ws.isReady && connectionState == "正在配置回复来源") {
                            connectionState = if (localDsh) "本机插件未完成握手" else "回复来源配置超时"
                            renderStatus()
                        }
                    }, 20000)
                }
            } else {
                remoteProactiveSupported = false
                remoteProactiveId = null
                proactivePlaybackComplete = false
                proactive.finished()
                localOpeningPlayback.cancel()
                motionDirector.cancelReply()
                providerReady = false
                mic.stop(false)
                speech.cancel()
                localSpeech?.cancel()
                playbackCompletePending = false
                handler.removeCallbacks(playbackAck)
                activityState = ""
                finishUnconfirmedImageSend(showNotice = foreground)
            }
            renderStatus()
        }
        val error = connectionSettingsError
        if (error == null) ws.open()
        else { connectionState = error; renderStatus() }
    }

    private fun playOpeningIfReady() {
        val router = localSpeech ?: return
        val line = opening.claim(callCharacter, replyLanguage, foreground,
            providerReady, router.available,
            speech.isBusy || router.isBusy || mic.isRecording || imageSending ||
                actionPanelOpen || placementEditing || b.inputText.text.isNotBlank()) ?: return
        val message = InAudio(display_text = DisplayText(text = line.text,
            translation = line.translation, translation_language = line.translationLanguage,
            spoken_text = line.spokenText),
            actions = Actions(reply_id = "local-opening-ellen-v1"), forwarded = true)
        activityState = "正在准备语音"
        localOpeningPlayback.queued()
        Log.i("PRTS-Opening", "local opening queued character=ellen language=$replyLanguage")
        router.enqueue(message)
        renderStatus()
    }

    /** A local welcome has no backend chain-end. Its failure can hand text to
     * SpeechPlayer synchronously while the router is still busy, so both idle
     * sources must check completion. No caption or retry is produced here. */
    private fun finishLocalOpeningIfIdle() {
        if (!localOpeningPlayback.takeIdleCompletion(foreground, speech.isBusy,
                localSpeech?.isBusy == true, playbackCompletePending)) return
        activityState = ""
        if (::motionDirector.isInitialized) motionDirector.endReply()
        renderStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("localOpeningConsumed", opening.consumed)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        proactive.resumed()
        if (localSpeech?.engineId == LocalVoiceSettings.MODE_ID) {
            Audio8ModelStore.prepare(this)
            modelPreparation = Audio8ModelStore.observe(this) { state ->
            if (foreground) {
                if (!state.ready && state.error == null) {
                    val percent = if (state.totalBytes > 0) (state.processedBytes * 100 / state.totalBytes).coerceIn(0, 100) else 0
                    activityState = "正在准备语音 · $percent%"
                    renderStatus()
                } else if (state.ready && ::ws.isInitialized && ws.isReady && !providerReady) providerConfigured()
            }
            }
        }
        directPlacement?.setForeground(true)
        if (background == null) background = BackgroundPresenter(this, b.bgImage).also { it.showSelected() }
        camera.setForeground(true)
        motionDirector.setForeground(true)
        if (::live2D.isInitialized) {
            val transform = CharacterPresentationStore.load(this, callCharacter)
            live2D.setPresentationTransform(transform.scale, transform.offsetX, transform.offsetY)
            live2D.startRender()
        }
        val reuseMediaConnection = mediaSuspended && ::ws.isInitialized && ws.isReady
        mediaSuspended = false
        if (reuseMediaConnection) {
            // Do not reconfigure an uninterrupted socket: that would create a new remote session.
            renderImageDraft()
            renderStatus()
        } else {
            if (::ws.isInitialized) ws.close()
            if (ConnectionSettings.selectedMode(this) == com.prts.client.net.ConnectionMode.LOCAL_DSH) readConnectionSettings()
            openConnection()
        }
        handler.post(clockTick)
        handler.post(emotionTick)
        handler.postDelayed({ if (foreground) b.callHint.visibility = View.GONE }, 6500)
    }

    override fun onPause() {
        cancelCharacterTap()
        modelPreparation?.close(); modelPreparation = null
        directPlacement?.setForeground(false)
        placementEditor?.close()
        background?.close(); background = null
        mediaSuspended = camera.externalActivityPending
        camera.setForeground(false)
        finishUnconfirmedImageSend(showNotice = false)
        mic.stop(false)
        interruptReply()
        motionDirector.setForeground(false)
        foreground = false
        handler.removeCallbacksAndMessages(null)
        if (!mediaSuspended) {
            providerReady = false
            if (::ws.isInitialized) ws.close()
        }
        if (::live2D.isInitialized) { live2D.resetMotionState(); live2D.stopRender() }
        super.onPause()
    }

    override fun onDestroy() {
        directPlacement?.close(); directPlacement = null
        if (::motionDirector.isInitialized) motionDirector.setForeground(false)
        handler.removeCallbacksAndMessages(null)
        mic.close()
        speech.close()
        localSpeech?.close()
        background?.close(); background = null
        b.photoDraftThumb.setImageDrawable(null)
        draftThumbnail = null; draftThumbnailImage = null
        if (::ws.isInitialized) ws.close()
        if (::live2D.isInitialized) live2D.stopRender()
        super.onDestroy()
    }
}

/** Main-thread state independent of whether synthesis ever produced PCM. */
internal class LocalOpeningPlaybackState {
    var pending: Boolean = false
        private set

    fun queued() { pending = true }
    fun cancel() { pending = false }

    fun takeIdleCompletion(foreground: Boolean, playerBusy: Boolean,
                           routerBusy: Boolean, backendPlaybackPending: Boolean): Boolean {
        if (!pending || !foreground || playerBusy || routerBusy || backendPlaybackPending) return false
        pending = false
        return true
    }
}

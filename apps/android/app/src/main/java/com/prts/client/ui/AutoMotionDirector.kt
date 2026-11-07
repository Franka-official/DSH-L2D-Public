package com.prts.client.ui

import kotlin.random.Random
import java.util.Locale

/** Main-thread conversation choreography. No network, microphone or unbounded timer. */
internal class AutoMotionDirector(
    private val clock: () -> Long,
    private val expressions: () -> Set<String>,
    private val gestures: () -> Set<String>,
    private val showEmotionWeights: (EmotionWeights) -> Unit,
    private val clearAutoEmotion: () -> Unit,
    private val releaseManualExpression: () -> Unit,
    private val playGesture: (String) -> Boolean,
    private val report: (String) -> Unit = {},
    private val emotionReport: (String) -> Unit = {},
    private val randomBetween: (Int, Int) -> Int = { from, until -> Random.nextInt(from, until) },
    private val emotionRuntime: EmotionRuntime = EmotionRuntime(clock),
    private val onPerformanceIntent: (String, EmotionSignal?) -> Unit = { _, _ -> },
) {
    data class Cue(val expression: String? = null, val gesture: String? = null, val replyId: String? = null,
        val emotionIntent: EmotionSignal? = null)

    var enabled = true
        private set
    private var foreground = false
    private var turnActive = false
    private var turnId: String? = null
    private var turnGesturePlayed = false
    private var explicitUserGesture: String? = null
    private var lastGestureAt = -60_000L
    private var lastTouchAt = -60_000L
    private var manualUntil = 0L
    private var manualExpressionActive = false
    private var localTurnSequence = 0L
    private var localEmotionKey = "legacy-turn:0"
    private var nextEmotionLogAt = 0.0
    private var profileReported = false
    private val repliesWithMotion = linkedSetOf<String>()
    private val stagedMotions = linkedMapOf<String, String>()
    private val stagedIntents = linkedMapOf<String, EmotionSignal>()
    private var networkReplyId: String? = null
    private val retiredNetworkReplies = linkedSetOf<String>()

    fun setEnabled(value: Boolean) {
        if (!profileReported) {
            val profile = emotionRuntime.policy()
            emotionReport(String.format(Locale.ROOT,
                "profile configured reactivity=%.4f approach_rate=%.4f half_life_s=%.3f decay_rate=%.6f hold_base_s=6 hold_scale_s=%.2f confidence_min=%.2f baseline=%.4f,%.4f,%.4f ambient=%.4f step_s=0.05",
                profile.reactivity, profile.approachRate, kotlin.math.ln(2.0) / profile.decayRate,
                profile.decayRate, profile.holdScaleSeconds, EmotionRuntime.CONFIDENCE_MINIMUM,
                profile.baseline.valence, profile.baseline.arousal, profile.baseline.dominance, profile.ambientStrength))
            profileReported = true
        }
        enabled = value
        if (!value) { cancelReply(); emotionRuntime.pause(); clearAutoEmotion(); report("automatic disabled") }
        else { if (foreground) emotionRuntime.resume(); report("automatic enabled") }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) { if (enabled) emotionRuntime.resume() }
        else {
            cancelReply(); emotionRuntime.pause(); manualUntil = 0L
            if (manualExpressionActive) releaseManualExpression()
            manualExpressionActive = false
            clearAutoEmotion()
        }
    }

    /** Kept for input hooks; continuous natural motion is owned by the renderer. */
    fun userInteraction() = Unit

    fun onCharacterTouch(region: String): Boolean {
        val now = clock()
        if (!enabled || !foreground || now - lastTouchAt < 1_250L || now < manualUntil) return false
        val (gesture, emotion) = when (region) {
            "head" -> "call:nod" to "happy"
            "body" -> "call:sway" to "surprised"
            else -> return false
        }
        val animated = !turnActive && play(gesture, "character-touch")
        val expressed = "mobile:$emotion" in expressions() && emotionRuntime.presentTouch(emotion)
        if (expressed) runCatching {
            onPerformanceIntent(emotion, EmotionSignal(emotion = emotion, intensity = .58, confidence = 1.0, source = "local-touch"))
        }
        if (animated || expressed) lastTouchAt = now
        return animated || expressed
    }

    fun manualInteraction(manualExpression: Boolean = false) {
        manualUntil = clock() + 15_000L
        manualExpressionActive = manualExpressionActive || manualExpression
        turnGesturePlayed = true
        rememberMotion()
        explicitUserGesture = null
        emotionRuntime.cancelReaction()
    }

    fun onUserInput(text: String) {
        retireNetworkReply()
        turnActive = true
        turnId = null
        localEmotionKey = "legacy-turn:${++localTurnSequence}"
        turnGesturePlayed = false
        explicitUserGesture = requestedGesture(text)
        // A direct request is more specific than a mood-based flourish.
        if (enabled && foreground && explicitUserGesture != null) {
            performExplicitRequest()
        }
    }

    fun beginReply(replyId: String? = null) {
        val id = replyId?.takeIf { it.isNotBlank() }?.take(160)
        if (!turnActive || (id != null && turnId != null && id != turnId)) {
            retireNetworkReply()
            turnActive = true
            turnGesturePlayed = false
            explicitUserGesture = null
            localEmotionKey = "legacy-turn:${++localTurnSequence}"
        }
        if (id != null) turnId = id
        if (id != null && id !in retiredNetworkReplies) networkReplyId = id
        if (id != null && id in repliesWithMotion) turnGesturePlayed = true
        if (turnGesturePlayed) rememberMotion()
    }

    fun onSpeech(text: String, cue: Cue = Cue()) {
        beginReply(cue.replyId)
        if (!enabled || !foreground) return
        val expression = cue.expression?.takeIf { it.isNotBlank() }?.let(::normalExpression)
            ?: inferredExpression(text)
        val emotionKey = cue.replyId?.takeIf { it.isNotBlank() }?.take(160) ?: turnId ?: localEmotionKey
        val signal = cue.emotionIntent ?: stagedIntents[emotionKey]
        applyReplyEmotion(emotionKey, signal, expression)
        // Apply the current reply's expression at playback; later slices do not nudge the mood again.
        emotionRuntime.presentSpeech(emotionKey, signal, expression)
        if (clock() < manualUntil) return
        if (explicitUserGesture != null) {
            performExplicitRequest()
            return
        }
        if (turnGesturePlayed) return
        val structured = cue.gesture?.takeIf { it.isNotBlank() } ?: cue.replyId?.let { stagedMotions[it] }
        val gesture = if (structured != null) normalGesture(structured)
            else learnedGesture(signal) ?: inferredGesture(text, expression)
        if (structured != null && (gesture == null || gesture !in gestures())) {
            // An unsupported explicit motion must not turn into an unrelated gesture.
            turnGesturePlayed = true
            rememberMotion()
            report("reply motion unavailable")
            return
        }
        if (gesture != null && (structured != null || clock() - lastGestureAt >= 8_000L) &&
            play(gesture, if (structured != null) "reply-metadata" else "reply")) {
            turnGesturePlayed = true
            rememberMotion()
        }
    }

    private fun applyReplyEmotion(emotionKey: String, signal: EmotionSignal?, expression: String?): Boolean {
        val accepted = emotionRuntime.acceptReply(emotionKey, signal, expression) ?: return false
            val frame = emotionRuntime.frame()
            val safeId = emotionKey.replace(Regex("[^A-Za-z0-9:._-]"), "_")
            emotionReport(String.format(Locale.ROOT,
                "intent accepted id=%s source=%s analysis_ms=%s emotion=%s confidence=%s intensity=%s nudge_count=%d current=%.4f,%.4f,%.4f target=%.4f,%.4f,%.4f hold=%.2f",
                safeId, emotionRuntime.lastSource, emotionRuntime.lastAnalysisMs, accepted,
                signal?.confidence, signal?.intensity, emotionRuntime.nudgeCount,
                frame.state.current.valence, frame.state.current.arousal, frame.state.current.dominance,
                frame.state.target.valence, frame.state.target.arousal, frame.state.target.dominance, frame.state.holdSeconds))
        runCatching { onPerformanceIntent(accepted, signal) }
        return true
    }

    /** Metadata can arrive while local speech is still synthesizing an earlier slice. */
    fun stageCue(cue: Cue) {
        val id = cue.replyId?.takeIf { it.isNotBlank() && it.length <= 160 } ?: return
        if (id in retiredNetworkReplies) return
        if (turnActive && networkReplyId == null && id !in retiredNetworkReplies) networkReplyId = id
        cue.gesture?.takeIf { it.isNotBlank() }?.let { motion ->
            if (id !in stagedMotions) stagedMotions[id] = motion.take(80)
        }
        cue.emotionIntent?.let { if (id !in stagedIntents) stagedIntents[id] = it }
        while (stagedMotions.size > 32) stagedMotions.remove(stagedMotions.keys.first())
        while (stagedIntents.size > 32) stagedIntents.remove(stagedIntents.keys.first())
    }

    /** A late classifier result is metadata for the current already-seen reply, never speech. */
    fun onEmotionUpdate(replyId: String, signal: EmotionSignal): Boolean {
        if (!enabled || !foreground || !turnActive || replyId.isBlank() || replyId.length > 160
            || replyId in retiredNetworkReplies) return false
        // A fast independent classifier can finish before its audio envelope.
        // Stage it until the envelope confirms the current reply identity.
        if (networkReplyId == null) {
            if (replyId !in stagedIntents) stagedIntents[replyId] = signal
            while (stagedIntents.size > 32) stagedIntents.remove(stagedIntents.keys.first())
            return false
        }
        if (replyId != networkReplyId) return false
        if (replyId !in stagedIntents) stagedIntents[replyId] = signal
        if (replyId != turnId) return false
        val accepted = applyReplyEmotion(replyId, signal, null)
        // Explicit audio-envelope or user commands always beat a learned cue.
        if (clock() < manualUntil || explicitUserGesture != null || turnGesturePlayed || replyId in stagedMotions) return accepted
        val gesture = learnedGesture(signal) ?: return accepted
        if (play(gesture, "trained-intent:${signal.intent}")) {
            turnGesturePlayed = true
            rememberMotion()
            return true
        }
        return accepted
    }

    private fun retireNetworkReply() {
        networkReplyId?.let { retiredNetworkReplies.add(it) }
        turnId?.let { retiredNetworkReplies.add(it) }
        networkReplyId = null
        while (retiredNetworkReplies.size > 64) retiredNetworkReplies.remove(retiredNetworkReplies.first())
    }

    fun endReply() {
        retireNetworkReply()
        turnActive = false
        turnId = null
        explicitUserGesture = null
    }

    fun cancelReply() {
        retireNetworkReply()
        turnActive = false
        turnId = null
        turnGesturePlayed = false
        explicitUserGesture = null
        emotionRuntime.cancelReaction()
    }

    /** Ten-Hz publication; the GL thread owns its short anti-staircase filter. */
    fun updateEmotionFrame(manualUiOpen: Boolean = false) {
        if (!enabled || !foreground) return
        if (manualExpressionActive && clock() >= manualUntil && !manualUiOpen) {
            releaseManualExpression()
            manualExpressionActive = false
        }
        val frame = emotionRuntime.frame()
        if (!manualUiOpen) showEmotionWeights(frame.weights)
        if (frame.activeSeconds >= nextEmotionLogAt) {
            nextEmotionLogAt = frame.activeSeconds + 5
            emotionReport(String.format(Locale.ROOT,
                "state active_s=%.1f current=%.4f,%.4f,%.4f target=%.4f,%.4f,%.4f hold=%.1f mood=%s weights=%.4f,%.4f,%.4f,%.4f,%.4f",
                frame.activeSeconds, frame.state.current.valence, frame.state.current.arousal, frame.state.current.dominance,
                frame.state.target.valence, frame.state.target.arousal, frame.state.target.dominance, frame.state.holdSeconds, frame.state.dominantEmotion,
                frame.weights.happy, frame.weights.sad, frame.weights.angry, frame.weights.surprised, frame.weights.shy))
        }
    }

    fun emotionSnapshot(): EmotionFrame = emotionRuntime.frame()
    val emotionNudgeCount: Long get() = emotionRuntime.nudgeCount

    /** Compatibility hook only: no idle/waiting timer schedules large gestures.
     * The native render loop continuously blends mouth and mood into natural
     * motion; this director only issues semantically justified gesture cues. */
    @Suppress("UNUSED_PARAMETER")
    fun tick(busy: Boolean, waitingForReply: Boolean = false, speaking: Boolean = false) = Unit

    private fun performExplicitRequest() {
        if (turnGesturePlayed) return
        val wanted = explicitUserGesture ?: return
        if (wanted in gestures()) {
            if (play(wanted, "user-request")) { turnGesturePlayed = true; rememberMotion() }
        } else {
            turnGesturePlayed = true
            rememberMotion()
            report("requested motion unavailable")
        }
    }

    private fun play(id: String, source: String): Boolean {
        if (id !in gestures() || !playGesture(id)) return false
        lastGestureAt = clock()
        report("motion queued source=$source id=$id")
        return true
    }

    private fun rememberMotion() {
        turnId?.let { repliesWithMotion.add(it) }
        while (repliesWithMotion.size > 32) repliesWithMotion.remove(repliesWithMotion.first())
    }

    private fun normalExpression(value: String): String? {
        val text = value.trim().lowercase().removePrefix("mobile:")
        val id = when (text) {
            "neutral", "natural", "normal", "自然", "中性" -> "mobile:neutral"
            "happy", "joy", "开心", "高兴" -> "mobile:happy"
            "sad", "难过", "伤心" -> "mobile:sad"
            "angry", "生气", "愤怒" -> "mobile:angry"
            "surprised", "surprise", "惊讶" -> "mobile:surprised"
            "shy", "blush", "害羞", "脸红" -> "mobile:shy"
            "thinking", "思考" -> "mobile:neutral"
            else -> return value.trim().takeIf { it in expressions() }
        }
        return id.takeIf { it in expressions() }
    }

    private fun normalGesture(value: String): String? {
        val text = value.trim().lowercase().removePrefix("call:").replace('_', '-')
        return when (text) {
            "nod", "点头" -> "call:nod"
            "shake", "摇头" -> "call:shake"
            "bow", "欠身", "鞠躬" -> "call:bow"
            "sway", "摇摆", "轻摆" -> "call:sway"
            "raise-hand", "raise", "hand-up", "举手", "抬手" -> "call:raise-hand"
            "lower-hand", "lower", "hand-down", "放下", "放下手" -> "call:lower-hand"
            "none", "neutral", "idle" -> null
            else -> null
        }
    }

    /** One generic learned intent, routed only through this model's capabilities.
     * Engineering thresholds are fixed before held-out evaluation; scores are
     * not calibrated accuracy. The regression is not a direct joint controller. */
    private fun learnedGesture(signal: EmotionSignal?): String? {
        if (signal == null || signal.source?.contains("trained_general_head_v1") != true) return null
        val confidence = signal.intentConfidence?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
        val intensity = signal.gestureIntensity?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
        if (confidence < .65 || intensity < .20) return null
        val semantic = when (signal.intent) {
            "greet" -> "call:greet"
            "acknowledge", "emphasize" -> "call:nod"
            "disagree" -> "call:shake"
            "reflect", "comfort", "celebrate" -> "call:sway"
            "apologize" -> "call:bow"
            else -> return null
        }
        // Reject an inconsistent/unreviewed command instead of silently
        // executing an arbitrary string from metadata.
        if (signal.gesture != semantic) return null
        val supported = gestures()
        val selected = if (semantic == "call:greet") {
            if ("call:raise-hand" in supported) "call:raise-hand" else "call:nod"
        } else semantic
        return selected.takeIf { it in supported }
    }

    private fun requestedGesture(text: String): String? {
        val clipped = text.take(300).lowercase()
        if (Regex("(?:别|不要|不用|不许|别再).{0,5}(?:放下(?:你(?:的)?)?手|把手放下|手放下|垂下手|放下来|放下)").containsMatchIn(clipped) ||
            Regex("(?:don't|do not|don’t) (?:lower|put down) (?:your )?hand").containsMatchIn(clipped))
            return "none"
        val mentionsGesture = Regex("(?:举手|抬手|放下|点头|摇头|欠身|鞠躬|raise.{0,8}hand|lower.{0,8}hand)").containsMatchIn(clipped)
        if (mentionsGesture && (Regex("(?:什么意思|什么含义|含义是什么|指什么|代表什么|表示什么|定义|这个词|怎么解释|怎么实现|如何实现|原理|翻译|英文|什么是|什么叫)").containsMatchIn(clipped) ||
                Regex("(?:解释|说明|支持|有没有|具备).{0,12}(?:举手|抬手|放下|点头|摇头|欠身|鞠躬)").containsMatchIn(clipped)))
            return "none"
        if (Regex("(?:我(?:刚才|已经|正在)|他|她|有人).{0,4}(?:举手|抬手|点头|摇头)").containsMatchIn(clipped))
            return null
        if (clipped.trim().trimEnd('。', '！', '!', '？', '?') in setOf("放下", "放下吧", "放下来") ||
            Regex("(?:放下(?:你(?:的)?)?手|把手放下|手放下|垂下手|lower (?:your )?hand|put your hand down)").containsMatchIn(clipped))
            return "call:lower-hand"
        if (Regex("(?:不要|别|不用|别再).{0,5}(?:举手|抬手)").containsMatchIn(clipped))
            return "call:lower-hand"
        if (Regex("(?:不要|别|不用|别再|不许).{0,5}(?:点头|摇头|鞠躬|欠身)").containsMatchIn(clipped))
            return "none"
        if (Regex("(?:举(?:起)?(?:你(?:的)?)?手|抬(?:起)?(?:你(?:的)?)?手|raise (?:your )?hand)").containsMatchIn(clipped))
            return "call:raise-hand"
        if (Regex("(?:点(?:个|下|一下)?头|nod)").containsMatchIn(clipped)) return "call:nod"
        if (Regex("(?:摇(?:个|下|一下)?头|shake your head)").containsMatchIn(clipped)) return "call:shake"
        if (Regex("(?:鞠(?:个|一)?躬|欠(?:个|下)?身)").containsMatchIn(clipped)) return "call:bow"
        return null
    }

    private fun inferredExpression(text: String): String? {
        val value = text.take(400)
        val mood = when {
            Regex("(?:害羞|脸红|不好意思|别夸|照れる|恥ずかし)").containsMatchIn(value) -> "mobile:shy"
            Regex("(?:生气|气死|可恶|烦死|怒った|むかつく)").containsMatchIn(value) -> "mobile:angry"
            Regex("(?:难过|伤心|好可惜|好遗憾|悲しい|寂しい)").containsMatchIn(value) -> "mobile:sad"
            Regex("(?:居然|竟然|真的吗|没想到|びっくり|まさか)").containsMatchIn(value) -> "mobile:surprised"
            Regex("(?:哈哈|嘿嘿|开心|太好了|好耶|嬉しい|よかった)").containsMatchIn(value) -> "mobile:happy"
            else -> null
        }
        return mood?.takeIf { it in expressions() }
    }

    private fun inferredGesture(text: String, expression: String?): String? {
        val value = text.take(220)
        return when {
            Regex("(?:谢谢|多谢|感谢|ありがとう)").containsMatchIn(value) -> "call:bow"
            Regex("^(?:不行|不可以|不是|不要|没有|いいえ)").containsMatchIn(value.trim()) -> "call:shake"
            Regex("^(?:好[的呀啊吧]|嗯|对[的呀啊]|明白|知道了|是[的呀啊]|うん|はい)").containsMatchIn(value.trim()) -> "call:nod"
            expression in setOf("mobile:happy", "mobile:shy") -> "call:sway"
            else -> null
        }?.takeIf { it in gestures() }
    }
}

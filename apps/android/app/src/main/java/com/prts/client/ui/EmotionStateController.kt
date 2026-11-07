// SPDX-License-Identifier: Apache-2.0
// Adapted core from nanlingyin/soullink-emotion-sdk, revision
// 02cd75ac0a085a87d84d1a885f1b33eca8742d53:
// EmotionStateController.ts, EmotionPresetRegistry.ts, VADState.ts and utility functions.
// Source: https://github.com/nanlingyin/soullink-emotion-sdk
// Android additions: slower personality, fixed-step active clock, reply deduplication,
// confidence policy, bounded reaction pulses and safe five-weight presentation.
// This is an adapted Kotlin core, not the complete upstream SDK.
// Full license and attribution: APK assets/licenses/soullink-emotion-sdk-*.txt.
package com.prts.client.ui

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

internal data class VADVector(val valence: Double = 0.0, val arousal: Double = 0.0, val dominance: Double = 0.0) {
    fun clamped() = VADVector(axis(valence), axis(arousal), axis(dominance))
    companion object { private fun axis(value: Double) = if (value.isFinite()) value.coerceIn(-1.0, 1.0) else 0.0 }
}

internal data class PartialVAD(val valence: Double? = null, val arousal: Double? = null, val dominance: Double? = null) {
    fun complete(fallback: VADVector) = VADVector(
        valence?.takeIf { it.isFinite() } ?: fallback.valence,
        arousal?.takeIf { it.isFinite() } ?: fallback.arousal,
        dominance?.takeIf { it.isFinite() } ?: fallback.dominance,
    ).clamped()
}

internal data class EmotionPersonality(
    val baseline: VADVector? = null,
    val reactivity: Double? = null,
    val targetApproachRate: Double? = null,
    val decayRate: Double? = null,
    val emotionHoldSeconds: Double? = null,
    val emotionBias: Map<String, Double>? = null,
    val ambientDriftStrength: Double? = null,
)

internal data class CoreEmotionIntent(
    val emotion: String, val intensity: Double, val variant: String? = null,
    val naturalEmotion: String? = null, val naturalVariant: String? = null, val naturalVAD: PartialVAD? = null,
)

internal data class VADRuntimeState(
    val current: VADVector, val target: VADVector, val dominantEmotion: String,
    val intensity: Double, val ambient: VADVector, val holdSeconds: Double, val decayRate: Double,
)

internal data class EmotionPolicy(val baseline: VADVector, val reactivity: Double, val approachRate: Double,
    val decayRate: Double, val holdScaleSeconds: Double, val ambientStrength: Double)

internal object EmotionPresets {
    val neutral = VADVector()
    val values = linkedMapOf(
        "neutral" to neutral,
        "calm" to VADVector(.25, -.45, .2),
        "happy" to VADVector(.75, .45, .35),
        "excited" to VADVector(.85, .85, .45),
        "shy" to VADVector(.35, .6, -.45),
        "affectionate" to VADVector(.65, .1, .1),
        "curious" to VADVector(.35, .55, .2),
        "confused" to VADVector(-.1, .35, -.3),
        "tired" to VADVector(-.25, -.7, -.3),
        "sad" to VADVector(-.65, -.45, -.5),
        "anxiety" to VADVector(-.6, .7, -.55),
        "anger" to VADVector(-.7, .75, .55),
        "angry" to VADVector(-.7, .75, .55),
        "concerned" to VADVector(-.18, .28, -.2),
        "surprised" to VADVector(.18, .78, -.08),
    )
    fun get(emotion: String, variant: String? = null): VADVector = when {
        variant?.contains("shy") == true -> values.getValue("shy")
        variant?.contains("comfort") == true -> values.getValue(if (emotion == "concerned") "concerned" else "affectionate")
        variant?.contains("startled") == true -> values.getValue("surprised")
        else -> values[emotion] ?: neutral
    }
    fun magnitude(v: VADVector) = ((abs(v.valence) + abs(v.arousal) * .82 + abs(v.dominance) * .64) / 2.46).coerceIn(0.0, 1.0)
    fun nearest(v: VADVector): String {
        var best = "neutral"
        var distance = Double.POSITIVE_INFINITY
        for ((name, preset) in values) {
            if (name == "neutral" || name == "angry") continue
            val dv = v.valence - preset.valence; val da = v.arousal - preset.arousal; val dd = v.dominance - preset.dominance
            val next = dv * dv * 1.08 + da * da * .88 + dd * dd * 1.28
            if (next < distance) { distance = next; best = name }
        }
        return if (distance < .92) best else "neutral"
    }
}

/** Upstream state equations retained; mobile timing/policy is in EmotionRuntime below. */
internal class EmotionStateController(personality: EmotionPersonality = EmotionPersonality()) {
    private var current = EmotionPresets.neutral
    private var target = EmotionPresets.neutral
    private var baseline = EmotionPresets.neutral
    private var ambientDrift = EmotionPresets.neutral
    private var ambientTarget = VADVector(.018, -.012, .01)
    private var ambientDriftStrength = .034
    private var driftClock = 0.0
    private var nextDriftAt = 0.0
    private var randomValue = 9137L
    private var reactivity = 1.0
    private var targetApproachRate = 1.35
    private var decayRate = .018
    private var emotionHoldSeconds = 18.0
    private var holdRemainingSeconds = 0.0
    private var emotionBias = emptyMap<String, Double>()
    private var dominantEmotion = "neutral"

    init { configure(personality); reset() }

    fun policy() = EmotionPolicy(baseline, reactivity, targetApproachRate, decayRate, emotionHoldSeconds, ambientDriftStrength)

    fun configure(personality: EmotionPersonality) {
        personality.baseline?.let { baseline = it.clamped() }
        personality.reactivity?.takeIf { it.isFinite() }?.let { reactivity = it.coerceIn(.2, 2.5) }
        personality.targetApproachRate?.takeIf { it.isFinite() }?.let { targetApproachRate = it.coerceIn(.2, 4.0) }
        personality.decayRate?.takeIf { it.isFinite() }?.let { decayRate = it.coerceIn(.002, .4) }
        personality.emotionHoldSeconds?.takeIf { it.isFinite() }?.let { emotionHoldSeconds = it.coerceIn(0.0, 90.0) }
        personality.emotionBias?.let { emotionBias = emotionBias + it.filterValues { value -> value.isFinite() } }
        personality.ambientDriftStrength?.takeIf { it.isFinite() }?.let { ambientDriftStrength = it.coerceIn(0.0, .09) }
    }

    fun nudge(intent: CoreEmotionIntent) {
        val naturalEmotion = intent.naturalEmotion ?: intent.emotion
        val naturalVariant = intent.naturalVariant ?: intent.variant
        val fallback = EmotionPresets.get(naturalEmotion, naturalVariant)
        val preset = intent.naturalVAD?.complete(fallback) ?: fallback
        val bias = emotionBias[naturalEmotion] ?: naturalVariant?.let { emotionBias[it] } ?: 1.0
        val intensity = intent.intensity.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val amount = ((.28 + intensity * .58) * reactivity * bias).coerceIn(0.0, .96)
        target = lerpVAD(target, preset, amount)
        extendHold(6 + intensity * emotionHoldSeconds)
        dominantEmotion = if (naturalVariant?.contains("shy") == true) "shy" else naturalEmotion
    }

    fun blendTo(value: PartialVAD, amount: Double = .65) {
        val gain = amount.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        target = lerpVAD(target, value.complete(target), gain)
        extendHold(4 + gain * emotionHoldSeconds)
    }

    fun nudgeVAD(delta: PartialVAD, amount: Double = 1.0) {
        val safe = amount.takeIf { it.isFinite() } ?: 0.0
        val gain = (safe * reactivity).coerceIn(0.0, 2.0)
        target = VADVector(target.valence + (delta.valence?.takeIf { it.isFinite() } ?: 0.0) * gain,
            target.arousal + (delta.arousal?.takeIf { it.isFinite() } ?: 0.0) * gain,
            target.dominance + (delta.dominance?.takeIf { it.isFinite() } ?: 0.0) * gain).clamped()
        extendHold(3 + safe.coerceIn(0.0, 1.5) * emotionHoldSeconds * .55)
    }

    fun reset() {
        current = baseline; target = baseline; ambientDrift = EmotionPresets.neutral
        ambientTarget = pickAmbientTarget(); driftClock = 0.0
        nextDriftAt = .8 + random() * 2.1
        holdRemainingSeconds = 0.0; dominantEmotion = "neutral"
    }

    fun update(deltaSeconds: Double): VADRuntimeState {
        val delta = deltaSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val approach = 1 - exp(-delta * targetApproachRate)
        val decay = if (holdRemainingSeconds > 0) 0.0 else 1 - exp(-delta * decayRate)
        updateAmbientDrift(delta)
        holdRemainingSeconds = max(0.0, holdRemainingSeconds - delta)
        current = lerpVAD(current, VADVector(target.valence + ambientDrift.valence,
            target.arousal + ambientDrift.arousal, target.dominance + ambientDrift.dominance).clamped(), approach)
        target = lerpVAD(target, baseline, decay)
        val intensity = EmotionPresets.magnitude(current)
        dominantEmotion = when {
            intensity < .0018 -> "neutral"
            intensity < .08 -> inferSubtle(current)
            else -> inferDominant(current)
        }
        return VADRuntimeState(current, target, dominantEmotion, intensity, ambientDrift, holdRemainingSeconds, decayRate)
    }

    private fun inferDominant(v: VADVector): String = when {
        v.valence > .12 && v.dominance < -.22 -> "shy"
        v.valence < -.34 && v.arousal > .38 && v.dominance < -.12 -> "anxiety"
        v.valence < -.42 && v.arousal > .42 && v.dominance > .18 -> "anger"
        v.valence > .58 && v.arousal > .62 -> "excited"
        v.valence > .2 && v.arousal < -.24 -> "calm"
        else -> EmotionPresets.nearest(v)
    }

    private fun inferSubtle(v: VADVector): String = when {
        v.valence > .004 && v.arousal > .004 -> "soft-happy"
        v.valence > .004 && v.arousal < -.004 -> "soft-calm"
        v.valence > .004 -> "soft-positive"
        v.valence < -.004 && v.arousal > .004 -> "soft-uneasy"
        v.valence < -.004 -> "soft-low"
        v.arousal > .004 -> "soft-curious"
        v.arousal < -.004 -> "soft-calm"
        v.dominance < -.004 -> "soft-shy"
        v.dominance > .004 -> "soft-steady"
        else -> "neutral"
    }

    private fun updateAmbientDrift(delta: Double) {
        if (ambientDriftStrength <= 0) return
        driftClock += delta
        if (driftClock >= nextDriftAt) {
            ambientTarget = pickAmbientTarget()
            nextDriftAt = driftClock + 1.7 + random() * 4.2
        }
        ambientDrift = lerpVAD(ambientDrift, ambientTarget, 1 - exp(-delta * .62))
    }

    private fun random(): Double {
        randomValue = (randomValue * 9301 + 49297) % 233280
        return randomValue.toDouble() / 233280
    }

    private fun pickAmbientTarget(): VADVector {
        val centerBias = if (random() < .26) .42 else 1.0
        fun pick(axisScale: Double): Double {
            val half = ambientDriftStrength * axisScale * centerBias
            return -half + random() * half * 2
        }
        return VADVector(pick(1.0), pick(.82), pick(.68)).clamped()
    }

    private fun extendHold(duration: Double) { holdRemainingSeconds = max(holdRemainingSeconds, duration) }
    private fun lerpVAD(from: VADVector, to: VADVector, amount: Double) = VADVector(
        from.valence + (to.valence - from.valence) * amount,
        from.arousal + (to.arousal - from.arousal) * amount,
        from.dominance + (to.dominance - from.dominance) * amount,
    ).clamped()
}

/** Optional wire fields are not interpreted as a forced neutral command. */
internal data class EmotionSignal(
    val emotion: String? = null, val intensity: Double? = null,
    val valence: Double? = null, val arousal: Double? = null, val dominance: Double? = null,
    val confidence: Double? = null, val source: String? = null, val analysisMs: Double? = null,
    val intent: String? = null, val intentConfidence: Double? = null,
    val gesture: String? = null, val gestureIntensity: Double? = null,
)

internal data class EmotionWeights(val happy: Double = 0.0, val sad: Double = 0.0, val angry: Double = 0.0,
    val surprised: Double = 0.0, val shy: Double = 0.0) {
    fun values() = listOf(happy, sad, angry, surprised, shy)
    fun normalized(): EmotionWeights {
        val safe = values().map { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.0 }
        val gain = max(1.0, safe.sum())
        return EmotionWeights(safe[0] / gain, safe[1] / gain, safe[2] / gain, safe[3] / gain, safe[4] / gain)
    }
    fun pulse(other: EmotionWeights, amount: Double): EmotionWeights = EmotionWeights(
        happy + (1 - happy) * other.happy * amount, sad + (1 - sad) * other.sad * amount,
        angry + (1 - angry) * other.angry * amount, surprised + (1 - surprised) * other.surprised * amount,
        shy + (1 - shy) * other.shy * amount,
    ).normalized()
}

internal data class EmotionFrame(val state: VADRuntimeState, val weights: EmotionWeights, val activeSeconds: Double)

/** Mobile lifecycle, clock and five safe muscle targets, not a general FACS implementation. */
internal class EmotionRuntime(private val clock: () -> Long,
    personality: EmotionPersonality = EmotionPersonality(reactivity = .52, targetApproachRate = .25,
        decayRate = kotlin.math.ln(2.0) / 20, emotionHoldSeconds = 18.0)) {
    private val core = EmotionStateController(personality)
    private var state = core.update(0.0)
    private var lastWall = clock()
    private var activeMillis = 0L
    private var pendingMillis = 0L
    private var paused = false
    private data class Reaction(val started: Long, val weights: EmotionWeights, val peak: Double)
    private var reaction: Reaction? = null
    private val appliedReplies = linkedSetOf<String>()
    private val reactedReplies = linkedSetOf<String>()
    private val speechPresentedReplies = linkedSetOf<String>()
    var nudgeCount = 0L
        private set
    var lastSource: String? = null
        private set
    var lastAnalysisMs: Double? = null
        private set

    fun policy() = core.policy()

    fun pause() { advance(); paused = true; reaction = null }
    fun resume() { lastWall = clock(); paused = false }
    fun cancelReaction() { advance(); reaction = null }

    /** A local touch can change the current pose, without accumulating conversational mood. */
    fun presentTouch(expression: String): Boolean {
        advance()
        if (paused) return false
        val emotion = normalizeEmotion(expression) ?: return false
        reaction = Reaction(activeMillis, weightsForEmotion(emotion), .56)
        return true
    }

    fun acceptReply(replyId: String, signal: EmotionSignal?, fallbackEmotion: String?): String? {
        advance()
        val key = replyId.take(160)
        if (paused || key.isBlank() || key in appliedReplies) return null
        if (signal == null) {
            // Missing classification is not a new mood. Legacy tags may only make
            // one short presentation reaction; they cannot accumulate long state.
            val fallback = normalizeEmotion(fallbackEmotion)
            if (fallback != null && fallback != "neutral" && key !in reactedReplies) {
                reaction = Reaction(activeMillis, weightsForEmotion(fallback), .25)
                rememberReaction(key)
            }
            return null
        }
        val suppliedVAD = listOf(signal.valence, signal.arousal, signal.dominance).any { it?.isFinite() == true }
        val emotion = normalizeEmotion(signal.emotion ?: fallbackEmotion)
            ?: if (suppliedVAD) EmotionPresets.nearest(PartialVAD(signal.valence, signal.arousal, signal.dominance).complete(EmotionPresets.neutral)) else return null
        val confidence = signal.confidence?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: return null
        if (confidence < CONFIDENCE_MINIMUM) {
            // A weak classifier/rule is a brief rendering hint, never a mood
            // update. Do not discard the whole hint or accumulate low scores.
            presentWeakReply(key, emotion, confidence)
            return null
        }
        val intensity = (signal.intensity?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: .35) * confidence
        if (intensity <= .001 || emotion == "neutral" && !suppliedVAD) return null
        val vad = if (suppliedVAD) PartialVAD(signal.valence, signal.arousal, signal.dominance) else null
        if (emotion == "neutral" && EmotionPresets.magnitude(vad?.complete(EmotionPresets.neutral) ?: EmotionPresets.neutral) < .015) return null
        core.nudge(CoreEmotionIntent(emotion, intensity, naturalVAD = vad))
        state = core.update(0.0)
        appliedReplies.add(key)
        while (appliedReplies.size > 64) appliedReplies.remove(appliedReplies.first())
        nudgeCount++
        lastSource = signal.source?.take(60)?.replace(Regex("[^A-Za-z0-9_.:-]"), "_") ?: "structured-intent"
        lastAnalysisMs = signal.analysisMs?.takeIf { it.isFinite() && it >= 0 }
        if (key !in reactedReplies) {
            reaction = Reaction(activeMillis, weightsForEmotion(emotion), .40 + intensity * .32)
            rememberReaction(key)
        }
        return emotion
    }

    /** At most one visible reaction at playback; slicing cannot extend it. */
    fun presentSpeech(replyId: String, signal: EmotionSignal?, fallbackEmotion: String?): Boolean {
        advance()
        val key = replyId.take(160)
        if (paused || key.isBlank() || key in speechPresentedReplies) return false
        val emotion = normalizeEmotion(fallbackEmotion) ?: normalizeEmotion(signal?.emotion) ?: return false
        if (emotion == "neutral") return false
        val confidence = signal?.confidence?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
        val intensity = signal?.intensity?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: .35
        val peak = when {
            confidence != null && confidence >= CONFIDENCE_MINIMUM -> .40 + intensity * confidence * .32
            fallbackEmotion != null -> .30
            confidence != null && confidence >= .15 -> .12 + confidence * .16
            else -> return false
        }
        reaction = Reaction(activeMillis, weightsForEmotion(emotion), peak)
        speechPresentedReplies.add(key)
        while (speechPresentedReplies.size > 64) speechPresentedReplies.remove(speechPresentedReplies.first())
        return true
    }

    private fun presentWeakReply(key: String, emotion: String, confidence: Double) {
        if (emotion == "neutral" || confidence < .15 || key in reactedReplies) return
        reaction = Reaction(activeMillis, weightsForEmotion(emotion), .12 + confidence * .16)
        rememberReaction(key)
    }

    private fun rememberReaction(key: String) {
        reactedReplies.add(key)
        while (reactedReplies.size > 64) reactedReplies.remove(reactedReplies.first())
    }

    fun idleMicroReaction(): Boolean {
        advance()
        if (paused || reaction?.let { activeMillis - it.started < 6000 } == true) return false
        reaction = Reaction(activeMillis, weightsForEmotion(state.dominantEmotion).let {
            if (it.values().sum() < .001) EmotionWeights(happy = 1.0) else it
        }, IDLE_REACTION_PEAK)
        return true
    }

    fun frame(): EmotionFrame {
        advance()
        val base = weightsForVAD(state.current)
        val pulse = reaction?.let { short ->
            val elapsed = (activeMillis - short.started) / 1000.0
            if (elapsed >= 6) { reaction = null; 0.0 } else short.peak * envelope(elapsed)
        } ?: 0.0
        return EmotionFrame(state, if (reaction != null) base.pulse(reaction!!.weights, pulse) else base, activeMillis / 1000.0)
    }

    private fun advance() {
        val wall = clock()
        val delta = (wall - lastWall).coerceAtLeast(0)
        lastWall = maxOf(lastWall, wall)
        if (paused) return
        activeMillis += delta; pendingMillis += delta
        // Deterministic 20 Hz integration independent of caller/render frequency.
        while (pendingMillis >= 50) { state = core.update(.05); pendingMillis -= 50 }
    }

    private fun envelope(seconds: Double): Double = when {
        seconds < 0 -> 0.0
        seconds < 1 -> smooth(seconds)
        seconds < 2 -> 1.0
        seconds < 6 -> 1 - smooth((seconds - 2) / 4)
        else -> 0.0
    }
    private fun smooth(value: Double): Double { val u = value.coerceIn(0.0, 1.0); return u * u * u * (10 - 15 * u + 6 * u * u) }

    companion object {
        // Conservative engineering gate; classifier scores are not calibrated probabilities.
        const val CONFIDENCE_MINIMUM = .60
        const val IDLE_REACTION_PEAK = .065
        fun normalizeEmotion(value: String?): String? = when (value?.trim()?.lowercase()?.removePrefix("mobile:")) {
            "neutral", "natural", "normal", "自然", "中性" -> "neutral"
            "happy", "joy", "soft-happy", "soft-positive", "开心", "高兴" -> "happy"
            "sad", "soft-low", "难过", "伤心" -> "sad"
            "anger", "angry", "生气", "愤怒" -> "anger"
            "surprised", "surprise", "startled", "惊讶" -> "surprised"
            "shy", "blush", "soft-shy", "害羞", "脸红" -> "shy"
            "calm", "soft-calm", "soft-steady" -> "calm"
            "anxiety", "fear", "soft-uneasy" -> "anxiety"
            "curious", "soft-curious", "thinking", "思考" -> "curious"
            "confused", "tired", "affectionate", "concerned", "excited" -> value?.trim()?.lowercase()?.removePrefix("mobile:")
            else -> null
        }
        private fun weightsForEmotion(value: String): EmotionWeights = when (normalizeEmotion(value)) {
            "happy", "excited", "affectionate", "calm" -> EmotionWeights(happy = 1.0)
            "sad", "tired", "concerned" -> EmotionWeights(sad = 1.0)
            "anger" -> EmotionWeights(angry = 1.0)
            "surprised", "curious", "confused" -> EmotionWeights(surprised = 1.0)
            "shy" -> EmotionWeights(shy = 1.0)
            "anxiety" -> EmotionWeights(sad = .5, surprised = .5)
            else -> EmotionWeights()
        }
        private fun weightsForVAD(v: VADVector): EmotionWeights {
            val positive = max(0.0, v.valence); val negative = max(0.0, -v.valence)
            val high = max(0.0, v.arousal); val low = max(0.0, -v.arousal)
            val submissive = max(0.0, -v.dominance); val dominant = max(0.0, v.dominance)
            return EmotionWeights(
                happy = positive * (1 - .65 * submissive) * (.6 + .4 * high),
                sad = negative * (.55 + .45 * low) * (1 - .85 * high),
                angry = negative * high * (.25 + .75 * dominant),
                surprised = high * (1 - abs(v.valence)) * (1 - .5 * abs(v.dominance)) * .65,
                shy = positive * submissive * 1.6,
            ).normalized()
        }
    }
}

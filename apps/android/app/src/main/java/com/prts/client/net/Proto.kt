package com.prts.client.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Open-LLM-VTuber protocol (verified against backend source, Oct 2026).
 * ws://<host>:12393/client-ws
 */

@Serializable
data class OutText(val type: String = "text-input", val text: String,
    val face_observation: FaceObservationMetadata? = null, val reply_language: String = "zh",
    val bilingual_captions: Boolean = false)

/** Local, observable movement scores only; never an identity or mental-emotion claim. */
@Serializable
data class FaceObservationMetadata(
    val version: Int = 1,
    val age_ms: Long,
    val face_count: Int = 1,
    val confidence: Double? = null,
    val smile_probability: Double? = null,
    val left_eye_open_probability: Double? = null,
    val right_eye_open_probability: Double? = null,
    val mouth_open: Double? = null,
    val head_yaw_deg: Double? = null,
    val head_pitch_deg: Double? = null,
    val head_roll_deg: Double? = null,
)

@Serializable
data class OutControl(val type: String = "control", val text: String)

@Serializable
data class OutSwitchConfig(val type: String = "switch-config", val file: String)

@Serializable data class OutMicAudio(val type: String = "mic-audio-data", val audio: List<Float>)
@Serializable data class OutSignal(val type: String)
@Serializable data class OutMicEnd(val type: String = "mic-audio-end",
    val face_observation: FaceObservationMetadata? = null, val reply_language: String = "zh",
    val bilingual_captions: Boolean = false)
@Serializable data class OutInterrupt(val type: String = "interrupt-signal", val text: String = "")
@Serializable data class OutPlayStart(val type: String = "audio-play-start", val display_text: DisplayText? = null)

@Serializable
data class DisplayText(
    val text: String = "",
    val name: String? = null,
    val source: String? = null,
    val transcript: String? = null,
    val translation: String? = null,
    val translation_language: String? = null,
    val spoken_text: String? = null,
)

@Serializable
data class EmotionIntent(
    val emotion: String? = null,
    val intensity: Double? = null,
    val valence: Double? = null,
    val arousal: Double? = null,
    val dominance: Double? = null,
    val confidence: Double? = null,
    val source: String? = null,
    val analysis_ms: Double? = null,
    val intent: String? = null,
    val intent_confidence: Double? = null,
    val gesture: String? = null,
    val gesture_intensity: Double? = null,
)

@Serializable
data class InEmotionUpdate(
    val type: String = "emotion-update",
    val reply_id: String,
    val emotion_intent: EmotionIntent,
)

@Serializable
data class Actions(
    val expressions: List<JsonPrimitive>? = null,
    val motions: List<String>? = null,
    val reply_id: String? = null,
    val emotion_intent: EmotionIntent? = null,
)

@Serializable
data class InAudio(
    val type: String = "audio",
    val audio: String? = null, // base64 of a complete WAV file
    val volumes: List<Float> = emptyList(),
    val slice_length: Int = 20,
    val display_text: DisplayText? = null,
    val actions: Actions? = null,
    val forwarded: Boolean = false,
)

@Serializable
data class InFullText(val type: String = "full-text", val text: String = "")

@Serializable
data class InError(val type: String = "error", val message: String = "")

@Serializable
data class InSetModel(val type: String = "set-model-and-conf")

object Proto {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

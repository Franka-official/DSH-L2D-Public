package com.prts.client.localdsh

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** In-process frontend; HTTP connector credentials cannot submit a new user request. */
internal object LocalDshHub {
    const val PROACTIVE_PROTOCOL = "live2d.proactive-text.v1"
    private var proactiveReady = false
    private val lock = Object()
    private val main = Handler(Looper.getMainLooper())
    private val frames = java.util.ArrayDeque<JSONObject>()
    private var owner: Any? = null
    private var events: ((String) -> Unit)? = null
    private var state: ((String) -> Unit)? = null
    private var connectorAt = 0L
    private var fastHeartbeat = false
    private var connectorGeneration = 0L
    private fun connectorOnline(now: Long): Boolean =
        connectorAt != 0L && now - connectorAt < (if (fastHeartbeat) 3_500L else 45_000L)
    val connectorReady: Boolean get() = synchronized(lock) { connectorOnline(SystemClock.elapsedRealtime()) }
    private var requestId: String? = null
    private var asrId: String? = null
    private var asrReplyLanguage = "zh"
    private var asrFace: JSONObject? = null
    @Volatile var asrReady = false
        private set
    private var sessionId = UUID.randomUUID().toString()
    private var replyLanguage = "zh"
    private val reply = StringBuilder()
    private var speechSegments = LocalReplySpeechSegments("zh")
    private var speechEpoch = 0L
    private var playbackReply: String? = null
    private var presentationEmotion: String? = null
    private var presentationGesture: String? = null
    private var presentationUsed = false
    private var earlyTextReceived = false
    private var pendingFirstSpeech: String? = null
    private val allowedEmotions = setOf("neutral", "happy", "sad", "angry", "surprised", "thinking", "shy")
    private val allowedGestures = setOf("nod", "shake", "bow", "sway", "raise-hand", "lower-hand")

    private fun resetPresentation() {
        presentationEmotion = null
        presentationGesture = null
        presentationUsed = false
        earlyTextReceived = false
        pendingFirstSpeech = null
    }
    private val cancelled = linkedSetOf<String>()
    private val settled = linkedSetOf<String>()
    private var stopped = false
    private fun emit(value: JSONObject, epoch: Long? = null) {
        val target = synchronized(lock) { owner to events }
        main.post { synchronized(lock) {
            if (owner === target.first && (epoch == null || epoch == speechEpoch)) target.second?.invoke(value.toString())
        } }
    }
    private fun control(text: String) = emit(JSONObject().put("type", "control").put("text", text))

    /** Authenticated call metadata only; it never becomes reply text or an ACK. */
    private fun recordCallDiagnostics(id: String, event: JSONObject): Boolean {
        val type = event.optString("type")
        if (type != "call_options" && type != "call_metrics") return false
        // A best-effort final metric may arrive just after done. Ignore unknown or
        // cancelled turns without making a diagnostic failure cancel the call.
        if (id != requestId && id !in settled) return true
        val out = JSONObject().put("event", type).put("at_ms", SystemClock.elapsedRealtime())
            .put("reply_id", id.take(160))
        fun string(key: String, maximum: Int = 160) {
            out.put(key, (event.opt(key) as? String)?.take(maximum) ?: JSONObject.NULL)
        }
        fun number(key: String, maximum: Long = 1_000_000_000L) {
            val value = (event.opt(key) as? Number)?.toLong()?.takeIf { it in 0..maximum }
            out.put(key, value ?: JSONObject.NULL)
        }
        if (type == "call_options") {
            string("provider"); string("model")
            string("requested_effort", 32); string("effective_effort", 32); string("default_effort", 32)
            val status = event.opt("metadata_status") as? String
            out.put("metadata_status", status?.takeIf { it == "known" || it == "unknown" } ?: "unknown")
            val values = event.optJSONArray("effort_ids")
            val efforts = JSONArray()
            if (values != null) for (index in 0 until minOf(values.length(), 24)) {
                (values.opt(index) as? String)?.let { efforts.put(it.take(32)) }
            }
            out.put("effort_ids", efforts); number("turn"); number("step")
        } else {
            val count = (event.opt("thinking_chars") as? Number)?.toLong()
            if (count == null || count < 0) return true
            number("thinking_chars"); number("first_thinking_ms"); number("first_text_ms")
            number("elapsed_ms"); number("attempts"); string("source", 48)
        }
        Log.i("PRTS-CallOptions", out.toString())
        return true
    }
    private fun emitSpeech(id: String, text: String) {
        val actions = JSONObject().put("reply_id", id)
        if (!presentationUsed) {
            presentationEmotion?.let { emotion ->
                actions.put("expressions", JSONArray().put("mobile:$emotion"))
                if (emotion != "neutral" && emotion != "thinking") {
                    // This is a validated direction for the character, not a face-classifier score.
                    actions.put("emotion_intent", JSONObject().put("emotion", emotion)
                        .put("source", "dsh-go-reply").put("intensity", 0.65).put("confidence", 1.0))
                }
            }
            presentationGesture?.let { actions.put("motions", JSONArray().put("call:$it")) }
            presentationUsed = true
        }
        emit(JSONObject().put("type", "audio").put("display_text", JSONObject().put("text", text)
            .put("spoken_text", text).put("name", "DSHGo"))
            .put("actions", actions), speechEpoch)
    }
    fun attach(identity: Any, onEvent: (String) -> Unit, onState: (String) -> Unit) {
        synchronized(lock) {
            if (owner !== identity) asrFace = null
            stopped = false; owner = identity; events = onEvent; state = onState
        }
        main.post { synchronized(lock) { if (owner === identity) onState(if (connectorReady) "open" else "connecting") } }
        main.postDelayed({ watch(identity) }, 1_000L)
    }
    private fun watch(identity: Any) {
        synchronized(lock) {
            if (owner !== identity) return
            if (connectorAt != 0L && !connectorOnline(SystemClock.elapsedRealtime())) {
                connectorAt = 0L; fastHeartbeat = false; asrReady = false; proactiveReady = false
                resetConnectorQueue()
                state?.invoke("closed")
            }
        }
        main.postDelayed({ watch(identity) }, 1_000L)
    }
    fun detach(identity: Any) { synchronized(lock) { if (owner === identity) { cancel(); owner = null; events = null; state = null } } }
    fun shutdown() = synchronized(lock) {
        stopped = true; requestId = null; asrId = null; asrFace = null; asrReady = false; proactiveReady = false; reply.setLength(0); playbackReply = null; connectorAt = 0; fastHeartbeat = false
        speechSegments.cancel(); speechEpoch++
        resetPresentation()
        connectorGeneration++; frames.clear(); lock.notifyAll()
        val identity = owner; val callback = state
        main.post { synchronized(lock) { if (owner === identity) callback?.invoke("closed") } }
    }
    private fun resetConnectorQueue() {
        connectorGeneration++
        frames.clear()
        cancel()
        frames.clear()
        lock.notifyAll()
    }

    fun connectorAttached() {
        synchronized(lock) {
            val hadConnection = connectorAt != 0L
            resetConnectorQueue()
            stopped = false; fastHeartbeat = false; asrReady = false; proactiveReady = false
            connectorAt = SystemClock.elapsedRealtime()
            val generation = connectorGeneration; val identity = owner; val callback = state
            main.post {
                synchronized(lock) {
                    if (generation == connectorGeneration && owner === identity) {
                        if (hadConnection) callback?.invoke("closed")
                        callback?.invoke("open")
                    }
                }
            }
        }
    }

    fun connectorSeen(heartbeat: Boolean = false) {
        synchronized(lock) { stopped = false; val now = SystemClock.elapsedRealtime(); val wasOnline = connectorOnline(now)
            fastHeartbeat = fastHeartbeat || heartbeat
            connectorAt = now; val identity = owner; val callback = state
            if (!wasOnline) main.post { synchronized(lock) { if (owner === identity) callback?.invoke("open") } } }
    }
    private fun enqueue(frame: JSONObject) { check(frames.size < 32); frames.addLast(frame); lock.notifyAll() }
    private fun cancel() {
        listOfNotNull(requestId, asrId).forEach {
            if (cancelled.size >= 32) cancelled.remove(cancelled.first())
            cancelled.add(it)
            frames.removeAll { frame -> frame.optString("request_id") == it }
            enqueue(JSONObject().put("type", "cancel").put("request_id", it))
        }
        requestId = null; asrId = null; asrFace = null; reply.setLength(0); playbackReply = null
        speechSegments.cancel(); speechEpoch++
        resetPresentation()
    }
    private fun rememberSettled(id: String) {
        if (settled.size >= 32) settled.remove(settled.first())
        settled.add(id)
    }
    private val faceProbabilities = setOf("confidence", "smile_probability", "left_eye_open_probability",
        "right_eye_open_probability", "mouth_open")
    private val faceAngles = setOf("head_yaw_deg", "head_pitch_deg", "head_roll_deg")
    private val faceFields = faceProbabilities + faceAngles + setOf("version", "age_ms", "face_count")

    private fun normalizedFace(value: JSONObject?): JSONObject? {
        if (value == null || value.keys().asSequence().any { it !in faceFields }) return null
        fun integer(key: String): Long? = when (val item = value.opt(key)) {
            is Int -> item.toLong()
            is Long -> item
            else -> null
        }
        val age = integer("age_ms") ?: return null
        if (integer("version") != 1L || integer("face_count") != 1L || age !in 0L..3000L) return null
        val output = JSONObject().put("version", 1).put("age_ms", age).put("face_count", 1)
        var observed = false
        for (field in faceProbabilities + faceAngles) {
            val item = value.opt(field)
            if (item == null || item == JSONObject.NULL) continue
            val score = (item as? Number)?.toDouble() ?: return null
            val range = if (field in faceProbabilities) 0.0..1.0 else -90.0..90.0
            if (!score.isFinite() || score !in range) return null
            output.put(field, score)
            if (field != "confidence") observed = true
        }
        return output.takeIf { observed }
    }

    private fun enqueueConversation(text: String, language: String, face: JSONObject? = null,
                                    proactive: Boolean = false): Boolean {
        if (proactive && (!proactiveReady || !connectorOnline(SystemClock.elapsedRealtime()) ||
                text.length > 1600)) return false
        if (text.isBlank() || text.length > 16000 || requestId != null || asrId != null || frames.size >= 30) return false
        requestId = UUID.randomUUID().toString(); reply.setLength(0)
        resetPresentation()
        replyLanguage = language.takeIf { it in setOf("zh", "ja", "en") } ?: "zh"
        speechSegments = LocalReplySpeechSegments(replyLanguage); speechEpoch++
        val frame = JSONObject().put("type", if (proactive) "proactive-request" else "request")
            .put("request_id", requestId).put("session_id", sessionId)
            .put("text", text).put("reply_language", replyLanguage)
        if (!proactive && replyLanguage == "zh") frame.put("early_speech", true)
        if (!proactive && face != null) frame.put("face_observation", face)
        enqueue(frame)
        control("conversation-chain-start")
        return true
    }
    fun transcribe(identity: Any, audioBase64: String, samples: Int, language: String, face: JSONObject?,
                   stillCurrent: () -> Boolean): Boolean = synchronized(lock) {
        if (owner !== identity || !stillCurrent() || !asrReady || requestId != null || asrId != null || frames.size >= 30 ||
            samples !in 1..LocalDshPcmWav.MAX_SAMPLES || audioBase64.isBlank() || audioBase64.length > 2560060) return false
        asrId = UUID.randomUUID().toString()
        asrFace = normalizedFace(face)
        asrReplyLanguage = language.takeIf { it in setOf("zh", "ja", "en") } ?: "zh"
        enqueue(JSONObject().put("type", "transcribe").put("request_id", asrId).put("asr_request_id", asrId)
            .put("session_id", sessionId).put("audioBase64", audioBase64).put("sample_rate", 16000)
            .put("audio_seconds", samples / 16000.0).put("language", "auto").put("reply_language", asrReplyLanguage))
        true
    }
    fun fromFrontend(identity: Any, raw: String): Boolean = synchronized(lock) {
        if (owner !== identity || raw.toByteArray(Charsets.UTF_8).size > 65536) return false
        val data = JSONObject(raw)
        when (data.optString("type")) {
            "configure-mobile-provider" -> emit(JSONObject().put("type", "mobile-provider-ready")
                .put("mode", "local_dsh").put("capabilities", JSONObject().put("images", false).put("asr", asrReady)))
            "set-voice-profile" -> {
                if (data.optString("mode") != "client-local") return false
                emit(JSONObject().put("type", "voice-profile-ready").put("mode", "client-local"))
            }
            "text-input" -> {
                if (!enqueueConversation(data.optString("text").trim(), data.optString("reply_language", "zh"),
                        normalizedFace(data.optJSONObject("face_observation")))) return false
            }
            "proactive-text-input" -> {
                if (!enqueueConversation(data.optString("text").trim(), data.optString("reply_language", "zh"),
                        proactive = true)) return false
            }
            "interrupt-signal" -> { cancel(); control("interrupt") }
            "frontend-playback-complete" -> if (playbackReply != null) { playbackReply = null; control("conversation-chain-end") }
            "control" -> { cancel(); sessionId = UUID.randomUUID().toString(); control("interrupt") }
            "audio-play-start" -> Unit
            else -> return false
        }
        true
    }
    fun poll(): JSONArray = synchronized(lock) {
        connectorSeen()
        val generation = connectorGeneration
        val end = SystemClock.elapsedRealtime() + 25_000L
        while (!stopped && generation == connectorGeneration && frames.isEmpty() && SystemClock.elapsedRealtime() < end)
            lock.wait((end - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
        JSONArray().also { output ->
            if (generation == connectorGeneration) while (frames.isNotEmpty()) output.put(frames.removeFirst())
        }
    }
    fun connectorEvent(data: JSONObject): Boolean = synchronized(lock) {
        val type = data.optString("type")
        if (type == "capabilities") { asrReady = data.optBoolean("asr", false); return true }
        if (type == "proactive-capability") {
            proactiveReady = data.optString("protocol") == PROACTIVE_PROTOCOL && data.optBoolean("available")
            return true
        }
        val incomingId = data.optString("request_id")
        if (incomingId in cancelled) return true // Late events are consumed, never spoken.
        if (type == "event") {
            data.optJSONObject("event")?.let { if (recordCallDiagnostics(incomingId, it)) return true }
        }
        if (type in setOf("done", "transcription", "transcription_error") && incomingId in settled) return true
        if (incomingId == asrId && asrId != null) {
            when (type) {
                "transcription" -> {
                    val face = asrFace
                    asrFace = null; asrId = null; rememberSettled(incomingId)
                    val text = data.optString("text").trim()
                    if (text.isNotEmpty() && text.length <= 16000) {
                        emit(JSONObject().put("type", "user-input-transcription").put("text", text))
                        if (!enqueueConversation(text, asrReplyLanguage, face)) emit(JSONObject().put("type", "local-asr-error").put("message", "语音已识别，但当前回复尚未结束，请稍后再试"))
                    } else emit(JSONObject().put("type", "local-asr-error").put("message", "没有听清，请重新说一次"))
                }
                "transcription_error" -> {
                    asrFace = null; asrId = null; rememberSettled(incomingId)
                    val unavailable = data.optString("code") == "ASR_UNAVAILABLE"
                    if (unavailable) asrReady = false
                    emit(JSONObject().put("type", "local-asr-error").put("message",
                        if (unavailable) "DSHGo本机语音识别尚未就绪，请检查语音设置" else "本机语音识别失败，请重新说一次"))
                }
                "cancelled" -> { asrFace = null; asrId = null; rememberSettled(incomingId); control("interrupt") }
                else -> return false
            }
            return true
        }
        if (data.optString("request_id") != requestId || requestId == null) return false
        when (data.optString("type")) {
            "event" -> {
                val event = data.optJSONObject("event") ?: return false
                when (event.optString("type")) {
                    "status" -> emit(JSONObject().put("type", "mobile-provider-status").put("status", event.optString("status").take(64)))
                    "emotion" -> {
                        if (!presentationUsed) {
                            if (presentationEmotion == null)
                                presentationEmotion = (event.opt("emotion") as? String)?.takeIf { it in allowedEmotions }
                            if (presentationGesture == null)
                                presentationGesture = (event.opt("gesture") as? String)?.takeIf { it in allowedGestures }
                        }
                    }
                    "early_text" -> {
                        val text = event.opt("text") as? String
                        if (replyLanguage == "zh" && !earlyTextReceived && reply.isEmpty() &&
                            text != null && text.length in 2..96 && LocalReplySpeechSegments("zh").append(text) == text) {
                            earlyTextReceived = true
                            emit(JSONObject().put("type", "early-speech-preview").put("reply_id", incomingId).put("text", text), speechEpoch)
                        }
                    }
                    "early_text_cancel" -> if (earlyTextReceived) {
                        emit(JSONObject().put("type", "early-speech-cancel").put("reply_id", incomingId), speechEpoch)
                    }
                    "text" -> {
                        val text = event.optString("text")
                        if (reply.length + text.length > 16000) return false
                        reply.append(text)
                        speechSegments.append(text)?.let {
                            if (earlyTextReceived) pendingFirstSpeech = it else emitSpeech(incomingId, it)
                        }
                    }
                    else -> return false
                }
            }
            "done" -> {
                val id = requestId; requestId = null
                if (id != null) rememberSettled(id)
                if (data.optBoolean("success") && reply.isNotBlank() && id != null) {
                    pendingFirstSpeech?.let { emitSpeech(id, it) }
                    speechSegments.finish(true)?.let { emitSpeech(id, it) }
                    if (speechSegments.hasSegments) {
                        playbackReply = id
                        emit(JSONObject().put("type", "backend-synth-complete"), speechEpoch)
                    }
                } else {
                    val partialSpeech = speechSegments.hasSegments
                    speechSegments.finish(false)
                    if (partialSpeech || earlyTextReceived) { speechEpoch++; control("interrupt") }
                    emit(JSONObject().put("type", "error").put("message", "本机插件没有返回完整正文"))
                    control("conversation-chain-end")
                }
                reply.setLength(0)
                resetPresentation()
            }
            "cancelled" -> {
                requestId = null; reply.setLength(0); speechSegments.cancel(); speechEpoch++
                resetPresentation()
                control("interrupt")
            }
            else -> return false
        }
        true
    }
}

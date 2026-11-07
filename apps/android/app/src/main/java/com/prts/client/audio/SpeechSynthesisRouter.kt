package com.prts.client.audio

import android.content.Context
import com.prts.client.audio.audio8.OfflineAudio8TtsEngine
import com.prts.client.net.InAudio
import com.prts.client.net.VoiceProfile

/** Per-call route snapshot. API selection never constructs the local model engine. */
class SpeechSynthesisRouter(context: Context, profile: VoiceProfile,
    onPayload: (InAudio) -> Unit, onNotice: (String) -> Unit, onBusyChanged: () -> Unit,
    onLocalPayload: ((LocalSpeechAudio) -> Unit)? = null,
    onProgress: (LocalSpeechProgress) -> Unit = {},
    onStreamingPcm: ((InAudio, OfflineAudio8TtsEngine.PcmChunk) -> Boolean)? = null,
    onStreamingComplete: (InAudio, Result<OfflineAudio8TtsEngine.Output>) -> Unit = { _, result -> result.getOrNull()?.release() },
    mossCharacter: String? = null,
) : AutoCloseable {
    private val settings = runCatching { TtsBackendSettings.load(context) }
    val usesLocalModel: Boolean = settings.getOrNull()?.mode == TtsBackendSettings.LOCAL
    private val usesMoss = usesLocalModel && settings.getOrNull()?.localEngine == TtsBackendSettings.LOCAL_ENGINE_MOSS
    val engineId: String = when {
        usesMoss -> TtsBackendSettings.LOCAL_ENGINE_MOSS
        usesLocalModel -> LocalVoiceSettings.MODE_ID
        else -> TtsBackendSettings.API
    }
    private var failureReason: String? = settings.exceptionOrNull()?.message
    private val local = if (usesLocalModel && !usesMoss && settings.isSuccess) OfflineSpeechRouter(context, profile, onPayload,
        onNotice, onBusyChanged, onLocalPayload, onProgress, onStreamingPcm, onStreamingComplete) else null
    private val api = if (usesMoss) {
        failureReason = "请在音色管理中准备 MOSS 模型"
        ApiSpeechRouter(context, profile, null, failureReason, onPayload, onLocalPayload,
            onNotice, onBusyChanged, onProgress, moss = MossWavSynthesizer(context, profile, mossCharacter))
    } else if (!usesLocalModel || settings.isFailure) {
        val snapshot: Result<SpeechApiCredentials> = if (settings.isSuccess) runCatching { TtsBackendSettings.forCall(context) }
            else Result.failure(checkNotNull(settings.exceptionOrNull()))
        failureReason = snapshot.exceptionOrNull()?.message
        ApiSpeechRouter(context, profile, snapshot.getOrNull(), snapshot.exceptionOrNull()?.message,
            onPayload, onLocalPayload, onNotice, onBusyChanged, onProgress)
    } else null
    val isBusy: Boolean get() = local?.isBusy ?: api?.isBusy ?: false
    val available: Boolean get() = local?.available ?: api?.available ?: false
    val unavailableReason: String get() = failureReason ?: "语音API配置尚未就绪，请在设置中检查"
    fun enqueue(message: InAudio) { local?.enqueue(message) ?: api?.enqueue(message) }
    fun preparePreview(replyId: String, text: String) { local?.preparePreview(replyId, text) }
    fun cancelPreview(replyId: String) { local?.cancelPreview(replyId) }
    fun cancel() { local?.cancel(); api?.cancel() }
    override fun close() { local?.close(); api?.close() }
}

package com.prts.client.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.prts.client.net.DisplayText
import com.prts.client.net.InAudio
import com.prts.client.net.VoiceProfile
import okhttp3.Call
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One HTTP synthesis at a time; next segment can generate while the previous WAV plays. */
internal class ApiSpeechRouter(context: Context, profile: VoiceProfile,
    private val credentials: SpeechApiCredentials?, private val configurationError: String?,
    private val onPayload: (InAudio) -> Unit, private val onLocalPayload: ((LocalSpeechAudio) -> Unit)?,
    private val onNotice: (String) -> Unit, private val onBusyChanged: () -> Unit,
    private val onProgress: (LocalSpeechProgress) -> Unit,
    private val client: SpeechApiClient = SpeechApiClient(),
    private val moss: MossWavSynthesizer? = null,
) : AutoCloseable {
    private class Work(val generation: Int, val message: InAudio, val text: String, val segment: Int) {
        val cancelled = AtomicBoolean(false)
        @Volatile var call: Call? = null
    }
    private val cache = File(context.cacheDir, "tts-api").apply { mkdirs() }
    private val language = LocalVoiceSettings.outputLanguage(profile)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "PRTS-SpeechApi") }
    private val generation = AtomicInteger()
    private val closed = AtomicBoolean(false)
    private val queue = ArrayDeque<Work>()
    private var active: Work? = null
    private var failedGeneration = -1
    @Volatile private var busy = false
    val isBusy: Boolean get() = busy
    val available: Boolean get() = !closed.get() && language != null &&
        if (moss == null) credentials != null else moss.available
    private fun main(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() } }
    private fun valid(work: Work) = !closed.get() && !work.cancelled.get() && work.generation == generation.get() && active === work
    private fun changed() {
        val value = active != null || queue.isNotEmpty()
        if (value != busy) { busy = value; runCatching(onBusyChanged) }
    }

    fun enqueue(message: InAudio) {
        val ticket = generation.get()
        main {
            if (closed.get() || ticket != generation.get()) return@main
            if (!available || failedGeneration == ticket) { fail(ticket, configurationError ?: "语音API配置尚未就绪"); return@main }
            val original = message.display_text
            val plan = BilingualSpeechPolicy.select(language, original?.text, original?.transcript,
                original?.translation, original?.translation_language, original?.spoken_text)
            if (plan.missingSpeechNotice != null) { fail(ticket, plan.missingSpeechNotice); return@main }
            val text = plan.spokenText.orEmpty()
            if (text.isBlank() || !text.codePoints().anyMatch { Character.isLetterOrDigit(it) }) return@main
            // Keep ordinary replies and the fixed welcome whole. Split only a request exceeding the API bound.
            val parts = if (moss != null) LocalSpeechText.split(text, maximum = 48, firstMaximum = 48, eagerFirst = false)
                else if (text.codePointCount(0, text.length) <= 4096) listOf(text)
                else LocalSpeechText.split(text, maximum = 2000, firstMaximum = 2000, eagerFirst = false)
            parts.forEachIndexed { index, part ->
                val caption = BilingualSpeechPolicy.captionForSlice(plan, part, index)
                val display = (original ?: DisplayText()).copy(text = caption.text, transcript = caption.transcript,
                    translation = caption.translation, translation_language = caption.translationLanguage, spoken_text = caption.spokenText)
                val actions = if (index == 0) message.actions else message.actions?.copy(motions = null)
                queue.addLast(Work(ticket, message.copy(audio = null, volumes = emptyList(), display_text = display, actions = actions),
                    LocalSpeechText.forSpeech(part), index + 1))
            }
            changed(); pump()
        }
    }

    private fun pump() {
        if (closed.get() || active != null) return
        val work = queue.pollFirst() ?: run { changed(); return }
        active = work; changed()
        runCatching { onProgress(LocalSpeechProgress("synthesizing", work.segment, queue.size)) }
        worker.execute {
            var payload: LocalSpeechAudio? = null
            val result = runCatching {
                val target = File.createTempFile("speech-", ".wav", cache)
                try {
                    if (moss != null) moss.synthesize(work.text, target, work.cancelled::get)
                    else client.synthesize(checkNotNull(credentials), work.text, target, work.cancelled) { call ->
                        work.call = call
                        if (work.cancelled.get() || work.generation != generation.get() || closed.get()) call.cancel()
                    }
                    val volumes = LocalSpeechWav.levels(target.readBytes())
                    LocalSpeechAudio(work.message.copy(volumes = volumes, slice_length = 20), target) { target.delete() }
                        .also { payload = it }
                } catch (error: Exception) { target.delete(); throw error }
            }
            val posted = main.post {
                if (!valid(work)) { payload?.close(); return@post }
                result.fold(onSuccess = { audio ->
                    try {
                        if (onLocalPayload != null) onLocalPayload.invoke(audio)
                        else {
                            onPayload(audio.message.copy(audio = Base64.encodeToString(audio.wav.readBytes(), Base64.NO_WRAP)))
                            audio.close()
                        }
                        active = null; changed(); pump()
                    } catch (_: Exception) { audio.close(); fail(work.generation, "语音未能交给播放器，本轮没有重复播放") }
                }, onFailure = { error ->
                    val message = if (moss != null) "MOSS 未能生成完整语音" else "语音服务未能完成本轮音频，已停止"
                    fail(work.generation, (error as? SpeechApiClient.Failure)?.safeMessage ?: message)
                })
            }
            if (!posted) payload?.close()
        }
    }

    private fun fail(ticket: Int, message: String) {
        if (closed.get() || ticket != generation.get()) return
        val first = failedGeneration != ticket; failedGeneration = ticket
        active?.let { it.cancelled.set(true); it.call?.cancel() }; active = null
        queue.clear(); changed()
        runCatching { onProgress(LocalSpeechProgress("failed", 0, 0)) }
        if (first) runCatching { onNotice(message) }
    }
    fun cancel() {
        val ticket = generation.incrementAndGet()
        main {
            queue.removeAll { it.generation < ticket }
            active?.takeIf { it.generation < ticket }?.let { it.cancelled.set(true); it.call?.cancel(); active = null }
            failedGeneration = -1; changed(); pump()
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        main {
            queue.clear(); active?.let { it.cancelled.set(true); it.call?.cancel() }; active = null
            if (moss != null) { worker.execute { moss.close() }; worker.shutdown() } else worker.shutdownNow()
            changed()
        }
    }
}

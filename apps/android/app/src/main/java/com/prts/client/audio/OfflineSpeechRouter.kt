// SPDX-License-Identifier: Apache-2.0
// Queue and WAV ownership adapted from the previous local speech integration.
// Its CosyVoice source and upstream attribution remain in audio/cosy for rollback.
package com.prts.client.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Base64
import com.prts.client.audio.audio8.OfflineAudio8TtsEngine
import com.prts.client.net.DisplayText
import com.prts.client.net.InAudio
import com.prts.client.net.VoiceProfile
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

data class LocalSpeechProgress(val phase: String, val segment: Int, val queuedSegments: Int,
    val elapsedMs: Long = 0, val stageMs: Map<String, Long> = emptyMap())

/** Local synthesis only. Queue ownership and every application callback are on main.
 * [isBusy] includes text preparation, native synthesis and WAV packing, until all
 * valid payloads have been handed to SpeechPlayer. Playback itself is its concern.
 */
class OfflineSpeechRouter(
    context: Context,
    private val profile: VoiceProfile,
    private val onPayload: (InAudio) -> Unit,
    private val onNotice: (String) -> Unit,
    private val onBusyChanged: () -> Unit,
    private val onLocalPayload: ((LocalSpeechAudio) -> Unit)? = null,
    private val onProgress: (LocalSpeechProgress) -> Unit = {},
    // PCM is delivered on the TTS worker; completion is delivered on main.
    // Successful Output ownership moves to onStreamingComplete (release it).
    private val onStreamingPcm: ((InAudio, OfflineAudio8TtsEngine.PcmChunk) -> Boolean)? = null,
    private val onStreamingComplete: (InAudio, Result<OfflineAudio8TtsEngine.Output>) -> Unit = { _, result -> result.getOrNull()?.release() },
) : AutoCloseable {
    private data class Work(val generation: Int, val source: InAudio, val text: String, val segment: Int,
                            val missingSpeechNotice: String? = null,
                            var handle: OfflineAudio8TtsEngine.SynthesisHandle? = null)
    private data class Packed(val message: InAudio, val local: LocalSpeechAudio? = null)
    private class Preview(val replyId: String, val original: String, val spoken: String, val generation: Int) {
        val chunks = ArrayDeque<OfflineAudio8TtsEngine.PcmChunk>()
        var frames = 0
        var discarded = false
        var draining = false
        var work: Work? = null
        var handle: OfflineAudio8TtsEngine.SynthesisHandle? = null
        var result: Result<OfflineAudio8TtsEngine.Output>? = null
    }
    private var preview: Preview? = null // Main owns the pointer; the candidate lock owns its PCM.
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "PRTS-LocalSpeech-Packing") }
    private val engine = OfflineAudio8TtsEngine(context.applicationContext)
    private val cpuThreads = context.getSharedPreferences("prts", Context.MODE_PRIVATE)
        .getInt("qwen_cpu_threads", 4).takeIf { it in listOf(4, 6, 8) } ?: 4
    private val generation = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val queue = ArrayDeque<Work>()
    @Volatile private var active: Work? = null
    private var failedGeneration = -1
    private var lastReplyId: String? = null
    private var nextSegment = 1
    private var missingSpeechNoticeKey: String? = null
    @Volatile private var busy = false
    val isBusy: Boolean get() = busy
    private val language: String? get() = LocalVoiceSettings.outputLanguage(profile)
    val available: Boolean get() = !closed.get() && profile.modeId == LocalVoiceSettings.MODE_ID &&
        language != null && engine.available

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
    private fun valid(work: Work) = !closed.get() && work.generation == generation.get() && active === work
    private fun updateBusy() {
        val next = active != null || queue.isNotEmpty()
        if (busy != next) { busy = next; runCatching { onBusyChanged() } }
    }

    fun preparePreview(replyId: String, text: String) = onMain {
        if (!available || language != "zh" || onStreamingPcm == null || active != null || queue.isNotEmpty() ||
            preview != null || text.length !in 2..96) return@onMain
        val spoken = runCatching { LocalSpeechText.forSpeech(text) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@onMain
        val candidate = Preview(replyId, text, spoken, generation.get())
        preview = candidate
        try {
            candidate.handle = engine.synthesize(spoken, profile.voiceId, "zh", cpuThreads = cpuThreads,
                onPcm = { chunk ->
                    synchronized(candidate) {
                        if (closed.get() || candidate.discarded || candidate.generation != generation.get() ||
                            candidate.frames + chunk.samples.size > 44_100 * 6) return@synchronized false
                        candidate.frames += chunk.samples.size
                        candidate.chunks.addLast(chunk.copy(producedAtMs = SystemClock.elapsedRealtime()))
                        startPreviewDrain(candidate)
                        true
                    }
                }) { result ->
                    synchronized(candidate) {
                        if (candidate.discarded) result.getOrNull()?.release()
                        else { candidate.result = result; startPreviewDrain(candidate) }
                    }
                }
            Log.i("PRTS-EarlySpeech", "preview_started")
        } catch (_: Exception) { discardPreview(candidate) }
    }

    fun cancelPreview(replyId: String) = onMain {
        preview?.takeIf { it.replyId == replyId && synchronized(it) { it.work == null } }?.let(::discardPreview)
    }

    private fun attachPreview(work: Work, spoken: String): Boolean {
        val candidate = preview ?: return false
        val match = synchronized(candidate) {
            if (candidate.discarded || candidate.work != null || candidate.generation != work.generation ||
                candidate.replyId != work.source.actions?.reply_id || candidate.original != work.text ||
                candidate.spoken != spoken || candidate.result?.isFailure == true) false
            else {
                candidate.work = work
                work.handle = candidate.handle
                startPreviewDrain(candidate)
                true
            }
        }
        if (match) Log.i("PRTS-EarlySpeech", "preview_committed") else discardPreview(candidate)
        return match
    }

    // Called with the candidate lock held. Drain off the UI thread because the player applies backpressure.
    private fun startPreviewDrain(candidate: Preview) {
        if (closed.get() || candidate.work == null || candidate.draining || candidate.discarded) return
        candidate.draining = true
        worker.execute {
            while (true) {
                val item = synchronized(candidate) {
                    val work = candidate.work
                    if (candidate.discarded || work == null || !valid(work)) return@execute
                    val chunk = candidate.chunks.pollFirst()
                    if (chunk != null) candidate.frames -= chunk.samples.size
                    val result = if (chunk == null) candidate.result.also { candidate.result = null } else null
                    if (chunk == null && result == null) {
                        candidate.draining = false
                        return@execute
                    }
                    Triple(work, chunk, result)
                }
                val (work, chunk, result) = item
                if (chunk != null && runCatching { valid(work) && onStreamingPcm?.invoke(work.source, chunk) == true }.getOrDefault(false)) continue
                val completion = result ?: Result.failure(IllegalStateException("Committed preview PCM rejected"))
                if (!main.post {
                        discardPreview(candidate)
                        completeStream(work, completion)
                    }) completion.getOrNull()?.release()
                return@execute
            }
        }
    }

    private fun discardPreview(candidate: Preview) {
        if (preview === candidate) preview = null
        synchronized(candidate) {
            candidate.discarded = true
            candidate.chunks.clear()
            candidate.result?.getOrNull()?.release()
            candidate.result = null
        }
        candidate.handle?.cancel()
    }

    fun enqueue(message: InAudio) {
        val ticket = generation.get()
        onMain {
            if (closed.get() || ticket != generation.get()) return@onMain
            val original = message.display_text
            val plan = BilingualSpeechPolicy.select(language, original?.text, original?.transcript,
                original?.translation, original?.translation_language, original?.spoken_text)
            val replyId = message.actions?.reply_id
            val firstInReply = replyId == null || replyId != lastReplyId
            if (firstInReply) nextSegment = 1
            fun display(caption: SpeechCaption) = (original ?: DisplayText()).copy(text = caption.text,
                transcript = caption.transcript, translation = caption.translation,
                translation_language = caption.translationLanguage, spoken_text = caption.spokenText)
            if (plan.missingSpeechNotice != null) {
                lastReplyId = replyId
                queue.addLast(Work(ticket, message.copy(audio = null, volumes = emptyList(), slice_length = 20,
                    display_text = display(plan.caption)), "", nextSegment++, plan.missingSpeechNotice))
                updateBusy(); pump(); return@onMain
            }
            val chunks = if (onStreamingPcm != null || message.forwarded && replyId == "local-opening-ellen-v1") {
                // The fixed welcome stays byte-for-byte whole; do not apply CV3's early comma cut.
                listOfNotNull(plan.spokenText?.takeIf { it.isNotBlank() })
            } else LocalSpeechText.split(plan.spokenText.orEmpty(), firstMaximum = if (language == "ja") 60 else 28,
                eagerFirst = firstInReply)
            if (chunks.isEmpty()) return@onMain
            lastReplyId = replyId
            chunks.forEachIndexed { index, part ->
                val sliceDisplay = display(BilingualSpeechPolicy.captionForSlice(plan, part, index))
                // Main motion only on the first local slice. Retain reply_id and
                // emotion_intent on continuations: the state controller deduplicates
                // mood nudges across the whole reply, including synthesis fallback.
                val actions = if (index == 0) message.actions else message.actions?.copy(motions = null)
                queue.addLast(Work(ticket, message.copy(audio = null, volumes = emptyList(),
                    slice_length = 20, display_text = sliceDisplay, actions = actions), part, nextSegment++))
            }
            updateBusy()
            if (failedGeneration == ticket || !available) {
                failGeneration(ticket, "手机本地语音模型或所选音色尚未就绪，未使用服务器语音")
                return@onMain
            }
            pump()
        }
    }

    private fun pump() {
        if (closed.get() || active != null) return
        while (queue.isNotEmpty() && queue.peekFirst().generation != generation.get()) queue.removeFirst()
        val work = queue.pollFirst() ?: run { updateBusy(); return }
        active = work
        updateBusy()
        work.missingSpeechNotice?.let { message ->
            if (onStreamingPcm != null) {
                failGeneration(work.generation, message)
                return
            }
            val key = work.source.actions?.reply_id ?: "generation:${work.generation}"
            if (key != missingSpeechNoticeKey) {
                missingSpeechNoticeKey = key
                runCatching { onNotice(message) }
            }
            deliver(work, work.source)
            return
        }
        if (!work.text.codePoints().anyMatch { Character.isLetterOrDigit(it) }) {
            if (onStreamingPcm != null) {
                if (active === work) active = null
                updateBusy()
                main.post { pump() }
            } else deliver(work, work.source)
            return
        }
        worker.execute {
            val preparationStarted = System.nanoTime()
            val prepared = runCatching {
                // Preserve the original text for the local model tokenizer.
                LocalSpeechText.forSpeech(work.text)
            }
            main.post {
                if (!valid(work)) return@post
                prepared.fold(onSuccess = { spoken ->
                    try {
                        runCatching { onProgress(LocalSpeechProgress("preparing", work.segment, queue.size,
                            (System.nanoTime() - preparationStarted) / 1_000_000)) }
                        if (attachPreview(work, spoken)) return@post
                        work.handle = engine.synthesize(spoken, profile.voiceId, language!!,
                            cpuThreads = cpuThreads,
                            onProgress = { progress ->
                                if (valid(work)) runCatching { onProgress(LocalSpeechProgress(progress.phase,
                                    work.segment, queue.size, progress.elapsedMs, progress.stageMs)) }
                            }, onPcm = onStreamingPcm?.let { receiver -> { chunk ->
                                valid(work) && receiver(work.source, chunk)
                            } }) { result ->
                            if (!valid(work)) { result.getOrNull()?.release(); return@synthesize }
                            if (onStreamingPcm != null) {
                                completeStream(work, result)
                                return@synthesize
                            }
                            result.fold(onSuccess = { output -> pack(work, output) },
                                onFailure = { fail(work, "这轮手机本地语音未能生成，未重复尝试或切换服务器语音") })
                        }
                    } catch (_: Exception) {
                        fail(work, "手机本地语音未能启动，未使用服务器语音")
                    }
                }, onFailure = { fail(work, "这轮语音文字未能处理，未切换服务器语音") })
            }
        }
    }

    private fun completeStream(work: Work, result: Result<OfflineAudio8TtsEngine.Output>) {
        if (!valid(work)) { result.getOrNull()?.release(); return }
        val accepted = runCatching { onStreamingComplete(work.source, result) }
        if (accepted.isFailure) result.getOrNull()?.release()
        if (result.isFailure || accepted.isFailure) {
            // Partial PCM is never relabelled as a complete response or shown as
            // silent text. Let the player stop the failed stream explicitly.
            val reason = result.exceptionOrNull() ?: accepted.exceptionOrNull()!!
            failGeneration(work.generation, "这轮语音未完整结束，已停止播放；未自动重试", reason,
                completedWork = work)
            return
        }
        if (active === work) active = null
        updateBusy()
        main.post { pump() }
    }

    private fun pack(work: Work, output: OfflineAudio8TtsEngine.Output) {
        if (!valid(work) || worker.isShutdown) { output.release(); return }
        worker.execute {
            val result = runCatching {
                check(!closed.get() && work.generation == generation.get())
                require(output.wav.length() in 44..LocalSpeechWav.MAX_BYTES.toLong())
                val bytes = output.wav.readBytes()
                val volumes = LocalSpeechWav.levels(bytes)
                if (onLocalPayload != null) {
                    val message = work.source.copy(audio = null, volumes = volumes, slice_length = 20)
                    Packed(message, LocalSpeechAudio(message, output.wav, output::release))
                } else {
                    // Compatibility for an older caller until its local receiver is wired.
                    Packed(work.source.copy(audio = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        volumes = volumes, slice_length = 20))
                }
            }
            if (result.getOrNull()?.local == null) output.release()
            val posted = main.post {
                if (!valid(work)) { result.getOrNull()?.local?.close(); return@post }
                result.fold(onSuccess = { packed ->
                    if (packed.local != null) deliverLocal(work, packed.local) else deliver(work, packed.message)
                },
                    onFailure = { fail(work, "本地语音音频无效，未重复播放或切换服务器语音") })
            }
            if (!posted) result.getOrNull()?.local?.close()
        }
    }

    private fun deliverLocal(work: Work, payload: LocalSpeechAudio) {
        if (!valid(work)) { payload.close(); return }
        try { checkNotNull(onLocalPayload).invoke(payload) } catch (_: Exception) {
            payload.close()
            fail(work, "本地语音没有交给播放器，未重复播放")
            return
        }
        // File ownership moved to the player; cancellation there releases it.
        if (active === work) active = null
        updateBusy()
        main.post { pump() }
    }

    private fun deliver(work: Work, payload: InAudio) {
        if (!valid(work)) return
        try { onPayload(payload) } catch (_: Exception) {
            fail(work, "本地语音没有交给播放器，未重复播放")
            return
        }
        if (active === work) active = null
        updateBusy()
        main.post { pump() } // Avoid deep recursion for punctuation-only slices.
    }
    private fun fail(work: Work, message: String) {
        if (!valid(work)) return
        failGeneration(work.generation, message)
    }
    private fun failGeneration(ticket: Int, message: String,
        cause: Throwable = IllegalStateException(message), completedWork: Work? = null) {
        preview?.takeIf { it.generation == ticket }?.let(::discardPreview)
        val unread = mutableListOf<Work>()
        active?.takeIf { it.generation == ticket }?.let {
            it.handle?.cancel()
            unread.add(it)
            active = null
        }
        unread.addAll(queue.filter { it.generation == ticket })
        queue.removeAll { it.generation == ticket }
        val first = failedGeneration != ticket
        failedGeneration = ticket
        // Text remains visible. Keep busy true throughout this hand-off so a
        // synchronous text-only SpeechPlayer.onIdle cannot send an early ACK.
        unread.forEach { work ->
            if (!closed.get() && work.generation == generation.get()) {
                if (onStreamingPcm != null) {
                    if (work !== completedWork) runCatching { onStreamingComplete(work.source, Result.failure(cause)) }
                } else runCatching { onPayload(work.source.copy(audio = null, volumes = emptyList(), slice_length = 20)) }
            }
        }
        updateBusy()
        if (first && !closed.get()) runCatching { onNotice(message) }
        pump()
    }

    fun cancel() {
        val ticket = generation.incrementAndGet()
        onMain {
            preview?.takeIf { it.generation < ticket }?.let(::discardPreview)
            queue.removeAll { it.generation < ticket }
            val previous = active
            if (previous != null && previous.generation < ticket) {
                previous.handle?.cancel()
                active = null
            }
            failedGeneration = -1
            lastReplyId = null; nextSegment = 1
            missingSpeechNoticeKey = null
            updateBusy()
            pump()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        onMain {
            preview?.let(::discardPreview)
            queue.clear()
            active?.handle?.cancel()
            active = null
            engine.close()
            // Drain already queued packing jobs so their Output.release still runs.
            worker.shutdown()
            updateBusy()
        }
    }

}

/** Actual little-endian PCM/float WAV decoding; one RMS mouth value per 20 ms. */
internal object LocalSpeechWav {
    const val MAX_BYTES = 32 * 1024 * 1024
    private fun u16(b: ByteArray, n: Int) = (b[n].toInt() and 255) or ((b[n + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, n: Int): Long = (u16(b, n).toLong() or (u16(b, n + 2).toLong() shl 16))
    private fun tag(b: ByteArray, n: Int) = String(b, n, 4, Charsets.US_ASCII)

    fun levels(bytes: ByteArray): List<Float> {
        require(bytes.size in 44..MAX_BYTES && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE")
        val limit = u32(bytes, 4) + 8
        require(limit in 12..bytes.size.toLong())
        var offset = 12
        var codec = 0; var channels = 0; var rate = 0; var bits = 0; var align = 0
        var pcm = -1; var size = 0
        while (offset.toLong() + 8 <= limit) {
            val length = u32(bytes, offset + 4)
            val start = offset + 8
            val end = start.toLong() + length
            require(end <= limit)
            when (tag(bytes, offset)) {
                "fmt " -> {
                    require(length >= 16)
                    codec = u16(bytes, start); channels = u16(bytes, start + 2)
                    rate = u32(bytes, start + 4).toInt(); align = u16(bytes, start + 12); bits = u16(bytes, start + 14)
                    if (codec == 0xfffe) { require(length >= 40); codec = u16(bytes, start + 24) }
                }
                "data" -> if (pcm < 0) { pcm = start; size = length.toInt() }
            }
            offset = (end + (length and 1)).toInt()
        }
        require(channels in 1..8 && rate in 8000..192000 && pcm >= 0 && size > 0)
        require((codec == 1 && bits in listOf(8, 16, 24, 32)) || (codec == 3 && bits == 32))
        val width = bits / 8
        require(align == channels * width && size % align == 0)
        val frames = size / align
        val window = (rate / 50).coerceAtLeast(1)
        val rms = ArrayList<Float>((frames + window - 1) / window)
        var frame = 0
        while (frame < frames) {
            val stop = minOf(frames, frame + window)
            var energy = 0.0
            for (f in frame until stop) for (channel in 0 until channels) {
                val at = pcm + f * align + channel * width
                val sample = when {
                    codec == 3 -> java.lang.Float.intBitsToFloat(u32(bytes, at).toInt()).toDouble()
                    bits == 8 -> ((bytes[at].toInt() and 255) - 128) / 128.0
                    bits == 16 -> u16(bytes, at).toShort() / 32768.0
                    bits == 24 -> {
                        var n = u16(bytes, at) or ((bytes[at + 2].toInt() and 255) shl 16)
                        if (n and 0x800000 != 0) n = n or -0x1000000
                        n / 8388608.0
                    }
                    else -> u32(bytes, at).toInt() / 2147483648.0
                }
                require(sample.isFinite())
                val bounded = sample.coerceIn(-1.0, 1.0)
                energy += bounded * bounded
            }
            rms.add(sqrt(energy / ((stop - frame) * channels)).toFloat())
            frame = stop
        }
        val reference = maxOf(rms.maxOrNull() ?: 0f, 0.05f)
        return rms.map { (it / reference).coerceIn(0f, 1f) }
    }
}

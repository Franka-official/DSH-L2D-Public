package com.prts.client.audio

import kotlin.math.abs
import kotlin.math.max

/** Timing only: never alters PCM, speech text or generation parameters. */
internal class PcmBufferPolicy(private val rate: Int, text: String, val playbackSpeed: Double = 1.0) {
    init { require(playbackSpeed.isFinite() && playbackSpeed in 0.5..2.0) }
    val estimatedAudioMs = estimateAudioMs(text)
    var receivedFrames = 0L
        private set
    var finalSeen = false
        private set
    var productionRtf = 2.0
        private set
    private var firstAt = -1L
    private var firstFrames = 0L
    private var lastAt = -1L
    var observedIntervals = 0
        private set

    private var audio8EarlyStartCandidate = rate == 44_100 &&
        abs(playbackSpeed - 0.65) < 0.005 && text.isNotBlank() && estimatedAudioMs <= 16_000L

    private var audio8ShortStartCandidate = rate == 44_100 && playbackSpeed in 0.645..0.755 &&
        text.isNotBlank() && estimatedAudioMs <= 2_800L

    fun observe(frames: Int, atMs: Long, final: Boolean, eligibleForAudio8EarlyStart: Boolean = false,
        eligibleForAudio8ShortStart: Boolean = false) {
        if (frames > 0) {
            if (audio8ShortStartCandidate && receivedFrames < AUDIO8_SHORT_START_FRAMES) {
                audio8ShortStartCandidate = eligibleForAudio8ShortStart && frames == AUDIO8_CHUNK_FRAMES &&
                    (lastAt < 0L || atMs - lastAt in 1L..900L)
            }
            if (audio8EarlyStartCandidate && receivedFrames < AUDIO8_START_FRAMES) {
                val maxIntervalMs = AUDIO8_CHUNK_FRAMES * 1000.0 / rate * 2.1
                val stableSpacing = lastAt < 0L || (atMs > lastAt && atMs - lastAt <= maxIntervalMs)
                audio8EarlyStartCandidate = eligibleForAudio8EarlyStart &&
                    frames == AUDIO8_CHUNK_FRAMES && stableSpacing
            }
            receivedFrames += frames
            if (firstAt < 0) { firstAt = atMs; firstFrames = receivedFrames }
            else if (atMs > lastAt) observedIntervals++
            lastAt = max(lastAt, atMs)
            val freshAudio = (receivedFrames - firstFrames) * 1000.0 / rate
            if (freshAudio > 0 && atMs > firstAt) {
                val observed = ((atMs - firstAt) / freshAudio).coerceIn(.5, 8.0)
                // One fast interval is not yet evidence that sustained generation is faster.
                // Slow observations still raise the estimate immediately; final PCM bypasses waiting.
                productionRtf = if (observedIntervals < 2) max(2.0, observed) else observed
            }
        }
        finalSeen = finalSeen || final
    }

    fun targetMs(playedFrames: Long, resumes: Int): Long {
        val receivedMs = receivedFrames * 1000.0 / rate
        val playedMs = playedFrames * 1000.0 / rate
        // Text duration is an estimate, not a promise about unknown future PCM.
        val totalEstimate = max(estimatedAudioMs.toDouble(), receivedMs + if (finalSeen) 0.0 else 960.0)
        val remaining = (totalEstimate - playedMs).coerceAtLeast(0.0)
        // All frame/duration values remain in source-audio time. Slower playback
        // consumes fewer source frames per wall second; it does not speed up TTS.
        val deficit = remaining * (1.0 - 1.0 / (productionRtf * playbackSpeed)).coerceAtLeast(0.0)
        val minimum = if (resumes == 0) 650.0 else (1800.0 + (resumes - 1) * 700.0).coerceAtMost(5000.0)
        val cap = if (resumes == 0) 20000.0 else 12000.0
        return (deficit + 700.0).coerceIn(minimum, cap).toLong()
    }

    fun ready(bufferedFrames: Long, playedFrames: Long, resumes: Int): Boolean =
        finalSeen || bufferedFrames * 1000L / rate >= targetMs(playedFrames, resumes)

    fun readyForAudio8EarlyStart(bufferedFrames: Long, resumes: Int): Boolean =
        resumes == 0 && !finalSeen && audio8EarlyStartCandidate && observedIntervals >= 2 &&
            bufferedFrames >= AUDIO8_START_FRAMES

    fun readyForAudio8ShortStart(bufferedFrames: Long, resumes: Int): Boolean {
        if (resumes != 0 || finalSeen || !audio8ShortStartCandidate || observedIntervals != 1 ||
            receivedFrames != AUDIO8_SHORT_START_FRAMES || bufferedFrames < AUDIO8_SHORT_START_FRAMES) return false
        // Two measured C12 blocks cover the next block at these speeds. Budget for
        // a longer-than-estimated short utterance and a slower subsequent cadence.
        val blockMs = AUDIO8_CHUNK_FRAMES * 1000.0 / rate
        val guardedRtf = max(1.55, (lastAt - firstAt) / blockMs * 1.10)
        val utteranceMs = max(estimatedAudioMs * 1.35, blockMs * 3)
        val deficit = utteranceMs * (1.0 - 1.0 / (guardedRtf * playbackSpeed)).coerceAtLeast(0.0)
        val nextBlockReserve = (lastAt - firstAt + 180L) * playbackSpeed + 120.0
        return bufferedFrames * 1000.0 / rate >= max(deficit + 275.0, nextBlockReserve)
    }

    companion object {
        private const val AUDIO8_CHUNK_FRAMES = 24_576
        private const val AUDIO8_START_FRAMES = AUDIO8_CHUNK_FRAMES * 3L
        private const val AUDIO8_SHORT_START_FRAMES = AUDIO8_CHUNK_FRAMES * 2L

        // Approximate speaking units; conservative headroom is deliberately bounded.
        fun estimateAudioMs(text: String): Long {
            var millis = 0L
            var inWord = false
            var offset = 0
            while (offset < text.length) {
                val cp = text.codePointAt(offset)
                offset += Character.charCount(cp)
                when {
                    cp in 0x3400..0x9fff || cp in 0x20000..0x3134f -> { millis += 250; inWord = false }
                    cp in 0x3040..0x30ff -> { millis += 150; inWord = false }
                    Character.isLetterOrDigit(cp) -> {
                        if (!inWord) millis += 420
                        inWord = true
                    }
                    cp == 0x3002 || cp == 0xff01 || cp == 0xff1f || cp == 33 || cp == 63 || cp == 46 -> { millis += 250; inWord = false }
                    cp == 0x3001 || cp == 0xff0c || cp == 0xff1b || cp == 44 || cp == 59 || cp == 58 -> { millis += 130; inWord = false }
                    else -> inWord = false
                }
            }
            return millis.coerceAtLeast(1000L)
        }
    }
}

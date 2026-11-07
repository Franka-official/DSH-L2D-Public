package com.prts.client.audio.audio8

@androidx.annotation.Keep
internal object Audio8Native {
    val loadFailure: String?
    val available: Boolean
    init {
        val failure = runCatching { System.loadLibrary("prts_audio8_tts") }.exceptionOrNull()
        loadFailure = failure?.let { "${it.javaClass.simpleName}: ${it.message}" }
        available = failure == null
    }
    @androidx.annotation.Keep
    interface Sink {
        fun onPcm(pcm: FloatArray, sampleRate: Int, finalBlock: Boolean): Boolean
        fun onNativeEvent(json: String)
    }
    external fun encodeReference(graphPath: String, samples: FloatArray): LongArray
    external fun create(directory: String, threads: Int): Long
    external fun createTuned(directory: String, arThreads: Int, codecThreads: Int): Long
    external fun createProfiled(directory: String, arThreads: Int, codecThreads: Int, autoCapacityProfile: Boolean): Long
    external fun preparePrefix(handle: Long, packed: LongArray, positions: Int, prefixPositions: Int): String
    external fun createMnn(directory: String, codecModelPath: String, arThreads: Int, codecThreads: Int,
        useOpenCl: Boolean, lowPrecision: Boolean): Long
    external fun createMnnSplit(directory: String, codecModelPath: String, slowArThreads: Int, fastArThreads: Int,
        codecThreads: Int, useOpenCl: Boolean, lowPrecision: Boolean, keepGpuState: Boolean): Long
    external fun createQnnSplit(directory: String, modelPath: String, pluginPath: String,
        backendPath: String, dspDirectory: String, profilePath: String, cacheDirectory: String,
        slowThreads: Int, fastThreads: Int): Long
    external fun mnnRuntimeInfo(): String
    external fun resetNative(handle: Long)
    external fun generateNative(handle: Long, packed: LongArray, positions: Int, prefix: Int,
        maxFrames: Int, chunk: Int, context: Int, guard: Int, seed: Long, sink: Sink): String
    external fun setPipeline(handle: Long, enabled: Boolean)
    external fun runtimeInfo(): String
    external fun affinityProbe(): String
    external fun saveNative(handle: Long, prefix: String)
    external fun cancelNative(handle: Long)
    external fun destroy(handle: Long)
}

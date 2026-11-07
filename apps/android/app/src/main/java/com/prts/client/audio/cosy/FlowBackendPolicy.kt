package com.prts.client.audio.cosy

import java.io.DataInputStream
import java.io.File

/** Only acoustic Flow changes backend. The value is an MNN GPU flag, not CPU threads. */
object FlowBackendPolicy {
    const val GPU_MODE = 80 // MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST.
    val profileId = "opencl-buffer-high-$GPU_MODE-openclvendor-v2"
    val gpuCacheName = "flow-$profileId.cache"
    val buckets = listOf(384, 512, 768, 1024, 1280, 1536, 2048)
    fun bucket(sequence: Int): Int = buckets.firstOrNull { sequence > 0 && sequence <= it }
        ?: error("句子超过离线语音单段容量，请拆分后朗读")
    fun mayFallback(nativeCode: Int): Boolean = nativeCode in setOf(5, 8, 9, 10)
    fun gpuVerified(backendInfo: Boolean, actualBackend: Int, outputDevice: Boolean): Boolean =
        backendInfo && actualBackend == 3 && outputDevice
    fun finiteMel(file: File, expectedFloats: Int): Boolean {
        check(expectedFloats > 0 && file.isFile && file.length() == expectedFloats.toLong() * 4) { "声学输出形状不符" }
        return DataInputStream(file.inputStream().buffered()).use { input ->
            repeat(expectedFloats) {
                if (!java.lang.Float.intBitsToFloat(Integer.reverseBytes(input.readInt())).isFinite()) return false
            }
            true
        }
    }
}

/** A delayed cleanup may reset shared native state only when no owner remains. */
class NativeSessionLeasePolicy {
    private val guard = Any()
    private var owners = 0
    private var revision = 0
    val activeOwners: Int get() = synchronized(guard) { owners }
    fun acquire() = synchronized(guard) { owners++; revision++ }
    fun release(): Int = synchronized(guard) { check(owners > 0); owners--; ++revision }
    fun mayReset(ticket: Int): Boolean = synchronized(guard) { owners == 0 && revision == ticket }
    fun resetIfIdle(ticket: Int, action: () -> Unit): Boolean = synchronized(guard) {
        if (owners != 0 || revision != ticket) false
        else { revision++; action(); true }
    }
}

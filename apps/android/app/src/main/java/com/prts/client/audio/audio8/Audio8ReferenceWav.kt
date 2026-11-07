package com.prts.client.audio.audio8

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal object Audio8ReferenceWav {
    private const val RATE = 44_100
    private const val LIMIT = 8 * 1024 * 1024
    private const val BLOCK = 2048

    fun read(input: InputStream): FloatArray = read(input, RATE, BLOCK)

    fun readForMoss(input: InputStream): FloatArray = read(input, 48_000, 1)

    private fun read(input: InputStream, targetRate: Int, frameBlock: Int): FloatArray {
        val out = java.io.ByteArrayOutputStream()
        val block = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(block)
            if (count < 0) break
            require(out.size() + count <= LIMIT) { "WAV 超过 8 MB" }
            out.write(block, 0, count)
        }
        val bytes = out.toByteArray()
        require(bytes.size in 44..LIMIT) { "请选择 0.5–8 秒的 WAV" }
        val raw = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun hasTag(at: Int, value: String) = value.indices.all { bytes[at + it].toInt() == value[it].code }
        fun u16(at: Int) = raw.getShort(at).toInt() and 0xffff
        fun u32(at: Int) = raw.getInt(at).toLong() and 0xffffffffL
        require(hasTag(0, "RIFF") && hasTag(8, "WAVE")) { "文件不是 RIFF WAV" }
        val end = 8 + u32(4)
        require(end in 44L..bytes.size.toLong()) { "WAV 文件不完整" }
        var formatAt = 0
        var dataAt = 0
        var dataLength = 0
        var at = 12
        while (at + 8 <= end) {
            val start = at + 8
            val length = u32(at + 4)
            require(length <= end - start) { "WAV 数据块不完整" }
            if (hasTag(at, "fmt ")) {
                require(formatAt == 0 && length >= 16) { "WAV 格式块无效" }
                formatAt = start
            } else if (hasTag(at, "data")) {
                require(dataAt == 0) { "WAV 数据块重复" }
                dataAt = start
                dataLength = length.toInt()
            }
            at = (start + length + (length and 1L)).toInt()
        }
        require(at == end.toInt() && formatAt > 0 && dataAt > 0) { "WAV 格式或数据缺失" }
        val format = u16(formatAt)
        val channels = u16(formatAt + 2)
        val sampleRate = raw.getInt(formatAt + 4)
        val alignment = u16(formatAt + 12)
        val bits = u16(formatAt + 14)
        require((format == 1 && bits in setOf(16, 24, 32)) || (format == 3 && bits == 32)) {
            "请选择 PCM 16/24/32 位或浮点 32 位 WAV"
        }
        val width = bits / 8
        require(channels in 1..2 && sampleRate in 8_000..96_000 &&
            alignment == width * channels && dataLength % alignment == 0) { "WAV 采样格式不受支持" }
        val frames = dataLength / alignment
        require(frames in (sampleRate / 2)..(sampleRate * 8)) { "请选择 0.5–8 秒的 WAV" }
        var position = dataAt
        val mono = FloatArray(frames) {
            var value = 0f
            repeat(channels) {
                val sample = when {
                    format == 3 -> raw.getFloat(position)
                    bits == 16 -> raw.getShort(position) / 32768f
                    bits == 24 -> {
                        val integer = (bytes[position].toInt() and 255) or
                            ((bytes[position + 1].toInt() and 255) shl 8) or
                            (bytes[position + 2].toInt() shl 16)
                        integer / 8388608f
                    }
                    else -> raw.getInt(position) / 2147483648f
                }
                require(sample.isFinite()) { "WAV 采样数据无效" }
                value += sample.coerceIn(-1f, 1f)
                position += width
            }
            value / channels
        }
        val length = ((frames.toLong() * targetRate + sampleRate - 1) / sampleRate).toInt()
        val output = FloatArray((length + frameBlock - 1) / frameBlock * frameBlock)
        if (sampleRate == targetRate) mono.copyInto(output)
        else resample(mono, output, length, sampleRate, targetRate)
        return output
    }

    private fun resample(source: FloatArray, target: FloatArray, length: Int, sampleRate: Int, targetRate: Int) {
        val cutoff = minOf(1.0, targetRate.toDouble() / sampleRate)
        val radius = 12.0 / cutoff
        val halfWidth = ceil(radius).toInt()
        for (i in 0 until length) {
            val center = i.toDouble() * sampleRate / targetRate
            val base = floor(center).toInt()
            var sum = 0.0
            for (sourceIndex in maxOf(0, base - halfWidth)..minOf(source.lastIndex, base + halfWidth)) {
                val delta = center - sourceIndex
                if (abs(delta) >= radius) continue
                val angle = PI * cutoff * delta
                val sinc = if (abs(angle) < 1e-10) 1.0 else sin(angle) / angle
                val window = 0.5 + 0.5 * cos(PI * delta / radius)
                sum += source[sourceIndex] * (cutoff * sinc * window)
            }
            target[i] = sum.coerceIn(-1.0, 1.0).toFloat()
        }
    }
}

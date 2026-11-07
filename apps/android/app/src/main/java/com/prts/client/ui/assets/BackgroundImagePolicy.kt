package com.prts.client.ui.assets

import java.io.InputStream
import java.io.OutputStream

object BackgroundImagePolicy {
    const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
    const val MAX_SIDE = 1920
    fun sampleSize(width: Int, height: Int, requestedSide: Int): Int {
        require(width > 0 && height > 0) { "图片尺寸无法识别" }
        val target = requestedSide.coerceIn(1, MAX_SIDE)
        var sample = 1
        while (maxOf(width, height) / sample > target && sample < (1 shl 29)) sample *= 2
        return sample
    }
    fun copyBounded(input: InputStream, output: OutputStream, limit: Long = MAX_IMPORT_BYTES,
                    limitMessage: String = "图片不能超过32 MiB"): Long {
        require(limit > 0)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            if (count == 0) continue
            require(total <= limit - count) { limitMessage }
            output.write(buffer, 0, count); total += count
        }
    }
}

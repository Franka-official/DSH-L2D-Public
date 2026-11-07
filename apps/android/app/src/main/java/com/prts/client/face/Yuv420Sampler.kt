package com.prts.client.face

import java.nio.ByteBuffer

/** Nearest-neighbour, bounded RGB sampling; respects each YUV_420_888 plane's strides.
 * No JPEG is made and no original frame/landmarks are persisted.
 */
object Yuv420Sampler {
    data class Plane(val bytes: ByteBuffer, val rowStride: Int, val pixelStride: Int)
    /** One worker owns this buffer. A Bitmap copies its colors before this array is reused. */
    class Workspace {
        internal var pixels = IntArray(0)
        internal var columns = IntArray(0)
        internal var rows = IntArray(0)
        private var left = -1; private var top = -1
        private var width = -1; private var height = -1
        private var outWidth = -1; private var outHeight = -1
        internal fun prepare(l: Int, t: Int, w: Int, h: Int, ow: Int, oh: Int) {
            if (pixels.size < ow * oh) pixels = IntArray(ow * oh)
            if (columns.size < ow) columns = IntArray(ow)
            if (rows.size < oh) rows = IntArray(oh)
            if (l != left || t != top || w != width || h != height || ow != outWidth || oh != outHeight) {
                for (x in 0 until ow) columns[x] = l + ((x.toLong() * w) / ow).toInt()
                for (y in 0 until oh) rows[y] = t + ((y.toLong() * h) / oh).toInt()
                left = l; top = t; width = w; height = h; outWidth = ow; outHeight = oh
            }
        }
    }

    fun argb(
        y: Plane, u: Plane, v: Plane,
        cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int,
        outputWidth: Int, outputHeight: Int,
        workspace: Workspace = Workspace(),
    ): IntArray {
        require(cropLeft >= 0 && cropTop >= 0 && cropWidth > 0 && cropHeight > 0)
        require(cropLeft.toLong() + cropWidth <= Int.MAX_VALUE && cropTop.toLong() + cropHeight <= Int.MAX_VALUE)
        require(outputWidth in 1..640 && outputHeight in 1..640)
        workspace.prepare(cropLeft, cropTop, cropWidth, cropHeight, outputWidth, outputHeight)
        val output = workspace.pixels
        val columns = workspace.columns; val rows = workspace.rows
        // Validate each plane once. ByteBuffer.get still performs its own bounds check.
        fun validate(plane: Plane, maxRow: Int, maxColumn: Int) {
            require(plane.rowStride > 0 && plane.pixelStride > 0)
            require(maxColumn.toLong() * plane.pixelStride < plane.rowStride)
            val end = plane.bytes.position().toLong() + maxRow.toLong() * plane.rowStride + maxColumn.toLong() * plane.pixelStride
            require(end >= plane.bytes.position() && end < plane.bytes.limit())
        }
        val maxX = columns[outputWidth - 1]; val maxY = rows[outputHeight - 1]
        validate(y, maxY, maxX); validate(u, maxY / 2, maxX / 2); validate(v, maxY / 2, maxX / 2)
        val yBytes = y.bytes; val uBytes = u.bytes; val vBytes = v.bytes
        val yStart = yBytes.position(); val uStart = uBytes.position(); val vStart = vBytes.position()
        val yStep = y.pixelStride; val uStep = u.pixelStride; val vStep = v.pixelStride
        for (oy in 0 until outputHeight) {
            val sy = rows[oy]
            val yr = yStart + sy * y.rowStride
            val ur = uStart + (sy / 2) * u.rowStride; val vr = vStart + (sy / 2) * v.rowStride
            val outputRow = oy * outputWidth
            for (ox in 0 until outputWidth) {
                val sx = columns[ox]; val chromaX = sx / 2
                val yy = ((yBytes.get(yr + sx * yStep).toInt() and 255) - 16).coerceAtLeast(0)
                val uu = (uBytes.get(ur + chromaX * uStep).toInt() and 255) - 128
                val vv = (vBytes.get(vr + chromaX * vStep).toInt() and 255) - 128
                val r = ((298 * yy + 409 * vv + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * yy - 100 * uu - 208 * vv + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * yy + 516 * uu + 128) shr 8).coerceIn(0, 255)
                output[outputRow + ox] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return output
    }
}

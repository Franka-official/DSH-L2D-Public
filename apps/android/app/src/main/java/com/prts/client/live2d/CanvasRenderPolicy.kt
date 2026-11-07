package com.prts.client.live2d

enum class CanvasQuality(val key: String, val title: String, val width: Int, val height: Int) {
    P480("480p", "480P", 480, 854),
    P720("720p", "720P", 720, 1280),
    P1080("1080p", "1080P", 1080, 1920),
}

/** Canvas selection does not change character scale, position or frame cadence. */
object CanvasRenderPolicy {
    val defaultQuality: CanvasQuality = CanvasQuality.P1080
    fun fromSaved(value: String?): CanvasQuality = CanvasQuality.values().firstOrNull { it.key == value } ?: defaultQuality

    fun requireActualExtent(quality: CanvasQuality, width: Int, height: Int) {
        check(width >= quality.width && height >= quality.height) {
            "Live2D requires at least ${quality.width}x${quality.height} (${quality.title}); EGL returned ${width}x$height; no lower-resolution fallback"
        }
    }

    fun textureLimit(glMaximum: Int): Int {
        require(glMaximum > 0) { "OpenGL did not report a valid maximum texture size" }
        return minOf(4096, glMaximum)
    }

    fun textureSample(width: Int, height: Int, glMaximum: Int): Int {
        require(width > 0 && height > 0)
        val limit = textureLimit(glMaximum)
        var sample = 1
        while (width / sample > limit || height / sample > limit) sample *= 2
        return sample
    }
}

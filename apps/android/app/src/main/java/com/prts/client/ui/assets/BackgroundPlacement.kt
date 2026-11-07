package com.prts.client.ui.assets

data class BackgroundPlacement(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun bounded() = BackgroundPlacement(
        if (scale.isFinite()) scale.coerceIn(.65f, 2.25f) else 1f,
        if (x.isFinite()) x.coerceIn(-.5f, .5f) else 0f,
        if (y.isFinite()) y.coerceIn(-.5f, .5f) else 0f,
    )

    /** Coordinates are relative to the viewport, so they survive layout changes. */
    fun transform(width: Int, height: Int, sourceWidth: Int, sourceHeight: Int): BackgroundTransform? {
        if (minOf(width, height, sourceWidth, sourceHeight) <= 0) return null
        val value = bounded()
        val cover = maxOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight) * value.scale
        val drawnWidth = sourceWidth * cover
        val drawnHeight = sourceHeight * cover
        return BackgroundTransform(drawnWidth, drawnHeight, (width - drawnWidth) / 2 + width * value.x,
            (height - drawnHeight) / 2 + height * value.y)
    }
}

data class BackgroundTransform(val width: Float, val height: Float, val left: Float, val top: Float)
data class BackgroundOptions(val placement: BackgroundPlacement = BackgroundPlacement(), val sound: Boolean = false)

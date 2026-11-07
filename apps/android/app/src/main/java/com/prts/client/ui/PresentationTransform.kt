package com.prts.client.ui

/** Viewport-relative placement, independent of model bones and physics. */
data class PresentationTransform(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    fun sanitized() = PresentationTransform(
        if (scale.isFinite()) scale.coerceIn(.65f, 2.25f) else 1f,
        if (offsetX.isFinite()) offsetX.coerceIn(-.5f, .5f) else 0f,
        if (offsetY.isFinite()) offsetY.coerceIn(-.5f, .5f) else 0f,
    )
}

package com.prts.client.ui

import kotlin.math.hypot

/** Screen-pixel bounds of the renderer's actual fitted viewport, including its letterbox origin. */
data class PlacementViewport(val left: Float, val top: Float, val width: Float, val height: Float) {
    val valid: Boolean get() = listOf(left, top, width, height).all { it.isFinite() } && width > 0f && height > 0f
    fun x(screenX: Float): Float = (screenX - left) / width - .5f
    fun y(screenY: Float): Float = (screenY - top) / height - .5f
}

data class PlacementPointer(val id: Int, val x: Float, val y: Float)

object PlacementGesturePolicy {
    /** Rebase `before` and `anchor` whenever a pointer joins/leaves; no hidden gesture state. */
    fun move(before: PresentationTransform, anchor: List<PlacementPointer>, current: List<PlacementPointer>,
             viewport: PlacementViewport): PresentationTransform {
        val safe = before.sanitized()
        if (!viewport.valid) return safe
        val pairs = anchor.mapNotNull { start ->
            current.firstOrNull { it.id == start.id }?.let { end -> start to end }
        }.filter { (a, b) -> listOf(a.x, a.y, b.x, b.y).all { it.isFinite() } }.take(2)
        if (pairs.isEmpty()) return safe
        if (pairs.all { (start, end) -> start == end }) return safe
        val startX = pairs.sumOf { it.first.x.toDouble() }.toFloat() / pairs.size
        val startY = pairs.sumOf { it.first.y.toDouble() }.toFloat() / pairs.size
        val endX = pairs.sumOf { it.second.x.toDouble() }.toFloat() / pairs.size
        val endY = pairs.sumOf { it.second.y.toDouble() }.toFloat() / pairs.size
        var nextScale = safe.scale
        if (pairs.size == 2) {
            val startSpan = hypot(pairs[1].first.x - pairs[0].first.x, pairs[1].first.y - pairs[0].first.y)
            val endSpan = hypot(pairs[1].second.x - pairs[0].second.x, pairs[1].second.y - pairs[0].second.y)
            if (startSpan >= 8f && endSpan.isFinite()) nextScale = (safe.scale * endSpan / startSpan).coerceIn(.65f, 2.25f)
        }
        val ratio = nextScale / safe.scale
        // Keep the model point under the gesture focus fixed while fingers spread or move.
        return PresentationTransform(nextScale,
            viewport.x(endX) - (viewport.x(startX) - safe.offsetX) * ratio,
            viewport.y(endY) - (viewport.y(startY) - safe.offsetY) * ratio).sanitized()
    }
}

data class DirectPlacementEnd(val value: PresentationTransform, val save: Boolean, val tap: Boolean)

/** Gesture lifecycle shared by the Android adapter and real JVM scenarios. No persistence during move. */
class DirectPlacementSession(private val touchSlop: Float, private val longPressMs: Long) {
    var active = false; private set
    var manipulating = false; private set
    var value = PresentationTransform(); private set
    private var original = value
    private var before = value
    private var anchor = emptyList<PlacementPointer>()
    private var viewport: PlacementViewport? = null
    private var beganAt = 0L
    private var everMultiPointer = false

    fun begin(initial: PresentationTransform, points: List<PlacementPointer>, bounds: PlacementViewport, now: Long) {
        value = initial.sanitized(); original = value; active = true; manipulating = false
        beganAt = now; everMultiPointer = points.size > 1; rebase(points, bounds)
    }

    fun rebase(points: List<PlacementPointer>, bounds: PlacementViewport) {
        if (!active) return
        everMultiPointer = everMultiPointer || points.size > 1
        anchor = points; before = value; viewport = bounds
    }

    fun move(points: List<PlacementPointer>, bounds: PlacementViewport) {
        if (!active) return
        if (bounds != viewport) { rebase(points, bounds); return }
        if (!manipulating) {
            val travelled = anchor.any { start -> points.firstOrNull { it.id == start.id }?.let {
                hypot(it.x - start.x, it.y - start.y) > touchSlop.coerceAtLeast(0f)
            } == true }
            if (!travelled) return
            manipulating = true
        }
        value = PlacementGesturePolicy.move(before, anchor, points, bounds)
    }

    fun finish(now: Long, cancelled: Boolean = false): DirectPlacementEnd {
        if (!active) return DirectPlacementEnd(value, false, false)
        val result = DirectPlacementEnd(value, value != original,
            !cancelled && !manipulating && !everMultiPointer && now - beganAt in 0 until longPressMs)
        active = false; manipulating = false; anchor = emptyList(); viewport = null
        return result
    }
}

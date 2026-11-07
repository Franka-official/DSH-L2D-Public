package com.prts.client.ui

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.prts.client.live2d.Live2DView

/** Owns only the model View's touch stream; controls and camera overlays retain priority. */
class DirectCharacterPlacementController(
    private val view: Live2DView,
    private val character: String,
    private val enabled: () -> Boolean,
    private val blockedViews: () -> List<View> = { emptyList() },
    private val onInteraction: () -> Unit = {},
    private val onTap: (Float, Float) -> Unit = { _, _ -> },
    private val onNonTap: () -> Unit = {},
    private val onManipulationChanged: (Boolean) -> Unit = {},
) : AutoCloseable {
    private val session = DirectPlacementSession(ViewConfiguration.get(view.context).scaledTouchSlop.toFloat(),
        ViewConfiguration.getLongPressTimeout().toLong())
    private val location = IntArray(2)
    private val visible = Rect()
    private var foreground = false
    private var closed = false
    val isManipulating: Boolean get() = session.manipulating

    init { view.setOnTouchListener { _, event -> touch(event) } }

    private fun points(event: MotionEvent, skip: Int = -1): List<PlacementPointer> {
        view.getLocationOnScreen(location)
        return (0 until event.pointerCount).filter { it != skip }.map {
            PlacementPointer(event.getPointerId(it), location[0] + event.getX(it), location[1] + event.getY(it))
        }
    }

    private fun viewport(): PlacementViewport {
        view.getLocationOnScreen(location)
        val fitted = view.presentationViewport()
        return PlacementViewport(location[0] + fitted.left, location[1] + fitted.top, fitted.width(), fitted.height())
    }

    private fun isBlocked(point: PlacementPointer): Boolean = blockedViews().any {
        it.isShown && it.getGlobalVisibleRect(visible) && visible.contains(point.x.toInt(), point.y.toInt())
    }

    private fun move(event: MotionEvent) {
        val previous = session.value; val wasMoving = session.manipulating
        session.move(points(event), viewport())
        if (!wasMoving && session.manipulating) onManipulationChanged(true)
        if (session.value != previous) apply(session.value)
    }

    private fun apply(value: PresentationTransform) = view.setPresentationTransform(value.scale, value.offsetX, value.offsetY)

    private fun end(now: Long, cancelled: Boolean, event: MotionEvent? = null) {
        val wasActive = session.active
        val wasMoving = session.manipulating
        val result = session.finish(now, cancelled)
        if (result.save) CharacterPresentationStore.save(view.context, character, result.value)
        view.parent?.requestDisallowInterceptTouchEvent(false)
        if (wasMoving) onManipulationChanged(false)
        if (result.tap && event != null) { view.performClick(); onTap(event.x, event.y) }
        else if (wasActive) onNonTap()
    }

    private fun touch(event: MotionEvent): Boolean {
        if (closed) return false
        if (!foreground || !enabled()) {
            val owned = session.active
            finishGesture()
            return owned
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val points = points(event)
            if (points.isEmpty() || isBlocked(points.first()) || !viewport().valid) return false
            session.begin(CharacterPresentationStore.load(view.context, character), points, viewport(), event.eventTime)
            onInteraction()
            return true
        }
        if (!session.active) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> session.rebase(points(event), viewport())
            MotionEvent.ACTION_MOVE -> {
                move(event)
                if (session.manipulating) view.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_UP -> { move(event); session.rebase(points(event, event.actionIndex), viewport()) }
            MotionEvent.ACTION_UP -> { move(event); end(event.eventTime, false, event) }
            MotionEvent.ACTION_CANCEL -> end(event.eventTime, true)
        }
        return true
    }

    fun finishGesture() { if (session.active) end(SystemClock.uptimeMillis(), true) }
    fun reload() { finishGesture(); if (!closed) apply(CharacterPresentationStore.load(view.context, character)) }
    fun setForeground(value: Boolean) {
        if (closed) return
        if (!value) finishGesture()
        foreground = value
        if (value) reload()
    }
    override fun close() {
        if (closed) return
        finishGesture(); closed = true; foreground = false; view.setOnTouchListener(null)
    }
}

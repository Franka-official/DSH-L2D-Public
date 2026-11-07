package com.prts.client.ui

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.util.Log
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.prts.client.R
import com.prts.client.live2d.Live2DView
import kotlin.math.roundToInt

/** Same viewport editor on start/call pages. Closing/background saves and removes its own overlay. */
object CharacterPlacementEditor {
    interface Handle { fun close() }
    fun show(activity: AppCompatActivity, character: String, live2D: Live2DView,
             onDismiss: () -> Unit = {}): Handle {
        val root = activity.findViewById<FrameLayout>(android.R.id.content)
        val overlay = FrameLayout(activity).apply {
            isClickable = true; isFocusableInTouchMode = true
            elevation = 32f * activity.resources.displayMetrics.density
        }
        var value = CharacterPresentationStore.load(activity, character)
        var anchor = emptyList<PlacementPointer>(); var before = value; var closed = false
        var gestureViewport: PlacementViewport? = null
        val touchLocation = IntArray(2); val modelLocation = IntArray(2)
        val scale = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * scale).roundToInt()
        val ink = ContextCompat.getColor(activity, R.color.ui_text)
        val hint = TextView(activity).apply {
            text = "单指拖动位置 · 双指缩放"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(ink); setBackgroundResource(R.drawable.ui_glass_panel); setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        overlay.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(125) })
        val touch = View(activity).apply { id = R.id.placementDragSurface; contentDescription = "拖动角色调整位置"; isClickable = true }
        overlay.addView(touch, 0, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(activity).apply {
            isClickable = true
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)); setBackgroundResource(R.drawable.ui_glass_panel)
        }
        val label = TextView(activity).apply { id = R.id.placementSizeLabel; textSize = 13f; setTextColor(ink) }
        val seek = SeekBar(activity).apply { id = R.id.placementScale; max = 160; contentDescription = "调整角色大小" }
        fun publish() {
            value = value.sanitized(); label.text = "大小 ${(value.scale * 100).roundToInt()}%"
            seek.progress = ((value.scale - .65f) * 100).roundToInt()
            live2D.setPresentationTransform(value.scale, value.offsetX, value.offsetY)
        }
        fun save() = CharacterPresentationStore.save(activity, character, value)
        panel.addView(label); panel.addView(seek, LinearLayout.LayoutParams(-1, dp(44)))
        val row = LinearLayout(activity)
        fun button(text: String) = TextView(activity).apply {
            this.text = text; textSize = 13f; setTextColor(ink); gravity = Gravity.CENTER
            isClickable = true; isFocusable = true; setBackgroundResource(R.drawable.ui_chip)
        }
        val reset = button("居中复位"); val done = button("完成")
        reset.id = R.id.placementReset; done.id = R.id.placementDone
        row.addView(reset, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) })
        row.addView(done, LinearLayout.LayoutParams(0, dp(46), 1f)); panel.addView(row)
        overlay.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(22); rightMargin = dp(22); bottomMargin = dp(28)
        })
        seek.progress = ((value.scale - .65f) * 100).roundToInt()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { value = value.copy(scale = .65f + progress / 100f); publish() }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) { save() }
        })
        fun pointers(e: MotionEvent, skip: Int = -1): List<PlacementPointer> {
            touch.getLocationOnScreen(touchLocation)
            return (0 until e.pointerCount).filter { it != skip }.map {
                PlacementPointer(e.getPointerId(it), e.getX(it) + touchLocation[0], e.getY(it) + touchLocation[1])
            }
        }
        fun viewport(): PlacementViewport {
            live2D.getLocationOnScreen(modelLocation)
            val fitted = live2D.presentationViewport()
            return PlacementViewport(modelLocation[0] + fitted.left, modelLocation[1] + fitted.top,
                fitted.width(), fitted.height())
        }
        fun rebase(e: MotionEvent, skip: Int = -1) {
            anchor = pointers(e, skip); before = value; gestureViewport = viewport()
        }
        fun move(e: MotionEvent) {
            val currentViewport = viewport()
            if (currentViewport != gestureViewport) { rebase(e); return }
            value = PlacementGesturePolicy.move(before, anchor, pointers(e), currentViewport); publish()
        }
        touch.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    touch.parent.requestDisallowInterceptTouchEvent(true)
                    rebase(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    move(e)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    move(e); rebase(e, e.actionIndex)
                }
                MotionEvent.ACTION_UP -> {
                    move(e)
                    anchor = emptyList(); save(); touch.parent.requestDisallowInterceptTouchEvent(false)
                    touch.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    anchor = emptyList(); save(); touch.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            true
        }
        val back = object : OnBackPressedCallback(true) { var closeAction: (() -> Unit)? = null
            override fun handleOnBackPressed() { closeAction?.invoke() } }
        var closeAction: (() -> Unit)? = null
        val observer = object : DefaultLifecycleObserver { override fun onPause(owner: LifecycleOwner) { closeAction?.invoke() } }
        val handle = object : Handle { override fun close() {
            if (closed) return
            closed = true; anchor = emptyList(); save(); back.remove(); activity.lifecycle.removeObserver(observer)
            touch.parent?.requestDisallowInterceptTouchEvent(false)
            root.removeView(overlay); onDismiss()
        } }
        closeAction = { handle.close() }; back.closeAction = closeAction
        reset.setOnClickListener { value = PresentationTransform(); seek.progress = 35; publish(); save() }
        done.setOnClickListener { handle.close() }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1, Gravity.FILL))
        overlay.bringToFront(); overlay.requestFocus()
        overlay.post {
            if (!closed) Log.i("PRTS-Placement", "opened host=${root.width}x${root.height} touch=${touch.width}x${touch.height} model=${live2D.width}x${live2D.height} viewport=${viewport()} scale=${value.scale} x=${value.offsetX} y=${value.offsetY}")
        }
        activity.lifecycle.addObserver(observer); activity.onBackPressedDispatcher.addCallback(activity, back)
        publish()
        return handle
    }
}

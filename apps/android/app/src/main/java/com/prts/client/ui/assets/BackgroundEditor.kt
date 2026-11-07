package com.prts.client.ui.assets

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.prts.client.databinding.DialogBackgroundEditorBinding

object BackgroundEditor {
    fun show(activity: ComponentActivity, item: BgItem, beforeOpen: () -> Unit = {}, afterClose: () -> Unit = {}): Dialog {
        val binding = DialogBackgroundEditorBinding.inflate(activity.layoutInflater)
        val dialog = Dialog(activity)
        val library = BackgroundLibrary(activity)
        val presenter = BackgroundPresenter(activity, binding.previewImage)
        var options = library.options(item)
        val host = activity.window.decorView
        val hostWidth = host.width.coerceAtLeast(1)
        val hostHeight = host.height.coerceAtLeast(1)
        binding.previewSlot.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view.width > 0 && view.height > 0) {
                val fit = minOf(view.width.toFloat() / hostWidth, view.height.toFloat() / hostHeight)
                binding.previewCanvas.layoutParams = FrameLayout.LayoutParams(
                    (fit * hostWidth).toInt().coerceAtLeast(1), (fit * hostHeight).toInt().coerceAtLeast(1), Gravity.CENTER,
                )
            }
        }
        fun move(value: BackgroundPlacement) {
            options = options.copy(placement = value.bounded())
            presenter.preview(options)
        }

        val touch = binding.previewTouch
        val detector = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var lastX = 0f
            private var lastY = 0f
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastX = detector.focusX
                lastY = detector.focusY
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = options.placement
                val next = current.copy(scale = current.scale * detector.scaleFactor).bounded()
                val ratio = next.scale / current.scale
                val width = touch.width.coerceAtLeast(1).toFloat()
                val height = touch.height.coerceAtLeast(1).toFloat()
                move(next.copy(
                    x = current.x + (detector.focusX - lastX) / width + (1 - ratio) * (lastX / width - .5f - current.x),
                    y = current.y + (detector.focusY - lastY) / height + (1 - ratio) * (lastY / height - .5f - current.y),
                ))
                lastX = detector.focusX
                lastY = detector.focusY
                return true
            }
        }).apply { isQuickScaleEnabled = false; isStylusScaleEnabled = false }
        var drag = false
        var dragX = 0f
        var dragY = 0f
        touch.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { drag = true; dragX = event.x; dragY = event.y }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = false
                MotionEvent.ACTION_MOVE -> if (drag && event.pointerCount == 1) {
                    val current = options.placement
                    move(current.copy(x = current.x + (event.x - dragX) / touch.width.coerceAtLeast(1),
                        y = current.y + (event.y - dragY) / touch.height.coerceAtLeast(1)))
                    dragX = event.x; dragY = event.y
                }
            }
            true
        }

        binding.videoSound.visibility = if (item.video) View.VISIBLE else View.GONE
        binding.videoSound.isChecked = options.sound
        binding.videoSound.setOnCheckedChangeListener { _, enabled ->
            options = options.copy(sound = enabled)
            presenter.preview(options)
        }
        binding.resetPlacement.setOnClickListener { move(BackgroundPlacement()) }
        binding.cancelPlacement.setOnClickListener { dialog.dismiss() }
        binding.savePlacement.setOnClickListener { library.saveOptions(item, options); dialog.dismiss() }

        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = presenter.setForeground(true)
            override fun onPause(owner: LifecycleOwner) = presenter.setForeground(false)
            override fun onDestroy(owner: LifecycleOwner) = dialog.dismiss()
        }
        dialog.setContentView(binding.root)
        dialog.setOnDismissListener {
            activity.lifecycle.removeObserver(observer)
            presenter.close()
            afterClose()
        }
        beforeOpen()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH_PARENT, MATCH_PARENT)
        }
        activity.lifecycle.addObserver(observer)
        presenter.show(item)
        return dialog
    }
}

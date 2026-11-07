package com.prts.client.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import android.view.View

/**
 * 背景模糊工具：把任意 View（或背景 Bitmap）实时降采样+高斯模糊，
 * 供磨砂控件"透过"显示。minSdk26 用 RenderScript（仍可用）。
 */
object BackdropBlur {

    /** 对传入 bitmap 做半径 r 的高斯模糊，返回新 bitmap（自动降采样提速）。 */
    fun blurBitmap(ctx: Context, src: Bitmap, radius: Float = 18f): Bitmap {
        val scale = 0.35f
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val out = small.copy(Bitmap.Config.ARGB_8888, true)
        return try {
            val rs = RenderScript.create(ctx)
            val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            val inAlloc = Allocation.createFromBitmap(rs, out)
            val outAlloc = Allocation.createFromBitmap(rs, out)
            script.setRadius(radius.coerceIn(1f, 25f))
            script.setInput(inAlloc)
            script.forEach(outAlloc)
            outAlloc.copyTo(out)
            rs.destroy()
            Bitmap.createScaledBitmap(out, src.width, src.height, true)
        } catch (t: Throwable) {
            Log.w("BackdropBlur", "RS failed, fallback raw", t)
            out
        }
    }

    /** 截一个 View 当前画面 -> 模糊后的 bitmap（用于面板背后）。 */
    fun blurView(ctx: Context, view: View, radius: Float = 18f): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            blurBitmap(ctx, bmp, radius)
        } catch (t: Throwable) { null }
    }
}

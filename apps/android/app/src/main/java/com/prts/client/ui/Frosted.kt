package com.prts.client.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/**
 * 清透白·磨砂设计系统（iOS / 微信通话质感）。
 * 所有控件统一从这里出：胶囊按钮、面板、输入条、描边。
 */
object Frosted {

    // 主色板
    val WHITE_A16 = 0x4DFFFFFF.toInt()   // 磨砂填充 alpha .30（深底上提亮，iOS 观感）
    val WHITE_A24 = 0x66FFFFFF.toInt()   // 强调磨砂填充 alpha .40
    val STROKE_A35 = 0x8CFFFFFF.toInt()  // 描边 alpha .55
    val TEXT_MAIN = 0xEBFFFFFF.toInt()   // 主文字 alpha .92
    val TEXT_SUB = 0x8CFFFFFF.toInt()    // 次级 alpha .55
    val DANGER = 0xFFE06C75.toInt()      // 挂断红（唯一彩色）

    /** 纯代码磨砂面板：半透白 + 圆角 + 描边。背景模糊由 BackdropBlurView 提供。 */
    fun panel(radiusPx: Float, fill: Int = WHITE_A16): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = radiusPx
        d.setColor(fill)
        d.setStroke(2.dp(), STROKE_A35)
        return d
    }

    /** 胶囊按钮背景（圆角=高度一半）。pressed=true 时更亮。 */
    fun capsule(heightPx: Int, pressed: Boolean = false): GradientDrawable {
        val d = panel(heightPx / 2f, if (pressed) WHITE_A24 else WHITE_A16)
        return d
    }

    /** 挂断胶囊：微信红半透 + 白描边。 */
    fun hangCapsule(heightPx: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = heightPx / 2f
        d.setColor(0x99E06C75.toInt())
        d.setStroke(2.dp(), STROKE_A35)
        return d
    }

    /**
     * 生成一张磨砂噪声纹理（给面板加"磨砂颗粒感"，叠在 panel 上）。
     * 微信/iOS 的 frosted 观感来自细噪 + 模糊，纯半透会显得死板。
     */
    fun frostNoise(w: Int = 256, h: Int = 256): Drawable {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        val rnd = java.util.Random(42)
        for (i in 0 until 5200) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val a = 10 + rnd.nextInt(26)
            p.color = (a shl 24) or 0x00FFFFFF
            c.drawCircle(x, y, 0.8f + rnd.nextFloat() * 1.2f, p)
        }
        // 轻微竖向渐变提亮顶部，模拟玻璃反光
        val gradPaint = Paint()
        gradPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0x14FFFFFF, 0x00000000, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), gradPaint)
        return BitmapDrawable(bmp)
    }

    private fun Int.dp(): Int = this // px 直用；调用方按 density 换算好再传入
}

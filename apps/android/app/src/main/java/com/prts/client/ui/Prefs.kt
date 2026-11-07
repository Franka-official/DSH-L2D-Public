package com.prts.client.ui

import android.content.Context
import com.prts.client.live2d.CanvasQuality
import com.prts.client.live2d.CanvasRenderPolicy

object Prefs {
    private const val F = "prts"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(F, Context.MODE_PRIVATE)

    fun get(ctx: Context): Pair<String, String> {
        val server = sp(ctx).getString("server", "").orEmpty()
        val config = sp(ctx).getString("config", "default")!!
        return server to config
    }

    fun set(ctx: Context, server: String, config: String) {
        sp(ctx).edit().putString("server", server).putString("config", config).apply()
    }

    fun getConfig(ctx: Context): String = sp(ctx).getString("config", "default")!!

    fun getSelectedBg(ctx: Context): String =
        sp(ctx).getString("bg", "builtin:aurora")!!

    fun setSelectedBg(ctx: Context, id: String) {
        sp(ctx).edit().putString("bg", id).apply()
    }

    fun getCanvasQuality(ctx: Context): CanvasQuality =
        CanvasRenderPolicy.fromSaved(sp(ctx).getString("live2d_canvas_quality", null))

    fun setCanvasQuality(ctx: Context, quality: CanvasQuality) {
        sp(ctx).edit().putString("live2d_canvas_quality", quality.key).apply()
    }
}

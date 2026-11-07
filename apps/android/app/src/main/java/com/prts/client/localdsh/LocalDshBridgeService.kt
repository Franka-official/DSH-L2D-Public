package com.prts.client.localdsh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Started only by the visible authorization flow. Never sticky and never boot-started. */
class LocalDshBridgeService : Service() {
    companion object {
        private const val CHANNEL = "local_dsh_bridge"
        private const val ID = 47651
        private const val STOP = "com.prts.client.localdsh.STOP"
        @Volatile var running = false
            private set
        fun rememberForOpen(ctx: Context, enabled: Boolean) {
            ctx.getSharedPreferences("local_dsh_bridge", Context.MODE_PRIVATE)
                .edit().putBoolean("resume_on_open", enabled).apply()
        }
        fun canResumeOnOpen(ctx: Context): Boolean =
            ctx.getSharedPreferences("local_dsh_bridge", Context.MODE_PRIVATE).getBoolean("resume_on_open", false) &&
                LocalDshBridge.hasSavedGrant(ctx)
        fun startFromVisible(ctx: Context) {
            val intent = Intent(ctx, LocalDshBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        }
        fun stop(ctx: Context) {
            rememberForOpen(ctx, false)
            running = false
            ctx.stopService(Intent(ctx, LocalDshBridgeService::class.java))
            LocalDshBridge.stop()
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private var connected = false
    private val watchStatus = object : Runnable {
        override fun run() {
            if (!running) return
            val online = LocalDshBridge.connectorReady
            if (online != connected) {
                connected = online
                getSystemService(NotificationManager::class.java).notify(ID, connectionNotification(online))
            }
            main.postDelayed(this, 1_000L)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            rememberForOpen(this, false)
            running = false; LocalDshBridge.stop(); stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL,
                "本机 DSHGo 连接", NotificationManager.IMPORTANCE_LOW))
            connected = LocalDshBridge.connectorReady
            val notification = connectionNotification(connected)
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            else startForeground(ID, notification)
            LocalDshBridge.start(this)
            running = true
            main.removeCallbacks(watchStatus)
            main.post(watchStatus)
        } catch (_: Exception) { running = false; LocalDshBridge.stop(); stopSelf() }
        return START_NOT_STICKY
    }
    private fun connectionNotification(online: Boolean): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, LocalDshBridgeService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent().setClassName(this, "com.prts.client.ui.StartActivity"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(if (online) "DSH Go · 本机已连接" else "DSH Go · 等待连接")
            .setContentText(if (online) "通话使用已授权的本机插件" else "可在 DSH Go 的「全部功能」→「DSH L2D桥接」点连接")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止连接", stop).build())
            .build()
    }
    override fun onDestroy() {
        main.removeCallbacks(watchStatus)
        running = false; LocalDshBridge.stop()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        super.onDestroy()
    }
}

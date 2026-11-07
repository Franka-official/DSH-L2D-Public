package com.prts.client.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.prts.client.localdsh.LocalDshBridge
import kotlin.math.roundToInt

internal class FirstRunGuide(
    private val activity: AppCompatActivity,
    private val openGoConnection: () -> Unit,
) {
    private val preferences get() = activity.getSharedPreferences("first_run_guide", Context.MODE_PRIVATE)
    private var dialog: AlertDialog? = null
    private var refresh: (() -> Unit)? = null
    private val requestPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh?.invoke()
    }

    fun onResume() {
        if (dialog?.isShowing == true) refresh?.invoke()
        else if (!preferences.getBoolean("seen", false)) show()
    }

    fun show() {
        if (dialog?.isShowing == true || activity.isFinishing || activity.isDestroyed) return
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(10), dp(24), dp(4))
        }
        fun heading(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 14f
            setPadding(dp(2), dp(8), dp(2), dp(2))
            content.addView(this)
        }
        fun action(onClick: () -> Unit) = Button(activity).apply {
            setAllCaps(false)
            setOnClickListener { onClick() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        heading("语音输入与视频通话")
        val microphone = action { request(Manifest.permission.RECORD_AUDIO) }
        val camera = action { request(Manifest.permission.CAMERA) }
        heading("需要后台保持桥接时，可在应用的电池设置中选择不受限制。")
        val battery = action { openAppSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
        heading("同一部手机上的本机连接")
        val go = action {
            markSeen()
            close()
            openGoConnection()
        }
        val scroll = ScrollView(activity).apply { addView(content) }
        val guide = AlertDialog.Builder(activity)
            .setTitle("新手引导")
            .setView(scroll)
            .setPositiveButton("完成") { _, _ -> markSeen() }
            .setNegativeButton("稍后") { _, _ -> markSeen() }
            .setOnCancelListener { markSeen() }
            .create()
        guide.setOnDismissListener {
            if (dialog === guide) {
                dialog = null
                refresh = null
            }
        }
        dialog = guide
        refresh = {
            renderPermission(microphone, Manifest.permission.RECORD_AUDIO, "麦克风")
            renderPermission(camera, Manifest.permission.CAMERA, "相机")
            val unrestricted = activity.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(activity.packageName) == true
            battery.text = if (unrestricted) "电池优化 · 已豁免" else "打开电池设置"
            battery.isEnabled = !unrestricted
            go.text = if (LocalDshBridge.connectorReady) "查看 DSH Go 连接" else "连接 DSH Go"
        }
        refresh?.invoke()
        guide.show()
    }

    fun close() {
        dialog?.dismiss()
    }

    private fun renderPermission(button: Button, permission: String, label: String) {
        val granted = hasPermission(permission)
        button.text = when {
            granted -> "$label · 已允许"
            systemSettingsRequired(permission) -> "$label · 打开系统设置"
            else -> "允许$label"
        }
        button.isEnabled = !granted
    }

    private fun request(permission: String) {
        if (hasPermission(permission)) return
        if (systemSettingsRequired(permission)) {
            openAppSettings(Settings.ACTION_SETTINGS)
            return
        }
        preferences.edit().putBoolean(permission, true).apply()
        requestPermission.launch(permission)
    }

    private fun hasPermission(permission: String) =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun systemSettingsRequired(permission: String) =
        preferences.getBoolean(permission, false) &&
            !activity.shouldShowRequestPermissionRationale(permission)

    private fun openAppSettings(fallback: String) {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null))
        val opened = runCatching { activity.startActivity(details) }.isSuccess ||
            runCatching { activity.startActivity(Intent(fallback)) }.isSuccess
        if (!opened) Toast.makeText(activity, "请在系统设置中打开应用信息", Toast.LENGTH_SHORT).show()
    }

    private fun markSeen() { preferences.edit().putBoolean("seen", true).apply() }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
}

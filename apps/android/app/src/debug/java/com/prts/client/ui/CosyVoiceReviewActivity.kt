package com.prts.client.ui

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.prts.client.R
import com.prts.client.audio.cosy.OfflineCosyVoiceEngine
import com.prts.client.net.ConnectionSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/** Debug-only, local CPU acceptance path. It never opens a call or a network client. */
class CosyVoiceReviewActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: OfflineCosyVoiceEngine? = null
    private var player: MediaPlayer? = null
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var play: Button
    private lateinit var resultFile: File
    private lateinit var wavFile: File
    private var record = JSONObject()
    private val memorySamples = JSONArray()
    private var language = ""
    private var running = false
    private var destroyed = false
    private var wallStarted = 0L
    private var peakPssKiB = 0
    private var peakJavaHeapBytes = 0L
    private val memoryTick = object : Runnable {
        override fun run() {
            if (!running || destroyed) return
            sampleMemory()
            if (memorySamples.length() % 5 == 0) persist()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        language = intent.getStringExtra("review_cosy_language") ?: ""
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || language !in setOf("zh", "ja")) {
            finish(); return
        }
        makeUi()
        val external = getExternalFilesDir(null)
        if (external == null) { status.text = "应用文件目录不可用，未开始验收"; return }
        val directory = File(external, "cosyvoice3")
        if (!directory.isDirectory && !directory.mkdirs()) { status.text = "无法创建验收结果目录"; return }
        resultFile = File(directory, "review-$language.json")
        wavFile = File(directory, "review-$language.wav")
        if (savedInstanceState != null) {
            record = runCatching { JSONObject(resultFile.readText()) }.getOrDefault(JSONObject())
            status.text = "页面已重建，未自动重新验收"
            detail.text = "保留的结果：${record.optString("status", "未知")}\n${resultFile.absolutePath}"
            play.isEnabled = record.optString("status") == "succeeded" && wavFile.isFile
            return
        }
        val text = intent.getStringExtra("review_cosy_text")?.trim()
            ?: if (language == "zh") "今天也辛苦了，我会陪你聊一会儿。" else "コンニチハ。キョウモオツカレサマ。"
        wallStarted = SystemClock.elapsedRealtime()
        record = JSONObject().put("marker", "RESULT").put("language", language)
            .put("voiceId", "ellen_$language").put("modelId", OfflineCosyVoiceEngine.MODEL_ID)
            .put("startedAtEpochMs", System.currentTimeMillis()).put("status", "running")
            .put("stage", "input_check").put("text", text).put("textCharacters", text.codePointCount(0, text.length))
            .put("networkUsed", false).put("humanListeningVerified", false)
            .put("playbackRequested", intent.getBooleanExtra("review_cosy_play", false))
            .put("playbackCompleted", false).put("memorySampleIntervalMs", 1000)
            .put("memoryScope", "whole_app_process").put("memoryIncludesChildProcesses", false)
            .put("memoryPeakIsSampled", true).put("memorySamples", memorySamples)
        running = true
        persist()
        if (text.isBlank() || text.codePointCount(0, text.length) > 100) {
            fail(IllegalArgumentException("验收文字需为1至100个字符")); return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.post(memoryTick)
        engine = OfflineCosyVoiceEngine(this, { message -> stage(message) }, directory)
        stage("正在检查手机本地运行库")
        engine!!.checkNative { result ->
            result.onSuccess {
                record.put("nativeCheckPassed", true)
                val state = engine!!.modelState()
                record.put("installedBytes", state.installedBytes).put("expectedBytes", state.expectedBytes)
                    .put("modelFilesReady", state.ready).put("nativeFilesReady", state.nativeReady)
                    .put("missingModelFiles", JSONArray(state.missing))
                stage("正在开始本地语音合成")
                runCatching {
                    engine!!.synthesize(text, "ellen_$language", language) { output ->
                        output.onSuccess(::verifyOutput).onFailure(::fail)
                    }
                }.onFailure(::fail)
            }.onFailure(::fail)
        }
    }

    private fun makeUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(38), dp(24), dp(24))
            setBackgroundResource(R.drawable.ui_bg_aurora)
        }
        content.addView(TextView(this).apply {
            text = "手机离线语音验收"
            textSize = 22f; setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            text = "准备开始"
            textSize = 15f; setTextColor(0xFFB6E9E9.toInt())
            setPadding(0, dp(24), 0, dp(18))
        }
        detail = TextView(this).apply { textSize = 12f; setTextColor(0xFFCAD5E5.toInt()); setLineSpacing(dp(4).toFloat(), 1f) }
        content.addView(status)
        content.addView(detail)
        play = Button(this).apply {
            text = "播放生成的语音"; isEnabled = false
            setOnClickListener { playOutput() }
        }
        content.addView(play, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(24) })
        content.addView(TextView(this).apply {
            text = "播放完成只说明音频已播完；音色、内容和自然度需实际听后判断。"
            textSize = 11f; setTextColor(0xFF9DACC2.toInt()); setPadding(0, dp(12), 0, dp(20))
        })
        content.addView(Button(this).apply { text = "关闭验收页"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF101925.toInt()); addView(content) })
    }

    private fun stage(message: String) {
        if (destroyed || !running) return
        status.text = message
        record.put("stage", message).put("lastProgressAtEpochMs", System.currentTimeMillis())
        persist()
    }

    private fun verifyOutput(output: OfflineCosyVoiceEngine.Output) {
        if (destroyed) { output.release(); return }
        stage("正在检查生成的本地音频")
        Thread {
            val checked = runCatching {
                val audio = inspectWav(output.wav)
                require(output.totalMs > 0 && output.audioSeconds.isFinite() && output.audioSeconds > 0)
                output.wav.copyTo(wavFile, overwrite = true)
                audio
            }
            output.release()
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                checked.onSuccess { audio ->
                    runCatching {
                        record.put("totalMs", output.totalMs).put("audioSeconds", audio.seconds)
                            .put("reportedAudioSeconds", output.audioSeconds)
                            .put("RTF", output.totalMs / 1000.0 / audio.seconds)
                            .put("wavBytes", wavFile.length()).put("sampleRate", audio.sampleRate)
                            .put("pcmSamples", audio.samples).put("peakAbsPcm", audio.peak)
                            .put("wavPath", wavFile.absolutePath)
                        ConnectionSettings.markOfflineVoiceValidated(this, language)
                        record.put("offlineVoiceValidated", true)
                        complete("succeeded")
                        status.text = "本地合成完成 · 听感尚未确认"
                        detail.text = "耗时 ${output.totalMs} ms\n音频 ${"%.2f".format(java.util.Locale.ROOT, audio.seconds)} 秒\nRTF ${"%.2f".format(java.util.Locale.ROOT, output.totalMs / 1000.0 / audio.seconds)}\n采样峰值 PSS $peakPssKiB KiB\n结果已保留：${resultFile.absolutePath}"
                        play.isEnabled = true
                        if (intent.getBooleanExtra("review_cosy_play", false)) playOutput()
                    }.onFailure(::fail)
                }.onFailure(::fail)
            }
        }.start()
    }

    private fun sampleMemory() {
        try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            val runtime = Runtime.getRuntime()
            val javaHeap = runtime.totalMemory() - runtime.freeMemory()
            peakPssKiB = maxOf(peakPssKiB, info.totalPss)
            peakJavaHeapBytes = maxOf(peakJavaHeapBytes, javaHeap)
            memorySamples.put(JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - wallStarted)
                .put("pssKiB", info.totalPss).put("javaHeapBytes", javaHeap)
                .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize()))
            record.put("peakPssKiB", peakPssKiB).put("peakJavaHeapBytes", peakJavaHeapBytes)
        } catch (_: Exception) { record.put("memoryObservationError", "Debug memory snapshot unavailable") }
    }

    private fun complete(value: String) {
        sampleMemory()
        running = false
        handler.removeCallbacks(memoryTick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        record.put("status", value).put("finishedAtEpochMs", System.currentTimeMillis())
            .put("wallTotalMs", SystemClock.elapsedRealtime() - wallStarted)
        persist()
        Log.i("PRTS-CosyReview", "RESULT language=$language status=$value file=${resultFile.name}")
    }

    private fun fail(error: Throwable) {
        if (destroyed) return
        val safe = (error.message ?: "本地语音验收失败").lineSequence().first().take(300)
            .replace(Regex("(?i)(bearer\\s+|api[_ -]?key\\s*[:=]\\s*)\\S+"), "\$1[redacted]")
        record.put("error", JSONObject().put("type", error.javaClass.simpleName).put("message", safe))
        complete("failed")
        status.text = "验收未完成"
        detail.text = "$safe\n结果已保留，不会自动重试。"
    }

    private fun persist() {
        if (!::resultFile.isInitialized) return
        try {
            val temporary = File(resultFile.parentFile, resultFile.name + ".tmp")
            temporary.writeText(record.toString(2), Charsets.UTF_8)
            if (!temporary.renameTo(resultFile)) { temporary.copyTo(resultFile, overwrite = true); temporary.delete() }
        } catch (_: Exception) { if (::detail.isInitialized) detail.text = "结果文件暂时无法写入" }
    }

    private fun playOutput() {
        if (!::wavFile.isInitialized || !wavFile.isFile) return
        player?.release()
        play.isEnabled = false
        record.put("playbackCompleted", false).put("playbackStatus", "preparing")
        persist()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(wavFile.absolutePath)
                setOnPreparedListener { media ->
                    record.put("playbackStatus", "playing").put("playbackStartedAtEpochMs", System.currentTimeMillis())
                    persist(); media.start(); status.text = "正在播放 · 请实际试听"
                }
                setOnCompletionListener { media ->
                    record.put("playbackCompleted", true).put("playbackStatus", "completed")
                        .put("playbackCompletedAtEpochMs", System.currentTimeMillis())
                    persist(); media.release(); player = null; play.isEnabled = true
                    status.text = "播放完成 · 听感仍需人工确认"
                }
                setOnErrorListener { media, what, extra ->
                    record.put("playbackStatus", "failed").put("playbackError", "$what/$extra")
                    persist(); media.release(); player = null; play.isEnabled = true
                    status.text = "播放失败，合成结果仍已保留"; true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            player?.release(); player = null; play.isEnabled = true
            record.put("playbackStatus", "failed").put("playbackError", "Local MediaPlayer could not prepare WAV")
            persist(); status.text = "无法播放这份本地音频"
        }
    }

    private data class WavInfo(val seconds: Double, val sampleRate: Int, val samples: Long, val peak: Int)

    private fun inspectWav(file: File): WavInfo = RandomAccessFile(file, "r").use { input ->
        fun tag(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun u16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
        require(tag() == "RIFF"); u32(); require(tag() == "WAVE")
        var format = 0; var channels = 0; var rate = 0; var bits = 0
        var audioStart = 0L; var audioBytes = 0L
        while (input.filePointer + 8 <= input.length()) {
            val id = tag(); val bytes = u32(); val start = input.filePointer
            require(bytes >= 0 && start + bytes <= input.length()) { "音频数据不完整" }
            if (id == "fmt ") {
                require(bytes >= 16); format = u16(); channels = u16(); rate = u32().toInt()
                u32(); u16(); bits = u16()
            } else if (id == "data") { audioStart = start; audioBytes = bytes }
            input.seek(start + bytes + (bytes and 1))
        }
        require(format == 1 && channels == 1 && rate == 24000 && bits == 16 && audioBytes > 0 && audioBytes % 2 == 0L) { "生成的音频格式异常" }
        input.seek(audioStart)
        val buffer = ByteArray(16384)
        var remaining = audioBytes
        var peak = 0
        while (remaining > 0) {
            val count = minOf(buffer.size.toLong(), remaining).toInt()
            input.readFully(buffer, 0, count)
            for (offset in 0 until count step 2) {
                val value = ((buffer[offset].toInt() and 255) or (buffer[offset + 1].toInt() shl 8)).toShort().toInt()
                peak = maxOf(peak, abs(value))
            }
            remaining -= count
        }
        require(peak > 0) { "生成的音频为静音" }
        WavInfo(audioBytes / 2.0 / rate, rate, audioBytes / 2, peak)
    }

    override fun onDestroy() {
        if (running && ::resultFile.isInitialized) {
            record.put("error", JSONObject().put("type", "ActivityClosed").put("message", "验收页面已关闭，未自动重试"))
            complete("interrupted")
        }
        if (player != null && ::resultFile.isInitialized) {
            record.put("playbackStatus", "interrupted").put("playbackCompleted", false)
            persist()
        }
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        player?.release(); player = null
        engine?.close(); engine = null
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

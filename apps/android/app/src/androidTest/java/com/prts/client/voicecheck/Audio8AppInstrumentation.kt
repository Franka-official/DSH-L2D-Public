package com.prts.client.voicecheck

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.os.SystemClock
import com.prts.client.audio.LocalVoiceSettings
import com.prts.client.audio.SpeechPlayer
import com.prts.client.audio.SpeechSynthesisRouter
import com.prts.client.audio.SpeechTempo
import com.prts.client.audio.TtsBackendSettings
import com.prts.client.audio.audio8.Audio8Tokenizer
import com.prts.client.audio.audio8.Audio8Native
import com.prts.client.audio.audio8.Audio8ModelStore
import com.prts.client.audio.audio8.OfflineAudio8TtsEngine
import com.prts.client.net.DisplayText
import com.prts.client.net.InAudio
import com.prts.client.net.VoiceProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Normal application UID + real local router/player + foreground Live2D. No LLM or network calls. */
class Audio8AppInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var directory: File
    private lateinit var fixtures: File
    private lateinit var report: JSONObject
    private val current = AtomicReference<CaseState?>()
    private var router: SpeechSynthesisRouter? = null
    private var player: SpeechPlayer? = null
    private class CaseState(val name: String, val started: Long, val wave: RandomAccessFile) {
        val done = CountDownLatch(1)
        val arrivals = JSONArray()
        var firstPcm = -1L; var firstVoice = -1L; var synthesisEnd = -1L; var playbackEnd = -1L
        var frames = 0L; var finalChunks = 0; var sequence = 0; var rate = 0
        var success = false; var error: String? = null; var metrics = JSONObject()
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments);args=arguments?:Bundle();start() }
    private fun persist() { File(directory,"results.json").writeText(report.toString(2),Charsets.UTF_8) }
    override fun onStart() {
        val label=args.getString("result_label")?:"audio8-app-${System.currentTimeMillis()}"
        check(label.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        directory=File(targetContext.getExternalFilesDir(null),"acceptance/$label")
        check(!directory.exists()&&directory.mkdirs())
        report=JSONObject().put("schema","dsh-audio8-app-v1").put("passed",false)
            .put("app_uid",android.os.Process.myUid()).put("app_pid",android.os.Process.myPid())
            .put("llm_requests_started_by_test",0).put("network_requests_started_by_test",0).put("tts_requests_started_by_test",0).put("human_listening_verified",false)
            .put("max_java_heap_bytes",Runtime.getRuntime().maxMemory()).put("started_epoch_ms",System.currentTimeMillis())
        if(args.getString("mode")=="engine_probe") { runEngineProbe();return }
        if(args.getString("mode")=="import_whale_reference") { importWhaleReference();return }
        if(args.getString("mode")=="stage_mnn") { stageMnnCandidate();return }
        if(args.getString("mode")=="mnn_runtime_info") { runRuntimeInfo(true);return }
        if(args.getString("mode")=="runtime_info") { runRuntimeInfo();return }
        if(args.getString("mode")=="affinity") { runAffinityProbe();return }
        if(args.getString("mode")=="affinity_context") { runAffinityContextProbe();return }
        var ok=false
        val preferences=targetContext.getSharedPreferences("prts",android.content.Context.MODE_PRIVATE)
        val codecBackend=args.getString("codec_backend") ?: "ort_cpu"
        val codecPrecision=args.getString("codec_precision") ?: "high"
        check(codecBackend in setOf("ort_cpu","mnn_cpu","mnn_opencl") && codecPrecision in setOf("high","low") && (codecPrecision=="high" || codecBackend=="mnn_opencl"))
        val codecKeys=listOf("audio8_codec_backend","audio8_codec_precision","audio8_codec_model_path")
        val codecPresent=codecKeys.associateWith{preferences.contains(it)}
        val codecBefore=codecKeys.associateWith{preferences.getString(it,null)}
        val codecModelVariant=args.getString("codec_model_variant") ?: "c12"
        check(codecModelVariant in setOf("c12","c24"))
        val codecModel=File(targetContext.filesDir,"audio8-mnn/"+if(codecModelVariant=="c24")"codec_incremental_f32_c24.mnn" else "codec_incremental_f32_c12.mnn")
        val hadThreads=preferences.contains("qwen_cpu_threads")
        val oldThreads=preferences.getInt("qwen_cpu_threads",4)
        val requestedThreads=args.getString("threads")?.toIntOrNull()?:4
        check(requestedThreads in setOf(4,6,8))
        check(preferences.edit().putInt("qwen_cpu_threads",requestedThreads).commit())
        val tuningKeys=listOf("audio8_ar_threads","audio8_codec_threads","audio8_chunk_frames","audio8_slow_ar_threads","audio8_fast_ar_threads")
        val tuningPresent=tuningKeys.associateWith { preferences.contains(it) }
        val tuningBefore=tuningKeys.associateWith { preferences.getInt(it,0) }
        val arThreads=args.getString("ar_threads")?.toIntOrNull()?:1
        val slowArThreads=args.getString("slow_ar_threads")?.toIntOrNull()?:arThreads
        val fastArThreads=args.getString("fast_ar_threads")?.toIntOrNull()?:arThreads
        val codecThreads=args.getString("codec_threads")?.toIntOrNull()?:4
        val chunkFrames=args.getString("chunk_frames")?.toIntOrNull()?:24
        check(arThreads in 1..8 && slowArThreads in 1..8 && fastArThreads in 1..8 && codecThreads in 1..8 && chunkFrames in 1..128)
        check(preferences.edit().putInt("audio8_ar_threads",arThreads).putInt("audio8_codec_threads",codecThreads)
            .putInt("audio8_chunk_frames",chunkFrames).putInt("audio8_slow_ar_threads",slowArThreads).putInt("audio8_fast_ar_threads",fastArThreads).commit())
        report.put("requested_cpu_threads",requestedThreads).put("requested_ar_threads",arThreads)
            .put("requested_codec_threads",codecThreads).put("requested_chunk_frames",chunkFrames)
            .put("requested_ar_slow_threads",slowArThreads).put("requested_ar_fast_threads",fastArThreads)
        try {
            check(preferences.edit().putString("audio8_codec_backend",codecBackend).putString("audio8_codec_precision",codecPrecision)
                .putString("audio8_codec_model_path",codecModel.absolutePath).commit())
            report.put("requested_codec_backend",codecBackend).put("requested_codec_precision",codecPrecision).put("requested_codec_model_variant",codecModelVariant)
                .put("requested_codec_model_path",if(codecBackend=="ort_cpu")JSONObject.NULL else codecModel.absolutePath)
            val root=File(targetContext.getExternalFilesDir(null),"audio8")
            report.put("phase","app_owned_model_installation");persist()
            val installation=Audio8ModelStore.ensureInstalled(targetContext)
            report.put("model_installation",JSONObject().put("phase",installation.phase).put("processed_bytes",installation.processedBytes)
                .put("total_bytes",installation.totalBytes).put("copied_bytes",installation.copiedBytes).put("elapsed_ms",installation.elapsedMs)
                .put("model_root_owner_uid",android.system.Os.stat(root.absolutePath).st_uid).put("app_uid",android.os.Process.myUid()))
            fixtures=File(targetContext.cacheDir,"audio8-check-fixtures").apply{check(isDirectory||mkdirs())}
            context.assets.list("audio8/fixtures").orEmpty().forEach { name ->
                context.assets.open("audio8/fixtures/$name").use{input->File(fixtures,name).outputStream().use{input.copyTo(it)}}
            }
            report.put("phase","tokenizer_exact_check");persist()
            checkTokenizer(root)
            val mode=args.getString("mode")?:"hello"
            if(mode!="tokenizer") {
                check(TtsBackendSettings.load(targetContext).mode==TtsBackendSettings.LOCAL)
                report.put("phase","foreground_start_activity");persist()
                verifyForegroundAndCapture()
                report.put("requested_playback_speed",SpeechTempo.speed(targetContext)).put("requested_pitch",1.0)
                report.put("normal_reference_warmup_after_foreground",OfflineAudio8TtsEngine.referenceWarmupStatus());persist()
                onMain { createSpeechChain() }
                val names=when(mode) { "hello"->listOf("hello");"poem"->listOf("poem");"poem_pair"->listOf("poem","poem");"chat"->listOf("chat_a","chat_b","chat_c","chat_a","chat_b","chat_c");else->error("UnsupportedMode") }
                val cases=JSONArray();report.put("cases",cases)
                names.forEachIndexed { index,name ->
                    report.put("phase","speech_${index}_$name");persist()
                    val text=File(fixtures,"$name.txt").readText(Charsets.UTF_8)
                    val result=runSpeech("$index-$name",text)
                    cases.put(result);persist()
                    check(result.optBoolean("passed")) { "SpeechCaseFailed:$name" }
                }
            }
            report.put("phase","completed").put("passed",true);ok=true
        } catch(error:Throwable) { report.put("phase","failed").put("error",error.toString());android.util.Log.e("PRTS-Audio8Check","Failed",error) }
        finally {
            runCatching { onMain { router?.close();player?.close() } }
            val edit=preferences.edit()
            if(hadThreads)edit.putInt("qwen_cpu_threads",oldThreads)else edit.remove("qwen_cpu_threads")
            tuningKeys.forEach { key -> if(tuningPresent.getValue(key))edit.putInt(key,tuningBefore.getValue(key))else edit.remove(key) }
            codecKeys.forEach { key -> if(codecPresent.getValue(key))edit.putString(key,codecBefore.getValue(key))else edit.remove(key) }
            val restored=edit.commit()
            report.put("thread_preferences_restored",restored).put("codec_preferences_restored",restored)
            report.put("finished_epoch_ms",System.currentTimeMillis());persist()
            finish(if(ok)android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED,
                Bundle().apply {putString("result_path",File(directory,"results.json").absolutePath);putBoolean("passed",ok)})
        }
    }
    /** Exercise model loading and generation independently of the playback UI. */
    private fun runEngineProbe() {
        val gpu = args.getString("gpu") == "true"
        val whale = args.getString("voice") == "whale"
        val preferences = targetContext.getSharedPreferences("prts", android.content.Context.MODE_PRIVATE)
        val keys = listOf("audio8_codec_backend", "audio8_codec_precision", "audio8_codec_model_path")
        val previous = keys.associateWith { preferences.getString(it, null) }
        val engine = OfflineAudio8TtsEngine(targetContext)
        var ok = false
        try {
            val edit = preferences.edit().putString(keys[0], if (gpu) "mnn_opencl" else "ort_cpu")
                .putString(keys[1], if (gpu) "low" else "high")
            if (gpu) edit.putString(keys[2], File(targetContext.filesDir, "audio8-mnn/codec_incremental_f32_c12.mnn").absolutePath)
            check(edit.commit())
            check(engine.modelState().ready)
            val voice = if (whale) LocalVoiceSettings.WHALE_VOICE else LocalVoiceSettings.ELLEN_VOICE
            val text = context.assets.open("audio8/fixtures/hello.txt").bufferedReader().use { it.readText() }
            val cases = JSONArray()
            report.put("mode", "engine_probe").put("cases", cases).put("speaker_playback", false)
            repeat(if (gpu) 2 else 1) { index ->
                val done = java.util.concurrent.CountDownLatch(1)
                val result = java.util.concurrent.atomic.AtomicReference<Result<OfflineAudio8TtsEngine.Output>>()
                val first = java.util.concurrent.atomic.AtomicLong(-1)
                val start = SystemClock.elapsedRealtime()
                val handle = engine.synthesize(text, voice, "zh", onPcm = { chunk ->
                    if (chunk.samples.isNotEmpty()) first.compareAndSet(-1, SystemClock.elapsedRealtime() - start)
                    true
                }) { output -> result.set(output); done.countDown() }
                if (!done.await(180, java.util.concurrent.TimeUnit.SECONDS)) {
                    handle.cancel()
                    error("Audio8 engine probe timed out")
                }
                result.get().getOrThrow().use { output ->
                    check(first.get() >= 0 && output.audioSeconds > 0 && output.nativeReport.optBoolean("natural_eos_evidence"))
                    if (gpu) check(output.nativeReport.optBoolean("gpu_execution_confirmed"))
                    if (whale) check(output.voiceId == "local_whale_reference")
                    if (whale) output.wav.copyTo(File(directory, "whale-native.wav"))
                    cases.put(JSONObject().put("index", index).put("first_pcm_ms", first.get())
                        .put("audio_seconds", output.audioSeconds).put("reference", output.voiceId)
                        .put("native", output.nativeReport))
                    persist()
                }
            }
            ok = true
        } catch (error: Throwable) { report.put("error", error.toString()) }
        finally {
            engine.close()
            val restore = preferences.edit()
            previous.forEach { (key, value) -> if (value == null) restore.remove(key) else restore.putString(key, value) }
            if (!restore.commit()) ok = false
            report.put("phase", if (ok) "completed" else "failed").put("passed", ok)
            persist()
            finish(if (ok) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED,
                Bundle().apply { putBoolean("passed", ok); putString("result_path", File(directory,"results.json").absolutePath) })
        }
    }

    private fun importWhaleReference() {
        val started = SystemClock.elapsedRealtime()
        var ok = false
        try {
            val owned = requireNotNull(targetContext.getExternalFilesDir(null))
            val packageFile = File(owned, "audio8-whale-reference.zip")
            check(packageFile.isFile && packageFile.length() in 1..800_000)
            val before = Audio8ModelStore.referenceDirectory(targetContext, LocalVoiceSettings.ELLEN_VOICE)
            onMain { Audio8ModelStore.importPackage(targetContext, android.net.Uri.fromFile(packageFile)) }
            while (Audio8ModelStore.status().phase == "importing" && SystemClock.elapsedRealtime() - started < 30_000L) {
                SystemClock.sleep(50)
            }
            val state = Audio8ModelStore.status(targetContext)
            val whale = Audio8ModelStore.referenceDirectory(targetContext, LocalVoiceSettings.WHALE_VOICE)
            check(state.ready && state.copiedBytes == 0L) { state.error ?: "ReferenceImportFailed" }
            check(whale.name.startsWith("reference-whale-") && Audio8ModelStore.validReference(whale, checkCodes = true))
            check(before == Audio8ModelStore.referenceDirectory(targetContext, LocalVoiceSettings.ELLEN_VOICE))
            report.put("model_bytes_copied", state.copiedBytes).put("ellen_reference_preserved", true)
                .put("whale_reference_installed", true)
            ok = true
        } catch (error: Exception) { report.put("error", error.toString()) }
        report.put("mode", "import_whale_reference").put("phase", if (ok) "completed" else "failed").put("passed", ok)
        persist()
        finish(if (ok) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED,
            Bundle().apply { putBoolean("passed", ok); putString("result_path", File(directory,"results.json").absolutePath) })
    }

    private fun stageMnnCandidate() {
        val variant=args.getString("codec_model_variant") ?: "c12"
        val basename=if(variant=="c24")"codec_incremental_f32_c24.mnn" else "codec_incremental_f32_c12.mnn"
        val source="/data/local/tmp/dsh-audio8-mnn/$basename"
        val expected=if(variant=="c24")261764152L else 261764152L
        val parent=File(targetContext.filesDir,"audio8-mnn")
        val target=File(parent,basename)
        val partial=File(parent,basename+".part")
        var bytes=0L;var ok=false;var reused=false
        val begin=SystemClock.elapsedRealtime()
        report.put("mode","stage_mnn").put("phase","stage_mnn_candidate").put("codec_model_variant",variant).put("model_chunk_frames",if(variant=="c24")24 else 12)
            .put("source_path",source).put("target_path",target.absolutePath).put("partial_path",partial.absolutePath)
            .put("expected_bytes",expected).put("target_frames_generated_by_test",0).put("pcm_callbacks_started_by_test",0)
            .put("model_sessions_created_by_test",0).put("normal_start_activity_launched_by_test",false)
            .put("thread_preferences_modified_by_test",false).put("transport","UiAutomation_fixed_cat_stdout_fd")
        persist()
        try {
            check(variant in setOf("c12","c24")){"UnsupportedCandidateVariant"}
            check(parent.isDirectory||parent.mkdirs()){"CannotCreateAppOwnedCandidateDirectory"}
            check(android.system.Os.stat(parent.absolutePath).st_uid==android.os.Process.myUid()){"CandidateDirectoryOwnerMismatch"}
            if(target.exists()) {
                check(target.isFile&&target.length()==expected&&android.system.Os.stat(target.absolutePath).st_uid==android.os.Process.myUid()){"ExistingCandidateIsIncompleteOrNotAppOwned"}
                bytes=target.length();reused=true;report.put("reused_complete_candidate",true)
            } else {
                check(!partial.exists()){"ExistingPartialPreserved_NotOverwriting"}
                var last=begin
                ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("cat -- $source")).use { input ->
                    partial.outputStream().use { output ->
                        val buffer=ByteArray(128*1024)
                        while(true) {
                            val count=input.read(buffer);if(count<0)break
                            output.write(buffer,0,count);bytes+=count
                            check(bytes<=expected){"CandidateStreamExceedsExpectedSize"}
                            val now=SystemClock.elapsedRealtime()
                            if(now-last>=500){report.put("bytes_read",bytes).put("elapsed_ms",now-begin);persist();last=now}
                        }
                    }
                }
                check(bytes==expected&&partial.length()==expected){"CandidateStreamIncomplete"}
                check(android.system.Os.stat(partial.absolutePath).st_uid==android.os.Process.myUid()){"PartialOwnerMismatch"}
                check(partial.renameTo(target)){"CannotCommitCandidate"}
            }
            check(target.length()==expected&&target.canRead()){"CandidateCommitVerificationFailed"}
            report.put("phase","completed").put("passed",true).put("complete",true)
                .put("source_bytes_read",if(reused)0L else bytes).put("bytes_copied_this_run",if(reused)0L else bytes)
                .put("target_bytes",target.length()).put("inference_verified",false)
                .put("target_owner_uid",android.system.Os.stat(target.absolutePath).st_uid)
            ok=true
        } catch(error:Throwable) {
            report.put("phase","failed").put("error",error.toString()).put("complete",false)
                .put("bytes_read",bytes).put("partial_preserved",partial.exists()).put("partial_bytes",partial.length())
            android.util.Log.e("PRTS-Audio8Check","MNN candidate staging failed",error)
        } finally {
            report.put("elapsed_ms",SystemClock.elapsedRealtime()-begin).put("finished_epoch_ms",System.currentTimeMillis());persist()
            finish(if(ok)android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED,
                Bundle().apply{putString("result_path",File(directory,"results.json").absolutePath);putBoolean("passed",ok)})
        }
    }
    private fun runRuntimeInfo(mnn: Boolean = false) {
        var ok=false
        report.put("mode",if(mnn)"mnn_runtime_info" else "runtime_info").put("phase","runtime_metadata")
            .put("model_installation_started_by_test",false).put("model_sessions_created_by_test",0)
            .put("tokenizer_loaded_by_test",false).put("normal_start_activity_launched_by_test",false)
            .put("normal_reference_warmup_requested_by_test",false).put("foreground_required_for_metadata",false)
            .put("thread_preferences_modified_by_test",false).put("target_frames_generated_by_test",0)
            .put("pcm_callbacks_started_by_test",0).put("production_execution_provider_changed_by_test",false)
        persist()
        try {
            check(Audio8Native.available){"Audio8NativeUnavailable:${Audio8Native.loadFailure}"}
            val begin=SystemClock.elapsedRealtime()
            report.put("native_runtime",JSONObject(if(mnn)Audio8Native.mnnRuntimeInfo() else Audio8Native.runtimeInfo()))
                .put("runtime_metadata_elapsed_ms",SystemClock.elapsedRealtime()-begin)
                .put("thread_tid",android.os.Process.myTid()).put("thread_name",Thread.currentThread().name)
                .put("phase","completed").put("passed",true)
            ok=true
        } catch(error:Throwable) {
            report.put("phase","failed").put("error",error.toString())
            android.util.Log.e("PRTS-Audio8Check","Runtime metadata failed",error)
        } finally {
            report.put("finished_epoch_ms",System.currentTimeMillis());persist()
            finish(if(ok)android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED,
                Bundle().apply {putString("result_path",File(directory,"results.json").absolutePath);putBoolean("passed",ok)})
        }
    }
    private fun runAffinityContextProbe() {
        var ok=false
        report.put("mode","affinity_context").put("phase","foreground_start_activity")
            .put("normal_start_activity_requested_by_test",true).put("normal_reference_warmup_allowed",true)
            .put("target_text_supplied",false).put("target_frames_generated_by_test",0)
            .put("pcm_callbacks_started_by_test",0).put("thread_preferences_modified_by_test",false)
        persist()
        try {
            verifyForegroundAndCapture()
            val waitStart=SystemClock.elapsedRealtime()
            val history=JSONArray()
            var lastPhase=""
            while(true) {
                val warmup=OfflineAudio8TtsEngine.referenceWarmupStatus()
                val phase=warmup.optString("phase")
                if(phase!=lastPhase) {
                    history.put(JSONObject().put("observed_elapsed_ms",SystemClock.elapsedRealtime()).put("status",warmup))
                    lastPhase=phase
                }
                report.put("phase","waiting_for_normal_reference_warmup").put("normal_reference_warmup",warmup)
                    .put("normal_warmup_phase_history",history)
                persist()
                if(phase=="ready"||phase=="failed"||SystemClock.elapsedRealtime()-waitStart>=60_000)break
                SystemClock.sleep(100)
            }
            val warmup=OfflineAudio8TtsEngine.referenceWarmupStatus()
            report.put("normal_warmup_wait_ms",SystemClock.elapsedRealtime()-waitStart)
                .put("normal_warmup_wait_terminal",warmup.optString("phase") in setOf("ready","failed"))
                .put("normal_reference_warmup_before_probe",warmup)
            check(Audio8Native.available){"Audio8NativeUnavailable:${Audio8Native.loadFailure}"}
            val probes=JSONArray();report.put("context_probes",probes)
            fun observe(name:String):JSONObject {
                val started=SystemClock.elapsedRealtime()
                val resumed=shell("dumpsys activity activities").lineSequence().map{it.trim()}.filter {
                    it.contains("topResumedActivity")||it.contains("mResumedActivity")
                }.toList()
                val power=checkNotNull(targetContext.getSystemService(android.os.PowerManager::class.java))
                val keyguard=checkNotNull(targetContext.getSystemService(android.app.KeyguardManager::class.java))
                val front=JSONObject().put("resumed_activity_lines",JSONArray(resumed)).put("interactive",power.isInteractive)
                    .put("keyguard_locked",keyguard.isKeyguardLocked).put("device_locked",keyguard.isDeviceLocked)
                return JSONObject().put("context",name).put("app_uid",android.os.Process.myUid())
                    .put("app_pid",android.os.Process.myPid()).put("thread_tid",android.os.Process.myTid())
                    .put("thread_name",Thread.currentThread().name).put("started_elapsed_ms",started)
                    .put("foreground_at_probe",front)
                    .put("normal_reference_warmup_before",OfflineAudio8TtsEngine.referenceWarmupStatus())
                    .put("native",JSONObject(Audio8Native.affinityProbe()))
                    .put("completed_elapsed_ms",SystemClock.elapsedRealtime())
                    .put("normal_reference_warmup_after",OfflineAudio8TtsEngine.referenceWarmupStatus())
            }
            report.put("phase","probe_instrumentation_thread");persist()
            probes.put(observe("instrumentation_thread"));persist()
            val field=OfflineAudio8TtsEngine::class.java.getDeclaredField("WORKER")
            field.isAccessible=true
            val worker=field.get(null) as java.util.concurrent.ExecutorService
            report.put("phase","probe_normal_audio8_worker").put("worker_reflection_field",field.name);persist()
            val future=worker.submit(java.util.concurrent.Callable { observe("normal_audio8_worker") })
            probes.put(future.get(30,TimeUnit.SECONDS));persist()
            val verified=(0 until probes.length()).all { index ->
                val native=probes.getJSONObject(index).getJSONObject("native")
                native.optBoolean("passed",false)&&native.optBoolean("original_mask_restored",false)&&
                    native.optInt("model_sessions_created",-1)==0&&native.optInt("target_frames_generated",-1)==0&&
                    native.optInt("pcm_samples",-1)==0
            }
            report.put("diagnostic_contexts_completed",probes.length()).put("affinity_all_verified",verified)
                .put("normal_reference_warmup_after_probes",OfflineAudio8TtsEngine.referenceWarmupStatus())
            check(probes.length()==2){"MissingContextProbe"}
            report.put("phase","completed").put("passed",verified);ok=verified
        } catch(error:Throwable) {
            report.put("phase","failed").put("error",error.toString())
            android.util.Log.e("PRTS-Audio8Check","Affinity context probe failed",error)
        } finally {
            report.put("finished_epoch_ms",System.currentTimeMillis());persist()
            finish(if(ok)android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED,
                Bundle().apply {putString("result_path",File(directory,"results.json").absolutePath);putBoolean("passed",ok)})
        }
    }
    private fun runAffinityProbe() {
        var ok=false
        report.put("mode","affinity").put("phase","self_thread_affinity_probe")
            .put("model_installation_started_by_test",false).put("tokenizer_loaded_by_test",false)
            .put("normal_start_activity_launched_by_test",false).put("normal_reference_warmup_requested_by_test",false)
            .put("foreground_required_for_probe",false).put("pcm_callbacks_started_by_test",0)
            .put("thread_preferences_modified_by_test",false)
        persist()
        try {
            val power=checkNotNull(targetContext.getSystemService(android.os.PowerManager::class.java))
            val keyguard=checkNotNull(targetContext.getSystemService(android.app.KeyguardManager::class.java))
            val resumed=shell("dumpsys activity activities").lineSequence().map{it.trim()}.filter {
                it.contains("topResumedActivity")||it.contains("mResumedActivity")
            }.toList()
            report.put("current_activity_observation",JSONObject().put("resumed_activity_lines",JSONArray(resumed))
                .put("interactive",power.isInteractive).put("keyguard_locked",keyguard.isKeyguardLocked)
                .put("device_locked",keyguard.isDeviceLocked).put("app_front_state_claimed",false))
            check(Audio8Native.available){"Audio8NativeUnavailable:${Audio8Native.loadFailure}"}
            report.put("native_runtime",JSONObject(Audio8Native.runtimeInfo()))
            val started=SystemClock.elapsedRealtime()
            val probe=JSONObject(Audio8Native.affinityProbe())
            report.put("affinity_probe",probe).put("probe_elapsed_ms",SystemClock.elapsedRealtime()-started)
            check(probe.optBoolean("passed",false)){"AffinityProbeFailed"}
            report.put("phase","completed").put("passed",true);ok=true
        } catch(error:Throwable) {
            report.put("phase","failed").put("error",error.toString())
            android.util.Log.e("PRTS-Audio8Check","Affinity probe failed",error)
        } finally {
            report.put("finished_epoch_ms",System.currentTimeMillis());persist()
            finish(if(ok)android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED,
                Bundle().apply {putString("result_path",File(directory,"results.json").absolutePath);putBoolean("passed",ok)})
        }
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun verifyForegroundAndCapture() {
        val packageName=targetContext.packageName
        val power=checkNotNull(targetContext.getSystemService(android.os.PowerManager::class.java))
        val keyguard=checkNotNull(targetContext.getSystemService(android.app.KeyguardManager::class.java))
        fun screenState()=JSONObject().put("interactive",power.isInteractive).put("keyguard_locked",keyguard.isKeyguardLocked)
            .put("device_locked",keyguard.isDeviceLocked).put("keyguard_secure",keyguard.isKeyguardSecure)
        val proof=JSONObject().put("confirmed",false).put("screenshot_saved",false).put("screen_before",screenState())
        report.put("foreground_proof",proof);persist()
        if(!power.isInteractive) { proof.put("normal_wake_output",shell("input keyevent 224"));SystemClock.sleep(300) }
        proof.put("screen_after_wake",screenState());persist()
        check(power.isInteractive){"ScreenNotInteractive"}
        check(!keyguard.isKeyguardLocked&&!keyguard.isDeviceLocked) {
            if(keyguard.isKeyguardSecure) "SecureKeyguardLocked_ManualUnlockRequired" else "KeyguardLocked_ManualUnlockRequired"
        }
        val command="am start -W -n $packageName/com.prts.client.ui.StartActivity"
        val startOutput=shell(command)
        proof.put("start_command",command).put("start_output",startOutput.take(8000));persist()
        val deadline=SystemClock.elapsedRealtime()+10_000
        var matched=false
        while(true) {
            val activity=shell("dumpsys activity activities")
            val lines=activity.lineSequence().map {it.trim()}.filter {
                it.contains("topResumedActivity")||it.contains("mResumedActivity")
            }.toList()
            val top=lines.filter {it.contains("topResumedActivity")}
            val authoritative=if(top.isNotEmpty())top else lines.filter {it.contains("mResumedActivity")}
            matched=authoritative.any {it.contains("$packageName/")}
            proof.put("resumed_activity_lines",JSONArray(lines)).put("confirmed",matched)
                .put("observed_elapsed_ms",SystemClock.elapsedRealtime())
            persist()
            if(matched||SystemClock.elapsedRealtime()>=deadline)break
            SystemClock.sleep(250)
        }
        check(matched){"TargetAppForegroundNotConfirmed"}
        // Allow the resumed Activity to render; one screenshot records the actual surface.
        SystemClock.sleep(800)
        proof.put("screen_before_capture",screenState());persist()
        check(power.isInteractive&&!keyguard.isKeyguardLocked&&!keyguard.isDeviceLocked){"ScreenLockedOrInactiveBeforeCapture"}
        val bitmap=checkNotNull(uiAutomation.takeScreenshot()){ "ForegroundScreenshotUnavailable" }
        val image=File(directory,"foreground.png")
        try {
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            proof.put("screenshot_width",bitmap.width).put("screenshot_height",bitmap.height)
        } finally { bitmap.recycle() }
        proof.put("screenshot_saved",image.isFile&&image.length()>0).put("screenshot_path",image.absolutePath)
        check(proof.getBoolean("screenshot_saved")) {"ForegroundScreenshotWriteFailed"}
        report.put("normal_start_activity_resumed",true);persist()
    }
    private fun checkTokenizer(root:File) {
        val begin=SystemClock.elapsedRealtime()
        val tokenizer=Audio8Tokenizer(File(root,"model/tokenizer/tokenizer.json").absolutePath)
        val loadMs=SystemClock.elapsedRealtime()-begin
        val tests=JSONArray(File(fixtures,"tokenizer-check.json").readText())
        val checks=JSONArray()
        for(i in 0 until tests.length()) {
            val test=tests.getJSONObject(i);val ids=test.getJSONArray("ids")
            val expected=LongArray(ids.length()){ids.getLong(it)};val actual=tokenizer.encode(test.getString("text"))
            val valid=expected.contentEquals(actual);checks.put(JSONObject().put("kind","encode").put("index",i).put("passed",valid))
            check(valid){"TokenizerEncodeMismatch:$i"}
        }
        val directory = Audio8ModelStore.referenceDirectory(targetContext)
        val reference=JSONObject(File(directory,"reference.json").readText())
        val data=ByteBuffer.wrap(File(directory,"reference.codes.i64.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val codes=Array(10){LongArray(reference.getJSONArray("shape").getInt(1)){data.long}}
        val cases=JSONObject(File(fixtures,"cases.json").readText())
        val names=cases.keys()
        while(names.hasNext()) {
            val name=names.next();val info=cases.getJSONObject(name)
            val prompt=tokenizer.buildPrompt(File(fixtures,info.getString("text_file")).readText(),reference.getString("reference_text"),codes)
            val prefix=tokenizer.buildReferencePrefix(reference.getString("reference_text"),codes)
            var prefixMatches=prefix.positions==prompt.reusablePrefixPositions&&prefix.positions==prefix.reusablePrefixPositions
            for(row in 0 until 11)for(column in 0 until prefix.positions)
                if(prefix.values[row*prefix.positions+column]!=prompt.values[row*prompt.positions+column])prefixMatches=false
            checks.put(JSONObject().put("kind","reference_prefix").put("case",name).put("passed",prefixMatches).put("positions",prefix.positions))
            check(prefixMatches){"ReferencePrefixMismatch:$name"}
        }
        report.put("tokenizer",JSONObject().put("passed",true).put("load_ms",loadMs).put("checks",checks).put("encode_tests",tests.length()).put("prompt_tests",cases.length()))
        persist()
    }
    private fun createSpeechChain() {
        player=SpeechPlayer(onStart={},onAudioStarted={current.get()?.let {if(it.firstVoice<0)it.firstVoice=SystemClock.elapsedRealtime()}},
            onMouth={},onIdle={},onError={reason->current.get()?.let{it.error=reason;it.done.countDown()}},
            onAudioEnded={_,success->current.get()?.let{it.success=success;it.playbackEnd=SystemClock.elapsedRealtime();it.done.countDown()}},
            onPlaybackProgress={_,_,_,_->},playbackSpeed={SpeechTempo.speed(targetContext)})
        val voice = if (args.getString("voice") == "whale") LocalVoiceSettings.WHALE_VOICE else LocalVoiceSettings.ELLEN_VOICE
        router=SpeechSynthesisRouter(targetContext,VoiceProfile(LocalVoiceSettings.MODE_ID,voice,"zh"),
            onPayload={current.get()?.let{it.error="UnexpectedNonPcm";it.done.countDown()}},
            onNotice={reason->current.get()?.let{it.error=reason;it.done.countDown()}},onBusyChanged={},
            onLocalPayload={it.close();current.get()?.let{state->state.error="UnexpectedFilePayload";state.done.countDown()}},
            onStreamingPcm={message,chunk->
                val state=checkNotNull(current.get());val at=SystemClock.elapsedRealtime()
                if(state.firstPcm<0&&chunk.samples.isNotEmpty())state.firstPcm=at
                check(chunk.sequence==state.sequence++){"PcmSequenceMismatch"};state.rate=chunk.sampleRate
                if(chunk.final)state.finalChunks++
                val accepted=checkNotNull(player).enqueuePcm(message,chunk)
                if(accepted) {
                    val bytes=ByteBuffer.allocate(chunk.samples.size*2).order(ByteOrder.LITTLE_ENDIAN)
                    chunk.samples.forEach {bytes.putShort((it.coerceIn(-1f,1f)*32767f).roundToInt().toShort())}
                    state.wave.write(bytes.array());state.frames+=chunk.samples.size
                }
                state.arrivals.put(JSONObject().put("at_ms",at-state.started).put("samples",chunk.samples.size).put("final",chunk.final).put("accepted",accepted))
                accepted
            },onStreamingComplete={message,result->
                current.get()?.let {state->
                    state.synthesisEnd=SystemClock.elapsedRealtime()
                    result.getOrNull()?.let {output->state.metrics=JSONObject(output.nativeReport.toString());output.wav.copyTo(File(directory,"${state.name}-native.wav"),overwrite=false)}
                    if(result.isFailure){state.error=result.exceptionOrNull().toString();state.done.countDown()}
                }
                checkNotNull(player).completeStreaming(message,result)
            })
        check(checkNotNull(router).usesLocalModel&&checkNotNull(router).available){"Audio8ModelOrRouteUnavailable"}
    }
    private fun runSpeech(name:String,text:String):JSONObject {
        val wave=RandomAccessFile(File(directory,"$name-played-pcm.wav"),"rw").apply{setLength(44);seek(44)}
        val warmupAtRequest=OfflineAudio8TtsEngine.referenceWarmupStatus()
        val state=CaseState(name,SystemClock.elapsedRealtime(),wave);current.set(state)
        try {
            report.put("tts_requests_started_by_test",report.optInt("tts_requests_started_by_test")+1);persist()
            onMain {checkNotNull(router).enqueue(InAudio(display_text=DisplayText(text=text,spoken_text=text)))}
            check(state.done.await(args.getString("observation_seconds")?.toLong()?:360,TimeUnit.SECONDS)){"PlaybackObservationExpired"}
            val native=state.metrics
            val expectedEngine=if(report.optString("requested_codec_backend","ort_cpu")=="ort_cpu")"audio8_ort_android_cpu" else "audio8_ort_ar_mnn_codec_android"
            val passed=state.error==null&&state.success&&state.frames>0&&state.finalChunks==1&&native.optString("engine")==expectedEngine&&native.optBoolean("natural_eos_evidence")&&!native.optBoolean("frame_cap_reached")
            return JSONObject().put("name",name).put("passed",passed).put("error",state.error?:JSONObject.NULL)
                .put("normal_reference_warmup_at_request",warmupAtRequest)
                .put("normal_reference_warmup_after_playback",OfflineAudio8TtsEngine.referenceWarmupStatus())
                .put("first_pcm_ms",state.firstPcm.takeIf{it>=0}?.minus(state.started)?:JSONObject.NULL)
                .put("first_audio_consumed_ms",state.firstVoice.takeIf{it>=0}?.minus(state.started)?:JSONObject.NULL)
                .put("synthesis_done_ms",state.synthesisEnd.takeIf{it>=0}?.minus(state.started)?:JSONObject.NULL)
                .put("playback_done_ms",state.playbackEnd.takeIf{it>=0}?.minus(state.started)?:JSONObject.NULL)
                .put("audio_seconds",state.frames.toDouble()/state.rate.coerceAtLeast(1)).put("sample_rate",state.rate)
                .put("final_chunks",state.finalChunks).put("native",native).put("arrivals",state.arrivals)
        } finally {
            val bytes=wave.length()-44;val rate=state.rate.takeIf{it>0}?:44100
            val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt((bytes+36).toInt()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(rate).putInt(rate*2).putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes.toInt())
            wave.seek(0);wave.write(header.array());wave.close();current.set(null)
        }
    }
    private fun onMain(block:()->Unit){if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())block()else runOnMainSync{block()}}
}

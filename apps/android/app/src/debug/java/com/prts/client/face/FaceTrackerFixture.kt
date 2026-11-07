package com.prts.client.face

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Explicit debug-only, bundled-public-portrait test. Never writes to the runtime observation filter,
 * never opens a camera, never sends network requests, and never stores a user photo.
 */
object FaceTrackerFixture {
    private const val ASSET = "face-fixture-astronaut.png"
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    @JvmStatic fun run(context: Context, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || !running.compareAndSet(false, true)) {
            main.post { onDone(false) }; return
        }
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val proof = JSONObject().put("kind", "explicit_public_portrait_fixture")
                .put("fixture_asset", ASSET).put("camera_opened", false).put("network_requests", 0)
                .put("runtime_observation_modified", false).put("delegate", "CPU")
            var source: Bitmap? = null
            var rgb: Bitmap? = null
            var success = false
            try {
                source = app.assets.open(ASSET).use { BitmapFactory.decodeStream(it) }
                    ?: error("fixture_decode_failed")
                require(source.width == 512 && source.height == 512)
                rgb = source.copy(Bitmap.Config.ARGB_8888, false) ?: error("fixture_rgb_failed")
                val initStart = SystemClock.elapsedRealtime()
                val buffer = LocalFaceTracker.loadModelBuffer(app)
                val detector = LocalFaceTracker.createDetector(app, buffer)
                proof.put("initialization_ms", SystemClock.elapsedRealtime() - initStart)
                val rows = JSONArray()
                // MPImage.close recycles its Bitmap. Reuse one MPImage while the fixture runs,
                // then close it once, rather than wrapping a recycled bitmap on repeat 2.
                val image = BitmapImageBuilder(rgb).build()
                try {
                    for (i in 0..5) {
                        val started = SystemClock.elapsedRealtimeNanos()
                        val result = detector.detectForVideo(image, 1000L + i * 333L)
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                        val faceCount = result.faceLandmarks().size
                        val matrix = result.facialTransformationMatrixes().orElse(emptyList()).firstOrNull()
                        val pose = FacePoseMath.fromColumnMajor(matrix)
                        rows.put(JSONObject().put("index", i).put("warmup", i == 0).put("inference_ms", elapsed)
                            .put("face_count", faceCount)
                            .put("landmark_count", result.faceLandmarks().firstOrNull()?.size ?: 0)
                            .put("blendshape_count", result.faceBlendshapes().orElse(emptyList()).firstOrNull()?.size ?: 0)
                            .put("pose_valid", pose != null)
                            .put("yaw_deg", pose?.yaw ?: JSONObject.NULL)
                            .put("pitch_deg", pose?.pitch ?: JSONObject.NULL)
                            .put("roll_deg", pose?.roll ?: JSONObject.NULL))
                    }
                } finally { image.close(); detector.close() }
                proof.put("samples", rows)
                success = (0 until rows.length()).all { rows.getJSONObject(it).getInt("face_count") == 1 &&
                    rows.getJSONObject(it).getInt("landmark_count") == 478 &&
                    rows.getJSONObject(it).getInt("blendshape_count") == 52 }
                proof.put("phase", if (success) "passed" else "unexpected_fixture_result")
            } catch (_: Exception) {
                proof.put("phase", "failed").put("error", "fixture_or_detector_unavailable")
            } catch (_: LinkageError) {
                proof.put("phase", "failed").put("error", "detector_native_dependency_unavailable")
            } finally {
                if (rgb !== source) rgb?.takeUnless { it.isRecycled }?.recycle()
                source?.takeUnless { it.isRecycled }?.recycle()
                File(app.filesDir, "face-public-fixture-proof.json").writeText(proof.toString(2), Charsets.UTF_8)
                Log.i("PRTS-FaceFixture", "explicit public fixture phase=${proof.optString("phase")} samples=${proof.optJSONArray("samples")?.length() ?: 0}")
                running.set(false)
                main.post { onDone(success) }
                worker.shutdown()
            }
        }
    }
}

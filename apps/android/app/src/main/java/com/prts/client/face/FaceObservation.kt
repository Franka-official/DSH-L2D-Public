package com.prts.client.face

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/** Local, short-lived visible face measurements. No identity or inferred mental emotion.
 * The wire protocol calls scores "probability"; MediaPipe blendshape scores are not calibrated
 * probabilities. Left/right always refer to the subject, with unmirrored detector input.
 */
data class FaceObservation(
    val version: Int = 1,
    val ageMs: Long,
    val faceCount: Int = 1,
    val confidence: Double? = null,
    val smileProbability: Double?,
    val leftEyeOpenProbability: Double?,
    val rightEyeOpenProbability: Double?,
    val mouthOpen: Double?,
    val headYawDeg: Double?,
    val headPitchDeg: Double?,
    val headRollDeg: Double?,
)

data class FacePose(val yaw: Double, val pitch: Double, val roll: Double)

/** Mathematical Euler convention Rz(roll)*Ry(yaw)*Rx(pitch), column-major canonical-face matrix.
 * A malformed, reflected or sheared matrix produces no pose instead of fabricated zero angles.
 * Actual camera orientation/signs still require the phone test; this is not an identity estimate.
 */
object FacePoseMath {
    fun fromColumnMajor(matrix: FloatArray?): FacePose? {
        if (matrix == null || matrix.size != 16 || matrix.any { !it.isFinite() }) return null
        val columns = (0..2).map { c -> DoubleArray(3) { r -> matrix[c * 4 + r].toDouble() } }
        for (col in columns) {
            val norm = sqrt(col.sumOf { it * it })
            if (norm < 1e-6) return null
            for (i in col.indices) col[i] /= norm
        }
        fun dot(a: Int, b: Int) = (0..2).sumOf { columns[a][it] * columns[b][it] }
        if (abs(dot(0, 1)) > .08 || abs(dot(0, 2)) > .08 || abs(dot(1, 2)) > .08) return null
        val a = columns[0]; val b = columns[1]; val c = columns[2]
        val determinant = a[0] * (b[1] * c[2] - b[2] * c[1]) -
            b[0] * (a[1] * c[2] - a[2] * c[1]) + c[0] * (a[1] * b[2] - a[2] * b[1])
        if (determinant < .9) return null
        val yaw = asin((-a[2]).coerceIn(-1.0, 1.0))
        // At gimbal lock the pitch/roll decomposition is ambiguous; omit the whole estimate.
        if (abs(kotlin.math.cos(yaw)) < .05) return null
        val pitch = atan2(b[2], c[2]); val roll = atan2(a[1], a[0])
        val degrees = 180.0 / Math.PI
        val values = listOf(yaw * degrees, pitch * degrees, roll * degrees)
        if (values.any { abs(it) > 90.0 }) return null
        return FacePose(values[0], values[1], values[2])
    }
}

/** Thread-safe fake-clock-testable filter. Only one face is eligible for a message snapshot. */
class FaceObservationFilter(private val nowMs: () -> Long) {
    private data class State(val at: Long, val values: List<Double?>)
    private var state: State? = null

    @Synchronized fun reset() { state = null }

    @Synchronized fun accept(
        capturedElapsedMs: Long,
        faceCount: Int,
        scores: Map<String, Double>,
        matrix: FloatArray?,
    ) {
        val now = nowMs()
        if (faceCount != 1 || capturedElapsedMs > now || now - capturedElapsedMs > 3000) {
            state = null
            return
        }
        val previous = state
        if (previous != null && capturedElapsedMs <= previous.at) return
        fun score(name: String): Double? = scores[name]?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
        val smileL = score("mouthSmileLeft"); val smileR = score("mouthSmileRight")
        val pose = FacePoseMath.fromColumnMajor(matrix)
        val raw = listOf(
            if (smileL != null && smileR != null) (smileL + smileR) * .5 else null,
            score("eyeBlinkLeft")?.let { 1.0 - it },
            score("eyeBlinkRight")?.let { 1.0 - it },
            score("jawOpen"), pose?.yaw, pose?.pitch, pose?.roll,
        )
        // Reset across a missing-face gap; otherwise follow using actual elapsed time, not FPS.
        val elapsed = previous?.let { (capturedElapsedMs - it.at) / 1000.0 }
        val filtered = raw.mapIndexed { index, value ->
            val old = previous?.values?.get(index)
            if (value == null || old == null || elapsed == null || elapsed > 1.0) value else {
                val tau = if (index < 4) .18 else .25
                old + (1.0 - exp(-elapsed / tau)) * (value - old)
            }
        }
        state = State(capturedElapsedMs, filtered)
    }

    @Synchronized fun snapshot(maxAgeMs: Long = 3000): FaceObservation? {
        val value = state ?: return null
        val age = nowMs() - value.at
        if (age < 0 || age > maxAgeMs.coerceIn(0, 3000)) return null
        val v = value.values
        return FaceObservation(ageMs = age, smileProbability = v[0],
            leftEyeOpenProbability = v[1], rightEyeOpenProbability = v[2], mouthOpen = v[3],
            headYawDeg = v[4], headPitchDeg = v[5], headRollDeg = v[6])
    }
}

/** Camera backpressure/lifecycle admission: one in flight, zero queued, at most about 3 Hz. */
class FaceFrameGate {
    data class Ticket(val epoch: Long)
    data class Admission(val ticket: Ticket?, val clearObservation: Boolean, val dropReason: String? = null)
    private var epoch = 0L
    private var generation: Long? = null
    private var lastCapture: Long? = null
    private var busy = false
    private var closed = false

    @Synchronized fun enter(front: Boolean, cameraGeneration: Long, capturedMs: Long, intervalMs: Long = 333): Admission {
        if (closed) return Admission(null, false, "closed")
        if (!front) { reset(); return Admission(null, true, "rear") }
        val changed = generation != cameraGeneration
        if (changed) { epoch++; generation = cameraGeneration; lastCapture = null }
        val tooSoon = lastCapture?.let { capturedMs - it < intervalMs.coerceIn(333, 1000) } ?: false
        if (busy) return Admission(null, changed, "busy")
        if (capturedMs < 0) return Admission(null, changed, "timestamp")
        if (tooSoon) return Admission(null, changed, "rate")
        busy = true
        lastCapture = capturedMs
        return Admission(Ticket(epoch), changed)
    }
    @Synchronized fun valid(ticket: Ticket): Boolean = !closed && epoch == ticket.epoch
    @Synchronized fun release() { busy = false }
    @Synchronized fun reset() { epoch++; generation = null; lastCapture = null }
    @Synchronized fun close(): Boolean {
        if (closed) return false
        closed = true; reset()
        return true
    }
    @Synchronized fun isClosed(): Boolean = closed
}

/** Measured processing-cost policy; engineering thresholds, not a claim of phone benchmark results.
 * Keep the original 640 input ceiling until real-device quality comparisons justify a smaller one.
 */
class FaceWorkBudget {
    data class Plan(val intervalMs: Long, val maxSide: Int = 640, val mode: String)
    private var callBusy = false
    private var costEma: Double? = null
    private var processorBusy = false
    private var fastSamples = 0
    @Synchronized fun setCallBusy(busy: Boolean) { callBusy = busy }
    @Synchronized fun plan(): Plan = when {
        callBusy -> Plan(1000, mode = "call-busy")
        processorBusy -> Plan(1000, mode = "processor-busy")
        else -> Plan(333, mode = "normal")
    }
    @Synchronized fun recordProcessing(elapsedMs: Double, warmed: Boolean = true) {
        if (!warmed || !elapsedMs.isFinite() || elapsedMs !in 0.0..10_000.0) return
        costEma = costEma?.let { it + .25 * (elapsedMs - it) } ?: elapsedMs
        if (costEma!! >= 120.0) { processorBusy = true; fastSamples = 0 }
        else if (processorBusy && costEma!! < 75.0) {
            if (++fastSamples >= 4) { processorBusy = false; fastSamples = 0 }
        } else fastSamples = 0
    }
    @Synchronized fun resetTiming() { costEma = null; processorBusy = false; fastSamples = 0 }
}

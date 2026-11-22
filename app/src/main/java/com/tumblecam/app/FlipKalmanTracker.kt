package com.tumblecam.app

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.hypot

/** Coordinates are normalized 0..1 in the upright image. */
data class Joint(val x: Float, val y: Float, val confidence: Float)

data class TrackState(
    val joints: Map<Int, Joint>,
    val com: PointF,
    val path: List<PointF>,
    val coasting: Boolean,
    val coastFrames: Int,
    val subjectLocked: Boolean
)

/** Constant-velocity Kalman filter for one axis; two of these form the 2D filter. */
class KalmanAxis(private val accelVar: Float, private val measVar: Float) {
    var pos = 0f
        private set
    var vel = 0f
        private set
    private var p00 = 1f
    private var p01 = 0f
    private var p10 = 0f
    private var p11 = 1f

    fun reset(p: Float) { pos = p; vel = 0f; p00 = 0.01f; p01 = 0f; p10 = 0f; p11 = 4f }

    fun predict(dt: Float) {
        pos += vel * dt
        val n00 = p00 + dt * (p01 + p10) + dt * dt * p11 + accelVar * dt * dt * dt * dt / 4f
        val n01 = p01 + dt * p11 + accelVar * dt * dt * dt / 2f
        val n10 = p10 + dt * p11 + accelVar * dt * dt * dt / 2f
        val n11 = p11 + accelVar * dt * dt
        p00 = n00; p01 = n01; p10 = n10; p11 = n11
    }

    fun update(z: Float) {
        val s = p00 + measVar
        val k0 = p00 / s
        val k1 = p10 / s
        val innovation = z - pos
        pos += k0 * innovation
        vel += k1 * innovation
        val n00 = (1f - k0) * p00
        val n01 = (1f - k0) * p01
        val n10 = p10 - k1 * p00
        val n11 = p11 - k1 * p01
        p00 = n00; p01 = n01; p10 = n10; p11 = n11
    }
}

/**
 * ML Kit STREAM_MODE pose -> torso center of mass (shoulder/hip midpoints, no face) -> 2D Kalman.
 * Predicts through up to [MAX_COAST] frames without a valid measurement.
 * All state is touched on the main thread (ML Kit listeners run there).
 */
class FlipKalmanTracker(
    private val onState: (TrackState?) -> Unit,
    private val requestFrame: () -> Unit
) {
    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
    )
    private val kx = KalmanAxis(8f, 0.0004f)
    private val ky = KalmanAxis(8f, 0.0004f)
    private var active = false
    private var gated = false
    private var coast = 0
    private var lastNanos = 0L
    private var lastJoints: Map<Int, Joint> = emptyMap()
    private var anchorX = 0f
    private var anchorY = 0f

    fun process(bitmap: Bitmap) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { pose ->
                handle(pose.allPoseLandmarks.associate {
                    it.landmarkType to Joint(it.position.x / w, it.position.y / h, it.inFrameLikelihood)
                })
            }
            .addOnFailureListener { handle(emptyMap()) }
    }

    /** Restrict tracking to the person near this point (normalized). */
    fun lockTo(p: PointF) {
        kx.reset(p.x); ky.reset(p.y)
        active = true; gated = true; coast = 0; lastNanos = 0L
        lastJoints = emptyMap(); anchorX = p.x; anchorY = p.y
    }

    fun unlock() { gated = false }

    fun reset() { active = false; gated = false; coast = 0; lastJoints = emptyMap(); lastNanos = 0L }

    fun close() = detector.close()

    private fun mid(a: Joint?, b: Joint?): PointF? {
        val ok = listOfNotNull(a, b).filter { it.confidence >= MIN_CONF }
        if (ok.isEmpty()) return null
        return PointF(ok.map { it.x }.average().toFloat(), ok.map { it.y }.average().toFloat())
    }

    private fun measureCom(j: Map<Int, Joint>): PointF? {
        val s = mid(j[PoseLandmark.LEFT_SHOULDER], j[PoseLandmark.RIGHT_SHOULDER]) ?: return null
        val h = mid(j[PoseLandmark.LEFT_HIP], j[PoseLandmark.RIGHT_HIP]) ?: return null
        return PointF((s.x + h.x) / 2f, (s.y + h.y) / 2f)
    }

    private fun handle(joints: Map<Int, Joint>) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.033f else ((now - lastNanos) / 1e9f).coerceIn(0.008f, 0.1f)
        lastNanos = now
        if (active) { kx.predict(dt); ky.predict(dt) }
        val m = measureCom(joints)
        val accept = m != null && (!gated || !active || hypot(m.x - kx.pos, m.y - ky.pos) < GATE)
        if (m != null && accept) {
            if (!active) { kx.reset(m.x); ky.reset(m.y); active = true } else { kx.update(m.x); ky.update(m.y) }
            coast = 0
            lastJoints = joints
            anchorX = kx.pos; anchorY = ky.pos
        } else if (active) {
            coast++
            if (coast > MAX_COAST) { reset(); onState(null); requestFrame(); return }
        }
        if (active) emit() else onState(null)
        requestFrame()
    }

    private fun emit() {
        val dx = kx.pos - anchorX
        val dy = ky.pos - anchorY
        val shown = if (coast == 0) lastJoints else lastJoints.mapValues { (_, j) -> j.copy(x = j.x + dx, y = j.y + dy) }
        val path = (0..12).map { i -> PointF(kx.pos + kx.vel * i / 30f, ky.pos + ky.vel * i / 30f) }
        onState(TrackState(shown, PointF(kx.pos, ky.pos), path, coast > 0, coast, gated))
    }

    companion object {
        const val MAX_COAST = 15
        private const val MIN_CONF = 0.5f
        private const val GATE = 0.3f
    }
}

package com.hereliesaz.sphereslam.attitude

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A short history of device-attitude samples that answers "what was (or will be) the attitude at
 * time `t`?" — the attitude feed for per-display-frame overlays such as the coverage glow.
 *
 * Feed it every rotation-vector sample ([push]); read it from the render thread with [quaternionAt] /
 * [deviceToWorldAt] / [worldFromCameraAt]:
 *
 * - a target time **inside** the history is answered by slerp between the two bracketing samples, so
 *   an overlay can be timed to the camera frame actually on screen (a *negative* lead), which is what
 *   keeps it glued to the preview during a fast pan;
 * - a target time **after** the newest sample is extrapolated at the constant angular velocity of the
 *   last two samples, for at most [maxExtrapolationNs] (a positive lead, or simply covering the gap
 *   between sensor events at display rate);
 * - a target time **before** the oldest sample returns the oldest sample.
 *
 * Velocity is not extrapolated across a sensor gap longer than [maxSampleGapNs] (the newest sample
 * is returned as-is), so a paused sensor never spins the overlay.
 *
 * Quaternions are `[x, y, z, w]`, body→world, Android's rotation-vector convention (for
 * `TYPE_ROTATION_VECTOR` the world frame is East-North-Up). Timestamps are `SensorEvent.timestamp`
 * nanoseconds, i.e. the `SystemClock.elapsedRealtimeNanos()` time base — query with that clock,
 * **not** `System.nanoTime()` / Choreographer frame time, which stop during deep sleep.
 *
 * Thread-safe: one writer (the sensor thread) and any number of readers. Reads write into
 * caller-supplied arrays and allocate nothing.
 *
 * @param capacity samples retained, `>= 2`. The default (64) covers ~300 ms at `SENSOR_DELAY_FASTEST`.
 * @param maxExtrapolationNs forward-prediction horizon cap past the newest sample, `>= 0`.
 * @param maxSampleGapNs the longest gap between the last two samples that still yields a velocity.
 */
class AttitudeExtrapolator @JvmOverloads constructor(
    val capacity: Int = DEFAULT_CAPACITY,
    val maxExtrapolationNs: Long = DEFAULT_MAX_EXTRAPOLATION_NS,
    val maxSampleGapNs: Long = DEFAULT_MAX_SAMPLE_GAP_NS,
) {
    init {
        require(capacity >= 2) { "capacity must be >= 2, was $capacity" }
        require(maxExtrapolationNs >= 0) { "maxExtrapolationNs must be >= 0, was $maxExtrapolationNs" }
        require(maxSampleGapNs > 0) { "maxSampleGapNs must be > 0, was $maxSampleGapNs" }
    }

    private val lock = Any()
    private val quats = FloatArray(capacity * 4)
    private val times = LongArray(capacity)
    private var head = -1 // index of the newest sample
    private var count = 0

    /** Samples currently held. */
    val size: Int get() = synchronized(lock) { count }

    /** Timestamp of the newest sample, or null when empty. */
    val newestTimestampNs: Long? get() = synchronized(lock) { if (count == 0) null else times[head] }

    /** Forget every sample (e.g. on sensor restart). */
    fun clear() = synchronized(lock) {
        head = -1
        count = 0
    }

    /** Add [sample]; see [push]. */
    fun push(sample: AttitudeSample) = push(sample.quaternion, sample.timestampNs)

    /**
     * Add a body→world unit quaternion `[x, y, z, w]` sampled at [timestampNs]. Non-finite or
     * zero-length quaternions are ignored; a sample not newer than the newest one replaces nothing
     * and is ignored (sensor events are monotonic). The quaternion is normalized and stored on the
     * same hemisphere as its predecessor so slerp always takes the short way.
     */
    fun push(quaternion: FloatArray, timestampNs: Long) {
        require(quaternion.size >= 4) { "quaternion must be [x, y, z, w]" }
        var x = quaternion[0]; var y = quaternion[1]; var z = quaternion[2]; var w = quaternion[3]
        if (!(x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite())) return
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 1e-6f) return
        x /= n; y /= n; z /= n; w /= n
        synchronized(lock) {
            if (count > 0) {
                if (timestampNs <= times[head]) return
                val p = head * 4
                if (x * quats[p] + y * quats[p + 1] + z * quats[p + 2] + w * quats[p + 3] < 0f) {
                    x = -x; y = -y; z = -z; w = -w
                }
            }
            head = (head + 1) % capacity
            val o = head * 4
            quats[o] = x; quats[o + 1] = y; quats[o + 2] = z; quats[o + 3] = w
            times[head] = timestampNs
            if (count < capacity) count++
        }
    }

    /**
     * The body→world attitude at [targetTimeNs], written to [out] (`[x, y, z, w]`, length >= 4).
     *
     * @return false (and [out] untouched) when no sample has been pushed.
     */
    fun quaternionAt(targetTimeNs: Long, out: FloatArray): Boolean {
        require(out.size >= 4) { "out must hold [x, y, z, w]" }
        synchronized(lock) {
            if (count == 0) return false
            val newest = head
            if (targetTimeNs >= times[newest] || count == 1) {
                if (count == 1 || targetTimeNs <= times[newest]) {
                    copyQuat(newest, out)
                    return true
                }
                val prev = (newest - 1 + capacity) % capacity
                val dt = times[newest] - times[prev]
                if (dt <= 0 || dt > maxSampleGapNs) {
                    copyQuat(newest, out)
                    return true
                }
                val horizon = minOf(targetTimeNs - times[newest], maxExtrapolationNs)
                extrapolate(prev, newest, horizon.toDouble() / dt.toDouble(), out)
                return true
            }
            // Walk back to the sample at or before the target.
            var later = newest
            for (k in 1 until count) {
                val earlier = (newest - k + capacity) % capacity
                if (times[earlier] <= targetTimeNs) {
                    val span = (times[later] - times[earlier]).toDouble()
                    val f = if (span <= 0.0) 1.0 else (targetTimeNs - times[earlier]) / span
                    slerp(earlier, later, f, out)
                    return true
                }
                later = earlier
            }
            copyQuat(later, out) // older than the whole history: the oldest sample
            return true
        }
    }

    /**
     * The device→world rotation at [targetTimeNs] as a row-major 3×3 (`v_world = R · v_device`, the
     * `SensorManager.getRotationMatrixFromVector` layout) written to [out] (length >= 9).
     *
     * @return false when no sample has been pushed.
     */
    fun deviceToWorldAt(targetTimeNs: Long, out: FloatArray): Boolean {
        require(out.size >= 9) { "out must hold a row-major 3x3" }
        val q = scratch.get()!!
        if (!quaternionAt(targetTimeNs, q)) return false
        quatToMat(q[0], q[1], q[2], q[3], out)
        return true
    }

    /**
     * The world-from-camera rotation at [targetTimeNs] — `deviceToWorld · cameraFromDeviceᵀ`, row-major
     * 3×3 whose columns are the camera's GL axes (x right, y up, z backward) in world coordinates —
     * written to [out] (length >= 9). This is what
     * [com.hereliesaz.sphereslam.CoverageGlowProjection]'s full-rotation projection consumes.
     *
     * @param cameraFromDevice row-major 3×3 GL-camera-from-device ([DeviceCameraRotation]); for an
     *   overlay drawn on a display-oriented surface use
     *   [DeviceCameraRotation.cameraFromDeviceForDisplayFrame] of the current display rotation.
     * @return false when no sample has been pushed.
     */
    fun worldFromCameraAt(targetTimeNs: Long, cameraFromDevice: FloatArray, out: FloatArray): Boolean {
        require(cameraFromDevice.size == 9) { "cameraFromDevice must be a row-major 3x3" }
        require(out.size >= 9) { "out must hold a row-major 3x3" }
        val q = scratch.get()!!
        if (!quaternionAt(targetTimeNs, q)) return false
        val r = matScratch.get()!!
        quatToMat(q[0], q[1], q[2], q[3], r)
        // out = R · Cᵀ  →  out[i][j] = Σk R[i][k] · C[j][k]
        for (i in 0..2) for (j in 0..2) {
            out[i * 3 + j] = r[i * 3] * cameraFromDevice[j * 3] +
                r[i * 3 + 1] * cameraFromDevice[j * 3 + 1] +
                r[i * 3 + 2] * cameraFromDevice[j * 3 + 2]
        }
        return true
    }

    private fun copyQuat(i: Int, out: FloatArray) {
        val o = i * 4
        out[0] = quats[o]; out[1] = quats[o + 1]; out[2] = quats[o + 2]; out[3] = quats[o + 3]
    }

    /** Slerp from sample [a] to sample [b] by [f] in `[0, 1]`. */
    private fun slerp(a: Int, b: Int, f: Double, out: FloatArray) {
        val pa = a * 4; val pb = b * 4
        val ax = quats[pa].toDouble(); val ay = quats[pa + 1].toDouble()
        val az = quats[pa + 2].toDouble(); val aw = quats[pa + 3].toDouble()
        val bx = quats[pb].toDouble(); val by = quats[pb + 1].toDouble()
        val bz = quats[pb + 2].toDouble(); val bw = quats[pb + 3].toDouble()
        val d = (ax * bx + ay * by + az * bz + aw * bw).coerceIn(-1.0, 1.0)
        val wa: Double
        val wb: Double
        if (d > 0.9995) {
            wa = 1.0 - f; wb = f
        } else {
            val theta = acos(d)
            val s = sin(theta)
            wa = sin((1.0 - f) * theta) / s
            wb = sin(f * theta) / s
        }
        writeNormalized(wa * ax + wb * bx, wa * ay + wb * by, wa * az + wb * bz, wa * aw + wb * bw, out)
    }

    /**
     * Continue the rotation from sample [a] to sample [b] past [b] by [ratio] times that step:
     * `q = Δ^ratio · q_b` with the world-frame step `Δ = q_b · q_a⁻¹`.
     */
    private fun extrapolate(a: Int, b: Int, ratio: Double, out: FloatArray) {
        val pa = a * 4; val pb = b * 4
        // Δ = q_b ⊗ conj(q_a)
        val ax = -quats[pa].toDouble(); val ay = -quats[pa + 1].toDouble()
        val az = -quats[pa + 2].toDouble(); val aw = quats[pa + 3].toDouble()
        val bx = quats[pb].toDouble(); val by = quats[pb + 1].toDouble()
        val bz = quats[pb + 2].toDouble(); val bw = quats[pb + 3].toDouble()
        var dx = bw * ax + bx * aw + by * az - bz * ay
        var dy = bw * ay - bx * az + by * aw + bz * ax
        var dz = bw * az + bx * ay - by * ax + bz * aw
        var dw = bw * aw - bx * ax - by * ay - bz * az
        if (dw < 0) { dx = -dx; dy = -dy; dz = -dz; dw = -dw }
        val sinHalf = sqrt(dx * dx + dy * dy + dz * dz)
        if (sinHalf < 1e-9) {
            writeNormalized(bx, by, bz, bw, out)
            return
        }
        val half = kotlin.math.atan2(sinHalf, dw) * ratio
        val s = sin(half) / sinHalf
        val ex = dx * s; val ey = dy * s; val ez = dz * s; val ew = cos(half)
        // q = E ⊗ q_b
        writeNormalized(
            ew * bx + ex * bw + ey * bz - ez * by,
            ew * by - ex * bz + ey * bw + ez * bx,
            ew * bz + ex * by - ey * bx + ez * bw,
            ew * bw - ex * bx - ey * by - ez * bz,
            out,
        )
    }

    private fun writeNormalized(x: Double, y: Double, z: Double, w: Double, out: FloatArray) {
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 1e-12 || abs(n - 1.0) < 1e-12) {
            out[0] = x.toFloat(); out[1] = y.toFloat(); out[2] = z.toFloat(); out[3] = w.toFloat()
            return
        }
        out[0] = (x / n).toFloat(); out[1] = (y / n).toFloat(); out[2] = (z / n).toFloat(); out[3] = (w / n).toFloat()
    }

    // Per-reader-thread scratch so concurrent readers never share buffers and reads never allocate
    // after the first call on a thread.
    private val scratch = ThreadLocal.withInitial { FloatArray(4) }
    private val matScratch = ThreadLocal.withInitial { FloatArray(9) }

    companion object {
        /** Default history length. */
        const val DEFAULT_CAPACITY: Int = 64

        /** Default forward-prediction cap: 50 ms. */
        const val DEFAULT_MAX_EXTRAPOLATION_NS: Long = 50_000_000L

        /** Default longest sensor gap that still yields a velocity: 100 ms. */
        const val DEFAULT_MAX_SAMPLE_GAP_NS: Long = 100_000_000L

        private fun quatToMat(x: Float, y: Float, z: Float, w: Float, out: FloatArray) {
            val xx = x * x; val yy = y * y; val zz = z * z
            val xy = x * y; val xz = x * z; val yz = y * z
            val wx = w * x; val wy = w * y; val wz = w * z
            out[0] = 1f - 2f * (yy + zz); out[1] = 2f * (xy - wz); out[2] = 2f * (xz + wy)
            out[3] = 2f * (xy + wz); out[4] = 1f - 2f * (xx + zz); out[5] = 2f * (yz - wx)
            out[6] = 2f * (xz - wy); out[7] = 2f * (yz + wx); out[8] = 1f - 2f * (xx + yy)
        }
    }
}

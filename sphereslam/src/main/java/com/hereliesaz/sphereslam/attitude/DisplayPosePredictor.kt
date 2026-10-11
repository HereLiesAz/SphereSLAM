package com.hereliesaz.sphereslam.attitude

/**
 * Display-rate pose between vision updates: holds the last vision pose together with the device
 * attitude **at the frame that produced it**, and on every draw rotates that pose by the attitude
 * change since then — camera centre held fixed, exactly like the gyro bridge
 * ([AttitudeRotationBridge] / `:reloc` `PhotosphereTrackingSession`'s IMU bridge), but without
 * re-referencing, so it can be called any number of times per vision update and never accumulates.
 *
 * Why: a vision solve (e.g. full-frame KPM) lands 40–150+ ms after its frame, at analyzer rate. Drawn
 * as-is, the overlay lags the preview by that much and steps at analyzer rate. Rotating the newest
 * vision pose by `attitude(now) · attitude(frame)⁻¹` on the GL thread makes it follow the device at
 * display rate and removes the solve latency for rotation (the dominant motion of a hand-held
 * phone); translation still updates at vision rate.
 *
 * Math (identical to the bridge): device delta `ΔR_d = R(q_now)ᵀ · R(q_frame)`, camera delta
 * `ΔR = C · ΔR_d · Cᵀ` with [C] = `cameraFromDevice`, predicted pose `[ΔR·R | ΔR·t]`.
 *
 * Conventions: poses are column-major 16, camera-from-world (GL eye frame, the frame `cameraFromDevice`
 * maps into). Quaternions are `[x, y, z, w]` body→world of any fixed world frame (rotation vector or
 * game rotation vector — but the same sensor for the reference and the current attitude).
 *
 * Thread-safe: [update] / [clear] from the analyzer, [predictInto] from the GL thread. [predictInto]
 * allocates nothing.
 */
class DisplayPosePredictor {

    private val lock = Any()
    private val pose = FloatArray(16)
    private val refQuat = FloatArray(4)
    private val cameraFromDevice = FloatArray(9)
    private var hasPoseValue = false
    private var poseTimestamp = 0L

    // Draw-side scratch (guarded by lock).
    private val nowQuat = FloatArray(4)
    private val rRef = FloatArray(9)
    private val rNow = FloatArray(9)
    private val tmp = FloatArray(9)
    private val delta = FloatArray(9)

    /** True once a pose has been [update]d (and not [clear]ed). */
    val hasPose: Boolean get() = synchronized(lock) { hasPoseValue }

    /** Camera timestamp of the held vision pose (as passed to [update]), or null. */
    val poseTimestampNs: Long? get() = synchronized(lock) { if (hasPoseValue) poseTimestamp else null }

    /**
     * Hold a new vision pose.
     *
     * @param cameraFromWorld column-major 16 camera-from-world pose solved from a frame.
     * @param frameAttitude body→world quaternion `[x, y, z, w]` **at that frame's capture time** (e.g.
     *   [AttitudeExtrapolator.quaternionAt] of the frame timestamp; the latest sample before the solve
     *   is an approximation).
     * @param cameraFromDevice row-major 3×3 GL-camera-from-device of the frame the pose is solved in
     *   ([DeviceCameraRotation.cameraFromDevice]) — the same matrix the bridge uses.
     * @param frameTimestampNs the frame's camera timestamp, kept for [poseTimestampNs].
     * @throws IllegalArgumentException for wrong sizes; non-finite input is ignored (nothing changes).
     */
    @JvmOverloads
    fun update(
        cameraFromWorld: FloatArray,
        frameAttitude: FloatArray,
        cameraFromDevice: FloatArray,
        frameTimestampNs: Long = 0L,
    ) {
        require(cameraFromWorld.size == 16) { "pose must be column-major length 16" }
        require(frameAttitude.size == 4) { "attitude must be a quaternion [x, y, z, w]" }
        require(cameraFromDevice.size == 9) { "cameraFromDevice must be a row-major 3x3" }
        if (cameraFromWorld.any { !it.isFinite() } || frameAttitude.any { !it.isFinite() } ||
            cameraFromDevice.any { !it.isFinite() }
        ) {
            return
        }
        val n = kotlin.math.sqrt(
            frameAttitude[0] * frameAttitude[0] + frameAttitude[1] * frameAttitude[1] +
                frameAttitude[2] * frameAttitude[2] + frameAttitude[3] * frameAttitude[3],
        )
        if (n < 1e-6f) return
        synchronized(lock) {
            cameraFromWorld.copyInto(pose)
            for (i in 0..3) refQuat[i] = frameAttitude[i] / n
            cameraFromDevice.copyInto(this.cameraFromDevice)
            poseTimestamp = frameTimestampNs
            hasPoseValue = true
        }
    }

    /** [update] with an [AttitudeSample] as the frame attitude (its timestamp is not used). */
    @JvmOverloads
    fun update(cameraFromWorld: FloatArray, frameAttitude: AttitudeSample, cameraFromDevice: FloatArray, frameTimestampNs: Long = 0L) =
        update(cameraFromWorld, frameAttitude.quaternion, cameraFromDevice, frameTimestampNs)

    /** Drop the held pose (e.g. on tracking loss); [predictInto] returns false until the next [update]. */
    fun clear() = synchronized(lock) { hasPoseValue = false }

    /**
     * The held pose rotated by the attitude change from its frame to [currentAttitude], written to
     * [out] (column-major 16). Allocation-free.
     *
     * @param currentAttitude body→world quaternion `[x, y, z, w]` now (same sensor as the frame's).
     * @return false (and [out] untouched) without a held pose or for an invalid attitude.
     */
    fun predictInto(currentAttitude: FloatArray, out: FloatArray): Boolean {
        require(currentAttitude.size >= 4) { "attitude must be a quaternion [x, y, z, w]" }
        require(out.size >= 16) { "out must hold a column-major 4x4" }
        synchronized(lock) {
            if (!hasPoseValue) return false
            for (i in 0..3) {
                if (!currentAttitude[i].isFinite()) return false
                nowQuat[i] = currentAttitude[i]
            }
            return rotateHeldInto(out)
        }
    }

    /**
     * [predictInto] with the attitude at [targetTimeNs] from [attitude] — interpolated inside its
     * history or extrapolated a short way past it (pass the display frame time, plus any lead).
     *
     * @return false without a held pose or without attitude samples.
     */
    fun predictInto(attitude: AttitudeExtrapolator, targetTimeNs: Long, out: FloatArray): Boolean {
        require(out.size >= 16) { "out must hold a column-major 4x4" }
        synchronized(lock) {
            if (!hasPoseValue) return false
            if (!attitude.quaternionAt(targetTimeNs, nowQuat)) return false
            return rotateHeldInto(out)
        }
    }

    /** Allocating convenience over [predictInto]: the predicted pose, or null. */
    fun predict(currentAttitude: FloatArray): FloatArray? {
        val out = FloatArray(16)
        return if (predictInto(currentAttitude, out)) out else null
    }

    /** The camera-frame rotation delta `ΔR` (row-major 3×3) [predictInto] would apply, or null. */
    fun cameraRotationDelta(currentAttitude: FloatArray): FloatArray? {
        require(currentAttitude.size >= 4) { "attitude must be a quaternion [x, y, z, w]" }
        synchronized(lock) {
            if (!hasPoseValue || (0..3).any { !currentAttitude[it].isFinite() }) return null
            for (i in 0..3) nowQuat[i] = currentAttitude[i]
            if (!computeDelta()) return null
            return delta.copyOf()
        }
    }

    // Caller holds lock; nowQuat is set.
    private fun rotateHeldInto(out: FloatArray): Boolean {
        if (!computeDelta()) return false
        // [ΔR·R | ΔR·t], column-major; bottom row unchanged.
        for (col in 0 until 4) {
            val c0 = pose[col * 4]; val c1 = pose[col * 4 + 1]; val c2 = pose[col * 4 + 2]
            for (row in 0 until 3) {
                out[col * 4 + row] = delta[row * 3] * c0 + delta[row * 3 + 1] * c1 + delta[row * 3 + 2] * c2
            }
            out[col * 4 + 3] = pose[col * 4 + 3]
        }
        return true
    }

    // Caller holds lock. delta = C · (R_nowᵀ · R_ref) · Cᵀ
    private fun computeDelta(): Boolean {
        if (!quatToMat(refQuat, rRef) || !quatToMat(nowQuat, rNow)) return false
        // tmp = R_nowᵀ · R_ref
        for (i in 0..2) for (j in 0..2) {
            tmp[i * 3 + j] = rNow[i] * rRef[j] + rNow[3 + i] * rRef[3 + j] + rNow[6 + i] * rRef[6 + j]
        }
        val c = cameraFromDevice
        // rNow (reused) = C · tmp
        for (i in 0..2) for (j in 0..2) {
            rNow[i * 3 + j] = c[i * 3] * tmp[j] + c[i * 3 + 1] * tmp[3 + j] + c[i * 3 + 2] * tmp[6 + j]
        }
        // delta = (C · tmp) · Cᵀ
        for (i in 0..2) for (j in 0..2) {
            delta[i * 3 + j] = rNow[i * 3] * c[j * 3] + rNow[i * 3 + 1] * c[j * 3 + 1] + rNow[i * 3 + 2] * c[j * 3 + 2]
        }
        return true
    }

    private companion object {
        /** Normalized quaternion → row-major 3×3; false for a near-zero quaternion. */
        fun quatToMat(q: FloatArray, out: FloatArray): Boolean {
            val n = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            if (n < 1e-6f) return false
            val x = q[0] / n; val y = q[1] / n; val z = q[2] / n; val w = q[3] / n
            val xx = x * x; val yy = y * y; val zz = z * z
            val xy = x * y; val xz = x * z; val yz = y * z
            val wx = w * x; val wy = w * y; val wz = w * z
            out[0] = 1f - 2f * (yy + zz); out[1] = 2f * (xy - wz); out[2] = 2f * (xz + wy)
            out[3] = 2f * (xy + wz); out[4] = 1f - 2f * (xx + zz); out[5] = 2f * (yz - wx)
            out[6] = 2f * (xz - wy); out[7] = 2f * (yz + wx); out[8] = 1f - 2f * (xx + yy)
            return true
        }
    }
}

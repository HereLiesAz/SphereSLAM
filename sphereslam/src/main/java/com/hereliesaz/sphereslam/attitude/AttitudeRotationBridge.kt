package com.hereliesaz.sphereslam.attitude

import com.hereliesaz.sphereslam.math.RotationMath

/**
 * Carries a held camera-from-world pose through a brief vision dropout using only device attitude:
 * rotation from the gyro, camera **centre held fixed** (`[ΔR·R | ΔR·t]`). It never estimates
 * translation — that would need double-integrated accelerometer data — so keep the bridge window
 * short.
 *
 * Incremental: [markReference] records the attitude at which the last rendered pose was valid;
 * [advance] rotates that pose by the attitude change since then and re-references to "now", so a
 * consumer that feeds each bridged pose back in (as `:reloc`'s `RobustTrackingLoop` does with its
 * last rendered pose) never applies the same rotation twice.
 *
 * Poses are column-major 16, camera-from-world, in the camera frame [cameraFromDevice] maps into
 * (OpenGL convention by default; see [DeviceCameraRotation]). The `cameraFromDevice` rotation is
 * fixed for the bridge's life — create a new bridge (or [clear]) when the frame you track in changes
 * orientation, because the reference attitude and the held pose were both captured under the old one.
 *
 * Thread-safe (all methods synchronize).
 *
 * @param cameraFromDevice row-major 3×3 from the device body frame to the pose's camera frame
 *   ([DeviceCameraRotation.cameraFromDevice]); identity for a rear camera solved in a natural-
 *   orientation display frame.
 */
class AttitudeRotationBridge(cameraFromDevice: FloatArray = RotationMath.IDENTITY_3X3) {

    private val cameraFromDevice: FloatArray = cameraFromDevice.copyOf()
    private var reference: FloatArray? = null

    init {
        require(cameraFromDevice.size == 9) { "cameraFromDevice must be a row-major 3x3 (length 9)" }
        require(cameraFromDevice.all { it.isFinite() }) { "cameraFromDevice must be finite" }
    }

    /** True once [markReference] has been called (and not [clear]ed). */
    val hasReference: Boolean get() = synchronized(this) { reference != null }

    /** Record [attitudeQuat] `[x, y, z, w]` as the attitude at which the last rendered pose was valid. Ignores non-finite input. */
    fun markReference(attitudeQuat: FloatArray) {
        require(attitudeQuat.size == 4) { "attitude must be a quaternion [x, y, z, w]" }
        if (attitudeQuat.any { !it.isFinite() }) return
        synchronized(this) { reference = RotationMath.normalize(attitudeQuat) }
    }

    /** [markReference] from a sample; a null sample clears the reference (nothing to bridge from). */
    fun markReference(sample: AttitudeSample?) {
        if (sample == null) clear() else markReference(sample.quaternion)
    }

    /** Forget the reference; [advance] returns null until the next [markReference]. */
    fun clear() {
        synchronized(this) { reference = null }
    }

    /**
     * The camera-frame rotation `ΔR` since the reference (`C · R(conj(qNow)·qRef) · Cᵀ`, row-major
     * 3×3), such that a held pose becomes `ΔR · pose`; null without a reference or a valid attitude.
     */
    fun cameraRotationDelta(attitudeQuat: FloatArray?): FloatArray? {
        if (attitudeQuat == null || attitudeQuat.size != 4 || attitudeQuat.any { !it.isFinite() }) return null
        val ref = synchronized(this) { reference } ?: return null
        return DeviceCameraRotation.cameraDelta(cameraFromDevice, RotationMath.cameraRotationDelta(ref, attitudeQuat))
    }

    /**
     * Rotate [lastPose] by the attitude change since the reference, holding its camera centre, then
     * re-reference to [attitudeQuat]. Null (and the reference unchanged) without a reference or a
     * valid attitude.
     */
    fun advance(lastPose: FloatArray, attitudeQuat: FloatArray?): FloatArray? {
        require(lastPose.size == 16) { "pose must be column-major length 16" }
        synchronized(this) {
            val delta = cameraRotationDelta(attitudeQuat) ?: return null
            reference = RotationMath.normalize(attitudeQuat!!)
            return RotationMath.rotateAboutCameraCentre(lastPose, delta)
        }
    }

    /**
     * A `(lastRenderedPose) -> bridgedPose?` function for `:reloc`'s
     * `RobustTrackingLoop(bridgeRotatedPose = ...)`, reading the current attitude from [currentSample]
     * on each call.
     */
    fun bridgeFunction(currentSample: () -> AttitudeSample?): (FloatArray) -> FloatArray? =
        { last -> advance(last, currentSample()?.quaternion) }
}

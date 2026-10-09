package com.hereliesaz.sphereslam.reloc

/**
 * Minimal [EarlyPosePredictor]: holds the last visually-corrected pose and, between corrections,
 * rotates it by the device-attitude change since that correction. Rotation-only — the camera
 * **centre** is held fixed (a pure rotation about the camera), because attitude alone carries no
 * translation. That makes it a short-horizon bridge for solve lag and brief off-page glances, not an
 * odometry source: it drifts with any real camera translation, so the caller should keep its
 * window short (the tracking loop's bridge timeout does this).
 *
 * Poses are **column-major 16, camera-from-map, OpenGL eye frame** (the [SphereSlamSession] /
 * [RobustTrackingLoop] convention). Attitudes are unit quaternions `[x, y, z, w]` giving the device
 * orientation in a fixed world frame (e.g. Android's rotation-vector sensor), as consumed by
 * [RotationDeltaMath.cameraRotationDelta].
 *
 * The device-frame delta is mapped into the camera frame through [cameraFromDevice]. The default
 * identity is correct for a rear camera on a device in its natural orientation, where Android's
 * device axes (x right, y up, z out of the screen) coincide with the GL eye axes (camera looking
 * down −z). Supply the fixed rotation for any other mounting / display rotation.
 *
 * Not thread-safe; drive it from the same worker as the session.
 *
 * @param cameraFromDevice row-major 3×3 rotation from the device sensor frame to the GL camera frame.
 */
@ExperimentalSphereSlamRelocApi
class AttitudePosePredictor(
    cameraFromDevice: FloatArray = IDENTITY_3X3,
) : EarlyPosePredictor {

    private val cameraFromDevice: FloatArray = cameraFromDevice.copyOf()

    init {
        require(cameraFromDevice.size == 9) { "cameraFromDevice must be a row-major 3x3 (length 9)" }
        require(cameraFromDevice.all { it.isFinite() }) { "cameraFromDevice must be finite" }
    }

    private var referencePose: FloatArray? = null
    private var referenceAttitude: FloatArray? = null

    override val hasReference: Boolean
        get() = referencePose != null

    override fun correct(columnMajorView: FloatArray, attitudeQuat: FloatArray) {
        require(columnMajorView.size == 16) { "pose must be column-major length 16" }
        require(attitudeQuat.size == 4) { "attitude must be a quaternion [x, y, z, w]" }
        if (columnMajorView.any { !it.isFinite() } || attitudeQuat.any { !it.isFinite() }) return
        referencePose = columnMajorView.copyOf()
        referenceAttitude = RotationDeltaMath.normalize(attitudeQuat)
    }

    override fun predict(attitudeQuat: FloatArray): FloatArray? {
        val pose = referencePose ?: return null
        val from = referenceAttitude ?: return null
        require(attitudeQuat.size == 4) { "attitude must be a quaternion [x, y, z, w]" }
        if (attitudeQuat.any { !it.isFinite() }) return null
        return rotateAboutCameraCentre(pose, cameraDelta(from, attitudeQuat))
    }

    override fun reset() {
        referencePose = null
        referenceAttitude = null
    }

    /** The attitude delta re-expressed in the camera frame: `C · ΔR_device · Cᵀ` (row-major 3×3). */
    private fun cameraDelta(from: FloatArray, to: FloatArray): FloatArray {
        val deviceDelta = RotationDeltaMath.cameraRotationDelta(from, to)
        return RotationDeltaMath.multiplyMat3(
            RotationDeltaMath.multiplyMat3(cameraFromDevice, deviceDelta),
            RotationDeltaMath.transposeMat3(cameraFromDevice),
        )
    }

    internal companion object {
        val IDENTITY_3X3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        /**
         * Left-multiply a column-major camera-from-map [view] by the row-major 3×3 [delta]:
         * `[ΔR·R | ΔR·t]`. The camera centre `−Rᵀt` is unchanged, so this is a pure rotation of the
         * camera in place. Pure; unit-tested.
         */
        fun rotateAboutCameraCentre(view: FloatArray, delta: FloatArray): FloatArray {
            val m = FloatArray(16)
            for (row in 0 until 3) for (col in 0 until 3) m[col * 4 + row] = delta[row * 3 + col]
            m[15] = 1f
            return PoseMath.multiply(m, view)
        }
    }
}

package com.hereliesaz.sphereslam.attitude

import com.hereliesaz.sphereslam.math.RotationMath

/**
 * The fixed rotation from the device **body** frame (Android sensor axes: x right, y up, z out of
 * the screen, in the device's natural orientation) to a **rear** camera's frame — settling which
 * angle to use, since three different "rotation" numbers are in play on Android:
 *
 * | Quantity | Source | Meaning |
 * | --- | --- | --- |
 * | display rotation `d` | `Display.getRotation()` × 90 | how far the device is turned CCW from natural |
 * | sensor orientation `s` | `CameraCharacteristics.SENSOR_ORIENTATION` | CW rotation that makes the raw buffer upright in natural orientation; fixed per camera |
 * | CameraX `rotationDegrees` `k` | `ImageInfo.rotationDegrees` | CW rotation that makes the buffer upright for the current target rotation; for a rear camera `(s − d) mod 360` |
 *
 * The camera frame is the frame **your pose is solved in**, which is set by which image you track:
 *
 * - **Raw sensor buffer** (`ImageProxy` planes as delivered): `cameraFromDevice = R_z(s)` —
 *   independent of how the device is held, because the sensor is bolted to the body.
 * - **Display-upright image** (the buffer rotated CW by `k`, i.e. what the preview shows):
 *   `cameraFromDevice = R_z(d)` = [bodyToDisplay].
 * - **Any image rotated CW by `r`**: `cameraFromDevice = R_z(s − r)` ([cameraFromDevice]).
 *
 * Passing CameraX `rotationDegrees` itself as the angle is **wrong** in general (it coincides with
 * the raw-buffer answer only at `d = 0`); GraffitiXR's `GyroOrientationBridge` documented exactly
 * that open question.
 *
 * Results are row-major 3×3, `v_camera = M · v_device`, in the **OpenGL** camera convention (x
 * right, y up, looking down −z) — the convention of `:reloc`'s `AttitudePosePredictor` and
 * [AttitudeRotationBridge]. The rear camera looks along body −z, so no axis flip is needed beyond the
 * roll about z. For an OpenCV camera frame (y down, z forward) conjugate by `diag(1, −1, −1)`, which
 * turns `R_z(θ)` into `R_z(−θ)` ([toOpenCv]).
 *
 * `R_z(d)` for `d ∈ {0, 90, 180, 270}` equals `SensorManager.remapCoordinateSystem`'s body-to-display
 * remap (for 90: `x' = −y, y' = x`); `DeviceCameraRotationTest` pins this against an independent
 * model of the remap.
 */
object DeviceCameraRotation {

    /** `Surface.ROTATION_0..3` (0, 1, 2, 3) → degrees (0, 90, 180, 270). */
    fun surfaceRotationToDegrees(surfaceRotation: Int): Int {
        require(surfaceRotation in 0..3) { "surfaceRotation must be Surface.ROTATION_0..3, was $surfaceRotation" }
        return surfaceRotation * 90
    }

    /** CameraX / Camera2 rotation for a **rear** camera: `(sensorOrientation − displayRotation) mod 360`. */
    fun rearImageRotationDegrees(sensorOrientationDeg: Int, displayRotationDeg: Int): Int =
        normalizeQuarterTurn(sensorOrientationDeg - displayRotationDeg)

    /**
     * Body-to-display remap for a display rotation in degrees (0/90/180/270): `v_display = M · v_body`,
     * a rotation about z by `+displayRotationDeg`. `ROTATION_90` is the device turned 90° CCW, so its
     * right edge (body +x) points up and screen right is body −y.
     */
    fun bodyToDisplay(displayRotationDeg: Int): FloatArray =
        RotationMath.rotationAboutZ(normalizeQuarterTurn(displayRotationDeg))

    /**
     * GL-camera-from-device rotation for a **rear** camera whose pose is solved in the raw sensor
     * buffer rotated clockwise by [imageRotationAppliedDeg]: `R_z(sensorOrientation − applied)`.
     *
     * @param sensorOrientationDeg `CameraCharacteristics.SENSOR_ORIENTATION` (multiple of 90).
     * @param imageRotationAppliedDeg the clockwise rotation you applied to the buffer before
     *   tracking: 0 for the raw buffer, CameraX `rotationDegrees` for the display-upright image.
     */
    fun cameraFromDevice(sensorOrientationDeg: Int, imageRotationAppliedDeg: Int = 0): FloatArray =
        RotationMath.rotationAboutZ(normalizeQuarterTurn(sensorOrientationDeg - imageRotationAppliedDeg))

    /** GL-camera-from-device for a pose solved in the display-upright image: [bodyToDisplay]. */
    fun cameraFromDeviceForDisplayFrame(displayRotationDeg: Int): FloatArray = bodyToDisplay(displayRotationDeg)

    /** Convert a GL-camera-frame rotation (row-major 3×3) to the OpenCV camera frame: `D · M`, `D = diag(1, −1, −1)`. */
    fun toOpenCv(glCameraFromDevice: FloatArray): FloatArray {
        require(glCameraFromDevice.size == 9) { "expected a row-major 3x3" }
        val sign = floatArrayOf(1f, -1f, -1f)
        return FloatArray(9) { i -> sign[i / 3] * glCameraFromDevice[i] }
    }

    /**
     * Re-express a body-frame rotation delta (e.g. [com.hereliesaz.sphereslam.math.RotationMath.cameraRotationDelta]
     * of two [AttitudeSample]s) in a camera frame: `M · ΔR · Mᵀ`.
     */
    fun cameraDelta(cameraFromDevice: FloatArray, deltaBody: FloatArray): FloatArray =
        RotationMath.conjugateMat3(cameraFromDevice, deltaBody)

    private fun normalizeQuarterTurn(deg: Int): Int {
        val r = ((deg % 360) + 360) % 360
        require(r % 90 == 0) { "rotation must be a multiple of 90 degrees, was $deg" }
        return r
    }
}

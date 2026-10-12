package com.hereliesaz.sphereslam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import kotlin.math.hypot
import kotlin.math.max

/**
 * Lens distortion against SphereSLAM's zero-distortion pinhole model ([CameraIntrinsics]).
 *
 * KPM, flow PnP, the photosphere and the projection matrix all assume an undistorted image. Camera2
 * can deliver one: with `DISTORTION_CORRECTION_MODE` FAST or HIGH_QUALITY (API 28+) every processed
 * output stream — the YUV analysis stream included — is corrected by the HAL, and the pinhole model
 * holds. Hosts should request it while tracking ([chooseCorrectionMode]). When a device cannot
 * correct, `LENS_DISTORTION` still says how far the model is off: [maxResidualPx] is the largest
 * displacement, in stream pixels, between where the pinhole model puts a point and where the
 * uncorrected lens images it.
 *
 * `LENS_DISTORTION` is `[κ1, κ2, κ3, κ4, κ5]` in normalized pinhole coordinates (pixels relative to
 * the principal point, divided by the focal length), so it applies unchanged to any crop or uniform
 * scale of the sensor — i.e. to [CameraIntrinsics] for any stream size. Per the Camera2 reference,
 * an ideal point `(x, y)` with `r² = x² + y²` is imaged at
 *
 *     x_d = x (1 + κ1 r² + κ2 r⁴ + κ3 r⁶) + κ4 (2 x y) + κ5 (r² + 2 x²)
 *     y_d = y (1 + κ1 r² + κ2 r⁴ + κ3 r⁶) + κ5 (2 x y) + κ4 (r² + 2 y²)
 *
 * The pre-API-28 `LENS_RADIAL_DISTORTION` (a different, deprecated parameterization) is not read.
 */
object LensDistortion {

    /** What a camera reports: its distortion coefficients and the correction modes it supports. */
    class Profile(
        /** `LENS_DISTORTION` `[κ1..κ5]`, or null when not reported (or API < 28). */
        val coefficients: FloatArray?,
        /** `DISTORTION_CORRECTION_AVAILABLE_MODES`; empty when not reported (or API < 28). */
        val correctionModes: IntArray,
    ) {
        /** The correction mode to request while tracking, or null when the camera cannot correct. */
        val trackingCorrectionMode: Int? get() = chooseCorrectionMode(correctionModes)
    }

    /** Read [Profile] from [characteristics]. */
    fun read(characteristics: CameraCharacteristics): Profile {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Profile(null, IntArray(0))
        return Profile(
            coefficients = characteristics.get(CameraCharacteristics.LENS_DISTORTION),
            correctionModes = characteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                ?: IntArray(0),
        )
    }

    /**
     * The correction mode for a real-time tracking stream: FAST (corrects without lowering the frame
     * rate), else HIGH_QUALITY, else null (only OFF, or nothing reported). Values are Camera2's
     * `DISTORTION_CORRECTION_MODE_*`.
     */
    fun chooseCorrectionMode(available: IntArray): Int? = when {
        CameraMetadata.DISTORTION_CORRECTION_MODE_FAST in available -> CameraMetadata.DISTORTION_CORRECTION_MODE_FAST
        CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY in available ->
            CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        else -> null
    }

    /** Where the uncorrected lens images the ideal normalized point ([x], [y]): `(x_d, y_d)`. */
    fun distort(coefficients: FloatArray, x: Double, y: Double): DoubleArray {
        require(coefficients.size >= 5) { "LENS_DISTORTION has 5 coefficients" }
        val k1 = coefficients[0].toDouble()
        val k2 = coefficients[1].toDouble()
        val k3 = coefficients[2].toDouble()
        val k4 = coefficients[3].toDouble()
        val k5 = coefficients[4].toDouble()
        val r2 = x * x + y * y
        val radial = 1 + r2 * (k1 + r2 * (k2 + r2 * k3))
        return doubleArrayOf(
            x * radial + k4 * (2 * x * y) + k5 * (r2 + 2 * x * x),
            y * radial + k5 * (2 * x * y) + k4 * (r2 + 2 * y * y),
        )
    }

    /**
     * The largest displacement, in pixels of the [intrinsics] stream, between the pinhole model and
     * the uncorrected lens over the frame (sampled on a [grid] × [grid] lattice including the edges
     * and corners, where radial distortion peaks). 0 for null or all-zero [coefficients].
     */
    fun maxResidualPx(coefficients: FloatArray?, intrinsics: CameraIntrinsics, grid: Int = 9): Float {
        if (coefficients == null || coefficients.size < 5 || coefficients.all { it == 0f }) return 0f
        require(grid >= 2)
        var worst = 0.0
        for (j in 0 until grid) {
            val v = j * (intrinsics.height - 1.0) / (grid - 1)
            for (i in 0 until grid) {
                val u = i * (intrinsics.width - 1.0) / (grid - 1)
                val x = (u - intrinsics.cx) / intrinsics.fx
                val y = (v - intrinsics.cy) / intrinsics.fy
                val d = distort(coefficients, x, y)
                worst = max(worst, hypot((d[0] - x) * intrinsics.fx, (d[1] - y) * intrinsics.fy))
            }
        }
        return worst.toFloat()
    }

    /** A one-line description for diagnostics, e.g. `correction=FAST` or `uncorrected residual≤3.1px`. */
    fun describe(profile: Profile, appliedMode: Int?, intrinsics: CameraIntrinsics?): String {
        if (appliedMode != null && appliedMode != CameraMetadata.DISTORTION_CORRECTION_MODE_OFF) {
            return "correction=" + modeName(appliedMode)
        }
        val coeffs = profile.coefficients ?: return "uncorrected coefficients=unreported"
        val residual = intrinsics?.let { maxResidualPx(coeffs, it) }
        return "uncorrected residual≤" + (residual?.let { String.format(java.util.Locale.US, "%.1fpx", it) } ?: "?")
    }

    private fun modeName(mode: Int) = when (mode) {
        CameraMetadata.DISTORTION_CORRECTION_MODE_FAST -> "FAST"
        CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.DISTORTION_CORRECTION_MODE_OFF -> "OFF"
        else -> "mode$mode"
    }
}

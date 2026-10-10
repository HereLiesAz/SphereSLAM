package com.hereliesaz.sphereslam.camera

import com.hereliesaz.sphereslam.SphereSlamCalibration

/**
 * Pinhole intrinsics in pixels for an image of [width] × [height]: focal lengths [fx]/[fy] and
 * principal point ([cx], [cy]), OpenCV pixel convention (u right, v down, origin at the top-left
 * corner, continuous coordinates).
 *
 * Unlike [SphereSlamCalibration] this carries the image size the numbers are measured against, so
 * crop/rotate/rescale steps ([CameraIntrinsicsTransforms], [CaptureRotation]) and
 * [ProjectionMatrix] can follow the image. [UNKNOWN] (all zeros) is an explicit "absent" marker.
 */
data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
) {
    /** True for finite, positive focal lengths, finite principal point, and a positive image size. */
    val isValid: Boolean
        get() = fx.isFinite() && fy.isFinite() && fx > 0f && fy > 0f &&
            cx.isFinite() && cy.isFinite() && width > 0 && height > 0

    /** The native-engine calibration for these intrinsics. @throws IllegalArgumentException when not [isValid]. */
    fun toCalibration(): SphereSlamCalibration = SphereSlamCalibration(fx = fx, fy = fy, cx = cx, cy = cy)

    companion object {
        /** Explicit "no intrinsics" marker — never a plausible-looking guess. */
        val UNKNOWN: CameraIntrinsics = CameraIntrinsics(fx = 0f, fy = 0f, cx = 0f, cy = 0f, width = 0, height = 0)
    }
}

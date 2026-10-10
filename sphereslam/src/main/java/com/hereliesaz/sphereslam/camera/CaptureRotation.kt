package com.hereliesaz.sphereslam.camera

/**
 * The quarter-turn image rotation convention, applied identically to pixels and intrinsics.
 *
 * Rotating a raw sensor image **clockwise** by `θ` (Android `Matrix.postRotate(θ)` + `createBitmap`,
 * or [LumaFrameTransform]) maps pixel `(u, v)` as [rotatePixel] does (for 90: `(u, v) → (rawH − v, u)`)
 * and turns sensor intrinsics into [rotateIntrinsics]'s result. A sensor ray `d_s` then becomes
 * `R_z(+θ)·d_s` in the rotated (OpenCV) camera frame — `CaptureRotationTest` closes that loop by
 * projecting a 3D point, rotating the pixel, and unprojecting with the rotated intrinsics.
 *
 * Use CameraX `ImageInfo.rotationDegrees` as `θ` to obtain the display-upright image.
 */
object CaptureRotation {

    /**
     * Rotate raw intrinsics by [rotationDeg] (clockwise): swap focal lengths on quarter turns and
     * remap the principal point against the **unrotated** size [rawW] × [rawH].
     *
     * @return `[fx, fy, cx, cy]` for the rotated image.
     * @throws IllegalArgumentException when [rotationDeg] is not a multiple of 90.
     */
    fun rotateIntrinsics(
        fx: Float, fy: Float, cx: Float, cy: Float,
        rawW: Float, rawH: Float,
        rotationDeg: Int,
    ): FloatArray {
        var rfx = fx; var rfy = fy; var rcx = cx; var rcy = cy
        when (normalize(rotationDeg)) {
            90 -> {
                val t = rfx; rfx = rfy; rfy = t
                val tcx = rcx; rcx = rawH - rcy; rcy = tcx
            }
            180 -> {
                rcx = rawW - rcx
                rcy = rawH - rcy
            }
            270 -> {
                val t = rfx; rfx = rfy; rfy = t
                val tcx = rcx; rcx = rcy; rcy = rawW - tcx
            }
        }
        return floatArrayOf(rfx, rfy, rcx, rcy)
    }

    /**
     * Inverse of [rotateIntrinsics]: rotated intrinsics back to the raw sensor frame (for a consumer
     * that must index a sensor-frame buffer such as a depth image).
     *
     * @param rotatedW / [rotatedH] the **rotated** image size.
     * @param rotationDeg the same angle that was passed to [rotateIntrinsics].
     */
    fun unrotateIntrinsics(
        fx: Float, fy: Float, cx: Float, cy: Float,
        rotatedW: Float, rotatedH: Float,
        rotationDeg: Int,
    ): FloatArray = rotateIntrinsics(fx, fy, cx, cy, rotatedW, rotatedH, (360 - normalize(rotationDeg)) % 360)

    /** Where raw pixel `(u, v)` lands after a clockwise rotation by [rotationDeg] (continuous coordinates). */
    fun rotatePixel(u: Float, v: Float, rawW: Float, rawH: Float, rotationDeg: Int): FloatArray =
        when (normalize(rotationDeg)) {
            90 -> floatArrayOf(rawH - v, u)
            180 -> floatArrayOf(rawW - u, rawH - v)
            270 -> floatArrayOf(v, rawW - u)
            else -> floatArrayOf(u, v)
        }

    /** [rotationDeg] in `{0, 90, 180, 270}`. @throws IllegalArgumentException for other angles. */
    fun normalize(rotationDeg: Int): Int {
        val r = ((rotationDeg % 360) + 360) % 360
        require(r % 90 == 0) { "rotation must be a multiple of 90 degrees, was $rotationDeg" }
        return r
    }
}

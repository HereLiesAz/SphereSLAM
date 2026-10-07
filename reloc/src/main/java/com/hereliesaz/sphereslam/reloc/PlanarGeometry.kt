package com.hereliesaz.sphereslam.reloc

/**
 * Pure geometry for the planar fingerprint: a reference image is treated as a flat target on the
 * `z = 0` plane, origin at its center, scaled so its width is [widthMeters]. A feature at image
 * pixel `(u, v)` therefore sits at a known 3D point — the correspondence `solvePnP` needs. Height
 * is derived from the image aspect so the mapping is isotropic.
 *
 * Non-metric is fine: pass `widthMeters = 1f` and the whole reconstruction is simply unit-scaled —
 * poses are then up-to-scale, which is all basic overlay AR needs.
 *
 * Pure and framework-free so it is unit-testable without OpenCV's native library loaded.
 */
@ExperimentalSphereSlamRelocApi
object PlanarGeometry {

    /** Map a reference-image pixel to its 3D point on the centered `z = 0` target plane. */
    fun pixelToPlane(
        u: Float,
        v: Float,
        imageWidth: Int,
        imageHeight: Int,
        widthMeters: Float,
    ): FloatArray {
        require(imageWidth > 0 && imageHeight > 0) { "image dims must be positive" }
        require(widthMeters > 0f && widthMeters.isFinite()) { "widthMeters must be positive" }
        val heightMeters = widthMeters * imageHeight.toFloat() / imageWidth.toFloat()
        val x = (u / imageWidth - 0.5f) * widthMeters
        // Image +v points down; the plane's +y points up, so flip.
        val y = (0.5f - v / imageHeight) * heightMeters
        return floatArrayOf(x, y, 0f)
    }
}

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

    /**
     * Map a reference-image pixel onto a plane spanning exactly `[-halfWidth, halfWidth] ×
     * [-halfHeight, halfHeight]` (`z = 0`, +X right, +Y up, top-left pixel at `(-halfWidth,
     * +halfHeight)`), independent of the image aspect — for a reference stretched onto a design quad
     * of given half-extents (GraffitiXR's `HomographyTracker` object frame).
     */
    fun pixelToExtents(
        u: Float,
        v: Float,
        imageWidth: Int,
        imageHeight: Int,
        halfWidth: Float,
        halfHeight: Float,
    ): FloatArray {
        require(imageWidth > 0 && imageHeight > 0) { "image dims must be positive" }
        require(halfWidth > 0f && halfWidth.isFinite() && halfHeight > 0f && halfHeight.isFinite()) {
            "half-extents must be positive"
        }
        return floatArrayOf((u / imageWidth - 0.5f) * 2f * halfWidth, -(v / imageHeight - 0.5f) * 2f * halfHeight, 0f)
    }
}

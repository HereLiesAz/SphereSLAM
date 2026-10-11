package com.hereliesaz.sphereslam.reloc

/**
 * Monocular depth for photosphere tiles — where MiDaS earns its keep in tracking: a single
 * photosphere knows each feature's direction but not its distance, so a camera that has stepped away
 * from the station could only be placed after a second station triangulates the scene. With a
 * relative depth map per tile, calibrated to known depths ([PhotosphereTrackingSession]: triangulated
 * landmarks, overlapping calibrated tiles, the wall plane within its extent), every tile feature
 * becomes a 3D point from the first station on.
 *
 * Implement over `:models` `DepthEstimator`:
 * ~~~
 * TileDepthSource { luma, w, h -> estimator.estimateLuma(luma, w, h)?.let { TileDepthMap(it.width, it.height, it.data) } }
 * ~~~
 * Called on the session's tile worker, once per new tile (after the tile-spacing gate; not per
 * frame or keyframe). Return null when no depth is available; tiles then rely on triangulation alone.
 */
@ExperimentalSphereSlamRelocApi
fun interface TileDepthSource {
    /** Relative inverse depth of [luma] (`width * height`, tightly packed), or null. */
    fun inverseDepth(luma: ByteArray, width: Int, height: Int): TileDepthMap?
}

/**
 * A relative inverse-depth map (MiDaS convention: larger = nearer; affine-invariant), row-major
 * [width] × [height], covering the whole source image at its own resolution.
 */
@ExperimentalSphereSlamRelocApi
class TileDepthMap(val width: Int, val height: Int, values: FloatArray) {
    private val data = values.copyOf()

    init {
        require(width > 0 && height > 0 && values.size == width * height) { "values must be width*height" }
    }

    /**
     * Bilinear sample at source-image pixel ([u], [v]) of a [sourceWidth] × [sourceHeight] image; null
     * outside it or on a non-finite value.
     */
    fun sample(u: Float, v: Float, sourceWidth: Int, sourceHeight: Int): Float? {
        if (u < 0f || v < 0f || u >= sourceWidth || v >= sourceHeight) return null
        val x = ((u + 0.5f) * width / sourceWidth - 0.5f).coerceIn(0f, (width - 1).toFloat())
        val y = ((v + 0.5f) * height / sourceHeight - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = minOf(x0 + 1, width - 1); val y1 = minOf(y0 + 1, height - 1)
        val fx = x - x0; val fy = y - y0
        val top = data[y0 * width + x0] * (1 - fx) + data[y0 * width + x1] * fx
        val bottom = data[y1 * width + x0] * (1 - fx) + data[y1 * width + x1] * fx
        return (top * (1 - fy) + bottom * fy).takeIf { it.isFinite() }
    }
}

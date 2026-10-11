package com.hereliesaz.sphereslam.reloc

/**
 * A relative inverse-depth map (e.g. MiDaS output) for one camera frame: [width] × [height] samples
 * covering a [frameWidth] × [frameHeight] image. Larger values are nearer. Data is copied.
 * Ported from GraffitiXR's native `MobileGS::setLatestDepthMap` stash.
 */
@ExperimentalSphereSlamRelocApi
class InverseDepthMap(
    data: FloatArray,
    val width: Int,
    val height: Int,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    private val values = data.copyOf()

    init {
        require(width > 0 && height > 0 && frameWidth > 0 && frameHeight > 0)
        require(data.size == width * height) { "data must be width*height" }
    }

    /** The sample under frame pixel ([u], [v]) (nearest, truncating), or NaN outside the map. */
    fun sample(u: Float, v: Float): Float {
        if (!u.isFinite() || !v.isFinite()) return Float.NaN
        val x = (u * width / frameWidth).toInt()
        val y = (v * height / frameHeight).toInt()
        if (u < 0f || v < 0f || x >= width || y >= height) return Float.NaN
        return values[y * width + x]
    }
}

/**
 * Places off-plane features radially from a relative depth map calibrated against the plane — the
 * omnidirectional "sphere" growth around a wall fingerprint. Features whose rays hit the wall give
 * known `(1/Z, invDepth)` pairs; [DepthScaleFit] recovers the affine scale; an off-wall feature is
 * then placed at its fitted camera-axis depth, but only within a sane band around the wall distance
 * so one bad depth pixel cannot throw a point to infinity.
 *
 * Ported from GraffitiXR's native `MobileGS::growMapFromReloc` depth branch, with the plain least
 * squares replaced by [DepthScaleFit]'s robust fit.
 */
@ExperimentalSphereSlamRelocApi
object RadialDepthPlacement {

    /** Smallest admitted depth, as a fraction of the wall distance. */
    const val MIN_WALL_FRACTION = 0.1f

    /** Largest admitted depth, as a multiple of the wall distance. */
    const val MAX_WALL_MULTIPLE = 10f

    /**
     * Calibrate [depth] to metric camera-axis depth from on-plane features: [planeDepths] are their
     * plane-intersection depths (only `> 1e-3` are used) at pixels [pixels] (flat `[u, v, …]`).
     * Null when fewer than [minSamples] usable pairs or a degenerate / inverted fit.
     */
    fun calibrate(
        pixels: FloatArray,
        planeDepths: FloatArray,
        depth: InverseDepthMap,
        minSamples: Int = 6,
    ): DepthScaleFit.Fit? {
        require(pixels.size == planeDepths.size * 2)
        val xs = ArrayList<Float>(planeDepths.size)
        val ys = ArrayList<Float>(planeDepths.size)
        for (i in planeDepths.indices) {
            val t = planeDepths[i]
            if (!t.isFinite() || t <= 1e-3f) continue
            val inv = depth.sample(pixels[2 * i], pixels[2 * i + 1])
            if (!inv.isFinite()) continue
            xs += 1f / t
            ys += inv
        }
        return DepthScaleFit.fit(xs.toFloatArray(), ys.toFloatArray(), minSamples)
    }

    /**
     * Camera-axis depth for the feature at ([u], [v]) from [fit], or null when the depth map has no
     * sample there, the fit puts it behind the camera, or it falls outside
     * `[MIN_WALL_FRACTION, MAX_WALL_MULTIPLE] × wallDistance`.
     */
    fun radialDepth(fit: DepthScaleFit.Fit, depth: InverseDepthMap, u: Float, v: Float, wallDistance: Float): Float? {
        val inv = depth.sample(u, v)
        if (!inv.isFinite()) return null
        val z = fit.depthFor(inv) ?: return null
        if (z < MIN_WALL_FRACTION * wallDistance || z > MAX_WALL_MULTIPLE * wallDistance) return null
        return z
    }
}

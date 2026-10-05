package com.hereliesaz.sphereslam.reloc

/**
 * Calibrates a relative monocular depth map (e.g. MiDaS inverse depth) to the metric scale the
 * planar fingerprint already defines, so off-plane features can be placed radially for the
 * surrounding "sphere" feature map.
 *
 * MiDaS is affine on **inverse** depth: `invDepth ≈ a·(1/Z) + b`. Features that DO land on the
 * target plane give known `(1/Z, invDepth)` pairs (Z = their camera-axis depth from PnP), so a
 * least-squares line recovers `(a, b)`. Off-plane features are then placed at
 * `Z = a / (invDepth − b)`.
 *
 * Pure math, no framework — unit-testable directly. This is the non-proprietary depth→geometry
 * bridge; it carries none of the engine's relocalization logic.
 */
object DepthScaleFit {

    /** The recovered affine fit, or null when the samples are too few or degenerate. */
    data class Fit(val a: Float, val b: Float) {
        /**
         * Camera-axis depth Z for a given inverse-depth sample, or null when the result is behind
         * the camera / at infinity. Only valid when [a] > 0 (nearer ⇒ larger invDepth ⇒ larger 1/Z).
         */
        fun depthFor(invDepth: Float): Float? {
            if (a <= 0f) return null
            val invZ = (invDepth - b) / a
            if (invZ <= 1e-4f) return null
            return 1f / invZ
        }
    }

    /**
     * Least-squares fit of `invDepth = a·(1/Z) + b` over on-plane samples. [oneOverZ] and
     * [invDepth] are parallel; needs at least [minSamples] finite pairs and a non-degenerate spread.
     * Returns null (caller falls back to plane-only placement) otherwise, and only accepts a
     * physically-sane `a > 0`.
     */
    fun fit(
        oneOverZ: FloatArray,
        invDepth: FloatArray,
        minSamples: Int = 6,
    ): Fit? {
        require(oneOverZ.size == invDepth.size) { "parallel arrays must match" }
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        var n = 0
        for (i in oneOverZ.indices) {
            val x = oneOverZ[i]
            val y = invDepth[i]
            if (!x.isFinite() || !y.isFinite()) continue
            sx += x; sy += y; sxx += x.toDouble() * x; sxy += x.toDouble() * y; n++
        }
        if (n < minSamples) return null
        val denom = n * sxx - sx * sx
        if (kotlin.math.abs(denom) < 1e-9) return null
        val a = ((n * sxy - sx * sy) / denom).toFloat()
        val b = ((sy * sxx - sx * sxy) / denom).toFloat()
        if (a <= 1e-6f || !a.isFinite() || !b.isFinite()) return null
        return Fit(a, b)
    }
}

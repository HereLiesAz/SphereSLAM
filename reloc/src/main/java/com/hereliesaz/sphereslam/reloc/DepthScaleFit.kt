package com.hereliesaz.sphereslam.reloc

/**
 * Calibrates a relative monocular depth map (e.g. MiDaS inverse depth) to the metric scale the
 * planar fingerprint already defines, so off-plane features can be placed radially for the
 * surrounding "sphere" feature map.
 *
 * MiDaS is affine on **inverse** depth: `invDepth ≈ a·(1/Z) + b`. Features that DO land on the
 * target plane give known `(1/Z, invDepth)` pairs (Z = their camera-axis depth from PnP), so a
 * robust line fit recovers `(a, b)`. Off-plane features are then placed at
 * `Z = a / (invDepth − b)`.
 *
 * Pure math, no framework — unit-testable directly. A small depth→geometry helper; it carries no
 * relocalization logic of its own.
 */
@ExperimentalSphereSlamRelocApi
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

    /** Minimum relative spread `std(1/Z) / mean(1/Z)` for a well-conditioned fit (scale-invariant). */
    const val MIN_RELATIVE_SPREAD = 1e-3

    /** Residual cutoff in robust standard deviations (`1.4826 · MAD`) for the trimmed refinement. */
    const val OUTLIER_SIGMAS = 3.0

    /** Cap on candidate sample pairs scored by the least-median seed (keeps it O(pairs · n)). */
    private const val MAX_SEED_PAIRS = 256

    /**
     * Robust fit of `invDepth = a·(1/Z) + b` over on-plane samples. [oneOverZ] and [invDepth] are
     * parallel; needs at least [minSamples] finite pairs and a non-degenerate spread. Returns null
     * (caller falls back to plane-only placement) otherwise, and only accepts a physically-sane `a > 0`.
     *
     * Robustness: real on-plane samples include mismatches and depth-network artefacts, which plain
     * least squares lets drag the line arbitrarily. The fit is therefore seeded by a deterministic
     * **least-median-of-squares** search over sample pairs (tolerates up to ~50% outliers), then
     * refined by **iterative trimmed least squares** on the samples within [OUTLIER_SIGMAS] robust
     * standard deviations. Null if fewer than [minSamples] samples survive trimming.
     *
     * Degeneracy is judged **scale-invariantly**: the relative spread of `1/Z` must reach
     * [MIN_RELATIVE_SPREAD], so the same scene is accepted or refused whatever its metric units.
     */
    fun fit(
        oneOverZ: FloatArray,
        invDepth: FloatArray,
        minSamples: Int = 6,
    ): Fit? {
        require(oneOverZ.size == invDepth.size) { "parallel arrays must match" }
        require(minSamples >= 2) { "minSamples must be >= 2" }
        val xs = ArrayList<Double>(oneOverZ.size)
        val ys = ArrayList<Double>(oneOverZ.size)
        for (i in oneOverZ.indices) {
            val x = oneOverZ[i]
            val y = invDepth[i]
            if (!x.isFinite() || !y.isFinite()) continue
            xs.add(x.toDouble()); ys.add(y.toDouble())
        }
        val n = xs.size
        if (n < minSamples) return null
        val all = IntArray(n) { it }
        if (degenerate(xs, all)) return null

        // 1. Least-median-of-squares seed over a deterministic set of sample pairs.
        var best: DoubleArray? = null
        var bestMedian = Double.POSITIVE_INFINITY
        val totalPairs = n.toLong() * (n - 1) / 2
        val stride = maxOf(1L, totalPairs / MAX_SEED_PAIRS)
        var k = 0L
        for (i in 0 until n) for (j in i + 1 until n) {
            if (k++ % stride != 0L) continue
            val dx = xs[j] - xs[i]
            if (kotlin.math.abs(dx) < 1e-12) continue
            val a = (ys[j] - ys[i]) / dx
            val b = ys[i] - a * xs[i]
            val med = median(DoubleArray(n) { val r = ys[it] - (a * xs[it] + b); r * r })
            if (med < bestMedian) { bestMedian = med; best = doubleArrayOf(a, b) }
        }
        var line = best ?: leastSquares(xs, ys, all) ?: return null

        // 2. Iterative trimmed least squares around the seed.
        var active = all
        for (iteration in 0 until MAX_TRIM_ITERATIONS) {
            val residuals = DoubleArray(n) { ys[it] - (line[0] * xs[it] + line[1]) }
            val absActive = DoubleArray(active.size) { kotlin.math.abs(residuals[active[it]]) }
            val sigma = 1.4826 * median(absActive)
            val yScale = active.maxOf { kotlin.math.abs(ys[it]) }
            val cutoff = maxOf(OUTLIER_SIGMAS * sigma, 1e-6 * maxOf(yScale, 1e-12))
            val next = all.filter { kotlin.math.abs(residuals[it]) <= cutoff }.toIntArray()
            if (next.size < minSamples || degenerate(xs, next)) return null
            val refit = leastSquares(xs, ys, next) ?: return null
            val converged = next.contentEquals(active)
            active = next
            line = refit
            if (converged) break
        }

        val a = line[0].toFloat()
        val b = line[1].toFloat()
        if (a <= 1e-6f || !a.isFinite() || !b.isFinite()) return null
        return Fit(a, b)
    }

    private const val MAX_TRIM_ITERATIONS = 5

    /** Ordinary least-squares line over [idx], or null when the normal equations are singular. */
    private fun leastSquares(xs: List<Double>, ys: List<Double>, idx: IntArray): DoubleArray? {
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in idx) { sx += xs[i]; sy += ys[i]; sxx += xs[i] * xs[i]; sxy += xs[i] * ys[i] }
        val m = idx.size.toDouble()
        val denom = m * sxx - sx * sx
        if (denom <= 0.0 || !denom.isFinite()) return null
        return doubleArrayOf((m * sxy - sx * sy) / denom, (sy * sxx - sx * sxy) / denom)
    }

    /** True when the `1/Z` samples in [idx] lack relative spread (see [MIN_RELATIVE_SPREAD]). */
    internal fun degenerate(xs: List<Double>, idx: IntArray): Boolean {
        if (idx.size < 2) return true
        val mean = idx.sumOf { xs[it] } / idx.size
        val variance = idx.sumOf { (xs[it] - mean) * (xs[it] - mean) } / idx.size
        val scale = idx.sumOf { kotlin.math.abs(xs[it]) } / idx.size
        if (scale <= 0.0) return true
        return kotlin.math.sqrt(variance) / scale < MIN_RELATIVE_SPREAD
    }

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else 0.5 * (sorted[mid - 1] + sorted[mid])
    }
}

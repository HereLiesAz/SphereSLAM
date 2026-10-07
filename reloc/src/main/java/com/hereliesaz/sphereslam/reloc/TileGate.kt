package com.hereliesaz.sphereslam.reloc

/**
 * The **geometric** admission gate for depth-backed tiles: pure, mechanical thresholds on what
 * [TileTriangulator] measured, deciding whether a triangulated point is well-enough constrained to
 * keep and whether a tile has enough such points to be worth matching against. It makes no judgment
 * about whether a tile is *trustworthy to anchor on* — that teleological call is the host's, made
 * after this gate through a [TileCorroborator]. Keeping the two apart is the project's boundary: the
 * library admits on geometry, the host promotes on trust.
 *
 * Pure data in, booleans out — unit-tested without OpenCV or a device.
 *
 * @property minParallaxDeg a point's rays must subtend at least this angle, so its depth is actually
 *   constrained (a tiny parallax means large depth error however clean the reprojection).
 * @property maxReprojectionRmsPx a point's views must agree to within this RMS pixel error.
 * @property minViews a point must have been seen in at least this many views.
 * @property minPoints a tile must retain at least this many admitted points to be admitted itself.
 */
@ExperimentalSphereSlamRelocApi
class TileGate(
    val minParallaxDeg: Float = 2f,
    val maxReprojectionRmsPx: Float = 2f,
    val minViews: Int = 2,
    val minPoints: Int = 12,
) {
    init {
        require(minParallaxDeg >= 0f) { "minParallaxDeg must be >= 0" }
        require(maxReprojectionRmsPx >= 0f) { "maxReprojectionRmsPx must be >= 0" }
        require(minViews >= 2) { "minViews must be >= 2 (triangulation needs two)" }
        require(minPoints >= 1) { "minPoints must be >= 1" }
    }

    /** Whether one triangulated point clears the per-point geometry thresholds. */
    fun admitsPoint(t: TileTriangulator.Triangulation): Boolean =
        t.views >= minViews &&
            t.minParallaxDeg >= minParallaxDeg &&
            t.reprojectionRmsPx <= maxReprojectionRmsPx

    /** The subset of [points] that clear the per-point thresholds, order preserved. */
    fun admittedPoints(points: List<TileTriangulator.Triangulation>): List<TileTriangulator.Triangulation> =
        points.filter(::admitsPoint)

    /** Whether a tile built from [points] is admissible: enough of its points clear the thresholds. */
    fun admitsTile(points: List<TileTriangulator.Triangulation>): Boolean =
        admittedPoints(points).size >= minPoints
}

package com.hereliesaz.sphereslam

/**
 * Angular-coverage accumulator for the guided sweep that fills SphereSLAM's surrounding feature map.
 *
 * A surface (wall or canvas) can only be seen from in front of it, so coverage is tracked over the
 * **viewable front region** — an azimuth arc `±`[viewableHalfAngleDeg] and an elevation arc
 * `±`[viewableElevationHalfAngleDeg] around the direction the camera faces when the surface is
 * captured head-on ([setWallHeading]; the horizon at elevation 0). Directions outside that region
 * carry no surface and are ignored, so "100%" means the reachable region is mapped, not a full
 * sphere.
 *
 * **Two dimensions, one sensor.** Azimuth and elevation both come from a single magnetometer-fused
 * device attitude (`TYPE_ROTATION_VECTOR`): azimuth is the camera axis's compass bearing
 * `atan2(east, north)`, elevation is `asin(up)`. Both are gravity/north anchored, so neither drifts
 * over a long pivot the way a bare gyro would. This class is pure math and holds no sensor itself —
 * feed it absolute (heading, elevation) degrees from whatever attitude source the host provides.
 *
 * **Scale-free.** Coverage is purely angular, identical whether the surface is a 3 m wall or a 30 cm
 * canvas; only the angles swept through matter, never the distance.
 *
 * **Backward-compatible.** With [elevationBandCount] 1 the elevation dimension collapses to a single
 * band spanning the whole elevation arc, and the accumulator behaves as a plain azimuth ring:
 * [observe] with only a heading lands at the horizon band. Pass `elevationBandCount > 1` for the 2-D
 * coverage a directional glow consumes via [thinDirections].
 *
 * Not thread-safe; guard it in the owner if observations and reads cross threads.
 *
 * Example:
 * ```
 * val coverage = SphereCoverage(elevationBandCount = 3)
 * coverage.setWallHeading(attitude.headingWhenCapturedHeadOn)
 * // per keyframe, from CameraAttitudeProvider:
 * coverage.observe(headingDeg, elevationDeg)
 * val pct = coverage.coverageFraction()          // drive a progress read-out
 * val gaps = coverage.thinDirections()           // feed CoverageGlowProjection
 * ```
 *
 * @property sectorCount number of azimuth sectors spanning the viewable arc (`>= 2`); ~15° each at
 *   the default half-angle.
 * @property viewableHalfAngleDeg half-width of the viewable azimuth arc in degrees, `(0, 180]`.
 * @property elevationBandCount number of elevation bands (`>= 1`); 1 collapses the elevation
 *   dimension to a plain azimuth ring.
 * @property viewableElevationHalfAngleDeg half-height of the viewable elevation arc around the
 *   horizon in degrees, `(0, 90]`.
 * @throws IllegalArgumentException if any argument is outside the stated range.
 */
class SphereCoverage(
    private val sectorCount: Int = DEFAULT_SECTORS,
    private val viewableHalfAngleDeg: Float = DEFAULT_VIEWABLE_HALF_ANGLE_DEG,
    private val elevationBandCount: Int = DEFAULT_ELEVATION_BANDS,
    private val viewableElevationHalfAngleDeg: Float = DEFAULT_VIEWABLE_ELEVATION_HALF_ANGLE_DEG,
) {
    init {
        require(sectorCount >= 2) { "sectorCount must be >= 2, was $sectorCount" }
        require(viewableHalfAngleDeg in 1f..180f) {
            "viewableHalfAngleDeg must be in (0, 180], was $viewableHalfAngleDeg"
        }
        require(elevationBandCount >= 1) {
            "elevationBandCount must be >= 1, was $elevationBandCount"
        }
        require(viewableElevationHalfAngleDeg in 1f..90f) {
            "viewableElevationHalfAngleDeg must be in (0, 90], was $viewableElevationHalfAngleDeg"
        }
    }

    /** The shared angular lattice; sector/band indexing and centers live here, not duplicated. */
    private val grid = SphereGrid(
        sectorCount,
        viewableHalfAngleDeg,
        elevationBandCount,
        viewableElevationHalfAngleDeg,
    )

    /** One count per (sector, band) bin, row-major: `sector * elevationBandCount + band`. */
    private val hits = IntArray(sectorCount * elevationBandCount)
    private var total = 0

    /**
     * Compass heading (degrees) the camera faces when the surface is captured head-on — the center of
     * the viewable arc. Null until anchored; the first observation auto-anchors to its own heading.
     */
    private var wallHeadingDeg: Float? = null

    /** Total in-region observations folded in so far (repeats included). */
    val observationCount: Int get() = total

    /**
     * Set (or re-set) the heading the camera faces when the surface is seen head-on — the center of
     * the viewable arc. Does not clear accumulated bins. Ignored when [headingDeg] is non-finite.
     *
     * @param headingDeg absolute compass bearing in degrees (0 = north, clockwise).
     */
    fun setWallHeading(headingDeg: Float) {
        if (headingDeg.isFinite()) wallHeadingDeg = norm360(headingDeg)
    }

    /**
     * @return whether a wall-facing heading has been anchored, explicitly via [setWallHeading] or
     *   implicitly by the first [observe].
     */
    fun hasWallHeading(): Boolean = wallHeadingDeg != null

    /**
     * Fold one camera direction into the coverage. Auto-anchors the wall heading on the first call if
     * unset (to this call's heading).
     *
     * @param headingDeg absolute compass bearing of the camera axis, degrees (0 = north, clockwise).
     * @param elevationDeg camera axis angle above (+) / below (−) the horizon, degrees; defaults to
     *   the horizon so azimuth-only callers are unchanged.
     * @return true when the sample landed in a viewable bin not previously covered (coverage grew);
     *   false when either angle is non-finite or the direction is outside the viewable front region.
     */
    fun observe(headingDeg: Float, elevationDeg: Float = 0f): Boolean {
        if (!headingDeg.isFinite() || !elevationDeg.isFinite()) return false
        val anchor = wallHeadingDeg ?: norm360(headingDeg).also { wallHeadingDeg = it }
        val sector = grid.sectorOf(headingDeg, anchor) ?: return false
        val band = grid.bandOf(elevationDeg) ?: return false
        total++
        val index = sector * elevationBandCount + band
        val wasEmpty = hits[index] == 0
        hits[index]++
        return wasEmpty
    }

    /** @return fraction of the viewable region's bins with at least one observation, in `[0, 1]`. */
    fun coverageFraction(): Float {
        var covered = 0
        for (h in hits) if (h > 0) covered++
        return covered.toFloat() / hits.size
    }

    /**
     * A simple left/right turn hint: the azimuth columns still missing coverage. Collapses the
     * elevation dimension (a column counts as thin if any of its bands is empty), so with the default
     * single band this is the plain per-sector thin list.
     *
     * @return absolute compass headings (degrees, 0 = north) of each thin column's center; empty
     *   before the arc is anchored and once the region is fully covered.
     */
    fun thinHeadingsDegrees(): FloatArray {
        val anchor = wallHeadingDeg ?: return FloatArray(0)
        val out = ArrayList<Float>()
        for (s in 0 until sectorCount) {
            val anyEmpty = (0 until elevationBandCount).any { hits[s * elevationBandCount + it] == 0 }
            if (anyEmpty) out.add(norm360(anchor + grid.sectorCenterDelta(s)))
        }
        return out.toFloatArray()
    }

    /**
     * A still-unscanned direction.
     *
     * @property azimuthDeg absolute compass bearing of the bin center, degrees (0 = north, clockwise).
     * @property elevationDeg signed angle above (+) / below (−) the horizon of the bin center, degrees.
     */
    data class Direction(val azimuthDeg: Float, val elevationDeg: Float)

    /**
     * Every still-unobserved bin center, as absolute directions — the signal a scene-anchored glow
     * draws over (feed it to [CoverageGlowProjection.project]).
     *
     * @return the unscanned (azimuth, elevation) bin centers; empty before the arc is anchored and
     *   once the region is fully covered.
     */
    fun thinDirections(): List<Direction> {
        val anchor = wallHeadingDeg ?: return emptyList()
        val out = ArrayList<Direction>()
        for (s in 0 until sectorCount) {
            val az = norm360(anchor + grid.sectorCenterDelta(s))
            for (b in 0 until elevationBandCount) {
                if (hits[s * elevationBandCount + b] == 0) {
                    out.add(Direction(azimuthDeg = az, elevationDeg = grid.bandCenterElevation(b)))
                }
            }
        }
        return out
    }

    /** Drop all accumulated coverage and the anchor (new canonical frame / reference reset). */
    fun reset() {
        hits.fill(0)
        total = 0
        wallHeadingDeg = null
    }

    companion object {
        /** Sectors across the viewable azimuth arc — ~15° each at the default half-angle. */
        const val DEFAULT_SECTORS = 12

        /**
         * Half-width of the viewable front arc. 85° (≈170° total) keeps the near-grazing edges, where
         * the surface is barely resolvable, out of the coverage target.
         */
        const val DEFAULT_VIEWABLE_HALF_ANGLE_DEG = 85f

        /** Elevation bands. Default 1 collapses elevation (plain azimuth-ring behavior). */
        const val DEFAULT_ELEVATION_BANDS = 1

        /**
         * Half-height of the viewable elevation arc around the horizon. 60° covers the up/down range a
         * standing artist realistically pans a wall or leans over a canvas, without targeting the
         * straight-up/down directions that carry no surface.
         */
        const val DEFAULT_VIEWABLE_ELEVATION_HALF_ANGLE_DEG = 60f

        /** Normalize a heading to [0, 360). */
        fun norm360(deg: Float): Float = ((deg % 360f) + 360f) % 360f

        /**
         * Build coverage from a recorded log of camera directions — useful for replaying a persisted
         * keyframe log or unit-testing. Directions outside the viewable region are ignored.
         *
         * @param headingsDeg absolute compass bearings, degrees.
         * @param wallHeadingDeg wall-facing heading to anchor the arc, or null to auto-anchor to the
         *   first in-region sample.
         * @param sectorCount azimuth sectors (see the primary constructor).
         * @param viewableHalfAngleDeg viewable azimuth half-arc, degrees.
         * @param elevationsDeg per-sample elevations parallel to [headingsDeg], or null to fold every
         *   sample at the horizon.
         * @param elevationBandCount elevation bands (see the primary constructor).
         * @param viewableElevationHalfAngleDeg viewable elevation half-arc, degrees.
         * @return a populated accumulator.
         * @throws IllegalArgumentException if [elevationsDeg] is non-null and not the same length as
         *   [headingsDeg], or if any constructor argument is out of range.
         */
        fun fromHeadingLog(
            headingsDeg: FloatArray,
            wallHeadingDeg: Float? = null,
            sectorCount: Int = DEFAULT_SECTORS,
            viewableHalfAngleDeg: Float = DEFAULT_VIEWABLE_HALF_ANGLE_DEG,
            elevationsDeg: FloatArray? = null,
            elevationBandCount: Int = DEFAULT_ELEVATION_BANDS,
            viewableElevationHalfAngleDeg: Float = DEFAULT_VIEWABLE_ELEVATION_HALF_ANGLE_DEG,
        ): SphereCoverage {
            require(elevationsDeg == null || elevationsDeg.size == headingsDeg.size) {
                "elevationsDeg must be parallel to headingsDeg"
            }
            val c = SphereCoverage(
                sectorCount,
                viewableHalfAngleDeg,
                elevationBandCount,
                viewableElevationHalfAngleDeg,
            )
            if (wallHeadingDeg != null) c.setWallHeading(wallHeadingDeg)
            for (i in headingsDeg.indices) c.observe(headingsDeg[i], elevationsDeg?.get(i) ?: 0f)
            return c
        }
    }
}

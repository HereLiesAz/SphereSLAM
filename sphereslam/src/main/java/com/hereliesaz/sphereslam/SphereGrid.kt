package com.hereliesaz.sphereslam

import kotlin.math.floor

/**
 * The shared angular lattice behind the guided sweep: the viewable front region partitioned into
 * `sectorCount` azimuth sectors × `elevationBandCount` elevation bands, anchored to a wall-facing
 * heading. Pure geometry — no state, no sensors — so [SphereCoverage] (observed-ness) and
 * [PhotosphereMap] (per-tile freshness) address tiles through one definition and cannot drift apart.
 *
 * Azimuth sectors span `±`[viewableHalfAngleDeg] around the anchor; elevation bands span
 * `±`[viewableElevationHalfAngleDeg] around the horizon. Bins are row-major: `sector * bands + band`.
 *
 * @property sectorCount azimuth sectors across the viewable arc (`>= 2`).
 * @property viewableHalfAngleDeg half-width of the viewable azimuth arc, degrees `(0, 180]`.
 * @property elevationBandCount elevation bands (`>= 1`; 1 collapses elevation to a ring).
 * @property viewableElevationHalfAngleDeg half-height of the viewable elevation arc, degrees `(0, 90]`.
 */
internal class SphereGrid(
    val sectorCount: Int,
    val viewableHalfAngleDeg: Float,
    val elevationBandCount: Int,
    val viewableElevationHalfAngleDeg: Float,
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

    /** Total number of (sector, band) bins. */
    val tileCount: Int get() = sectorCount * elevationBandCount

    /**
     * Whether the azimuth arc spans a full ring, making sector 0 and sector `sectorCount - 1`
     * adjacent. A narrower viewable arc (the default) has hard edges that do not wrap.
     */
    val azimuthWraps: Boolean get() = viewableHalfAngleDeg >= 180f

    /** Row-major bin index for ([sector], [band]). */
    fun index(sector: Int, band: Int): Int = sector * elevationBandCount + band

    /** Azimuth width of one sector, degrees. */
    val sectorStepDeg: Float get() = (2f * viewableHalfAngleDeg) / sectorCount

    /** Elevation height of one band, degrees. */
    val bandStepDeg: Float get() = (2f * viewableElevationHalfAngleDeg) / elevationBandCount

    /** Signed azimuth offset (deg) of sector [s]'s center from the anchor, in `[-half, +half]`. */
    fun sectorCenterDelta(s: Int): Float {
        val step = (2f * viewableHalfAngleDeg) / sectorCount
        return -viewableHalfAngleDeg + (s + 0.5f) * step
    }

    /** Signed elevation (deg) of band [b]'s center, in `[-half, +half]`; 0 for the collapsed band. */
    fun bandCenterElevation(b: Int): Float {
        val step = (2f * viewableElevationHalfAngleDeg) / elevationBandCount
        return -viewableElevationHalfAngleDeg + (b + 0.5f) * step
    }

    /**
     * Absolute angular extent of tile ([sector], [band]) for a wall anchored at [anchorDeg]. The
     * start azimuth is normalized to `[0, 360)`; the end is start + [sectorStepDeg] (not wrapped).
     */
    fun regionOf(sector: Int, band: Int, anchorDeg: Float): SphereCoverage.TileRegion {
        val start = norm360(anchorDeg + sectorCenterDelta(sector) - sectorStepDeg / 2f)
        val bottom = bandCenterElevation(band) - bandStepDeg / 2f
        return SphereCoverage.TileRegion(start, start + sectorStepDeg, bottom, bottom + bandStepDeg)
    }

    /** Sector index for [headingDeg] within the viewable arc around [anchorDeg], or null if outside. */
    fun sectorOf(headingDeg: Float, anchorDeg: Float): Int? {
        val delta = signedDelta(norm360(headingDeg), anchorDeg)
        if (delta < -viewableHalfAngleDeg || delta > viewableHalfAngleDeg) return null
        val step = (2f * viewableHalfAngleDeg) / sectorCount
        return floor((delta + viewableHalfAngleDeg) / step).toInt().coerceIn(0, sectorCount - 1)
    }

    /** Elevation band for [elevationDeg] within the viewable band, or null if outside it. */
    fun bandOf(elevationDeg: Float): Int? {
        if (elevationDeg < -viewableElevationHalfAngleDeg || elevationDeg > viewableElevationHalfAngleDeg) {
            return null
        }
        val step = (2f * viewableElevationHalfAngleDeg) / elevationBandCount
        return floor((elevationDeg + viewableElevationHalfAngleDeg) / step).toInt()
            .coerceIn(0, elevationBandCount - 1)
    }

    /**
     * Edge-adjacent tiles of ([sector], [band]): the azimuth neighbors (`sector ± 1`, wrapping only
     * on a full ring per [azimuthWraps]) and the elevation neighbors (`band ± 1`, always clamped —
     * the elevation arc's top and bottom are real edges). Diagonal tiles are not included.
     */
    fun neighbors(sector: Int, band: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(4)
        for (ds in intArrayOf(-1, 1)) {
            var ns = sector + ds
            if (azimuthWraps) ns = (ns + sectorCount) % sectorCount
            if (ns in 0 until sectorCount) out.add(ns to band)
        }
        for (db in intArrayOf(-1, 1)) {
            val nb = band + db
            if (nb in 0 until elevationBandCount) out.add(sector to nb)
        }
        return out
    }

    companion object {
        /** Normalize a heading to `[0, 360)`. */
        fun norm360(deg: Float): Float = ((deg % 360f) + 360f) % 360f

        /** Shortest signed angular difference `a − b` in degrees, in `[-180, 180]`. */
        fun signedDelta(a: Float, b: Float): Float {
            var d = (a - b + 180f) % 360f
            if (d < 0f) d += 360f
            return d - 180f
        }
    }
}

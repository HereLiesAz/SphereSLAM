package com.hereliesaz.sphereslam

import kotlin.math.abs

/**
 * A tile address in the photosphere lattice: [sector] (azimuth) × [band] (elevation), in the same
 * grid [SphereCoverage] and the coverage glow use.
 */
data class TileId(val sector: Int, val band: Int)

/**
 * One photosphere tile's freshness record.
 *
 * Deliberately one state, not two: a tile either [needsUpdate] or it doesn't. "Never scanned" and
 * "scanned but now stale" are the same instruction to the user — point the camera there — so they
 * collapse to a single flag and a single glow. [lastUpdatedMs] (0 = never) is kept only so a
 * time-to-live re-check policy can decide *when* to flip a tile back to needing an update.
 *
 * A tile's heavy content (fingerprint / descriptors) is intentionally not held here: this module
 * stays dependency-free, and a matcher layer attaches that externally, keyed by [id]. The light
 * sensor tags the vision calls for are carried, though: [representativeOrientation] (a unit
 * quaternion `[x, y, z, w]` — the gyro/compass attitude the tile was captured at) and the optional
 * [rangeMeters] (an estimated distance to the surface at the tile center, e.g. from monocular depth).
 *
 * @property id the tile's grid address.
 * @property center the tile center's absolute direction (needs the map anchored).
 * @property needsUpdate whether the tile should be (re)scanned.
 * @property lastUpdatedMs monotonic time of the last update, or 0 if never scanned.
 * @property representativeOrientation the attitude the tile was last captured at, or null.
 * @property rangeMeters estimated distance to the surface at the tile center, or null if unknown.
 */
data class PanoramaTile(
    val id: TileId,
    val center: SphereCoverage.Direction,
    val needsUpdate: Boolean,
    val lastUpdatedMs: Long,
    val representativeOrientation: FloatArray?,
    val rangeMeters: Float?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PanoramaTile) return false
        return id == other.id &&
            center == other.center &&
            needsUpdate == other.needsUpdate &&
            lastUpdatedMs == other.lastUpdatedMs &&
            rangeMeters == other.rangeMeters &&
            (representativeOrientation?.contentEquals(other.representativeOrientation)
                ?: (other.representativeOrientation == null))
    }

    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + center.hashCode()
        r = 31 * r + needsUpdate.hashCode()
        r = 31 * r + lastUpdatedMs.hashCode()
        r = 31 * r + (representativeOrientation?.contentHashCode() ?: 0)
        r = 31 * r + (rangeMeters?.hashCode() ?: 0)
        return r
    }
}

/**
 * A plain, serialization-ready snapshot of a [PhotosphereMap]'s full state, for persisting the
 * photosphere across process death (as GraffitiXR persists the fingerprint / atlas). The library
 * does not pick a wire format — the host serializes this DTO however it likes and rebuilds the map
 * with [PhotosphereMap.fromSnapshot].
 *
 * [lastUpdatedMs] values are whatever monotonic clock the host fed [PhotosphereMap.markUpdated];
 * such clocks reset on reboot, so a host that relies on TTL ages should decide on restore whether to
 * trust persisted timestamps or treat restored tiles as due for re-verification.
 *
 * Arrays are parallel, row-major (`sector * elevationBandCount + band`), length `tileCount`.
 * [orientations] entries and [rangeMeters] entries (NaN = none) are per tile.
 */
data class PhotosphereMapSnapshot(
    val sectorCount: Int,
    val viewableHalfAngleDeg: Float,
    val elevationBandCount: Int,
    val viewableElevationHalfAngleDeg: Float,
    val wallHeadingDeg: Float?,
    val needsUpdate: BooleanArray,
    val lastUpdatedMs: LongArray,
    val orientations: Array<FloatArray?>,
    val rangeMeters: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhotosphereMapSnapshot) return false
        return sectorCount == other.sectorCount &&
            viewableHalfAngleDeg == other.viewableHalfAngleDeg &&
            elevationBandCount == other.elevationBandCount &&
            viewableElevationHalfAngleDeg == other.viewableElevationHalfAngleDeg &&
            wallHeadingDeg == other.wallHeadingDeg &&
            needsUpdate.contentEquals(other.needsUpdate) &&
            lastUpdatedMs.contentEquals(other.lastUpdatedMs) &&
            orientations.contentDeepEquals(other.orientations) &&
            rangeMeters.contentEquals(other.rangeMeters)
    }

    override fun hashCode(): Int {
        var r = sectorCount
        r = 31 * r + viewableHalfAngleDeg.hashCode()
        r = 31 * r + elevationBandCount
        r = 31 * r + viewableElevationHalfAngleDeg.hashCode()
        r = 31 * r + (wallHeadingDeg?.hashCode() ?: 0)
        r = 31 * r + needsUpdate.contentHashCode()
        r = 31 * r + lastUpdatedMs.contentHashCode()
        r = 31 * r + orientations.contentDeepHashCode()
        r = 31 * r + rangeMeters.contentHashCode()
        return r
    }
}

/**
 * An orientation-indexed photosphere of tiles over the viewable front region, tracking which tiles
 * still [needsUpdate]. It is the content-bearing successor to [SphereCoverage]: where that counts
 * *that* a direction was observed, this remembers *per tile* whether its capture is current, lets a
 * re-check mark a tile (and, by the propagation rule, its neighbors) as needing an update again, and
 * hands a glow the directions still to cover.
 *
 * Every tile starts needing an update (nothing scanned yet). [markUpdated] clears a tile when it is
 * freshly captured; [recordStale] flips a re-checked tile back to needing an update **and spreads
 * that to its edge-adjacent neighbors** — a detected change at one tile is reason to re-verify the
 * ones around it. Coverage and the glow read straight off the single flag.
 *
 * Geometry mirrors [SphereCoverage] exactly (shared [SphereGrid]): azimuth arc `±`[viewableHalfAngleDeg],
 * elevation arc `±`[viewableElevationHalfAngleDeg], anchored to a wall-facing heading that the first
 * [markUpdated] sets if unset. Pure Kotlin, holds no sensor; feed it absolute (heading, elevation)
 * degrees. Not thread-safe; guard it in the owner if reads and updates cross threads.
 *
 * @param sectorCount azimuth sectors across the viewable arc (`>= 2`).
 * @param viewableHalfAngleDeg half-width of the viewable azimuth arc, degrees `(0, 180]`.
 * @param elevationBandCount elevation bands (`>= 1`; 1 collapses elevation to a ring).
 * @param viewableElevationHalfAngleDeg half-height of the viewable elevation arc, degrees `(0, 90]`.
 */
class PhotosphereMap(
    sectorCount: Int = SphereCoverage.DEFAULT_SECTORS,
    viewableHalfAngleDeg: Float = SphereCoverage.DEFAULT_VIEWABLE_HALF_ANGLE_DEG,
    elevationBandCount: Int = SphereCoverage.DEFAULT_ELEVATION_BANDS,
    viewableElevationHalfAngleDeg: Float = SphereCoverage.DEFAULT_VIEWABLE_ELEVATION_HALF_ANGLE_DEG,
) {
    private val grid = SphereGrid(
        sectorCount,
        viewableHalfAngleDeg,
        elevationBandCount,
        viewableElevationHalfAngleDeg,
    )

    /** True = the tile needs (re)scanning. Every tile starts unscanned. */
    private val needsUpdateFlags = BooleanArray(grid.tileCount) { true }
    private val lastUpdatedMs = LongArray(grid.tileCount)
    private val orientation = arrayOfNulls<FloatArray>(grid.tileCount)

    /** Per-tile estimated range to the surface, metres; NaN = unknown. */
    private val rangeMeters = FloatArray(grid.tileCount) { Float.NaN }

    private var wallHeadingDeg: Float? = null

    /** Number of tiles in the lattice. */
    val tileCount: Int get() = grid.tileCount

    /** @return whether a wall-facing heading has been anchored (explicitly or by the first update). */
    fun hasWallHeading(): Boolean = wallHeadingDeg != null

    /**
     * Set (or re-set) the heading the camera faces when the surface is seen head-on — the center of
     * the viewable arc. Does not change any tile's freshness. Ignored when [headingDeg] is non-finite.
     */
    fun setWallHeading(headingDeg: Float) {
        if (headingDeg.isFinite()) wallHeadingDeg = SphereGrid.norm360(headingDeg)
    }

    /**
     * The tile a camera direction falls in, or null when the map is unanchored or the direction is
     * outside the viewable region.
     */
    fun tileAt(headingDeg: Float, elevationDeg: Float = 0f): TileId? {
        if (!headingDeg.isFinite() || !elevationDeg.isFinite()) return null
        val anchor = wallHeadingDeg ?: return null
        val s = grid.sectorOf(headingDeg, anchor) ?: return null
        val b = grid.bandOf(elevationDeg) ?: return null
        return TileId(s, b)
    }

    /**
     * Mark the tile a camera direction falls in as freshly captured (no longer needing an update).
     * Auto-anchors the wall heading on the first call if unset. A no-op (returns null) when either
     * angle is non-finite or the direction is outside the viewable region.
     *
     * @param representativeOrientation optional unit quaternion `[x, y, z, w]` stored on the tile.
     * @param rangeMeters optional estimated distance to the surface at this direction, metres.
     * @return the updated tile's id, or null if the direction mapped to no tile.
     */
    fun markUpdated(
        headingDeg: Float,
        elevationDeg: Float = 0f,
        nowMs: Long = 0L,
        representativeOrientation: FloatArray? = null,
        rangeMeters: Float? = null,
    ): TileId? {
        if (!headingDeg.isFinite() || !elevationDeg.isFinite()) return null
        val anchor = wallHeadingDeg ?: SphereGrid.norm360(headingDeg).also { wallHeadingDeg = it }
        val s = grid.sectorOf(headingDeg, anchor) ?: return null
        val b = grid.bandOf(elevationDeg) ?: return null
        val id = TileId(s, b)
        applyUpdated(id, nowMs, representativeOrientation, rangeMeters)
        return id
    }

    /** Mark a specific tile freshly captured. Ignores an out-of-range id. */
    fun markUpdated(
        id: TileId,
        nowMs: Long = 0L,
        representativeOrientation: FloatArray? = null,
        rangeMeters: Float? = null,
    ) {
        if (!inRange(id)) return
        applyUpdated(id, nowMs, representativeOrientation, rangeMeters)
    }

    private fun applyUpdated(
        id: TileId,
        nowMs: Long,
        orientationValue: FloatArray?,
        rangeValue: Float?,
    ) {
        val i = grid.index(id.sector, id.band)
        needsUpdateFlags[i] = false
        lastUpdatedMs[i] = nowMs
        if (orientationValue != null) orientation[i] = orientationValue.copyOf()
        if (rangeValue != null) rangeMeters[i] = rangeValue
    }

    /** Flag a single tile as needing an update. Ignores an out-of-range id. */
    fun markNeedsUpdate(id: TileId) {
        if (!inRange(id)) return
        needsUpdateFlags[grid.index(id.sector, id.band)] = true
    }

    /**
     * Record that a re-check found [id] in need of updating: flag it, **and** flag its edge-adjacent
     * neighbors (the propagation rule — a change here means the tiles around it are suspect too).
     * Neighbors already needing an update stay so; fresh neighbors flip back. Ignores an out-of-range id.
     */
    fun recordStale(id: TileId) {
        if (!inRange(id)) return
        needsUpdateFlags[grid.index(id.sector, id.band)] = true
        for ((ns, nb) in grid.neighbors(id.sector, id.band)) {
            needsUpdateFlags[grid.index(ns, nb)] = true
        }
    }

    /** Whether [id] needs (re)scanning. An out-of-range id reads as not needing one. */
    fun needsUpdate(id: TileId): Boolean =
        if (inRange(id)) needsUpdateFlags[grid.index(id.sector, id.band)] else false

    /**
     * Flip every currently-fresh tile last updated before [cutoffMs] back to needing an update — the
     * time-to-live re-check: a capture goes stale with age so the sweep is kept current. Never-scanned
     * tiles are already needing an update and are untouched; this does not propagate to neighbors (age
     * is per-tile, not a detected change). Pass `nowMs - ttlMs` as the cutoff.
     *
     * @return how many tiles this flipped.
     */
    fun expireOlderThan(cutoffMs: Long): Int {
        var flipped = 0
        for (i in needsUpdateFlags.indices) {
            if (!needsUpdateFlags[i] && lastUpdatedMs[i] < cutoffMs) {
                needsUpdateFlags[i] = true
                flipped++
            }
        }
        return flipped
    }

    /** Fraction of tiles that are up to date, in `[0, 1]`. */
    fun coverageFraction(): Float {
        var fresh = 0
        for (f in needsUpdateFlags) if (!f) fresh++
        return fresh.toFloat() / needsUpdateFlags.size
    }

    /** Every tile still needing an update, by id (anchor-independent). */
    fun tilesNeedingUpdate(): List<TileId> {
        val out = ArrayList<TileId>()
        for (s in 0 until grid.sectorCount) {
            for (b in 0 until grid.elevationBandCount) {
                if (needsUpdateFlags[grid.index(s, b)]) out.add(TileId(s, b))
            }
        }
        return out
    }

    /**
     * Absolute directions of every tile still needing an update — the signal the coverage glow draws
     * over ([CoverageGlowProjection.project]). Empty until the map is anchored.
     */
    fun directionsNeedingUpdate(): List<SphereCoverage.Direction> {
        val anchor = wallHeadingDeg ?: return emptyList()
        val out = ArrayList<SphereCoverage.Direction>()
        for (s in 0 until grid.sectorCount) {
            val az = SphereGrid.norm360(anchor + grid.sectorCenterDelta(s))
            for (b in 0 until grid.elevationBandCount) {
                if (needsUpdateFlags[grid.index(s, b)]) {
                    out.add(SphereCoverage.Direction(az, grid.bandCenterElevation(b)))
                }
            }
        }
        return out
    }

    /**
     * The tiles whose centers currently fall within the camera's view cone — the candidate set a tile
     * matcher should try to recognize this frame, so matching stays cheap no matter how large the map
     * grows. Pure angular test against the live attitude; needs the map anchored.
     *
     * @param headingDeg camera-axis compass bearing now, degrees (0 = north, clockwise).
     * @param elevationDeg camera-axis angle above (+) / below (−) the horizon now, degrees.
     * @param hFovDeg horizontal field of view, degrees (`> 0`).
     * @param vFovDeg vertical field of view, degrees (`> 0`).
     * @return the in-view tiles' ids; empty when unanchored, an input is non-finite, or an fov `<= 0`.
     */
    fun tilesInView(headingDeg: Float, elevationDeg: Float, hFovDeg: Float, vFovDeg: Float): List<TileId> {
        val anchor = wallHeadingDeg ?: return emptyList()
        if (!headingDeg.isFinite() || !elevationDeg.isFinite() || hFovDeg <= 0f || vFovDeg <= 0f) {
            return emptyList()
        }
        val halfH = hFovDeg / 2f
        val halfV = vFovDeg / 2f
        val camHeading = SphereGrid.norm360(headingDeg)
        val out = ArrayList<TileId>()
        for (s in 0 until grid.sectorCount) {
            val az = SphereGrid.norm360(anchor + grid.sectorCenterDelta(s))
            if (abs(SphereGrid.signedDelta(az, camHeading)) > halfH) continue
            for (b in 0 until grid.elevationBandCount) {
                if (abs(grid.bandCenterElevation(b) - elevationDeg) <= halfV) out.add(TileId(s, b))
            }
        }
        return out
    }

    /**
     * Reacquisition candidates, ordered: the tiles to re-match when tracking is lost and the live
     * heading can no longer be trusted to gate [tilesInView]. In place of a view cone this leans on
     * *where you last were* — recently recognized tiles and their scanned neighbors — because losing a
     * lock rarely means teleporting. The host joins these ids to the fingerprints it holds and feeds a
     * matcher, capped by [limit] so a relock stays cheap no matter how large the map grows.
     *
     * This only seeds KPM/relocalization; it adds nothing to the map. Only scanned tiles
     * ([PanoramaTile.lastUpdatedMs] `> 0`) appear — an unscanned tile has nothing to match against.
     * The walk visits the most recently updated tile, then its scanned neighbors, then the next most
     * recent not yet visited, and so on, so the immediate neighborhood of the last known tile is tried
     * before older recognitions. Purely a read of recency and adjacency; nothing is written back, so
     * it carries no drift of its own and needs no corroboration.
     *
     * @param limit maximum ids to return (`<= 0` yields an empty list); the cheap cap on a relock.
     * @return scanned tile ids in reacquisition-priority order, at most [limit]; empty when nothing
     *   has been scanned yet. Anchor-independent (ids, not directions).
     */
    fun relockSeeds(limit: Int): List<TileId> {
        if (limit <= 0) return emptyList()
        // Scanned tiles, most recent first; index breaks ties so the order is deterministic.
        val scanned = ArrayList<TileId>()
        for (s in 0 until grid.sectorCount) {
            for (b in 0 until grid.elevationBandCount) {
                if (scannedFlags[grid.index(s, b)]) scanned.add(TileId(s, b))
            }
        }
        if (scanned.isEmpty()) return emptyList()
        scanned.sortWith(
            compareByDescending<TileId> { lastUpdatedMs[grid.index(it.sector, it.band)] }
                .thenBy { grid.index(it.sector, it.band) },
        )

        val out = ArrayList<TileId>(minOf(limit, scanned.size))
        val seen = HashSet<TileId>()
        for (seed in scanned) {
            if (out.size >= limit) break
            if (seen.add(seed)) out.add(seed)
            if (out.size >= limit) break
            for ((ns, nb) in grid.neighbors(seed.sector, seed.band)) {
                if (!scannedFlags[grid.index(ns, nb)]) continue
                val n = TileId(ns, nb)
                if (seen.add(n)) {
                    out.add(n)
                    if (out.size >= limit) break
                }
            }
        }
        return out
    }

    /** A snapshot of one tile, or null for an out-of-range id. Center is null-direction until anchored. */
    fun tile(id: TileId): PanoramaTile? {
        if (!inRange(id)) return null
        val i = grid.index(id.sector, id.band)
        val anchor = wallHeadingDeg
        val center = if (anchor == null) {
            SphereCoverage.Direction(grid.sectorCenterDelta(id.sector), grid.bandCenterElevation(id.band))
        } else {
            SphereCoverage.Direction(
                SphereGrid.norm360(anchor + grid.sectorCenterDelta(id.sector)),
                grid.bandCenterElevation(id.band),
            )
        }
        return PanoramaTile(
            id,
            center,
            needsUpdateFlags[i],
            lastUpdatedMs[i],
            orientation[i]?.copyOf(),
            rangeMeters[i].takeUnless { it.isNaN() },
        )
    }

    /** Reset every tile to needing an update and drop the anchor (new canonical frame / reference). */
    fun reset() {
        needsUpdateFlags.fill(true)
        lastUpdatedMs.fill(0L)
        orientation.fill(null)
        rangeMeters.fill(Float.NaN)
        wallHeadingDeg = null
    }

    /** A serialization-ready copy of the full map state (see [PhotosphereMapSnapshot]). */
    fun snapshot(): PhotosphereMapSnapshot = PhotosphereMapSnapshot(
        sectorCount = grid.sectorCount,
        viewableHalfAngleDeg = grid.viewableHalfAngleDeg,
        elevationBandCount = grid.elevationBandCount,
        viewableElevationHalfAngleDeg = grid.viewableElevationHalfAngleDeg,
        wallHeadingDeg = wallHeadingDeg,
        needsUpdate = needsUpdateFlags.copyOf(),
        lastUpdatedMs = lastUpdatedMs.copyOf(),
        orientations = Array(orientation.size) { orientation[it]?.copyOf() },
        rangeMeters = rangeMeters.copyOf(),
    )

    private fun inRange(id: TileId): Boolean =
        id.sector in 0 until grid.sectorCount && id.band in 0 until grid.elevationBandCount

    companion object {
        /**
         * Rebuild a map from a [PhotosphereMapSnapshot]. The grid dimensions come from the snapshot;
         * the per-tile arrays must all be length `sectorCount * elevationBandCount`.
         *
         * @throws IllegalArgumentException if the snapshot's grid is invalid or an array length is wrong.
         */
        fun fromSnapshot(snapshot: PhotosphereMapSnapshot): PhotosphereMap {
            val map = PhotosphereMap(
                snapshot.sectorCount,
                snapshot.viewableHalfAngleDeg,
                snapshot.elevationBandCount,
                snapshot.viewableElevationHalfAngleDeg,
            )
            val n = map.grid.tileCount
            require(
                snapshot.needsUpdate.size == n &&
                    snapshot.lastUpdatedMs.size == n &&
                    snapshot.orientations.size == n &&
                    snapshot.rangeMeters.size == n,
            ) { "snapshot arrays must all be length $n" }
            snapshot.needsUpdate.copyInto(map.needsUpdateFlags)
            snapshot.lastUpdatedMs.copyInto(map.lastUpdatedMs)
            snapshot.rangeMeters.copyInto(map.rangeMeters)
            for (i in 0 until n) map.orientation[i] = snapshot.orientations[i]?.copyOf()
            map.wallHeadingDeg = snapshot.wallHeadingDeg
            return map
        }
    }
}

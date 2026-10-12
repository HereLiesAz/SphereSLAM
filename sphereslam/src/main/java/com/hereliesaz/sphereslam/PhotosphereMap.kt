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
 * collapse to a single flag and a single glow. [lastUpdatedMs] is kept only so a time-to-live
 * re-check policy can decide *when* to flip a tile back to needing an update. `0L` is a valid
 * default update timestamp and is therefore not used as a capture-existence sentinel.
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
 * @property lastUpdatedMs monotonic time of the last update; `0L` is permitted.
 * @property representativeOrientation the attitude the tile was last captured at, or null.
 * @property rangeMeters estimated distance to the surface at the tile center, or null if unknown.
 */
class PanoramaTile(
    val id: TileId,
    val center: SphereCoverage.Direction,
    val needsUpdate: Boolean,
    val lastUpdatedMs: Long,
    representativeOrientation: FloatArray?,
    val rangeMeters: Float?,
) {
    private val representativeOrientationValue = representativeOrientation?.copyOf()

    /** Captured attitude quaternion [x, y, z, w], returned as a defensive copy. */
    val representativeOrientation: FloatArray?
        get() = representativeOrientationValue?.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PanoramaTile) return false
        return id == other.id &&
            center == other.center &&
            needsUpdate == other.needsUpdate &&
            lastUpdatedMs == other.lastUpdatedMs &&
            rangeMeters == other.rangeMeters &&
            (representativeOrientationValue?.contentEquals(other.representativeOrientationValue)
                ?: (other.representativeOrientationValue == null))
    }

    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + center.hashCode()
        r = 31 * r + needsUpdate.hashCode()
        r = 31 * r + lastUpdatedMs.hashCode()
        r = 31 * r + (representativeOrientationValue?.contentHashCode() ?: 0)
        r = 31 * r + (rangeMeters?.hashCode() ?: 0)
        return r
    }
}

/**
 * A plain, serialization-ready snapshot of a [PhotosphereMap]'s full state, for persisting the
 * photosphere across process death (alongside whatever fingerprint / atlas the host persists). The library
 * does not pick a wire format — the host serializes this DTO however it likes and rebuilds the map
 * with [PhotosphereMap.fromSnapshot].
 *
 * [lastUpdatedMs] values are whatever monotonic clock the host fed [PhotosphereMap.markUpdated];
 * such clocks reset on reboot, so a host that relies on TTL ages should decide on restore whether to
 * trust persisted timestamps or treat restored tiles as due for re-verification.
 *
 * Arrays are parallel, row-major (`sector * elevationBandCount + band`), length `tileCount`.
 * [scanned] records capture history independently of timestamp/freshness. [rangePresent] marks which
 * [rangeMeters] entries are meaningful, so snapshots produced by [PhotosphereMap.snapshot] contain
 * only finite float values and remain valid standard JSON without special NaN handling.
 *
 * When [scanned] is omitted (snapshots persisted before it existed) it is inferred per tile: a tile
 * counts as scanned if it is fresh, has a positive timestamp, or carries an orientation or a
 * (non-NaN) range. A tile captured at timestamp `0L` that has since gone stale and carries neither
 * tag is indistinguishable from a never-scanned one; pass [scanned] explicitly to preserve it. The
 * inference tolerates mismatched array lengths; [PhotosphereMap.fromSnapshot] then rejects them.
 *
 * [wallHeadingDeg] must be finite (or null for an unanchored map); [PhotosphereMap.fromSnapshot]
 * rejects NaN/infinite headings.
 */
class PhotosphereMapSnapshot(
    val sectorCount: Int,
    val viewableHalfAngleDeg: Float,
    val elevationBandCount: Int,
    val viewableElevationHalfAngleDeg: Float,
    val wallHeadingDeg: Float?,
    needsUpdate: BooleanArray,
    lastUpdatedMs: LongArray,
    orientations: Array<FloatArray?>,
    rangeMeters: FloatArray,
    scanned: BooleanArray = inferScanned(needsUpdate, lastUpdatedMs, orientations, rangeMeters),
    rangePresent: BooleanArray = BooleanArray(rangeMeters.size) { i ->
        !rangeMeters[i].isNaN()
    },
) {
    private val needsUpdateValue = needsUpdate.copyOf()
    private val lastUpdatedMsValue = lastUpdatedMs.copyOf()
    private val orientationsValue = Array(orientations.size) { orientations[it]?.copyOf() }
    private val rangeMetersValue = rangeMeters.copyOf()
    private val scannedValue = scanned.copyOf()
    private val rangePresentValue = rangePresent.copyOf()

    val needsUpdate: BooleanArray get() = needsUpdateValue.copyOf()
    val lastUpdatedMs: LongArray get() = lastUpdatedMsValue.copyOf()
    val orientations: Array<FloatArray?> get() = Array(orientationsValue.size) { orientationsValue[it]?.copyOf() }
    val rangeMeters: FloatArray get() = rangeMetersValue.copyOf()
    val scanned: BooleanArray get() = scannedValue.copyOf()
    val rangePresent: BooleanArray get() = rangePresentValue.copyOf()

    /** Data-class-style copy retained for persistence/test callers while preserving ownership. */
    fun copy(
        sectorCount: Int = this.sectorCount,
        viewableHalfAngleDeg: Float = this.viewableHalfAngleDeg,
        elevationBandCount: Int = this.elevationBandCount,
        viewableElevationHalfAngleDeg: Float = this.viewableElevationHalfAngleDeg,
        wallHeadingDeg: Float? = this.wallHeadingDeg,
        needsUpdate: BooleanArray = this.needsUpdate,
        lastUpdatedMs: LongArray = this.lastUpdatedMs,
        orientations: Array<FloatArray?> = this.orientations,
        rangeMeters: FloatArray = this.rangeMeters,
        scanned: BooleanArray = this.scanned,
        rangePresent: BooleanArray = this.rangePresent,
    ): PhotosphereMapSnapshot = PhotosphereMapSnapshot(
        sectorCount,
        viewableHalfAngleDeg,
        elevationBandCount,
        viewableElevationHalfAngleDeg,
        wallHeadingDeg,
        needsUpdate,
        lastUpdatedMs,
        orientations,
        rangeMeters,
        scanned,
        rangePresent,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhotosphereMapSnapshot) return false
        return sectorCount == other.sectorCount &&
            viewableHalfAngleDeg == other.viewableHalfAngleDeg &&
            elevationBandCount == other.elevationBandCount &&
            viewableElevationHalfAngleDeg == other.viewableElevationHalfAngleDeg &&
            wallHeadingDeg == other.wallHeadingDeg &&
            needsUpdateValue.contentEquals(other.needsUpdateValue) &&
            lastUpdatedMsValue.contentEquals(other.lastUpdatedMsValue) &&
            scannedValue.contentEquals(other.scannedValue) &&
            orientationsValue.contentDeepEquals(other.orientationsValue) &&
            rangePresentValue.contentEquals(other.rangePresentValue) &&
            rangeMetersValue.contentEquals(other.rangeMetersValue)
    }

    override fun hashCode(): Int {
        var r = sectorCount
        r = 31 * r + viewableHalfAngleDeg.hashCode()
        r = 31 * r + elevationBandCount
        r = 31 * r + viewableElevationHalfAngleDeg.hashCode()
        r = 31 * r + (wallHeadingDeg?.hashCode() ?: 0)
        r = 31 * r + needsUpdateValue.contentHashCode()
        r = 31 * r + lastUpdatedMsValue.contentHashCode()
        r = 31 * r + scannedValue.contentHashCode()
        r = 31 * r + orientationsValue.contentDeepHashCode()
        r = 31 * r + rangePresentValue.contentHashCode()
        r = 31 * r + rangeMetersValue.contentHashCode()
        return r
    }
}

/**
 * An orientation-indexed photosphere of tiles — by default the whole sphere, 24 × 6 tiles of
 * 15° × 30° — tracking which tiles still [needsUpdate], and how far each is through its scan
 * ([recordView], [scanProgress]). It is the content-bearing successor to [SphereCoverage]: where that counts
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
    private val scannedFlags = BooleanArray(grid.tileCount)
    private val orientation = arrayOfNulls<FloatArray>(grid.tileCount)

    /** Per-tile estimated range to the surface, metres; NaN = unknown. */
    private val rangeMeters = FloatArray(grid.tileCount) { Float.NaN }

    // Scan progress ([recordView]): per tile, which of its sample directions have been in frame, and
    // the area-weighted share seen. Transient (not in snapshots): a restored map restarts partial
    // tiles from zero; current tiles stay current.
    private val seenSamples = arrayOfNulls<BooleanArray>(grid.tileCount)
    private val progressValue = FloatArray(grid.tileCount)
    // Per tile: sample unit directions + area weights `x, y, z, w` for the current anchor.
    private var sampleCache: Array<DoubleArray?> = arrayOfNulls(grid.tileCount)
    private var sampleCacheAnchor = Float.NaN
    private var sampleCacheStep = Float.NaN

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
     * The result of [recordView].
     *
     * @property progressed tiles whose scan progress grew (including those that completed).
     * @property completed tiles that became current in this view.
     * @property firstCompleted the subset of [completed] that had never been scanned before.
     */
    data class ScanUpdate(
        val progressed: List<TileId>,
        val completed: List<TileId>,
        val firstCompleted: List<TileId>,
    )

    /** A tile still needing an update that is partly scanned: its extent and progress in `(0, 1)`. */
    data class RegionProgress(val region: SphereCoverage.TileRegion, val progress: Float)

    /**
     * Fold one camera view into the per-tile **scan progress**: every tile accumulates the share of
     * its area (sampled every [sampleStepDeg], weighted by `cos(elevation)`) that any view has had in
     * frame, so a tile fills as the artist sweeps across it rather than only when one frame happens to
     * hold most of it. A tile reaching [completeFraction] is marked current ([markUpdated], with
     * [representativeOrientation]); until then [scanProgress] / [regionsInProgress] report how far it
     * has come — what the coverage glow shows as a tile growing more opaque until it opens.
     *
     * Anchors the wall heading on the first call when unset, at the bearing the camera faces
     * ([AttitudeBasis.facingAzimuthDeg]): the axis heading near the horizon, the bearing the top of
     * the image points along when looking down at a floor — never the noise-dominated axis heading
     * near the poles.
     *
     * @param headingDeg camera-axis compass heading, degrees (0 = north, clockwise).
     * @param elevationDeg camera-axis elevation, degrees.
     * @param rollDeg image-right axis angle above the horizon, degrees (same sample as [headingDeg]).
     * @param lens the view's projection (`CoverageGlowProjection.Lens.fromIntrinsics`).
     * @return what changed; empty when an input is non-finite.
     */
    fun recordView(
        headingDeg: Float,
        elevationDeg: Float,
        rollDeg: Float,
        lens: CoverageGlowProjection.Lens,
        nowMs: Long = 0L,
        representativeOrientation: FloatArray? = null,
        completeFraction: Float = DEFAULT_COMPLETE_FRACTION,
        sampleStepDeg: Float = DEFAULT_COVERAGE_SAMPLE_STEP_DEG,
    ): ScanUpdate {
        require(completeFraction > 0f && completeFraction <= 1f) {
            "completeFraction must be in (0, 1], was $completeFraction"
        }
        require(sampleStepDeg.isFinite() && sampleStepDeg > 0f) { "sampleStepDeg must be finite and > 0" }
        val none = ScanUpdate(emptyList(), emptyList(), emptyList())
        if (!headingDeg.isFinite() || !elevationDeg.isFinite() || !rollDeg.isFinite()) return none
        val m = CoverageGlowProjection.worldFromCamera(headingDeg, elevationDeg, rollDeg) ?: return none
        if (wallHeadingDeg == null) {
            if (grid.bandOf(elevationDeg) == null) return none
            wallHeadingDeg = AttitudeBasis.facingAzimuthDeg(headingDeg, elevationDeg, rollDeg)
                ?: SphereGrid.norm360(headingDeg)
        }
        val anchor = wallHeadingDeg!!
        if (anchor != sampleCacheAnchor || sampleStepDeg != sampleCacheStep) {
            sampleCache = arrayOfNulls(grid.tileCount)
            sampleCacheAnchor = anchor
            sampleCacheStep = sampleStepDeg
            // Sample layouts changed: earlier masks no longer line up with them.
            seenSamples.fill(null)
            for (i in progressValue.indices) if (needsUpdateFlags[i]) progressValue[i] = 0f
        }
        val progressed = ArrayList<TileId>()
        val completed = ArrayList<TileId>()
        val firstCompleted = ArrayList<TileId>()
        for (s in 0 until grid.sectorCount) {
            for (b in 0 until grid.elevationBandCount) {
                val i = grid.index(s, b)
                if (!needsUpdateFlags[i]) continue
                val samples = samplesOf(s, b, anchor, sampleStepDeg)
                val n = samples.size / 4
                val seen = seenSamples[i] ?: BooleanArray(n).also { seenSamples[i] = it }
                var grew = false
                for (k in 0 until n) {
                    if (seen[k]) continue
                    val vx = samples[4 * k]; val vy = samples[4 * k + 1]; val vz = samples[4 * k + 2]
                    val depth = -(m[2] * vx + m[5] * vy + m[8] * vz)
                    if (depth <= 1e-4) continue
                    val x = lens.scaleX * (m[0] * vx + m[3] * vy + m[6] * vz) / depth + lens.offsetX
                    val y = lens.scaleY * (m[1] * vx + m[4] * vy + m[7] * vz) / depth + lens.offsetY
                    if (abs(x) <= 1.0 && abs(y) <= 1.0) {
                        seen[k] = true
                        grew = true
                    }
                }
                if (!grew) continue
                var seenW = 0.0
                var totalW = 0.0
                for (k in 0 until n) {
                    totalW += samples[4 * k + 3]
                    if (seen[k]) seenW += samples[4 * k + 3]
                }
                val p = if (totalW > 0.0) (seenW / totalW).toFloat() else 0f
                progressValue[i] = p
                val id = TileId(s, b)
                progressed += id
                if (p >= completeFraction) {
                    if (!scannedFlags[i]) firstCompleted += id
                    applyUpdated(id, nowMs, representativeOrientation, null)
                    completed += id
                }
            }
        }
        return ScanUpdate(progressed, completed, firstCompleted)
    }

    /** Scan progress of [id] in `[0, 1]`: 1 when current, the share seen while it needs an update. */
    fun scanProgress(id: TileId): Float {
        if (!inRange(id)) return 0f
        val i = grid.index(id.sector, id.band)
        return if (needsUpdateFlags[i]) progressValue[i] else 1f
    }

    /**
     * Tiles still needing an update that are partly scanned, as extents with their progress — the
     * glow's "being scanned" overlay. Empty until anchored.
     */
    fun regionsInProgress(): List<RegionProgress> {
        val anchor = wallHeadingDeg ?: return emptyList()
        val out = ArrayList<RegionProgress>()
        for (s in 0 until grid.sectorCount) {
            for (b in 0 until grid.elevationBandCount) {
                val i = grid.index(s, b)
                if (needsUpdateFlags[i] && progressValue[i] > 0f) {
                    out += RegionProgress(grid.regionOf(s, b, anchor), progressValue[i])
                }
            }
        }
        return out
    }

    private fun samplesOf(s: Int, b: Int, anchor: Float, step: Float): DoubleArray {
        val i = grid.index(s, b)
        sampleCache[i]?.let { return it }
        val r = grid.regionOf(s, b, anchor)
        val azSpan = r.azimuthEndDeg - r.azimuthStartDeg
        val elSpan = r.elevationTopDeg - r.elevationBottomDeg
        val nAz = kotlin.math.ceil(azSpan / step).toInt().coerceAtLeast(1)
        val nEl = kotlin.math.ceil(elSpan / step).toInt().coerceAtLeast(1)
        val out = DoubleArray(nAz * nEl * 4)
        var k = 0
        for (j in 0 until nEl) {
            val el = Math.toRadians((r.elevationBottomDeg + elSpan * (j + 0.5f) / nEl).toDouble())
            for (q in 0 until nAz) {
                val az = Math.toRadians((r.azimuthStartDeg + azSpan * (q + 0.5f) / nAz).toDouble())
                out[k++] = kotlin.math.cos(el) * kotlin.math.sin(az)
                out[k++] = kotlin.math.cos(el) * kotlin.math.cos(az)
                out[k++] = kotlin.math.sin(el)
                out[k++] = kotlin.math.cos(el)
            }
        }
        sampleCache[i] = out
        return out
    }

    /** Forget [i]'s partial scan (it needs a fresh one). */
    private fun clearProgress(i: Int) {
        seenSamples[i] = null
        progressValue[i] = 0f
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
     * If the wall heading is unset, the first call whose elevation lies inside the viewable
     * elevation arc anchors it; an out-of-band call never anchors. A no-op (returns null) when either
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
        // Band check first: an out-of-region sample must not anchor the map.
        val b = grid.bandOf(elevationDeg) ?: return null
        val anchor = wallHeadingDeg ?: SphereGrid.norm360(headingDeg).also { wallHeadingDeg = it }
        val s = grid.sectorOf(headingDeg, anchor) ?: return null
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
        scannedFlags[i] = true
        lastUpdatedMs[i] = nowMs
        clearProgress(i)
        if (orientationValue != null) orientation[i] = orientationValue.copyOf()
        if (rangeValue != null) rangeMeters[i] = rangeValue
    }

    /** Flag a single tile as needing an update. Ignores an out-of-range id. */
    fun markNeedsUpdate(id: TileId) {
        if (!inRange(id)) return
        val i = grid.index(id.sector, id.band)
        if (!needsUpdateFlags[i]) clearProgress(i)
        needsUpdateFlags[i] = true
    }

    /**
     * Record that a re-check found [id] in need of updating: flag it, **and** flag its edge-adjacent
     * neighbors (the propagation rule — a change here means the tiles around it are suspect too).
     * Neighbors already needing an update stay so; fresh neighbors flip back. Ignores an out-of-range id.
     */
    fun recordStale(id: TileId) {
        if (!inRange(id)) return
        markNeedsUpdate(id)
        for ((ns, nb) in grid.neighbors(id.sector, id.band)) markNeedsUpdate(TileId(ns, nb))
    }

    /** Whether [id] needs (re)scanning. An out-of-range id reads as not needing one. */
    fun needsUpdate(id: TileId): Boolean =
        if (inRange(id)) needsUpdateFlags[grid.index(id.sector, id.band)] else false

    /** Whether [id] has ever held a capture, independently of timestamp or current freshness. */
    fun hasBeenScanned(id: TileId): Boolean =
        if (inRange(id)) scannedFlags[grid.index(id.sector, id.band)] else false

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
                clearProgress(i)
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
     * Every tile still needing an update as its full angular extent — what the coverage haze covers
     * ([CoverageGlowProjection.projectRegions]). Accepted tiles are omitted, so the haze's border is
     * the edge of what has been scanned. Empty until the map is anchored.
     */
    fun regionsNeedingUpdate(): List<SphereCoverage.TileRegion> {
        val anchor = wallHeadingDeg ?: return emptyList()
        val out = ArrayList<SphereCoverage.TileRegion>()
        for (s in 0 until grid.sectorCount) {
            for (b in 0 until grid.elevationBandCount) {
                if (needsUpdateFlags[grid.index(s, b)]) out.add(grid.regionOf(s, b, anchor))
            }
        }
        return out
    }

    /** Every tile that is currently up to date (accepted and not expired), by id (anchor-independent). */
    fun currentTiles(): List<TileId> {
        val out = ArrayList<TileId>()
        for (s in 0 until grid.sectorCount) {
            for (b in 0 until grid.elevationBandCount) {
                if (!needsUpdateFlags[grid.index(s, b)]) out.add(TileId(s, b))
            }
        }
        return out
    }

    /**
     * Every up-to-date tile as its full angular extent — the complement of [regionsNeedingUpdate]
     * within the lattice. A "wash with holes" coverage overlay punches these out
     * (`CoverageGlowProjection.projectRegions(map.currentRegions(), ...)`). Empty until anchored.
     */
    fun currentRegions(): List<SphereCoverage.TileRegion> {
        val anchor = wallHeadingDeg ?: return emptyList()
        val out = ArrayList<SphereCoverage.TileRegion>()
        for (s in 0 until grid.sectorCount) {
            for (b in 0 until grid.elevationBandCount) {
                if (!needsUpdateFlags[grid.index(s, b)]) out.add(grid.regionOf(s, b, anchor))
            }
        }
        return out
    }

    /**
     * The tiles whose centers currently fall within the camera's view — the candidate set a tile
     * matcher should try to recognize this frame, so matching stays cheap no matter how large the map
     * grows. A tile is a candidate when its center lies inside the camera's pinhole frustum, or within
     * half the field of view of the camera axis in both of the camera's own angles (yaw about image-up,
     * pitch toward it) — the second keeps tiles whose centre sits just past a frustum corner. Both are
     * measured in the full, rolled camera basis ([AttitudeBasis]), so the set is as exact looking
     * straight down at a floor as at the horizon. Needs the map anchored.
     *
     * @param headingDeg camera-axis compass bearing now, degrees (0 = north, clockwise).
     * @param elevationDeg camera-axis angle above (+) / below (−) the horizon now, degrees.
     * @param hFovDeg horizontal field of view, degrees (`> 0`).
     * @param vFovDeg vertical field of view, degrees (`> 0`).
     * @param rollDeg image-right axis angle above the horizon, degrees (from the same sample as
     *   [headingDeg]; near the poles heading alone does not orient the frustum).
     * @return the in-view tiles' ids; empty when unanchored, an input is non-finite, or an fov `<= 0`.
     */
    fun tilesInView(
        headingDeg: Float,
        elevationDeg: Float,
        hFovDeg: Float,
        vFovDeg: Float,
        rollDeg: Float = 0f,
    ): List<TileId> {
        val anchor = wallHeadingDeg ?: return emptyList()
        if (!hFovDeg.isFinite() || !vFovDeg.isFinite() || hFovDeg <= 0f || vFovDeg <= 0f) return emptyList()
        val basis = AttitudeBasis.basis(headingDeg, elevationDeg, rollDeg) ?: return emptyList()
        val tanH = kotlin.math.tan(Math.toRadians(hFovDeg / 2.0).coerceAtMost(Math.PI / 2 - 1e-6))
        val tanV = kotlin.math.tan(Math.toRadians(vFovDeg / 2.0).coerceAtMost(Math.PI / 2 - 1e-6))
        val halfH = hFovDeg / 2.0 + 1e-4
        val halfV = vFovDeg / 2.0 + 1e-4
        val out = ArrayList<TileId>()
        for (s in 0 until grid.sectorCount) {
            val az = Math.toRadians(SphereGrid.norm360(anchor + grid.sectorCenterDelta(s)).toDouble())
            for (b in 0 until grid.elevationBandCount) {
                val el = Math.toRadians(grid.bandCenterElevation(b).toDouble())
                val d = doubleArrayOf(
                    kotlin.math.cos(el) * kotlin.math.sin(az),
                    kotlin.math.cos(el) * kotlin.math.cos(az),
                    kotlin.math.sin(el),
                )
                val z = AttitudeBasis.dot(d, basis.forward)
                if (z <= 0.0) continue
                val x = AttitudeBasis.dot(d, basis.right)
                val y = AttitudeBasis.dot(d, basis.up)
                val inFrustum = abs(x) <= z * tanH + 1e-9 && abs(y) <= z * tanV + 1e-9
                val yaw = Math.toDegrees(kotlin.math.atan2(x, z))
                val pitch = Math.toDegrees(kotlin.math.asin(y.coerceIn(-1.0, 1.0)))
                if (inFrustum || (abs(yaw) <= halfH && abs(pitch) <= halfV)) out.add(TileId(s, b))
            }
        }
        return out
    }

    /**
     * The tiles a camera frame actually covers: each tile whose solid angle lies inside the frame for
     * at least [minCoveredFraction]. Where [tileAt] names only the tile under the view axis, this is
     * what a keyframe really saw — so the area visible in a frame stays current after it leaves the
     * screen. A tile is sampled every [sampleStepDeg] (cell centres, weighted by `cos(elevation)` for
     * equal-area), each sample projected through the frame's attitude and [lens].
     *
     * @param headingDeg camera-axis compass heading, degrees (0 = north, clockwise).
     * @param elevationDeg camera-axis elevation, degrees.
     * @param rollDeg image-right axis angle above the horizon, degrees (see
     *   [CameraAttitudeProvider.cameraRollDegrees]).
     * @param lens the frame's projection, e.g. `CoverageGlowProjection.Lens.fromIntrinsics` of the
     *   keyframe's intrinsics.
     * @param minCoveredFraction share of a tile's area that must be in frame, `(0, 1]`.
     * @param sampleStepDeg sampling pitch over a tile, degrees, `> 0`.
     * @return covered tile ids; empty when unanchored or an input is non-finite.
     */
    fun tilesCovered(
        headingDeg: Float,
        elevationDeg: Float,
        rollDeg: Float,
        lens: CoverageGlowProjection.Lens,
        minCoveredFraction: Float = DEFAULT_MIN_COVERED_FRACTION,
        sampleStepDeg: Float = DEFAULT_COVERAGE_SAMPLE_STEP_DEG,
    ): List<TileId> {
        require(minCoveredFraction > 0f && minCoveredFraction <= 1f) {
            "minCoveredFraction must be in (0, 1], was $minCoveredFraction"
        }
        require(sampleStepDeg.isFinite() && sampleStepDeg > 0f) { "sampleStepDeg must be finite and > 0" }
        val anchor = wallHeadingDeg ?: return emptyList()
        val m = CoverageGlowProjection.worldFromCamera(headingDeg, elevationDeg, rollDeg) ?: return emptyList()
        val out = ArrayList<TileId>()
        for (s in 0 until grid.sectorCount) {
            for (b in 0 until grid.elevationBandCount) {
                val r = grid.regionOf(s, b, anchor)
                val azSpan = r.azimuthEndDeg - r.azimuthStartDeg
                val elSpan = r.elevationTopDeg - r.elevationBottomDeg
                val nAz = kotlin.math.ceil(azSpan / sampleStepDeg).toInt().coerceAtLeast(1)
                val nEl = kotlin.math.ceil(elSpan / sampleStepDeg).toInt().coerceAtLeast(1)
                var inFrame = 0.0
                var total = 0.0
                for (j in 0 until nEl) {
                    val el = Math.toRadians((r.elevationBottomDeg + elSpan * (j + 0.5f) / nEl).toDouble())
                    val w = kotlin.math.cos(el)
                    for (i in 0 until nAz) {
                        val az = Math.toRadians((r.azimuthStartDeg + azSpan * (i + 0.5f) / nAz).toDouble())
                        total += w
                        val vx = kotlin.math.cos(el) * kotlin.math.sin(az)
                        val vy = kotlin.math.cos(el) * kotlin.math.cos(az)
                        val vz = kotlin.math.sin(el)
                        val depth = -(m[2] * vx + m[5] * vy + m[8] * vz)
                        if (depth <= 1e-4) continue
                        val x = lens.scaleX * (m[0] * vx + m[3] * vy + m[6] * vz) / depth + lens.offsetX
                        val y = lens.scaleY * (m[1] * vx + m[4] * vy + m[7] * vz) / depth + lens.offsetY
                        if (abs(x) <= 1.0 && abs(y) <= 1.0) inFrame += w
                    }
                }
                if (total > 0.0 && inFrame / total >= minCoveredFraction) out.add(TileId(s, b))
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
     * This only seeds KPM/relocalization; it adds nothing to the map. Only tiles that have actually
     * been captured appear; scan history is independent of [PanoramaTile.lastUpdatedMs] because `0L`
     * is a valid public update timestamp.
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

    /**
     * A snapshot of one tile, or null for an out-of-range id. Until the map is anchored the center is
     * relative to heading 0 (a placeholder, not the tile's real direction).
     */
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
        scannedFlags.fill(false)
        orientation.fill(null)
        rangeMeters.fill(Float.NaN)
        wallHeadingDeg = null
        seenSamples.fill(null)
        progressValue.fill(0f)
        sampleCache = arrayOfNulls(grid.tileCount)
        sampleCacheAnchor = Float.NaN
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
        rangeMeters = FloatArray(rangeMeters.size) { i ->
            if (rangeMeters[i].isNaN()) 0f else rangeMeters[i]
        },
        scanned = scannedFlags.copyOf(),
        rangePresent = BooleanArray(rangeMeters.size) { i -> !rangeMeters[i].isNaN() },
    )

    private fun inRange(id: TileId): Boolean =
        id.sector in 0 until grid.sectorCount && id.band in 0 until grid.elevationBandCount

    companion object {
        /** [tilesCovered] default: a tile counts as seen when at least this share of it was in frame. */
        const val DEFAULT_MIN_COVERED_FRACTION: Float = 0.6f

        /** [recordView] default: a tile is scanned once this share of its area has been in frame. */
        const val DEFAULT_COMPLETE_FRACTION: Float = 0.9f

        /** [tilesCovered] / [recordView] default sampling pitch over a tile, degrees. */
        const val DEFAULT_COVERAGE_SAMPLE_STEP_DEG: Float = 5f

        /**
         * Absolute direction of image pixel ([u], [v]) for a pinhole camera ([fx], [fy], [cx], [cy];
         * OpenCV pixel convention, v down) whose optical axis points at ([cameraHeadingDeg],
         * [cameraElevationDeg]) with image-right rolled [cameraRollDeg] above the horizon
         * ([CameraAttitudeProvider.cameraRollDegrees]).
         *
         * Exact ray rotation, not `heading + atan(x)`: with roll, a horizontal pixel offset moves the
         * direction in elevation too, and even unrolled the azimuth of an off-axis pixel depends on
         * elevation.
         *
         * Defined at every elevation ([AttitudeBasis]).
         *
         * @return the direction, or null for non-finite input or non-positive focal lengths.
         */
        fun directionOfPixel(
            u: Float,
            v: Float,
            fx: Float,
            fy: Float,
            cx: Float,
            cy: Float,
            cameraHeadingDeg: Float,
            cameraElevationDeg: Float,
            cameraRollDeg: Float = 0f,
        ): SphereCoverage.Direction? {
            val inputs = floatArrayOf(u, v, fx, fy, cx, cy, cameraHeadingDeg, cameraElevationDeg, cameraRollDeg)
            if (inputs.any { !it.isFinite() } || fx <= 0f || fy <= 0f) return null
            val ray = rayOfPixel(u, v, fx, fy, cx, cy, cameraHeadingDeg, cameraElevationDeg, cameraRollDeg) ?: return null
            return AttitudeBasis.direction(ray)
        }

        /** [directionOfPixel] as an East-North-Up vector (not normalized), or null for bad input. */
        internal fun rayOfPixel(
            u: Float,
            v: Float,
            fx: Float,
            fy: Float,
            cx: Float,
            cy: Float,
            cameraHeadingDeg: Float,
            cameraElevationDeg: Float,
            cameraRollDeg: Float,
        ): DoubleArray? {
            if (fx <= 0f || fy <= 0f || !u.isFinite() || !v.isFinite()) return null
            val b = AttitudeBasis.basis(cameraHeadingDeg, cameraElevationDeg, cameraRollDeg) ?: return null
            val xn = (u - cx) / fx.toDouble()
            val yn = (v - cy) / fy.toDouble()
            return DoubleArray(3) { b.forward[it] + xn * b.right[it] - yn * b.up[it] }
        }

        /**
         * Rebuild a map from a [PhotosphereMapSnapshot]. The grid dimensions come from the snapshot;
         * the per-tile arrays must all be length `sectorCount * elevationBandCount`.
         *
         * @throws IllegalArgumentException if the snapshot's grid is invalid, an array length is wrong,
         *   a present range is non-finite, or [PhotosphereMapSnapshot.wallHeadingDeg] is NaN/infinite.
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
                    snapshot.scanned.size == n &&
                    snapshot.orientations.size == n &&
                    snapshot.rangeMeters.size == n &&
                    snapshot.rangePresent.size == n,
            ) { "snapshot arrays must all be length $n" }
            snapshot.needsUpdate.copyInto(map.needsUpdateFlags)
            snapshot.lastUpdatedMs.copyInto(map.lastUpdatedMs)
            snapshot.scanned.copyInto(map.scannedFlags)
            for (i in 0 until n) {
                val persistedRange = snapshot.rangeMeters[i]
                if (snapshot.rangePresent[i]) {
                    require(persistedRange.isFinite()) { "present rangeMeters[$i] must be finite" }
                    map.rangeMeters[i] = persistedRange
                } else {
                    map.rangeMeters[i] = Float.NaN
                }
                map.orientation[i] = snapshot.orientations[i]?.copyOf()
            }
            val heading = snapshot.wallHeadingDeg
            require(heading == null || heading.isFinite()) {
                "wallHeadingDeg must be finite or null, was $heading"
            }
            map.wallHeadingDeg = heading?.let { SphereGrid.norm360(it) }
            return map
        }
    }
}

/** Legacy `scanned` inference for [PhotosphereMapSnapshot]; bounds-safe on mismatched arrays. */
private fun inferScanned(
    needsUpdate: BooleanArray,
    lastUpdatedMs: LongArray,
    orientations: Array<FloatArray?>,
    rangeMeters: FloatArray,
): BooleanArray = BooleanArray(lastUpdatedMs.size) { i ->
    lastUpdatedMs[i] > 0L ||
        needsUpdate.getOrNull(i) == false ||
        orientations.getOrNull(i) != null ||
        rangeMeters.getOrNull(i)?.isNaN() == false
}

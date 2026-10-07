package com.hereliesaz.sphereslam

/**
 * The per-frame freshness loop that keeps a [PhotosphereMap] current as the artist sweeps — the
 * "continuously checked / updated tiles" layer. It is deliberately **recognition-source-agnostic**,
 * the same way [com.hereliesaz.sphereslam.reloc.RobustTrackingLoop] is pose-source-agnostic: it does
 * not run a matcher or touch OpenCV. Each frame the host asks it which tiles are worth trying
 * ([candidates]), recognizes them however it likes (e.g. the reloc `TileMatcher` over their
 * fingerprints), then hands back the verdict ([onFrame]); the loop folds that into the map's single
 * `needsUpdate` state.
 *
 * The three freshness forces, in order each frame:
 * 1. **Age (TTL).** Tiles last updated longer than [ReviewConfig.ttlMs] ago flip back to needing an
 *    update, so a sweep is re-verified over time. A [ReviewConfig.ttlMs] of 0 disables age expiry.
 * 2. **Confirm.** A tile recognized this frame is marked current.
 * 3. **Change.** A tile that was current but is now looked-at-and-unrecognized is deemed changed:
 *    it, **and its neighbors**, go back to needing an update (the propagation rule). A tile that was
 *    never scanned failing to match is expected, not a change, so it does not propagate.
 *
 * Pure Kotlin, no sensor or matcher dependency; feed it absolute (heading, elevation) degrees. Not
 * thread-safe; drive it and its map from one worker.
 *
 * @property map the photosphere whose freshness this maintains.
 * @property config the field of view used for candidate prediction and the TTL.
 */
class PhotosphereReviewLoop(
    private val map: PhotosphereMap,
    private val config: ReviewConfig = ReviewConfig(),
) {
    /**
     * @property hFovDeg horizontal field of view for candidate prediction, degrees (`> 0`).
     * @property vFovDeg vertical field of view for candidate prediction, degrees (`> 0`).
     * @property ttlMs how long a capture stays current before age flips it back; 0 disables age expiry.
     */
    data class ReviewConfig(
        val hFovDeg: Float = 60f,
        val vFovDeg: Float = 45f,
        val ttlMs: Long = 0L,
    ) {
        init {
            require(hFovDeg > 0f && vFovDeg > 0f) { "fields of view must be positive" }
            require(ttlMs >= 0L) { "ttlMs must be >= 0" }
        }
    }

    /**
     * The result of folding one frame in.
     *
     * @property candidates the tiles now in view — the set to try to recognize next frame.
     * @property directionsNeedingUpdate absolute directions of every tile still needing an update,
     *   for the coverage glow.
     * @property coverageFraction fraction of the map that is up to date, in `[0, 1]`.
     */
    data class Outcome(
        val candidates: List<TileId>,
        val directionsNeedingUpdate: List<SphereCoverage.Direction>,
        val coverageFraction: Float,
    )

    /** The tiles in view for ([headingDeg], [elevationDeg]) — the recognition candidate set. */
    fun candidates(headingDeg: Float, elevationDeg: Float): List<TileId> =
        map.tilesInView(headingDeg, elevationDeg, config.hFovDeg, config.vFovDeg)

    /**
     * Fold one frame's recognition verdict into the map and return the refreshed view.
     *
     * @param headingDeg camera-axis compass bearing now, degrees.
     * @param elevationDeg camera-axis elevation now, degrees.
     * @param nowMs monotonic clock for TTL and update timestamps.
     * @param confirmed tiles the matcher recognized this frame (marked current).
     * @param checkedButUnmatched in-view tiles the matcher tried and failed; a previously-current one
     *   here is treated as changed (stale + neighbor propagation), a never-scanned one is ignored.
     */
    fun onFrame(
        headingDeg: Float,
        elevationDeg: Float,
        nowMs: Long,
        confirmed: Collection<TileId> = emptyList(),
        checkedButUnmatched: Collection<TileId> = emptyList(),
    ): Outcome {
        if (config.ttlMs > 0L) map.expireOlderThan(nowMs - config.ttlMs)
        for (id in confirmed) map.markUpdated(id, nowMs)
        for (id in checkedButUnmatched) {
            val tile = map.tile(id) ?: continue
            // Only a tile that actually held a capture can have "changed"; a never-scanned miss is
            // expected and must not spread suspicion to its neighbors.
            if (tile.lastUpdatedMs > 0L && !tile.needsUpdate) map.recordStale(id)
        }
        return Outcome(
            candidates = candidates(headingDeg, elevationDeg),
            directionsNeedingUpdate = map.directionsNeedingUpdate(),
            coverageFraction = map.coverageFraction(),
        )
    }
}

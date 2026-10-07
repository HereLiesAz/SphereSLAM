package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.PhotosphereMap
import com.hereliesaz.sphereslam.PhotosphereReviewLoop
import com.hereliesaz.sphereslam.SphereCoverage
import com.hereliesaz.sphereslam.TileId
import org.opencv.core.Mat

/**
 * The single entry point that ties SphereSLAM's pieces into one per-frame call, so a consumer drives
 * tracking without hand-wiring the matcher, the robustness loop, the photosphere, relock seeding, the
 * review loop, and (optionally) a low-latency predictor. Feed it a frame plus the device attitude and
 * heading each tick; it returns the pose to draw and keeps the coverage glow current.
 *
 * **What it does _not_ do.** It never generates fingerprints and never decides a tile is trustworthy
 * to anchor on — both are the host's (fingerprint generation and corroboration are proprietary). The
 * host builds fingerprints and hands them in with [supplyTile]; the trust call rides the
 * [TileCorroborator] seam. This object is the wiring, not the judgment.
 *
 * **Frames.** The matcher and tile poses are row-major 4×4 ([RelocResult]); the robustness loop and
 * the predictor are column-major 16 (OpenGL). The session owns that conversion so a consumer sees one
 * convention: the returned [onFrame] pose is **column-major 16, camera-from-map**, ready to hand a GL
 * renderer.
 *
 * This is the **photosphere / depth** entry layer — anchoring across a space. For anchoring to a
 * single flat reference surface (a mural wall, a page), use the planar-wall KPM layer instead
 * ([com.hereliesaz.sphereslam.SphereSlam] and its session family). Both are stable, frozen public
 * API as of 1.0; they are two layers by design, not duplicates.
 *
 * Not thread-safe; drive it from one worker. The OpenCV-touching path ([onFrame]) needs native; the
 * decisions it is built from ([chooseCandidates], [rowMajorToColumnMajor]) are pure and unit-tested.
 *
 * @param relocalizer the PnP relocalizer the matcher wraps (its intrinsics gate the solve).
 * @param photosphere the tile map (freshness, relock seeds, glow, persistence).
 * @param loop the robustness loop (acceptance, stabilization, state).
 * @param reviewConfig candidate field-of-view and TTL for the review loop.
 * @param relockSeedLimit how many relock candidates to try when tracking is lost (the cheap cap).
 * @param assumedReprojectionError reprojection error reported to the loop for a match (the matcher
 *   surfaces inliers, not residual); 0 means "do not reject on residual". Tune per front-end.
 * @param predictor optional low-latency early-anchor / off-page bridge (see [EarlyPosePredictor]).
 */
class SphereSlamSession(
    private val relocalizer: Relocalizer,
    private val photosphere: PhotosphereMap,
    private val loop: RobustTrackingLoop = RobustTrackingLoop(),
    reviewConfig: PhotosphereReviewLoop.ReviewConfig = PhotosphereReviewLoop.ReviewConfig(),
    private val relockSeedLimit: Int = 8,
    private val assumedReprojectionError: Float = 0f,
    private val predictor: EarlyPosePredictor? = null,
) {
    private val matcher = TileMatcher<TileId>(relocalizer)
    private val reviewLoop = PhotosphereReviewLoop(photosphere, reviewConfig)
    private val fingerprints = HashMap<TileId, Fingerprint>()

    private var lastState: TrackingState = TrackingState.INITIALIZING

    /** Absolute directions of every tile still needing an update — drive the coverage glow from this. */
    var glowDirections: List<SphereCoverage.Direction> = emptyList()
        private set

    /** Fraction of the map that is up to date, in `[0, 1]`. */
    var coverageFraction: Float = 0f
        private set

    /** Hand in (or replace) the fingerprint the host built for a tile. The session never builds these. */
    fun supplyTile(id: TileId, fingerprint: Fingerprint) {
        fingerprints[id] = fingerprint
    }

    /** Forget a tile's fingerprint (e.g. it went stale and was rebuilt elsewhere). */
    fun removeTile(id: TileId) {
        fingerprints.remove(id)
    }

    /**
     * Process one camera frame.
     *
     * @param gray single-channel frame.
     * @param attitudeQuat device attitude `[x, y, z, w]` now (for the predictor; may be identity if unused).
     * @param headingDeg camera-axis compass bearing now, degrees.
     * @param elevationDeg camera-axis elevation now, degrees.
     * @param frameTimestampNs frame timestamp (ns, realtime clock).
     * @param nowElapsedRealtimeNs current realtime clock (ns).
     * @param timestampSource the camera's timestamp source.
     * @param nowMs monotonic millisecond clock.
     * @return the pose to draw this frame (column-major 16, camera-from-map), or null to draw nothing.
     */
    fun onFrame(
        gray: Mat,
        attitudeQuat: FloatArray,
        headingDeg: Float,
        elevationDeg: Float,
        frameTimestampNs: Long,
        nowElapsedRealtimeNs: Long,
        timestampSource: CameraTimestampSource,
        nowMs: Long,
    ): FloatArray? {
        // 1. Which tiles to try: the in-view cone normally, the recency/adjacency relock set when lost.
        val candidateIds = chooseCandidates(lastState, headingDeg, elevationDeg)
        val candidates = LinkedHashMap<TileId, Fingerprint>(candidateIds.size)
        for (id in candidateIds) fingerprints[id]?.let { candidates[id] = it }

        // 2. Recognize every attempted candidate, while still using only the strongest pose for
        // tracking. The full verdict is retained so freshness never mistakes a lower-scoring
        // recognized tile for an unmatched/changed tile.
        val evaluation = if (candidates.isEmpty()) null else matcher.evaluate(gray, candidates)
        val match = evaluation?.best
        val columnMajorPose = match?.let { toColumnMajorMapPose(it) }

        // 3. Robustness loop gates/stabilizes; a bridge may be offered by the predictor.
        val bridgeAvailable = predictor?.hasReference == true
        val outcome = loop.onFrame(
            viewMatrix = columnMajorPose,
            inlierCount = match?.inliers ?: 0,
            reprojectionError = assumedReprojectionError,
            frameTimestampNs = frameTimestampNs,
            nowElapsedRealtimeNs = nowElapsedRealtimeNs,
            timestampSource = timestampSource,
            nowMs = nowMs,
            bridgeAvailable = bridgeAvailable,
        )
        lastState = outcome.state

        // 4. Keep the predictor's reference on a fresh accept; otherwise fall back to its prediction.
        val renderPose = when {
            outcome.accepted && outcome.renderPose != null -> {
                predictor?.correct(outcome.renderPose, attitudeQuat)
                outcome.renderPose
            }
            outcome.renderPose != null -> outcome.renderPose
            else -> predictor?.predict(attitudeQuat)
        }

        // 5. Fold truthful per-candidate recognition verdicts into the photosphere; refresh glow +
        // coverage. If live-feature detection failed, evaluate() reports neither matches nor misses.
        val confirmed = evaluation?.recognized ?: emptySet()
        val checked = evaluation?.checkedButUnmatched ?: emptySet()
        val review = reviewLoop.onFrame(headingDeg, elevationDeg, nowMs, confirmed, checked)
        glowDirections = review.directionsNeedingUpdate
        coverageFraction = review.coverageFraction

        return renderPose
    }

    /** Lift a match into the map frame: tile fingerprints compose through [TileFingerprint.anchorFromTile]; a planar reference is already in the map frame. */
    private fun toColumnMajorMapPose(match: TileMatcher.Match<TileId>): FloatArray {
        val fp = fingerprints[match.key]
        val rowMajorMap = if (fp is TileFingerprint) {
            TilePose.cameraFromMap(match.cameraFromObject, fp.anchorFromTile)
        } else {
            match.cameraFromObject
        }
        return rowMajorToColumnMajor(rowMajorMap)
    }

    /** Reset tracking and the predictor reference (new canonical frame). Keeps supplied fingerprints. */
    fun reset() {
        lastState = TrackingState.INITIALIZING
        predictor?.reset()
    }

    /** The in-view candidate set, or the relock set when tracking is lost/reacquiring. */
    internal fun chooseCandidates(state: TrackingState, headingDeg: Float, elevationDeg: Float): List<TileId> =
        if (reacquiring(state)) {
            photosphere.relockSeeds(relockSeedLimit)
        } else {
            reviewLoop.candidates(headingDeg, elevationDeg)
        }

    companion object {
        /** Whether tracking has lost its lock, so relock seeding — not the in-view cone — drives candidates. */
        internal fun reacquiring(state: TrackingState): Boolean =
            state == TrackingState.LOST || state == TrackingState.REACQUIRING

        /** Row-major 4×4 → column-major 16 (a transpose of storage for the same transform). */
        fun rowMajorToColumnMajor(rowMajor: FloatArray): FloatArray {
            require(rowMajor.size == 16) { "matrix must be length 16" }
            val out = FloatArray(16)
            for (row in 0 until 4) for (col in 0 until 4) out[col * 4 + row] = rowMajor[row * 4 + col]
            return out
        }
    }
}

/**
 * A low-latency pose predictor the session folds in to hide solve lag and bridge off-page views —
 * the shape of [AttitudePosePredictor] (`:reloc`), kept as an interface so the session depends on the
 * capability, not the concrete class. Poses are column-major 16 (the loop's convention).
 */
interface EarlyPosePredictor {
    /** Whether a reference has been installed and [predict] can return a pose. */
    val hasReference: Boolean

    /** Install a fresh solved pose as the prediction reference, paired with the attitude at that frame. */
    fun correct(columnMajorView: FloatArray, attitudeQuat: FloatArray)

    /** The predicted current pose for [attitudeQuat], or null before the first [correct]. */
    fun predict(attitudeQuat: FloatArray): FloatArray?

    /** Forget the reference (new session / reference change). */
    fun reset()
}

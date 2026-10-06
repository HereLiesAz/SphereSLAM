package com.hereliesaz.sphereslam.reloc

/**
 * Ties the relocalization robustness pieces into one per-frame loop: stale-frame rejection
 * ([ObservationAgePolicy]) → quality + continuity acceptance ([PoseAcceptancePolicy]) → temporal
 * smoothing ([PoseStabilizer]) → lock/reacquire/bridge/lost hysteresis ([TrackingStateMachine]).
 *
 * It is **pose-source-agnostic**: the caller supplies each frame's best candidate pose, however it
 * was produced — a KPM planar match, a fingerprint [Relocalizer] solve used as a fallback when KPM
 * misses, or null when neither found anything. That keeps the loop pure and unit-testable while
 * letting a consumer wire in whatever front-ends it has. A typical driver:
 *
 * ```
 * val candidate = kpmPose ?: relocalizer.relocalize(grayFrame)?.toCandidate()   // KPM, else fingerprint
 * val outcome = loop.onFrame(
 *     viewMatrix = candidate?.viewMatrix,
 *     inlierCount = candidate?.inliers ?: 0,
 *     reprojectionError = candidate?.error ?: Float.MAX_VALUE,
 *     frameTimestampNs = tsNs, nowElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
 *     timestampSource = source, nowMs = SystemClock.uptimeMillis(),
 * )
 * outcome.renderPose?.let { draw(it) }
 * ```
 *
 * A bridge pose across a brief visual miss (from a gyro rotation bridge) is supplied through the
 * optional [bridgeRotatedPose] callback; when absent, a miss simply yields no render pose.
 *
 * Not thread-safe; drive it from one worker. [reset] clears all state on a new reference / session.
 *
 * @property referenceWidthUnits the reference width in the pose's units, for the acceptance policy's
 *   translation-jump limit.
 * @property acceptancePolicy quality + continuity gate.
 * @property agePolicy stale-frame gate.
 * @property stabilizer temporal smoother.
 * @property stateMachine tracking-state hysteresis.
 * @property bridgeRotatedPose optional: given the last rendered pose, return a gyro-bridged pose to
 *   render during an [TrackingState.IMU_BRIDGE] miss, or null to render nothing.
 */
class RobustTrackingLoop(
    private val referenceWidthUnits: Float = 1f,
    private val acceptancePolicy: PoseAcceptancePolicy = PoseAcceptancePolicy(),
    private val agePolicy: ObservationAgePolicy = ObservationAgePolicy(),
    private val stabilizer: PoseStabilizer = PoseStabilizer(),
    private val stateMachine: TrackingStateMachine = TrackingStateMachine(),
    private val bridgeRotatedPose: ((lastRenderedPose: FloatArray) -> FloatArray?)? = null,
) {
    /**
     * The result of processing one frame.
     *
     * @property renderPose the pose to draw this frame (stabilized accept, or a bridge pose), or null
     *   when nothing should be drawn.
     * @property state the tracking state after this frame.
     * @property accepted whether a visual pose was accepted this frame.
     * @property rejection why a supplied pose was rejected, when [accepted] is false and one was given.
     * @property stale whether the frame was dropped as too old.
     */
    data class Outcome(
        val renderPose: FloatArray?,
        val state: TrackingState,
        val accepted: Boolean,
        val rejection: PoseRejection? = null,
        val stale: Boolean = false,
    )

    private var lastAccepted: FloatArray? = null
    private var lastRendered: FloatArray? = null

    /**
     * Process one frame's candidate pose.
     *
     * @param viewMatrix the candidate pose (column-major length 16), or null if no front-end matched.
     * @param inlierCount inliers backing the candidate.
     * @param reprojectionError reprojection error of the candidate.
     * @param frameTimestampNs the frame's timestamp (ns, realtime clock).
     * @param nowElapsedRealtimeNs current time on that clock (ns).
     * @param timestampSource the camera's timestamp source.
     * @param nowMs a monotonic millisecond clock for state-machine timing.
     * @param bridgeAvailable whether a gyro bridge can hold a brief miss from a lock.
     * @return the [Outcome].
     */
    fun onFrame(
        viewMatrix: FloatArray?,
        inlierCount: Int,
        reprojectionError: Float,
        frameTimestampNs: Long,
        nowElapsedRealtimeNs: Long,
        timestampSource: CameraTimestampSource,
        nowMs: Long,
        bridgeAvailable: Boolean = false,
    ): Outcome {
        if (viewMatrix == null) return miss(bridgeAvailable, nowMs)

        val age = agePolicy.evaluate(frameTimestampNs, nowElapsedRealtimeNs, timestampSource)
        if (age.stale) return miss(bridgeAvailable, nowMs).copy(stale = true)

        val reacquiring = stateMachine.state != TrackingState.LOCKED
        val acceptance = acceptancePolicy.evaluate(
            viewMatrix = viewMatrix,
            inlierCount = inlierCount,
            reprojectionError = reprojectionError,
            previousViewMatrix = lastAccepted,
            referenceWidthUnits = referenceWidthUnits,
            reacquiring = reacquiring,
        )
        if (!acceptance.accepted) {
            return miss(bridgeAvailable, nowMs).copy(rejection = acceptance.rejection)
        }

        val rendered = stabilizer.stabilize(viewMatrix)
        lastAccepted = viewMatrix.copyOf()
        lastRendered = rendered
        val state = stateMachine.onAcceptedVisual(nowMs)
        return Outcome(renderPose = rendered, state = state, accepted = true)
    }

    private fun miss(bridgeAvailable: Boolean, nowMs: Long): Outcome {
        val state = stateMachine.onVisualMiss(bridgeAvailable, nowMs)
        // A real discontinuity is coming when we re-lock; snap instead of blending across the gap.
        stabilizer.reset()
        val bridged = if (state == TrackingState.IMU_BRIDGE) {
            lastRendered?.let { prev -> bridgeRotatedPose?.invoke(prev) }
        } else {
            null
        }
        return Outcome(renderPose = bridged, state = state, accepted = false)
    }

    /** Clear all state for a new reference / session. */
    fun reset() {
        lastAccepted = null
        lastRendered = null
        stabilizer.reset()
        stateMachine.reset()
    }
}

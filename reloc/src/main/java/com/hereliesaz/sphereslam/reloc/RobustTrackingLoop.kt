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
 * optional [bridgeRotatedPose] callback; when absent, a miss simply yields no render pose. A consumer
 * that builds its own richer bridge output can instead ignore the render pose on an
 * [TrackingState.IMU_BRIDGE] outcome and produce its own, using the loop purely for the state decision.
 *
 * The displayed pose may differ from the pose that gates: [correctAcceptedPose], when supplied,
 * post-processes an accepted pose (e.g. a corroborating drift-trim toward an independent relocalizer)
 * before smoothing, while acceptance and continuity still gate on the raw candidate. This keeps the
 * correction out of the tracking decision — it only changes what is drawn.
 *
 * Continuity is gated against the last accepted raw pose, with two exceptions: the baseline is
 * dropped once tracking is [TrackingState.LOST], and during [TrackingState.INITIALIZING] a candidate
 * that fails continuity against the unconfirmed seed replaces it (see the confirmation rule in
 * [TrackingStateConfig]) instead of being vetoed by a possibly-outlying first pose.
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
 *   [com.hereliesaz.sphereslam.attitude.AttitudeRotationBridge.bridgeFunction] provides one from
 *   device attitude (mark its reference after each accepted frame).
 * @property correctAcceptedPose optional: given the accepted raw pose (column-major length 16), return
 *   the pose to render for it (length 16) — a post-acceptance correction smoothed and drawn in place of
 *   the raw pose. Gating is unaffected; defaults to the identity (render the raw accepted pose).
 */
@ExperimentalSphereSlamRelocApi
class RobustTrackingLoop(
    private val referenceWidthUnits: Float = 1f,
    private val acceptancePolicy: PoseAcceptancePolicy = PoseAcceptancePolicy(),
    private val agePolicy: ObservationAgePolicy = ObservationAgePolicy(),
    private val stabilizer: PoseStabilizer = PoseStabilizer(),
    private val stateMachine: TrackingStateMachine = TrackingStateMachine(),
    private val bridgeRotatedPose: ((lastRenderedPose: FloatArray) -> FloatArray?)? = null,
    private val correctAcceptedPose: ((accepted: FloatArray) -> FloatArray)? = null,
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

        // A visual return from IMU_BRIDGE is also a reacquisition: the bridge may have legitimately
        // carried the camera beyond the tight per-frame continuity envelope.
        val reacquiring =
            stateMachine.state == TrackingState.IMU_BRIDGE ||
                stateMachine.state == TrackingState.REACQUIRING ||
                stateMachine.state == TrackingState.LOST
        val acceptance = acceptancePolicy.evaluate(
            viewMatrix = viewMatrix,
            inlierCount = inlierCount,
            reprojectionError = reprojectionError,
            previousViewMatrix = lastAccepted,
            referenceWidthUnits = referenceWidthUnits,
            reacquiring = reacquiring,
        )
        if (!acceptance.accepted) {
            val continuityFailure = acceptance.rejection == PoseRejection.TRANSLATION_JUMP ||
                acceptance.rejection == PoseRejection.ANGULAR_JUMP
            if (continuityFailure && stateMachine.state == TrackingState.INITIALIZING) {
                return reseed(viewMatrix, nowMs, acceptance.rejection)
            }
            return miss(bridgeAvailable, nowMs).copy(rejection = acceptance.rejection)
        }

        // Acceptance and continuity gate on the RAW candidate; the rendered pose is an optional
        // post-acceptance correction of it (e.g. a corroborating drift-trim toward an independent
        // relocalizer), then temporally smoothed. Gating on the raw pose keeps the correction out of
        // the acceptance decision — it only ever changes what is displayed.
        val rawAccepted = viewMatrix.copyOf()
        val corrected = correctAcceptedPose?.invoke(rawAccepted.copyOf()) ?: rawAccepted
        val rendered = stabilizer.stabilize(corrected)
        lastAccepted = rawAccepted
        lastRendered = rendered.copyOf()
        val state = stateMachine.onAcceptedVisual(nowMs)
        return Outcome(renderPose = rendered, state = state, accepted = true)
    }

    /**
     * During [TrackingState.INITIALIZING] the continuity baseline is an *unconfirmed* pose, which may
     * itself be the outlier. Rather than let it veto every later candidate, a candidate that fails
     * continuity against it replaces it as the new seed: the confirmation count restarts at this
     * candidate, so a lock still needs [TrackingStateConfig.confirmationFrames] consecutive
     * mutually-consistent poses. Nothing is rendered for the re-seed frame.
     */
    private fun reseed(viewMatrix: FloatArray, nowMs: Long, rejection: PoseRejection?): Outcome {
        stateMachine.onVisualMiss(bridgeAvailable = false, nowMs = nowMs) // restart the count
        stabilizer.reset()
        lastAccepted = viewMatrix.copyOf()
        lastRendered = null
        val state = stateMachine.onAcceptedVisual(nowMs)
        return Outcome(renderPose = null, state = state, accepted = false, rejection = rejection)
    }

    private fun miss(bridgeAvailable: Boolean, nowMs: Long): Outcome {
        val state = stateMachine.onVisualMiss(bridgeAvailable, nowMs)
        // Once LOST (or a never-confirmed acquisition broke its streak), the old pose says nothing about
        // where the camera now is; drop it so relock is not gated by a stale continuity baseline.
        if (state == TrackingState.LOST || state == TrackingState.INITIALIZING) lastAccepted = null
        // A brief IMU_BRIDGE keeps the smoothing baseline so a quick visual return blends rather than
        // snaps; a real loss (REACQUIRING/LOST) resets it, since re-lock is a genuine discontinuity.
        if (state != TrackingState.IMU_BRIDGE) stabilizer.reset()
        val bridged = if (state == TrackingState.IMU_BRIDGE) {
            lastRendered?.let { prev -> bridgeRotatedPose?.invoke(prev.copyOf()) }
        } else {
            null
        }
        if (bridged != null) {
            // The next visual pose must blend from what the user actually saw during the bridge,
            // not from the pre-bridge visual baseline.
            stabilizer.synchronize(bridged)
            lastRendered = bridged.copyOf()
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

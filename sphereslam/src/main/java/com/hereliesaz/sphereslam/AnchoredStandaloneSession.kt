package com.hereliesaz.sphereslam

import java.nio.ByteBuffer

/**
 * World-size retention over [SphereSlamStandaloneSession]: place content once at a real-world size
 * and have SphereSLAM hold it there.
 *
 * [SphereSlamStandaloneSession] only hands out a per-frame camera pose and leaves all content
 * anchoring to the caller — which means every consumer must re-implement "keep my overlay the same
 * physical size and place as the camera moves, and snap it back after a dropout." That is the whole
 * point of a tracing-projector use, so it belongs in the library. This wrapper stores a
 * [MetricAnchor] in the canonical metric frame and, on every match, returns the content's model-view
 * alongside the raw pose; on a frame with no match it returns null **without forgetting the anchor**,
 * so the next successful match re-applies the identical anchor and the content reappears at the same
 * spot and size.
 *
 * Retention holds across arbitrarily long gaps (camera pocketed, screen off) because the anchor is
 * plain state here and the session rebases every match into the same canonical frame. It does not
 * itself persist across process death — the host saves the anchor's components (and the reference)
 * and calls [place] again on restore.
 *
 * Thread-safety: [place]/[clearPlacement] publish the anchor via `@Volatile`; [match] reads one
 * snapshot. Drive [match] from a single camera worker, as with the wrapped session.
 *
 * Example:
 * ```
 * val anchored = AnchoredStandaloneSession(session)
 * anchored.base.addReference(luma, w, h, referenceWidthMeters = 0.3f, physicallyMetric = true)
 * anchored.place(OverlayPlacement.anchor(panXMeters, panYMeters, rotationZDeg, halfWMeters, halfHMeters))
 * // per frame:
 * anchored.match(luma, timestampNs)?.let { ap ->
 *     val mvp = multiply(projection, ap.viewFromContent)  // draw the quad at ±halfW × ±halfH metres
 * }
 * ```
 *
 * @constructor wraps an existing [SphereSlamStandaloneSession]; the wrapper does not create or own
 *   the session's native engine beyond closing it in [close].
 * @param session the calibrated metric session to add retention to.
 */
class AnchoredStandaloneSession(
    private val session: SphereSlamStandaloneSession,
) : AutoCloseable {

    @Volatile
    private var anchor: MetricAnchor? = null

    /** The wrapped session, for reference registration (`addReference`), `reset`, and state reads. */
    val base: SphereSlamStandaloneSession get() = session

    /** Whether content has been placed (and not since cleared). */
    val hasPlacement: Boolean get() = anchor != null

    /** The current content anchor, or null before [place] / after [clearPlacement]. */
    val placement: MetricAnchor? get() = anchor

    /**
     * Place (or replace) the content anchor. Retained across tracking loss until [clearPlacement] or
     * [close].
     *
     * @param anchor the content placement, typically from [OverlayPlacement.anchor].
     */
    fun place(anchor: MetricAnchor) {
        this.anchor = anchor
    }

    /** Forget the placed content. The reference/track is unaffected. */
    fun clearPlacement() {
        anchor = null
    }

    /**
     * A matched frame plus the placed content's pose at its fixed real-world size.
     *
     * @property pose the raw per-frame pose from the wrapped session (view matrix canonical→view).
     * @property viewFromContent column-major `pose.viewMatrix · canonicalFromContent`: multiply by the
     *   renderer's projection, then draw the quad.
     * @property halfWidthMeters half the content's physical width, metres.
     * @property halfHeightMeters half the content's physical height, metres.
     */
    data class AnchoredPose(
        val pose: SphereSlamStandaloneSession.Pose,
        val viewFromContent: FloatArray,
        val halfWidthMeters: Float,
        val halfHeightMeters: Float,
    )

    /**
     * Match one tightly packed direct luma frame. A null return never clears the anchor, so a later
     * match restores the identical placement — this is what makes content snap back after a dropout.
     *
     * @param luma a tightly packed direct luma buffer for the current frame.
     * @param timestampNs frame timestamp, nanoseconds.
     * @return the anchored content pose when a wall page matches **and** content has been placed;
     *   null when no page matched this frame or nothing is placed.
     */
    fun match(luma: ByteBuffer, timestampNs: Long): AnchoredPose? {
        val a = anchor ?: return null
        val pose = session.match(luma, timestampNs) ?: return null
        return AnchoredPose(
            pose = pose,
            viewFromContent = OverlayPlacement.viewFromContent(pose, a),
            halfWidthMeters = a.halfWidthMeters,
            halfHeightMeters = a.halfHeightMeters,
        )
    }

    /** Clear the placement and close the wrapped session (releasing its native engine). */
    override fun close() {
        anchor = null
        session.close()
    }
}

package com.hereliesaz.sphereslam.reloc

/**
 * A temporal low-pass for a per-frame relocalization/tracking pose.
 *
 * A raw per-frame pose carries jitter inside the acceptance envelope that shimmers an overlay. This
 * blends each new pose toward the previous emitted one, damping that jitter — but only while the two
 * are close: a legitimately large frame-to-frame move (a fast pan, a reacquisition after a dropout)
 * exceeds [PoseBlend.diverged]'s thresholds and passes through unsmoothed, so the overlay never lags
 * real motion.
 *
 * Operates on the column-major 4×4 view matrix via [PoseBlend]'s rigid primitives and holds no state
 * beyond the last emitted pose, which [reset] clears on tracking loss or a session rebuild. It is a
 * stabilizer, not a drift corrector — it cannot remove accumulated error, only smooth what it is given.
 *
 * Not thread-safe; drive it from one worker.
 *
 * @property alpha weight toward the newest pose each frame, `(0, 1]`; 0.5 halves per-frame jitter
 *   while keeping the emitted pose within half a frame's motion of the truth.
 */
@ExperimentalSphereSlamRelocApi
class PoseStabilizer(
    private val alpha: Float = DEFAULT_ALPHA,
) {
    private var last: FloatArray? = null

    /**
     * The pose to render for [raw]: [raw] itself on the first frame or after a large move (a snap),
     * otherwise [raw] blended toward the previous emitted pose by [alpha]. The returned array is
     * retained as the next frame's baseline, so callers must not mutate it in place.
     *
     * @param raw the newest accepted pose, column-major length 16.
     * @return the smoothed (or snapped) pose, column-major length 16.
     * @throws IllegalArgumentException if [raw] is not length 16.
     */
    fun stabilize(raw: FloatArray): FloatArray {
        require(raw.size == 16) { "pose must be a length-16 column-major matrix" }
        val prev = last
        val out = if (prev == null || PoseBlend.diverged(prev, raw)) raw.copyOf() else PoseBlend.blend(prev, raw, alpha)
        last = out
        return out
    }

    /** Set the smoothing baseline to a pose that was actually rendered outside [stabilize]. */
    fun synchronize(renderedPose: FloatArray) {
        require(renderedPose.size == 16) { "pose must be a length-16 column-major matrix" }
        last = renderedPose.copyOf()
    }

    /** Forget the last pose so the next [stabilize] snaps instead of blending across a discontinuity. */
    fun reset() {
        last = null
    }

    companion object {
        /** Default blend weight toward the newest pose; see [alpha]. */
        const val DEFAULT_ALPHA = 0.5f
    }
}

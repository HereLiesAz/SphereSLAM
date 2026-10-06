package com.hereliesaz.sphereslam

import kotlin.math.cos
import kotlin.math.sin

/**
 * A piece of content (an overlay image, a design) placed on the tracked surface, anchored in the
 * **canonical metric frame** SphereSLAM re-establishes on every match.
 *
 * Why this makes "real-world size" hold: the content's physical extent lives in
 * [halfWidthMeters]/[halfHeightMeters] — metres in the canonical frame — and its pose lives in the
 * rigid [canonicalFromContent] transform. Neither is expressed in screen pixels or tied to the live
 * camera, so as the camera moves only the per-frame view matrix changes; the content's metric size
 * and place do not. And because [SphereSlamStandaloneSession] rebases every match into the same
 * canonical frame, a lost-then-reacquired track re-applies this exact anchor — the overlay snaps
 * back to the same spot at the same size.
 *
 * The user's resize folds into the half-extents, deliberately kept out of [canonicalFromContent], so
 * scaling never shears the pose and the extents stay the single source of physical size.
 *
 * Build instances with [OverlayPlacement.anchor] rather than the constructor directly.
 *
 * @property canonicalFromContent column-major 4×4 rigid transform mapping content-local coordinates
 *   into the canonical metric frame; translation is in metres in indices 12/13/14.
 * @property halfWidthMeters half the content's physical width, in metres (`> 0`).
 * @property halfHeightMeters half the content's physical height, in metres (`> 0`).
 * @throws IllegalArgumentException if the matrix is not a finite length-16 array, or an extent is
 *   not positive.
 */
data class MetricAnchor(
    val canonicalFromContent: FloatArray,
    val halfWidthMeters: Float,
    val halfHeightMeters: Float,
) {
    init {
        require(canonicalFromContent.size == 16 && canonicalFromContent.all { it.isFinite() }) {
            "canonicalFromContent must be a finite 4x4 transform"
        }
        require(halfWidthMeters > 0f && halfHeightMeters > 0f) {
            "content half-extents must be positive metres"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MetricAnchor) return false
        return canonicalFromContent.contentEquals(other.canonicalFromContent) &&
            halfWidthMeters == other.halfWidthMeters &&
            halfHeightMeters == other.halfHeightMeters
    }

    override fun hashCode(): Int {
        var r = canonicalFromContent.contentHashCode()
        r = 31 * r + halfWidthMeters.hashCode()
        r = 31 * r + halfHeightMeters.hashCode()
        return r
    }
}

/**
 * Builds and applies [MetricAnchor]s. Pure column-major matrix math — no framework, unit-testable.
 */
object OverlayPlacement {

    /**
     * Anchor content on the surface plane. The resulting transform is rigid; the chosen size lives
     * only in the extents, so a later resize changes the extents, never this matrix.
     *
     * @param panXMeters in-plane translation along the canonical X axis, metres.
     * @param panYMeters in-plane translation along the canonical Y axis, metres.
     * @param rotationZDeg rotation about the surface normal, degrees (CCW+, right-handed OpenGL).
     * @param halfWidthMeters half the content's physical width, metres (`> 0`).
     * @param halfHeightMeters half the content's physical height, metres (`> 0`).
     * @return the placed [MetricAnchor].
     * @throws IllegalArgumentException if an extent is not positive.
     */
    fun anchor(
        panXMeters: Float,
        panYMeters: Float,
        rotationZDeg: Float,
        halfWidthMeters: Float,
        halfHeightMeters: Float,
    ): MetricAnchor {
        val rad = Math.toRadians(rotationZDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        // Column-major T * Rz: rotate about Z, then translate in the plane (z = 0).
        val m = floatArrayOf(
            c, s, 0f, 0f,
            -s, c, 0f, 0f,
            0f, 0f, 1f, 0f,
            panXMeters, panYMeters, 0f, 1f,
        )
        return MetricAnchor(m, halfWidthMeters, halfHeightMeters)
    }

    /**
     * The content's model-view matrix for a given pose. Multiply the renderer's projection by this
     * and draw a quad spanning ±[MetricAnchor.halfWidthMeters] × ±[MetricAnchor.halfHeightMeters] to
     * place the content at its fixed real-world size. With an identity view matrix it returns the
     * anchor transform itself.
     *
     * @param pose a pose from [SphereSlamStandaloneSession.match]; its view matrix is canonical→view.
     * @param anchor the placed content anchor.
     * @return column-major `pose.viewMatrix · anchor.canonicalFromContent` (content-local → view).
     */
    fun viewFromContent(pose: SphereSlamStandaloneSession.Pose, anchor: MetricAnchor): FloatArray =
        multiplyColumnMajor(pose.viewMatrix, anchor.canonicalFromContent)

    /**
     * Column-major 4×4 matrix product `a · b`.
     *
     * @param a left matrix, length 16, column-major.
     * @param b right matrix, length 16, column-major.
     * @return the product, length 16, column-major.
     * @throws IllegalArgumentException if either array is not length 16.
     */
    internal fun multiplyColumnMajor(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == 16 && b.size == 16)
        val out = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += a[k * 4 + row] * b[col * 4 + k]
                }
                out[col * 4 + row] = sum
            }
        }
        return out
    }
}

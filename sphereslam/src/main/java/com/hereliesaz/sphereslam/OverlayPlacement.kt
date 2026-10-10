package com.hereliesaz.sphereslam

import com.hereliesaz.sphereslam.math.RigidMath
import kotlin.math.cos
import kotlin.math.sin

/**
 * Immutable content placement in SphereSLAM's canonical metric frame.
 *
 * The transform is copied at the API boundary. Reading [canonicalFromContent] returns a fresh copy,
 * so a consumer cannot mutate an anchor already retained by a session.
 */
class MetricAnchor internal constructor(
    canonicalFromContent: FloatArray,
    val halfWidthMeters: Float,
    val halfHeightMeters: Float,
) {
    private val canonicalFromContentValue = canonicalFromContent.copyOf()

    init {
        require(canonicalFromContentValue.size == 16 && canonicalFromContentValue.all { it.isFinite() }) {
            "canonicalFromContent must be a finite 4x4 transform"
        }
        require(
            halfWidthMeters.isFinite() && halfHeightMeters.isFinite() &&
                halfWidthMeters > 0f && halfHeightMeters > 0f
        ) {
            "content half-extents must be finite positive metres"
        }
    }

    /** Column-major rigid transform mapping content-local coordinates into the canonical frame. */
    val canonicalFromContent: FloatArray
        get() = canonicalFromContentValue.copyOf()

    override fun equals(other: Any?): Boolean =
        other is MetricAnchor &&
            canonicalFromContentValue.contentEquals(other.canonicalFromContentValue) &&
            halfWidthMeters == other.halfWidthMeters &&
            halfHeightMeters == other.halfHeightMeters

    override fun hashCode(): Int {
        var r = canonicalFromContentValue.contentHashCode()
        r = 31 * r + halfWidthMeters.hashCode()
        r = 31 * r + halfHeightMeters.hashCode()
        return r
    }
}

/** Builds, restores, and applies immutable [MetricAnchor] values. */
object OverlayPlacement {

    /**
     * Anchor content on the canonical surface plane.
     */
    fun anchor(
        panXMeters: Float,
        panYMeters: Float,
        rotationZDeg: Float,
        halfWidthMeters: Float,
        halfHeightMeters: Float,
    ): MetricAnchor {
        require(panXMeters.isFinite() && panYMeters.isFinite() && rotationZDeg.isFinite())
        val rad = Math.toRadians(rotationZDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val m = floatArrayOf(
            c, s, 0f, 0f,
            -s, c, 0f, 0f,
            0f, 0f, 1f, 0f,
            panXMeters, panYMeters, 0f, 1f,
        )
        return MetricAnchor(m, halfWidthMeters, halfHeightMeters)
    }

    /**
     * Rebuild a persisted placement from its canonical transform and half-extents.
     *
     * The matrix is copied; later mutation of [canonicalFromContent] cannot change the returned
     * anchor.
     */
    fun fromCanonicalTransform(
        canonicalFromContent: FloatArray,
        halfWidthMeters: Float,
        halfHeightMeters: Float,
    ): MetricAnchor = MetricAnchor(canonicalFromContent, halfWidthMeters, halfHeightMeters)

    /**
     * Compute column-major camera-from-content for a matched frame.
     */
    fun cameraFromContent(
        pose: SphereSlamStandaloneSession.Pose,
        anchor: MetricAnchor,
    ): FloatArray = multiplyColumnMajor(pose.cameraFromCanonical, anchor.canonicalFromContent)

    /** Legacy rendering name; prefer [cameraFromContent]. */
    @Deprecated(
        "Use cameraFromContent; it states the transform direction explicitly.",
        ReplaceWith("cameraFromContent(pose, anchor)"),
    )
    fun viewFromContent(
        pose: SphereSlamStandaloneSession.Pose,
        anchor: MetricAnchor,
    ): FloatArray = cameraFromContent(pose, anchor)

    /** Column-major 4x4 product `a * b`. */
    internal fun multiplyColumnMajor(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == 16 && b.size == 16)
        return RigidMath.multiply(a, b)
    }
}

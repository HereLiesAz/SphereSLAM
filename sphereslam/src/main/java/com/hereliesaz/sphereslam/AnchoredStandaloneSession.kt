package com.hereliesaz.sphereslam

import java.nio.ByteBuffer

/**
 * World-size retention over [SphereSlamStandaloneSession]: place content once and reapply the same
 * immutable [MetricAnchor] whenever visual tracking reacquires.
 */
class AnchoredStandaloneSession(
    private val session: SphereSlamStandaloneSession,
) : AutoCloseable {

    @Volatile
    private var anchor: MetricAnchor? = null

    /** Wrapped session, used to register/reset references. */
    val base: SphereSlamStandaloneSession get() = session

    val hasPlacement: Boolean get() = anchor != null

    /** Current immutable placement, or null. */
    val placement: MetricAnchor? get() = anchor

    fun place(anchor: MetricAnchor) {
        this.anchor = anchor
    }

    fun clearPlacement() {
        anchor = null
    }

    /**
     * A matched frame plus the content transform.
     *
     * [cameraFromContent] is copied on input/output and is column-major 4x4.
     */
    class AnchoredPose internal constructor(
        val pose: SphereSlamStandaloneSession.Pose,
        cameraFromContent: FloatArray,
        val halfWidthMeters: Float,
        val halfHeightMeters: Float,
    ) {
        private val cameraFromContentValue = cameraFromContent.copyOf()

        init {
            require(cameraFromContentValue.size == 16)
            require(cameraFromContentValue.all { it.isFinite() })
        }

        val cameraFromContent: FloatArray
            get() = cameraFromContentValue.copyOf()

        @Deprecated("Use cameraFromContent", ReplaceWith("cameraFromContent"))
        val viewFromContent: FloatArray
            get() = cameraFromContent
    }

    /**
     * Match one tightly packed direct luma frame. A miss never clears the retained placement.
     */
    fun match(luma: ByteBuffer, timestampNs: Long): AnchoredPose? {
        val a = anchor ?: return null
        val pose = session.match(luma, timestampNs) ?: return null
        return AnchoredPose(
            pose = pose,
            cameraFromContent = OverlayPlacement.cameraFromContent(pose, a),
            halfWidthMeters = a.halfWidthMeters,
            halfHeightMeters = a.halfHeightMeters,
        )
    }

    override fun close() {
        anchor = null
        session.close()
    }
}

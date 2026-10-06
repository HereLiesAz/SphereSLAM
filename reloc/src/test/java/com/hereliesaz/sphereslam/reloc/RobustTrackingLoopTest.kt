package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun idMatrix() = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    0f, 0f, 0f, 1f,
)

private fun movedMatrix(x: Float) = idMatrix().also { it[12] = x }

class RotationDeltaMathTest {
    @Test
    fun `delta between equal orientations is identity`() {
        val q = RotationDeltaMath.normalize(floatArrayOf(0.1f, 0.2f, 0.3f, 1f))
        val d = RotationDeltaMath.cameraRotationDelta(q, q)
        // Identity row-major 3x3.
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            d,
            1e-4f,
        )
    }

    @Test
    fun `conjugate inverts a unit quaternion under the Hamilton product`() {
        val q = RotationDeltaMath.normalize(floatArrayOf(0.3f, -0.2f, 0.5f, 0.8f))
        val p = RotationDeltaMath.multiplyQuaternions(q, RotationDeltaMath.conjugate(q))
        assertArrayEquals(RotationDeltaMath.IDENTITY_QUATERNION, RotationDeltaMath.normalize(p), 1e-4f)
    }
}

class RobustTrackingLoopTest {
    private val realtime = CameraTimestampSource.REALTIME

    private fun loop(
        bridge: ((FloatArray) -> FloatArray?)? = null,
    ) = RobustTrackingLoop(
        referenceWidthUnits = 1f,
        stateMachine = TrackingStateMachine(TrackingStateConfig(confirmationFrames = 2, lostAfterMs = 1000L)),
        bridgeRotatedPose = bridge,
    )

    private fun RobustTrackingLoop.good(nowMs: Long, pose: FloatArray = idMatrix()) = onFrame(
        viewMatrix = pose,
        inlierCount = 30,
        reprojectionError = 1f,
        frameTimestampNs = 0L,
        nowElapsedRealtimeNs = 0L,
        timestampSource = CameraTimestampSource.UNKNOWN, // age untrusted → never stale
        nowMs = nowMs,
    )

    @Test
    fun `two good frames lock and render a stabilized pose`() {
        val l = loop()
        val first = l.good(0L)
        assertFalse(first.accepted.not()) // accepted
        assertEquals(TrackingState.INITIALIZING, first.state)
        val second = l.good(16L)
        assertEquals(TrackingState.LOCKED, second.state)
        assertNotNull(second.renderPose)
    }

    @Test
    fun `a stale realtime frame is dropped`() {
        val l = loop()
        val out = l.onFrame(
            viewMatrix = idMatrix(),
            inlierCount = 30,
            reprojectionError = 1f,
            frameTimestampNs = 1_000_000_000L,
            nowElapsedRealtimeNs = 1_000_000_000L + 400_000_000L, // 400 ms old
            timestampSource = realtime,
            nowMs = 0L,
        )
        assertTrue(out.stale)
        assertFalse(out.accepted)
        assertNull(out.renderPose)
    }

    @Test
    fun `too few inliers is rejected with a reason`() {
        val l = loop()
        val out = l.onFrame(
            viewMatrix = idMatrix(), inlierCount = 2, reprojectionError = 1f,
            frameTimestampNs = 0L, nowElapsedRealtimeNs = 0L,
            timestampSource = CameraTimestampSource.UNKNOWN, nowMs = 0L,
        )
        assertFalse(out.accepted)
        assertEquals(PoseRejection.TOO_FEW_INLIERS, out.rejection)
    }

    @Test
    fun `a miss from lock bridges with the supplied pose`() {
        val bridgePose = movedMatrix(9f)
        val l = loop(bridge = { bridgePose })
        l.good(0L); l.good(16L) // locked
        val out = l.onFrame(
            viewMatrix = null, inlierCount = 0, reprojectionError = 0f,
            frameTimestampNs = 0L, nowElapsedRealtimeNs = 0L,
            timestampSource = realtime, nowMs = 32L, bridgeAvailable = true,
        )
        assertEquals(TrackingState.IMU_BRIDGE, out.state)
        assertArrayEquals(bridgePose, out.renderPose, 0f)
    }

    @Test
    fun `correctAcceptedPose changes only the rendered pose, not gating`() {
        // Correction shifts the rendered pose far in X; the raw candidate stays the identity.
        val l = RobustTrackingLoop(
            referenceWidthUnits = 1f,
            stateMachine = TrackingStateMachine(TrackingStateConfig(confirmationFrames = 2, lostAfterMs = 1000L)),
            correctAcceptedPose = { raw -> movedMatrix(raw[12] + 100f) },
        )
        // First accepted frame: stabilizer has no baseline, so it renders the corrected pose as-is.
        val first = l.good(0L)
        assertTrue(first.accepted)
        assertEquals(100f, first.renderPose!![12], 0f)
        // Second frame's RAW candidate is continuous with the first RAW (both identity), so it is
        // accepted — proving acceptance gates on the raw pose, not the far-shifted corrected one.
        val second = l.good(16L)
        assertTrue(second.accepted)
        assertEquals(TrackingState.LOCKED, second.state)
    }

    @Test
    fun `a brief bridge keeps the smoothing baseline`() {
        // Lock, then blend two close poses so the stabilizer holds a baseline distinct from raw.
        val l = loop()
        l.good(0L); l.good(16L)
        val a = l.good(32L, movedMatrix(0.01f)).renderPose!!
        // A bridged miss must not reset the stabilizer: the next close pose still blends (≠ snap).
        l.onFrame(
            viewMatrix = null, inlierCount = 0, reprojectionError = 0f,
            frameTimestampNs = 0L, nowElapsedRealtimeNs = 0L,
            timestampSource = realtime, nowMs = 40L, bridgeAvailable = true,
        )
        val b = l.good(48L, movedMatrix(0.02f)).renderPose!!
        // Blended, not snapped: b sits between the retained baseline (~0.005) and the raw 0.02.
        assertTrue("expected a blend toward 0.02, got ${b[12]}", b[12] > a[12] && b[12] < 0.02f)
    }

    @Test
    fun `reset returns to initializing`() {
        val l = loop()
        l.good(0L); l.good(16L)
        l.reset()
        val out = l.good(100L)
        assertEquals("fresh acquisition after reset", TrackingState.INITIALIZING, out.state)
    }
}

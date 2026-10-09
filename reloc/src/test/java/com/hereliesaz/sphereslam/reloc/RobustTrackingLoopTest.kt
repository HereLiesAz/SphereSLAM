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
    fun `a bridge synchronizes smoothing to the pose actually rendered`() {
        val bridgePose = movedMatrix(0.10f)
        val l = loop(bridge = { bridgePose })
        l.good(0L); l.good(16L)
        val bridged = l.onFrame(
            viewMatrix = null, inlierCount = 0, reprojectionError = 0f,
            frameTimestampNs = 0L, nowElapsedRealtimeNs = 0L,
            timestampSource = realtime, nowMs = 40L, bridgeAvailable = true,
        )
        assertEquals(0.10f, bridged.renderPose!![12], 0f)

        val returned = l.good(48L, movedMatrix(0.10f)).renderPose!!
        assertEquals(0.10f, returned[12], 1e-6f)
    }

    @Test
    fun `visual return from bridge uses reacquisition continuity limits`() {
        val l = loop(bridge = { it })
        l.good(0L); l.good(16L)
        l.onFrame(
            viewMatrix = null, inlierCount = 0, reprojectionError = 0f,
            frameTimestampNs = 0L, nowElapsedRealtimeNs = 0L,
            timestampSource = realtime, nowMs = 32L, bridgeAvailable = true,
        )

        val out = l.good(48L, movedMatrix(3f))

        assertTrue(out.accepted)
        assertEquals(TrackingState.LOCKED, out.state)
    }

    @Test
    fun `in-place correction cannot mutate the raw continuity baseline`() {
        val l = RobustTrackingLoop(
            referenceWidthUnits = 1f,
            stateMachine = TrackingStateMachine(TrackingStateConfig(confirmationFrames = 2, lostAfterMs = 1000L)),
            correctAcceptedPose = { raw ->
                raw[12] = 100f
                raw
            },
        )

        assertTrue(l.good(0L).accepted)
        assertTrue(l.good(16L).accepted)
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

class RobustTrackingLoopRelockTest {
    private fun loop(confirmationFrames: Int = 2, maxBridgeMs: Long = 1_000L, bridge: ((FloatArray) -> FloatArray?)? = null) =
        RobustTrackingLoop(
            referenceWidthUnits = 1f,
            stateMachine = TrackingStateMachine(
                TrackingStateConfig(confirmationFrames = confirmationFrames, lostAfterMs = 100L, maxBridgeMs = maxBridgeMs),
            ),
            bridgeRotatedPose = bridge,
        )

    private fun RobustTrackingLoop.good(nowMs: Long, pose: FloatArray = idMatrix()) = onFrame(
        viewMatrix = pose, inlierCount = 30, reprojectionError = 1f,
        frameTimestampNs = 0L, nowElapsedRealtimeNs = 0L,
        timestampSource = CameraTimestampSource.UNKNOWN, nowMs = nowMs,
    )

    private fun RobustTrackingLoop.none(nowMs: Long, bridge: Boolean = false) = onFrame(
        viewMatrix = null, inlierCount = 0, reprojectionError = 0f,
        frameTimestampNs = 0L, nowElapsedRealtimeNs = 0L,
        timestampSource = CameraTimestampSource.UNKNOWN, nowMs = nowMs, bridgeAvailable = bridge,
    )

    @Test
    fun `relock after LOST is not gated by the stale pre-loss pose`() {
        val l = loop()
        l.good(0L); assertEquals(TrackingState.LOCKED, l.good(16L).state)
        l.none(20L) // REACQUIRING
        assertEquals(TrackingState.LOST, l.none(200L).state)
        // 50 reference-widths away: beyond even the reacquire limit (8) against the old pose.
        val far = movedMatrix(50f)
        val first = l.good(300L, far)
        assertTrue("stale baseline dropped on LOST", first.accepted)
        assertEquals(TrackingState.LOCKED, l.good(316L, far).state)
    }

    @Test
    fun `an outlying first pose does not veto a consistent acquisition`() {
        val l = loop(confirmationFrames = 2)
        val outlier = l.good(0L, movedMatrix(40f))
        assertTrue(outlier.accepted)
        assertEquals(TrackingState.INITIALIZING, outlier.state)
        // Inconsistent with the unconfirmed seed: re-seeds instead of being rejected forever.
        val reseed = l.good(16L, idMatrix())
        assertFalse(reseed.accepted)
        assertEquals(PoseRejection.TRANSLATION_JUMP, reseed.rejection)
        assertNull(reseed.renderPose)
        assertEquals(TrackingState.INITIALIZING, reseed.state)
        // A second pose consistent with the new seed confirms the lock.
        val confirm = l.good(32L, idMatrix())
        assertTrue(confirm.accepted)
        assertEquals(TrackingState.LOCKED, confirm.state)
    }

    @Test
    fun `alternating inconsistent poses never lock`() {
        val l = loop(confirmationFrames = 2)
        var t = 0L
        repeat(6) { i ->
            val out = l.good(t, movedMatrix(if (i % 2 == 0) 40f else 0f))
            assertEquals(TrackingState.INITIALIZING, out.state)
            t += 16L
        }
    }

    @Test
    fun `consecutive bridge misses expire into reacquiring with no render pose`() {
        val l = loop(maxBridgeMs = 50L, bridge = { it })
        l.good(0L); l.good(16L)
        assertEquals(TrackingState.IMU_BRIDGE, l.none(20L, bridge = true).state)
        assertEquals(TrackingState.IMU_BRIDGE, l.none(60L, bridge = true).state)
        val expired = l.none(70L, bridge = true)
        assertEquals(TrackingState.REACQUIRING, expired.state)
        assertNull(expired.renderPose)
        assertEquals(TrackingState.LOST, l.none(170L, bridge = true).state)
    }
}

package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun identity() = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    0f, 0f, 0f, 1f,
)

private fun translated(x: Float, y: Float, z: Float) = identity().also {
    it[12] = x; it[13] = y; it[14] = z
}

class PoseMathTest {
    @Test
    fun `quaternion round-trips through a matrix`() {
        val q = PoseMath.matrixToQuaternion(identity())
        val m = PoseMath.fromQuaternionTranslation(q, floatArrayOf(1f, 2f, 3f))
        assertEquals(1f, m[0], 1e-5f); assertEquals(1f, m[5], 1e-5f); assertEquals(1f, m[10], 1e-5f)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), PoseMath.translationOf(m), 1e-5f)
    }

    @Test
    fun `rigid inverse undoes a translation`() {
        val inv = PoseMath.rigidInverse(translated(1f, 2f, 3f))
        assertArrayEquals(floatArrayOf(-1f, -2f, -3f), PoseMath.translationOf(inv), 1e-5f)
    }
}

class PoseBlendTest {
    @Test
    fun `blend at half lands at the translation midpoint`() {
        val out = PoseBlend.blend(identity(), translated(1f, 0f, 0f), 0.5f)
        assertEquals(0.5f, out[12], 1e-5f)
    }

    @Test
    fun `small move is not diverged, large move is`() {
        assertFalse(PoseBlend.diverged(identity(), translated(0.05f, 0f, 0f)))
        assertTrue(PoseBlend.diverged(identity(), translated(0.5f, 0f, 0f)))
    }
}

class PoseStabilizerTest {
    @Test
    fun `first frame passes through`() {
        val s = PoseStabilizer()
        assertArrayEquals(translated(1f, 0f, 0f), s.stabilize(translated(1f, 0f, 0f)), 1e-6f)
    }

    @Test
    fun `a small move is damped`() {
        val s = PoseStabilizer(alpha = 0.5f)
        s.stabilize(identity())
        val out = s.stabilize(translated(0.05f, 0f, 0f))
        assertEquals("blended toward the previous pose", 0.025f, out[12], 1e-5f)
    }

    @Test
    fun `a diverging move snaps through unsmoothed`() {
        val s = PoseStabilizer(alpha = 0.5f)
        s.stabilize(identity())
        val out = s.stabilize(translated(1f, 0f, 0f))
        assertEquals(1f, out[12], 1e-5f)
    }

    @Test
    fun `reset makes the next frame snap`() {
        val s = PoseStabilizer()
        s.stabilize(identity())
        s.reset()
        assertArrayEquals(translated(0.05f, 0f, 0f), s.stabilize(translated(0.05f, 0f, 0f)), 1e-6f)
    }
}

class PoseAcceptancePolicyTest {
    private val policy = PoseAcceptancePolicy()

    @Test
    fun `too few inliers rejected`() {
        val r = policy.evaluate(identity(), inlierCount = 3, reprojectionError = 1f)
        assertFalse(r.accepted); assertEquals(PoseRejection.TOO_FEW_INLIERS, r.rejection)
    }

    @Test
    fun `excessive reprojection error rejected`() {
        val r = policy.evaluate(identity(), inlierCount = 20, reprojectionError = 99f)
        assertEquals(PoseRejection.EXCESSIVE_REPROJECTION_ERROR, r.rejection)
    }

    @Test
    fun `a clean first pose is accepted`() {
        assertTrue(policy.evaluate(identity(), inlierCount = 20, reprojectionError = 1f).accepted)
    }

    @Test
    fun `a huge translation jump is rejected normally but allowed while reacquiring`() {
        val prev = identity()
        val far = translated(5f, 0f, 0f) // 5 reference-widths at referenceWidthUnits = 1
        assertEquals(
            PoseRejection.TRANSLATION_JUMP,
            policy.evaluate(far, 20, 1f, previousViewMatrix = prev, referenceWidthUnits = 1f).rejection,
        )
        assertTrue(
            policy.evaluate(far, 20, 1f, previousViewMatrix = prev, referenceWidthUnits = 1f, reacquiring = true).accepted,
        )
    }
}

class ObservationAgePolicyTest {
    private val policy = ObservationAgePolicy(ObservationAgeConfig(maxRealtimeAgeMs = 250f))

    @Test
    fun `a fresh realtime frame is not stale`() {
        val now = 1_000_000_000L
        val r = policy.evaluate(now - 50_000_000L /*50ms*/, now, CameraTimestampSource.REALTIME)
        assertEquals(50f, r.ageMs!!, 1e-3f); assertFalse(r.stale)
    }

    @Test
    fun `an old realtime frame is stale`() {
        val now = 1_000_000_000L
        assertTrue(policy.evaluate(now - 400_000_000L, now, CameraTimestampSource.REALTIME).stale)
    }

    @Test
    fun `an unknown timestamp source is never aged or stale`() {
        val now = 1_000_000_000L
        val r = policy.evaluate(now - 400_000_000L, now, CameraTimestampSource.UNKNOWN)
        assertNull(r.ageMs); assertFalse(r.stale)
    }
}

class TrackingStateMachineTest {
    @Test
    fun `two confirmations lock`() {
        val m = TrackingStateMachine(TrackingStateConfig(confirmationFrames = 2, lostAfterMs = 2000L))
        assertEquals(TrackingState.INITIALIZING, m.onAcceptedVisual(0L))
        assertEquals(TrackingState.LOCKED, m.onAcceptedVisual(16L))
    }

    @Test
    fun `a miss from lock bridges when available`() {
        val m = TrackingStateMachine(TrackingStateConfig(confirmationFrames = 1))
        m.onAcceptedVisual(0L)
        assertEquals(TrackingState.IMU_BRIDGE, m.onVisualMiss(bridgeAvailable = true, nowMs = 10L))
    }

    @Test
    fun `reacquiring becomes lost after the window`() {
        val m = TrackingStateMachine(TrackingStateConfig(confirmationFrames = 1, lostAfterMs = 100L))
        m.onAcceptedVisual(0L)
        assertEquals(TrackingState.REACQUIRING, m.onVisualMiss(bridgeAvailable = false, nowMs = 10L))
        assertEquals(TrackingState.LOST, m.onVisualMiss(bridgeAvailable = false, nowMs = 200L))
    }
}

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

class TrackingStateMachineBridgeTimeoutTest {
    private fun locked(maxBridgeMs: Long = 100L, lostAfterMs: Long = 200L) =
        TrackingStateMachine(
            TrackingStateConfig(confirmationFrames = 1, lostAfterMs = lostAfterMs, maxBridgeMs = maxBridgeMs),
        ).also { it.onAcceptedVisual(0L) }

    @Test
    fun `consecutive bridged misses time out to reacquiring then lost`() {
        val m = locked(maxBridgeMs = 100L, lostAfterMs = 200L)
        assertEquals(TrackingState.IMU_BRIDGE, m.onVisualMiss(bridgeAvailable = true, nowMs = 10L))
        assertEquals(TrackingState.IMU_BRIDGE, m.onVisualMiss(bridgeAvailable = true, nowMs = 60L))
        assertEquals(TrackingState.IMU_BRIDGE, m.onVisualMiss(bridgeAvailable = true, nowMs = 109L))
        // Bridge began at 10 ms; at 110 ms it has held 100 ms and expires.
        assertEquals(TrackingState.REACQUIRING, m.onVisualMiss(bridgeAvailable = true, nowMs = 110L))
        // A still-available bridge must not re-arm once reacquiring.
        assertEquals(TrackingState.REACQUIRING, m.onVisualMiss(bridgeAvailable = true, nowMs = 200L))
        // Reacquire clock started at expiry (110 ms): LOST at 110 + 200.
        assertEquals(TrackingState.REACQUIRING, m.onVisualMiss(bridgeAvailable = true, nowMs = 309L))
        assertEquals(TrackingState.LOST, m.onVisualMiss(bridgeAvailable = true, nowMs = 310L))
    }

    @Test
    fun `a visual accept re-arms the bridge`() {
        val m = locked(maxBridgeMs = 100L)
        m.onVisualMiss(bridgeAvailable = true, nowMs = 10L)
        m.onVisualMiss(bridgeAvailable = true, nowMs = 90L)
        assertEquals(TrackingState.LOCKED, m.onAcceptedVisual(95L))
        assertEquals(TrackingState.IMU_BRIDGE, m.onVisualMiss(bridgeAvailable = true, nowMs = 150L))
        assertEquals(TrackingState.IMU_BRIDGE, m.onVisualMiss(bridgeAvailable = true, nowMs = 240L))
    }

    @Test
    fun `zero bridge window never bridges`() {
        val m = locked(maxBridgeMs = 0L)
        assertEquals(TrackingState.REACQUIRING, m.onVisualMiss(bridgeAvailable = true, nowMs = 10L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative bridge window is rejected`() {
        TrackingStateConfig(maxBridgeMs = -1L)
    }
}

class PoseMathRotationTest {
    /** Column-major rotation about the given unit axis by [deg] (Rodrigues). */
    private fun rotation(axis: FloatArray, deg: Float, t: FloatArray = floatArrayOf(0f, 0f, 0f)): FloatArray {
        val h = Math.toRadians(deg.toDouble() / 2.0)
        val s = kotlin.math.sin(h).toFloat()
        val q = floatArrayOf(axis[0] * s, axis[1] * s, axis[2] * s, kotlin.math.cos(h).toFloat())
        return PoseMath.fromQuaternionTranslation(q, t)
    }

    private fun assertRoundTrip(m: FloatArray) {
        val back = PoseMath.fromQuaternionTranslation(PoseMath.matrixToQuaternion(m), PoseMath.translationOf(m))
        assertArrayEquals(m, back, 1e-5f)
    }

    @Test
    fun `matrixToQuaternion round-trips through every branch`() {
        assertRoundTrip(rotation(floatArrayOf(0f, 0f, 1f), 30f)) // trace > 0
        assertRoundTrip(rotation(floatArrayOf(1f, 0f, 0f), 180f)) // m00 dominant
        assertRoundTrip(rotation(floatArrayOf(0f, 1f, 0f), 170f)) // m11 dominant
        assertRoundTrip(rotation(floatArrayOf(0f, 0f, 1f), 179f)) // m22 dominant
        val n = 1f / kotlin.math.sqrt(3f)
        assertRoundTrip(rotation(floatArrayOf(n, n, n), 150f, floatArrayOf(1f, -2f, 3f)))
    }

    @Test
    fun `nlerpQuat endpoints and hemisphere correction`() {
        val a = PoseMath.matrixToQuaternion(rotation(floatArrayOf(0f, 0f, 1f), 0f))
        val b = PoseMath.matrixToQuaternion(rotation(floatArrayOf(0f, 0f, 1f), 90f))
        assertArrayEquals(a, PoseMath.nlerpQuat(a, b, 0f), 1e-6f)
        assertArrayEquals(b, PoseMath.nlerpQuat(a, b, 1f), 1e-6f)
        // The negated quaternion is the same rotation; nlerp must take the short way, not via zero.
        val negB = floatArrayOf(-b[0], -b[1], -b[2], -b[3])
        val mid = PoseMath.nlerpQuat(a, negB, 0.5f)
        val expectedMid = PoseMath.matrixToQuaternion(rotation(floatArrayOf(0f, 0f, 1f), 45f))
        val dot = kotlin.math.abs(mid.indices.sumOf { (mid[it] * expectedMid[it]).toDouble() })
        assertEquals(1.0, dot, 1e-5)
    }
}

class PoseBlendRotationTest {
    /** Column-major camera-from-map for a camera at centre [c] with rotation [deg] about +Y. */
    private fun cameraAt(c: FloatArray, deg: Float): FloatArray {
        val h = Math.toRadians(deg.toDouble() / 2.0)
        val q = floatArrayOf(0f, kotlin.math.sin(h).toFloat(), 0f, kotlin.math.cos(h).toFloat())
        val r = PoseMath.fromQuaternionTranslation(q, floatArrayOf(0f, 0f, 0f))
        val t = FloatArray(3) { row -> -(r[row] * c[0] + r[4 + row] * c[1] + r[8 + row] * c[2]) }
        return PoseMath.fromQuaternionTranslation(q, t)
    }

    @Test
    fun `pure rotation in place is not a translation divergence`() {
        // Same camera centre, 10 deg turn: translation columns differ a lot, centres do not.
        val a = cameraAt(floatArrayOf(2f, 0f, 3f), 0f)
        val b = cameraAt(floatArrayOf(2f, 0f, 3f), 10f)
        assertFalse(PoseBlend.diverged(a, b))
    }

    @Test
    fun `a large turn diverges on angle`() {
        val a = cameraAt(floatArrayOf(0f, 0f, 0f), 0f)
        assertTrue(PoseBlend.diverged(a, cameraAt(floatArrayOf(0f, 0f, 0f), 20f)))
    }

    @Test
    fun `a moved camera centre diverges even when rotated`() {
        val a = cameraAt(floatArrayOf(0f, 0f, 0f), 5f)
        assertTrue(PoseBlend.diverged(a, cameraAt(floatArrayOf(0.5f, 0f, 0f), 5f)))
    }

    @Test
    fun `blend interpolates the camera centre, not the translation column`() {
        val c = floatArrayOf(1f, 0.5f, -2f)
        val out = PoseBlend.blend(cameraAt(c, 0f), cameraAt(c, 12f), 0.5f)
        // Rotating in place must keep the camera centre fixed throughout the blend.
        assertArrayEquals(c, PoseBlend.cameraCenter(out), 1e-5f)
        assertArrayEquals(cameraAt(c, 6f), out, 1e-5f)
    }

    @Test
    fun `blend midpoint of two rotated cameras lands at the centre midpoint`() {
        val out = PoseBlend.blend(
            cameraAt(floatArrayOf(0f, 0f, 0f), 30f),
            cameraAt(floatArrayOf(1f, 0f, 1f), 30f),
            0.5f,
        )
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0.5f), PoseBlend.cameraCenter(out), 1e-5f)
    }
}

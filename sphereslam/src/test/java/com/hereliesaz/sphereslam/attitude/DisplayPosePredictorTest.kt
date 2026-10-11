package com.hereliesaz.sphereslam.attitude

import com.hereliesaz.sphereslam.math.RigidMath
import com.hereliesaz.sphereslam.math.RotationMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class DisplayPosePredictorTest {

    private fun about(axis: FloatArray, deg: Double): FloatArray {
        val h = Math.toRadians(deg / 2.0)
        return floatArrayOf(
            (axis[0] * sin(h)).toFloat(), (axis[1] * sin(h)).toFloat(), (axis[2] * sin(h)).toFloat(), cos(h).toFloat(),
        )
    }

    private val view = floatArrayOf(
        0.96f, 0.1f, -0.26f, 0f,
        -0.05f, 0.98f, 0.2f, 0f,
        0.27f, -0.18f, 0.95f, 0f,
        0.2f, -0.1f, -1.5f, 1f,
    )
    private val q0 = RotationMath.normalize(floatArrayOf(0.1f, 0.2f, -0.05f, 0.97f))

    @Test
    fun `no rotation since the frame returns the vision pose`() {
        val p = DisplayPosePredictor()
        p.update(view, q0, DeviceCameraRotation.cameraFromDevice(90))
        val out = FloatArray(16)
        assertTrue(p.predictInto(q0, out))
        assertArrayEquals(view, out, 1e-5f)
    }

    @Test
    fun `matches the gyro bridge for the same delta, any cameraFromDevice, repeatably`() {
        for (deg in intArrayOf(0, 90, 180, 270)) {
            val c = DeviceCameraRotation.cameraFromDevice(deg)
            val q1 = RotationMath.multiplyQuaternions(about(floatArrayOf(0.48f, 0.6f, 0.64f), 17.0), q0)
            val bridge = AttitudeRotationBridge(c).also { it.markReference(q0) }
            val expected = bridge.advance(view, q1)!!
            val p = DisplayPosePredictor().apply { update(view, q0, c) }
            assertArrayEquals(expected, p.predict(q1), 1e-5f)
            // No re-referencing: asking again gives the same answer (the bridge would not).
            assertArrayEquals(expected, p.predict(q1), 1e-5f)
            assertArrayEquals(bridge.cameraRotationDelta(q1) ?: RotationMath.IDENTITY_3X3, RotationMath.IDENTITY_3X3, 1e-5f)
        }
    }

    @Test
    fun `the camera centre is held`() {
        val p = DisplayPosePredictor().apply { update(view, q0, RotationMath.IDENTITY_3X3) }
        val q1 = RotationMath.multiplyQuaternions(about(floatArrayOf(0f, 0f, 1f), 40.0), q0)
        assertArrayEquals(RigidMath.cameraCentre(view), RigidMath.cameraCentre(p.predict(q1)!!), 1e-5f)
    }

    @Test
    fun `prediction horizon uses the extrapolated attitude at the target time`() {
        val ms = 1_000_000L
        val z = floatArrayOf(0f, 0f, 1f)
        val x = AttitudeExtrapolator(maxExtrapolationNs = 50 * ms)
        x.push(about(z, 0.0), 0L)
        x.push(about(z, 10.0), 10 * ms) // 1 deg/ms
        val p = DisplayPosePredictor().apply { update(view, about(z, 0.0), RotationMath.IDENTITY_3X3, 0L) }
        val out = FloatArray(16)
        assertTrue(p.predictInto(x, 30 * ms, out))
        assertArrayEquals(p.predict(about(z, 30.0)), out, 1e-4f)
        // Beyond the cap the rotation stops growing.
        assertTrue(p.predictInto(x, 400 * ms, out))
        assertArrayEquals(p.predict(about(z, 60.0)), out, 1e-4f)
        // Interpolated inside the history (a negative lead).
        assertTrue(p.predictInto(x, 5 * ms, out))
        assertArrayEquals(p.predict(about(z, 5.0)), out, 1e-4f)
    }

    @Test
    fun `nothing is predicted without a pose, after clear, or for bad input`() {
        val p = DisplayPosePredictor()
        assertFalse(p.predictInto(q0, FloatArray(16)))
        assertNull(p.poseTimestampNs)
        p.update(view, q0, RotationMath.IDENTITY_3X3, 42L)
        assertEquals(42L, p.poseTimestampNs)
        assertFalse(p.predictInto(floatArrayOf(Float.NaN, 0f, 0f, 1f), FloatArray(16)))
        assertFalse(p.predictInto(AttitudeExtrapolator(), 0L, FloatArray(16)))
        // A non-finite pose is ignored: the held one stays.
        p.update(FloatArray(16) { Float.NaN }, q0, RotationMath.IDENTITY_3X3)
        assertArrayEquals(view, p.predict(q0), 1e-5f)
        p.clear()
        assertFalse(p.hasPose)
        assertNull(p.predict(q0))
    }
}

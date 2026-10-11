package com.hereliesaz.sphereslam.attitude

import com.hereliesaz.sphereslam.CoverageGlowProjection
import com.hereliesaz.sphereslam.math.RotationMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class AttitudeExtrapolatorTest {

    /** Rotation of [deg] about world up (z). */
    private fun yaw(deg: Double): FloatArray {
        val h = Math.toRadians(deg / 2.0)
        return floatArrayOf(0f, 0f, sin(h).toFloat(), cos(h).toFloat())
    }

    /** Yaw angle (degrees) of a z-axis quaternion, sign-agnostic. */
    private fun yawOf(q: FloatArray): Double {
        val s = if (q[3] < 0f) -1f else 1f
        return Math.toDegrees(2.0 * atan2((s * q[2]).toDouble(), (s * q[3]).toDouble()))
    }

    private fun at(x: AttitudeExtrapolator, t: Long): FloatArray =
        FloatArray(4).also { assertTrue(x.quaternionAt(t, it)) }

    private val ms = 1_000_000L

    @Test
    fun `empty history answers nothing`() {
        val x = AttitudeExtrapolator()
        assertFalse(x.quaternionAt(0L, FloatArray(4)))
        assertFalse(x.deviceToWorldAt(0L, FloatArray(9)))
        assertNull(x.newestTimestampNs)
    }

    @Test
    fun `a single sample is returned for any time`() {
        val x = AttitudeExtrapolator().apply { push(yaw(30.0), 10 * ms) }
        assertEquals(30.0, yawOf(at(x, 0L)), 1e-4)
        assertEquals(30.0, yawOf(at(x, 500 * ms)), 1e-4)
    }

    @Test
    fun `times inside the history are slerped between bracketing samples`() {
        val x = AttitudeExtrapolator()
        x.push(yaw(0.0), 0L); x.push(yaw(20.0), 20 * ms); x.push(yaw(60.0), 40 * ms)
        assertEquals(10.0, yawOf(at(x, 10 * ms)), 1e-3)
        assertEquals(40.0, yawOf(at(x, 30 * ms)), 1e-3)
        assertEquals(20.0, yawOf(at(x, 20 * ms)), 1e-3)
        assertEquals("before the history: the oldest", 0.0, yawOf(at(x, -5 * ms)), 1e-4)
    }

    @Test
    fun `times past the newest sample extrapolate at constant angular velocity, capped`() {
        val x = AttitudeExtrapolator(maxExtrapolationNs = 50 * ms)
        x.push(yaw(0.0), 0L); x.push(yaw(10.0), 10 * ms) // 1 degree per ms
        assertEquals(15.0, yawOf(at(x, 15 * ms)), 1e-3)
        assertEquals(40.0, yawOf(at(x, 40 * ms)), 1e-3)
        assertEquals("capped at 50 ms past the newest", 60.0, yawOf(at(x, 500 * ms)), 1e-3)
    }

    @Test
    fun `extrapolation follows a tilted axis, not just yaw`() {
        val axis = doubleArrayOf(0.6, 0.0, 0.8)
        fun about(deg: Double): FloatArray {
            val h = Math.toRadians(deg / 2.0)
            return floatArrayOf((axis[0] * sin(h)).toFloat(), 0f, (axis[2] * sin(h)).toFloat(), cos(h).toFloat())
        }
        val base = yaw(70.0)
        val x = AttitudeExtrapolator()
        x.push(RotationMath.multiplyQuaternions(about(0.0), base), 0L)
        x.push(RotationMath.multiplyQuaternions(about(8.0), base), 10 * ms)
        val expected = RotationMath.multiplyQuaternions(about(12.0), base)
        val got = at(x, 15 * ms)
        val dot = expected.indices.sumOf { (expected[it] * got[it]).toDouble() }
        assertEquals(1.0, abs(dot), 1e-6)
    }

    @Test
    fun `a sensor gap stops velocity extrapolation`() {
        val x = AttitudeExtrapolator(maxSampleGapNs = 100 * ms)
        x.push(yaw(0.0), 0L); x.push(yaw(10.0), 300 * ms)
        assertEquals(10.0, yawOf(at(x, 320 * ms)), 1e-4)
    }

    @Test
    fun `stale or invalid pushes are ignored and the hemisphere is kept continuous`() {
        val x = AttitudeExtrapolator()
        x.push(yaw(0.0), 10 * ms)
        x.push(yaw(90.0), 10 * ms) // not newer
        x.push(floatArrayOf(Float.NaN, 0f, 0f, 1f), 20 * ms)
        x.push(floatArrayOf(0f, 0f, 0f, 0f), 30 * ms)
        assertEquals(1, x.size)
        val q = yaw(20.0)
        x.push(floatArrayOf(-q[0], -q[1], -q[2], -q[3]), 20 * ms) // same rotation, other hemisphere
        assertEquals(10.0, yawOf(at(x, 15 * ms)), 1e-3)
    }

    @Test
    fun `history is bounded by capacity`() {
        val x = AttitudeExtrapolator(capacity = 4)
        for (i in 0 until 10) x.push(yaw(i.toDouble()), i * ms)
        assertEquals(4, x.size)
        assertEquals("older than retained: the oldest retained", 6.0, yawOf(at(x, 0L)), 1e-3)
        assertEquals(6.5, yawOf(at(x, 6 * ms + ms / 2)), 1e-3)
    }

    @Test
    fun `worldFromCameraAt composes the sensor matrix with cameraFromDevice`() {
        val q = RotationMath.normalize(floatArrayOf(0.2f, -0.3f, 0.1f, 0.9f))
        val x = AttitudeExtrapolator().apply { push(q, 1L) }
        val c = DeviceCameraRotation.cameraFromDeviceForDisplayFrame(90)
        val out = FloatArray(9)
        assertTrue(x.worldFromCameraAt(1L, c, out))
        val expected = CoverageGlowProjection.worldFromCamera(RotationMath.toRotationMatrix3x3(q), c)
        assertArrayEquals(expected, out, 1e-5f)
        val r = FloatArray(9)
        assertTrue(x.deviceToWorldAt(1L, r))
        assertArrayEquals(RotationMath.toRotationMatrix3x3(q), r, 1e-5f)
    }

    @Test
    fun `constructor validates`() {
        assertThrows(IllegalArgumentException::class.java) { AttitudeExtrapolator(capacity = 1) }
        assertThrows(IllegalArgumentException::class.java) { AttitudeExtrapolator(maxExtrapolationNs = -1) }
        assertThrows(IllegalArgumentException::class.java) { AttitudeExtrapolator(maxSampleGapNs = 0) }
        assertThrows(IllegalArgumentException::class.java) { AttitudeExtrapolator().quaternionAt(0L, FloatArray(3)) }
    }
}

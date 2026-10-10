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

class AttitudeRotationBridgeTest {

    private fun yaw(deg: Float): FloatArray {
        val h = Math.toRadians(deg.toDouble() / 2.0)
        return floatArrayOf(0f, sin(h).toFloat(), 0f, cos(h).toFloat())
    }

    private fun pose(deg: Float, tx: Float, ty: Float, tz: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, tx, ty, tz, 1f)
    }

    @Test
    fun `no reference means no bridge`() {
        val b = AttitudeRotationBridge()
        assertFalse(b.hasReference)
        assertNull(b.advance(pose(0f, 0f, 0f, 1f), yaw(10f)))
    }

    @Test
    fun `device yaw rotates the view by the inverse and holds the camera centre`() {
        val b = AttitudeRotationBridge()
        val ref = pose(0f, 0.5f, 0.2f, -2f)
        b.markReference(yaw(0f))
        assertTrue(b.hasReference)
        val out = b.advance(ref, yaw(30f))!!
        val expected = pose(-30f, 0f, 0f, 0f)
        for (i in intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10)) assertEquals(expected[i], out[i], 1e-5f)
        assertArrayEquals(RigidMath.cameraCentre(ref), RigidMath.cameraCentre(out), 1e-5f)
    }

    @Test
    fun `advance is incremental, so feeding the bridged pose back never double-applies`() {
        val b = AttitudeRotationBridge()
        val ref = pose(10f, 0.1f, 0f, -1f)
        b.markReference(yaw(0f))
        val step1 = b.advance(ref, yaw(10f))!!
        val step2 = b.advance(step1, yaw(25f))!!
        val oneShot = AttitudeRotationBridge().also { it.markReference(yaw(0f)) }.advance(ref, yaw(25f))!!
        assertArrayEquals(oneShot, step2, 1e-5f)
    }

    @Test
    fun `camera delta is conjugated by cameraFromDevice`() {
        val c = DeviceCameraRotation.cameraFromDevice(90)
        val b = AttitudeRotationBridge(c)
        b.markReference(yaw(0f))
        val delta = b.cameraRotationDelta(yaw(20f))!!
        val expected = RotationMath.conjugateMat3(c, RotationMath.cameraRotationDelta(yaw(0f), yaw(20f)))
        assertArrayEquals(expected, delta, 1e-6f)
    }

    @Test
    fun `bridgeFunction reads the current sample and a null sample yields null`() {
        val b = AttitudeRotationBridge()
        var current: AttitudeSample? = null
        val f = b.bridgeFunction { current }
        b.markReference(AttitudeSample(yaw(0f), 1L))
        assertNull(f(pose(0f, 0f, 0f, 1f)))
        current = AttitudeSample(yaw(0f), 2L)
        assertArrayEquals(pose(0f, 0f, 0f, 1f), f(pose(0f, 0f, 0f, 1f)), 1e-6f)
        b.markReference(null as AttitudeSample?)
        assertFalse(b.hasReference)
    }
}

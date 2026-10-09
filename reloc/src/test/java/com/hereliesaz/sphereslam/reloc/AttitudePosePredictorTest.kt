package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AttitudePosePredictorTest {

    /** Unit quaternion `[x, y, z, w]` for a rotation of [deg] about +Y. */
    private fun yaw(deg: Float): FloatArray {
        val h = Math.toRadians(deg.toDouble() / 2.0)
        return floatArrayOf(0f, sin(h).toFloat(), 0f, cos(h).toFloat())
    }

    /** Column-major camera-from-map: rotation [deg] about +Y, then translation (tx, ty, tz). */
    private fun pose(deg: Float, tx: Float, ty: Float, tz: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(
            c, 0f, -s, 0f,
            0f, 1f, 0f, 0f,
            s, 0f, c, 0f,
            tx, ty, tz, 1f,
        )
    }

    @Test
    fun `no reference means no prediction`() {
        val p = AttitudePosePredictor()
        assertFalse(p.hasReference)
        assertNull(p.predict(yaw(0f)))
    }

    @Test
    fun `unchanged attitude returns the corrected pose`() {
        val p = AttitudePosePredictor()
        val ref = pose(20f, 0.3f, -0.1f, 1.5f)
        p.correct(ref, yaw(10f))
        assertTrue(p.hasReference)
        assertArrayEquals(ref, p.predict(yaw(10f)), 1e-5f)
    }

    @Test
    fun `device yaw rotates the view by the inverse and holds the camera centre`() {
        val p = AttitudePosePredictor()
        val ref = pose(0f, 0.5f, 0.2f, -2f)
        p.correct(ref, yaw(0f))
        val predicted = p.predict(yaw(30f))!!
        // Device turned +30 deg about world Y => camera-from-map rotation becomes R_y(-30) * R_ref.
        val expectedRotation = pose(-30f, 0f, 0f, 0f)
        for (i in intArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10)) {
            assertEquals("rotation[$i]", expectedRotation[i], predicted[i], 1e-5f)
        }
        assertArrayEquals(PoseBlend.cameraCenter(ref), PoseBlend.cameraCenter(predicted), 1e-5f)
    }

    @Test
    fun `reset forgets the reference`() {
        val p = AttitudePosePredictor()
        p.correct(pose(0f, 0f, 0f, 0f), yaw(0f))
        p.reset()
        assertFalse(p.hasReference)
        assertNull(p.predict(yaw(5f)))
    }

    @Test
    fun `non-finite correction is ignored`() {
        val p = AttitudePosePredictor()
        p.correct(FloatArray(16) { Float.NaN }, yaw(0f))
        assertFalse(p.hasReference)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `extrinsic must be 3x3`() {
        AttitudePosePredictor(cameraFromDevice = FloatArray(4))
    }

    @Test
    fun `extrinsic maps the device delta into the camera frame`() {
        // Camera frame = device frame rotated 90 deg about Z. The prediction must apply the
        // device delta conjugated into the camera frame: C * dR * C^T (reference rotation = I).
        val c = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val p = AttitudePosePredictor(cameraFromDevice = c)
        val ref = pose(0f, 0f, 0f, 0f)
        p.correct(ref, yaw(0f))
        val predicted = p.predict(yaw(90f))!!
        val dR = RotationDeltaMath.cameraRotationDelta(yaw(0f), yaw(90f))
        val expected = RotationDeltaMath.multiplyMat3(
            RotationDeltaMath.multiplyMat3(c, dR),
            RotationDeltaMath.transposeMat3(c),
        )
        for (row in 0 until 3) for (col in 0 until 3) {
            assertEquals(expected[row * 3 + col], predicted[col * 4 + row], 1e-5f)
        }
    }
}

package com.hereliesaz.sphereslam.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-math correctness for [RotationMath] (ported from GraffitiXR's RotationDeltaMathTest). */
class RotationMathTest {

    private val eps = 1e-4f

    private fun assertMatEquals(expected: FloatArray, actual: FloatArray, tolerance: Float = eps) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue(
                "index $i: expected ${expected[i]}, was ${actual[i]}",
                abs(expected[i] - actual[i]) <= tolerance,
            )
        }
    }

    private fun quaternionAboutAxis(axis: FloatArray, radians: Double): FloatArray {
        val half = radians / 2.0
        val s = sin(half).toFloat()
        return RotationMath.normalize(
            floatArrayOf(axis[0] * s, axis[1] * s, axis[2] * s, cos(half).toFloat()),
        )
    }

    @Test
    fun `identity quaternion produces the identity matrix`() {
        val identity3x3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertMatEquals(identity3x3, RotationMath.toRotationMatrix3x3(RotationMath.IDENTITY_QUATERNION))
    }

    @Test
    fun `90 degree quaternion about Z matches rotationAboutZ`() {
        val q = quaternionAboutAxis(floatArrayOf(0f, 0f, 1f), PI / 2.0)
        assertMatEquals(RotationMath.rotationAboutZ(90), RotationMath.toRotationMatrix3x3(q))
    }

    @Test
    fun `rotationAboutZ(0) is the identity`() {
        val identity3x3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertMatEquals(identity3x3, RotationMath.rotationAboutZ(0))
    }

    @Test
    fun `rotationAboutZ(360) is the identity`() {
        val identity3x3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertMatEquals(identity3x3, RotationMath.rotationAboutZ(360), tolerance = 1e-3f)
    }

    @Test
    fun `conjugate of a unit quaternion is its inverse`() {
        val q = quaternionAboutAxis(floatArrayOf(1f, 2f, 3f).let { v ->
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
        }, 1.234)
        val shouldBeIdentity = RotationMath.multiplyQuaternions(RotationMath.conjugate(q), q)
        assertMatEquals(RotationMath.IDENTITY_QUATERNION, shouldBeIdentity)
    }

    @Test
    fun `quaternion multiplication order matches matrix multiplication order`() {
        val qa = quaternionAboutAxis(floatArrayOf(0f, 0f, 1f), PI / 2.0)
        val qb = quaternionAboutAxis(floatArrayOf(0f, 1f, 0f), PI / 3.0)

        val viaQuaternion = RotationMath.toRotationMatrix3x3(
            RotationMath.normalize(RotationMath.multiplyQuaternions(qa, qb)),
        )
        val viaMatrix = RotationMath.multiplyMat3(
            RotationMath.toRotationMatrix3x3(qa),
            RotationMath.toRotationMatrix3x3(qb),
        )
        assertMatEquals(viaMatrix, viaQuaternion, tolerance = 1e-3f)
    }

    @Test
    fun `transpose of a rotation matrix is its inverse`() {
        val m = RotationMath.rotationAboutZ(37)
        val shouldBeIdentity = RotationMath.multiplyMat3(m, RotationMath.transposeMat3(m))
        val identity3x3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertMatEquals(identity3x3, shouldBeIdentity)
    }

    @Test
    fun `multiplyMat3Vec3 rotates a vector the same way multiplyMat3 rotates a matrix column`() {
        // 90 degrees about Z: (x,y,z) -> (-y,x,z). rotationAboutZ is [[c,-s,0],[s,c,0],[0,0,1]].
        val rot = RotationMath.rotationAboutZ(90)
        val v = floatArrayOf(1f, 0f, 0f)
        val rotated = RotationMath.multiplyMat3Vec3(rot, v)
        assertMatEquals(floatArrayOf(0f, 1f, 0f), rotated)
    }

    @Test
    fun `multiplyMat3Vec3 by the identity matrix leaves the vector unchanged`() {
        val identity3x3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val v = floatArrayOf(3f, -2f, 5f)
        assertMatEquals(v, RotationMath.multiplyMat3Vec3(identity3x3, v))
    }

    @Test
    fun `a rotation delta between a quaternion and itself is the identity`() {
        val q = quaternionAboutAxis(floatArrayOf(0.267f, 0.535f, 0.802f), 0.77)
        val delta = RotationMath.multiplyQuaternions(RotationMath.conjugate(q), q)
        assertMatEquals(RotationMath.IDENTITY_QUATERNION, delta)
    }

    @Test
    fun `normalize renormalizes a drifted-length quaternion without changing its direction`() {
        val q = floatArrayOf(0f, 0f, 0f, 2f) // identity direction, wrong length
        val n = RotationMath.normalize(q)
        assertMatEquals(RotationMath.IDENTITY_QUATERNION, n)
    }

    @Test
    fun `normalize of a near-zero quaternion falls back to identity rather than dividing by zero`() {
        val n = RotationMath.normalize(floatArrayOf(0f, 0f, 0f, 0f))
        assertMatEquals(RotationMath.IDENTITY_QUATERNION, n)
    }

    @Test
    fun `rotationAboutZ quarter turns are exact and agree with the general formula`() {
        assertMatEquals(floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), RotationMath.rotationAboutZ(90), 0f)
        assertMatEquals(RotationMath.rotationAboutZ(270), RotationMath.rotationAboutZ(-90), 0f)
        assertMatEquals(RotationMath.rotationAboutZ(180), RotationMath.rotationAboutZ(540), 0f)
        val q = quaternionAboutAxis(floatArrayOf(0f, 0f, 1f), Math.toRadians(37.0))
        assertMatEquals(RotationMath.toRotationMatrix3x3(q), RotationMath.rotationAboutZ(37))
    }

    @Test
    fun `cameraRotationDelta equals R of conj(qNow) times qRef`() {
        val qRef = quaternionAboutAxis(floatArrayOf(0f, 1f, 0f), 0.4)
        val qNow = quaternionAboutAxis(floatArrayOf(1f, 0f, 0f), -0.3)
        val viaQuat = RotationMath.toRotationMatrix3x3(
            RotationMath.normalize(RotationMath.multiplyQuaternions(RotationMath.conjugate(qNow), qRef)),
        )
        assertMatEquals(viaQuat, RotationMath.cameraRotationDelta(qRef, qNow))
    }

    @Test
    fun `rotateAboutCameraCentre rotates the basis and holds the camera centre`() {
        // Column-major camera-from-world: 20 deg about Y plus a translation.
        val r = Math.toRadians(20.0)
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val view = floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0.3f, -0.2f, 1.5f, 1f)
        val delta = RotationMath.rotationAboutZ(33)
        val out = RotationMath.rotateAboutCameraCentre(view, delta)
        fun centre(m: FloatArray) = floatArrayOf(
            -(m[0] * m[12] + m[1] * m[13] + m[2] * m[14]),
            -(m[4] * m[12] + m[5] * m[13] + m[6] * m[14]),
            -(m[8] * m[12] + m[9] * m[13] + m[10] * m[14]),
        )
        assertMatEquals(centre(view), centre(out))
        // Upper-left 3x3 (row-major) == delta * R.
        val rView = floatArrayOf(view[0], view[4], view[8], view[1], view[5], view[9], view[2], view[6], view[10])
        val expected = RotationMath.multiplyMat3(delta, rView)
        val got = floatArrayOf(out[0], out[4], out[8], out[1], out[5], out[9], out[2], out[6], out[10])
        assertMatEquals(expected, got)
        assertEquals(1f, out[15], 0f)
    }

    @Test
    fun `rotationAngleDegrees reads the angle back from the trace`() {
        assertEquals(37f, RotationMath.rotationAngleDegrees(RotationMath.rotationAboutZ(37)), 1e-3f)
        assertEquals(0f, RotationMath.rotationAngleDegrees(RotationMath.IDENTITY_3X3), 1e-3f)
    }
}

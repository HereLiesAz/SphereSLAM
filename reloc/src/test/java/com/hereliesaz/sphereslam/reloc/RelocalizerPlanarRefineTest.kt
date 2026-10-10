package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The pose-selection rule behind [Relocalizer]'s IPPE planar-flip refine. */
class RelocalizerPlanarRefineTest {

    private val fx = 500.0
    private val fy = 500.0
    private val cx = 320.0
    private val cy = 240.0

    /** Row-major camera_from_object: rotation [deg] about +Y, translation (0, 0, z). */
    private fun pose(deg: Double, z: Float): FloatArray {
        val r = Math.toRadians(deg)
        val c = kotlin.math.cos(r).toFloat(); val s = kotlin.math.sin(r).toFloat()
        return floatArrayOf(c, 0f, s, 0f, 0f, 1f, 0f, 0f, -s, 0f, c, z, 0f, 0f, 0f, 1f)
    }

    // A planar (z = 0) target, observed under the true pose.
    private val truePose = pose(25.0, 2f)
    private val obj = listOf(
        doubleArrayOf(-0.3, -0.2, 0.0), doubleArrayOf(0.3, -0.2, 0.0),
        doubleArrayOf(0.3, 0.2, 0.0), doubleArrayOf(-0.3, 0.2, 0.0), doubleArrayOf(0.05, 0.1, 0.0),
    )
    private val img = obj.map { p ->
        val m = truePose
        val x = m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3]
        val y = m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7]
        val z = m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11]
        doubleArrayOf(fx * x / z + cx, fy * y / z + cy)
    }
    private val inliers = IntArray(obj.size) { it }
    private val residual = { m: FloatArray ->
        Relocalizer.meanReprojectionErrorPx(m, fx, fy, cx, cy, obj, img, inliers)
    }

    @Test
    fun `the flipped RANSAC pose is replaced by the IPPE candidate that reprojects better`() {
        val flipped = pose(-25.0, 2f) // the planar mirror ambiguity
        val (chosen, err) = Relocalizer.lowestResidualPose(flipped, listOf(flipped, truePose), residual)
        assertArrayEquals(truePose, chosen, 0f)
        assertEquals(0f, err, 1e-3f)
    }

    @Test
    fun `refinement never makes the pose worse`() {
        val (chosen, err) = Relocalizer.lowestResidualPose(truePose, listOf(pose(-25.0, 2f), pose(10.0, 3f)), residual)
        assertSame(truePose, chosen)
        assertEquals(residual(truePose), err, 0f)
    }

    @Test
    fun `malformed and non-finite candidates are ignored`() {
        val bad = FloatArray(16) { Float.NaN }
        val (chosen, _) = Relocalizer.lowestResidualPose(truePose, listOf(bad, FloatArray(9)), residual)
        assertSame(truePose, chosen)
    }

    @Test
    fun `a finite candidate replaces a RANSAC pose with points behind the camera`() {
        val behind = pose(0.0, -2f)
        val (chosen, err) = Relocalizer.lowestResidualPose(behind, listOf(truePose), residual)
        assertArrayEquals(truePose, chosen, 0f)
        assertEquals(0f, err, 1e-3f)
    }
}

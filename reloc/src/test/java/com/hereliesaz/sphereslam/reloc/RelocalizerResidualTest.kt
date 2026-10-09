package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure residual math behind [RelocResult.reprojectionErrorPx]; the solve itself needs native. */
class RelocalizerResidualTest {
    private val pose = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 2f,
        0f, 0f, 0f, 1f,
    )
    private val obj = listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.2, -0.2, 0.0))

    @Test
    fun `exact projections have zero residual and offsets average over inliers only`() {
        // fx = fy = 100, c = (50, 50): (0,0,2) -> (50,50); (0.2,-0.2,2) -> (60,40).
        val exact = listOf(doubleArrayOf(50.0, 50.0), doubleArrayOf(60.0, 40.0))
        assertEquals(0f, Relocalizer.meanReprojectionErrorPx(pose, 100.0, 100.0, 50.0, 50.0, obj, exact, intArrayOf(0, 1)), 1e-5f)
        val off = listOf(doubleArrayOf(53.0, 54.0), doubleArrayOf(160.0, 40.0))
        assertEquals(5f, Relocalizer.meanReprojectionErrorPx(pose, 100.0, 100.0, 50.0, 50.0, obj, off, intArrayOf(0)), 1e-5f)
    }

    @Test
    fun `a point behind the camera yields an infinite residual`() {
        val behind = pose.copyOf().also { it[11] = -2f }
        val r = Relocalizer.meanReprojectionErrorPx(
            behind, 100.0, 100.0, 50.0, 50.0, obj, listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0)), intArrayOf(0),
        )
        assertEquals(Float.POSITIVE_INFINITY, r, 0f)
    }
}

package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileTriangulatorTest {

    private val fx = 500.0
    private val fy = 500.0
    private val cx = 320.0
    private val cy = 240.0

    /** Camera at world position [cx,cy,cz] with identity rotation (looking down +Z): cameraFromWorld = [I | -C]. */
    private fun camAt(x: Float, y: Float, z: Float) = floatArrayOf(
        1f, 0f, 0f, -x,
        0f, 1f, 0f, -y,
        0f, 0f, 1f, -z,
        0f, 0f, 0f, 1f,
    )

    /** Project a world point through an identity-rotation camera at C to a pixel. */
    private fun project(cx0: Float, cy0: Float, cz0: Float, px: Float, py: Float, pz: Float): Pair<Float, Float> {
        val xc = px - cx0; val yc = py - cy0; val zc = pz - cz0
        return Pair((fx * xc / zc + cx).toFloat(), (fy * yc / zc + cy).toFloat())
    }

    private fun view(camX: Float, camY: Float, camZ: Float, px: Float, py: Float, pz: Float): TileTriangulator.View {
        val (u, v) = project(camX, camY, camZ, px, py, pz)
        return TileTriangulator.View(camAt(camX, camY, camZ), u, v)
    }

    @Test
    fun `two views recover the point with near-zero reprojection error`() {
        val target = floatArrayOf(0.3f, -0.2f, 5f)
        val views = listOf(
            view(-1f, 0f, 0f, target[0], target[1], target[2]),
            view(1f, 0f, 0f, target[0], target[1], target[2]),
        )
        val t = TileTriangulator.triangulate(views, fx, fy, cx, cy)
        assertNotNull(t)
        assertArrayEqualsF(target, t!!.point, 1e-2f)
        assertTrue("rms ${t.reprojectionRmsPx}", t.reprojectionRmsPx < 1e-2f)
        assertTrue("parallax ${t.minParallaxDeg}", t.minParallaxDeg > 5f)
        assertEquals(2, t.views)
    }

    @Test
    fun `three views over-determine the point and stay accurate`() {
        val target = floatArrayOf(0f, 0f, 8f)
        val views = listOf(
            view(-1.5f, 0f, 0f, target[0], target[1], target[2]),
            view(0f, 1.5f, 0f, target[0], target[1], target[2]),
            view(1.5f, 0f, 0f, target[0], target[1], target[2]),
        )
        val t = TileTriangulator.triangulate(views, fx, fy, cx, cy)!!
        assertArrayEqualsF(target, t.point, 1e-2f)
        assertEquals(3, t.views)
    }

    @Test
    fun `a pixel observation error shows up as reprojection rms but the point stays close`() {
        // Three views over-determine the point; perturbing one observation off the plane the clean
        // rays share means no single 3D point reprojects to all three exactly, so rms > 0.
        val target = floatArrayOf(0f, 0f, 5f)
        val clean0 = view(-1.5f, 0f, 0f, target[0], target[1], target[2])
        val clean1 = view(1.5f, 0f, 0f, target[0], target[1], target[2])
        val (nu, nv) = project(0f, 1.5f, 0f, target[0], target[1], target[2])
        val noisy = TileTriangulator.View(camAt(0f, 1.5f, 0f), nu, nv + 1.5f)
        val t = TileTriangulator.triangulate(listOf(clean0, clean1, noisy), fx, fy, cx, cy)!!
        assertTrue("rms ${t.reprojectionRmsPx}", t.reprojectionRmsPx > 0.1f)
        assertArrayEqualsF(target, t.point, 0.2f)
    }

    @Test
    fun `fewer than two views is null`() {
        val v = view(0f, 0f, 0f, 0f, 0f, 5f)
        assertNull(TileTriangulator.triangulate(listOf(v), fx, fy, cx, cy))
        assertNull(TileTriangulator.triangulate(emptyList(), fx, fy, cx, cy))
    }

    @Test
    fun `parallel rays with zero parallax resolve at infinity and are null`() {
        // Both cameras look down +Z and observe the principal point, so both rays are the +Z axis:
        // parallel, never converging — the point solves at infinity (w ~ 0).
        val views = listOf(
            TileTriangulator.View(camAt(-1f, 0f, 0f), cx.toFloat(), cy.toFloat()),
            TileTriangulator.View(camAt(1f, 0f, 0f), cx.toFloat(), cy.toFloat()),
        )
        assertNull(TileTriangulator.triangulate(views, fx, fy, cx, cy))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive focal length is rejected`() {
        TileTriangulator.triangulate(
            listOf(view(-1f, 0f, 0f, 0f, 0f, 5f), view(1f, 0f, 0f, 0f, 0f, 5f)),
            fx = 0.0, fy = fy, cx = cx, cy = cy,
        )
    }

    private fun assertArrayEqualsF(expected: FloatArray, actual: FloatArray, tol: Float) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("index $i", expected[i], actual[i], tol)
    }
}

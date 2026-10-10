package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.math.RigidMath
import com.hereliesaz.sphereslam.math.RotationMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class PhotosphereAtlasGrowthTest {

    private val k = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)
    private val root = listOf(PhotosphereAtlasGrowth.PageWindow(0, 0f, 0f, 1f, 0.75f))

    /** GL camera-from-canonical for a camera at [c] yawed [deg] about +Y. */
    private fun view(c: FloatArray, deg: Float): FloatArray {
        val h = Math.toRadians(deg / 2.0)
        val r = RotationMath.toRotationMatrix3x3(floatArrayOf(0f, sin(h).toFloat(), 0f, cos(h).toFloat()))
        val worldFromCamera = RigidMath.identity()
        for (row in 0..2) for (col in 0..2) worldFromCamera[col * 4 + row] = r[row * 3 + col]
        worldFromCamera[12] = c[0]; worldFromCamera[13] = c[1]; worldFromCamera[14] = c[2]
        return RigidMath.rigidInverse(worldFromCamera)
    }

    private fun pattern(x: Float, y: Float): Float = 128f + 90f * sin(9f * x) * cos(7f * y)

    /** Render the wall [pattern] through [v] (every pixel ray hits the wall in these tests). */
    private fun render(v: FloatArray): ByteArray = ByteArray(k.width * k.height) { i ->
        val p = PhotosphereAtlasGrowth.backProjectToWall((i % k.width).toFloat(), (i / k.width).toFloat(), v, k)!!
        pattern(p[0], p[1]).roundToInt().coerceIn(0, 255).toByte()
    }

    @Test
    fun `projection and back-projection are inverse`() {
        val v = view(floatArrayOf(0.9f, 0.1f, 1.2f), 15f)
        for ((x, y) in listOf(0.5f to 0f, 0.7f to 0.2f, 0.3f to -0.1f)) {
            val uv = PhotosphereAtlasGrowth.projectWallPoint(x, y, v, k)!!
            val back = PhotosphereAtlasGrowth.backProjectToWall(uv[0], uv[1], v, k)!!
            assertArrayEquals(floatArrayOf(x, y), back, 1e-4f)
        }
        // Behind the camera / facing away.
        assertNull(PhotosphereAtlasGrowth.projectWallPoint(0f, 0f, view(floatArrayOf(0f, 0f, -1f), 0f), k))
        assertNull(PhotosphereAtlasGrowth.backProjectToWall(320f, 240f, view(floatArrayOf(0f, 0f, -1f), 0f), k))
    }

    @Test
    fun `no page is proposed too close to an existing one`() {
        assertNull(PhotosphereAtlasGrowth.propose(view(floatArrayOf(0.2f, 0f, 1f), 0f), k, 640, 480, 1f, 0.75f, root))
        assertNull(PhotosphereAtlasGrowth.propose(view(floatArrayOf(0.6f, 0f, 1f), 0f), k, 640, 480, 1f, 0.75f, emptyList()))
    }

    @Test
    fun `no page is proposed without overlap`() {
        // Centre 2.0 units away: no chain to page 0.
        assertNull(PhotosphereAtlasGrowth.propose(view(floatArrayOf(2f, 0f, 1f), 0f), k, 640, 480, 1f, 0.75f, root))
    }

    @Test
    fun `an off-centre view proposes a full-size page around the optical axis`() {
        val g = PhotosphereAtlasGrowth.propose(view(floatArrayOf(0.6f, 0f, 1f), 0f), k, 640, 480, 1f, 0.75f, root)!!
        assertEquals(0.6f, g.centerX, 1e-4f)
        assertEquals(0f, g.centerY, 1e-4f)
        assertEquals(1f, g.width, 1e-5f)
        assertEquals(0.75f, g.height, 1e-5f)
        assertArrayEquals(floatArrayOf(70f, 52.5f, 570f, 52.5f, 570f, 427.5f, 70f, 427.5f), g.sourceCorners, 1e-2f)
    }

    @Test
    fun `a far view shrinks the page until it fits`() {
        // At 0.8 units the frame spans 1.024 x 0.768: the full page does not fit inside the margins.
        val g = PhotosphereAtlasGrowth.propose(view(floatArrayOf(0.6f, 0f, 0.8f), 0f), k, 640, 480, 1f, 0.75f, root)!!
        assertTrue(g.width < 1f)
        assertEquals(0.75f / 1f, g.height / g.width, 1e-5f)
    }

    @Test
    fun `rectify recovers the fronto-parallel wall from an oblique view`() {
        val v = view(floatArrayOf(0.9f, 0.05f, 1.2f), 15f)
        val g = PhotosphereAtlasGrowth.propose(v, k, 640, 480, 1f, 0.75f, root)
        assertNotNull(g)
        g!!
        val page = PhotosphereAtlasGrowth.rectify(render(v), 640, 480, g)!!
        assertEquals((page.width * g.height / g.width).roundToInt(), page.height)
        var worst = 0f
        var total = 0f
        var n = 0
        for (py in 2 until page.height - 2 step 7) {
            for (px in 2 until page.width - 2 step 7) {
                val x = g.centerX - g.width / 2 + px.toFloat() / (page.width - 1) * g.width
                val y = g.centerY + g.height / 2 - py.toFloat() / (page.height - 1) * g.height
                val err = abs((page.luma[py * page.width + px].toInt() and 0xff) - pattern(x, y))
                worst = maxOf(worst, err)
                total += err
                n++
            }
        }
        assertTrue("mean error ${total / n}", total / n < 3f)
        assertTrue("worst error $worst", worst < 12f)
    }

    @Test
    fun `rectify rejects malformed input`() {
        val g = PhotosphereAtlasGrowth.Geometry(0f, 0f, 1f, 0.75f, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        assertNull(PhotosphereAtlasGrowth.rectify(ByteArray(10), 640, 480, g))
        assertNull(PhotosphereAtlasGrowth.rectify(ByteArray(640 * 480), 640, 480, g))
    }

    @Test
    fun `pose gate and page transform`() {
        assertTrue(PhotosphereAtlasGrowth.poseQualifies(18, 4f, 0))
        assertFalse(PhotosphereAtlasGrowth.poseQualifies(17, 1f, 0))
        assertFalse(PhotosphereAtlasGrowth.poseQualifies(30, 4.1f, 0))
        assertFalse(PhotosphereAtlasGrowth.poseQualifies(30, 1f, PhotosphereAtlasGrowth.MAX_PAGES - 1))
        val m = PhotosphereAtlasGrowth.canonicalFromPage(0.4f, -0.2f)
        assertEquals(0.4f, m[12], 0f)
        assertEquals(-0.2f, m[13], 0f)
        assertEquals(0f, RigidMath.rotationAngleDeg(m), 0f)
        assertEquals(
            0.4f,
            PhotosphereAtlasGrowth.overlapRatio(0.6f, 0f, 1f, 0.75f, root[0]),
            1e-5f,
        )
    }
}

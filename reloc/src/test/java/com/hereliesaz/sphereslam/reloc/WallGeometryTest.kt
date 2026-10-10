package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.math.RigidMath
import com.hereliesaz.sphereslam.math.RotationMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** [WallPlane], [RectifyHomography], [MetricMarks] and [RadialDepthPlacement]. */
class WallGeometryTest {

    private val fx = 500f
    private val fy = 500f
    private val cx = 320f
    private val cy = 240f

    /** GL view (column-major camera-from-world) of a camera at [c] yawed [deg] about +Y. */
    private fun glView(c: FloatArray, deg: Float): FloatArray {
        val h = Math.toRadians(deg / 2.0)
        val r = RotationMath.toRotationMatrix3x3(floatArrayOf(0f, sin(h).toFloat(), 0f, cos(h).toFloat()))
        val worldFromCamera = RigidMath.identity()
        for (row in 0..2) for (col in 0..2) worldFromCamera[col * 4 + row] = r[row * 3 + col]
        worldFromCamera[12] = c[0]; worldFromCamera[13] = c[1]; worldFromCamera[14] = c[2]
        return RigidMath.rigidInverse(worldFromCamera)
    }

    /** Pixel of world point [p] through a GL view. */
    private fun pixel(view: FloatArray, p: FloatArray): FloatArray {
        val x = view[0] * p[0] + view[4] * p[1] + view[8] * p[2] + view[12]
        val y = view[1] * p[0] + view[5] * p[1] + view[9] * p[2] + view[13]
        val z = view[2] * p[0] + view[6] * p[1] + view[10] * p[2] + view[14]
        return floatArrayOf(fx * x / -z + cx, fy * -y / -z + cy)
    }

    private val wallWorld = (0 until 20).map { i -> floatArrayOf(-0.8f + 0.09f * i, -0.5f + 0.05f * ((i * 7) % 20), -2f) }
    private val refView = RigidMath.identity()

    /** Reference marks in the reference camera's OpenCV frame. */
    private val refPointsCv = FloatArray(wallWorld.size * 3).also { out ->
        wallWorld.forEachIndexed { i, p ->
            val cv = RigidMath.glViewToCv(refView)
            out[3 * i] = cv[0] * p[0] + cv[4] * p[1] + cv[8] * p[2] + cv[12]
            out[3 * i + 1] = cv[1] * p[0] + cv[5] * p[1] + cv[9] * p[2] + cv[13]
            out[3 * i + 2] = cv[2] * p[0] + cv[6] * p[1] + cv[10] * p[2] + cv[14]
        }
    }

    @Test
    fun `plane fit recovers centroid and normal`() {
        val plane = WallPlane.fit(refPointsCv)!!
        assertEquals(1f, abs(plane.normal[2]), 1e-4f)
        assertEquals(2f, plane.point[2], 1e-5f)
        assertEquals(0f, plane.signedDistance(0.3f, 0.2f, 2f), 1e-5f)
        assertNull(WallPlane.fit(floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 2f, 2f, 2f, 3f, 3f, 3f)))
        assertNull(WallPlane.fit(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f)))
        val moved = WallPlane(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 2f))
            .transformedBy(RigidMath.identity().also { it[14] = 5f })
        assertArrayEquals(floatArrayOf(0f, 0f, 5f), moved.point, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), moved.normal, 0f)
    }

    @Test
    fun `rectify homography maps reference pixels onto current pixels`() {
        val cur = glView(floatArrayOf(1.2f, 0.1f, 0f), 30f)
        val r = RectifyHomography.compute(cur, refView, refPointsCv, fx.toDouble(), fy.toDouble(), cx.toDouble(), cy.toDouble())!!
        assertEquals(30.0, r.obliquityDeg, 0.05)
        for (p in wallWorld + listOf(floatArrayOf(0.4f, 0.3f, -2f))) {
            val ref = pixel(refView, p)
            val expected = pixel(cur, p)
            val mapped = r.referenceToCurrent(ref[0].toDouble(), ref[1].toDouble())!!
            assertEquals(expected[0].toDouble(), mapped[0], 1e-2)
            assertEquals(expected[1].toDouble(), mapped[1], 1e-2)
            val back = r.currentToReference(mapped[0], mapped[1])!!
            assertEquals(ref[0].toDouble(), back[0], 1e-2)
            assertEquals(ref[1].toDouble(), back[1], 1e-2)
        }
        val head = RectifyHomography.compute(refView, refView, refPointsCv, 500.0, 500.0, 320.0, 240.0)!!
        assertEquals(0.0, head.obliquityDeg, 0.01)
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), head.currentFromReference, 1e-6)
        assertNull(RectifyHomography.compute(cur, refView, refPointsCv.copyOf(30), 500.0, 500.0, 320.0, 240.0))
    }

    @Test
    fun `warping the current frame into the reference frame unwarps the wall`() {
        val cur = glView(floatArrayOf(1.2f, 0.1f, 0f), 30f)
        val r = RectifyHomography.compute(cur, refView, refPointsCv, fx.toDouble(), fy.toDouble(), cx.toDouble(), cy.toDouble())!!
        fun pattern(x: Float, y: Float) = 128f + 90f * sin(7f * x) * cos(5f * y)
        // Render the wall z = -2 through the current camera.
        val curGlInv = RigidMath.rigidInverse(cur)
        val luma = ByteArray(640 * 480) { i ->
            val u = (i % 640).toFloat(); val v = (i / 640).toFloat()
            val d = floatArrayOf((u - cx) / fx, -(v - cy) / fy, -1f)
            val o = floatArrayOf(curGlInv[12], curGlInv[13], curGlInv[14])
            val w = floatArrayOf(
                curGlInv[0] * d[0] + curGlInv[4] * d[1] + curGlInv[8] * d[2],
                curGlInv[1] * d[0] + curGlInv[5] * d[1] + curGlInv[9] * d[2],
                curGlInv[2] * d[0] + curGlInv[6] * d[1] + curGlInv[10] * d[2],
            )
            val t = (-2f - o[2]) / w[2]
            pattern(o[0] + t * w[0], o[1] + t * w[1]).roundToInt().coerceIn(0, 255).toByte()
        }
        val warped = RectifyHomography.warpCurrentToReference(luma, 640, 480, r)
        var total = 0f; var n = 0
        for (v in 140 until 340 step 9) for (u in 220 until 420 step 9) {
            // Reference camera at the origin looking down -z: wall point under (u, v).
            val x = 2f * (u - cx) / fx; val y = -2f * (v - cy) / fy
            total += abs((warped[v * 640 + u].toInt() and 0xff) - pattern(x, y)); n++
        }
        assertTrue("mean error ${total / n}", total / n < 3f)
    }

    @Test
    fun `plane back-projection keeps rays that hit the plane in range`() {
        val cvView = RigidMath.identity()
        val r = MetricMarks.backProjectToPlane(
            floatArrayOf(cx, cy, cx + 100f, cy, cx, cy - 50f),
            cvView, floatArrayOf(0f, 0f, 2f), floatArrayOf(0f, 0f, 5f), fx, fy, cx, cy,
        )
        assertArrayEquals(intArrayOf(0, 1, 2), r.kept)
        assertArrayEquals(floatArrayOf(0f, 0f, 2f, 0.4f, 0f, 2f, 0f, -0.2f, 2f), r.points, 1e-5f)
        // A floor plane below the camera: upper-half rays never hit it, a far one is out of range.
        val floor = MetricMarks.backProjectToPlane(
            floatArrayOf(cx, cy - 10f, cx, cy + 100f, cx, cy + 1f),
            cvView, floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f), fx, fy, cx, cy,
        )
        assertArrayEquals(intArrayOf(1), floor.kept)
        assertEquals(5f, floor.points[2], 1e-4f)
    }

    @Test
    fun `two-view triangulation recovers marks in the first camera frame`() {
        val v0 = RigidMath.glViewToCv(glView(floatArrayOf(0f, 0f, 0f), 0f))
        val v1 = RigidMath.glViewToCv(glView(floatArrayOf(0.3f, 0f, 0.1f), 5f))
        val world = listOf(floatArrayOf(0.1f, 0.2f, -2f), floatArrayOf(-0.3f, -0.1f, -2.5f), floatArrayOf(0.2f, -0.2f, -1.8f))
        fun px(view: FloatArray, p: FloatArray): FloatArray {
            val x = view[0] * p[0] + view[4] * p[1] + view[8] * p[2] + view[12]
            val y = view[1] * p[0] + view[5] * p[1] + view[9] * p[2] + view[13]
            val z = view[2] * p[0] + view[6] * p[1] + view[10] * p[2] + view[14]
            return floatArrayOf(fx * x / z + cx, fy * y / z + cy)
        }
        val corrs = world.map { p -> px(v0, p).let { a -> px(v1, p).let { b -> MetricMarks.Corr(a[0], a[1], b[0], b[1]) } } } +
            MetricMarks.Corr(300f, 200f, 50f, 400f) // inconsistent pair
        val r = MetricMarks.triangulateTwoView(corrs, v0, v1, fx, fy, cx, cy)
        assertArrayEquals(intArrayOf(0, 1, 2), r.kept)
        world.forEachIndexed { i, p ->
            // v0 is the identity GL camera: CV frame = (x, -y, -z).
            assertArrayEquals(floatArrayOf(p[0], -p[1], -p[2]), r.points.copyOfRange(3 * i, 3 * i + 3), 2e-3f)
        }
        assertEquals(0, MetricMarks.triangulateTwoView(corrs, v0, v0, fx, fy, cx, cy).count)
    }

    @Test
    fun `display-oriented views agree between conventions`() {
        val v = glView(floatArrayOf(0.2f, -0.1f, 0.4f), 17f)
        for (rot in listOf(0, 90, 180, 270, -90)) {
            assertArrayEquals(
                MetricMarks.glViewToCvDisplay(v, rot),
                RigidMath.glViewToCv(MetricMarks.glViewDisplayOriented(v, rot)),
                1e-6f,
            )
        }
        assertArrayEquals(RigidMath.glViewToCv(v), MetricMarks.glViewToCvDisplay(v, 360), 0f)
    }

    @Test
    fun `radial depth placement calibrates against the plane`() {
        val map = InverseDepthMap(FloatArray(4 * 3) { it.toFloat() }, 4, 3, 40, 30)
        assertEquals(5f, map.sample(15f, 12f), 0f)
        assertTrue(map.sample(-1f, 0f).isNaN())
        assertTrue(map.sample(40f, 0f).isNaN())

        // invDepth = 2/Z + 0.5 everywhere; plane samples at varying depth.
        val a = 2f; val b = 0.5f
        val depths = floatArrayOf(1f, 1.5f, 2f, 3f, 4f, 6f, 8f)
        val w = depths.size
        val dm = InverseDepthMap(FloatArray(w) { a / depths[it] + b }, w, 1, w, 1)
        val pixels = FloatArray(2 * w) { if (it % 2 == 0) (it / 2).toFloat() else 0f }
        val fit = RadialDepthPlacement.calibrate(pixels, depths, dm)!!
        assertEquals(a, fit.a, 1e-3f)
        assertEquals(b, fit.b, 1e-3f)
        assertEquals(3f, RadialDepthPlacement.radialDepth(fit, dm, 3f, 0f, wallDistance = 2f)!!, 1e-3f)
        assertNull(RadialDepthPlacement.radialDepth(fit, dm, 0f, 0f, wallDistance = 20f)) // 1 < 0.1 * 20
        assertNotNull(RadialDepthPlacement.radialDepth(fit, dm, 6f, 0f, wallDistance = 1f))
        assertNull(RadialDepthPlacement.calibrate(pixels, FloatArray(w) { -1f }, dm))
    }
}

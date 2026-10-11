package com.hereliesaz.sphereslam

import com.hereliesaz.sphereslam.CoverageGlowProjection.Lens
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.math.RotationMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

/** The full-rotation projection: roll, poles, edge sampling, lens, and agreement with the old path. */
class CoverageGlowFullRotationTest {

    private fun region(az0: Float, az1: Float, el0: Float, el1: Float) = SphereCoverage.TileRegion(az0, az1, el0, el1)
    private fun dir(az: Float, el: Float) = SphereCoverage.Direction(az, el)
    private fun vertices(v: FloatArray) = (v.indices step 2).map { v[it] to v[it + 1] }

    @Test
    @Suppress("DEPRECATION")
    fun `zero roll reproduces the heading-elevation projection`() {
        val regions = listOf(region(10f, 30f, -10f, 10f), region(30f, 50f, 10f, 30f))
        for ((h, e) in listOf(20f to 0f, 35f to 15f, 300f to -20f)) {
            val old = CoverageGlowProjection.projectRegions(regions, h, e, 70f, 55f, subdivisions = 4)
            val new = CoverageGlowProjection.projectRegions(
                regions, CoverageGlowProjection.worldFromCamera(h, e)!!, Lens.fromFieldOfView(70f, 55f),
                maxSegmentDeg = 5f, // 20° tiles -> 4 segments per side, as the old call
            )
            assertEquals(old.size, new.size)
            assertArrayEquals(old, new, 1e-4f)
        }
    }

    @Test
    fun `projection agrees with directionOfPixel at any roll and off-centre principal point`() {
        val k = CameraIntrinsics(fx = 900f, fy = 880f, cx = 500f, cy = 980f, width = 1080, height = 1920)
        val lens = Lens.fromIntrinsics(k)
        for (roll in floatArrayOf(0f, 25f, -60f, 90f, 170f)) {
            val m = CoverageGlowProjection.worldFromCamera(40f, 20f, roll)!!
            for ((u, v) in listOf(100f to 200f, 540f to 960f, 1000f to 1800f, 50f to 1500f)) {
                val d = PhotosphereMap.directionOfPixel(u, v, k.fx, k.fy, k.cx, k.cy, 40f, 20f, roll)!!
                val mark = CoverageGlowProjection.project(listOf(d), m, lens).single()
                assertTrue(mark.onScreen)
                assertEquals("roll $roll u", 2f * u / k.width - 1f, mark.ndcX, 2e-3f)
                assertEquals("roll $roll v", 1f - 2f * v / k.height, mark.ndcY, 2e-3f)
            }
        }
    }

    @Test
    fun `roll turns the scene opposite to the camera`() {
        // Right edge of the image raised 90°: a horizon point right of the view axis appears below.
        val m = CoverageGlowProjection.worldFromCamera(0f, 0f, 90f)!!
        val mark = CoverageGlowProjection.project(listOf(dir(20f, 0f)), m, Lens.fromFieldOfView(90f, 90f)).single()
        assertEquals(0f, mark.ndcX, 1e-4f)
        assertTrue(mark.ndcY < -0.3f)
    }

    @Test
    fun `sensor matrix path equals the heading-elevation-roll path`() {
        // Device held upright in portrait, rotated: build device->world from the camera basis and
        // check CameraAttitudeProvider's own readings reproduce the same world-from-camera.
        val m = CoverageGlowProjection.worldFromCamera(123f, -17f, 31f)!!
        // With cameraFromDevice = identity the device->world matrix is world-from-camera itself.
        val deviceToWorld = m.copyOf()
        val (h, e) = CameraAttitudeProvider.cameraAxisHeadingElevation(deviceToWorld)!!
        val roll = CameraAttitudeProvider.cameraRollDegrees(deviceToWorld)!!
        assertEquals(123f, h, 1e-3f); assertEquals(-17f, e, 1e-3f); assertEquals(31f, roll, 1e-3f)
        assertArrayEquals(m, CoverageGlowProjection.worldFromCamera(deviceToWorld), 1e-6f)
        // A display rotation: identity body frame mapped through R_z(90).
        val c = RotationMath.rotationAboutZ(90)
        val w = CoverageGlowProjection.worldFromCamera(deviceToWorld, c)
        assertArrayEquals(RotationMath.multiplyMat3(deviceToWorld, RotationMath.transposeMat3(c)), w, 1e-6f)
    }

    @Test
    fun `looking straight up still projects the polar band around the centre`() {
        // Device face-down: body z points down, so the rear camera (body -z) looks at the zenith.
        val faceDown = floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f)
        assertNull(CoverageGlowProjection.worldFromCamera(0f, 90f))
        val m = CoverageGlowProjection.worldFromCamera(faceDown)
        val cap = (0 until 12).map { region(it * 30f, it * 30f + 30f, 60f, 90f) }
        val v = CoverageGlowProjection.projectRegions(cap, m, Lens.fromFieldOfView(90f, 90f))
        assertTrue(v.isNotEmpty())
        val r = tan(Math.toRadians(30.0)).toFloat() // 30° off axis at the band's lower edge
        var maxR = 0f
        for ((x, y) in vertices(v)) maxR = maxOf(maxR, kotlin.math.hypot(x, y))
        assertEquals(r, maxR, 1e-3f)
        // The cap is symmetric about the centre.
        val xs = vertices(v).map { it.first }
        assertEquals(-xs.max(), xs.min(), 1e-3f)
    }

    @Test
    fun `tile edges are subdivided to the requested angular step`() {
        val m = CoverageGlowProjection.worldFromCamera(15f, 10f)!!
        val lens = Lens.fromFieldOfView(100f, 80f)
        val tile = listOf(region(0f, 30f, 0f, 20f))
        // 30° / 5° = 6 by 20° / 5° = 4 cells, 2 triangles each, 3 vertices x 2 floats.
        assertEquals(6 * 4 * 12, CoverageGlowProjection.projectRegions(tile, m, lens).size)
        assertEquals(12, CoverageGlowProjection.projectRegions(tile, m, lens, maxSegmentDeg = 90f).size)
        assertEquals(3 * 2 * 12, CoverageGlowProjection.projectRegions(tile, m, lens, maxSegmentDeg = 10f).size)
    }

    @Test
    fun `subdivided parallels follow the true curve under roll`() {
        // The bottom edge (el = 30) of a wide tile, viewed rolled: the midpoint of each sampled
        // segment must stay close to the projection of the true midpoint direction.
        val m = CoverageGlowProjection.worldFromCamera(30f, 35f, 40f)!!
        val lens = Lens.fromFieldOfView(100f, 100f)
        fun ndc(az: Float, el: Float) = CoverageGlowProjection.project(listOf(dir(az, el)), m, lens).single().let { it.ndcX to it.ndcY }
        fun worstChordError(step: Float): Float {
            var worst = 0f
            var az = 0f
            while (az < 60f) {
                val a = ndc(az, 30f); val b = ndc(az + step, 30f); val mid = ndc(az + step / 2f, 30f)
                worst = maxOf(worst, kotlin.math.hypot((a.first + b.first) / 2f - mid.first, (a.second + b.second) / 2f - mid.second))
                az += step
            }
            return worst
        }
        val fine = worstChordError(CoverageGlowProjection.DEFAULT_MAX_SEGMENT_DEG)
        val coarse = worstChordError(60f)
        assertTrue("fine $fine", fine < 0.005f)
        assertTrue("coarse $coarse", coarse > 10 * fine)
    }

    @Test
    fun `adjacent tiles across the 0-360 seam share edge vertices exactly`() {
        val m = CoverageGlowProjection.worldFromCamera(5f, 0f, 12f)!!
        val lens = Lens.fromFieldOfView(80f, 60f)
        val left = vertices(CoverageGlowProjection.projectRegions(listOf(region(340f, 370f, -10f, 10f)), m, lens)).toSet()
        val right = vertices(CoverageGlowProjection.projectRegions(listOf(region(10f, 40f, -10f, 10f)), m, lens)).toSet()
        val shared = left.intersect(right)
        assertEquals(5, shared.size) // the az = 10 meridian, 4 segments + 1
    }

    @Test
    fun `prepared regions project repeatedly into a reused buffer`() {
        val prepared = CoverageGlowProjection.prepareRegions(listOf(region(0f, 30f, -10f, 10f), region(170f, 200f, -10f, 10f)))
        val out = FloatArray(prepared.maxVertexFloats)
        val lens = Lens.fromFieldOfView(60f, 45f)
        val n1 = prepared.projectInto(CoverageGlowProjection.worldFromCamera(15f, 0f)!!, lens, out)
        assertEquals("the tile behind is dropped", prepared.maxVertexFloats / 2, n1)
        val n2 = prepared.projectInto(CoverageGlowProjection.worldFromCamera(185f, 0f)!!, lens, out)
        assertEquals(n1, n2)
        // Centred tile now is the second one: all x within its half-width.
        for (i in 0 until n2 step 2) assertTrue(abs(out[i]) <= tan(Math.toRadians(15.0)).toFloat() / tan(Math.toRadians(30.0)).toFloat() + 1e-4f)
        assertThrows(IllegalArgumentException::class.java) {
            prepared.projectInto(RotationMath.IDENTITY_3X3, lens, FloatArray(prepared.maxVertexFloats - 1))
        }
        assertEquals(0, CoverageGlowProjection.prepareRegions(emptyList()).maxVertexFloats)
    }

    @Test
    fun `lens factories agree and validate`() {
        val k = CameraIntrinsics(fx = 800f, fy = 800f, cx = 640f, cy = 360f, width = 1280, height = 720)
        val fromK = Lens.fromIntrinsics(k)
        val hFov = Math.toDegrees(2.0 * kotlin.math.atan(640.0 / 800.0)).toFloat()
        val vFov = Math.toDegrees(2.0 * kotlin.math.atan(360.0 / 800.0)).toFloat()
        val fromFov = Lens.fromFieldOfView(hFov, vFov)
        assertEquals(fromFov.scaleX, fromK.scaleX, 1e-4f)
        assertEquals(fromFov.scaleY, fromK.scaleY, 1e-4f)
        assertEquals(0f, fromK.offsetX, 1e-6f)
        assertEquals(0f, fromK.offsetY, 1e-6f)
        for (bad in listOf<() -> Unit>(
            { Lens(0f, 1f) },
            { Lens(1f, Float.NaN) },
            { Lens.fromFieldOfView(180f, 45f) },
            { Lens.fromIntrinsics(0f, 1f, 0f, 0f, 10, 10) },
            { Lens.fromIntrinsics(1f, 1f, 0f, 0f, 0, 10) },
            { CoverageGlowProjection.prepareRegions(emptyList(), maxSegmentDeg = 0f) },
            { CoverageGlowProjection.prepareRegions(listOf(region(Float.NaN, 1f, 0f, 1f))) },
            { CoverageGlowProjection.projectRegions(emptyList(), FloatArray(8), fromK) },
            { CoverageGlowProjection.worldFromCamera(FloatArray(9) { Float.NaN }) },
        )) {
            assertThrows(IllegalArgumentException::class.java) { bad() }
        }
    }
}

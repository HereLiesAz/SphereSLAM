package com.hereliesaz.sphereslam.overlay

import com.hereliesaz.sphereslam.CoverageGlowProjection
import com.hereliesaz.sphereslam.SphereCoverage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GL-free contract of [CoverageGlowRenderer]. Unit tests run against the Android stub jar (GL
 * calls return defaults), so drawing paths are exercised only for "no resources -> no-op"; geometry
 * resolution ([CoverageGlowRenderer.refreshGeometry]) is pure and tested directly.
 */
class CoverageGlowRendererTest {

    private val regions = listOf(
        SphereCoverage.TileRegion(0f, 30f, -10f, 10f),
        SphereCoverage.TileRegion(30f, 60f, -10f, 10f),
    )
    private val lens = CoverageGlowProjection.Lens.fromFieldOfView(70f, 55f)

    @Test
    fun `defaults keep the haze behaviour`() {
        val r = CoverageGlowRenderer()
        assertEquals(CoverageGlowRenderer.FillMode.TRIANGLES, r.fillMode)
        assertArrayEquals(CoverageGlowRenderer.HOT_PINK, r.hazeColor, 0f)
        assertEquals(CoverageGlowRenderer.DEFAULT_HAZE_ALPHA, r.hazeAlpha, 0f)
        assertEquals(CoverageGlowRenderer.DEFAULT_OUTLINE_ALPHA, r.outlineAlpha, 0f)
        assertEquals(CoverageGlowRenderer.DEFAULT_OUTLINE_WIDTH_PX, r.outlineWidthPx, 0f)
        assertEquals(null, r.rotationSource)
    }

    @Test
    fun `embedded draw without GL resources is a no-op in every mode`() {
        val r = CoverageGlowRenderer(CoverageGlowRenderer.WHITE, 0.5f)
        r.setTriangles(floatArrayOf(-1f, -1f, 1f, -1f, 0f, 1f))
        for (mode in CoverageGlowRenderer.FillMode.entries) {
            r.fillMode = mode
            r.drawEmbedded()
            r.drawEmbedded(123L)
        }
    }

    @Test
    fun `triangles must be whole`() {
        assertThrows(IllegalArgumentException::class.java) {
            CoverageGlowRenderer().setTriangles(FloatArray(5))
        }
    }

    @Test
    fun `style setters accept valid values and reject invalid ones`() {
        val r = CoverageGlowRenderer()
        r.hazeColor = floatArrayOf(0f, 0.5f, 1f)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f), r.hazeColor, 0f)
        r.setHazeColorArgb(0x80FF8000.toInt())
        assertArrayEquals(floatArrayOf(1f, 128f / 255f, 0f), r.hazeColor, 1e-6f)
        r.hazeAlpha = 0.7f; r.outlineAlpha = 1f; r.outlineWidthPx = 40f
        assertEquals(0.7f, r.hazeAlpha, 0f); assertEquals(1f, r.outlineAlpha, 0f); assertEquals(40f, r.outlineWidthPx, 0f)
        r.fillMode = CoverageGlowRenderer.FillMode.COMPLEMENT_OUTLINED
        assertEquals(CoverageGlowRenderer.FillMode.COMPLEMENT_OUTLINED, r.fillMode)
        for (bad in listOf<() -> Unit>(
            { r.hazeColor = floatArrayOf(1f, 1f) },
            { r.hazeColor = floatArrayOf(1f, 1.5f, 0f) },
            { r.hazeAlpha = -0.1f },
            { r.hazeAlpha = Float.NaN },
            { r.outlineAlpha = 2f },
            { r.outlineWidthPx = 0f },
            { r.outlineWidthPx = CoverageGlowRenderer.MAX_OUTLINE_WIDTH_PX + 1f },
            { r.setWorldFromCamera(FloatArray(4)) },
            { CoverageGlowRenderer(floatArrayOf(2f, 0f, 0f)) },
            { CoverageGlowRenderer(hazeAlpha = 1.1f) },
        )) {
            assertThrows(IllegalArgumentException::class.java) { bad() }
        }
        // Rejected values left the previous state in place.
        assertEquals(0.7f, r.hazeAlpha, 0f)
        assertEquals(40f, r.outlineWidthPx, 0f)
    }

    @Test
    fun `colour reads are copies`() {
        val r = CoverageGlowRenderer()
        r.hazeColor[0] = 0f
        assertArrayEquals(CoverageGlowRenderer.HOT_PINK, r.hazeColor, 0f)
    }

    @Test
    fun `live regions are re-projected with the pushed rotation`() {
        val r = CoverageGlowRenderer()
        r.setRegions(regions)
        assertEquals("no lens or rotation yet", 0, r.refreshGeometry(0L))
        r.setLens(lens)
        assertEquals("no rotation yet", 0, r.refreshGeometry(0L))
        val m = CoverageGlowProjection.worldFromCamera(30f, 0f, 15f)!!
        r.setWorldFromCamera(m)
        assertTrue(r.refreshGeometry(0L) > 0)
        assertArrayEquals(CoverageGlowProjection.projectRegions(regions, m, lens), r.resolvedGeometry(), 0f)
    }

    @Test
    fun `a rotation source is queried every frame with the frame time`() {
        val r = CoverageGlowRenderer()
        r.setRegions(regions)
        r.setLens(lens)
        val times = ArrayList<Long>()
        var heading = 10f
        r.rotationSource = CoverageGlowRenderer.RotationSource { t, out ->
            times.add(t)
            CoverageGlowProjection.worldFromCamera(heading, 0f)!!.copyInto(out)
            true
        }
        r.refreshGeometry(100L)
        val first = r.resolvedGeometry()
        heading = 40f
        r.refreshGeometry(200L)
        val second = r.resolvedGeometry()
        assertEquals(listOf(100L, 200L), times)
        assertArrayEquals(
            CoverageGlowProjection.projectRegions(regions, CoverageGlowProjection.worldFromCamera(40f, 0f)!!, lens),
            second, 0f,
        )
        assertTrue(!first.contentEquals(second))
    }

    @Test
    fun `an unavailable or failing source holds the last projection`() {
        val r = CoverageGlowRenderer()
        r.setRegions(regions)
        r.setLens(lens)
        var mode = 0
        r.rotationSource = CoverageGlowRenderer.RotationSource { _, out ->
            when (mode) {
                0 -> { CoverageGlowProjection.worldFromCamera(30f, 0f)!!.copyInto(out); true }
                1 -> false
                2 -> { out.fill(Float.NaN); true }
                else -> throw IllegalStateException("sensor gone")
            }
        }
        r.refreshGeometry(0L)
        val held = r.resolvedGeometry()
        assertTrue(held.isNotEmpty())
        for (m in 1..3) {
            mode = m
            r.refreshGeometry(0L)
            assertArrayEquals(held, r.resolvedGeometry(), 0f)
        }
        // New regions drop the old projection rather than drawing it against the wrong tiles.
        r.setRegions(regions.take(1))
        assertEquals(0, r.refreshGeometry(0L))
    }

    @Test
    fun `setTriangles switches back to static geometry`() {
        val r = CoverageGlowRenderer()
        r.setRegions(regions)
        r.setLens(lens)
        r.setWorldFromCamera(CoverageGlowProjection.worldFromCamera(30f, 0f)!!)
        assertTrue(r.refreshGeometry(0L) > 6)
        val tri = floatArrayOf(-1f, -1f, 1f, -1f, 0f, 1f)
        r.setTriangles(tri)
        assertEquals(6, r.refreshGeometry(0L))
        assertArrayEquals(tri, r.resolvedGeometry(), 0f)
    }
}

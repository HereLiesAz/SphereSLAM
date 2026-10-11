package com.hereliesaz.sphereslam.overlay

import com.hereliesaz.sphereslam.CoverageGlowProjection
import com.hereliesaz.sphereslam.PhotosphereMap
import com.hereliesaz.sphereslam.SphereCoverage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val tri = floatArrayOf(-1f, -1f, 1f, -1f, 0f, 1f)
        r.setTriangles(tri)
        for (mode in CoverageGlowRenderer.FillMode.entries) {
            r.fillMode = mode
            assertFalse("$mode drew without resources", r.draw(123L))
            r.drawEmbedded() // the public entry point is the same no-op and never throws
            // The geometry was still resolved for the frame.
            assertArrayEquals(tri, r.resolvedGeometry(), 0f)
        }
        // Context loss forgets names without touching GL; a new generation is counted per create.
        r.onGlContextLost()
        assertFalse(r.draw(0L))
        val before = r.glGeneration
        r.createGlResources() // against the stub GL: every name comes back 0
        assertEquals(before + 1, r.glGeneration)
        assertFalse(r.draw(0L))
    }

    @Test
    fun `GL version parsing decides whether the glow uses its own VAO`() {
        assertTrue(CoverageGlowRenderer.isEs3("OpenGL ES 3.2 V@0502.0"))
        assertTrue(CoverageGlowRenderer.isEs3("OpenGL ES 3.0 build 1.13"))
        assertFalse(CoverageGlowRenderer.isEs3("OpenGL ES 2.0 build 1.9"))
        assertFalse(CoverageGlowRenderer.isEs3("OpenGL ES-CM 1.1"))
        assertFalse(CoverageGlowRenderer.isEs3(null))
    }

    @Test
    fun `wash and edge alpha follow the shader math`() {
        // Wash: full alpha outside the current regions, none inside.
        assertEquals(0.3f, CoverageGlowRenderer.washAlpha(0f, 0.3f), 0f)
        assertEquals(0f, CoverageGlowRenderer.washAlpha(1f, 0.3f), 0f)
        // Edge: inside a region nothing is drawn, however strong the ring.
        assertEquals(0f, CoverageGlowRenderer.edgeAlpha(1f, 16f, 0f, 0.5f), 0f)
        // At the boundary (half the taps inside, tapSum = 8) the glow peaks …
        assertEquals(0.5f, CoverageGlowRenderer.edgeAlpha(0f, 8f, 0f, 0.5f), 1e-6f)
        // … fades with distance (fewer taps inside) …
        val mid = CoverageGlowRenderer.edgeAlpha(0f, 2f, 0f, 0.5f)
        assertTrue(mid > 0f && mid < 0.5f)
        assertEquals(0.5f * (0.25f * 0.25f * (3f - 0.5f)), mid, 1e-6f)
        // … and is gone a full radius away.
        assertEquals(0f, CoverageGlowRenderer.edgeAlpha(0f, 0f, 0f, 0.5f), 0f)
        // COMPLEMENT_OUTLINED: never below the wash.
        assertEquals(0.2f, CoverageGlowRenderer.edgeAlpha(0f, 0f, 0.2f, 0.5f), 0f)
        assertEquals(0.5f, CoverageGlowRenderer.edgeAlpha(0f, 8f, 0.2f, 0.5f), 1e-6f)
    }

    @Test
    fun `the view feeds each fill mode the regions it draws from`() {
        val map = com.hereliesaz.sphereslam.PhotosphereMap(
            sectorCount = 4, viewableHalfAngleDeg = 90f, elevationBandCount = 1, viewableElevationHalfAngleDeg = 30f,
        )
        map.markUpdated(0f, 0f, nowMs = 1L) // anchors and makes one tile current
        val current = map.currentRegions()
        val needing = map.regionsNeedingUpdate()
        assertEquals(1, current.size)
        assertEquals(3, needing.size)
        assertEquals(needing, CoverageGlowView.regionsFor(map, CoverageGlowRenderer.FillMode.TRIANGLES))
        for (mode in listOf(
            CoverageGlowRenderer.FillMode.COMPLEMENT,
            CoverageGlowRenderer.FillMode.OUTLINE,
            CoverageGlowRenderer.FillMode.COMPLEMENT_OUTLINED,
        )) {
            assertEquals(current, CoverageGlowView.regionsFor(map, mode))
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

    @Test
    fun `scan progress is grouped into opacity steps and finished or untouched tiles are left out`() {
        val a = SphereCoverage.TileRegion(0f, 15f, 0f, 30f)
        val b = SphereCoverage.TileRegion(15f, 30f, 0f, 30f)
        val c = SphereCoverage.TileRegion(30f, 45f, 0f, 30f)
        val levels = CoverageGlowRenderer.progressLevels(
            listOf(
                PhotosphereMap.RegionProgress(a, 0.05f), // started: shows the first step
                PhotosphereMap.RegionProgress(b, 0.42f),
                PhotosphereMap.RegionProgress(c, 0.48f),
                PhotosphereMap.RegionProgress(a, 0f), // untouched
                PhotosphereMap.RegionProgress(b, 1f), // scanned: opens, not drawn
            ),
        )
        assertEquals(listOf(listOf(a) to 0.1f, listOf(b, c) to 0.5f), levels)
    }

    @Test
    fun `a tile grows from the wash toward progressAlpha as its scan progresses`() {
        val haze = 0.2f
        val peak = 0.85f
        fun total(p: Float): Float {
            val e = CoverageGlowRenderer.progressOverlayAlpha(CoverageGlowRenderer.FillMode.COMPLEMENT, haze, peak, p)
            return haze + e * (1f - haze) // premultiplied overlay on the wash
        }
        assertEquals(haze, total(0f), 1e-6f)
        assertEquals(haze + (peak - haze) * 0.5f, total(0.5f), 1e-6f)
        assertEquals(peak, total(1f), 1e-6f)
        assertTrue(total(0.3f) < total(0.6f))
        // OUTLINE has no wash: the tile itself carries the progress.
        assertEquals(peak * 0.5f, CoverageGlowRenderer.progressOverlayAlpha(CoverageGlowRenderer.FillMode.OUTLINE, haze, peak, 0.5f), 1e-6f)
        // A peak below the wash draws nothing extra.
        assertEquals(0f, CoverageGlowRenderer.progressOverlayAlpha(CoverageGlowRenderer.FillMode.TRIANGLES, 0.9f, 0.5f, 0.7f), 0f)
    }

    @Test
    fun `progressAlpha is validated and region progress is accepted from any thread`() {
        val r = CoverageGlowRenderer()
        assertEquals(CoverageGlowRenderer.DEFAULT_PROGRESS_ALPHA, r.progressAlpha, 0f)
        assertThrows(IllegalArgumentException::class.java) { r.progressAlpha = 1.5f }
        r.setRegionProgress(listOf(PhotosphereMap.RegionProgress(regions[0], 0.5f)))
        r.setRegionProgress(emptyList())
    }
}

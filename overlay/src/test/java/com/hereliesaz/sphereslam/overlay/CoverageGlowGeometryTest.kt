package com.hereliesaz.sphereslam.overlay

import com.hereliesaz.sphereslam.CoverageGlowProjection
import com.hereliesaz.sphereslam.SphereCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageGlowGeometryTest {

    private fun mark(x: Float, y: Float, onScreen: Boolean) =
        CoverageGlowProjection.GlowMark(SphereCoverage.Direction(0f, 0f), x, y, onScreen)

    @Test
    fun `empty marks pack to empty`() {
        assertTrue(CoverageGlowGeometry.buildVertexData(emptyList()).isEmpty())
    }

    @Test
    fun `each mark becomes three floats x y intensity`() {
        val data = CoverageGlowGeometry.buildVertexData(
            marks = listOf(mark(0.1f, -0.2f, onScreen = true)),
            onScreenIntensity = 1f,
            offScreenIntensity = 0.45f,
        )
        assertEquals(3, data.size)
        assertEquals(0.1f, data[0], 0f)
        assertEquals(-0.2f, data[1], 0f)
        assertEquals(1f, data[2], 0f)
    }

    @Test
    fun `off-screen marks carry the fainter intensity`() {
        val data = CoverageGlowGeometry.buildVertexData(
            marks = listOf(mark(0f, 0f, onScreen = true), mark(1f, 0f, onScreen = false)),
            onScreenIntensity = 1f,
            offScreenIntensity = 0.4f,
        )
        assertEquals(6, data.size)
        assertEquals(1f, data[2], 0f)   // first, on-screen
        assertEquals(0.4f, data[5], 0f) // second, off-screen
    }

    @Test
    fun `point size is clamped to the driver range`() {
        assertEquals(64f, CoverageGlowGeometry.clampPointSize(220f, 1f, 64f), 0f)
        assertEquals(32f, CoverageGlowGeometry.clampPointSize(32f, 1f, 64f), 0f)
        assertEquals(2f, CoverageGlowGeometry.clampPointSize(0.5f, 2f, 64f), 0f)
        assertEquals(1f, CoverageGlowGeometry.clampPointSize(0.5f, 0f, 64f), 0f)
    }

    @Test
    fun `an unqueried or invalid range leaves the request unclamped`() {
        assertEquals(220f, CoverageGlowGeometry.clampPointSize(220f, 0f, 0f), 0f)
        assertEquals(220f, CoverageGlowGeometry.clampPointSize(220f, 10f, 5f), 0f)
        assertEquals(220f, CoverageGlowGeometry.clampPointSize(220f, Float.NaN, 64f), 0f)
    }
}

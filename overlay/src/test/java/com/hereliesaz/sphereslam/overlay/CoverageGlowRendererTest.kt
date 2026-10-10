package com.hereliesaz.sphereslam.overlay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The GL-free contract of [CoverageGlowRenderer]. Unit tests run against the Android stub jar (GL
 * calls return defaults), so drawing paths are exercised only for "no resources -> no-op".
 */
class CoverageGlowRendererTest {

    @Test
    fun `defaults keep the haze behaviour`() {
        val r = CoverageGlowRenderer()
        assertEquals(CoverageGlowRenderer.FillMode.TRIANGLES, r.fillMode)
        assertArrayEquals(CoverageGlowRenderer.HOT_PINK, r.hazeColor, 0f)
    }

    @Test
    fun `embedded draw without GL resources is a no-op in both modes`() {
        val r = CoverageGlowRenderer(CoverageGlowRenderer.WHITE, 0.5f)
        r.setTriangles(floatArrayOf(-1f, -1f, 1f, -1f, 0f, 1f))
        r.drawEmbedded()
        r.fillMode = CoverageGlowRenderer.FillMode.COMPLEMENT
        r.drawEmbedded()
    }

    @Test
    fun `triangles must be whole`() {
        assertThrows(IllegalArgumentException::class.java) {
            CoverageGlowRenderer().setTriangles(FloatArray(5))
        }
    }
}

package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGateTest {

    private fun point(parallaxDeg: Float, rmsPx: Float, views: Int = 2) =
        TileTriangulator.Triangulation(
            point = floatArrayOf(0f, 0f, 1f),
            reprojectionRmsPx = rmsPx,
            minParallaxDeg = parallaxDeg,
            views = views,
        )

    @Test
    fun `a point is admitted only when parallax, rms, and views all pass`() {
        val gate = TileGate(minParallaxDeg = 2f, maxReprojectionRmsPx = 2f, minViews = 2, minPoints = 1)
        assertTrue(gate.admitsPoint(point(parallaxDeg = 5f, rmsPx = 1f, views = 3)))
        assertFalse("thin parallax", gate.admitsPoint(point(parallaxDeg = 1f, rmsPx = 1f)))
        assertFalse("high rms", gate.admitsPoint(point(parallaxDeg = 5f, rmsPx = 3f)))
        assertFalse("too few views", gate.admitsPoint(point(parallaxDeg = 5f, rmsPx = 1f, views = 1)))
    }

    @Test
    fun `admittedPoints filters and preserves order`() {
        val gate = TileGate(minParallaxDeg = 2f, maxReprojectionRmsPx = 2f, minPoints = 1)
        val pts = listOf(
            point(5f, 1f),   // keep
            point(1f, 1f),   // drop (parallax)
            point(5f, 0.5f), // keep
        )
        val kept = gate.admittedPoints(pts)
        assertEquals(2, kept.size)
        assertEquals(0.5f, kept[1].reprojectionRmsPx, 0f)
    }

    @Test
    fun `a tile needs minPoints admitted points`() {
        val gate = TileGate(minParallaxDeg = 2f, maxReprojectionRmsPx = 2f, minPoints = 2)
        assertFalse(gate.admitsTile(listOf(point(5f, 1f))))
        assertTrue(gate.admitsTile(listOf(point(5f, 1f), point(4f, 1f), point(1f, 1f))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `minViews below two is rejected`() {
        TileGate(minViews = 1)
    }
}

package com.hereliesaz.sphereslam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compass-anchored angular-coverage math behind the guided-sweep hint and the directional glow.
 * Deterministic, so it is fully unit-tested here even though the sweep UX it feeds is device-only.
 */
class SphereCoverageTest {

    @Test
    fun `empty coverage is zero and unanchored`() {
        val c = SphereCoverage()
        assertEquals(0f, c.coverageFraction(), 0f)
        assertFalse(c.hasWallHeading())
        assertEquals(0, c.thinHeadingsDegrees().size)
        assertTrue(c.thinDirections().isEmpty())
    }

    @Test
    fun `first observation auto-anchors the wall heading`() {
        val c = SphereCoverage()
        assertTrue(c.observe(200f))
        assertTrue(c.hasWallHeading())
    }

    @Test
    fun `a fresh viewable sector grows coverage, a repeat does not`() {
        val c = SphereCoverage(sectorCount = 12, viewableHalfAngleDeg = 85f)
        c.setWallHeading(100f)
        assertTrue(c.observe(100f))
        assertFalse(c.observe(100f))
        assertEquals(1f / 12f, c.coverageFraction(), 1e-6f)
        assertEquals(2, c.observationCount)
        assertEquals(11, c.thinHeadingsDegrees().size)
    }

    @Test
    fun `headings behind the wall are outside the viewable arc and ignored`() {
        val c = SphereCoverage(viewableHalfAngleDeg = 85f)
        c.setWallHeading(0f)
        assertFalse(c.observe(180f))
        assertFalse(c.observe(120f))
        assertEquals(0, c.observationCount)
        assertEquals(0f, c.coverageFraction(), 0f)
    }

    @Test
    fun `sweeping the whole viewable arc reaches full coverage`() {
        val sectors = 12
        val half = 85f
        val c = SphereCoverage(sectorCount = sectors, viewableHalfAngleDeg = half)
        c.setWallHeading(90f)
        val step = (2f * half) / sectors
        for (s in 0 until sectors) {
            val delta = -half + (s + 0.5f) * step
            c.observe(SphereCoverage.norm360(90f + delta))
        }
        assertEquals(1f, c.coverageFraction(), 1e-6f)
        assertEquals(0, c.thinHeadingsDegrees().size)
    }

    @Test
    fun `viewable arc wraps across the 0-360 seam`() {
        val c = SphereCoverage(viewableHalfAngleDeg = 85f)
        c.setWallHeading(350f)
        assertTrue(c.observe(10f))
        assertTrue(c.observe(330f))
        assertTrue(c.coverageFraction() > 0f)
    }

    @Test
    fun `non-finite observations are ignored`() {
        val c = SphereCoverage()
        c.setWallHeading(0f)
        assertFalse(c.observe(Float.NaN))
        assertFalse(c.observe(0f, Float.NaN))
        assertEquals(0, c.observationCount)
    }

    @Test
    fun `reset clears coverage and the anchor`() {
        val c = SphereCoverage()
        c.observe(45f)
        c.reset()
        assertEquals(0f, c.coverageFraction(), 0f)
        assertFalse(c.hasWallHeading())
    }

    // ---- 2-D (azimuth x elevation) coverage ----

    @Test
    fun `default single band preserves azimuth-only fraction`() {
        val c = SphereCoverage(sectorCount = 12, viewableHalfAngleDeg = 85f)
        c.setWallHeading(100f)
        assertTrue(c.observe(100f)) // elevation defaults to the horizon
        assertEquals(1f / 12f, c.coverageFraction(), 1e-6f)
    }

    @Test
    fun `elevation bands multiply the bin count`() {
        val c = SphereCoverage(
            sectorCount = 12,
            viewableHalfAngleDeg = 85f,
            elevationBandCount = 3,
            viewableElevationHalfAngleDeg = 60f,
        )
        c.setWallHeading(0f)
        assertTrue(c.observe(0f, 0f))
        assertEquals(1f / 36f, c.coverageFraction(), 1e-6f)
        assertTrue("looking up hits a new band", c.observe(0f, 45f))
        assertEquals(2f / 36f, c.coverageFraction(), 1e-6f)
        assertFalse(c.observe(0f, 45f))
    }

    @Test
    fun `elevation beyond the viewable band is ignored`() {
        val c = SphereCoverage(elevationBandCount = 3, viewableElevationHalfAngleDeg = 60f)
        c.setWallHeading(0f)
        assertFalse(c.observe(0f, 80f))
        assertFalse(c.observe(0f, -80f))
        assertEquals(0, c.observationCount)
    }

    @Test
    fun `thinDirections enumerates every empty azimuth-elevation bin`() {
        val c = SphereCoverage(
            sectorCount = 4,
            viewableHalfAngleDeg = 80f,
            elevationBandCount = 2,
            viewableElevationHalfAngleDeg = 40f,
        )
        c.setWallHeading(0f)
        assertEquals(8, c.thinDirections().size)
        c.observe(0f, 20f)
        val thin = c.thinDirections()
        assertEquals(7, thin.size)
        assertTrue(thin.all { it.elevationDeg == 20f || it.elevationDeg == -20f })
    }

    @Test
    fun `fromHeadingLog folds parallel elevations`() {
        val c = SphereCoverage.fromHeadingLog(
            headingsDeg = floatArrayOf(0f, 0f),
            wallHeadingDeg = 0f,
            elevationsDeg = floatArrayOf(-30f, 30f),
            elevationBandCount = 2,
            viewableElevationHalfAngleDeg = 45f,
        )
        assertEquals(2, c.observationCount)
        assertEquals(2f / (SphereCoverage.DEFAULT_SECTORS * 2), c.coverageFraction(), 1e-6f)
    }
}

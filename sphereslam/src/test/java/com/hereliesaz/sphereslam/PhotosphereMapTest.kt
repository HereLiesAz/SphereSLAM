package com.hereliesaz.sphereslam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotosphereMapTest {

    private fun fullRing() = PhotosphereMap(
        sectorCount = 4,
        viewableHalfAngleDeg = 180f,
        elevationBandCount = 3,
        viewableElevationHalfAngleDeg = 60f,
    )

    @Test
    fun `every tile starts needing an update`() {
        val m = fullRing()
        assertEquals(12, m.tileCount)
        assertEquals(0f, m.coverageFraction(), 0f)
        assertEquals(12, m.tilesNeedingUpdate().size)
        assertFalse(m.hasWallHeading())
    }

    @Test
    fun `markUpdated anchors, clears the tile, and grows coverage`() {
        val m = fullRing()
        val id = m.markUpdated(headingDeg = 0f, elevationDeg = 0f, nowMs = 100L)
        assertNotNull(id)
        assertTrue(m.hasWallHeading())
        assertFalse(m.needsUpdate(id!!))
        assertEquals(1f / 12f, m.coverageFraction(), 1e-6f)
        val tile = m.tile(id)!!
        assertEquals(100L, tile.lastUpdatedMs)
        assertFalse(tile.needsUpdate)
    }

    @Test
    fun `a direction outside the viewable arc maps to no tile`() {
        val m = PhotosphereMap(sectorCount = 4, viewableHalfAngleDeg = 85f)
        m.setWallHeading(0f)
        assertNull(m.markUpdated(headingDeg = 170f))
        assertNull(m.tileAt(headingDeg = 170f))
    }

    @Test
    fun `tileAt is null until anchored`() {
        val m = fullRing()
        assertNull(m.tileAt(0f, 0f))
    }

    @Test
    fun `recordStale re-flags the tile and its neighbors`() {
        val m = fullRing()
        m.setWallHeading(0f)
        // Head-on at (0 deg, 0 deg) lands in tile (sector 2, band 1) for this 4x3 full-ring grid.
        val a = m.markUpdated(0f, 0f, nowMs = 1L)!!
        assertEquals(TileId(2, 1), a)
        val neighbors = listOf(TileId(1, 1), TileId(3, 1), TileId(2, 0), TileId(2, 2))
        neighbors.forEach { m.markUpdated(it, nowMs = 1L) }
        // a + its 4 neighbors are now fresh.
        assertEquals(5f / 12f, m.coverageFraction(), 1e-6f)

        m.recordStale(a)

        assertTrue(m.needsUpdate(a))
        neighbors.forEach { assertTrue("neighbor $it should need update", m.needsUpdate(it)) }
        assertEquals(0f, m.coverageFraction(), 0f)
    }

    @Test
    fun `directionsNeedingUpdate is empty until anchored and excludes fresh tiles`() {
        val m = fullRing()
        assertTrue(m.directionsNeedingUpdate().isEmpty())
        val id = m.markUpdated(0f, 0f, nowMs = 1L)!!
        val freshCenter = m.tile(id)!!.center
        val dirs = m.directionsNeedingUpdate()
        assertEquals(11, dirs.size)
        assertFalse(dirs.any { it == freshCenter })
    }

    @Test
    fun `reset returns every tile to needing an update and drops the anchor`() {
        val m = fullRing()
        m.markUpdated(0f, 0f, nowMs = 1L)
        m.reset()
        assertEquals(0f, m.coverageFraction(), 0f)
        assertFalse(m.hasWallHeading())
        assertEquals(12, m.tilesNeedingUpdate().size)
    }

    @Test
    fun `tilesInView returns the candidates within the view cone`() {
        val m = fullRing()
        assertTrue("unanchored yields no candidates", m.tilesInView(0f, 0f, 90f, 90f).isEmpty())
        m.setWallHeading(0f)
        val inView = m.tilesInView(headingDeg = 0f, elevationDeg = 0f, hFovDeg = 90f, vFovDeg = 90f)
        // Sectors 1 and 2 fall within ±45°; all three bands within ±45° of the horizon.
        assertEquals(6, inView.size)
        assertTrue(inView.contains(TileId(2, 1)))
        assertTrue(inView.contains(TileId(1, 0)))
        assertFalse(inView.contains(TileId(0, 1)))
    }

    @Test
    fun `tilesInView rejects a non-positive field of view`() {
        val m = fullRing()
        m.setWallHeading(0f)
        assertTrue(m.tilesInView(0f, 0f, 0f, 90f).isEmpty())
        assertTrue(m.tilesInView(0f, 0f, 90f, -1f).isEmpty())
    }

    @Test
    fun `an out-of-range id is ignored, not crashing`() {
        val m = fullRing()
        m.markUpdated(TileId(99, 99), nowMs = 1L)
        m.markNeedsUpdate(TileId(-1, 0))
        m.recordStale(TileId(99, 99))
        assertFalse(m.needsUpdate(TileId(99, 99)))
        assertNull(m.tile(TileId(99, 99)))
    }
}

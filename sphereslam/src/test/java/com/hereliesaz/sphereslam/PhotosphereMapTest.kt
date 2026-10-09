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
    fun `expireOlderThan flips only fresh tiles updated before the cutoff`() {
        val m = fullRing()
        val id = m.markUpdated(0f, 0f, nowMs = 100L)!!
        assertFalse(m.needsUpdate(id))
        assertEquals(0, m.expireOlderThan(50L)) // 100 is not older than 50
        assertFalse(m.needsUpdate(id))
        assertEquals(1, m.expireOlderThan(150L)) // 100 is older than 150
        assertTrue(m.needsUpdate(id))
    }

    @Test
    fun `a tile carries its optional depth and orientation tags`() {
        val m = fullRing()
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        val id = m.markUpdated(0f, 0f, nowMs = 1L, representativeOrientation = q, rangeMeters = 2.5f)!!
        val tile = m.tile(id)!!
        assertEquals(2.5f, tile.rangeMeters!!, 0f)
        assertTrue(q.contentEquals(tile.representativeOrientation))
        // A tile updated without a range reports none.
        m.markUpdated(TileId(0, 0), nowMs = 1L)
        assertNull(m.tile(TileId(0, 0))!!.rangeMeters)
    }

    @Test
    fun `snapshot round-trips the full map state`() {
        val m = fullRing()
        m.markUpdated(0f, 0f, nowMs = 7L, representativeOrientation = floatArrayOf(0f, 0f, 0f, 1f), rangeMeters = 3f)
        m.markUpdated(TileId(0, 0), nowMs = 9L)
        m.recordStale(TileId(2, 1))

        val restored = PhotosphereMap.fromSnapshot(m.snapshot())

        assertEquals(m.coverageFraction(), restored.coverageFraction(), 0f)
        assertEquals(m.tilesNeedingUpdate().toSet(), restored.tilesNeedingUpdate().toSet())
        assertEquals(m.tile(TileId(0, 0)), restored.tile(TileId(0, 0)))
        assertEquals(m.tile(TileId(2, 1)), restored.tile(TileId(2, 1)))
        assertTrue(restored.hasWallHeading())
    }

    @Test
    fun `snapshot encodes absent ranges without NaN and restores absence`() {
        val m = fullRing()
        m.markUpdated(TileId(0, 0), rangeMeters = 2.5f)
        m.markUpdated(TileId(1, 0))

        val snapshot = m.snapshot()

        assertTrue(snapshot.rangeMeters.all { it.isFinite() })
        assertTrue(snapshot.rangePresent.any { it })
        assertTrue(snapshot.rangePresent.any { !it })

        val restored = PhotosphereMap.fromSnapshot(snapshot)
        assertEquals(2.5f, restored.tile(TileId(0, 0))!!.rangeMeters!!, 0f)
        assertNull(restored.tile(TileId(1, 0))!!.rangeMeters)
    }


    @Test
    fun `tile and snapshot arrays are defensive copies`() {
        val m = fullRing()
        val orientation = floatArrayOf(0f, 0f, 0f, 1f)
        val id = m.markUpdated(0f, 0f, representativeOrientation = orientation)!!
        orientation[3] = 0f

        val tileOrientation = m.tile(id)!!.representativeOrientation!!
        tileOrientation[0] = 99f
        assertEquals(1f, m.tile(id)!!.representativeOrientation!![3], 0f)
        assertEquals(0f, m.tile(id)!!.representativeOrientation!![0], 0f)

        val snapshot = m.snapshot()
        val needsUpdate = snapshot.needsUpdate
        needsUpdate.fill(false)
        assertTrue(snapshot.needsUpdate.any { it })
    }

    @Test
    fun `fromSnapshot rejects a wrong-length array`() {
        val m = fullRing()
        val bad = m.snapshot().copy(lastUpdatedMs = LongArray(3))
        try {
            PhotosphereMap.fromSnapshot(bad)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `relockSeeds is empty before anything is scanned`() {
        val m = fullRing()
        assertTrue(m.relockSeeds(10).isEmpty())
    }

    @Test
    fun `relockSeeds includes default-timestamp captures even after they become stale`() {
        val m = fullRing()
        val id = TileId(0, 0)
        m.markUpdated(id) // public default nowMs = 0L
        m.markNeedsUpdate(id)

        assertEquals(listOf(id), m.relockSeeds(10))

        val restored = PhotosphereMap.fromSnapshot(m.snapshot())
        assertEquals(listOf(id), restored.relockSeeds(10))
    }

    @Test
    fun `relockSeeds returns only scanned tiles, most recent first`() {
        val m = fullRing()
        m.markUpdated(TileId(0, 1), nowMs = 100L)
        m.markUpdated(TileId(2, 2), nowMs = 300L)
        m.markUpdated(TileId(3, 0), nowMs = 200L)
        val seeds = m.relockSeeds(10)
        assertTrue(seeds.all { m.tile(it)!!.lastUpdatedMs > 0L })
        assertEquals(TileId(2, 2), seeds.first())
    }

    @Test
    fun `relockSeeds visits a recent tile's scanned neighbors before older recognitions`() {
        val m = fullRing() // 4 sectors (full ring, wraps), 3 bands
        m.markUpdated(TileId(1, 2), nowMs = 100L) // oldest, spatially far
        m.markUpdated(TileId(0, 0), nowMs = 400L) // most recent
        m.markUpdated(TileId(0, 1), nowMs = 300L) // scanned neighbor of (0,0)
        val seeds = m.relockSeeds(10)
        assertEquals(TileId(0, 0), seeds[0])
        assertTrue(seeds.indexOf(TileId(0, 1)) < seeds.indexOf(TileId(1, 2)))
    }

    @Test
    fun `relockSeeds honors the limit and a non-positive limit yields empty`() {
        val m = fullRing()
        m.markUpdated(TileId(0, 0), nowMs = 100L)
        m.markUpdated(TileId(1, 0), nowMs = 200L)
        m.markUpdated(TileId(2, 0), nowMs = 300L)
        assertEquals(2, m.relockSeeds(2).size)
        assertTrue(m.relockSeeds(0).isEmpty())
        assertTrue(m.relockSeeds(-1).isEmpty())
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

    @Test
    fun `an out-of-band first sample does not anchor the map`() {
        val m = fullRing()
        // Pointing at the floor (below the 60 deg elevation arc) must not anchor.
        assertNull(m.markUpdated(headingDeg = 200f, elevationDeg = -80f))
        assertFalse(m.hasWallHeading())
        val id = m.markUpdated(headingDeg = 30f, elevationDeg = 0f)
        assertNotNull(id)
        assertTrue(m.hasWallHeading())
        // Anchored at 30 deg (not 200): 4 sectors of 90 deg, the sample sits at delta 0 -> sector 2,
        // whose center is anchor + 45 = 75 deg.
        assertEquals(TileId(2, 1), id)
        assertEquals(75f, m.tile(id!!)!!.center.azimuthDeg, 1e-4f)
    }

    @Test
    fun `fromSnapshot rejects a non-finite wall heading and normalizes a finite one`() {
        val m = fullRing()
        m.markUpdated(headingDeg = 10f)
        for (badHeading in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            try {
                PhotosphereMap.fromSnapshot(m.snapshot().copy(wallHeadingDeg = badHeading))
                throw AssertionError("expected IllegalArgumentException for $badHeading")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        val unanchored = PhotosphereMap.fromSnapshot(m.snapshot().copy(wallHeadingDeg = null))
        assertFalse(unanchored.hasWallHeading())
        val wrapped = PhotosphereMap.fromSnapshot(m.snapshot().copy(wallHeadingDeg = 370f))
        assertEquals(10f, wrapped.snapshot().wallHeadingDeg!!, 1e-4f)
    }

    @Test
    fun `legacy snapshot with mismatched arrays is rejected, not crashed, when scanned is inferred`() {
        // Omitting `scanned` runs the default inference; a short needsUpdate must not throw
        // ArrayIndexOutOfBounds from the constructor, and fromSnapshot must reject it cleanly.
        val snapshot = PhotosphereMapSnapshot(
            sectorCount = 4,
            viewableHalfAngleDeg = 180f,
            elevationBandCount = 3,
            viewableElevationHalfAngleDeg = 60f,
            wallHeadingDeg = 0f,
            needsUpdate = BooleanArray(2) { true },
            lastUpdatedMs = LongArray(12),
            orientations = arrayOfNulls(12),
            rangeMeters = FloatArray(12) { Float.NaN },
        )
        try {
            PhotosphereMap.fromSnapshot(snapshot)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `legacy scanned inference keeps a stale t=0 capture that carries a tag`() {
        val n = 12
        val orientations = arrayOfNulls<FloatArray>(n).also { it[0] = floatArrayOf(0f, 0f, 0f, 1f) }
        val ranges = FloatArray(n) { Float.NaN }.also { it[1] = 2.5f }
        val snapshot = PhotosphereMapSnapshot(
            sectorCount = 4,
            viewableHalfAngleDeg = 180f,
            elevationBandCount = 3,
            viewableElevationHalfAngleDeg = 60f,
            wallHeadingDeg = 0f,
            needsUpdate = BooleanArray(n) { true }, // every tile stale
            lastUpdatedMs = LongArray(n), // every timestamp 0L
            orientations = orientations,
            rangeMeters = ranges,
        )
        val scanned = snapshot.scanned
        assertTrue("orientation-tagged t=0 tile was scanned", scanned[0])
        assertTrue("range-tagged t=0 tile was scanned", scanned[1])
        assertFalse("untagged stale t=0 tile reads as never scanned", scanned[2])
        val restored = PhotosphereMap.fromSnapshot(snapshot)
        assertTrue(restored.hasBeenScanned(TileId(0, 0)))
        assertTrue(restored.hasBeenScanned(TileId(0, 1)))
        assertFalse(restored.hasBeenScanned(TileId(0, 2)))
    }
}

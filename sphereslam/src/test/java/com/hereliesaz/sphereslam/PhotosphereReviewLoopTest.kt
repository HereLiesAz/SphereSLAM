package com.hereliesaz.sphereslam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotosphereReviewLoopTest {

    private fun anchoredMap() = PhotosphereMap(
        sectorCount = 4,
        viewableHalfAngleDeg = 180f,
        elevationBandCount = 3,
        viewableElevationHalfAngleDeg = 60f,
    ).apply { setWallHeading(0f) }

    private fun loop(map: PhotosphereMap, ttlMs: Long = 0L) = PhotosphereReviewLoop(
        map,
        PhotosphereReviewLoop.ReviewConfig(hFovDeg = 90f, vFovDeg = 90f, ttlMs = ttlMs),
    )

    @Test
    fun `candidates are the in-view tiles`() {
        val m = anchoredMap()
        val c = loop(m).candidates(0f, 0f)
        assertEquals(6, c.size)
        assertTrue(c.contains(TileId(2, 1)))
    }

    @Test
    fun `a confirmed tile becomes current`() {
        val m = anchoredMap()
        val out = loop(m).onFrame(0f, 0f, nowMs = 10L, confirmed = listOf(TileId(2, 1)))
        assertFalse(m.needsUpdate(TileId(2, 1)))
        assertEquals(1f / 12f, out.coverageFraction, 1e-6f)
        assertFalse(out.directionsNeedingUpdate.any { it == m.tile(TileId(2, 1))!!.center })
    }

    @Test
    fun `a current tile that stops matching goes stale with its neighbors`() {
        val m = anchoredMap()
        val l = loop(m)
        l.onFrame(0f, 0f, nowMs = 10L, confirmed = listOf(TileId(2, 1)))
        l.onFrame(0f, 0f, nowMs = 20L, checkedButUnmatched = listOf(TileId(2, 1)))
        assertTrue(m.needsUpdate(TileId(2, 1)))
        // Propagated to edge-adjacent neighbors.
        assertTrue(m.needsUpdate(TileId(1, 1)))
        assertTrue(m.needsUpdate(TileId(2, 0)))
    }

    @Test
    fun `a never-scanned miss does not spread to neighbors`() {
        val m = anchoredMap()
        val l = loop(m)
        // Make a neighbor of (0,0) current, then fail to match the never-scanned (0,0).
        m.markUpdated(TileId(1, 0), nowMs = 5L)
        assertFalse(m.needsUpdate(TileId(1, 0)))
        l.onFrame(0f, 0f, nowMs = 10L, checkedButUnmatched = listOf(TileId(0, 0)))
        // The fresh neighbor must stay fresh — no propagation from a never-scanned tile.
        assertFalse(m.needsUpdate(TileId(1, 0)))
    }

    @Test
    fun `adjacent unmatched fresh tiles both propagate from the pre-mutation snapshot`() {
        val m = anchoredMap()
        val l = loop(m)
        val a = TileId(1, 1)
        val b = TileId(2, 1)
        val aUniqueNeighbor = TileId(0, 1)
        val bUniqueNeighbor = TileId(3, 1)
        listOf(a, b, aUniqueNeighbor, bUniqueNeighbor).forEach { m.markUpdated(it, nowMs = 5L) }

        l.onFrame(0f, 0f, nowMs = 10L, checkedButUnmatched = listOf(a, b))

        assertTrue(m.needsUpdate(aUniqueNeighbor))
        assertTrue(m.needsUpdate(bUniqueNeighbor))
    }

    @Test
    fun `zero timestamp capture is still eligible to propagate a later miss`() {
        val m = anchoredMap()
        val l = loop(m)
        val id = TileId(1, 1)
        val neighbor = TileId(0, 1)
        m.markUpdated(id)
        m.markUpdated(neighbor, nowMs = 5L)

        l.onFrame(0f, 0f, nowMs = 10L, checkedButUnmatched = listOf(id))

        assertTrue(m.needsUpdate(id))
        assertTrue(m.needsUpdate(neighbor))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `infinite field of view is rejected`() {
        PhotosphereReviewLoop.ReviewConfig(hFovDeg = Float.POSITIVE_INFINITY)
    }

    @Test
    fun `TTL ages a capture back to needing an update`() {
        val m = anchoredMap()
        val l = loop(m, ttlMs = 100L)
        l.onFrame(0f, 0f, nowMs = 10L, confirmed = listOf(TileId(2, 1)))
        assertFalse(m.needsUpdate(TileId(2, 1)))
        // 190 ms later (> ttl) the capture expires on the next frame.
        l.onFrame(0f, 0f, nowMs = 200L)
        assertTrue(m.needsUpdate(TileId(2, 1)))
    }
}

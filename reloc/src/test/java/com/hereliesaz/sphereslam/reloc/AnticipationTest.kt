package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AnticipationResolution], its throttled subsets and the governor that picks them. */
class AnticipationTest {

    private val max = AnticipationResolution(distance = 3, latitude = 8, longitude = 3)

    private fun sites(r: AnticipationResolution) = buildList {
        for (s in 0 until r.distance) {
            add(ReferencePreviews.Site(s, 0, 0))
            for (c in 1..r.longitude) for (m in 0 until r.latitude) add(ReferencePreviews.Site(s, c, m))
        }
    }

    @Test
    fun `views are distance times the pole plus every cut`() {
        assertEquals(3 * (1 + 8 * 3), max.views)
        assertEquals(0, AnticipationResolution.NONE.views)
        assertEquals(max.views, sites(max).size)
    }

    @Test
    fun `throttled subsets are nested, from full to none`() {
        val chain = AnticipationDecimation.chain(max)
        assertEquals(AnticipationDecimation.FULL, chain.first())
        assertEquals(AnticipationDecimation.NONE, chain.last())
        assertEquals(max, chain.first().of(max))
        val all = sites(max)
        for (i in 1 until chain.size) {
            val kept = all.filter { chain[i].keeps(it, max) }
            // Each a subset of the one before, with fewer views, and the count it reports.
            assertTrue(kept.all { chain[i - 1].keeps(it, max) })
            assertTrue(kept.size < all.count { chain[i - 1].keeps(it, max) })
            assertEquals(chain[i].toString(), chain[i].of(max).views, kept.size)
        }
        // The middle half-sphere's pole is the last view standing.
        val last = chain[chain.size - 2]
        assertEquals(listOf(ReferencePreviews.Site(1, 0, 0)), all.filter { last.keeps(it, max) })
    }

    @Test
    fun `a factor picks the largest subset that fits`() {
        assertEquals(AnticipationDecimation.FULL, AnticipationDecimation.forFactor(max, 1f))
        assertEquals(AnticipationDecimation.NONE, AnticipationDecimation.forFactor(max, 0f))
        var previous = Int.MAX_VALUE
        for (f in listOf(0.9f, 0.6f, 0.4f, 0.2f, 0.05f)) {
            val views = AnticipationDecimation.forFactor(max, f).of(max).views
            assertTrue("$f: $views", views <= f * max.views && views <= previous)
            previous = views
        }
    }

    private class Source(var pressure: DevicePressure = DevicePressure.NONE) : DevicePressureSource {
        override fun read() = pressure
    }

    @Test
    fun `heat throttles at once, relief waits, and the session's own load counts`() {
        val source = Source()
        val g = AnticipationGovernor(max, AnticipationThrottle(battery = 0.5f, heat = 1f, compute = 1f), source)
        assertEquals(AnticipationDecimation.FULL, g.update(0).decimation)
        // Hot: cut to nothing at the next reading.
        source.pressure = DevicePressure(battery = 0f, heat = 1f)
        assertEquals(AnticipationDecimation.FULL, g.update(1_000).decimation) // not yet re-read
        assertEquals(AnticipationDecimation.NONE, g.update(2_000).decimation)
        assertEquals(1f, g.status.heatPressure)
        // Cooled: held for 10 s first.
        source.pressure = DevicePressure.NONE
        assertEquals(AnticipationDecimation.NONE, g.update(4_000).decimation)
        assertEquals(AnticipationDecimation.NONE, g.update(12_000).decimation)
        assertEquals(AnticipationDecimation.FULL, g.update(14_000).decimation)
        // Half-empty battery at weight ½: a quarter cut.
        source.pressure = DevicePressure(battery = 0.5f, heat = 0f)
        val s = g.update(16_000)
        assertEquals(0.75f, s.factor, 1e-6f)
        assertTrue(s.effective.views <= 0.75f * max.views)
        // Frames taking their whole interval: full compute pressure.
        var t = 0L
        repeat(60) { g.onFrame(33f, t); t += 33_000_000L }
        source.pressure = DevicePressure.NONE
        val busy = g.update(18_000)
        assertTrue("${busy.computePressure}", busy.computePressure > 0.9f)
        assertEquals(AnticipationDecimation.NONE, busy.decimation)
        // A new maximum applies at once; throttle off ignores every pressure.
        g.throttle = AnticipationThrottle.OFF
        g.max = AnticipationResolution(1, 4, 1)
        assertEquals(AnticipationResolution(1, 4, 1), g.update(18_500).max)
        assertEquals(AnticipationDecimation.FULL, g.update(30_000).decimation)
    }
}

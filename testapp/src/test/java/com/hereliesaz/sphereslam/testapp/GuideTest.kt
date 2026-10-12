package com.hereliesaz.sphereslam.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTest {

    private fun sample(t: Long, state: String, source: String? = null, method: String? = null) =
        Sample(t, state, source, method, 10f, 50, 0f, 0f, 0f, 0f, 0f)

    @Test
    fun `every check belongs to exactly one step`() {
        val inSteps = Guide.STEPS.flatMap { it.checks }
        assertEquals(inSteps.size, inSteps.toSet().size)
        assertEquals(Checklist().checks.map { it.id }.toSet(), inSteps.toSet())
        // Capture steps name the surface whose lock they grade.
        for (s in Guide.STEPS.filter { it.kind == StepKind.CAPTURE }) assertTrue(s.surface!!.checkId in s.checks)
    }

    @Test
    fun `a step is done once none of its checks is untested, and fails if any failed`() {
        val c = Checklist()
        val g = Guide()
        val device = g.steps[0]
        assertEquals(Status.UNTESTED, g.statusOf(device, c))
        c.onLayout(true, "ok")
        c.onAttitude(available = true, reliable = true)
        assertEquals(Status.UNTESTED, g.statusOf(device, c))
        c.onLens(null, 1.5f, "no correction")
        assertEquals(Status.PASS, g.statusOf(device, c))
        c.onLayout(false, "overlap")
        assertEquals(Status.FAIL, g.statusOf(device, c))
    }

    @Test
    fun `skip marks the step's undecided checks and moves on, retry clears them`() {
        val c = Checklist()
        val g = Guide()
        g.goTo(2, 0L)
        g.skip(c, 10L)
        assertEquals(3, g.index)
        assertEquals(Status.SKIPPED, c["match_speed"].status)
        assertEquals(Status.SKIPPED, g.statusOf(g.steps[2], c))
        g.goTo(2, 20L)
        g.retry(c, 30L)
        assertEquals(Status.UNTESTED, c["match_speed"].status)
        assertEquals("", c["match_speed"].evidence)
        assertEquals(30L, g.startedMs)
    }

    @Test
    fun `retrying match speed starts its sample count over`() {
        val c = Checklist()
        repeat(Checklist.MATCH_SAMPLES) { c.observe(sample(it.toLong(), "LOCKED", "KPM", "KPM_FULL").copy(matchMs = 90f)) }
        assertEquals(Status.FAIL, c["match_speed"].status)
        c.reset(listOf("match_speed"))
        repeat(Checklist.MATCH_SAMPLES - 1) { c.observe(sample(1_000L + it, "LOCKED", "KPM", "KPM_FULL").copy(matchMs = 5f)) }
        assertEquals(Status.UNTESTED, c["match_speed"].status)
        c.observe(sample(5_000L, "LOCKED", "KPM", "KPM_FULL").copy(matchMs = 5f))
        assertEquals(Status.PASS, c["match_speed"].status)
    }

    @Test
    fun `the capture surface gates on elevation and says which way to tilt`() {
        assertNull(Surface.WALL.aimHint(5f))
        assertEquals("Tilt down 20° to face the wall", Surface.WALL.aimHint(50f))
        assertEquals("Tilt up 10° to face the wall", Surface.WALL.aimHint(-40f))
        assertNull(Surface.FLOOR.aimHint(-88f))
        assertEquals("Tilt down 30° more to face the floor", Surface.FLOOR.aimHint(-30f))
        assertNull(Surface.CEILING.aimHint(70f))
        assertTrue(Surface.CEILING.aimHint(null)!!.contains("rotation sensor"))
    }

    @Test
    fun `a capture grades the surface it was taken for, whatever the elevation`() {
        val c = Checklist()
        c.onCapture(0L, 10f, "lock_ceiling")
        assertEquals("Locking… 2 s of 30 s (INITIALIZING)", run { c.observe(sample(2_000L, "INITIALIZING")); c.progress("lock_ceiling", 2_000L) })
        c.observe(sample(3_000L, "LOCKED", "KPM", "KPM_FULL"))
        assertEquals(Status.PASS, c["lock_ceiling"].status)
        assertEquals(Status.UNTESTED, c["lock_wall"].status)
        assertNull(c.progress("lock_ceiling", 3_000L))
    }

    @Test
    fun `the one-minute step unlocks its verdict only after a minute away from the target`() {
        val c = Checklist()
        val g = Guide()
        val step = g.steps.first { it.awayMs == 60_000L }
        assertTrue(g.manualWait(step, c, 0L)!!.startsWith("Lock a target"))
        c.observe(sample(1_000L, "LOCKED", "KPM", "KPM_FULL"))
        assertEquals("Turn away from the target to start the timer", g.manualWait(step, c, 1_500L))
        assertEquals("Keep looking away: 45 s left", g.manualWait(step, c, 16_000L))
        assertEquals("Keep looking away: 59 s left", g.manualWait(step, c, 2_000L))
        assertEquals("1:05", Guide.formatSeconds(64_001L))
        assertNull(g.manualWait(step, c, 61_000L))
        // Steps without a wait are ready at once.
        assertNull(g.manualWait(g.steps.first { it.kind == StepKind.MANUAL && it.awayMs == 0L }, c, 0L))
    }

    @Test
    fun `hints appear only after a step has stalled`() {
        val c = Checklist()
        val g = Guide()
        g.goTo(2, 0L)
        assertFalse(g.showHint(c, Guide.HINT_AFTER_MS - 1))
        assertTrue(g.showHint(c, Guide.HINT_AFTER_MS))
        c.set("match_speed", Status.PASS, "x"); c.set("flow_used", Status.PASS, "x")
        assertFalse(g.showHint(c, Guide.HINT_AFTER_MS))
    }

    @Test
    fun `finishing walks past the last step`() {
        val g = Guide()
        g.goTo(g.steps.size - 1, 0L)
        assertFalse(g.finished)
        g.next(1L)
        assertTrue(g.finished)
        assertNull(g.current)
    }
}

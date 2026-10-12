package com.hereliesaz.sphereslam.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistTest {

    private fun sample(
        t: Long,
        state: String = "INITIALIZING",
        source: String? = null,
        method: String? = null,
        matchMs: Float? = null,
        elevation: Float? = 0f,
        heading: Float? = 0f,
        facing: Float? = 0f,
        rebuild: Float? = 0f,
        step: Float? = 0f,
    ) = Sample(t, state, source, method, matchMs, 50, elevation, heading, facing, rebuild, step)

    @Test
    fun `a floor capture that locks passes the floor check, not the wall one`() {
        val c = Checklist()
        c.onCapture(1_000L, -82f)
        c.observe(sample(3_000L, "LOCKED", "KPM", "KPM_FULL"))
        assertEquals(Status.PASS, c["lock_floor"].status)
        assertEquals(Status.UNTESTED, c["lock_wall"].status)
        assertTrue(c["lock_floor"].evidence.contains("2000 ms"))
    }

    @Test
    fun `no lock within 30 s fails the capture's check`() {
        val c = Checklist()
        c.onCapture(0L, 5f)
        c.observe(sample(31_000L, "INITIALIZING"))
        assertEquals(Status.FAIL, c["lock_wall"].status)
    }

    @Test
    fun `match speed grades the median of KPM poses`() {
        val fast = Checklist()
        repeat(Checklist.MATCH_SAMPLES) { fast.observe(sample(it.toLong(), "LOCKED", "KPM", "FLOW", matchMs = 12f)) }
        assertEquals(Status.PASS, fast["match_speed"].status)
        assertEquals(Status.PASS, fast["flow_used"].status)
        val slow = Checklist()
        repeat(Checklist.MATCH_SAMPLES) { slow.observe(sample(it.toLong(), "LOCKED", "KPM", "KPM_FULL", matchMs = 60f)) }
        assertEquals(Status.FAIL, slow["match_speed"].status)
    }

    @Test
    fun `bridge, off-wall and relock-after-away follow the state sequence`() {
        val c = Checklist()
        c.observe(sample(0L, "LOCKED", "KPM", "KPM_FULL"))
        c.observe(sample(100L, "IMU_BRIDGE", "IMU_BRIDGE"))
        c.observe(sample(300L, "LOCKED", "KPM", "KPM_FULL"))
        assertEquals(Status.PASS, c["bridge"].status)
        c.observe(sample(2_000L, "REACQUIRING", "PHOTOSPHERE"))
        assertEquals(Status.PASS, c["off_wall"].status)
        c.observe(sample(15_000L, "LOCKED", "KPM", "KPM_FULL"))
        assertEquals(Status.PASS, c["relock_after_away"].status)
    }

    @Test
    fun `pole attitude passes on small rebuild errors and fails on a large one`() {
        val good = Checklist()
        repeat(Checklist.POLE_SAMPLES) { good.observe(sample(it.toLong(), elevation = -89f, rebuild = 0.01f)) }
        assertEquals(Status.PASS, good["pole_attitude"].status)
        val bad = Checklist()
        bad.observe(sample(0L, elevation = 88f, rebuild = 5f))
        assertEquals(Status.FAIL, bad["pole_attitude"].status)
        // Away from the poles nothing is graded.
        val wall = Checklist()
        repeat(100) { wall.observe(sample(it.toLong(), elevation = 0f, rebuild = 9f)) }
        assertEquals(Status.UNTESTED, wall["pole_attitude"].status)
    }

    @Test
    fun `facing over a floor is graded on still samples, across the 0-360 wrap`() {
        val c = Checklist()
        // Axis heading swings wildly; facing wobbles around north.
        for (i in 0..30) {
            c.observe(sample(i * 100L, elevation = -89.5f, heading = (i * 47f) % 360f, facing = if (i % 2 == 0) 358f else 2f, step = 0.1f))
        }
        assertEquals(Status.PASS, c["pole_facing"].status)
        assertTrue(c["pole_facing"].evidence, Regex("^facing spread 2\\.\\d°").containsMatchIn(c["pole_facing"].evidence))
        // A moving phone never accumulates a window.
        val moving = Checklist()
        for (i in 0..30) moving.observe(sample(i * 100L, elevation = -89.5f, facing = i * 10f, step = 3f))
        assertEquals(Status.UNTESTED, moving["pole_facing"].status)
    }

    @Test
    fun `tile fixes and events pass their checks and a tester verdict overrides`() {
        val c = Checklist()
        c.onDiagnostic("SphereSLAM standalone KPM … tiles{stations=1 active=1 tiles=4 anchored=2 landmarks=0 depthPoints=0 rotFixes=0 poseFixes=0}")
        assertEquals(Status.UNTESTED, c["tile_fixes"].status)
        c.onDiagnostic("… tiles{stations=2 active=1 tiles=23 anchored=5 landmarks=412 depthPoints=3180 rotFixes=17 poseFixes=4}")
        assertEquals(Status.PASS, c["tile_fixes"].status)
        c.onMapSolved(); c.onAtlasPage(1)
        assertEquals(Status.PASS, c["map_solved"].status)
        assertEquals(Status.PASS, c["atlas_growth"].status)
        val before = c.changes
        c.set("outline_on_target", Status.FAIL, "tester: fail")
        assertEquals(Status.FAIL, c["outline_on_target"].status)
        assertEquals(before + 1, c.changes)
    }

    @Test
    fun `report JSON escapes and carries every check`() {
        val c = Checklist()
        c.set("lens_correction", Status.PASS, "correction=FAST \"quoted\"\nline")
        val info = RunInfo("run-1234-5678", "0.26.3-2-g8c2ae09", "0.26.3", "1", "com.hereliesaz.sphereslam.testapp", "Google", "Pixel", "16", 36, "t0")
        val json = ReportJson.build(info, c.checks, "t1", "", "a\tb")
        assertTrue(json.contains("\"evidence\":\"correction=FAST \\\"quoted\\\"\\nline\""))
        assertTrue(json.contains("\"log\":\"a\\tb\""))
        assertEquals(c.checks.size, Regex("\"status\":").findAll(json).count())
    }
}

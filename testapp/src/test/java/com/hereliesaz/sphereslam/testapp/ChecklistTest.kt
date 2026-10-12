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
        c.onCapture(1_000L, -82f, "lock_floor")
        c.observe(sample(3_000L, "LOCKED", "KPM", "KPM_FULL"))
        assertEquals(Status.PASS, c["lock_floor"].status)
        assertEquals(Status.UNTESTED, c["lock_wall"].status)
        assertTrue(c["lock_floor"].evidence.contains("2000 ms"))
    }

    @Test
    fun `no lock within 30 s fails the capture's check`() {
        val c = Checklist()
        c.onCapture(0L, 5f, "lock_wall")
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

    @Test
    fun `off-wall needs the target out of view, not a relock still confirming`() {
        val c = Checklist()
        c.observe(sample(0L, "LOCKED", "KPM", "KPM_FULL"))
        // A photosphere pose while relocking, the target only just gone: not off the wall yet.
        c.observe(sample(500L, "REACQUIRING", "PHOTOSPHERE"))
        c.observe(sample(1_000L, "LOCKED", "PHOTOSPHERE"))
        assertEquals(Status.UNTESTED, c["off_wall"].status)
        c.observe(sample(1_600L, "REACQUIRING", "HELD"))
        assertEquals(Status.PASS, c["off_wall"].status)
    }

    @Test
    fun `a new capture starts the lock history over`() {
        val c = Checklist()
        c.observe(sample(0L, "LOCKED", "KPM", "KPM_FULL"))
        c.onCapture(20_000L, 0f, "lock_wall")
        // First lock on the new target: not a relock after being away.
        c.observe(sample(21_000L, "LOCKED", "KPM", "KPM_FULL"))
        assertEquals(Status.UNTESTED, c["relock_after_away"].status)
        assertEquals(Status.PASS, c["lock_wall"].status)
    }

    @Test
    fun `match speed stops collecting once graded and counts locked frames only`() {
        val c = Checklist()
        repeat(50) { c.observe(sample(it.toLong(), "REACQUIRING", "KPM", "KPM_FULL", matchMs = 90f)) }
        assertTrue(c.progress("match_speed", 0L)!!.startsWith("0 of"))
        repeat(Checklist.MATCH_SAMPLES) { c.observe(sample(100L + it, "LOCKED", "KPM", "FLOW", matchMs = 8f).copy(frameMs = 50f)) }
        // Matching fits, the whole frame doesn't.
        assertEquals(Status.FAIL, c["match_speed"].status)
        assertTrue(c["match_speed"].evidence, c["match_speed"].evidence.contains("whole frame p50 50.0 ms"))
    }

    @Test
    fun `tile localization reported disabled fails its check`() {
        val c = Checklist()
        c.onDiagnostic("… tiles{stations=0 active=0 tiles=0 anchored=0 landmarks=0 depthPoints=0 rotFixes=0 poseFixes=0 disabled}")
        assertEquals(Status.FAIL, c["tile_fixes"].status)
    }

    @Test
    fun `the attitude sensor passes only once it reports medium accuracy or better`() {
        val c = Checklist()
        c.onAttitude(true, null)
        c.onAttitude(true, 1)
        assertEquals(Status.UNTESTED, c["attitude_sensor"].status)
        assertTrue(c.progress("attitude_sensor", 0L)!!.contains("figure-8"))
        c.onAttitude(true, 2)
        assertEquals(Status.PASS, c["attitude_sensor"].status)
        val none = Checklist()
        none.onAttitude(false, null)
        assertEquals(Status.FAIL, none["attitude_sensor"].status)
    }

    @Test
    fun `the session check passes after a clean first capture and fails on a missing library`() {
        val ok = Checklist()
        ok.onCapture(0L, 0f, "lock_wall")
        ok.observe(sample(1_000L))
        assertEquals(Status.UNTESTED, ok["native_session"].status)
        ok.observe(sample(2_500L))
        assertEquals(Status.PASS, ok["native_session"].status)
        val broken = Checklist()
        broken.onCapture(0L, 0f, "lock_wall")
        broken.onFailure("NATIVE_LIBRARY_UNAVAILABLE", "dlopen failed")
        broken.observe(sample(3_000L))
        assertEquals(Status.FAIL, broken["native_session"].status)
    }

    @Test
    fun `a timeout fails only what is still undecided`() {
        val c = Checklist()
        c.set("bridge", Status.PASS, "bridged")
        c.timeOut("bridge", "not seen")
        c.timeOut("rectified", "not seen in 120 s")
        assertEquals(Status.PASS, c["bridge"].status)
        assertEquals(Status.FAIL, c["rectified"].status)
    }

    @Test
    fun `stillness is a rate, so a fast frame rate can't fake it`() {
        val c = Checklist()
        // 0.4° per 10 ms frame is 40°/s: moving, however small each step.
        for (i in 0..400) c.observe(sample(i * 10L, elevation = -89.5f, facing = 0f, step = 0.4f))
        assertEquals(Status.UNTESTED, c["pole_facing"].status)
    }
}

package com.hereliesaz.sphereslam.testapp

import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.reloc.CameraTimestampSource
import com.hereliesaz.sphereslam.reloc.KpmMatchSpeedConfig
import com.hereliesaz.sphereslam.reloc.PhotosphereTrackingSession
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.RandomAccessFile
import kotlin.math.cos
import kotlin.math.sin

/** The recording format, the measures, the knobs and the sweep bookkeeping (org.json needs Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TuningTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun frame(ts: Long, seed: Int, attitude: Boolean = true) = FrameRecord(
        timestampNs = ts,
        width = 64,
        height = 48,
        intrinsics = floatArrayOf(50f, 50f, 32f, 24f),
        cameraFromDevice = FloatArray(9) { if (it % 4 == 0) 1f else 0f },
        attitude = if (attitude) AttitudeSample(floatArrayOf(0f, 0f, 0f, 1f), ts - 1000, 3) else null,
        headingDeg = 12f, elevationDeg = null, rollDeg = -3f,
        convertNs = 1_500_000L,
        luma = ByteArray(64 * 48) { ((it * seed) % 251).toByte() },
    )

    private val header = RecordingHeader("0.26.4-20-gabc", "Google Pixel 5", "0", true, "2026-10-12T17:00:00Z", mapOf("oblique.rectifyDeg" to 25f))

    @Test
    fun `a recording reads back frame for frame, with its events in order`() {
        val file = tmp.newFile("a.sslrec")
        val w = RecordingWriter(file, header)
        val frames = (0 until 20).map { frame(1_000_000_000L + it * 33_333_333L, it + 1, attitude = it % 3 != 0) }
        val crop = CaptureRecord(frames[2].timestampNs, "lock_wall", 8, ByteArray(64) { it.toByte() })
        frames.forEachIndexed { i, f ->
            if (i == 2) w.event(RecordEvent.Capture(crop))
            if (i == 10) { w.event(RecordEvent.Knobs(f.timestampNs, mapOf("accept.age" to 400f))); w.event(RecordEvent.Restart(f.timestampNs, Replayer.RESTART_KNOBS)) }
            w.frame(f)
            Thread.sleep(1) // let the writer keep up: nothing dropped
        }
        w.stop()
        val r = RecordingReader.open(file)!!
        assertEquals("0.26.4-20-gabc", r.header.build)
        assertEquals(25f, r.header.knobs["oblique.rectifyDeg"])
        val index = r.index()
        assertEquals(20, index.frames.size)
        assertEquals((frames.last().timestampNs - frames.first().timestampNs) / 1_000_000L, index.durationMs)
        val back = r.frame(index.frames[7])
        assertEquals(frames[7].timestampNs, back.timestampNs)
        assertArrayEquals(frames[7].luma, back.luma)
        assertArrayEquals(frames[7].intrinsics, back.intrinsics, 0f)
        assertEquals(12f, back.headingDeg)
        assertNull(back.elevationDeg)
        assertEquals(-3f, back.rollDeg)
        assertNotNull(back.attitude)
        assertNull(r.frame(index.frames[9]).attitude)
        val events = index.events
        assertEquals(3, events.size)
        val c = events[0] as RecordEvent.Capture
        assertEquals("lock_wall", c.capture.checkId)
        assertArrayEquals(crop.crop, c.capture.crop)
        assertEquals(400f, (events[1] as RecordEvent.Knobs).values["accept.age"])
        assertEquals(Replayer.RESTART_KNOBS, (events[2] as RecordEvent.Restart).reason)
        r.close()
    }

    @Test
    fun `a recording cut short mid-frame keeps every whole frame`() {
        val file = tmp.newFile("b.sslrec")
        val w = RecordingWriter(file, header)
        repeat(5) { w.frame(frame(it * 33_000_000L, it + 2)); Thread.sleep(1) }
        w.stop()
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 100) }
        val r = RecordingReader.open(file)!!
        assertEquals(4, r.index().frames.size)
        r.close()
        assertNull(RecordingReader.open(tmp.newFile("not-a-recording").apply { writeText("hello") }))
    }

    @Test
    fun `the writer stops taking frames at its duration limit`() {
        val w = RecordingWriter(tmp.newFile("c.sslrec"), header, maxDurationMs = 100)
        assertTrue(w.frame(frame(0, 1)))
        assertTrue(w.frame(frame(50_000_000L, 1)))
        assertTrue(!w.frame(frame(150_000_000L, 1)))
        assertTrue(w.full)
        w.stop()
    }

    @Test
    fun `the replay takes the newest frame that has arrived, skipping the rest`() {
        val frames = (0 until 10).map { RecordingReader.FrameIndex(it * 33L, it.toLong()) }
        assertEquals(0, Replayer.latestArrived(frames, 0, 0))
        assertEquals(0, Replayer.latestArrived(frames, 0, 32))
        assertEquals(3, Replayer.latestArrived(frames, 0, 100)) // a 100 ms frame: three arrived meanwhile
        assertEquals(5, Replayer.latestArrived(frames, 5, 120)) // never before the frame it starts from
        assertEquals(9, Replayer.latestArrived(frames, 4, 10_000))
    }

    // --- measures -------------------------------------------------------------------------------

    /** A view (camera-from-fingerprint) looking at the target from [angleDeg] off its normal, [dist] widths away. */
    private fun view(angleDeg: Double, dist: Float = 2f, dx: Float = 0f): FloatArray {
        val a = Math.toRadians(angleDeg)
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        // Rotation about y; column-major; translation puts the target dist ahead (camera looks down −z).
        return floatArrayOf(c, 0f, s, 0f, 0f, 1f, 0f, 0f, -s, 0f, c, 0f, dx, 0f, -dist, 1f)
    }

    private fun obs(t: Long, visual: Boolean, angle: Double, still: Boolean = false, dx: Float = 0f, frameMs: Float = 20f) =
        FrameObservation(t, if (visual) "LOCKED" else "REACQUIRING", if (visual) "KPM" else "PHOTOSPHERE", null, 40, 1f, 10f, frameMs, view(angle, dx = dx), still)

    @Test
    fun `nothing is measured before the first capture`() {
        val m = RunMetrics()
        m.observe(obs(0, true, 0.0))
        m.onFailure("NO_CURRENT_PAGE_MATCH")
        assertEquals(0, m.summary().frames)
        assertNull(m.summary().lockedPct)
    }

    @Test
    fun `locked time, by viewing angle, and the steepest lock`() {
        val m = RunMetrics().apply { start() }
        // 2 s locked at 10°, then 2 s at 50° of which the first second is locked.
        var t = 0L
        repeat(60) { m.observe(obs(t, true, 10.0)); t += 33 }
        repeat(30) { m.observe(obs(t, true, 50.0)); t += 33 }
        repeat(30) { m.observe(obs(t, false, 50.0)); t += 33 }
        val s = m.summary()
        assertEquals(75f, s.lockedPct!!, 1.5f)
        assertEquals(100f, s.bandLockedPct[0]!!, 0.1f)
        assertEquals(50f, s.bandLockedPct[3]!!, 3f)
        assertNull(s.bandLockedPct[4]) // no time at 60°+
        assertEquals(50f, s.steepestLockDeg, 0.1f)
        assertEquals(50f, s.steepLockedPct!!, 3f)
        assertEquals(1, s.relocks + if (s.unresolvedLossMs != null) 1 else 0)
        assertNotNull(s.unresolvedLossMs)
    }

    @Test
    fun `a relock is timed, and the estimate shown before it is compared with it`() {
        val m = RunMetrics().apply { start() }
        m.observe(obs(0, true, 0.0))
        m.observe(obs(33, true, 0.0))
        // Lost for 500 ms; the estimate drifted half a width sideways.
        for (t in 66L..533L step 33) m.observe(obs(t, false, 0.0, dx = 0.5f))
        m.observe(obs(566, true, 0.0))
        // A blink under the loss threshold is not a relock.
        m.observe(obs(599, false, 0.0))
        m.observe(obs(632, true, 0.0))
        val s = m.summary()
        assertEquals(1, s.relocks)
        assertEquals(533f, s.relockMedianMs!!, 1f)
        assertEquals(0.5f, s.relockErrorWidths!!, 0.01f)
        assertEquals(0f, s.relockErrorDeg!!, 0.5f)
        assertNull(s.unresolvedLossMs)
    }

    @Test
    fun `jitter while still, and jumps between consecutive poses`() {
        val m = RunMetrics().apply { start() }
        var t = 0L
        repeat(20) { i -> m.observe(obs(t, true, 0.0, still = true, dx = if (i % 2 == 0) 0.01f else -0.01f)); t += 33 }
        m.observe(obs(t, true, 0.0, dx = 0.6f)); t += 33 // jumps 0.6 widths in one frame
        val s = m.summary()
        assertEquals(1f, s.jitterPct!!, 0.05f)
        assertEquals(1, s.jumps)
        assertEquals(20f, s.frameMsP50!!, 0f)
        assertTrue(s.timeline.isNotEmpty())
        assertTrue(s.describe(), s.describe().startsWith("locked 100%"))
    }

    @Test
    fun `measures survive a round trip through the saved sweep`() {
        val m = RunMetrics().apply { start() }
        var t = 0L
        repeat(40) { m.observe(obs(t, it < 30, 35.0)); t += 33 }
        m.onFailure("STALE_OBSERVATION")
        val s = m.summary()
        val back = SummaryJson.fromJson(JSONObject(SummaryJson.toJson(s).toString()))
        assertEquals(s.describe(), back.describe())
        assertEquals(s.timeline.size, back.timeline.size)
        val saved = SavedSweep(mapOf("accept.age" to 250f), mapOf("accept.age" to listOf(250f, 400f)), listOf(SweepResult(mapOf("accept.age" to 400f), s)), "now")
        val again = SavedSweep.fromJson(JSONObject(saved.toJson().toString()))
        assertEquals(listOf(250f, 400f), again.axes["accept.age"])
        assertEquals(1, again.results.single().summary.failures["STALE_OBSERVATION"])
    }

    // --- knobs and sweeps -----------------------------------------------------------------------

    @Test
    fun `knob defaults are the library's, and values reach the config`() {
        val defaults = Knobs.config(Knobs.DEFAULTS, CameraTimestampSource.REALTIME)
        assertEquals(PhotosphereTrackingSession.Config(timestampSource = CameraTimestampSource.REALTIME), defaults)
        val c = Knobs.config(mapOf("oblique.rectifyDeg" to 22f, "match.flow" to 0f, "accept.age" to 410f, "session.bridge" to 600f), CameraTimestampSource.UNKNOWN)
        assertEquals(20f, c.matchSpeed.obliqueRectificationDeg) // snapped to its 5° step
        assertEquals(false, c.matchSpeed.flowTracking)
        assertEquals(400f, c.observationAge.maxRealtimeAgeMs) // snapped to 25 ms
        assertEquals(600L, c.maxBridgeMs)
        assertEquals(KpmMatchSpeedConfig().obliqueSearchAfterMisses.toFloat(), Knobs["oblique.searchAfter"]!!.default)
        assertEquals("library defaults", Knobs.describe(Knobs.DEFAULTS))
        assertEquals("Rectify from 20°", Knobs.describe(Knobs.DEFAULTS + ("oblique.rectifyDeg" to 20f)))
    }

    @Test
    fun `a sweep is every combination on top of the base, baseline first, without repeats`() {
        val base = Knobs.DEFAULTS
        val sweep = Sweep(base, linkedMapOf("oblique.rectifyDeg" to listOf(20f, 30f, 40f), "accept.age" to listOf(250f, 500f)))
        // 6 combinations; one equals the base (30°, 250 ms) and is not run twice.
        assertEquals(6, sweep.configs.size)
        assertEquals(base, sweep.configs.first())
        assertTrue(sweep.configs.any { it["oblique.rectifyDeg"] == 40f && it["accept.age"] == 500f })
    }

    @Test
    fun `results rank by the chosen measure and report as tables`() {
        fun summary(locked: Float, frame: Float) = RunMetrics.Summary(
            100, 0, 10f, locked, List(5) { locked }, List(5) { 2f }, 40f, 1, 500f, 500f, null, 0.1f, 2f, 1f, 0, frame, frame, 10f, 10f, 1f, mapOf("POSE_JUMP" to 2),
        )
        val base = Knobs.DEFAULTS
        val a = SweepResult(base, summary(50f, 20f))
        val b = SweepResult(base + ("oblique.rectifyDeg" to 20f), summary(70f, 40f))
        assertEquals(listOf(b, a), Ranking.LOCKED.sort(listOf(a, b)))
        assertEquals(listOf(a, b), Ranking.FRAME.sort(listOf(a, b)))
        assertEquals("baseline", a.label(base))
        assertEquals("Rectify from 20°", b.label(base))
        val tables = SweepReport.tables(listOf(a, b), base, Ranking.LOCKED)
        assertEquals(3, tables.size)
        assertEquals("Rectify from 20°", tables[0].rows[0][1])
        assertEquals(tables[0].columns.size, tables[0].rows[0].size)
        assertEquals(listOf("#", "Configuration", "POSE_JUMP"), tables[2].columns)
        val json = ReportJson.build(
            RunInfo("run-12345678", "b", "v", "a", "p", "B", "M", "16", 36, "t"), emptyList(), "t", "notes", "", tables,
        )
        assertTrue(json.contains("\"tables\":[{\"title\":\"Configurations, by locked %\""))
    }
}

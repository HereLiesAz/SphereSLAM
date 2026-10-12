package com.hereliesaz.sphereslam.testapp

import android.os.SystemClock
import com.hereliesaz.sphereslam.reloc.CameraTimestampSource
import com.hereliesaz.sphereslam.reloc.PhotosphereTrackingSession
import java.util.Locale

/**
 * Runs the library over a recording as if it were the camera, on this phone, so two configurations
 * can be compared on identical input.
 *
 * Time is the recording's: a virtual clock starts at the first frame's timestamp and advances by
 * what each frame really costs here (the recorded packing time plus the session's work). Frames
 * that arrived while the session was busy are skipped, as CameraX skips them live (the latest frame
 * is taken), so a slower configuration sees fewer frames, exactly as it would on the camera. The
 * session reads the same clock, so its age checks, bridge windows and cadences hold.
 *
 * Events replay in place: captures before the frame they were taken on; restarts start a fresh
 * session (one restarted for new knob values keeps its target, as live). With [followRecordedKnobs]
 * the knobs change where they changed live; otherwise [knobs] hold throughout.
 */
class Replayer(
    private val reader: RecordingReader,
    private val index: RecordingReader.Index,
    private val knobs: Map<String, Float>,
    private val followRecordedKnobs: Boolean = false,
    private val cancelled: () -> Boolean = { false },
    private val onProgress: (Float) -> Unit = {},
) {
    private val timestampSource =
        if (reader.header.realtimeTimestamps) CameraTimestampSource.REALTIME else CameraTimestampSource.UNKNOWN

    // The virtual clock: vBase at realBase, running at real speed while the session works.
    @Volatile private var vBase = 0L
    @Volatile private var realBase = 0L
    private fun clock(): Long = vBase + (SystemClock.elapsedRealtimeNanos() - realBase)

    /** The measures, or null when cancelled. */
    fun run(): RunMetrics.Summary? {
        val frames = index.frames
        if (frames.isEmpty()) return RunMetrics().summary()
        val metrics = RunMetrics()
        var values = knobs
        var driver = newDriver(values, metrics)
        var lastCapture: CaptureRecord? = null
        val events = index.events
        var nextEvent = 0
        var vt = frames.first().timestampNs
        var i = 0
        try {
            while (i < frames.size) {
                if (cancelled()) return null
                if (frames[i].timestampNs > vt) vt = frames[i].timestampNs // idle until the next frame
                // The latest frame that has arrived by now; the ones before it were skipped.
                val j = latestArrived(frames, i, vt)
                metrics.onDropped(j - i)
                val ts = frames[j].timestampNs
                while (nextEvent < events.size && events[nextEvent].timestampNs <= ts) {
                    when (val e = events[nextEvent++]) {
                        is RecordEvent.Capture -> {
                            driver.capture(e.capture)
                            lastCapture = e.capture
                        }
                        is RecordEvent.Restart -> {
                            driver.close()
                            driver = newDriver(values, metrics)
                            if (e.reason == RESTART_KNOBS) lastCapture?.let { driver.capture(it) }
                        }
                        is RecordEvent.Knobs -> if (followRecordedKnobs) values = e.values
                        is RecordEvent.Gap -> Unit
                    }
                }
                val frame = reader.frame(frames[j])
                vBase = vt + frame.convertNs
                realBase = SystemClock.elapsedRealtimeNanos()
                driver.step(frame) { (frame.convertNs + SystemClock.elapsedRealtimeNanos() - realBase) / 1e6f }
                vt = clock()
                i = j + 1
                if (i % 30 == 0) onProgress(i.toFloat() / frames.size)
            }
        } finally {
            driver.close()
        }
        onProgress(1f)
        return metrics.summary(frames.last().timestampNs / 1_000_000L)
    }

    private fun newDriver(values: Map<String, Float>, metrics: RunMetrics) =
        SessionDriver(Knobs.config(values, timestampSource), ::clock, PhotosphereTrackingSession.Callbacks(), metrics)

    companion object {
        /** The index of the newest frame from [from] on that has arrived by [nowNs] (at least [from]). */
        fun latestArrived(frames: List<RecordingReader.FrameIndex>, from: Int, nowNs: Long): Int {
            var j = from
            while (j + 1 < frames.size && frames[j + 1].timestampNs <= nowNs) j++
            return j
        }

        /** [RecordEvent.Restart.reason] for a restart made to apply new knob values. */
        const val RESTART_KNOBS = "knobs"
        const val RESTART_TRACKING = "tracking"
    }
}

/** One configuration of a sweep and what it scored. */
class SweepResult(val values: Map<String, Float>, val summary: RunMetrics.Summary) {
    /** The knobs this result set differently from [base], for its row label. */
    fun label(base: Map<String, Float>): String =
        Knobs.ALL.filter { k -> (values[k.id] ?: k.default) != (base[k.id] ?: k.default) }
            .joinToString(", ") { k -> "${k.label} ${k.format(values[k.id] ?: k.default)}" }
            .ifEmpty { "baseline" }
}

/**
 * A grid of knob values over one recording: every combination of [axes] (knob id → values) on top
 * of [base], plus [base] itself first. At most [MAX_RUNS] runs.
 */
class Sweep(val base: Map<String, Float>, val axes: Map<String, List<Float>>) {
    val configs: List<Map<String, Float>> = run {
        var grid = listOf(emptyMap<String, Float>())
        for ((id, values) in axes) grid = grid.flatMap { partial -> values.map { v -> partial + (id to v) } }
        val all = listOf(base) + grid.map { base + it }
        all.distinctBy { cfg -> Knobs.ALL.map { k -> k.snap(cfg[k.id] ?: k.default) } }.take(MAX_RUNS)
    }

    companion object {
        const val MAX_RUNS = 36
    }
}

/** What a sweep's table is ordered by. */
enum class Ranking(val label: String, val better: (RunMetrics.Summary) -> Float?) {
    LOCKED("Locked %", { it.lockedPct }),
    STEEP("Locked at 45°+ %", { it.steepLockedPct }),
    STEEPEST("Steepest lock", { it.steepestLockDeg }),
    RELOCK("Fastest relock", { s -> s.relockMedianMs?.let { -it } }),
    JITTER("Least jitter", { s -> s.jitterPct?.let { -it } }),
    FRAME("Fastest frames", { s -> s.frameMsP50?.let { -it } });

    /** [results] best first; results with nothing to rank go last. */
    fun sort(results: List<SweepResult>): List<SweepResult> =
        results.sortedWith(compareByDescending<SweepResult> { better(it.summary) ?: Float.NEGATIVE_INFINITY })
}

/** A sweep's results as report tables: one row per configuration, and locked % by viewing angle. */
object SweepReport {
    fun tables(results: List<SweepResult>, base: Map<String, Float>, ranking: Ranking): List<ReportTable> {
        val sorted = ranking.sort(results)
        val main = ReportTable(
            title = "Configurations, by ${ranking.label.lowercase(Locale.ROOT)}",
            columns = listOf("#", "Configuration", "Locked %", "45°+ %", "Steepest", "Relocks", "Relock ms (med/max)", "Off at relock", "Jitter %", "Jumps", "Error px²", "Frame ms (med/p95)", "Dropped"),
            rows = sorted.mapIndexed { n, r ->
                val s = r.summary
                listOf(
                    "${n + 1}", r.label(base), RunMetrics.f0(s.lockedPct), RunMetrics.f0(s.steepLockedPct), "${RunMetrics.f0(s.steepestLockDeg)}°",
                    "${s.relocks}" + (s.unresolvedLossMs?.let { " +1 open" } ?: ""),
                    "${RunMetrics.f0(s.relockMedianMs)}/${RunMetrics.f0(s.relockMaxMs)}",
                    "${RunMetrics.f2(s.relockErrorWidths)} w / ${RunMetrics.f0(s.relockErrorDeg)}°",
                    RunMetrics.f1(s.jitterPct), "${s.jumps}", RunMetrics.f1(s.errorPx2P50),
                    "${RunMetrics.f0(s.frameMsP50)}/${RunMetrics.f0(s.frameMsP95)}", "${s.dropped}",
                )
            },
        )
        val byAngle = ReportTable(
            title = "Locked % by viewing angle (seconds spent in each band)",
            columns = listOf("#", "Configuration") + RunMetrics.BANDS.indices.map { RunMetrics.bandName(it) },
            rows = sorted.mapIndexed { n, r ->
                listOf("${n + 1}", r.label(base)) + RunMetrics.BANDS.indices.map { i ->
                    "${RunMetrics.f0(r.summary.bandLockedPct[i])} (${RunMetrics.f0(r.summary.bandSeconds[i])} s)"
                }
            },
        )
        val failures = results.flatMap { it.summary.failures.keys }.distinct()
        val failTable = if (failures.isEmpty()) null else ReportTable(
            title = "Failures by reason",
            columns = listOf("#", "Configuration") + failures,
            rows = sorted.mapIndexed { n, r -> listOf("${n + 1}", r.label(base)) + failures.map { "${r.summary.failures[it] ?: 0}" } },
        )
        return listOfNotNull(main, byAngle, failTable)
    }
}

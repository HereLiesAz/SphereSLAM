package com.hereliesaz.sphereslam.testapp

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A recording on the phone and what is known about it without reading it through. */
class RecordingInfo(
    val file: File,
    val header: RecordingHeader,
    val durationMs: Long,
    val frames: Int,
    val captures: Int,
    val droppedByWriter: Int,
) {
    val bytes: Long get() = file.length()
    val name: String get() = file.nameWithoutExtension

    fun describe(): String =
        "${Tuning.formatDuration(durationMs)} · $frames frames · ${Tuning.formatBytes(bytes)} · " +
            (if (captures == 0) "no target captured" else "$captures target${if (captures == 1) "" else "s"}") +
            (if (droppedByWriter > 0) " · $droppedByWriter frames lost while writing" else "")
}

/**
 * The tuning state of the process: the live knob values (kept across launches), the recording in
 * progress, the recordings on the phone, and the sweep running over one of them. Recordings live in
 * the app's external files (`Android/data/<package>/files/recordings`, reachable with `adb pull`).
 */
class Tuning(private val context: Context) {
    private val prefs = context.getSharedPreferences("tuning", Context.MODE_PRIVATE)

    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").apply { mkdirs() }

    /** The knob values live tracking uses; library defaults until changed. */
    var knobs: Map<String, Float> = RecordingFormat.knobsFrom(prefs.getString(KEY_KNOBS, "{}") ?: "{}")
        .filterKeys { Knobs[it] != null }
        .let { saved -> Knobs.DEFAULTS + saved }
        set(value) {
            field = value
            prefs.edit().putString(KEY_KNOBS, RecordingFormat.knobsJson(value)).apply()
        }

    @Volatile var recorder: RecordingWriter? = null
        private set

    @Volatile var job: SweepJob? = null

    /** Starts a recording (the oldest beyond [MAX_RECORDINGS] are deleted first). */
    fun startRecording(cameraId: String, realtime: Boolean): RecordingWriter {
        recordings().drop(MAX_RECORDINGS - 1).forEach { delete(it.file) }
        val stamp = FILE_STAMP.format(Instant.now())
        val header = RecordingHeader(
            build = BuildConfig.SPHERESLAM_BUILD,
            device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            cameraId = cameraId,
            realtimeTimestamps = realtime,
            startedAt = Instant.now().toString(),
            knobs = knobs,
        )
        return RecordingWriter(File(dir, "rec-$stamp.sslrec"), header).also { recorder = it }
    }

    /** Stops the recording in progress (on the caller's thread: it waits for the writer) and notes its size. */
    fun stopRecording(): File? {
        val w = recorder ?: return null
        recorder = null
        w.stop()
        val index = RecordingReader.open(w.file)?.let { r -> r.index().also { r.close() } }
        if (index == null) {
            w.file.delete()
            return null
        }
        sidecar(w.file).writeText(
            JSONObject()
                .put("durationMs", index.durationMs)
                .put("frames", index.frames.size)
                .put("captures", index.captures)
                .put("dropped", index.events.filterIsInstance<RecordEvent.Gap>().sumOf { it.frames })
                .toString(),
        )
        return w.file
    }

    /** Recordings on the phone, newest first. One cut short by a crash is indexed once to describe it. */
    fun recordings(): List<RecordingInfo> =
        (dir.listFiles { f -> f.extension == "sslrec" } ?: emptyArray())
            .filter { it != recorder?.file }
            .sortedByDescending { it.name }
            .mapNotNull { info(it) }

    fun info(file: File): RecordingInfo? {
        val reader = RecordingReader.open(file) ?: return null
        try {
            val side = sidecar(file)
            val j = if (side.exists()) runCatching { JSONObject(side.readText()) }.getOrNull() else null
            if (j != null) {
                return RecordingInfo(file, reader.header, j.optLong("durationMs"), j.optInt("frames"), j.optInt("captures"), j.optInt("dropped"))
            }
            val index = reader.index()
            return RecordingInfo(
                file, reader.header, index.durationMs, index.frames.size, index.captures,
                index.events.filterIsInstance<RecordEvent.Gap>().sumOf { it.frames },
            )
        } finally {
            reader.close()
        }
    }

    fun delete(file: File) {
        file.delete()
        sidecar(file).delete()
        resultsFile(file).delete()
    }

    private fun sidecar(file: File) = File(file.parentFile, file.nameWithoutExtension + ".json")

    fun resultsFile(file: File) = File(file.parentFile, file.nameWithoutExtension + ".results.json")

    /** The last sweep's results over [file], or null. */
    fun results(file: File): SavedSweep? = runCatching { SavedSweep.fromJson(JSONObject(resultsFile(file).readText())) }.getOrNull()

    companion object {
        const val MAX_RECORDINGS = 8
        private const val KEY_KNOBS = "knobs"
        private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

        fun formatDuration(ms: Long): String {
            val s = (ms + 500) / 1000
            return if (s >= 60) "${s / 60}:${"%02d".format(s % 60)}" else "$s s"
        }

        fun formatBytes(b: Long): String = when {
            b >= 1_000_000_000L -> "%.1f GB".format(b / 1e9)
            b >= 1_000_000L -> "%.0f MB".format(b / 1e6)
            else -> "%.0f KB".format(b / 1e3)
        }
    }
}

/** A finished sweep as saved next to its recording: its base, axes, and every configuration's measures. */
class SavedSweep(val base: Map<String, Float>, val axes: Map<String, List<Float>>, val results: List<SweepResult>, val finishedAt: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("base", JSONObject(RecordingFormat.knobsJson(base)))
        .put("axes", JSONObject().apply { axes.forEach { (k, v) -> put(k, JSONArray(v.map { it.toDouble() })) } })
        .put("finishedAt", finishedAt)
        .put("results", JSONArray(results.map { r -> JSONObject().put("values", JSONObject(RecordingFormat.knobsJson(r.values))).put("summary", SummaryJson.toJson(r.summary)) }))

    companion object {
        fun fromJson(o: JSONObject): SavedSweep {
            val axes = o.getJSONObject("axes").let { a -> a.keys().asSequence().associateWith { k -> a.getJSONArray(k).let { arr -> (0 until arr.length()).map { arr.getDouble(it).toFloat() } } } }
            val results = o.getJSONArray("results").let { arr ->
                (0 until arr.length()).map { i ->
                    val r = arr.getJSONObject(i)
                    SweepResult(RecordingFormat.knobsFrom(r.getJSONObject("values").toString()), SummaryJson.fromJson(r.getJSONObject("summary")))
                }
            }
            return SavedSweep(RecordingFormat.knobsFrom(o.getJSONObject("base").toString()), axes, results, o.optString("finishedAt"))
        }
    }
}

/** [RunMetrics.Summary] to and from JSON, for saved sweeps. */
object SummaryJson {
    private fun JSONObject.opt(name: String, v: Float?) = put(name, v?.toDouble() ?: JSONObject.NULL)
    private fun JSONObject.f(name: String): Float? = if (isNull(name) || !has(name)) null else getDouble(name).toFloat()

    fun toJson(s: RunMetrics.Summary): JSONObject = JSONObject()
        .put("frames", s.frames).put("dropped", s.dropped).put("seconds", s.seconds.toDouble())
        .opt("lockedPct", s.lockedPct)
        .put("bandLockedPct", JSONArray(s.bandLockedPct.map { it?.toDouble() ?: JSONObject.NULL }))
        .put("bandSeconds", JSONArray(s.bandSeconds.map { it.toDouble() }))
        .put("steepestLockDeg", s.steepestLockDeg.toDouble())
        .put("relocks", s.relocks)
        .opt("relockMedianMs", s.relockMedianMs).opt("relockMaxMs", s.relockMaxMs)
        .put("unresolvedLossMs", s.unresolvedLossMs ?: JSONObject.NULL)
        .opt("relockErrorWidths", s.relockErrorWidths).opt("relockErrorDeg", s.relockErrorDeg)
        .opt("jitterPct", s.jitterPct).put("jumps", s.jumps)
        .opt("frameMsP50", s.frameMsP50).opt("frameMsP95", s.frameMsP95)
        .opt("matchMsP50", s.matchMsP50).opt("matchMsP95", s.matchMsP95)
        .opt("errorPx2P50", s.errorPx2P50)
        .put("failures", JSONObject(s.failures as Map<*, *>))
        .put("timeline", JSONArray(s.timeline.flatMap { listOf(it.timeMs, it.angleDeg?.toDouble() ?: -1.0, if (it.visual) 1 else 0) }))

    fun fromJson(o: JSONObject): RunMetrics.Summary {
        val bands = o.getJSONArray("bandLockedPct")
        val bandSec = o.getJSONArray("bandSeconds")
        val fails = o.optJSONObject("failures")
        val tl = o.optJSONArray("timeline")
        return RunMetrics.Summary(
            frames = o.getInt("frames"),
            dropped = o.getInt("dropped"),
            seconds = o.getDouble("seconds").toFloat(),
            lockedPct = o.f("lockedPct"),
            bandLockedPct = (0 until bands.length()).map { if (bands.isNull(it)) null else bands.getDouble(it).toFloat() },
            bandSeconds = (0 until bandSec.length()).map { bandSec.getDouble(it).toFloat() },
            steepestLockDeg = o.getDouble("steepestLockDeg").toFloat(),
            relocks = o.getInt("relocks"),
            relockMedianMs = o.f("relockMedianMs"),
            relockMaxMs = o.f("relockMaxMs"),
            unresolvedLossMs = if (o.isNull("unresolvedLossMs")) null else o.getLong("unresolvedLossMs"),
            relockErrorWidths = o.f("relockErrorWidths"),
            relockErrorDeg = o.f("relockErrorDeg"),
            jitterPct = o.f("jitterPct"),
            jumps = o.getInt("jumps"),
            frameMsP50 = o.f("frameMsP50"),
            frameMsP95 = o.f("frameMsP95"),
            matchMsP50 = o.f("matchMsP50"),
            matchMsP95 = o.f("matchMsP95"),
            errorPx2P50 = o.f("errorPx2P50"),
            failures = fails?.keys()?.asSequence()?.associateWith { fails.getInt(it) } ?: emptyMap(),
            timeline = if (tl == null) emptyList() else (0 until tl.length() / 3).map { i ->
                RunMetrics.TimelinePoint(tl.getLong(i * 3), tl.getDouble(i * 3 + 1).toFloat().takeIf { it >= 0f }, tl.getInt(i * 3 + 2) == 1)
            },
        )
    }
}

/**
 * A sweep in the background: every configuration of [sweep] replayed over [recording] in turn, the
 * results saved next to the recording and reported to the build's issue. Process-scoped (in
 * [Tuning.job]), so leaving the screen or rotating it does not stop it; [cancel] does.
 */
class SweepJob(
    private val tuning: Tuning,
    val recording: RecordingInfo,
    val sweep: Sweep,
    private val report: (List<ReportTable>, String) -> Pair<Boolean, String>,
) {
    enum class State { RUNNING, DONE, CANCELLED, FAILED }

    @Volatile var state = State.RUNNING
        private set
    @Volatile var run = 0
        private set
    @Volatile var progress = 0f
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var reportState = "Report: sent when the sweep finishes"
        private set
    private val done = ArrayList<SweepResult>()
    @Volatile private var cancelled = false
    val startedAtMs = System.currentTimeMillis()

    val results: List<SweepResult> get() = synchronized(done) { done.toList() }

    fun start() {
        Thread({ work() }, "sphereslam-test-sweep").start()
    }

    fun cancel() {
        cancelled = true
    }

    private fun work() {
        val reader = RecordingReader.open(recording.file)
        if (reader == null) {
            fail("the recording can't be read")
            return
        }
        try {
            val index = reader.index()
            for ((n, values) in sweep.configs.withIndex()) {
                run = n
                progress = 0f
                val summary = Replayer(reader, index, values, cancelled = { cancelled }, onProgress = { progress = it }).run()
                if (summary == null || cancelled) {
                    state = State.CANCELLED
                    return
                }
                synchronized(done) { done += SweepResult(values, summary) }
            }
            val saved = SavedSweep(sweep.base, sweep.axes, results, Instant.now().toString())
            tuning.resultsFile(recording.file).writeText(saved.toJson().toString())
            state = State.DONE
            reportState = "Report: sending…"
            val tables = SweepReport.tables(results, sweep.base, Ranking.LOCKED)
            val notes = "Sweep of ${sweep.configs.size} configurations over ${recording.name} " +
                "(${recording.describe()}; recorded on build ${recording.header.build}, ${recording.header.device}). " +
                "Base: ${Knobs.describe(sweep.base)}. Varied: " +
                sweep.axes.entries.joinToString("; ") { (id, vs) -> Knobs[id]?.let { k -> "${k.label} ${vs.joinToString(", ") { k.format(it) }}" } ?: id } + "."
            var attempt = 0
            while (true) {
                val (ok, detail) = report(tables, notes)
                if (ok) { reportState = "Report sent to the build's issue"; break }
                if (++attempt >= 4) { reportState = "Report not sent ($detail); results are saved on the phone"; break }
                reportState = "Report not sent ($detail); retrying"
                Thread.sleep(Reporter.retryDelayMs(attempt))
            }
        } catch (t: Throwable) {
            fail("${t.javaClass.simpleName}: ${t.message}")
        } finally {
            reader.close()
        }
    }

    private fun fail(why: String) {
        error = why
        state = State.FAILED
    }

    companion object {
        /** The report body of a sweep: a run of its own (new id), tables instead of checks. */
        fun reportBody(tables: List<ReportTable>, notes: String, packageName: String): String = ReportJson.build(
            RunInfo(
                runId = UUID.randomUUID().toString(),
                build = BuildConfig.SPHERESLAM_BUILD,
                libraryVersion = BuildConfig.SPHERESLAM_VERSION,
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                packageName = packageName,
                brand = Build.MANUFACTURER,
                model = Build.MODEL,
                android = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                startedAt = Instant.now().toString(),
            ),
            emptyList(), Instant.now().toString(), notes, "", tables,
        )
    }
}

package com.hereliesaz.sphereslam.testapp

import java.util.Locale
import kotlin.math.abs

/*
 * The device checklist. Most checks grade themselves from live telemetry ([Sample]s and session
 * events); the visual ones ([Check.manual]) take the tester's verdict. [Checklist.progress] says how
 * far an undecided check has got, for the guided run ([Guide]). A check the telemetry can only ever
 * pass is failed by the guide when its step runs out of time ([Checklist.timeOut]). Pure Kotlin, no
 * Android, so every grading rule is unit-tested on the JVM.
 */

enum class Status { UNTESTED, PASS, FAIL, SKIPPED }

class Check(
    val id: String,
    val title: String,
    /** What the tester does to exercise it. */
    val howTo: String,
    /** True when only the tester can judge it (pass/fail buttons). */
    val manual: Boolean = false,
) {
    var status: Status = Status.UNTESTED
        internal set
    var evidence: String = ""
        internal set
}

/** One analyzed frame, as the checks see it. Angles in degrees; null when unknown. */
data class Sample(
    val timeMs: Long,
    val state: String,
    /** Pose source (`KPM`, `IMU_BRIDGE`, `HELD`, `PHOTOSPHERE`), or null without a pose. */
    val source: String?,
    /** Match method of a KPM pose (`KPM_FULL`, `KPM_FOCUSED`, `FLOW`, `KPM_RECTIFIED`), else null. */
    val method: String?,
    val matchMs: Float?,
    val inliers: Int?,
    val elevationDeg: Float?,
    val headingDeg: Float?,
    val facingDeg: Float?,
    /** Rotation between the matrix rebuilt from (heading, elevation, roll) and the sensor's, degrees. */
    val rebuildErrorDeg: Float?,
    /** How far the camera axis turned since the previous sample, degrees. */
    val axisStepDeg: Float?,
    /** Whole-frame analysis time (pack, attitude, session), ms; null when unmeasured. */
    val frameMs: Float? = null,
)

/**
 * The checks and their grading. Call [observe] per frame and the `on*` hooks for session events;
 * [changes] counts every status change, so a reporter can tell when there is something new to send.
 */
class Checklist {
    val checks: List<Check> = listOf(
        Check("layout_clear", "Every control clears the system bars and display cutout", "Automatic at start."),
        Check("native_packaged", "The APK ships libsphereslam for this device's ABI", "Automatic at start."),
        Check("attitude_sensor", "Rotation-vector sensor reports medium or high accuracy", "Open the app; wave the phone in a figure-8 if it stays low."),
        Check("lens_correction", "Camera delivers a distortion-corrected stream", "Automatic at camera start."),
        Check("lock_wall", "Locks a wall target within 30 s", "Point at a textured wall, tap Capture, keep it in view."),
        Check("native_session", "The first capture starts a native KPM session", "Automatic on the first capture."),
        Check("lock_floor", "Locks a floor target within 30 s", "Look straight down at a textured floor, tap Capture."),
        Check("lock_ceiling", "Locks a ceiling target within 30 s", "Optional: look straight up at a textured ceiling, tap Capture."),
        Check("match_speed", "Median match time and whole-frame time fit a 30 fps frame", "Stay locked on a target for ~10 s."),
        Check("flow_used", "Flow tracking runs between full solves", "Stay locked and move slowly."),
        Check("rectified", "Relocks at a grazing angle (KPM_RECTIFIED)", "While locked, walk around until you view the target 45°+ from the side."),
        Check("bridge", "Gyro bridge holds a brief miss and relocks", "While locked, cover the lens for half a second."),
        Check("off_wall", "Keeps a pose with the target out of view", "While locked, turn away from the target."),
        Check("relock_after_away", "Relocks after 10 s+ away from the target", "Look away 10 s or more, then back."),
        Check("map_solved", "Solves map_from_fingerprint on the first lock", "Automatic after the first lock."),
        Check("atlas_growth", "Grows the atlas beyond the captured page", "While locked, pan along the surface past the target's edge."),
        Check("tile_fixes", "Photosphere tiles fix the pose", "Look around the room, then back near the target."),
        Check("pole_attitude", "Heading/elevation/roll rebuild the sensor rotation at the poles", "Point straight down (and up) for a few seconds."),
        Check("pole_facing", "Facing direction is steady over a floor", "Hold the phone still, pointing straight down, for 2 s."),
        Check("outline_on_target", "Outline stays on the target edges while you move", "Watch the outline while moving around a locked target.", manual = true),
        Check("outline_at_edges", "Outline stays on the target near the screen edges", "Move so the target sits at a screen edge.", manual = true),
        Check("returns_after_minute", "After a minute away, the outline returns onto the target", "Look away 60 s or more, then back.", manual = true),
    )

    private val byId = checks.associateBy { it.id }

    /** Bumped on every status or evidence change. */
    var changes: Int = 0
        private set

    operator fun get(id: String): Check = byId.getValue(id)

    /** Sets [id]'s verdict outright (the tester's answers, skips, timeouts, the layout check). */
    fun set(id: String, status: Status, evidence: String) {
        val c = byId.getValue(id)
        if (c.status == status && c.evidence == evidence) return
        c.status = status
        c.evidence = evidence
        changes++
    }

    /** A verdict from telemetry: only an undecided check takes it, so the first one stands. */
    private fun decide(id: String, status: Status, evidence: String) {
        if (byId.getValue(id).status != Status.UNTESTED) return
        set(id, status, evidence)
    }

    /** Returns [ids] to UNTESTED and forgets what their grading had accumulated (the tester's Retry). */
    fun reset(ids: Collection<String>) {
        for (id in ids) {
            val c = byId.getValue(id)
            if (c.status != Status.UNTESTED || c.evidence.isNotEmpty()) {
                c.status = Status.UNTESTED
                c.evidence = ""
                changes++
            }
            when (id) {
                "match_speed" -> { matchTimes.clear(); frameTimes.clear() }
                "rectified" -> rectifiedCount = 0
                "pole_attitude" -> { poleSamples = 0; maxPoleError = 0f }
                "pole_facing" -> clearStill()
                "bridge" -> bridgeStartMs = Long.MIN_VALUE
                "native_session" -> sessionCheckAtMs = Long.MIN_VALUE
                "lock_wall", "lock_floor", "lock_ceiling" -> if (captureCheck == id) captureCheck = null
            }
        }
    }

    /** Fails [id] if it is still undecided: its step ran out of time without the telemetry it waits for. */
    fun timeOut(id: String, evidence: String) = decide(id, Status.FAIL, evidence)

    // --- events ---------------------------------------------------------------------------------

    /**
     * Whether the APK carries libsphereslam for the device's ABI. Deliberately not a load: loading it
     * here would hide a session path that forgets to (`native_session` catches that).
     */
    fun onNativePackaged(present: Boolean, evidence: String) =
        decide("native_packaged", if (present) Status.PASS else Status.FAIL, evidence)

    /** [clear] when no control overlaps a system bar or the display cutout; [evidence] gives the measurements. */
    fun onLayout(clear: Boolean, evidence: String) =
        set("layout_clear", if (clear) Status.PASS else Status.FAIL, evidence)

    /**
     * [accuracy] is the sensor's last reported `SENSOR_STATUS_*` value, or null before it has reported
     * one: a sensor that has said nothing yet has not shown it is reliable.
     */
    fun onAttitude(available: Boolean, accuracy: Int?) {
        if (!available) {
            decide("attitude_sensor", Status.FAIL, "no TYPE_ROTATION_VECTOR sensor")
            return
        }
        attitudeAccuracy = accuracy
        if (accuracy != null && accuracy >= ACCURACY_MEDIUM) {
            decide("attitude_sensor", Status.PASS, "accuracy ${accuracyName(accuracy)}")
        }
    }

    /** [mode] is the applied correction mode name, or null; [residualPx] the uncorrected pinhole error. */
    fun onLens(mode: String?, residualPx: Float?, description: String) {
        if (mode != null) decide("lens_correction", Status.PASS, description)
        else decide("lens_correction", Status.SKIPPED, description + (residualPx?.let { " (camera cannot correct)" } ?: ""))
    }

    private var captureMs = Long.MIN_VALUE
    private var captureCheck: String? = null
    private var captureElevation = 0f
    private var sessionCheckAtMs = Long.MIN_VALUE

    /**
     * A target was captured at [elevationDeg] for [checkId] (`lock_wall`, `lock_floor` or
     * `lock_ceiling`). The lock history starts over: a new target is not a relock of the old one.
     */
    fun onCapture(timeMs: Long, elevationDeg: Float?, checkId: String) {
        require(checkId in LOCK_CHECKS) { checkId }
        captureMs = timeMs
        captureElevation = elevationDeg ?: 0f
        captureCheck = checkId
        everLocked = false
        lastKpmMs = Long.MIN_VALUE
        bridgeStartMs = Long.MIN_VALUE
        if (sessionCheckAtMs == Long.MIN_VALUE) sessionCheckAtMs = timeMs + NATIVE_SESSION_GRACE_MS
    }

    /** A failure the session reported, by reason name (`NATIVE_LIBRARY_UNAVAILABLE` …). */
    fun onFailure(reason: String, diagnostic: String) {
        if (reason == "NATIVE_LIBRARY_UNAVAILABLE") {
            decide("native_session", Status.FAIL, "UnsatisfiedLinkError on the session path: ${diagnostic.take(160)}")
        }
    }

    // --- progress -------------------------------------------------------------------------------

    /** The latest sample's tracking state, or null before the first frame. */
    val trackingState: String? get() = lastState

    /** Milliseconds since the pending capture (one still waiting for its lock), or null when none is pending. */
    fun msSincePendingCapture(nowMs: Long): Long? = if (captureCheck == null) null else nowMs - captureMs

    /** Milliseconds since the last KPM pose (the target was last seen), or null if it never was. */
    fun msSinceTargetSeen(nowMs: Long): Long? = if (lastKpmMs == Long.MIN_VALUE) null else nowMs - lastKpmMs

    /** How far an undecided check has got, in the tester's words; null when there is nothing to show. */
    fun progress(id: String, nowMs: Long): String? {
        if (byId.getValue(id).status != Status.UNTESTED) return null
        return when (id) {
            "lock_wall", "lock_floor", "lock_ceiling" ->
                if (captureCheck == id) "Locking… ${(nowMs - captureMs) / 1000} s of ${LOCK_TIMEOUT_MS / 1000} s (${lastState ?: "starting"})"
                else "Waiting for Capture"
            "match_speed" -> "${matchTimes.size} of $MATCH_SAMPLES tracked frames" +
                (if (matchTimes.isNotEmpty()) ", median ${fmt1(median(matchTimes))} ms" else "")
            "flow_used" -> if (lastState == "LOCKED") "Locked; waiting for a flow-tracked frame" else "Waiting for a lock"
            "rectified" -> "$rectifiedCount of $RECTIFIED_POSES steep-angle matches"
            "bridge" -> when (lastState) {
                "LOCKED" -> "Locked. Cover the lens now"
                "IMU_BRIDGE" -> "Bridging on the gyro. Uncover the lens"
                else -> "Waiting for a lock (${lastState ?: "starting"})"
            }
            "off_wall" -> if (everLocked) "Waiting for a pose with the target out of view" else "Lock a target first"
            "relock_after_away" -> msSinceTargetSeen(nowMs)?.let { away ->
                if (away < AWAY_MS) "Away ${away / 1000} s of ${AWAY_MS / 1000} s" else "Away long enough. Point back at the target"
            } ?: "Lock a target first"
            "map_solved" -> "Waiting for the first lock"
            "native_session" -> "Waiting for the first capture"
            "atlas_growth" -> "Waiting for a new atlas page"
            "tile_fixes" -> "Waiting for a tile fix"
            "pole_attitude" -> "$poleSamples of $POLE_SAMPLES samples at the pole"
            "pole_facing" -> if (stillStartMs == Long.MIN_VALUE) "Hold still, pointing straight down"
                else "Still for ${fmt1((nowMs - stillStartMs) / 1000f)} s of ${STILL_WINDOW_MS / 1000} s"
            "attitude_sensor" -> when (val a = attitudeAccuracy) {
                null -> "Waiting for the sensor to report its accuracy"
                else -> "Accuracy ${accuracyName(a)}: move the phone in a slow figure-8"
            }
            "native_packaged" -> "Checking the APK"
            else -> null
        }
    }

    fun onMapSolved() = decide("map_solved", Status.PASS, "solved on the first confirmed lock")

    fun onAtlasPage(pageNo: Int) = decide("atlas_growth", Status.PASS, "page $pageNo added")

    /** A periodic session diagnostic line; picks the tile fix counters out of `tiles{…}`. */
    fun onDiagnostic(line: String) {
        val tiles = TILES_RE.find(line) ?: return
        if (tiles.groupValues[3].isNotEmpty()) {
            decide("tile_fixes", Status.FAIL, "tile localization is disabled (OpenCV not loaded, or its build failed)")
            return
        }
        val rotFixes = tiles.groupValues[1].toInt(); val poseFixes = tiles.groupValues[2].toInt()
        if (rotFixes + poseFixes > 0) decide("tile_fixes", Status.PASS, "rotFixes=$rotFixes poseFixes=$poseFixes")
    }

    // --- per frame ------------------------------------------------------------------------------

    private var lastState: String? = null
    private var lastSampleMs = Long.MIN_VALUE
    private var bridgeStartMs = Long.MIN_VALUE
    private var lastKpmMs = Long.MIN_VALUE
    private var everLocked = false
    private val matchTimes = ArrayList<Float>()
    private val frameTimes = ArrayList<Float>()
    private var rectifiedCount = 0
    private var maxPoleError = 0f
    private var poleSamples = 0
    private val stillFacing = ArrayList<Float>()
    private val stillHeading = ArrayList<Float>()
    private var stillStartMs = Long.MIN_VALUE
    private var attitudeAccuracy: Int? = null

    fun observe(s: Sample) {
        gradeLock(s)
        if (sessionCheckAtMs != Long.MIN_VALUE && s.timeMs >= sessionCheckAtMs) {
            decide("native_session", Status.PASS, "no native-library failure after the first capture (state ${s.state})")
        }
        val kpm = s.source == "KPM"
        if (kpm) {
            if (lastKpmMs != Long.MIN_VALUE && everLocked && s.timeMs - lastKpmMs >= AWAY_MS && s.state == "LOCKED") {
                decide("relock_after_away", Status.PASS, "relocked after ${(s.timeMs - lastKpmMs) / 1000} s away")
            }
            lastKpmMs = s.timeMs
            // Only collected until graded: a run can stay locked for minutes.
            if (this["match_speed"].status == Status.UNTESTED && s.state == "LOCKED") {
                s.matchMs?.let { matchTimes.add(it) }
                s.frameMs?.let { frameTimes.add(it) }
                if (matchTimes.size >= MATCH_SAMPLES) gradeMatchSpeed()
            }
            if (s.method == "FLOW" && s.state == "LOCKED") decide("flow_used", Status.PASS, "FLOW poses while locked")
            if (s.method == "KPM_RECTIFIED") {
                rectifiedCount++
                if (rectifiedCount >= RECTIFIED_POSES) decide("rectified", Status.PASS, "$rectifiedCount KPM_RECTIFIED poses")
            }
        }
        if (s.state == "LOCKED") everLocked = true
        // Bridge: LOCKED → IMU_BRIDGE → LOCKED.
        if (s.state == "IMU_BRIDGE" && lastState == "LOCKED") bridgeStartMs = s.timeMs
        if (s.state == "LOCKED" && lastState == "IMU_BRIDGE" && bridgeStartMs != Long.MIN_VALUE) {
            decide("bridge", Status.PASS, "bridged ${s.timeMs - bridgeStartMs} ms and relocked")
        }
        // Off the wall: a pose from elsewhere while the session is no longer seeing the target (not
        // the PHOTOSPHERE/HELD poses that also fill in while a relock is still confirming).
        val lookingAway = s.state == "REACQUIRING" || s.state == "LOST"
        val outOfView = lastKpmMs != Long.MIN_VALUE && s.timeMs - lastKpmMs >= OFF_WALL_MS
        if (everLocked && lookingAway && outOfView && (s.source == "PHOTOSPHERE" || s.source == "HELD")) {
            decide("off_wall", Status.PASS, "pose held from ${s.source} ${(s.timeMs - lastKpmMs) / 1000.0} s after the target left view")
        }
        gradePoles(s)
        lastState = s.state
        lastSampleMs = s.timeMs
    }

    private fun gradeLock(s: Sample) {
        val id = captureCheck ?: return
        if (captureMs == Long.MIN_VALUE) return
        val elapsed = s.timeMs - captureMs
        if (s.state == "LOCKED" && s.source == "KPM") {
            decide(id, Status.PASS, "locked $elapsed ms after capture at elevation ${fmt0(captureElevation)}°, ${s.inliers ?: "?"} inliers")
            captureCheck = null
        } else if (elapsed > LOCK_TIMEOUT_MS) {
            decide(id, Status.FAIL, "no lock within ${LOCK_TIMEOUT_MS / 1000} s of capture at elevation ${fmt0(captureElevation)}° (state ${s.state})")
            captureCheck = null
        }
    }

    private fun gradeMatchSpeed() {
        val sorted = matchTimes.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95) / 100]
        val frame50 = if (frameTimes.isEmpty()) null else median(frameTimes)
        val fits = p50 <= FRAME_BUDGET_MS && (frame50 == null || frame50 <= FRAME_BUDGET_MS)
        decide(
            "match_speed",
            if (fits) Status.PASS else Status.FAIL,
            "match p50 ${fmt1(p50)} ms, p95 ${fmt1(p95)} ms" +
                (frame50?.let { ", whole frame p50 ${fmt1(it)} ms" } ?: "") + " over ${sorted.size} poses",
        )
    }

    private fun gradePoles(s: Sample) {
        val el = s.elevationDeg ?: return
        if (abs(el) >= POLE_ATTITUDE_DEG && s.rebuildErrorDeg != null) {
            poleSamples++
            maxPoleError = maxOf(maxPoleError, s.rebuildErrorDeg)
            if (maxPoleError > POLE_ERROR_FAIL_DEG) {
                decide("pole_attitude", Status.FAIL, "rebuild error ${fmt2(maxPoleError)}° at elevation ${fmt1(el)}°")
            } else if (poleSamples >= POLE_SAMPLES) {
                decide("pole_attitude", Status.PASS, "max rebuild error ${String.format(Locale.ROOT, "%.3f", maxPoleError)}° over $poleSamples samples at |elevation| ≥ ${POLE_ATTITUDE_DEG.toInt()}°")
            }
        }
        // Facing steadiness: only while the phone is still (an angular rate, so it doesn't depend on
        // the frame rate) and pointing at the floor.
        val dtS = if (lastSampleMs == Long.MIN_VALUE) 0f else (s.timeMs - lastSampleMs) / 1000f
        val still = s.axisStepDeg != null && dtS > 0f && s.axisStepDeg / dtS < STILL_RATE_DEG_S
        if (el <= -POLE_FACING_DEG && still && s.facingDeg != null && s.headingDeg != null) {
            if (stillStartMs == Long.MIN_VALUE) stillStartMs = s.timeMs
            stillFacing.add(s.facingDeg); stillHeading.add(s.headingDeg)
            if (s.timeMs - stillStartMs >= STILL_WINDOW_MS && stillFacing.size >= 10) {
                val facingSpread = circularSpread(stillFacing)
                val headingSpread = circularSpread(stillHeading)
                decide(
                    "pole_facing",
                    if (facingSpread <= FACING_SPREAD_DEG) Status.PASS else Status.FAIL,
                    "facing spread ${fmt1(facingSpread)}° (axis heading spread ${fmt1(headingSpread)}°) over ${stillFacing.size} still samples",
                )
            }
        } else {
            clearStill()
        }
    }

    private fun clearStill() {
        stillStartMs = Long.MIN_VALUE
        stillFacing.clear(); stillHeading.clear()
    }

    /**
     * Thresholds. Where one comes from the library or a device budget it says so; the rest are this
     * bench's choices, tuned to what a person can do comfortably, and are listed in docs/TESTAPP.md.
     */
    companion object {
        val LOCK_CHECKS = setOf("lock_wall", "lock_floor", "lock_ceiling")
        /** Bench choice: a textured target locks in a few seconds; 30 s means it isn't going to. */
        const val LOCK_TIMEOUT_MS = 30_000L
        /** Bench choice: long enough that the session has dropped the lock, not just blinked. */
        const val AWAY_MS = 10_000L
        /** Out of view for this long before an off-target pose counts (the relock confirm window is shorter). */
        const val OFF_WALL_MS = 1_500L
        const val MATCH_SAMPLES = 100
        /** One frame at 30 fps, the camera's standard rate. */
        const val FRAME_BUDGET_MS = 33.3f
        const val RECTIFIED_POSES = 3
        /** The capture steps' gates (Surface.aimHint): a wall within 30° of level, floor/ceiling past 60°. */
        const val WALL_TARGET_DEG = 30f
        const val POLE_TARGET_DEG = 60f
        /** Inside the band where the camera-axis heading is noise, which is what the pole fixes are for. */
        const val POLE_ATTITUDE_DEG = 85f
        const val POLE_SAMPLES = 30
        /** Well above sensor noise (~0.1°) and well below a misread roll (≥ 90°). */
        const val POLE_ERROR_FAIL_DEG = 2f
        const val POLE_FACING_DEG = 80f
        /** A hand-held phone "held still" turns well under this; walking or panning is far above. */
        const val STILL_RATE_DEG_S = 5f
        const val STILL_WINDOW_MS = 2_000L
        const val FACING_SPREAD_DEG = 5f
        /** How long after the first capture a missing native library would have shown up. */
        const val NATIVE_SESSION_GRACE_MS = 2_000L
        /** `SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM`. */
        const val ACCURACY_MEDIUM = 2
        private val TILES_RE = Regex("""rotFixes=(\d+) poseFixes=(\d+)( disabled)?\}""")

        fun accuracyName(a: Int): String = when (a) {
            3 -> "high"
            2 -> "medium"
            1 -> "low"
            0 -> "unreliable"
            else -> "unknown ($a)"
        }

        // Locale.ROOT: evidence is read by people and parsed by tests, never localized.
        fun fmt0(v: Float) = String.format(Locale.ROOT, "%.0f", v)
        fun fmt1(v: Float) = String.format(Locale.ROOT, "%.1f", v)
        fun fmt2(v: Float) = String.format(Locale.ROOT, "%.2f", v)

        private fun median(values: List<Float>): Float = values.sorted()[values.size / 2]

        /** Largest angular distance of any value from the circular mean, degrees. */
        fun circularSpread(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            var x = 0.0; var y = 0.0
            for (v in values) { val r = Math.toRadians(v.toDouble()); x += kotlin.math.cos(r); y += kotlin.math.sin(r) }
            val mean = Math.toDegrees(kotlin.math.atan2(y, x)).toFloat()
            return values.maxOf { v -> abs(((v - mean + 540f) % 360f) - 180f) }
        }
    }
}

package com.hereliesaz.sphereslam.testapp

import kotlin.math.abs

/*
 * The device checklist. Most checks grade themselves from live telemetry ([Sample]s and session
 * events); the visual ones ([Check.manual]) take the tester's verdict. [Checklist.progress] says how
 * far an undecided check has got, for the guided run ([Guide]). Pure Kotlin, no Android, so every
 * grading rule is unit-tested on the JVM.
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
)

/**
 * The checks and their grading. Call [observe] per frame and the `on*` hooks for session events;
 * [changes] counts every status change, so a reporter can tell when there is something new to send.
 */
class Checklist {
    val checks: List<Check> = listOf(
        Check("layout_clear", "Every control clears the system bars and display cutout", "Automatic at start."),
        Check("attitude_sensor", "Rotation-vector sensor delivers reliable samples", "Open the app; wave the phone in a figure-8 if it says unreliable."),
        Check("lens_correction", "Camera delivers a distortion-corrected stream", "Automatic at camera start."),
        Check("lock_wall", "Locks a wall target within 30 s", "Point at a textured wall, tap Capture, keep it in view."),
        Check("lock_floor", "Locks a floor target within 30 s", "Look straight down at a textured floor, tap Capture."),
        Check("lock_ceiling", "Locks a ceiling target within 30 s", "Optional: look straight up at a textured ceiling, tap Capture."),
        Check("match_speed", "KPM/flow median match time fits a 30 fps frame", "Stay locked on a target for ~10 s."),
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

    fun set(id: String, status: Status, evidence: String) {
        val c = byId.getValue(id)
        if (c.status == status && c.evidence == evidence) return
        c.status = status
        c.evidence = evidence
        changes++
    }

    /** A decided check keeps its first verdict, except that a PASS can still be overturned by the tester. */
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
                "match_speed" -> matchTimes.clear()
                "rectified" -> rectifiedCount = 0
                "pole_attitude" -> { poleSamples = 0; maxPoleError = 0f }
                "pole_facing" -> { stillStartMs = Long.MIN_VALUE; stillFacing.clear(); stillHeading.clear() }
                "bridge" -> bridgeStartMs = Long.MIN_VALUE
                "lock_wall", "lock_floor", "lock_ceiling" -> if (captureCheck == id) captureCheck = null
            }
        }
    }

    // --- events ---------------------------------------------------------------------------------

    /** [clear] when no control overlaps a system bar or the display cutout; [evidence] gives the measurements. */
    fun onLayout(clear: Boolean, evidence: String) =
        set("layout_clear", if (clear) Status.PASS else Status.FAIL, evidence)

    fun onAttitude(available: Boolean, reliable: Boolean) {
        when {
            !available -> decide("attitude_sensor", Status.FAIL, "no TYPE_ROTATION_VECTOR sensor")
            reliable -> decide("attitude_sensor", Status.PASS, "reliable samples")
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

    /** A target was captured at [elevationDeg] for [checkId] (`lock_wall`, `lock_floor` or `lock_ceiling`). */
    fun onCapture(timeMs: Long, elevationDeg: Float?, checkId: String) {
        require(checkId in LOCK_CHECKS) { checkId }
        captureMs = timeMs
        captureElevation = elevationDeg ?: 0f
        captureCheck = checkId
    }

    // --- progress -------------------------------------------------------------------------------

    /** The latest sample's tracking state, or null before the first frame. */
    val trackingState: String? get() = lastState

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
                (if (matchTimes.isNotEmpty()) ", median ${"%.1f".format(matchTimes.sorted()[matchTimes.size / 2])} ms" else "")
            "flow_used" -> if (lastState == "LOCKED") "Locked; waiting for a flow-tracked frame" else "Waiting for a lock"
            "rectified" -> "$rectifiedCount of 3 steep-angle matches"
            "bridge" -> when (lastState) {
                "LOCKED" -> "Locked. Cover the lens now"
                "IMU_BRIDGE" -> "Bridging on the gyro. Uncover the lens"
                else -> "Waiting for a lock (${lastState ?: "starting"})"
            }
            "off_wall" -> if (everLocked) "Waiting for a pose without the target" else "Lock a target first"
            "relock_after_away" -> msSinceTargetSeen(nowMs)?.let { away ->
                if (away < AWAY_MS) "Away ${away / 1000} s of ${AWAY_MS / 1000} s" else "Away long enough. Point back at the target"
            } ?: "Lock a target first"
            "map_solved" -> "Waiting for the first lock"
            "atlas_growth" -> "Waiting for a new atlas page"
            "tile_fixes" -> "Waiting for a tile fix"
            "pole_attitude" -> "$poleSamples of $POLE_SAMPLES samples at the pole"
            "pole_facing" -> if (stillStartMs == Long.MIN_VALUE) "Hold still, pointing straight down"
                else "Still for ${"%.1f".format((nowMs - stillStartMs) / 1000f)} s of ${STILL_WINDOW_MS / 1000} s"
            "attitude_sensor" -> "Waiting for reliable rotation samples"
            else -> null
        }
    }

    fun onMapSolved() = decide("map_solved", Status.PASS, "solved on the first confirmed lock")

    fun onAtlasPage(pageNo: Int) = decide("atlas_growth", Status.PASS, "page $pageNo added")

    /** A periodic session diagnostic line; picks the tile fix counters out of `tiles{…}`. */
    fun onDiagnostic(line: String) {
        val rot = TILE_FIX_RE.find(line)?.groupValues
        if (rot != null) {
            val rotFixes = rot[1].toInt(); val poseFixes = rot[2].toInt()
            if (rotFixes + poseFixes > 0) decide("tile_fixes", Status.PASS, "rotFixes=$rotFixes poseFixes=$poseFixes")
        }
    }

    // --- per frame ------------------------------------------------------------------------------

    private var lastState: String? = null
    private var bridgeStartMs = Long.MIN_VALUE
    private var lastKpmMs = Long.MIN_VALUE
    private var everLocked = false
    private val matchTimes = ArrayList<Float>()
    private var rectifiedCount = 0
    private var maxPoleError = 0f
    private var poleSamples = 0
    private val stillFacing = ArrayList<Float>()
    private val stillHeading = ArrayList<Float>()
    private var stillStartMs = Long.MIN_VALUE

    fun observe(s: Sample) {
        gradeLock(s)
        val kpm = s.source == "KPM"
        if (kpm) {
            if (lastKpmMs != Long.MIN_VALUE && everLocked && s.timeMs - lastKpmMs >= AWAY_MS && s.state == "LOCKED") {
                decide("relock_after_away", Status.PASS, "relocked after ${(s.timeMs - lastKpmMs) / 1000} s away")
            }
            lastKpmMs = s.timeMs
            s.matchMs?.let { matchTimes.add(it) }
            if (matchTimes.size >= MATCH_SAMPLES) gradeMatchSpeed()
            if (s.method == "FLOW" && s.state == "LOCKED") decide("flow_used", Status.PASS, "FLOW poses while locked")
            if (s.method == "KPM_RECTIFIED") {
                rectifiedCount++
                if (rectifiedCount >= 3) decide("rectified", Status.PASS, "$rectifiedCount KPM_RECTIFIED poses")
            }
        }
        if (s.state == "LOCKED") everLocked = true
        // Bridge: LOCKED → IMU_BRIDGE → LOCKED.
        if (s.state == "IMU_BRIDGE" && lastState == "LOCKED") bridgeStartMs = s.timeMs
        if (s.state == "LOCKED" && lastState == "IMU_BRIDGE" && bridgeStartMs != Long.MIN_VALUE) {
            decide("bridge", Status.PASS, "bridged ${s.timeMs - bridgeStartMs} ms and relocked")
        }
        if (everLocked && (s.source == "PHOTOSPHERE" || s.source == "HELD")) {
            decide("off_wall", Status.PASS, "pose held from ${s.source} with the target out of view")
        }
        gradePoles(s)
        lastState = s.state
    }

    private fun gradeLock(s: Sample) {
        val id = captureCheck ?: return
        if (captureMs == Long.MIN_VALUE) return
        val elapsed = s.timeMs - captureMs
        if (s.state == "LOCKED" && s.source == "KPM") {
            decide(id, Status.PASS, "locked ${elapsed} ms after capture at elevation ${"%.0f".format(captureElevation)}°, ${s.inliers ?: "?"} inliers")
            captureCheck = null
        } else if (elapsed > LOCK_TIMEOUT_MS) {
            decide(id, Status.FAIL, "no lock within ${LOCK_TIMEOUT_MS / 1000} s of capture at elevation ${"%.0f".format(captureElevation)}° (state ${s.state})")
            captureCheck = null
        }
    }

    private fun gradeMatchSpeed() {
        val sorted = matchTimes.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95) / 100]
        decide(
            "match_speed",
            if (p50 <= FRAME_BUDGET_MS) Status.PASS else Status.FAIL,
            "p50 ${"%.1f".format(p50)} ms, p95 ${"%.1f".format(p95)} ms over ${sorted.size} poses",
        )
    }

    private fun gradePoles(s: Sample) {
        val el = s.elevationDeg ?: return
        if (abs(el) >= POLE_ATTITUDE_DEG && s.rebuildErrorDeg != null) {
            poleSamples++
            maxPoleError = maxOf(maxPoleError, s.rebuildErrorDeg)
            if (maxPoleError > POLE_ERROR_FAIL_DEG) {
                decide("pole_attitude", Status.FAIL, "rebuild error ${"%.2f".format(maxPoleError)}° at elevation ${"%.1f".format(el)}°")
            } else if (poleSamples >= POLE_SAMPLES) {
                decide("pole_attitude", Status.PASS, "max rebuild error ${"%.3f".format(maxPoleError)}° over $poleSamples samples at |elevation| ≥ ${POLE_ATTITUDE_DEG.toInt()}°")
            }
        }
        // Facing steadiness: only while the phone is still and pointing at the floor.
        val still = s.axisStepDeg != null && s.axisStepDeg < STILL_STEP_DEG
        if (el <= -POLE_FACING_DEG && still && s.facingDeg != null && s.headingDeg != null) {
            if (stillStartMs == Long.MIN_VALUE) stillStartMs = s.timeMs
            stillFacing.add(s.facingDeg); stillHeading.add(s.headingDeg)
            if (s.timeMs - stillStartMs >= STILL_WINDOW_MS && stillFacing.size >= 10) {
                val facingSpread = circularSpread(stillFacing)
                val headingSpread = circularSpread(stillHeading)
                decide(
                    "pole_facing",
                    if (facingSpread <= FACING_SPREAD_DEG) Status.PASS else Status.FAIL,
                    "facing spread ${"%.1f".format(facingSpread)}° (axis heading spread ${"%.1f".format(headingSpread)}°) over ${stillFacing.size} still samples",
                )
            }
        } else {
            stillStartMs = Long.MIN_VALUE
            stillFacing.clear(); stillHeading.clear()
        }
    }

    companion object {
        val LOCK_CHECKS = setOf("lock_wall", "lock_floor", "lock_ceiling")
        const val LOCK_TIMEOUT_MS = 30_000L
        const val AWAY_MS = 10_000L
        const val MATCH_SAMPLES = 100
        const val FRAME_BUDGET_MS = 33.3f
        const val WALL_TARGET_DEG = 30f
        const val POLE_TARGET_DEG = 60f
        const val POLE_ATTITUDE_DEG = 85f
        const val POLE_SAMPLES = 30
        const val POLE_ERROR_FAIL_DEG = 2f
        const val POLE_FACING_DEG = 80f
        const val STILL_STEP_DEG = 0.5f
        const val STILL_WINDOW_MS = 2_000L
        const val FACING_SPREAD_DEG = 5f
        private val TILE_FIX_RE = Regex("""rotFixes=(\d+) poseFixes=(\d+)""")

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

package com.hereliesaz.sphereslam.testapp

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The surface a capture step targets, the check its lock grades, and the camera elevation it needs. */
enum class Surface(val checkId: String, val word: String) {
    WALL("lock_wall", "wall"),
    FLOOR("lock_floor", "floor"),
    CEILING("lock_ceiling", "ceiling");

    /**
     * Null when a camera at [elevationDeg] (null: unknown) faces this surface; otherwise what the tester
     * has to do about it.
     */
    fun aimHint(elevationDeg: Float?): String? {
        val el = elevationDeg ?: return "Waiting for the rotation sensor"
        return when (this) {
            WALL -> when {
                el > Checklist.WALL_TARGET_DEG -> "Tilt down ${(el - Checklist.WALL_TARGET_DEG).roundToInt()}° to face the wall"
                el < -Checklist.WALL_TARGET_DEG -> "Tilt up ${(-Checklist.WALL_TARGET_DEG - el).roundToInt()}° to face the wall"
                else -> null
            }
            FLOOR -> if (el <= -Checklist.POLE_TARGET_DEG) null
                else "Tilt down ${(el + Checklist.POLE_TARGET_DEG).roundToInt()}° more to face the floor"
            CEILING -> if (el >= Checklist.POLE_TARGET_DEG) null
                else "Tilt up ${(Checklist.POLE_TARGET_DEG - el).roundToInt()}° more to face the ceiling"
        }
    }

    companion object {
        fun ofCheck(checkId: String?): Surface? = entries.firstOrNull { it.checkId == checkId }
    }
}

enum class StepKind {
    /** Graded from telemetry; the tester follows the instruction and watches. */
    AUTO,
    /** The tester aims at [Step.surface] and taps Capture; the lock is graded from telemetry. */
    CAPTURE,
    /** Only the tester can judge it: Pass / Fail, enabled once [Step.awayMs] is met. */
    MANUAL,
}

class Step(
    val title: String,
    /** Exactly what to do. */
    val instruction: String,
    /** The checks this step decides; it is done when none of them is UNTESTED. */
    val checks: List<String>,
    val kind: StepKind = StepKind.AUTO,
    val surface: Surface? = null,
    /** MANUAL only: how long the target must be out of view before the verdict buttons unlock. */
    val awayMs: Long = 0L,
    /** Shown when nothing has been detected after [Guide.HINT_AFTER_MS]. */
    val hint: String? = null,
    /** The target this step works on; it cannot run until the tracker holds a locked one. */
    val needs: Surface? = null,
    /** True when the step only makes progress while locked on [needs] (so being unlocked is a problem to fix). */
    val lockRequired: Boolean = false,
    /**
     * How long the step may run unblocked (no coach showing; for a capture step, after its lock is
     * decided) before its undecided checks fail as not seen. Null: never (only the tester decides).
     */
    val timeoutMs: Long? = null,
)

/** What the tracker is doing, as the coach needs it. */
class TrackingStatus(
    /** The session's state name, or null before the first frame. */
    val state: String?,
    /** Why tracking stopped for good, or null while it runs (a rejected capture is not a stop). */
    val fatal: String?,
    /** The latest recoverable failure and how long ago it happened, or null. */
    val problem: String?,
    val problemAgeMs: Long,
    /** How long since the session was last locked (or bridging); null if it never was. */
    val msSinceLocked: Long?,
    /** The lock check whose target the tracker currently holds (`lock_wall` …), or null when it holds none. */
    val reference: String? = null,
) {
    val locked: Boolean get() = state == "LOCKED" || state == "IMU_BRIDGE"
}

/** A fix the coach can offer as a button. */
enum class CoachAction(val label: String) {
    RESTART_TRACKING("Restart tracking"),
    CAPTURE_TARGET("Capture a target"),
    GRANT_CAMERA("Allow camera"),
    OPEN_SETTINGS("Open settings"),
    RESTART_CAMERA("Restart camera"),
}

/** Something in the way of the current step: what's wrong, what to do, and an optional button. */
class Coach(val message: String, val blocking: Boolean, val action: CoachAction? = null)

/**
 * The guided run: one [Step] at a time, in an order where each builds on the last (a lock before
 * anything that needs one, the wall before the poles). Pure state; the screen renders it.
 */
class Guide(val steps: List<Step> = STEPS) {

    var index: Int = 0
        private set

    /** When the current step started, on the caller's clock. */
    var startedMs: Long = 0L
        private set

    /** The step whose look-away wait has been met: its verdict stays unlocked when the target returns. */
    private var awayMetAt = -1

    val current: Step? get() = steps.getOrNull(index)
    val finished: Boolean get() = index >= steps.size

    fun start(nowMs: Long) = goTo(0, nowMs)

    /** Moves to step [to] (clamped; [steps.size] is the end). */
    fun goTo(to: Int, nowMs: Long) {
        index = to.coerceIn(0, steps.size)
        startedMs = nowMs
        awayMetAt = -1
    }

    fun next(nowMs: Long) = goTo(index + 1, nowMs)

    /** UNTESTED while any check is; then FAIL if any failed, SKIPPED if any was skipped, else PASS. */
    fun statusOf(step: Step, checklist: Checklist): Status {
        val statuses = step.checks.map { checklist[it].status }
        return when {
            Status.UNTESTED in statuses -> Status.UNTESTED
            Status.FAIL in statuses -> Status.FAIL
            Status.SKIPPED in statuses -> Status.SKIPPED
            else -> Status.PASS
        }
    }

    /** Marks the current step's undecided checks skipped and moves on. */
    fun skip(checklist: Checklist, nowMs: Long) {
        val step = current ?: return
        for (id in step.checks) if (checklist[id].status == Status.UNTESTED) checklist.set(id, Status.SKIPPED, "skipped by the tester")
        next(nowMs)
    }

    /** Clears the current step's verdicts so it runs again. */
    fun retry(checklist: Checklist, nowMs: Long) {
        val step = current ?: return
        checklist.reset(step.checks)
        startedMs = nowMs
        awayMetAt = -1
    }

    /** MANUAL steps: null when the verdict buttons may unlock, else the countdown to show. */
    fun manualWait(step: Step, checklist: Checklist, nowMs: Long): String? {
        if (step.awayMs <= 0L || awayMetAt == index) return null
        val away = checklist.msSinceTargetSeen(nowMs) ?: return "Lock a target first (go back to the wall step)"
        val left = step.awayMs - away
        if (left <= 0L) {
            // Latched: looking back at the target (the thing the question is about) must not lock it again.
            if (current === step) awayMetAt = index
            return null
        }
        return if (away < 1_000L) "Turn away from the target to start the timer"
        else "Keep looking away: ${formatSeconds(left)} left"
    }

    /** True once the current step has run [HINT_AFTER_MS] without a verdict. */
    fun showHint(checklist: Checklist, nowMs: Long): Boolean {
        val step = current ?: return false
        return step.hint != null && statusOf(step, checklist) == Status.UNTESTED && nowMs - startedMs >= HINT_AFTER_MS
    }

    /**
     * What, if anything, is stopping [step] from making progress, in the order the tester has to deal
     * with it: a stopped tracker, a rejected capture, a missing target, a slow or lost lock. Null when
     * nothing is.
     */
    fun coach(step: Step, checklist: Checklist, tracking: TrackingStatus, nowMs: Long): Coach? {
        if (statusOf(step, checklist) != Status.UNTESTED) return null
        tracking.fatal?.let { reason ->
            return Coach(
                "Tracking has stopped (${describeFailure(reason)}). Tap Restart tracking. If it stops again, " +
                    "this build can't run the step; the report has the details.",
                blocking = true,
                action = CoachAction.RESTART_TRACKING,
            )
        }
        val weak = tracking.problem == "REFERENCE_TOO_WEAK" && tracking.problemAgeMs < WEAK_TARGET_SHOWN_MS
        if (step.kind == StepKind.CAPTURE) {
            if (weak) {
                return Coach(
                    "That patch hasn't enough texture to track. Aim at something busier (printed text, a " +
                        "poster, a bookshelf) and tap Capture again.",
                    blocking = true,
                )
            }
            val since = checklist.msSincePendingCapture(nowMs)
            if (since != null && since >= SLOW_LOCK_MS) {
                return Coach("Still looking for it. Keep the same patch inside the frame and hold still.", blocking = false)
            }
            return null
        }
        val needs = step.needs
        if (needs != null && (checklist[needs.checkId].status != Status.PASS || tracking.reference != needs.checkId)) {
            val why = when {
                tracking.reference == null && checklist[needs.checkId].status == Status.PASS ->
                    "Tracking was restarted, so it no longer holds the ${needs.word} target."
                tracking.reference != null && tracking.reference != needs.checkId ->
                    "The tracker now holds the ${Surface.ofCheck(tracking.reference)?.word ?: "other"} target, not the ${needs.word} one."
                else -> "There isn't a locked ${needs.word} target yet."
            }
            return Coach("$why This step needs one: capture it first.", blocking = true, action = CoachAction.CAPTURE_TARGET)
        }
        if (step.lockRequired && !tracking.locked) {
            val lost = tracking.msSinceLocked ?: Long.MAX_VALUE
            if (lost >= UNLOCKED_GRACE_MS) {
                return Coach(
                    "Not locked on the target. Point the camera back at the ${needs?.word ?: "wall"} patch you " +
                        "captured and hold still; the white outline appears on it when it locks.",
                    blocking = false,
                    action = if (lost >= RECAPTURE_OFFER_MS) CoachAction.CAPTURE_TARGET else null,
                )
            }
        }
        return null
    }

    companion object {
        // Bench choices (docs/TESTAPP.md lists them with their reasons).
        const val HINT_AFTER_MS = 45_000L
        const val UNLOCKED_GRACE_MS = 3_000L
        const val RECAPTURE_OFFER_MS = 20_000L
        const val SLOW_LOCK_MS = 8_000L
        const val WEAK_TARGET_SHOWN_MS = 15_000L
        const val NO_FRAMES_MS = 5_000L

        /**
         * Camera trouble, which outranks any step: no permission, a failed start, or a started camera
         * delivering no frames. Null when the camera is fine (or still within its first seconds).
         */
        fun cameraCoach(permitted: Boolean, canAsk: Boolean, startError: String?, msWithoutFrames: Long?): Coach? = when {
            !permitted && canAsk -> Coach("The test needs the camera. Tap Allow camera.", blocking = true, action = CoachAction.GRANT_CAMERA)
            !permitted -> Coach(
                "Camera access is off for this app. Tap Open settings, then Permissions ▸ Camera ▸ Allow; the test " +
                    "picks up as soon as you come back.",
                blocking = true,
                action = CoachAction.OPEN_SETTINGS,
            )
            startError != null -> Coach("The camera didn't start ($startError).", blocking = true, action = CoachAction.RESTART_CAMERA)
            msWithoutFrames != null && msWithoutFrames >= NO_FRAMES_MS -> Coach(
                "The camera isn't delivering frames. Another app may be holding it; close it, then tap Restart camera.",
                blocking = true,
                action = CoachAction.RESTART_CAMERA,
            )
            else -> null
        }

        fun describeFailure(reason: String): String = when (reason) {
            "NATIVE_LIBRARY_UNAVAILABLE", "UnsatisfiedLinkError" -> "the native engine isn't loaded"
            "INTERNAL_FAILURE" -> "an internal error"
            "CAMERA_UNAVAILABLE" -> "the camera is unavailable"
            "INTRINSICS_UNAVAILABLE" -> "no camera calibration"
            else -> reason.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').lowercase(Locale.ROOT)
        }

        /** The tracking state in plain words, and the status that colours it. */
        fun describeState(tracking: TrackingStatus): Pair<String, Status> = when {
            tracking.fatal != null -> "Tracking stopped" to Status.FAIL
            tracking.state == null -> "Starting the camera" to Status.UNTESTED
            tracking.state == "FATAL" -> "Capture rejected" to Status.FAIL
            tracking.state == "LOCKED" -> "Locked on target" to Status.PASS
            tracking.state == "IMU_BRIDGE" -> "Holding on the gyro" to Status.PASS
            tracking.state == "REACQUIRING" -> "Target lost, searching" to Status.UNTESTED
            tracking.state == "LOST" -> "Target lost" to Status.UNTESTED
            tracking.state == "INITIALIZING" -> "Not locked" to Status.UNTESTED
            else -> tracking.state.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() } to Status.UNTESTED
        }

        fun formatSeconds(ms: Long): String {
            val s = ((ms + 999) / 1000).toInt()
            return if (s >= 60) String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) else "$s s"
        }

        /** Elevation in words, for the telemetry line. */
        fun describeElevation(el: Float): String = when {
            abs(el) < 2f -> "level"
            el > 0 -> "${el.roundToInt()}° up"
            else -> "${(-el).roundToInt()}° down"
        }

        val STEPS = listOf(
            Step(
                "Device check",
                "Hold the phone upright and still. This step checks the screen layout, the native engine " +
                    "in the APK, the rotation sensor and the camera's lens correction on its own.",
                listOf("layout_clear", "native_packaged", "attitude_sensor", "lens_correction"),
                hint = "Still waiting on the rotation sensor? Move the phone in a slow figure-8 a few times.",
                timeoutMs = 45_000L,
            ),
            Step(
                "Capture a wall target",
                "Stand 1 to 2 m from a well-lit, textured wall: a poster, a bookshelf or brick. A blank " +
                    "wall won't work. Fit the patch inside the square, hold still, then tap Capture.",
                listOf("lock_wall", "native_session", "map_solved"),
                kind = StepKind.CAPTURE,
                surface = Surface.WALL,
                timeoutMs = 20_000L,
            ),
            Step(
                "Track steadily",
                "Keep the target in view and sway slowly left and right for about 10 seconds.",
                listOf("match_speed", "flow_used"),
                hint = "The frame count only rises while the status line reads Locked on target.",
                needs = Surface.WALL, lockRequired = true,
                timeoutMs = 120_000L,
            ),
            Step(
                "Outline follows the target",
                "Take a step left, then right, keeping the target in view. Watch the white outline: " +
                    "does it stay on the target's edges the whole time?",
                listOf("outline_on_target"),
                kind = StepKind.MANUAL,
                needs = Surface.WALL, lockRequired = true,
            ),
            Step(
                "Outline at the screen edge",
                "Turn slowly until the target sits against the left edge of the camera view, then the " +
                    "right. Does the outline stay on the target there too?",
                listOf("outline_at_edges"),
                kind = StepKind.MANUAL,
                needs = Surface.WALL, lockRequired = true,
            ),
            Step(
                "Brief blackout",
                "While locked, cover the camera lens with a fingertip for about half a second, then lift it.",
                listOf("bridge"),
                hint = "Cover it only briefly: under a second. Make sure the status line reads Locked on target first.",
                needs = Surface.WALL, lockRequired = true,
                timeoutMs = 90_000L,
            ),
            Step(
                "Grow the map",
                "Pan slowly along the wall past one edge of the target, about one target-width beyond " +
                    "it, then come back.",
                listOf("atlas_growth"),
                hint = "Pan slower, and stay on textured parts of the wall.",
                needs = Surface.WALL, lockRequired = true,
                timeoutMs = 120_000L,
            ),
            Step(
                "Steep angle",
                "Walk to one side until you're looking at the target at a steep angle, 45° or more, " +
                    "keeping it in view. Hold there for a few seconds.",
                listOf("rectified"),
                hint = "Go steeper, but keep the target large in the frame.",
                needs = Surface.WALL, lockRequired = true,
                timeoutMs = 120_000L,
            ),
            Step(
                "Look away",
                "Turn about 90° away from the target and hold for a couple of seconds.",
                listOf("off_wall"),
                needs = Surface.WALL,
                timeoutMs = 60_000L,
            ),
            Step(
                "Look around the room",
                "Slowly look around the room: left, right, a little up and down. Then come back to face " +
                    "the target.",
                listOf("tile_fixes"),
                hint = "Move slowly and include textured areas; this builds the room map.",
                needs = Surface.WALL,
                timeoutMs = 120_000L,
            ),
            Step(
                "Relock after 10 seconds",
                "Look away from the target and keep looking away until the timer below passes 10 s. " +
                    "Then point back at the target.",
                listOf("relock_after_away"),
                needs = Surface.WALL,
                timeoutMs = 90_000L,
            ),
            Step(
                "Return after a minute",
                "Look away from the target for a full minute; the timer counts it down. Then point back " +
                    "at the target. Within a few seconds, does the outline land back on its edges?",
                listOf("returns_after_minute"),
                kind = StepKind.MANUAL,
                awayMs = 60_000L,
                needs = Surface.WALL,
            ),
            Step(
                "Capture a floor target",
                "Point the phone straight down at a textured floor: a rug, wood grain or patterned " +
                    "tiles. Fit it inside the square, hold still, then tap Capture.",
                listOf("lock_floor"),
                kind = StepKind.CAPTURE,
                surface = Surface.FLOOR,
            ),
            Step(
                "Hold over the floor",
                "Keep pointing straight down and hold the phone as still as you can for a few seconds.",
                listOf("pole_attitude", "pole_facing"),
                hint = "Point closer to straight down, and brace your elbows to hold still.",
                timeoutMs = 60_000L,
            ),
            Step(
                "Capture a ceiling target (optional)",
                "If the ceiling has texture (beams, panels, a light fitting), point straight up at it, " +
                    "fit it in the square and tap Capture. A plain ceiling won't work: tap Skip.",
                listOf("lock_ceiling"),
                kind = StepKind.CAPTURE,
                surface = Surface.CEILING,
            ),
        )
    }
}

package com.hereliesaz.sphereslam.testapp

import kotlin.math.abs
import kotlin.math.roundToInt

/** The surface a capture step targets, the check its lock grades, and the camera elevation it needs. */
enum class Surface(val checkId: String) {
    WALL("lock_wall"),
    FLOOR("lock_floor"),
    CEILING("lock_ceiling");

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
)

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

    val current: Step? get() = steps.getOrNull(index)
    val finished: Boolean get() = index >= steps.size

    fun start(nowMs: Long) {
        index = 0
        startedMs = nowMs
    }

    /** Moves to step [to] (clamped; [steps.size] is the end). */
    fun goTo(to: Int, nowMs: Long) {
        index = to.coerceIn(0, steps.size)
        startedMs = nowMs
    }

    fun next(nowMs: Long) = goTo(index + 1, nowMs)

    fun statusOf(step: Step, checklist: Checklist): Status {
        val statuses = step.checks.map { checklist[it].status }
        return when {
            Status.UNTESTED in statuses -> Status.UNTESTED
            Status.FAIL in statuses -> Status.FAIL
            statuses.all { it == Status.SKIPPED } -> Status.SKIPPED
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
    }

    /** MANUAL steps: null when the verdict buttons may unlock, else the countdown to show. */
    fun manualWait(step: Step, checklist: Checklist, nowMs: Long): String? {
        if (step.awayMs <= 0L) return null
        val away = checklist.msSinceTargetSeen(nowMs) ?: return "Lock a target first (go back to the wall step)"
        val left = step.awayMs - away
        if (left <= 0L) return null
        return if (away < 1_000L) "Turn away from the target to start the timer"
        else "Keep looking away: ${formatSeconds(left)} left"
    }

    /** True once the current step has run [HINT_AFTER_MS] without a verdict. */
    fun showHint(checklist: Checklist, nowMs: Long): Boolean {
        val step = current ?: return false
        return step.hint != null && statusOf(step, checklist) == Status.UNTESTED && nowMs - startedMs >= HINT_AFTER_MS
    }

    companion object {
        const val HINT_AFTER_MS = 45_000L

        fun formatSeconds(ms: Long): String {
            val s = ((ms + 999) / 1000).toInt()
            return if (s >= 60) "%d:%02d".format(s / 60, s % 60) else "$s s"
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
                "Hold the phone upright and still. This step checks the rotation sensor, the camera's " +
                    "lens correction and the screen layout on its own.",
                listOf("layout_clear", "attitude_sensor", "lens_correction"),
                hint = "Still waiting on the rotation sensor? Move the phone in a slow figure-8 a few times.",
            ),
            Step(
                "Capture a wall target",
                "Stand 1 to 2 m from a well-lit, textured wall: a poster, a bookshelf or brick. A blank " +
                    "wall won't work. Fit the patch inside the square, hold still, then tap Capture.",
                listOf("lock_wall", "map_solved"),
                kind = StepKind.CAPTURE,
                surface = Surface.WALL,
            ),
            Step(
                "Track steadily",
                "Keep the target in view and sway slowly left and right for about 10 seconds.",
                listOf("match_speed", "flow_used"),
                hint = "Tracking needs the target in view. If the state isn't LOCKED, point back at it.",
            ),
            Step(
                "Outline follows the target",
                "Take a step left, then right, keeping the target in view. Watch the white outline: " +
                    "does it stay on the target's edges the whole time?",
                listOf("outline_on_target"),
                kind = StepKind.MANUAL,
            ),
            Step(
                "Outline at the screen edge",
                "Turn slowly until the target sits against the left edge of the camera view, then the " +
                    "right. Does the outline stay on the target there too?",
                listOf("outline_at_edges"),
                kind = StepKind.MANUAL,
            ),
            Step(
                "Brief blackout",
                "While locked, cover the camera lens with a fingertip for about half a second, then lift it.",
                listOf("bridge"),
                hint = "Cover it only briefly: under a second. Make sure the state reads LOCKED first.",
            ),
            Step(
                "Grow the map",
                "Pan slowly along the wall past one edge of the target, about one target-width beyond " +
                    "it, then come back.",
                listOf("atlas_growth"),
                hint = "Pan slower, and stay on textured parts of the wall.",
            ),
            Step(
                "Steep angle",
                "Walk to one side until you're looking at the target at a steep angle, 45° or more, " +
                    "keeping it in view. Hold there for a few seconds.",
                listOf("rectified"),
                hint = "Go steeper, but keep the target large in the frame.",
            ),
            Step(
                "Look away",
                "Turn about 90° away from the target and hold for a second.",
                listOf("off_wall"),
            ),
            Step(
                "Look around the room",
                "Slowly look around the room: left, right, a little up and down. Then come back to face " +
                    "the target.",
                listOf("tile_fixes"),
                hint = "Move slowly and include textured areas; this builds the room map.",
            ),
            Step(
                "Relock after 10 seconds",
                "Look away from the target and keep looking away until the timer below passes 10 s. " +
                    "Then point back at the target.",
                listOf("relock_after_away"),
            ),
            Step(
                "Return after a minute",
                "Look away from the target for a full minute; the timer counts it down. Then point back " +
                    "at the target. Within a few seconds, does the outline land back on its edges?",
                listOf("returns_after_minute"),
                kind = StepKind.MANUAL,
                awayMs = 60_000L,
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

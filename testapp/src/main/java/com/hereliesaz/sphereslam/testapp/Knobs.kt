package com.hereliesaz.sphereslam.testapp

import com.hereliesaz.sphereslam.reloc.CameraTimestampSource
import com.hereliesaz.sphereslam.reloc.KpmMatchSpeedConfig
import com.hereliesaz.sphereslam.reloc.ObservationAgeConfig
import com.hereliesaz.sphereslam.reloc.PhotosphereTrackingSession
import com.hereliesaz.sphereslam.reloc.PoseAcceptanceConfig
import com.hereliesaz.sphereslam.reloc.TrackingStateConfig
import java.util.Locale

/**
 * One tunable setting of [PhotosphereTrackingSession.Config]: its range and step for the slider,
 * the library's default (read from the library's own defaults, so the two can't drift), what it does,
 * and how it is written into a config. Booleans are 0 / 1.
 */
class Knob(
    val id: String,
    val group: String,
    val label: String,
    val unit: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val default: Float,
    val explain: String,
    val apply: (PhotosphereTrackingSession.Config, Float) -> PhotosphereTrackingSession.Config,
) {
    val isSwitch: Boolean get() = min == 0f && max == 1f && step == 1f

    /** [value] snapped to the step and clamped to the range. */
    fun snap(value: Float): Float {
        val steps = Math.round((value.coerceIn(min, max) - min) / step)
        return (min + steps * step).coerceIn(min, max)
    }

    fun format(value: Float): String = when {
        isSwitch -> if (value >= 0.5f) "on" else "off"
        step >= 1f -> String.format(Locale.ROOT, "%.0f", value) + unit
        else -> String.format(Locale.ROOT, "%.1f", value) + unit
    }
}

/** The knobs a tuning session can turn, in the order the panel lists them. */
object Knobs {
    private val speed = KpmMatchSpeedConfig()
    private val acceptance = PoseAcceptanceConfig()
    private val state = TrackingStateConfig()
    private val session = PhotosphereTrackingSession.Config()

    private fun Boolean.f() = if (this) 1f else 0f

    private fun PhotosphereTrackingSession.Config.speed(f: (KpmMatchSpeedConfig) -> KpmMatchSpeedConfig) = copy(matchSpeed = f(matchSpeed))
    private fun PhotosphereTrackingSession.Config.accept(f: (PoseAcceptanceConfig) -> PoseAcceptanceConfig) = copy(acceptance = f(acceptance))
    private fun PhotosphereTrackingSession.Config.state(f: (TrackingStateConfig) -> TrackingStateConfig) = copy(trackingState = f(trackingState))

    val ALL: List<Knob> = listOf(
        Knob("oblique.rectifyDeg", "Steep angles", "Rectify from", "°", 0f, 60f, 5f, speed.obliqueRectificationDeg,
            "Predicted viewing angle at which the frame is re-rendered square-on before matching. 0 turns rectification off.",
        ) { c, v -> c.speed { it.copy(obliqueRectificationDeg = v) } },
        Knob("oblique.searchAfter", "Steep angles", "Guess steep views after", " misses", 0f, 10f, 1f, speed.obliqueSearchAfterMisses.toFloat(),
            "Direct misses in a row before guessed steep views join the search. 0 turns the search off.",
        ) { c, v -> c.speed { it.copy(obliqueSearchAfterMisses = v.toInt()) } },
        Knob("match.downscale", "Matching", "Match downscale", "×", 1f, 4f, 1f, speed.matchDownscale.toFloat(),
            "KPM matches the frame shrunk by this factor (never below 240 px on the short side). Higher is faster and coarser.",
        ) { c, v -> c.speed { it.copy(matchDownscale = v.toInt()) } },
        Knob("match.flow", "Matching", "Flow tracking", "", 0f, 1f, 1f, speed.flowTracking.f(),
            "Track frame to frame between full KPM solves.",
        ) { c, v -> c.speed { it.copy(flowTracking = v >= 0.5f) } },
        Knob("match.solveEvery", "Matching", "Full solve every", " frames", 1f, 30f, 1f, speed.fullSolveEveryFrames.toFloat(),
            "While flow tracks, a full KPM solve after at most this many frames.",
        ) { c, v -> c.speed { it.copy(fullSolveEveryFrames = v.toInt()) } },
        Knob("match.solveInterval", "Matching", "Full solve at least every", " ms", 100f, 2000f, 50f, speed.fullSolveIntervalMs.toFloat(),
            "While flow tracks, a full KPM solve at least this often.",
        ) { c, v -> c.speed { it.copy(fullSolveIntervalMs = v.toLong()) } },
        Knob("match.flowOnly", "Matching", "Flow alone for at most", " ms", 0f, 5000f, 250f, speed.maxFlowOnlyMs.toFloat(),
            "How long flow may keep the pose after the last successful full solve when periodic solves miss.",
        ) { c, v -> c.speed { it.copy(maxFlowOnlyMs = v.toLong()) } },
        Knob("match.flowInliers", "Matching", "Flow inliers at least", "", 4f, 40f, 1f, speed.minFlowInliers.toFloat(),
            "Fewest surviving flow points for a tracked pose.",
        ) { c, v -> c.speed { it.copy(minFlowInliers = v.toInt()) } },
        Knob("match.flowRms", "Matching", "Flow error at most", " px", 0.5f, 8f, 0.5f, speed.maxFlowRmsErrorPx,
            "Largest RMS reprojection error of a flow-tracked pose.",
        ) { c, v -> c.speed { it.copy(maxFlowRmsErrorPx = v) } },
        Knob("accept.error", "Acceptance", "Pose error at most", " px²", 1f, 30f, 1f, acceptance.maxReprojectionError,
            "Largest mean squared reprojection error a pose may have to be accepted.",
        ) { c, v -> c.accept { it.copy(maxReprojectionError = v) } },
        Knob("accept.inliers", "Acceptance", "Pose inliers at least", "", 4f, 60f, 1f, acceptance.minInliers.toFloat(),
            "Fewest matches a pose needs to be accepted.",
        ) { c, v -> c.accept { it.copy(minInliers = v.toInt()) } },
        Knob("accept.age", "Acceptance", "Frame age at most", " ms", 100f, 1000f, 25f, ObservationAgeConfig().maxRealtimeAgeMs,
            "A pose from a frame older than this when its match finishes is dropped as stale. Slow relock solves can exceed it.",
        ) { c, v -> c.copy(observationAge = c.observationAge.copy(maxRealtimeAgeMs = v)) },
        Knob("state.confirm", "Lock", "Confirm a lock over", " frames", 1f, 6f, 1f, state.confirmationFrames.toFloat(),
            "Consecutive accepted poses needed to (re)lock.",
        ) { c, v -> c.state { it.copy(confirmationFrames = v.toInt()) } },
        Knob("state.lostAfter", "Lock", "Searching becomes lost after", " ms", 250f, 5000f, 250f, state.lostAfterMs.toFloat(),
            "How long the session reacquires before it declares the target lost.",
        ) { c, v -> c.state { it.copy(lostAfterMs = v.toLong()) } },
        Knob("session.bridge", "Lock", "Gyro bridge up to", " ms", 0f, 1500f, 50f, session.maxBridgeMs.toFloat(),
            "Longest a brief visual miss is held on the gyro.",
        ) { c, v -> c.copy(maxBridgeMs = v.toLong()) },
        Knob("session.tiles", "Photosphere", "Tile localization", "", 0f, 1f, 1f, session.tileLocalization.f(),
            "Photosphere tiles fix the pose while the target is out of view.",
        ) { c, v -> c.copy(tileLocalization = v >= 0.5f) },
        Knob("session.growth", "Photosphere", "Atlas growth", "", 0f, 1f, 1f, session.atlasGrowth.f(),
            "Add new pages around the captured one as the wall comes into view.",
        ) { c, v -> c.copy(atlasGrowth = v >= 0.5f) },
    )

    private val byId = ALL.associateBy { it.id }

    operator fun get(id: String): Knob? = byId[id]

    val DEFAULTS: Map<String, Float> get() = ALL.associate { it.id to it.default }

    /** The session config for [values] (missing knobs at their defaults; unknown ids ignored). */
    fun config(values: Map<String, Float>, timestampSource: CameraTimestampSource): PhotosphereTrackingSession.Config =
        ALL.fold(PhotosphereTrackingSession.Config(timestampSource = timestampSource)) { c, k ->
            k.apply(c, k.snap(values[k.id] ?: k.default))
        }

    /** The knobs [values] sets away from their defaults, as `label value` pairs; empty for the defaults. */
    fun describe(values: Map<String, Float>): String =
        ALL.filter { k -> values[k.id]?.let { k.snap(it) != k.default } == true }
            .joinToString(", ") { k -> "${k.label} ${k.format(values.getValue(k.id))}" }
            .ifEmpty { "library defaults" }
}

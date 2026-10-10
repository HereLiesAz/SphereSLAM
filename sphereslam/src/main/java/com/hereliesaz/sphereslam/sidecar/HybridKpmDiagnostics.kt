package com.hereliesaz.sphereslam.sidecar

import java.util.Locale

/**
 * Outcome of one KPM sidecar observation. Order follows gate order; not meant to be persisted by
 * ordinal.
 */
enum class HybridKpmOutcome {
    /** No observation has been evaluated yet (or the hybrid path is not armed). */
    NOT_SAMPLED,

    /** The page is not physically metric, or the page↔artwork relation is not frozen yet. */
    NON_METRIC,

    /** Older than the pairing window, from the future, or the clock is missing. */
    STALE,
    TOO_FEW_INLIERS,
    BAD_REPROJECTION,
    NON_FINITE,

    /** No primary pose sample within the timestamp pairing window. */
    NO_POSE_PAIR,

    /** The implied correction exceeds the hard plausibility ceiling; refused outright. */
    CORRECTION_TOO_LARGE,

    /** Passed every gate but implies a large jump; held until repeated observations agree. */
    AWAITING_AGREEMENT,

    /** Handed to fusion. */
    ACCEPTED,
}

/**
 * What the hybrid correction path decided about the most recent KPM observation, and the evidence
 * it decided on. Every numeric field is `-1` when not computed for this outcome (an early gate
 * returns before later quantities exist).
 *
 * @property ageMs render-frame time minus observation time, milliseconds.
 * @property correctionMm magnitude of the anchor-local correction the observation implies, mm.
 * @property correctionDeg rotation magnitude of that correction, degrees.
 * @property agreeingObservations agreeing large-correction observations collected so far.
 * @property requiredAgreement agreeing observations a large correction needs before it may snap.
 */
data class HybridKpmDiagnostics(
    val outcome: HybridKpmOutcome = HybridKpmOutcome.NOT_SAMPLED,
    val observationTimestampNs: Long = -1L,
    val ageMs: Float = -1f,
    val inliers: Int = -1,
    val reprojectionPx: Float = -1f,
    val correctionMm: Float = -1f,
    val correctionDeg: Float = -1f,
    val agreeingObservations: Int = -1,
    val requiredAgreement: Int = -1,
) {
    /** One-line overlay/report rendering. */
    fun summary(): String = buildString {
        append(outcome.name)
        if (ageMs >= 0f) append(" age=").append(ageMs.toInt()).append("ms")
        if (inliers >= 0) append(" in=").append(inliers)
        if (reprojectionPx >= 0f) append(" err=").append(String.format(Locale.US, "%.1f", reprojectionPx)).append("px")
        if (correctionMm >= 0f) {
            append(" Δ=").append(correctionMm.toInt()).append("mm/")
            append(String.format(Locale.US, "%.1f", correctionDeg)).append("°")
        }
        if (agreeingObservations >= 0) append(" agree=").append(agreeingObservations).append('/').append(requiredAgreement)
    }
}

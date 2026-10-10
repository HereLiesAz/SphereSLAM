package com.hereliesaz.sphereslam.reloc

/** How a [TrackingFailureReason] should be handled. */
@ExperimentalSphereSlamRelocApi
enum class TrackingFailureSeverity {
    /** Expected frame-to-frame noise; keep tracking. */
    TRANSIENT,

    /** Needs host action (new target, camera, calibration) but the runtime can continue. */
    RECOVERABLE,

    /** The tracking runtime cannot continue. */
    FATAL,
}

/** Failure taxonomy of a camera-driven planar/photosphere tracking runtime. */
@ExperimentalSphereSlamRelocApi
enum class TrackingFailureReason {
    NATIVE_LIBRARY_UNAVAILABLE,
    CAMERA_UNAVAILABLE,
    INTRINSICS_UNAVAILABLE,
    REFERENCE_TOO_WEAK,
    NO_CURRENT_PAGE_MATCH,
    STALE_OBSERVATION,
    EXCESSIVE_REPROJECTION_ERROR,
    INSUFFICIENT_INLIERS,
    POSE_JUMP,
    PERSISTED_TARGET_CORRUPT,
    INTERNAL_FAILURE,
}

/**
 * One classified failure. [diagnostic] is a developer string; user-facing wording is the host's
 * concern (map [reason] to localised text).
 */
@ExperimentalSphereSlamRelocApi
data class TrackingFailureEvent(
    val reason: TrackingFailureReason,
    val severity: TrackingFailureSeverity,
    val diagnostic: String = "",
)

/** Maps reasons, [PoseRejection]s and exceptions onto [TrackingFailureEvent]s. Ported from GraffitiXR `StandaloneFailureClassifier`. */
@ExperimentalSphereSlamRelocApi
object TrackingFailureClassifier {

    fun severityOf(reason: TrackingFailureReason): TrackingFailureSeverity = when (reason) {
        TrackingFailureReason.NO_CURRENT_PAGE_MATCH,
        TrackingFailureReason.STALE_OBSERVATION,
        TrackingFailureReason.EXCESSIVE_REPROJECTION_ERROR,
        TrackingFailureReason.INSUFFICIENT_INLIERS,
        TrackingFailureReason.POSE_JUMP -> TrackingFailureSeverity.TRANSIENT

        TrackingFailureReason.REFERENCE_TOO_WEAK,
        TrackingFailureReason.PERSISTED_TARGET_CORRUPT,
        TrackingFailureReason.CAMERA_UNAVAILABLE,
        TrackingFailureReason.INTRINSICS_UNAVAILABLE -> TrackingFailureSeverity.RECOVERABLE

        TrackingFailureReason.NATIVE_LIBRARY_UNAVAILABLE,
        TrackingFailureReason.INTERNAL_FAILURE -> TrackingFailureSeverity.FATAL
    }

    fun event(reason: TrackingFailureReason, diagnostic: String = ""): TrackingFailureEvent =
        TrackingFailureEvent(reason = reason, severity = severityOf(reason), diagnostic = diagnostic)

    /** A single malformed (non-finite) pose is classified like a continuity jump: reacquire, don't die. */
    fun fromPoseRejection(rejection: PoseRejection, diagnostic: String = ""): TrackingFailureEvent = event(
        reason = when (rejection) {
            PoseRejection.TOO_FEW_INLIERS -> TrackingFailureReason.INSUFFICIENT_INLIERS
            PoseRejection.EXCESSIVE_REPROJECTION_ERROR -> TrackingFailureReason.EXCESSIVE_REPROJECTION_ERROR
            PoseRejection.TRANSLATION_JUMP,
            PoseRejection.ANGULAR_JUMP,
            PoseRejection.NON_FINITE -> TrackingFailureReason.POSE_JUMP
        },
        diagnostic = diagnostic,
    )

    fun fromThrowable(error: Throwable): TrackingFailureEvent = when (error) {
        is UnsatisfiedLinkError -> event(
            TrackingFailureReason.NATIVE_LIBRARY_UNAVAILABLE,
            "nativeError=${error::class.java.simpleName}: ${error.message.orEmpty()}",
        )
        is ReferenceTooWeakException -> event(
            TrackingFailureReason.REFERENCE_TOO_WEAK,
            "features=${error.featureCount} minimum=${error.minimumFeatureCount}",
        )
        else -> event(
            TrackingFailureReason.INTERNAL_FAILURE,
            "error=${error::class.java.simpleName}: ${error.message.orEmpty()}",
        )
    }
}

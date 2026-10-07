package com.hereliesaz.sphereslam.reloc

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Acceptance gate for a relocalization/tracking pose: inlier floor, reprojection-error ceiling, and
 * frame-to-frame continuity (how far the camera centre and view direction may jump in one frame).
 *
 * Defaults mirror a planar KPM/PnP front-end. Continuity limits loosen in [PoseAcceptancePolicy.evaluate]'s
 * `reacquiring` mode, where a large jump back to the true pose after a dropout is expected and good.
 *
 * @property minInliers minimum PnP inliers to accept (`>= 4`).
 * @property maxReprojectionError maximum PnP reprojection error to accept (`> 0`).
 * @property maxTranslationPageWidthsPerFrame max camera-centre move per frame, in reference-widths.
 * @property maxAngularJumpDegrees max view-direction change per frame, degrees (`0..180`).
 * @property reacquireMaxTranslationPageWidths looser translation limit while reacquiring.
 * @property reacquireMaxAngularJumpDegrees looser angular limit while reacquiring (`0..180`).
 */
@ExperimentalSphereSlamRelocApi
data class PoseAcceptanceConfig(
    val minInliers: Int = 4,
    val maxReprojectionError: Float = 10f,
    val maxTranslationPageWidthsPerFrame: Float = 2f,
    val maxAngularJumpDegrees: Float = 90f,
    val reacquireMaxTranslationPageWidths: Float = 8f,
    val reacquireMaxAngularJumpDegrees: Float = 175f,
) {
    init {
        require(minInliers >= 4)
        require(maxReprojectionError.isFinite() && maxReprojectionError > 0f)
        require(maxTranslationPageWidthsPerFrame.isFinite() && maxTranslationPageWidthsPerFrame > 0f)
        require(maxAngularJumpDegrees in 0f..180f)
        require(reacquireMaxTranslationPageWidths.isFinite() && reacquireMaxTranslationPageWidths > 0f)
        require(reacquireMaxAngularJumpDegrees in 0f..180f)
    }
}

/** Why a pose was rejected by [PoseAcceptancePolicy]. */
@ExperimentalSphereSlamRelocApi
enum class PoseRejection {
    NON_FINITE,
    TOO_FEW_INLIERS,
    EXCESSIVE_REPROJECTION_ERROR,
    TRANSLATION_JUMP,
    ANGULAR_JUMP,
}

/** Outcome of [PoseAcceptancePolicy.evaluate]: [accepted], with [rejection] set when false. */
@ExperimentalSphereSlamRelocApi
data class PoseAcceptance(
    val accepted: Boolean,
    val rejection: PoseRejection? = null,
)

/**
 * Evaluates whether a candidate pose should be accepted. Stateless — the caller supplies the
 * previous accepted pose for the continuity check.
 */
@ExperimentalSphereSlamRelocApi
class PoseAcceptancePolicy(
    private val config: PoseAcceptanceConfig = PoseAcceptanceConfig(),
) {
    /**
     * @param viewMatrix candidate pose, column-major length 16.
     * @param inlierCount PnP inliers backing it.
     * @param reprojectionError PnP reprojection error.
     * @param previousViewMatrix the last accepted pose for the continuity check, or null to skip it
     *   (first frame).
     * @param referenceWidthUnits the reference's width in the pose's units, used to scale the
     *   translation-jump limit; must be `> 0` when [previousViewMatrix] is given.
     * @param reacquiring true to apply the looser reacquire continuity limits.
     * @return the [PoseAcceptance] verdict.
     */
    fun evaluate(
        viewMatrix: FloatArray,
        inlierCount: Int,
        reprojectionError: Float,
        previousViewMatrix: FloatArray? = null,
        referenceWidthUnits: Float = 1f,
        reacquiring: Boolean = false,
    ): PoseAcceptance {
        if (viewMatrix.size != 16 || viewMatrix.any { !it.isFinite() } || !reprojectionError.isFinite()) {
            return PoseAcceptance(false, PoseRejection.NON_FINITE)
        }
        if (inlierCount < config.minInliers) {
            return PoseAcceptance(false, PoseRejection.TOO_FEW_INLIERS)
        }
        if (reprojectionError > config.maxReprojectionError) {
            return PoseAcceptance(false, PoseRejection.EXCESSIVE_REPROJECTION_ERROR)
        }

        if (previousViewMatrix != null) {
            if (previousViewMatrix.size != 16 || previousViewMatrix.any { !it.isFinite() }) {
                return PoseAcceptance(false, PoseRejection.NON_FINITE)
            }
            if (!referenceWidthUnits.isFinite() || referenceWidthUnits <= 0f) {
                return PoseAcceptance(false, PoseRejection.NON_FINITE)
            }

            val translationLimit = if (reacquiring) {
                config.reacquireMaxTranslationPageWidths
            } else {
                config.maxTranslationPageWidthsPerFrame
            }
            val angularLimit = if (reacquiring) {
                config.reacquireMaxAngularJumpDegrees
            } else {
                config.maxAngularJumpDegrees
            }

            val translationPageWidths =
                cameraCenterDistance(previousViewMatrix, viewMatrix) / referenceWidthUnits
            if (translationPageWidths > translationLimit) {
                return PoseAcceptance(false, PoseRejection.TRANSLATION_JUMP)
            }
            if (rotationDeltaDegrees(previousViewMatrix, viewMatrix) > angularLimit) {
                return PoseAcceptance(false, PoseRejection.ANGULAR_JUMP)
            }
        }
        return PoseAcceptance(true)
    }

    private fun cameraCenterDistance(a: FloatArray, b: FloatArray): Float {
        val ac = cameraCenter(a)
        val bc = cameraCenter(b)
        val dx = ac[0] - bc[0]
        val dy = ac[1] - bc[1]
        val dz = ac[2] - bc[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Camera centre `−Rᵀt` of a column-major view matrix. */
    private fun cameraCenter(view: FloatArray): FloatArray {
        val tx = view[12]; val ty = view[13]; val tz = view[14]
        return floatArrayOf(
            -(view[0] * tx + view[1] * ty + view[2] * tz),
            -(view[4] * tx + view[5] * ty + view[6] * tz),
            -(view[8] * tx + view[9] * ty + view[10] * tz),
        )
    }

    /** Geodesic angle (degrees) between the rotation parts of two column-major view matrices. */
    private fun rotationDeltaDegrees(a: FloatArray, b: FloatArray): Float {
        val trace =
            (a[0] * b[0] + a[4] * b[4] + a[8] * b[8]) +
                (a[1] * b[1] + a[5] * b[5] + a[9] * b[9]) +
                (a[2] * b[2] + a[6] * b[6] + a[10] * b[10])
        val cosine = ((trace - 1f) * 0.5f).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine.toDouble())).toFloat()
    }
}

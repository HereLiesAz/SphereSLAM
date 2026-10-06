package com.hereliesaz.sphereslam.reloc

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Rigid-pose blending and divergence tests on column-major 4×4 view matrices — the primitives the
 * [PoseStabilizer] and any pose-fusion step use.
 *
 * "Diverged" means two poses differ by more than the snap thresholds; a stabilizer passes such a
 * jump through unsmoothed (a fast pan / a reacquisition) rather than lagging it.
 */
object PoseBlend {

    /** Translation difference (metres) beyond which two poses are considered a discontinuity. */
    const val SNAP_DISTANCE_METERS = 0.20f

    /** Rotation difference (degrees) beyond which two poses are considered a discontinuity. */
    const val SNAP_ANGLE_DEGREES = 15f

    /**
     * Blend [current] toward [target] by [alpha] — translation lerp + quaternion nlerp.
     *
     * @param current the baseline pose, column-major length 16.
     * @param target the newest pose, column-major length 16.
     * @param alpha weight toward [target] in `[0, 1]` (0 keeps [current], 1 takes [target]).
     * @return the blended rigid pose, column-major length 16.
     */
    fun blend(current: FloatArray, target: FloatArray, alpha: Float): FloatArray {
        val t = PoseMath.lerp(PoseMath.translationOf(current), PoseMath.translationOf(target), alpha)
        val q = PoseMath.nlerpQuat(
            PoseMath.matrixToQuaternion(current),
            PoseMath.matrixToQuaternion(target),
            alpha,
        )
        return PoseMath.fromQuaternionTranslation(q, t)
    }

    /**
     * Whether two rigid poses differ in translation or rotation beyond the snap thresholds.
     *
     * @param a first pose, column-major length 16.
     * @param b second pose, column-major length 16.
     * @return true if the translation gap ≥ [SNAP_DISTANCE_METERS] or the rotation gap ≥
     *   [SNAP_ANGLE_DEGREES].
     */
    fun diverged(a: FloatArray, b: FloatArray): Boolean {
        val ta = PoseMath.translationOf(a); val tb = PoseMath.translationOf(b)
        val dx = ta[0] - tb[0]; val dy = ta[1] - tb[1]; val dz = ta[2] - tb[2]
        if (sqrt(dx * dx + dy * dy + dz * dz) >= SNAP_DISTANCE_METERS) return true
        val qa = PoseMath.matrixToQuaternion(a); val qb = PoseMath.matrixToQuaternion(b)
        val dot = abs(qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3]).coerceIn(0f, 1f)
        val angleDeg = Math.toDegrees(2.0 * acos(dot.toDouble())).toFloat()
        return angleDeg >= SNAP_ANGLE_DEGREES
    }
}

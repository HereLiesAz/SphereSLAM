package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.math.RigidMath
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
@ExperimentalSphereSlamRelocApi
object PoseBlend {

    /** Translation difference (metres) beyond which two poses are considered a discontinuity. */
    const val SNAP_DISTANCE_METERS = 0.20f

    /** Rotation difference (degrees) beyond which two poses are considered a discontinuity. */
    const val SNAP_ANGLE_DEGREES = 15f

    /**
     * Blend [current] toward [target] by [alpha] — camera-centre lerp + quaternion nlerp.
     *
     * The poses are camera-from-map, so their translation column `t = −R·c` mixes position with
     * orientation. Interpolating `t` directly would swing the camera centre `c` off the straight path
     * whenever the rotations differ; instead the camera centres are interpolated and `t` is recomputed
     * from the blended rotation.
     *
     * @param current the baseline pose, column-major length 16.
     * @param target the newest pose, column-major length 16.
     * @param alpha weight toward [target] in `[0, 1]` (0 keeps [current], 1 takes [target]).
     * @return the blended rigid pose, column-major length 16.
     */
    fun blend(current: FloatArray, target: FloatArray, alpha: Float): FloatArray {
        val c = PoseMath.lerp(cameraCenter(current), cameraCenter(target), alpha)
        val q = PoseMath.nlerpQuat(
            PoseMath.matrixToQuaternion(current),
            PoseMath.matrixToQuaternion(target),
            alpha,
        )
        val rotationOnly = PoseMath.fromQuaternionTranslation(q, floatArrayOf(0f, 0f, 0f))
        // t = −R·c (column-major: R[row][col] = m[col*4 + row]).
        val t = FloatArray(3) { row ->
            -(rotationOnly[row] * c[0] + rotationOnly[4 + row] * c[1] + rotationOnly[8 + row] * c[2])
        }
        return PoseMath.fromQuaternionTranslation(q, t)
    }

    /**
     * Whether two rigid poses differ in camera position or rotation beyond the snap thresholds.
     *
     * @param a first pose, column-major length 16.
     * @param b second pose, column-major length 16.
     * @return true if the camera-centre (`−Rᵀt`) gap ≥ [SNAP_DISTANCE_METERS] or the rotation gap ≥
     *   [SNAP_ANGLE_DEGREES].
     */
    fun diverged(a: FloatArray, b: FloatArray): Boolean {
        val ca = cameraCenter(a); val cb = cameraCenter(b)
        val dx = ca[0] - cb[0]; val dy = ca[1] - cb[1]; val dz = ca[2] - cb[2]
        if (sqrt(dx * dx + dy * dy + dz * dz) >= SNAP_DISTANCE_METERS) return true
        val qa = PoseMath.matrixToQuaternion(a); val qb = PoseMath.matrixToQuaternion(b)
        val dot = abs(qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3]).coerceIn(0f, 1f)
        val angleDeg = Math.toDegrees(2.0 * acos(dot.toDouble())).toFloat()
        return angleDeg >= SNAP_ANGLE_DEGREES
    }

    /** Camera centre `−Rᵀt` of a column-major camera-from-map matrix. */
    internal fun cameraCenter(view: FloatArray): FloatArray = RigidMath.cameraCentre(view)
}

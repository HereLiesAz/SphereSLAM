package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.math.RigidMath

/**
 * Pure column-major 4×4 (OpenGL layout) helpers for rigid transforms — no Android dependencies.
 *
 * These back the relocalization robustness layer ([PoseBlend], [PoseStabilizer], [PoseAcceptancePolicy])
 * and are exposed for consumers that need the same quaternion/translation math on poses.
 *
 * Every function delegates to the supported `:sphereslam` [com.hereliesaz.sphereslam.math.RigidMath],
 * which non-`:reloc` consumers should prefer.
 */
@ExperimentalSphereSlamRelocApi
object PoseMath {

    /**
     * Column-major 4×4 matrix product `a · b`.
     *
     * @param a left matrix, length 16, column-major.
     * @param b right matrix, length 16, column-major.
     * @return the product, length 16.
     */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray = RigidMath.multiply(a, b)

    /** Inverse of a rigid transform `[R|t]` (rotation + translation, no scale): `[Rᵀ | −Rᵀt]`. */
    fun rigidInverse(m: FloatArray): FloatArray = RigidMath.rigidInverse(m)

    /** Uniform scale of a similarity `[sR|t]` (norm of its first column); 1 for a rigid matrix. */
    fun scaleOf(m: FloatArray): Float = RigidMath.scaleOf(m)

    /**
     * Inverse of a uniform-scale similarity `[sR|t]`: `[Rᵀ/s | −Rᵀt/s]`. See
     * [RigidMath.similarityInverse] for the in-plane-scale exactness caveat.
     */
    fun similarityInverse(m: FloatArray): FloatArray = RigidMath.similarityInverse(m)

    /** The translation column `(x, y, z)` of a column-major matrix. */
    fun translationOf(m: FloatArray) = RigidMath.translationOf(m)

    /** Euclidean length of the translation column, in the matrix's own units. */
    fun translationNorm(m: FloatArray): Float = RigidMath.translationNorm(m)

    /** Rotation magnitude of a rigid transform in degrees, in `[0, 180]`. */
    fun rotationAngleDeg(m: FloatArray): Float = RigidMath.rotationAngleDeg(m)

    /** Extract a unit quaternion `(x, y, z, w)` from the rotation part of a column-major matrix. */
    fun matrixToQuaternion(m: FloatArray): FloatArray = RigidMath.matrixToQuaternion(m)

    /** Normalize a quaternion `(x, y, z, w)` to unit length; returns identity for a zero quaternion. */
    fun normalizeQuat(q: FloatArray): FloatArray = RigidMath.normalizeQuat(q)

    /** Build a column-major matrix from a unit quaternion `(x, y, z, w)` and a translation `(x, y, z)`. */
    fun fromQuaternionTranslation(q: FloatArray, t: FloatArray): FloatArray = RigidMath.fromQuaternionTranslation(q, t)

    /** Normalized lerp between unit quaternions, hemisphere-corrected. [t] in `[0, 1]`. */
    fun nlerpQuat(a: FloatArray, b: FloatArray, t: Float): FloatArray = RigidMath.nlerpQuat(a, b, t)

    /** Component-wise lerp of two 3-vectors. [t] in `[0, 1]`. */
    fun lerp(a: FloatArray, b: FloatArray, t: Float) = RigidMath.lerp(a, b, t)

    /**
     * OpenGL camera-from-world view (camera looks −z) → OpenCV convention (camera looks +z): negate
     * rows 1 and 2 (`diag(1, −1, −1) · view`). Column-major in and out. Its own inverse.
     */
    fun glViewToCv(view: FloatArray): FloatArray = RigidMath.glViewToCv(view)

    /** OpenCV row-major camera-from-world → OpenGL column-major camera-from-world. */
    fun cvRowMajorToGlColumnMajor(rowMajorCv: FloatArray): FloatArray = RigidMath.cvRowMajorToGlColumnMajor(rowMajorCv)
}

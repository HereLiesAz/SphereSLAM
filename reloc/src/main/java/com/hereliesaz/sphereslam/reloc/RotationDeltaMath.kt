package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.math.RotationMath

/**
 * Pure quaternion / rotation-matrix math for a gyro rotation bridge — the part that is easy to get
 * subtly wrong, split out so it is unit-testable without a device's sensors.
 *
 * Quaternions are `FloatArray(4) = [x, y, z, w]`. Rotation matrices are `FloatArray(9)`, row-major
 * (`m[row*3 + col]`) — these are pure 3×3 rotations; a consumer folds a result back into its own
 * column-major 4×4 pose when bridging.
 *
 * Every function delegates to the supported `:sphereslam`
 * [com.hereliesaz.sphereslam.math.RotationMath], which non-`:reloc` consumers should prefer.
 */
@ExperimentalSphereSlamRelocApi
object RotationDeltaMath {

    /** Identity quaternion (no rotation). */
    val IDENTITY_QUATERNION = floatArrayOf(0f, 0f, 0f, 1f)

    /** Hamilton product `a · b` — the result rotates first by `b`, then by `a`. */
    fun multiplyQuaternions(a: FloatArray, b: FloatArray): FloatArray = RotationMath.multiplyQuaternions(a, b)

    /** Inverse of a UNIT quaternion — its conjugate. Normalize first if unsure. */
    fun conjugate(q: FloatArray): FloatArray = RotationMath.conjugate(q)

    /** Renormalize [q] to unit length; returns identity for a (near-)zero quaternion. */
    fun normalize(q: FloatArray): FloatArray = RotationMath.normalize(q)

    /** Unit quaternion [q] → row-major 3×3 rotation matrix, `v' = R·v`. */
    fun toRotationMatrix3x3(q: FloatArray): FloatArray = RotationMath.toRotationMatrix3x3(q)

    /**
     * Row-major 3×3 rotation about the shared camera/sensor optical (Z) axis by [degrees]
     * (counter-clockwise). For a `Surface.ROTATION_*` angle this is Android's body-to-display remap;
     * see [RotationMath.rotationAboutZ].
     */
    fun rotationAboutZ(degrees: Int): FloatArray = RotationMath.rotationAboutZ(degrees)

    /** `a · b` for row-major 3×3 matrices. */
    fun multiplyMat3(a: FloatArray, b: FloatArray): FloatArray = RotationMath.multiplyMat3(a, b)

    /** Transpose (== inverse, for a rotation) of a row-major 3×3 matrix. */
    fun transposeMat3(m: FloatArray): FloatArray = RotationMath.transposeMat3(m)

    /** `m · v` for a row-major 3×3 matrix and a 3-vector. */
    fun multiplyMat3Vec3(m: FloatArray, v: FloatArray): FloatArray = RotationMath.multiplyMat3Vec3(m, v)

    /**
     * The camera-space rotation delta between two absolute device orientations, as a row-major 3×3:
     * `ΔR = Rₜᵀ · R₀` expressed so it can rotate a camera-from-world pose's basis to hold the camera
     * centre through a pure rotation. [from] and [to] are unit quaternions `[x, y, z, w]`.
     */
    fun cameraRotationDelta(from: FloatArray, to: FloatArray): FloatArray = RotationMath.cameraRotationDelta(from, to)

    /**
     * Left-multiply a column-major camera-from-map [view] by the row-major 3×3 [delta]:
     * `[ΔR·R | ΔR·t]`, holding the camera centre `−Rᵀt` fixed (a pure rotation in place).
     */
    fun rotateAboutCameraCentre(view: FloatArray, delta: FloatArray): FloatArray =
        RotationMath.rotateAboutCameraCentre(view, delta)
}

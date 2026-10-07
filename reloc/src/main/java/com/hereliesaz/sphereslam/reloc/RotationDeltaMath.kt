package com.hereliesaz.sphereslam.reloc

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure quaternion / rotation-matrix math for a gyro rotation bridge — the part that is easy to get
 * subtly wrong, split out so it is unit-testable without a device's sensors.
 *
 * Quaternions are `FloatArray(4) = [x, y, z, w]`. Rotation matrices are `FloatArray(9)`, row-major
 * (`m[row*3 + col]`) — these are pure 3×3 rotations; a consumer folds a result back into its own
 * column-major 4×4 pose when bridging.
 */
object RotationDeltaMath {

    /** Identity quaternion (no rotation). */
    val IDENTITY_QUATERNION = floatArrayOf(0f, 0f, 0f, 1f)

    /** Hamilton product `a · b` — the result rotates first by `b`, then by `a`. */
    fun multiplyQuaternions(a: FloatArray, b: FloatArray): FloatArray {
        val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]
        val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
        return floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz,
        )
    }

    /** Inverse of a UNIT quaternion — its conjugate. Normalize first if unsure. */
    fun conjugate(q: FloatArray): FloatArray = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    /** Renormalize [q] to unit length; returns identity for a (near-)zero quaternion. */
    fun normalize(q: FloatArray): FloatArray {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (n < 1e-9f) return IDENTITY_QUATERNION.copyOf()
        return floatArrayOf(q[0] / n, q[1] / n, q[2] / n, q[3] / n)
    }

    /** Unit quaternion [q] → row-major 3×3 rotation matrix, `v' = R·v`. */
    fun toRotationMatrix3x3(q: FloatArray): FloatArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return floatArrayOf(
            1f - 2f * (yy + zz), 2f * (xy - wz), 2f * (xz + wy),
            2f * (xy + wz), 1f - 2f * (xx + zz), 2f * (yz - wx),
            2f * (xz - wy), 2f * (yz + wx), 1f - 2f * (xx + yy),
        )
    }

    /** `a · b` for row-major 3×3 matrices. */
    fun multiplyMat3(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (r in 0..2) for (c in 0..2) {
            var sum = 0f
            for (k in 0..2) sum += a[r * 3 + k] * b[k * 3 + c]
            out[r * 3 + c] = sum
        }
        return out
    }

    /** Transpose (== inverse, for a rotation) of a row-major 3×3 matrix. */
    fun transposeMat3(m: FloatArray): FloatArray = floatArrayOf(
        m[0], m[3], m[6],
        m[1], m[4], m[7],
        m[2], m[5], m[8],
    )

    /** `m · v` for a row-major 3×3 matrix and a 3-vector. */
    fun multiplyMat3Vec3(m: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    /**
     * The camera-space rotation delta between two absolute device orientations, as a row-major 3×3:
     * `ΔR = Rₜᵀ · R₀` expressed so it can rotate a camera-from-world pose's basis to hold the camera
     * centre through a pure rotation. [from] and [to] are unit quaternions `[x, y, z, w]`.
     */
    fun cameraRotationDelta(from: FloatArray, to: FloatArray): FloatArray {
        val r0 = toRotationMatrix3x3(normalize(from))
        val rt = toRotationMatrix3x3(normalize(to))
        return multiplyMat3(transposeMat3(rt), r0)
    }

    /**
     * Rotate a column-major 4×4 camera-from-world view matrix by a camera-space rotation delta
     * ([deltaCameraRowMajor], row-major 3×3 from [cameraRotationDelta]), keeping the camera centre
     * fixed: `R' = ΔR·R`, `t' = ΔR·t`. Use it to carry a known pose through a pure rotation (an IMU or
     * attitude bridge). It recovers rotation only — no new translation.
     *
     * @param viewMatrix column-major length 16.
     * @param deltaCameraRowMajor row-major length 9.
     * @return the rotated view, column-major length 16.
     */
    fun rotateViewKeepingCameraCenter(viewMatrix: FloatArray, deltaCameraRowMajor: FloatArray): FloatArray {
        require(viewMatrix.size == 16) { "view matrix must be length 16" }
        require(deltaCameraRowMajor.size == 9) { "delta must be a row-major 3x3 (length 9)" }
        val rotation = FloatArray(9)
        for (row in 0..2) for (col in 0..2) rotation[row * 3 + col] = viewMatrix[col * 4 + row]
        val translation = floatArrayOf(viewMatrix[12], viewMatrix[13], viewMatrix[14])
        val rotatedR = multiplyMat3(deltaCameraRowMajor, rotation)
        val rotatedT = multiplyMat3Vec3(deltaCameraRowMajor, translation)
        val out = FloatArray(16)
        for (row in 0..2) for (col in 0..2) out[col * 4 + row] = rotatedR[row * 3 + col]
        out[12] = rotatedT[0]
        out[13] = rotatedT[1]
        out[14] = rotatedT[2]
        out[15] = 1f
        return out
    }
}

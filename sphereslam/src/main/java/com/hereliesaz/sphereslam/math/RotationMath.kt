package com.hereliesaz.sphereslam.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure quaternion / 3×3 rotation math for gyro bridging, attitude prediction and display remaps —
 * the part that is easy to get subtly wrong, kept free of Android types so it is unit-testable on
 * the JVM.
 *
 * Conventions:
 * - Quaternions are `FloatArray(4) = [x, y, z, w]` (Android's rotation-vector order).
 * - 3×3 rotations are `FloatArray(9)`, **row-major** (`m[row * 3 + col]`), acting as `v' = R·v`.
 * - 4×4 poses are `FloatArray(16)`, **column-major** (OpenGL layout), as everywhere else in
 *   SphereSLAM; [rotateAboutCameraCentre] is the one place a 3×3 is folded back into one.
 *
 * The experimental `:reloc` `RotationDeltaMath` delegates here.
 */
object RotationMath {

    /** Identity quaternion (no rotation). Returns a fresh copy on every read. */
    val IDENTITY_QUATERNION: FloatArray get() = floatArrayOf(0f, 0f, 0f, 1f)

    /** Row-major 3×3 identity. Returns a fresh copy on every read. */
    val IDENTITY_3X3: FloatArray get() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

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

    /**
     * Renormalize [q] to unit length; returns identity for a (near-)zero quaternion. Sensor-fusion
     * quaternions drift off unit length by a tiny amount over many samples, so apply this once where
     * each raw sample is ingested.
     */
    fun normalize(q: FloatArray): FloatArray {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (n < 1e-9f) return IDENTITY_QUATERNION
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

    /**
     * Row-major 3×3 rotation about the shared camera/sensor optical (Z) axis by [degrees]
     * (counter-clockwise, `v' = R·v`).
     *
     * `rotationAboutZ(displayDeg)` for a `Surface.ROTATION_*` angle in degrees is exactly Android's
     * body-to-display remap (`SensorManager.remapCoordinateSystem` per rotation; for 90:
     * `x' = −y, y' = x`). See `com.hereliesaz.sphereslam.attitude.DeviceCameraRotation`, which pins
     * that against an independent model of the remap.
     */
    fun rotationAboutZ(degrees: Int): FloatArray {
        val quarter = ((degrees % 360) + 360) % 360
        // Exact values for quarter turns, so a remap never carries float noise from cos/sin.
        when (quarter) {
            0 -> return IDENTITY_3X3
            90 -> return floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            180 -> return floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
            270 -> return floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        }
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return floatArrayOf(
            c, -s, 0f,
            s, c, 0f,
            0f, 0f, 1f,
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

    /** Conjugate a row-major 3×3 [r] by the rotation [basis]: `B · R · Bᵀ`. */
    fun conjugateMat3(basis: FloatArray, r: FloatArray): FloatArray =
        multiplyMat3(multiplyMat3(basis, r), transposeMat3(basis))

    /**
     * The rotation delta between two absolute device orientations, as a row-major 3×3:
     * `ΔR = Rₜᵀ · R₀` (equivalently `R(conj(qₜ) · q₀)`), expressed in the device body axes so it
     * can rotate a camera-from-world pose's basis to hold the camera centre through a pure rotation.
     * [from] and [to] are quaternions `[x, y, z, w]` (normalized here).
     */
    fun cameraRotationDelta(from: FloatArray, to: FloatArray): FloatArray {
        val r0 = toRotationMatrix3x3(normalize(from))
        val rt = toRotationMatrix3x3(normalize(to))
        return multiplyMat3(transposeMat3(rt), r0)
    }

    /**
     * Left-multiply a column-major camera-from-world [view] by the row-major 3×3 [delta]:
     * `[ΔR·R | ΔR·t]`. The camera centre `−Rᵀt` is unchanged, so this is a pure rotation of the
     * camera in place — the correct way to carry a held pose through a gyro-measured rotation
     * (rotating `R` while holding `t` would move the camera centre).
     */
    fun rotateAboutCameraCentre(view: FloatArray, delta: FloatArray): FloatArray {
        require(view.size == 16) { "view must be column-major length 16" }
        require(delta.size == 9) { "delta must be a row-major 3x3 (length 9)" }
        val out = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 3) {
            var sum = 0f
            for (k in 0 until 3) sum += delta[row * 3 + k] * view[col * 4 + k]
            out[col * 4 + row] = sum
        }
        out[3] = view[3]; out[7] = view[7]; out[11] = view[11]; out[15] = view[15]
        return out
    }

    /** Total rotation angle of a row-major 3×3 rotation, in degrees `[0, 180]`, from its trace. */
    fun rotationAngleDegrees(r: FloatArray): Float {
        val cosA = ((r[0] + r[4] + r[8] - 1f) / 2f).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cosA.toDouble())).toFloat()
    }
}

package com.hereliesaz.sphereslam.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Pure column-major 4×4 (OpenGL / ARCore layout, `m[col * 4 + row]`) helpers for rigid and
 * similarity transforms, plus the OpenCV ↔ OpenGL camera-convention flips. No Android dependencies.
 *
 * Camera conventions:
 * - **OpenGL eye frame**: x right, y up, the camera looks down −z (SphereSLAM's render poses).
 * - **OpenCV camera frame**: x right, y down, the camera looks down +z (PnP / KPM output).
 *
 * Both are related by `D = diag(1, −1, −1)`, which is its own inverse, so one flip converts either
 * way for a camera-from-world view: `view_cv = D · view_gl`.
 *
 * The experimental `:reloc` `PoseMath` delegates here; the supported `:sphereslam` pose code
 * ([com.hereliesaz.sphereslam.SphereSlamPoseMath], [com.hereliesaz.sphereslam.OverlayPlacement])
 * uses it too.
 */
object RigidMath {

    /** A fresh column-major 4×4 identity. */
    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** Column-major 4×4 product `a · b` (applies [b] first, then [a]). */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
            r[col * 4 + row] = sum
        }
        return r
    }

    /** Inverse of a rigid transform `[R|t]` (rotation + translation, no scale): `[Rᵀ | −Rᵀt]`. */
    fun rigidInverse(m: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (i in 0 until 3) for (j in 0 until 3) r[j * 4 + i] = m[i * 4 + j]
        val tx = m[12]; val ty = m[13]; val tz = m[14]
        r[12] = -(r[0] * tx + r[4] * ty + r[8] * tz)
        r[13] = -(r[1] * tx + r[5] * ty + r[9] * tz)
        r[14] = -(r[2] * tx + r[6] * ty + r[10] * tz)
        r[15] = 1f
        return r
    }

    /**
     * Uniform scale baked into a similarity transform `[sR|t]`, recovered as the norm of its first
     * column. Returns 1 for a rigid matrix.
     */
    fun scaleOf(m: FloatArray): Float = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])

    /**
     * Inverse of a SIMILARITY transform `[sR|t]` with uniform scale: `[Rᵀ/s | −Rᵀt/s]`.
     *
     * Use this, not [rigidInverse], for any matrix that may carry a scale: [rigidInverse] transposes
     * the linear part, yielding `sRᵀ` where `Rᵀ/s` was meant — an error of `s²`.
     *
     * **Exactness caveat.** For a matrix scaled only in-plane (`A = R·diag(s, s, 1)`, as
     * `Matrix.scaleM(.., s, s, 1f)` builds) this is exact in rows 0 and 1 and off by `1/s²` in row 2
     * (the plane normal) and in the z translation. For a genuinely uniform `diag(s, s, s)` it is
     * exact throughout.
     *
     * @throws IllegalArgumentException for a (near-)zero scale.
     */
    fun similarityInverse(m: FloatArray): FloatArray {
        val s = scaleOf(m)
        require(s > 1e-9f) { "similarityInverse: degenerate scale $s" }
        val inv = 1f / s
        val r = FloatArray(16)
        for (i in 0 until 3) for (j in 0 until 3) r[j * 4 + i] = m[i * 4 + j] * inv * inv
        val tx = m[12]; val ty = m[13]; val tz = m[14]
        r[12] = -(r[0] * tx + r[4] * ty + r[8] * tz)
        r[13] = -(r[1] * tx + r[5] * ty + r[9] * tz)
        r[14] = -(r[2] * tx + r[6] * ty + r[10] * tz)
        r[15] = 1f
        return r
    }

    /** The translation column `(x, y, z)` of a column-major matrix. */
    fun translationOf(m: FloatArray): FloatArray = floatArrayOf(m[12], m[13], m[14])

    /** Euclidean length of the translation column, in the matrix's own units. */
    fun translationNorm(m: FloatArray): Float = sqrt(m[12] * m[12] + m[13] * m[13] + m[14] * m[14])

    /**
     * World-space camera centre `C = −Rᵀt` of a rigid camera-from-world [view] — the quantity that
     * stays put through a pure camera rotation (unlike the translation column).
     */
    fun cameraCentre(view: FloatArray): FloatArray {
        val tx = view[12]; val ty = view[13]; val tz = view[14]
        return floatArrayOf(
            -(view[0] * tx + view[1] * ty + view[2] * tz),
            -(view[4] * tx + view[5] * ty + view[6] * tz),
            -(view[8] * tx + view[9] * ty + view[10] * tz),
        )
    }

    /**
     * Rotation magnitude of a rigid transform in degrees, in `[0, 180]`. Uses `|w|` because `q` and
     * `−q` are the same rotation.
     */
    fun rotationAngleDeg(m: FloatArray): Float {
        val w = abs(matrixToQuaternion(m)[3]).coerceIn(0f, 1f)
        return Math.toDegrees(2.0 * acos(w.toDouble())).toFloat()
    }

    /** Rotation angle in degrees `[0, 180]` between the rotation parts of two rigid transforms. */
    fun rotationDeltaDeg(a: FloatArray, b: FloatArray): Float =
        rotationAngleDeg(multiply(a, rigidInverse(b)))

    /** Extract a unit quaternion `(x, y, z, w)` from the rotation part of a column-major matrix. */
    fun matrixToQuaternion(m: FloatArray): FloatArray {
        val m00 = m[0]; val m10 = m[1]; val m20 = m[2]
        val m01 = m[4]; val m11 = m[5]; val m21 = m[6]
        val m02 = m[8]; val m12 = m[9]; val m22 = m[10]
        val trace = m00 + m11 + m22
        val q = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            q[3] = 0.25f * s; q[0] = (m21 - m12) / s; q[1] = (m02 - m20) / s; q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            q[3] = (m21 - m12) / s; q[0] = 0.25f * s; q[1] = (m01 + m10) / s; q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            q[3] = (m02 - m20) / s; q[0] = (m01 + m10) / s; q[1] = 0.25f * s; q[2] = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            q[3] = (m10 - m01) / s; q[0] = (m02 + m20) / s; q[1] = (m12 + m21) / s; q[2] = 0.25f * s
        }
        return normalizeQuat(q)
    }

    /** Normalize a quaternion `(x, y, z, w)` to unit length; returns identity for a zero quaternion. */
    fun normalizeQuat(q: FloatArray): FloatArray {
        val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return if (l == 0f) floatArrayOf(0f, 0f, 0f, 1f) else floatArrayOf(q[0] / l, q[1] / l, q[2] / l, q[3] / l)
    }

    /** Build a column-major matrix from a unit quaternion `(x, y, z, w)` and a translation `(x, y, z)`. */
    fun fromQuaternionTranslation(q: FloatArray, t: FloatArray): FloatArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val m = FloatArray(16)
        m[0] = 1 - 2 * (y * y + z * z); m[1] = 2 * (x * y + z * w); m[2] = 2 * (x * z - y * w)
        m[4] = 2 * (x * y - z * w); m[5] = 1 - 2 * (x * x + z * z); m[6] = 2 * (y * z + x * w)
        m[8] = 2 * (x * z + y * w); m[9] = 2 * (y * z - x * w); m[10] = 1 - 2 * (x * x + y * y)
        m[12] = t[0]; m[13] = t[1]; m[14] = t[2]; m[15] = 1f
        return m
    }

    /** Normalized lerp between unit quaternions, hemisphere-corrected. [t] in `[0, 1]`. */
    fun nlerpQuat(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        val s = if (dot < 0f) -1f else 1f
        return normalizeQuat(
            floatArrayOf(
                a[0] + (b[0] * s - a[0]) * t,
                a[1] + (b[1] * s - a[1]) * t,
                a[2] + (b[2] * s - a[2]) * t,
                a[3] + (b[3] * s - a[3]) * t,
            ),
        )
    }

    /** Component-wise lerp of two 3-vectors. [t] in `[0, 1]`. */
    fun lerp(a: FloatArray, b: FloatArray, t: Float): FloatArray =
        floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)

    /**
     * Convert a column-major OpenGL camera-from-world view (camera looks −z) to the OpenCV convention
     * (camera looks +z) by negating the camera Y and Z rows: `D · view`, `D = diag(1, −1, −1)`.
     * The same flip converts back ([cvViewToGl]).
     */
    fun glViewToCv(view: FloatArray): FloatArray {
        require(view.size == 16) { "view must be column-major length 16" }
        val v = view.copyOf()
        for (col in 0 until 4) {
            v[col * 4 + 1] = -v[col * 4 + 1]
            v[col * 4 + 2] = -v[col * 4 + 2]
        }
        return v
    }

    /** Inverse of [glViewToCv] (the flip is an involution): OpenCV view → OpenGL view, column-major. */
    fun cvViewToGl(view: FloatArray): FloatArray = glViewToCv(view)

    /** Row-major 4×4 → column-major 16 (a transpose of storage for the same transform). */
    fun rowMajorToColumnMajor(rowMajor: FloatArray): FloatArray {
        require(rowMajor.size == 16) { "matrix must be length 16" }
        val out = FloatArray(16)
        for (row in 0 until 4) for (col in 0 until 4) out[col * 4 + row] = rowMajor[row * 4 + col]
        return out
    }

    /** Column-major 16 → row-major 4×4 (a transpose of storage for the same transform). */
    fun columnMajorToRowMajor(columnMajor: FloatArray): FloatArray = rowMajorToColumnMajor(columnMajor)

    /**
     * OpenCV camera-from-world (row-major 4×4; x right, y down, z forward) → OpenGL camera-from-world
     * (column-major 16; x right, y up, looking down −z): `diag(1, −1, −1) · M`, then transpose storage.
     */
    fun cvRowMajorToGlColumnMajor(rowMajorCv: FloatArray): FloatArray =
        glViewToCv(rowMajorToColumnMajor(rowMajorCv))
}

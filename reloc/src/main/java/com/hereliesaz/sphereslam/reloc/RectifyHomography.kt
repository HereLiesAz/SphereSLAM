package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.math.RigidMath
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Plane-guided rectification for oblique relocalization: the fingerprint's marks lie on a known plane
 * and the tracker supplies a current pose, so the oblique-vs-frontal distortion between the
 * fingerprint's capture image and the live image is a plane-induced homography that can be cancelled
 * before matching. Warp the live frame into the reference frame ([warpCurrentToReference]), detect and
 * match there, then map the matched pixels back with [Result.currentFromReference] (RANSAC filters
 * the rest).
 *
 * Ported from GraffitiXR's native `MobileGS::computeRectifyHomography` (pure Kotlin; no OpenCV or
 * JNI). The native pass applies it only above ~25° of obliquity ([DEFAULT_MIN_OBLIQUITY_DEG]).
 */
@ExperimentalSphereSlamRelocApi
object RectifyHomography {

    /** Obliquity above which GraffitiXR's native reloc rectifies. */
    const val DEFAULT_MIN_OBLIQUITY_DEG = 25.0

    /**
     * @property currentFromReference row-major 3×3 (`h22 = 1`) mapping reference-image pixels to
     *   current-image pixels.
     * @property referenceFromCurrent its inverse (normalized the same way).
     * @property obliquityDeg angle between the plane normal and the current optical axis.
     */
    class Result(
        currentFromReference: DoubleArray,
        referenceFromCurrent: DoubleArray,
        val obliquityDeg: Double,
    ) {
        private val hCr = currentFromReference.copyOf()
        private val hRc = referenceFromCurrent.copyOf()
        val currentFromReference: DoubleArray get() = hCr.copyOf()
        val referenceFromCurrent: DoubleArray get() = hRc.copyOf()

        /** Map a reference-image pixel into the current image, or null at infinity. */
        fun referenceToCurrent(u: Double, v: Double): DoubleArray? = apply(hCr, u, v)

        /** Map a current-image pixel into the reference image, or null at infinity. */
        fun currentToReference(u: Double, v: Double): DoubleArray? = apply(hRc, u, v)
    }

    /**
     * @param cameraFromWorldCurrent column-major OpenGL view of the live frame.
     * @param cameraFromWorldReference column-major OpenGL view the fingerprint was captured with (same
     *   world as [cameraFromWorldCurrent]).
     * @param referencePoints flat `[x, y, z, …]` fingerprint marks in the **reference camera's OpenCV
     *   frame** (+z forward), as depth/plane-built fingerprints store them; at least [minPoints].
     * @param fx / [fy] / [cx] / [cy] reference intrinsics.
     * @param currentIntrinsics `[fx, fy, cx, cy]` of the live image, or null when it shares the
     *   reference intrinsics (the native assumption).
     * @return null for too few points, a degenerate plane, a plane through the reference camera, or a
     *   singular homography.
     */
    fun compute(
        cameraFromWorldCurrent: FloatArray,
        cameraFromWorldReference: FloatArray,
        referencePoints: FloatArray,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        currentIntrinsics: DoubleArray? = null,
        minPoints: Int = 12,
    ): Result? {
        require(cameraFromWorldCurrent.size == 16 && cameraFromWorldReference.size == 16)
        require(currentIntrinsics == null || currentIntrinsics.size == 4)
        if (referencePoints.size / 3 < minPoints || fx <= 0.0 || fy <= 0.0) return null
        val plane = WallPlane.fit(referencePoints, minPoints) ?: return null
        val n0 = plane.normal.map { it.toDouble() }.toDoubleArray()
        val c = plane.point
        var d = n0[0] * c[0] + n0[1] * c[1] + n0[2] * c[2]
        val n = if (d < 0) doubleArrayOf(-n0[0], -n0[1], -n0[2]).also { d = -d } else n0
        if (d < 1e-3) return null // plane n·X = d, d > 0: in front of the reference camera

        // Relative pose reference camera -> current camera (both GL): T = V_cur · V_ref⁻¹, then to
        // OpenCV with C = diag(1, -1, -1): R = C·R_gl·C, t = C·t_gl.
        val t4 = RigidMath.multiply(cameraFromWorldCurrent, RigidMath.rigidInverse(cameraFromWorldReference))
        val sign = doubleArrayOf(1.0, -1.0, -1.0)
        // Row-major R_cv[i][j] = s_i·s_j·R_gl[i][j] (R_gl[i][j] = t4[j*4+i]), t_cv[i] = s_i·t_gl[i].
        val r = DoubleArray(9) { k -> sign[k / 3] * sign[k % 3] * t4[(k % 3) * 4 + k / 3] }
        val t = DoubleArray(3) { i -> sign[i] * t4[12 + i] }
        if (r.any { !it.isFinite() } || t.any { !it.isFinite() }) return null

        // Obliquity: plane normal in the current camera frame vs the optical (+z) axis.
        val nCur = mul3(r, n)
        val nCurLen = sqrt(nCur[0] * nCur[0] + nCur[1] * nCur[1] + nCur[2] * nCur[2])
        val obliquity = Math.toDegrees(acos(minOf(1.0, abs(nCur[2]) / (nCurLen + 1e-9))))

        // For X on the plane nᵀX = d: R·X + t = (R + t·nᵀ/d)·X, so H = K_cur (R + t nᵀ / d) K_ref⁻¹.
        // (The native source used R − t nᵀ/d — the formula for the nᵀX + d = 0 convention — after
        // flipping n to make d positive, so its homography was wrong whenever the cameras translated.)
        val m = DoubleArray(9) { i -> r[i] + t[i / 3] * n[i % 3] / d }
        val kRef = doubleArrayOf(fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0)
        val kc = currentIntrinsics ?: doubleArrayOf(fx, fy, cx, cy)
        val kCur = doubleArrayOf(kc[0], 0.0, kc[2], 0.0, kc[1], kc[3], 0.0, 0.0, 1.0)
        val kRefInv = invert3(kRef) ?: return null
        val h = mulMat3(mulMat3(kCur, m), kRefInv)
        val hNorm = normalize(h) ?: return null
        val hInv = invert3(hNorm)?.let { normalize(it) } ?: return null
        return Result(hNorm, hInv, obliquity)
    }

    /**
     * The live luma resampled into the reference image's pixel frame (what OpenCV's
     * `warpPerspective(current, out, referenceFromCurrent, size)` produces): each output pixel `p`
     * samples the current image at `currentFromReference · p`, bilinear, 0 outside.
     */
    fun warpCurrentToReference(
        luma: ByteArray,
        width: Int,
        height: Int,
        result: Result,
        outWidth: Int = width,
        outHeight: Int = height,
    ): ByteArray {
        require(width > 0 && height > 0 && luma.size >= width * height && outWidth > 0 && outHeight > 0)
        val h = result.currentFromReference
        val out = ByteArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val w = h[6] * x + h[7] * y + h[8]
                if (abs(w) < 1e-12) continue
                val u = (h[0] * x + h[1] * y + h[2]) / w
                val v = (h[3] * x + h[4] * y + h[5]) / w
                out[y * outWidth + x] = bilinear(luma, width, height, u, v).toByte()
            }
        }
        return out
    }

    private fun bilinear(luma: ByteArray, width: Int, height: Int, u: Double, v: Double): Int {
        if (!u.isFinite() || !v.isFinite()) return 0
        val x0 = floor(u).toInt(); val y0 = floor(v).toInt()
        val fx = u - x0; val fy = v - y0
        fun px(x: Int, y: Int): Double =
            if (x < 0 || y < 0 || x >= width || y >= height) 0.0 else (luma[y * width + x].toInt() and 0xff).toDouble()
        val top = px(x0, y0) * (1 - fx) + px(x0 + 1, y0) * fx
        val bottom = px(x0, y0 + 1) * (1 - fx) + px(x0 + 1, y0 + 1) * fx
        return (top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
    }

    private fun apply(h: DoubleArray, u: Double, v: Double): DoubleArray? {
        val w = h[6] * u + h[7] * v + h[8]
        if (abs(w) < 1e-12) return null
        return doubleArrayOf((h[0] * u + h[1] * v + h[2]) / w, (h[3] * u + h[4] * v + h[5]) / w)
    }

    private fun normalize(h: DoubleArray): DoubleArray? {
        if (abs(h[8]) < 1e-9 || h.any { !it.isFinite() }) return null
        return DoubleArray(9) { h[it] / h[8] }
    }

    private fun mul3(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    private fun mulMat3(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    private fun invert3(m: DoubleArray): DoubleArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        if (abs(det) < 1e-15 || !det.isFinite()) return null
        val inv = 1.0 / det
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * inv, (m[2] * m[7] - m[1] * m[8]) * inv, (m[1] * m[5] - m[2] * m[4]) * inv,
            (m[5] * m[6] - m[3] * m[8]) * inv, (m[0] * m[8] - m[2] * m[6]) * inv, (m[2] * m[3] - m[0] * m[5]) * inv,
            (m[3] * m[7] - m[4] * m[6]) * inv, (m[1] * m[6] - m[0] * m[7]) * inv, (m[0] * m[4] - m[1] * m[3]) * inv,
        )
    }
}

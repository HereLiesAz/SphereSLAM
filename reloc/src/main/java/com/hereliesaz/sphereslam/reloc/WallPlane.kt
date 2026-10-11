package com.hereliesaz.sphereslam.reloc

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A plane through [point] with unit [normal], in whatever frame the caller's points live in (for a
 * fingerprint, its object frame). [fit] recovers it from a point set by PCA: centroid plus the
 * smallest-variance axis — the plane fit GraffitiXR's native `MobileGS` runs over the fingerprint's
 * 3D marks before rectifying or growing its feature map.
 */
@ExperimentalSphereSlamRelocApi
class WallPlane(point: FloatArray, normal: FloatArray) {
    private val p = point.copyOf()
    private val n: FloatArray

    init {
        require(point.size == 3 && normal.size == 3) { "point and normal must be 3-vectors" }
        require(point.all { it.isFinite() } && normal.all { it.isFinite() }) { "plane must be finite" }
        val len = sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2])
        require(len > 1e-9f) { "normal must be non-zero" }
        n = floatArrayOf(normal[0] / len, normal[1] / len, normal[2] / len)
    }

    /** A point on the plane (the centroid for a [fit] plane), fresh copy. */
    val point: FloatArray get() = p.copyOf()

    /** Unit normal, fresh copy (sign is arbitrary for a [fit] plane). */
    val normal: FloatArray get() = n.copyOf()

    /** Signed distance of ([x], [y], [z]) from the plane along [normal]. */
    fun signedDistance(x: Float, y: Float, z: Float): Float =
        n[0] * (x - p[0]) + n[1] * (y - p[1]) + n[2] * (z - p[2])

    /**
     * This plane expressed in another frame by a column-major rigid transform [targetFromSource]
     * (e.g. a camera-from-fingerprint view): the point is transformed, the normal rotated.
     */
    fun transformedBy(targetFromSource: FloatArray): WallPlane {
        require(targetFromSource.size == 16)
        val m = targetFromSource
        return WallPlane(
            floatArrayOf(
                m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
                m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
                m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14],
            ),
            floatArrayOf(
                m[0] * n[0] + m[4] * n[1] + m[8] * n[2],
                m[1] * n[0] + m[5] * n[1] + m[9] * n[2],
                m[2] * n[0] + m[6] * n[1] + m[10] * n[2],
            ),
        )
    }

    companion object {
        /**
         * Least-squares plane through flat `[x0, y0, z0, x1, …]` [points]: centroid and the eigenvector
         * of the smallest covariance eigenvalue. Null for fewer than [minPoints] finite points or a
         * degenerate (collinear / coincident) set.
         */
        fun fit(points: FloatArray, minPoints: Int = 3): WallPlane? {
            require(points.size % 3 == 0) { "points must be xyz triplets" }
            require(minPoints >= 3)
            var count = 0
            var sx = 0.0; var sy = 0.0; var sz = 0.0
            for (i in 0 until points.size / 3) {
                val x = points[3 * i]; val y = points[3 * i + 1]; val z = points[3 * i + 2]
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
                sx += x; sy += y; sz += z; count++
            }
            if (count < minPoints) return null
            val c = doubleArrayOf(sx / count, sy / count, sz / count)
            val cov = DoubleArray(9)
            for (i in 0 until points.size / 3) {
                val x = points[3 * i]; val y = points[3 * i + 1]; val z = points[3 * i + 2]
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
                val d = doubleArrayOf(x - c[0], y - c[1], z - c[2])
                for (r in 0..2) for (k in 0..2) cov[r * 3 + k] += d[r] * d[k]
            }
            val (values, vectors) = symmetricEigen3(cov)
            val order = (0..2).sortedBy { values[it] }
            // A line or a point has two (near-)zero eigenvalues: no unique plane.
            val scale = values.maxOrNull() ?: 0.0
            if (scale <= 0.0 || values[order[1]] <= 1e-9 * scale) return null
            val k = order[0]
            val normal = floatArrayOf(vectors[k].toFloat(), vectors[3 + k].toFloat(), vectors[6 + k].toFloat())
            return WallPlane(floatArrayOf(c[0].toFloat(), c[1].toFloat(), c[2].toFloat()), normal)
        }

        /**
         * Eigen-decomposition of a symmetric 3×3 (row-major) by cyclic Jacobi: eigenvalues, and the
         * eigenvectors as the columns of a row-major 3×3.
         */
        internal fun symmetricEigen3(symmetric: DoubleArray): Pair<DoubleArray, DoubleArray> {
            val a = symmetric.copyOf()
            val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            repeat(60) {
                var p = 0; var q = 1; var max = 0.0
                for (i in 0 until 3) for (j in i + 1 until 3) {
                    val m = abs(a[i * 3 + j])
                    if (m > max) { max = m; p = i; q = j }
                }
                if (max < 1e-18) return@repeat
                val phi = 0.5 * atan2(2.0 * a[p * 3 + q], a[q * 3 + q] - a[p * 3 + p])
                val cs = cos(phi); val sn = sin(phi)
                for (k in 0 until 3) {
                    val akp = a[k * 3 + p]; val akq = a[k * 3 + q]
                    a[k * 3 + p] = cs * akp - sn * akq
                    a[k * 3 + q] = sn * akp + cs * akq
                }
                for (k in 0 until 3) {
                    val apk = a[p * 3 + k]; val aqk = a[q * 3 + k]
                    a[p * 3 + k] = cs * apk - sn * aqk
                    a[q * 3 + k] = sn * apk + cs * aqk
                }
                for (k in 0 until 3) {
                    val vkp = v[k * 3 + p]; val vkq = v[k * 3 + q]
                    v[k * 3 + p] = cs * vkp - sn * vkq
                    v[k * 3 + q] = sn * vkp + cs * vkq
                }
            }
            return doubleArrayOf(a[0], a[4], a[8]) to v
        }
    }
}

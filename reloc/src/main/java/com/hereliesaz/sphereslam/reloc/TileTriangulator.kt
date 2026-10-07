package com.hereliesaz.sphereslam.reloc

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Multi-view triangulation: given several views of the *same* world point — each a known camera pose
 * and where the point landed in that image — recover the point's 3D position, and report how well the
 * views agree ([Triangulation.reprojectionRmsPx]) and how well they constrain depth
 * ([Triangulation.minParallaxDeg]). Those two numbers are the raw inputs a geometric gate weighs
 * before a tile is admitted (that gate lands in 0.20); this stage only measures, it does not judge.
 *
 * Pure math — linear (DLT) triangulation with a hand-rolled 4×4 symmetric eigensolver, Double
 * internally for conditioning — so it runs and is unit-tested without OpenCV or a device.
 *
 * **Conventions.** A view's pose is `cameraFromWorld`, a **row-major** 4×4 rigid transform
 * (`[R|t]` in the upper three rows, world → camera), matching [RelocResult]'s row-major layout.
 * Intrinsics are a pinhole `fx, fy, cx, cy`; pixels are `(u, v)` with `u` rightward, `v` downward.
 */
object TileTriangulator {

    /**
     * One view of a world point.
     *
     * @property cameraFromWorld row-major 4×4 rigid transform, world → camera (length 16).
     * @property u observed pixel x.
     * @property v observed pixel y.
     */
    class View(val cameraFromWorld: FloatArray, val u: Float, val v: Float) {
        init { require(cameraFromWorld.size == 16) { "cameraFromWorld must be length 16" } }
    }

    /**
     * A triangulated world point and the geometry-health numbers a gate reads.
     *
     * @property point world-frame position `[x, y, z]`.
     * @property reprojectionRmsPx RMS reprojection error over the views, pixels (lower is better).
     * @property minParallaxDeg smallest parallax angle between any pair of view rays to the point,
     *   degrees — near zero means depth is poorly constrained however small the reprojection error.
     * @property views how many views were triangulated.
     */
    data class Triangulation(
        val point: FloatArray,
        val reprojectionRmsPx: Float,
        val minParallaxDeg: Float,
        val views: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Triangulation) return false
            return point.contentEquals(other.point) &&
                reprojectionRmsPx == other.reprojectionRmsPx &&
                minParallaxDeg == other.minParallaxDeg &&
                views == other.views
        }

        override fun hashCode(): Int {
            var r = point.contentHashCode()
            r = 31 * r + reprojectionRmsPx.hashCode()
            r = 31 * r + minParallaxDeg.hashCode()
            r = 31 * r + views
            return r
        }
    }

    /**
     * Triangulate one world point from two or more [views].
     *
     * @return the triangulation, or null when there are fewer than two views, the linear system is
     *   degenerate (the point solves at infinity — parallel rays / zero parallax), or the solution
     *   lands behind any contributing camera.
     */
    fun triangulate(views: List<View>, fx: Double, fy: Double, cx: Double, cy: Double): Triangulation? {
        if (views.size < 2) return null
        require(fx > 0 && fy > 0) { "focal lengths must be positive" }

        // Per view, the 3×4 projection P = K·[R|t]; two DLT rows: u·P2 − P0, v·P2 − P1.
        val projections = ArrayList<DoubleArray>(views.size) // each length 12, row-major 3×4
        val rows = ArrayList<DoubleArray>(views.size * 2)     // each length 4
        for (view in views) {
            val p = projection(view.cameraFromWorld, fx, fy, cx, cy)
            projections.add(p)
            val p0 = doubleArrayOf(p[0], p[1], p[2], p[3])
            val p1 = doubleArrayOf(p[4], p[5], p[6], p[7])
            val p2 = doubleArrayOf(p[8], p[9], p[10], p[11])
            rows.add(axpy(view.u.toDouble(), p2, p0))  // u·P2 − P0
            rows.add(axpy(view.v.toDouble(), p2, p1))  // v·P2 − P1
        }

        // Smallest right singular vector of A == smallest eigenvector of AᵀA (4×4 symmetric).
        val ata = DoubleArray(16)
        for (row in rows) {
            for (i in 0 until 4) for (j in 0 until 4) ata[i * 4 + j] += row[i] * row[j]
        }
        val x = smallestEigenvector4(ata)
        val w = x[3]
        if (abs(w) < 1e-9) return null // point at infinity: rays effectively parallel
        val px = x[0] / w; val py = x[1] / w; val pz = x[2] / w
        val pointH = doubleArrayOf(px, py, pz, 1.0)

        // Reprojection RMS, and reject a point behind any camera.
        var sumSq = 0.0
        for (i in views.indices) {
            val p = projections[i]
            val cxp = p[8] * px + p[9] * py + p[10] * pz + p[11]
            if (cxp <= 1e-9) return null // behind (or on) the camera plane
            val up = (p[0] * px + p[1] * py + p[2] * pz + p[3]) / cxp
            val vp = (p[4] * px + p[5] * py + p[6] * pz + p[7]) / cxp
            val du = up - views[i].u
            val dv = vp - views[i].v
            sumSq += du * du + dv * dv
        }
        val rms = sqrt(sumSq / views.size)

        // Min pairwise parallax: angle between the rays from each camera centre to the point.
        val centers = views.map { cameraCenter(it.cameraFromWorld) }
        var maxCos = -1.0 // the largest cosine == the smallest angle between any ray pair
        for (i in views.indices) for (j in i + 1 until views.size) {
            val di = unit(doubleArrayOf(px - centers[i][0], py - centers[i][1], pz - centers[i][2]))
            val dj = unit(doubleArrayOf(px - centers[j][0], py - centers[j][1], pz - centers[j][2]))
            val c = (di[0] * dj[0] + di[1] * dj[1] + di[2] * dj[2]).coerceIn(-1.0, 1.0)
            if (c > maxCos) maxCos = c
        }
        val minParallaxDeg = Math.toDegrees(acos(maxCos.coerceIn(-1.0, 1.0)))

        return Triangulation(
            point = floatArrayOf(px.toFloat(), py.toFloat(), pz.toFloat()),
            reprojectionRmsPx = rms.toFloat(),
            minParallaxDeg = minParallaxDeg.toFloat(),
            views = views.size,
        )
    }

    /** `K · [R|t]` → row-major 3×4 (length 12), from a row-major 4×4 `cameraFromWorld`. */
    private fun projection(cfw: FloatArray, fx: Double, fy: Double, cx: Double, cy: Double): DoubleArray {
        // rt rows 0..2 of cfw (world→camera); K = [[fx,0,cx],[0,fy,cy],[0,0,1]].
        val rt = Array(3) { r -> DoubleArray(4) { c -> cfw[r * 4 + c].toDouble() } }
        val p = DoubleArray(12)
        for (c in 0 until 4) {
            p[0 * 4 + c] = fx * rt[0][c] + cx * rt[2][c]
            p[1 * 4 + c] = fy * rt[1][c] + cy * rt[2][c]
            p[2 * 4 + c] = rt[2][c]
        }
        return p
    }

    /** World-frame camera centre `C = −Rᵀ·t` from a row-major 4×4 world→camera transform. */
    private fun cameraCenter(cfw: FloatArray): DoubleArray {
        val r = Array(3) { i -> DoubleArray(3) { j -> cfw[i * 4 + j].toDouble() } }
        val t = doubleArrayOf(cfw[3].toDouble(), cfw[7].toDouble(), cfw[11].toDouble())
        return doubleArrayOf(
            -(r[0][0] * t[0] + r[1][0] * t[1] + r[2][0] * t[2]),
            -(r[0][1] * t[0] + r[1][1] * t[1] + r[2][1] * t[2]),
            -(r[0][2] * t[0] + r[1][2] * t[1] + r[2][2] * t[2]),
        )
    }

    /** `scale·a − b` for length-4 vectors. */
    private fun axpy(scale: Double, a: DoubleArray, b: DoubleArray): DoubleArray =
        DoubleArray(4) { scale * a[it] - b[it] }

    private fun unit(v: DoubleArray): DoubleArray {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (n < 1e-12) v else doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    /**
     * Eigenvector of the smallest eigenvalue of a 4×4 symmetric matrix (row-major, length 16), by
     * cyclic Jacobi rotation. Enough precision and robustness for a well-posed DLT system without a
     * linear-algebra dependency.
     */
    private fun smallestEigenvector4(symmetric: DoubleArray): DoubleArray {
        val a = symmetric.copyOf()
        val v = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 } // identity
        repeat(50) {
            // Largest off-diagonal magnitude.
            var p = 0; var q = 1; var max = 0.0
            for (i in 0 until 4) for (j in i + 1 until 4) {
                val m = abs(a[i * 4 + j])
                if (m > max) { max = m; p = i; q = j }
            }
            if (max < 1e-15) return@repeat
            val app = a[p * 4 + p]; val aqq = a[q * 4 + q]; val apq = a[p * 4 + q]
            val phi = 0.5 * kotlin.math.atan2(2.0 * apq, aqq - app)
            val c = kotlin.math.cos(phi); val s = kotlin.math.sin(phi)
            // A = Jᵀ A J
            for (k in 0 until 4) {
                val akp = a[k * 4 + p]; val akq = a[k * 4 + q]
                a[k * 4 + p] = c * akp - s * akq
                a[k * 4 + q] = s * akp + c * akq
            }
            for (k in 0 until 4) {
                val apk = a[p * 4 + k]; val aqk = a[q * 4 + k]
                a[p * 4 + k] = c * apk - s * aqk
                a[q * 4 + k] = s * apk + c * aqk
            }
            // V = V J
            for (k in 0 until 4) {
                val vkp = v[k * 4 + p]; val vkq = v[k * 4 + q]
                v[k * 4 + p] = c * vkp - s * vkq
                v[k * 4 + q] = s * vkp + c * vkq
            }
        }
        var minIdx = 0; var minVal = a[0]
        for (i in 1 until 4) if (a[i * 4 + i] < minVal) { minVal = a[i * 4 + i]; minIdx = i }
        return doubleArrayOf(v[minIdx], v[4 + minIdx], v[8 + minIdx], v[12 + minIdx])
    }
}

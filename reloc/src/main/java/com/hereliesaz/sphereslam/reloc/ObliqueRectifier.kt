package com.hereliesaz.sphereslam.reloc

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Matching a wall seen from a grazing angle. KPM's features and its similarity (rotation + scale)
 * vote do not survive strong foreshortening, so past a few tens of degrees a direct solve finds
 * nothing. Every page lies on one plane, though, and the tracker predicts where the camera is: the
 * live frame can be re-rendered as a **virtual frontal camera** would see the wall — same intrinsics,
 * looking straight at the point the real camera aims at, from the same distance — through the
 * plane-induced homography between the two cameras. KPM matches that near-frontal image; its
 * correspondences are mapped back to real pixels through the same homography (exact for any point on
 * the plane, whatever the prediction's error), and the real camera's pose is solved from them.
 *
 * The prediction only chooses the virtual view: an error in it leaves some foreshortening (and moves
 * the wall in the virtual image) but never biases the solved pose, which starts from the matches' own
 * homography. With no usable prediction, [hypotheses] supplies guessed views to rectify in turn.
 *
 * Conventions: page frame in millimetres (KPM's), z = 0 on the wall; cameras in the image convention
 * (x right, y down, z forward); rotations row-major world→camera; homographies row-major 3×3; pixels
 * in the matcher image ([k] `fx, fy, cx, cy`).
 */
@ExperimentalSphereSlamRelocApi
internal object ObliqueRectifier {

    /** Obliquity at which the frame is rectified before matching (KPM still matches below it). */
    const val DEFAULT_MIN_OBLIQUITY_DEG = 30f

    /**
     * One frame's rectification.
     *
     * @property realFromVirtual maps virtual-image pixels to real-image pixels.
     * @property obliquityDeg angle between the real optical axis and the wall normal.
     */
    class Plan(
        val realFromVirtual: DoubleArray,
        val obliquityDeg: Double,
        /** The prediction it was built from (prior for the solve), page frame. */
        val priorRotation: DoubleArray,
        val priorCentre: DoubleArray,
    )

    /** A pose solved from rectified correspondences. */
    class Solved(
        /** KPM-convention row-major camera-from-page 3×4 (page millimetres). */
        val cameraFromPage3x4: FloatArray,
        /** Mean squared reprojection error, real matcher pixels². */
        val meanSquaredErrorPx: Float,
        val inliers: Int,
        /** The inlier correspondences in real matcher pixels: rows of (x, y, page x mm, page y mm). */
        val correspondences: FloatArray,
    )

    /**
     * A camera-from-page pose ([rotation] row-major, [translation] mm) from a GL column-major
     * camera-from-canonical [view] (canonical units = metres) and the page's GL column-major
     * [canonicalFromPage] (centred page, metres) with its centre at ([centerXmm], [centerYmm]).
     */
    fun pagePose(
        view: FloatArray,
        canonicalFromPage: FloatArray,
        centerXmm: Float,
        centerYmm: Float,
    ): Pair<DoubleArray, DoubleArray>? {
        if (view.size != 16 || canonicalFromPage.size != 16) return null
        val m = DoubleArray(16)
        for (c in 0..3) for (r in 0..3) {
            var s = 0.0
            for (k in 0..3) s += view[k * 4 + r].toDouble() * canonicalFromPage[c * 4 + k].toDouble()
            m[c * 4 + r] = s
        }
        if (m.any { !it.isFinite() }) return null
        // GL eye (y up, z back) → image convention (y down, z forward): negate rows 1 and 2.
        val sign = doubleArrayOf(1.0, -1.0, -1.0)
        val r = DoubleArray(9) { i -> sign[i / 3] * m[(i % 3) * 4 + i / 3] }
        val tc = DoubleArray(3) { i -> sign[i] * m[12 + i] * 1000.0 } // centred page, mm
        // Un-centre: camera = R (x − centre) + t_c  ⇒  t = t_c − R·centre.
        val t = DoubleArray(3) { i -> tc[i] - r[i * 3] * centerXmm - r[i * 3 + 1] * centerYmm }
        return r to t
    }

    /**
     * The rectification for a camera at page-frame pose ([rotation], [translation]) with matcher
     * intrinsics [k], or null when the wall is too frontal (below [minObliquityDeg]), behind the
     * camera, or the geometry degenerates. [fallbackTargetMm] is aimed at when the optical axis misses
     * the plane (e.g. the page centre).
     */
    fun plan(
        rotation: DoubleArray,
        translation: DoubleArray,
        k: FloatArray,
        minObliquityDeg: Float,
        fallbackTargetMm: DoubleArray,
    ): Plan? {
        val r = rotation
        val centre = DoubleArray(3) { i -> -(r[i] * translation[0] + r[3 + i] * translation[1] + r[6 + i] * translation[2]) }
        if (abs(centre[2]) < 1e-6) return null
        val side = if (centre[2] > 0) 1.0 else -1.0
        // The optical axis in the page frame is the rotation's third row.
        val axis = doubleArrayOf(r[6], r[7], r[8])
        val obliquity = Math.toDegrees(acos((-side * axis[2]).coerceIn(-1.0, 1.0)))
        if (obliquity < minObliquityDeg || obliquity >= 89.0) return null
        val s = -centre[2] / axis[2]
        val target = if (s > 0) {
            doubleArrayOf(centre[0] + s * axis[0], centre[1] + s * axis[1], 0.0)
        } else {
            fallbackTargetMm
        }
        val distance = sqrt((centre[0] - target[0]).sq() + (centre[1] - target[1]).sq() + centre[2].sq())
        // Virtual camera: on the normal through the target, as far away, looking straight at it, its
        // x axis the real one's projected onto the wall (so the virtual image is not spun around).
        val zv = doubleArrayOf(0.0, 0.0, -side)
        var xv = doubleArrayOf(r[0], r[1], 0.0)
        val xn = sqrt(xv[0].sq() + xv[1].sq())
        xv = if (xn < 1e-6) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(xv[0] / xn, xv[1] / xn, 0.0)
        val yv = cross(zv, xv)
        val rv = doubleArrayOf(xv[0], xv[1], xv[2], yv[0], yv[1], yv[2], zv[0], zv[1], zv[2])
        val cv = doubleArrayOf(target[0], target[1], side * distance)
        val tv = DoubleArray(3) { i -> -(rv[i * 3] * cv[0] + rv[i * 3 + 1] * cv[1] + rv[i * 3 + 2] * cv[2]) }
        val hReal = planeHomography(r, translation, k)
        val hVirtual = planeHomography(rv, tv, k)
        val virtualInv = invert3(hVirtual) ?: return null
        val realFromVirtual = normalize(mul3(hReal, virtualInv)) ?: return null
        return Plan(realFromVirtual, obliquity, rotation.copyOf(), centre)
    }

    /**
     * Render the virtual image: every [width] × [height] (matcher-size) pixel of [dst] samples [src] at
     * its [Plan.realFromVirtual] image, bilinearly; outside the real frame it is mid-grey (no hard
     * border for the detector to fire on).
     *
     * [src] is the real frame at [srcScale] × the matcher's resolution ([srcWidth] × [srcHeight],
     * tightly packed): sampling the full frame rather than its downscaled copy keeps the detail the
     * foreshortened side of the wall is short of. A matcher pixel `x` is the frame pixel
     * `(x + ½)·srcScale − ½` (the box-downscale convention of `KpmMatchScale`), and each virtual pixel
     * averages srcScale × srcScale sub-samples so the uncompressed direction does not alias.
     */
    fun warp(
        src: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        srcScale: Int,
        width: Int,
        height: Int,
        plan: Plan,
        dst: ByteBuffer,
    ): ByteBuffer {
        require(srcScale >= 1 && src.size >= srcWidth * srcHeight && dst.capacity() >= width * height)
        require(srcWidth >= 2 && srcHeight >= 2)
        val h = plan.realFromVirtual
        val n = srcScale
        val maxX = (srcWidth - 1).toDouble()
        val maxY = (srcHeight - 1).toDouble()
        dst.clear()
        for (v in 0 until height) {
            for (u in 0 until width) {
                var sum = 0.0
                var inside = 0
                for (sj in 0 until n) {
                    val vv = v + (sj + 0.5) / n - 0.5
                    for (si in 0 until n) {
                        val uu = u + (si + 0.5) / n - 0.5
                        val w = h[6] * uu + h[7] * vv + h[8]
                        if (w <= 1e-12) continue
                        val x = ((h[0] * uu + h[1] * vv + h[2]) / w + 0.5) * n - 0.5
                        val y = ((h[3] * uu + h[4] * vv + h[5]) / w + 0.5) * n - 0.5
                        if (x < 0 || y < 0 || x > maxX || y > maxY) continue
                        val x0 = floor(x).toInt().coerceAtMost(srcWidth - 2)
                        val y0 = floor(y).toInt().coerceAtMost(srcHeight - 2)
                        val fx = x - x0
                        val fy = y - y0
                        val i = y0 * srcWidth + x0
                        val a = src[i].toInt() and 0xff
                        val b = src[i + 1].toInt() and 0xff
                        val c = src[i + srcWidth].toInt() and 0xff
                        val d = src[i + srcWidth + 1].toInt() and 0xff
                        sum += (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
                        inside++
                    }
                }
                // Sub-samples off the frame count as mid-grey, so the frame's edge stays soft.
                val value = if (inside == 0) 128 else ((sum + 128.0 * (n * n - inside)) / (n * n) + 0.5).toInt()
                dst.put(value.coerceIn(0, 255).toByte())
            }
        }
        dst.flip()
        return dst
    }

    /**
     * Solve the real camera from KPM [correspondences] found in the virtual image (rows of virtual
     * x, y, page x mm, page y mm): map the pixels back through [plan] and fit the pose with
     * [PhotosphereGeometry.ransacPose].
     *
     * The fit starts from two places and keeps the one that explains more matches: the pose read off
     * the plane homography of the matches themselves ([poseFromHomography], independent of any
     * prediction), and the prediction ([priorRotation] / [priorCentre], re-expressed in the matched
     * page's frame by the caller; null when there is none). A lost camera's prediction can be off by
     * metres (the photosphere knows where it turned, not where it walked), and a fit started there
     * may not reach the true pose.
     */
    fun solve(
        plan: Plan,
        correspondences: FloatArray,
        k: FloatArray,
        priorRotation: DoubleArray?,
        priorCentre: DoubleArray?,
        inlierPx: Double = 3.0,
        minInliers: Int = 8,
    ): Solved? {
        val n = correspondences.size / 4
        if (n < minInliers) return null
        val h = plan.realFromVirtual
        val points = ArrayList<DoubleArray>(n)
        val pixels = ArrayList<FloatArray>(n)
        for (i in 0 until n) {
            val u = correspondences[i * 4].toDouble()
            val v = correspondences[i * 4 + 1].toDouble()
            val w = h[6] * u + h[7] * v + h[8]
            if (w <= 1e-12) continue
            pixels += floatArrayOf(((h[0] * u + h[1] * v + h[2]) / w).toFloat(), ((h[3] * u + h[4] * v + h[5]) / w).toFloat())
            points += doubleArrayOf(correspondences[i * 4 + 2].toDouble(), correspondences[i * 4 + 3].toDouble(), 0.0)
        }
        val starts = ArrayList<Pair<DoubleArray, DoubleArray>>(2)
        robustHomography(points, pixels)?.let { hm -> poseFromHomography(hm, k) }?.let { (r, t) -> starts += r to centreOf(r, t) }
        if (priorRotation != null && priorCentre != null) starts += priorRotation to priorCentre
        val fit = starts.mapNotNull { (r0, c0) ->
            PhotosphereGeometry.ransacPose(points, pixels, k, r0, c0, inlierPx = inlierPx, minInliers = minInliers)
        }.maxWithOrNull(compareBy<PhotosphereGeometry.PoseFit> { it.inliers.size }.thenByDescending { it.rmsPx }) ?: return null
        val r = fit.rotation
        val c = fit.centre
        val pose = FloatArray(12)
        for (row in 0..2) {
            for (col in 0..2) pose[row * 4 + col] = r[row * 3 + col].toFloat()
            pose[row * 4 + 3] = (-(r[row * 3] * c[0] + r[row * 3 + 1] * c[1] + r[row * 3 + 2] * c[2])).toFloat()
        }
        val inlierCorr = FloatArray(fit.inliers.size * 4)
        fit.inliers.forEachIndexed { j, i ->
            inlierCorr[j * 4] = pixels[i][0]
            inlierCorr[j * 4 + 1] = pixels[i][1]
            inlierCorr[j * 4 + 2] = points[i][0].toFloat()
            inlierCorr[j * 4 + 3] = points[i][1].toFloat()
        }
        return Solved(pose, (fit.rmsPx * fit.rmsPx).toFloat(), fit.inliers.size, inlierCorr)
    }

    /**
     * Guessed views for a search without a usable prediction: a camera [distanceMm] from page point
     * [target], turned [tiltsDeg] away from the wall normal about the page's vertical axis (walking
     * along the wall) and then about its horizontal axis (looking up or down it), image up along
     * page −y. One is rectified per full solve, in turn; a guess within a few tens of degrees of the
     * truth leaves foreshortening KPM tolerates, and [solve] does not depend on the guess.
     */
    fun hypotheses(target: DoubleArray, distanceMm: Double, tiltsDeg: FloatArray): List<Pair<DoubleArray, DoubleArray>> {
        val out = ArrayList<Pair<DoubleArray, DoubleArray>>(tiltsDeg.size * 2)
        for (vertical in listOf(true, false)) for (tilt in tiltsDeg) {
            val a = Math.toRadians(tilt.toDouble())
            // Camera centre: back from the target along the tilted normal (+z is the camera side).
            val c = if (vertical) {
                doubleArrayOf(target[0] + distanceMm * kotlin.math.sin(a), target[1], distanceMm * kotlin.math.cos(a))
            } else {
                doubleArrayOf(target[0], target[1] + distanceMm * kotlin.math.sin(a), distanceMm * kotlin.math.cos(a))
            }
            val f = unit(doubleArrayOf(target[0] - c[0], target[1] - c[1], -c[2])) ?: continue
            // Image y down ↔ page −y: x = f × (0, −1, 0) negated, y = f × x.
            val x = unit(cross(doubleArrayOf(0.0, -1.0, 0.0), f).map { -it }.toDoubleArray()) ?: continue
            val y = cross(f, x)
            val r = doubleArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], f[0], f[1], f[2])
            val t = DoubleArray(3) { i -> -(r[i * 3] * c[0] + r[i * 3 + 1] * c[1] + r[i * 3 + 2] * c[2]) }
            out += r to t
        }
        return out
    }

    /**
     * The plane → pixel homography (page x, y mm → pixels) best fitting [points] / [pixels]: a
     * Hartley-normalised least-squares fit, refitted twice on the matches within
     * max(3 × median, 8 px) of the last one. Null under 4 matches or when degenerate.
     */
    internal fun robustHomography(points: List<DoubleArray>, pixels: List<FloatArray>): DoubleArray? {
        var sel = points.indices.toList()
        var h: DoubleArray? = null
        repeat(3) {
            if (sel.size < 4) return h
            val fit = fitHomography(sel.map { points[it] }, sel.map { pixels[it] }) ?: return h
            h = fit
            val res = points.indices.map { i ->
                val w = fit[6] * points[i][0] + fit[7] * points[i][1] + fit[8]
                if (w <= 1e-12) Double.POSITIVE_INFINITY else {
                    val u = (fit[0] * points[i][0] + fit[1] * points[i][1] + fit[2]) / w
                    val v = (fit[3] * points[i][0] + fit[4] * points[i][1] + fit[5]) / w
                    sqrt((u - pixels[i][0]).sq() + (v - pixels[i][1]).sq())
                }
            }
            val finite = res.filter { it.isFinite() }.sorted()
            if (finite.isEmpty()) return null
            val gate = maxOf(8.0, 3.0 * finite[finite.size / 2])
            sel = points.indices.filter { res[it] <= gate }
        }
        return h
    }

    /** Least-squares homography (h33 = 1) on Hartley-normalised coordinates; null when degenerate. */
    private fun fitHomography(points: List<DoubleArray>, pixels: List<FloatArray>): DoubleArray? {
        val n = points.size
        fun normaliser(xs: DoubleArray, ys: DoubleArray): DoubleArray? {
            val mx = xs.average(); val my = ys.average()
            val d = xs.indices.sumOf { sqrt((xs[it] - mx).sq() + (ys[it] - my).sq()) } / xs.size
            if (d < 1e-9) return null
            val s = sqrt(2.0) / d
            return doubleArrayOf(s, 0.0, -s * mx, 0.0, s, -s * my, 0.0, 0.0, 1.0)
        }
        val tp = normaliser(DoubleArray(n) { points[it][0] }, DoubleArray(n) { points[it][1] }) ?: return null
        val tx = normaliser(DoubleArray(n) { pixels[it][0].toDouble() }, DoubleArray(n) { pixels[it][1].toDouble() }) ?: return null
        // Normal equations AᵀA h = Aᵀb for h11..h32, two rows per match.
        val ata = Array(8) { DoubleArray(8) }
        val atb = DoubleArray(8)
        for (i in 0 until n) {
            val x = tp[0] * points[i][0] + tp[2]; val y = tp[4] * points[i][1] + tp[5]
            val u = tx[0] * pixels[i][0] + tx[2]; val v = tx[4] * pixels[i][1] + tx[5]
            val rows = arrayOf(
                doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y) to u,
                doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y) to v,
            )
            for ((a, b) in rows) {
                for (p in 0 until 8) {
                    atb[p] += a[p] * b
                    for (q in 0 until 8) ata[p][q] += a[p] * a[q]
                }
            }
        }
        val hn = solveLinear(ata, atb) ?: return null
        val hNorm = doubleArrayOf(hn[0], hn[1], hn[2], hn[3], hn[4], hn[5], hn[6], hn[7], 1.0)
        val txInv = invert3(tx) ?: return null
        return normalize(mul3(mul3(txInv, hNorm), tp))
    }

    /**
     * Camera-from-page pose (row-major R, t mm) read off a plane homography: `K⁻¹H = λ[r1 r2 t]`, the
     * page in front of the camera, the rotation squared up by Gram–Schmidt. Null when degenerate.
     */
    internal fun poseFromHomography(h: DoubleArray, k: FloatArray): Pair<DoubleArray, DoubleArray>? {
        val kInv = invert3(doubleArrayOf(k[0].toDouble(), 0.0, k[2].toDouble(), 0.0, k[1].toDouble(), k[3].toDouble(), 0.0, 0.0, 1.0)) ?: return null
        val b = mul3(kInv, h)
        val b1 = doubleArrayOf(b[0], b[3], b[6])
        val b2 = doubleArrayOf(b[1], b[4], b[7])
        val b3 = doubleArrayOf(b[2], b[5], b[8])
        val n1 = sqrt(b1.sumOf { it * it }); val n2 = sqrt(b2.sumOf { it * it })
        if (n1 < 1e-12 || n2 < 1e-12) return null
        var lambda = 2.0 / (n1 + n2)
        if (lambda * b3[2] < 0) lambda = -lambda
        val r1 = unit(DoubleArray(3) { lambda * b1[it] }) ?: return null
        val r2raw = DoubleArray(3) { lambda * b2[it] }
        val d = r2raw[0] * r1[0] + r2raw[1] * r1[1] + r2raw[2] * r1[2]
        val r2 = unit(DoubleArray(3) { r2raw[it] - d * r1[it] }) ?: return null
        val r3 = cross(r1, r2)
        // Columns r1 r2 r3 are the page axes in the camera frame.
        val r = doubleArrayOf(r1[0], r2[0], r3[0], r1[1], r2[1], r3[1], r1[2], r2[2], r3[2])
        val t = DoubleArray(3) { lambda * b3[it] }
        if (r.any { !it.isFinite() } || t.any { !it.isFinite() }) return null
        return r to t
    }

    private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                if (f != 0.0) for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }.takeIf { x -> x.all { it.isFinite() } }
    }

    private fun unit(v: DoubleArray): DoubleArray? {
        val n = sqrt(v.sumOf { it * it })
        return if (n < 1e-12 || !n.isFinite()) null else DoubleArray(v.size) { v[it] / n }
    }

    /** Camera centre (page frame) of a page pose. */
    fun centreOf(rotation: DoubleArray, translation: DoubleArray): DoubleArray =
        DoubleArray(3) { i -> -(rotation[i] * translation[0] + rotation[3 + i] * translation[1] + rotation[6 + i] * translation[2]) }

    /** `K [r1 r2 t]`: page-plane millimetres (x, y, 1) → pixels. */
    internal fun planeHomography(r: DoubleArray, t: DoubleArray, k: FloatArray): DoubleArray {
        val m = doubleArrayOf(r[0], r[1], t[0], r[3], r[4], t[1], r[6], r[7], t[2])
        val kk = doubleArrayOf(k[0].toDouble(), 0.0, k[2].toDouble(), 0.0, k[1].toDouble(), k[3].toDouble(), 0.0, 0.0, 1.0)
        return mul3(kk, m)
    }

    internal fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val row = i / 3
        val col = i % 3
        a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
    }

    internal fun invert3(m: DoubleArray): DoubleArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        if (abs(det) < 1e-18 || !det.isFinite()) return null
        val d = 1.0 / det
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * d, (m[2] * m[7] - m[1] * m[8]) * d, (m[1] * m[5] - m[2] * m[4]) * d,
            (m[5] * m[6] - m[3] * m[8]) * d, (m[0] * m[8] - m[2] * m[6]) * d, (m[2] * m[3] - m[0] * m[5]) * d,
            (m[3] * m[7] - m[4] * m[6]) * d, (m[1] * m[6] - m[0] * m[7]) * d, (m[0] * m[4] - m[1] * m[3]) * d,
        )
    }

    private fun normalize(h: DoubleArray): DoubleArray? {
        val s = h[8]
        if (abs(s) < 1e-15 || !s.isFinite()) return null
        return DoubleArray(9) { h[it] / s }
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )

    private fun Double.sq() = this * this
}

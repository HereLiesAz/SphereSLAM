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
 * the wall in the virtual image) but never biases the solved pose.
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
     * [PhotosphereGeometry.ransacPose], seeded by the prediction (re-expressed in the matched page's
     * frame by the caller: [priorRotation] / [priorCentre]).
     */
    fun solve(
        plan: Plan,
        correspondences: FloatArray,
        k: FloatArray,
        priorRotation: DoubleArray,
        priorCentre: DoubleArray,
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
        val fit = PhotosphereGeometry.ransacPose(
            points, pixels, k, priorRotation, priorCentre, inlierPx = inlierPx, minInliers = minInliers,
        ) ?: return null
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

package com.hereliesaz.sphereslam.reloc

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Previews of a reference image as the camera may see it: tilted in perspective, smaller or larger,
 * and turned in its plane. KPM learns each one as another image of the same page
 * ([com.hereliesaz.sphereslam.SphereSlamStandaloneSession.addReferenceView]), its features mapped back
 * to the page through [Preview.pageFromView], so a frame that looks like a preview matches without
 * any prediction of where the camera is.
 *
 * Pixels use the pixel-centre convention (pixel `i` at coordinate `i`) on both sides, as OpenCV's
 * warps and KPM's feature coordinates do.
 */
@ExperimentalSphereSlamRelocApi
internal object ReferencePreviews {

    /** What a preview shows. */
    enum class Kind { TILT, SIZE, ROTATION }

    /**
     * One preview: [luma] ([width] × [height], tightly packed) and [pageFromView], the row-major 3×3
     * mapping a preview pixel back to the reference pixel it shows. [cameraFromPage] and [scale] are
     * the view it stands for (see [Plan]).
     */
    class Preview(
        val kind: Kind,
        val label: String,
        val luma: ByteArray,
        val width: Int,
        val height: Int,
        val pageFromView: FloatArray,
        val cameraFromPage: DoubleArray,
        val scale: Double,
    )

    /**
     * A preview to render: the reference-to-preview homography and the preview's size, and the view it
     * stands for: [cameraFromPage], the row-major rotation from the page's millimetre frame (x right,
     * y up, z out of the wall) to a camera's (x right, y down, z forward) that would see the page as
     * the preview shows it, and [scale], the preview's pixels per reference pixel.
     */
    class Plan(
        val kind: Kind,
        val label: String,
        val viewFromPage: DoubleArray,
        val width: Int,
        val height: Int,
        val cameraFromPage: DoubleArray = FRONTAL,
        val scale: Double = 1.0,
    )

    /** The page seen square-on, upright: page y up is image y down, the wall faces the camera. */
    val FRONTAL = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0)

    /** Smaller previews than this (short side, px) are not rendered: KPM finds too little in them. */
    const val MIN_SIDE_PX = 64

    /**
     * The previews of a [width] × [height] reference for [config]: for each tilt in
     * [KpmMatchSpeedConfig.previewTiltsDeg], both ways about the vertical axis (walking along the wall)
     * and the horizontal one (looking up or down it); each size in [KpmMatchSpeedConfig.previewScales]
     * (below 1 the whole image shrunk, above 1 its centre enlarged to the same size); and in-plane turns
     * every [KpmMatchSpeedConfig.previewRotationStepDeg]. Each family on its own: FREAK's scale pyramid
     * and orientation cover the gaps between them.
     */
    fun plans(width: Int, height: Int, config: KpmMatchSpeedConfig): List<Plan> {
        val out = ArrayList<Plan>()
        val longSide = max(width, height).toDouble()
        for (tilt in config.previewTiltsDeg) for (sign in listOf(1, -1)) for (vertical in listOf(true, false)) {
            val a = sign * tilt.toDouble()
            val h = tiltHomography(width, height, a, vertical)
            fitted(h, width, height, longSide)?.let { (fit, w, hh) ->
                // The plane turned by the tilt's rotation, in the reference's pixel frame (FRONTAL).
                val r = mul(tiltRotation(a, vertical), FRONTAL)
                out += Plan(Kind.TILT, "tilt ${if (vertical) "side" else "up/down"} ${a.toInt()}°", fit, w, hh, r)
            }
        }
        for (s in config.previewScales) {
            if (s == 1f) continue
            if (s < 1f) {
                val w = Math.round(width * s); val hh = Math.round(height * s)
                if (min(w, hh) < MIN_SIDE_PX) continue
                // x' = s·(x + ½) − ½ keeps pixel centres aligned with the box-shrunk image.
                val off = 0.5 * s - 0.5
                out += Plan(Kind.SIZE, "size ×$s", doubleArrayOf(s.toDouble(), 0.0, off, 0.0, s.toDouble(), off, 0.0, 0.0, 1.0), w, hh, scale = s.toDouble())
            } else {
                // The centre 1/s of the reference, enlarged to the reference's size.
                val cx = (width - 1) / 2.0; val cy = (height - 1) / 2.0
                out += Plan(Kind.SIZE, "size ×$s", doubleArrayOf(s.toDouble(), 0.0, cx - s * cx, 0.0, s.toDouble(), cy - s * cy, 0.0, 0.0, 1.0), width, height, scale = s.toDouble())
            }
        }
        val step = config.previewRotationStepDeg
        if (step > 0f) {
            var a = step.toDouble()
            while (a < 360.0 - 1e-6) {
                val r = Math.toRadians(a)
                val cx = (width - 1) / 2.0; val cy = (height - 1) / 2.0
                val c = cos(r); val sn = sin(r)
                val rot = doubleArrayOf(c, -sn, cx - c * cx + sn * cy, sn, c, cy - sn * cx - c * cy, 0.0, 0.0, 1.0)
                // Turning the image turns the camera about its optical axis by the same angle.
                val roll = doubleArrayOf(c, -sn, 0.0, sn, c, 0.0, 0.0, 0.0, 1.0)
                fitted(rot, width, height, null)?.let { (fit, w, hh) -> out += Plan(Kind.ROTATION, "turn ${a.toInt()}°", fit, w, hh, mul(roll, FRONTAL)) }
                a += step
            }
        }
        return out
    }

    /**
     * The view of the reference from a camera turned [angleDeg] off its normal about its vertical
     * ([vertical]) or horizontal axis, looking at its centre from about one image-width away (a phone
     * camera's field of view).
     */
    fun tiltHomography(width: Int, height: Int, angleDeg: Double, vertical: Boolean): DoubleArray {
        val f = max(width, height) * 1.2
        val cx = (width - 1) / 2.0; val cy = (height - 1) / 2.0
        // Rotation of the plane about the axis through its centre, then seen from distance f.
        val r = tiltRotation(angleDeg, vertical)
        // Plane point (X, Y, 0) with X = x − cx, Y = y − cy → camera (R·P + (0, 0, f)) → pixel.
        val k = doubleArrayOf(f, 0.0, cx, 0.0, f, cy, 0.0, 0.0, 1.0)
        val m = doubleArrayOf(r[0], r[1], 0.0, r[3], r[4], 0.0, r[6], r[7], f)
        val center = doubleArrayOf(1.0, 0.0, -cx, 0.0, 1.0, -cy, 0.0, 0.0, 1.0)
        return mul(mul(k, m), center)
    }

    /** The tilt preview's turn of the plane, about its vertical ([vertical]) or horizontal axis. */
    private fun tiltRotation(angleDeg: Double, vertical: Boolean): DoubleArray {
        val a = Math.toRadians(angleDeg)
        val c = cos(a); val s = sin(a)
        return if (vertical) doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c) else doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
    }

    /**
     * [plans] reordered so every prefix covers the views evenly: starting from the reference itself
     * (square-on, full size), each next plan is the one farthest from all chosen so far, by [cost]
     * (`PreviewMatcher.priorityCost`). Ties keep [plans]' order.
     */
    fun prioritized(plans: List<Plan>, cost: (Plan, DoubleArray, Double) -> Double): List<Plan> {
        val left = plans.toMutableList()
        val chosen = ArrayList<Plan>(plans.size)
        // Distance from each remaining plan to its nearest chosen view (the reference to start).
        val nearest = left.map { cost(it, FRONTAL, 1.0) }.toMutableList()
        while (left.isNotEmpty()) {
            var best = 0
            for (i in 1 until left.size) if (nearest[i] > nearest[best] + 1e-9) best = i
            val pick = left.removeAt(best)
            nearest.removeAt(best)
            chosen += pick
            for (i in left.indices) nearest[i] = minOf(nearest[i], cost(left[i], pick.cameraFromPage, pick.scale))
        }
        return chosen
    }

    /**
     * Angle in degrees between two views ([cameraFromPage] rotations): how far a camera at one must
     * turn to be at the other.
     */
    fun viewAngleDeg(a: DoubleArray, b: DoubleArray): Double {
        // trace(A·Bᵀ) = 1 + 2 cos θ.
        var trace = 0.0
        for (i in 0..2) for (k in 0..2) trace += a[i * 3 + k] * b[i * 3 + k]
        return Math.toDegrees(kotlin.math.acos(((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
    }

    /**
     * [h] moved and scaled so the reference's corners land inside a preview with no wasted border:
     * scaled to [longSide] when given (the tilt previews stay the reference's size), else unscaled.
     * Null when a corner falls behind the virtual camera or the preview would be too small.
     */
    private fun fitted(h: DoubleArray, width: Int, height: Int, longSide: Double?): Triple<DoubleArray, Int, Int>? {
        val corners = listOf(0.0 to 0.0, width - 1.0 to 0.0, width - 1.0 to height - 1.0, 0.0 to height - 1.0).map { (x, y) ->
            apply(h, x, y) ?: return null
        }
        val minX = corners.minOf { it[0] }; val maxX = corners.maxOf { it[0] }
        val minY = corners.minOf { it[1] }; val maxY = corners.maxOf { it[1] }
        val spanX = maxX - minX; val spanY = maxY - minY
        if (spanX <= 1 || spanY <= 1) return null
        val s = longSide?.let { (it - 1) / max(spanX, spanY) } ?: 1.0
        val w = ceil(spanX * s).toInt() + 1
        val hh = ceil(spanY * s).toInt() + 1
        if (min(w, hh) < MIN_SIDE_PX) return null
        val t = doubleArrayOf(s, 0.0, -minX * s, 0.0, s, -minY * s, 0.0, 0.0, 1.0)
        return Triple(mul(t, h), w, hh)
    }

    /** Renders [plan] from the reference ([luma], [width] × [height]). */
    fun render(luma: ByteArray, width: Int, height: Int, plan: Plan, useOpenCv: Boolean = true): Preview {
        val pageFromView = invert(plan.viewFromPage) ?: error("singular preview homography")
        val pixels = (if (useOpenCv) renderOpenCv(luma, width, height, plan) else null)
            ?: renderKotlin(luma, width, height, plan, pageFromView)
        return Preview(plan.kind, plan.label, pixels, plan.width, plan.height, FloatArray(9) { pageFromView[it].toFloat() }, plan.cameraFromPage, plan.scale)
    }

    /**
     * OpenCV's warp (`warpPerspective`, bilinear, border replicated so the edge adds no false
     * corners), after an area shrink (`resize`, `INTER_AREA`) when the preview is much smaller than the
     * reference so it doesn't alias. Null when OpenCV isn't loaded.
     */
    private fun renderOpenCv(luma: ByteArray, width: Int, height: Int, plan: Plan): ByteArray? = try {
        val src = Mat(height, width, CvType.CV_8UC1).apply { put(0, 0, luma) }
        var input = src
        var h = plan.viewFromPage
        val shrink = shrinkFactor(plan.viewFromPage, width, height)
        if (shrink < 0.75) {
            val small = Mat()
            val sw = max(1, Math.round(width * shrink).toInt()); val sh = max(1, Math.round(height * shrink).toInt())
            Imgproc.resize(src, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val kx = sw.toDouble() / width; val ky = sh.toDouble() / height
            // Small-image pixel → reference pixel, folded into the warp.
            val up = doubleArrayOf(1 / kx, 0.0, 0.5 / kx - 0.5, 0.0, 1 / ky, 0.5 / ky - 0.5, 0.0, 0.0, 1.0)
            h = mul(h, up)
            input = small
        }
        val hm = Mat(3, 3, CvType.CV_64F).apply { put(0, 0, *h) }
        val dst = Mat()
        Imgproc.warpPerspective(input, dst, hm, Size(plan.width.toDouble(), plan.height.toDouble()), Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_REPLICATE)
        ByteArray(plan.width * plan.height).also { dst.get(0, 0, it) }
            .also { src.release(); if (input !== src) input.release(); hm.release(); dst.release() }
    } catch (_: LinkageError) {
        null
    }

    /** The same warp without OpenCV: bilinear, with a box pre-shrink, edge pixels replicated. */
    private fun renderKotlin(luma: ByteArray, width: Int, height: Int, plan: Plan, pageFromView: DoubleArray): ByteArray {
        var src = luma; var sw = width; var sh = height
        var back = pageFromView
        val shrink = shrinkFactor(plan.viewFromPage, width, height)
        if (shrink < 0.75) {
            sw = max(1, Math.round(width * shrink).toInt()); sh = max(1, Math.round(height * shrink).toInt())
            src = KpmMatchScale.resampleArea(luma, width, height, sw, sh).let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
            val kx = sw.toDouble() / width; val ky = sh.toDouble() / height
            val down = doubleArrayOf(kx, 0.0, 0.5 * kx - 0.5, 0.0, ky, 0.5 * ky - 0.5, 0.0, 0.0, 1.0)
            back = mul(down, pageFromView)
        }
        val out = ByteArray(plan.width * plan.height)
        for (v in 0 until plan.height) for (u in 0 until plan.width) {
            val w = back[6] * u + back[7] * v + back[8]
            val x = ((back[0] * u + back[1] * v + back[2]) / w).coerceIn(0.0, sw - 1.0)
            val y = ((back[3] * u + back[4] * v + back[5]) / w).coerceIn(0.0, sh - 1.0)
            val x0 = min(floor(x).toInt(), sw - 2).coerceAtLeast(0); val y0 = min(floor(y).toInt(), sh - 2).coerceAtLeast(0)
            val fx = x - x0; val fy = y - y0
            val i = y0 * sw + x0
            fun p(k: Int) = (src[k].toInt() and 0xff).toDouble()
            val x1 = if (sw > 1) 1 else 0; val y1 = if (sh > 1) sw else 0
            val value = (p(i) * (1 - fx) + p(i + x1) * fx) * (1 - fy) + (p(i + y1) * (1 - fx) + p(i + y1 + x1) * fx) * fy
            out[v * plan.width + u] = (value + 0.5).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    /** How much the preview shrinks the reference at its most compressed corner (≤ 1 shrinks). */
    private fun shrinkFactor(h: DoubleArray, width: Int, height: Int): Double {
        var least = Double.MAX_VALUE
        for ((x, y) in listOf(0.0 to 0.0, width - 1.0 to 0.0, width - 1.0 to height - 1.0, 0.0 to height - 1.0, (width - 1) / 2.0 to (height - 1) / 2.0)) {
            val p = apply(h, x, y) ?: continue
            val px = apply(h, x + 1, y) ?: continue
            val py = apply(h, x, y + 1) ?: continue
            val dx = sqrt((px[0] - p[0]) * (px[0] - p[0]) + (px[1] - p[1]) * (px[1] - p[1]))
            val dy = sqrt((py[0] - p[0]) * (py[0] - p[0]) + (py[1] - p[1]) * (py[1] - p[1]))
            least = min(least, max(dx, dy))
        }
        return if (least == Double.MAX_VALUE) 1.0 else least.coerceAtMost(1.0)
    }

    fun apply(h: DoubleArray, x: Double, y: Double): DoubleArray? {
        val w = h[6] * x + h[7] * y + h[8]
        if (w <= 1e-9) return null
        return doubleArrayOf((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    private fun mul(a: DoubleArray, b: DoubleArray) = ObliqueRectifier.mul3(a, b)
    private fun invert(m: DoubleArray) = ObliqueRectifier.invert3(m)
}

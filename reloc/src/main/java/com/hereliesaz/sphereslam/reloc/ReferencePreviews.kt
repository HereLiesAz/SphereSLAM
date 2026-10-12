package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.camera.CameraIntrinsics
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
 * Previews of a reference image as the camera may see it, placed on a half-sphere over the target
 * ([AnticipationResolution]): the target at the core, the square-on view at the pole, meridians
 * (latitude) from the pole to the equator cut by circles (longitude), one preview at the pole and at
 * every cut, each from a camera aimed at the core; nested half-spheres for the distances. Each is
 * what this phone's camera ([CameraIntrinsics] of the matched image) would see from there, cropped to
 * its frame: near ones large and strongly foreshortened, far ones small. Turned copies of the
 * square-on view are an extra ([KpmMatchSpeedConfig.previewRotationStepDeg]). KPM learns each preview
 * as another image of the same page ([com.hereliesaz.sphereslam.SphereSlamStandaloneSession.addReferenceView]),
 * its features mapped back to the page through [Preview.pageFromView], so a frame that looks like a
 * preview matches without any prediction of where the camera is.
 *
 * Pixels use the pixel-centre convention (pixel `i` at coordinate `i`) on both sides, as OpenCV's
 * warps and KPM's feature coordinates do.
 */
@ExperimentalSphereSlamRelocApi
internal object ReferencePreviews {

    /** What a preview shows: the square-on view (a pole), a view from a cut, or a turned pole. */
    enum class Kind { POLE, CUT, TURN }

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
     * Where a plan sits on the half-spheres of an [AnticipationResolution]: half-sphere [shell] (0
     * nearest), circle [circle] (0 the pole, 1 nearest it), meridian [meridian] (0 to the page's right,
     * counterclockwise), and [turn] (0 none; turned poles count from 1).
     */
    data class Site(val shell: Int, val circle: Int, val meridian: Int, val turn: Int = 0)

    /**
     * A preview to render: the reference-to-preview homography and the preview's size, and the view it
     * stands for: [cameraFromPage], the row-major rotation from the page's millimetre frame (x right,
     * y up, z out of the wall) to a camera's (x right, y down, z forward) that would see the page as
     * the preview shows it, and [scale], the preview's pixels per reference pixel at the target's
     * centre. [label] names the view (the same view always gets the same label); [site] places it.
     */
    class Plan(
        val kind: Kind,
        val label: String,
        val viewFromPage: DoubleArray,
        val width: Int,
        val height: Int,
        val cameraFromPage: DoubleArray = FRONTAL,
        val scale: Double = 1.0,
        val site: Site = Site(0, 0, 0),
    )

    /** The page seen square-on, upright: page y up is image y down, the wall faces the camera. */
    val FRONTAL = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0)

    /** Smaller previews than this (short side, px) are not rendered: KPM finds too little in them. */
    const val MIN_SIDE_PX = 64

    /**
     * The half-spheres' radii in target widths: [count] steps from [nearest] to [farthest], evenly
     * spaced in log distance (each the same factor farther); one is their geometric mean.
     */
    fun shells(count: Int, nearest: Float, farthest: Float): List<Double> = when {
        count <= 0 -> emptyList()
        count == 1 -> listOf(sqrt(nearest.toDouble() * farthest))
        else -> List(count) { i -> nearest * Math.pow(farthest.toDouble() / nearest, i / (count - 1.0)) }
    }

    /**
     * The previews of a [width] × [height] reference (the whole target) at [resolution], as [camera]
     * (the matched image's intrinsics) would see them: on each half-sphere, the pole and every cut of
     * a meridian by a circle (the equator, edge-on, excluded), then turned poles on the middle
     * half-sphere every [KpmMatchSpeedConfig.previewRotationStepDeg]. Views that leave the target
     * too small to match (under [MIN_SIDE_PX]) are dropped.
     */
    fun plans(width: Int, height: Int, camera: CameraIntrinsics, config: KpmMatchSpeedConfig, resolution: AnticipationResolution): List<Plan> {
        val out = ArrayList<Plan>()
        val radii = shells(resolution.distance, config.anticipationNearestWidths, config.anticipationFarthestWidths)
        for ((si, r) in radii.withIndex()) {
            view(width, height, camera, r, 0.0, 0.0, 0.0, Kind.POLE, Site(si, 0, 0))?.let { out += it }
            for (c in 1..resolution.longitude) {
                val tilt = c * 90.0 / (resolution.longitude + 1)
                for (m in 0 until resolution.latitude) {
                    val azimuth = m * 360.0 / resolution.latitude
                    view(width, height, camera, r, tilt, azimuth, 0.0, Kind.CUT, Site(si, c, m))?.let { out += it }
                }
            }
        }
        val step = config.previewRotationStepDeg
        if (step > 0f && radii.isNotEmpty()) {
            val mid = radii.size / 2
            var a = step.toDouble()
            var n = 1
            while (a < 360.0 - 1e-6) {
                view(width, height, camera, radii[mid], 0.0, 0.0, a, Kind.TURN, Site(mid, 0, 0, n))?.let { out += it }
                a += step
                n++
            }
        }
        return out
    }

    /**
     * The view from [distance] target widths, [tiltDeg] from the pole toward [azimuthDeg] (0 the
     * page's right, counterclockwise), aimed at the core, upright (image up toward the page's up) and
     * then turned [rollDeg] about its optical axis; cropped to [camera]'s frame and to the target.
     * Null when too little of the target is in view.
     */
    fun view(
        width: Int,
        height: Int,
        camera: CameraIntrinsics,
        distance: Double,
        tiltDeg: Double,
        azimuthDeg: Double,
        rollDeg: Double,
        kind: Kind,
        site: Site,
    ): Plan? {
        val t = Math.toRadians(tiltDeg); val a = Math.toRadians(azimuthDeg)
        val dir = doubleArrayOf(sin(t) * cos(a), sin(t) * sin(a), cos(t))
        val centre = DoubleArray(3) { distance * dir[it] }
        val r = mul(roll(rollDeg), lookingFrom(dir))
        // Reference pixel (x, y) → page point in target widths (y up, z = 0) → camera → pixel.
        val cx = (width - 1) / 2.0; val cy = (height - 1) / 2.0
        val w = width.toDouble()
        fun cameraPoint(x: Double, y: Double): DoubleArray {
            val p = doubleArrayOf((x - cx) / w - centre[0], -(y - cy) / w - centre[1], -centre[2])
            return DoubleArray(3) { i -> r[i * 3] * p[0] + r[i * 3 + 1] * p[1] + r[i * 3 + 2] * p[2] }
        }
        // Homography columns: d/dx, d/dy and the camera point of reference pixel (0, 0).
        val o = cameraPoint(0.0, 0.0); val dx = cameraPoint(1.0, 0.0); val dy = cameraPoint(0.0, 1.0)
        val m = doubleArrayOf(
            dx[0] - o[0], dy[0] - o[0], o[0],
            dx[1] - o[1], dy[1] - o[1], o[1],
            dx[2] - o[2], dy[2] - o[2], o[2],
        )
        val k = doubleArrayOf(camera.fx.toDouble(), 0.0, camera.cx.toDouble(), 0.0, camera.fy.toDouble(), camera.cy.toDouble(), 0.0, 0.0, 1.0)
        val h = mul(k, m)
        // The target's outline in camera space, clipped to the space in front of the camera, projected.
        val corners = listOf(-0.5 to -0.5, width - 0.5 to -0.5, width - 0.5 to height - 0.5, -0.5 to height - 0.5)
            .map { (x, y) -> cameraPoint(x, y) }
        val outline = clipInFront(corners).map { p ->
            doubleArrayOf(camera.fx * p[0] / p[2] + camera.cx, camera.fy * p[1] / p[2] + camera.cy)
        }
        if (outline.size < 3) return null
        val minX = max(0.0, floor(outline.minOf { it[0] })); val maxX = min(camera.width - 1.0, ceil(outline.maxOf { it[0] }))
        val minY = max(0.0, floor(outline.minOf { it[1] })); val maxY = min(camera.height - 1.0, ceil(outline.maxOf { it[1] }))
        val pw = (maxX - minX).toInt() + 1; val ph = (maxY - minY).toInt() + 1
        if (min(pw, ph) < MIN_SIDE_PX) return null
        val shift = doubleArrayOf(1.0, 0.0, -minX, 0.0, 1.0, -minY, 0.0, 0.0, 1.0)
        val label = when (kind) {
            Kind.POLE -> "pole ${fmt(distance)}w"
            Kind.CUT -> "${fmt(distance)}w tilt ${fmt(tiltDeg)}° toward ${fmt(azimuthDeg)}°"
            Kind.TURN -> "pole ${fmt(distance)}w turn ${fmt(rollDeg)}°"
        }
        return Plan(kind, label, mul(shift, h), pw, ph, r, camera.fx / (distance * w), site)
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.4g", v).trimEnd('0').trimEnd('.')

    /**
     * Rotation (page frame → camera) of a camera at [dir] from the core (page frame, unit), aimed at
     * the core, its image up toward the page's up. Square-on it is [FRONTAL].
     */
    private fun lookingFrom(dir: DoubleArray): DoubleArray {
        val f = DoubleArray(3) { -dir[it] }
        // Image x ⟂ the forward axis and the page's up; image y down (completes a right-handed frame).
        val up = doubleArrayOf(0.0, 1.0, 0.0)
        var x = cross(f, up)
        val n = sqrt(x.sumOf { it * it })
        x = if (n < 1e-9) doubleArrayOf(1.0, 0.0, 0.0) else DoubleArray(3) { x[it] / n }
        val y = cross(f, x)
        return doubleArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], f[0], f[1], f[2])
    }

    /** A turn of the camera by [deg] about its optical axis (the image turns the same way). */
    private fun roll(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        val c = cos(a); val s = sin(a)
        return doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
    }

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    /** [polygon] (camera space) clipped to z ≥ a small near distance (Sutherland–Hodgman, one plane). */
    private fun clipInFront(polygon: List<DoubleArray>): List<DoubleArray> {
        val near = 1e-3
        val out = ArrayList<DoubleArray>()
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
            val aIn = a[2] >= near; val bIn = b[2] >= near
            if (aIn) out += a
            if (aIn != bIn) {
                val t = (near - a[2]) / (b[2] - a[2])
                out += DoubleArray(3) { a[it] + t * (b[it] - a[it]) }
            }
        }
        return out
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

    /** Renders [plan] from the reference ([luma], [width] × [height]). */
    fun render(luma: ByteArray, width: Int, height: Int, plan: Plan, useOpenCv: Boolean = true): Preview {
        val pageFromView = invert(plan.viewFromPage) ?: error("singular preview homography")
        val pixels = (if (useOpenCv) renderOpenCv(luma, width, height, plan) else null)
            ?: renderKotlin(luma, width, height, plan, pageFromView)
        maskBehind(pixels, plan.width, plan.height, pageFromView, luma)
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

    /**
     * Fills the preview's pixels whose rays miss the target's plane (above its horizon in a steep,
     * near view) with the reference's mean level: the warp would mirror the plane into them. The
     * test is linear in the pixel, so a preview whose corners all see the plane has none.
     */
    private fun maskBehind(pixels: ByteArray, w: Int, h: Int, pageFromView: DoubleArray, reference: ByteArray) {
        fun sees(u: Int, v: Int) = pageFromView[6] * u + pageFromView[7] * v + pageFromView[8] > 0
        if (sees(0, 0) && sees(w - 1, 0) && sees(0, h - 1) && sees(w - 1, h - 1)) return
        var sum = 0L
        for (b in reference) sum += b.toInt() and 0xff
        val mean = (sum / reference.size.coerceAtLeast(1)).toInt().toByte()
        for (v in 0 until h) for (u in 0 until w) if (!sees(u, v)) pixels[v * w + u] = mean
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

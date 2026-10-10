package com.hereliesaz.sphereslam.sidecar

import com.hereliesaz.sphereslam.SphereSlamPoseMath
import com.hereliesaz.sphereslam.math.RigidMath
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Builds a physically metric, fronto-parallel KPM reference page from a camera image and a known
 * metric wall plane (e.g. an ARCore plane), for the hybrid sidecar.
 *
 * A raw camera photograph is not a metric planar reference when the phone is oblique: its pixels are
 * perspective-warped relative to metres on the wall, and assigning it a guessed DPI makes KPM
 * translation metre-shaped but geometrically wrong. [deriveGeometry] intersects camera rays with the
 * metric wall plane, chooses a conservative physical rectangle around the optical centre whose four
 * corners project in-frame, and [rectify] resamples that quadrilateral to an image whose pixel aspect
 * is the physical wall aspect. Feed the result to the tracker with
 * [SphereSlamPoseMath.dpiForReferenceWidth]-derived DPI ([Geometry.referenceDpi]).
 *
 * Arrays only (no Bitmap): the host supplies a luma plane ([lumaFromArgb] converts ARGB pixels).
 * Ported from GraffitiXR `HybridMetricKpmReference`; the OpenCV `warpPerspective` step is replaced by
 * a pure-Kotlin bilinear homography resample with the same corner mapping.
 */
object MetricPageRectification {
    private const val EDGE_MARGIN = 0.10f
    private const val RECT_SAFETY = 0.78f

    /** Largest rectified page side, pixels. */
    const val MAX_REFERENCE_DIM = 1024

    /** Smallest rectified page side, pixels. */
    const val MIN_REFERENCE_DIM = 160

    /** Smallest physical page side, metres. */
    const val MIN_SIDE_M = 0.08f

    /**
     * The metric page chosen on the wall.
     *
     * @property sourceCorners `[u0, v0, …, u3, v3]` — TL, TR, BR, BL in the camera image's pixels.
     * @property cameraFromPageGl column-major OpenGL camera-from-centred-page at capture (metres).
     */
    class Geometry(
        sourceCorners: FloatArray,
        val widthMeters: Float,
        val heightMeters: Float,
        val outputWidth: Int,
        val outputHeight: Int,
        val referenceDpi: Float,
        val pageGeometry: SphereSlamPoseMath.PageGeometry,
        cameraFromPageGl: FloatArray,
    ) {
        private val corners = sourceCorners.copyOf()
        private val cameraFromPage = cameraFromPageGl.copyOf()

        init {
            require(sourceCorners.size == 8) { "sourceCorners must hold four (u, v) pairs" }
            require(cameraFromPageGl.size == 16)
        }

        /** A fresh copy of the TL, TR, BR, BL corners. */
        val sourceCorners: FloatArray get() = corners.copyOf()

        /** A fresh copy of the camera-from-page transform. */
        val cameraFromPageGl: FloatArray get() = cameraFromPage.copyOf()
    }

    /** A rectified, tightly packed metric page ready for `addReference` / `setReference`. */
    class Reference(
        val luma: ByteArray,
        val width: Int,
        val height: Int,
        val widthMeters: Float,
        val heightMeters: Float,
        val referenceDpi: Float,
        val pageGeometry: SphereSlamPoseMath.PageGeometry,
    )

    /**
     * Choose a metric page on the wall seen by the camera.
     *
     * @param fx / [fy] / [cx] / [cy] intrinsics in the same pixel frame as the image.
     * @param glView column-major OpenGL camera-from-world view of the capture, in that same frame.
     * @param wallPlane `[px, py, pz, nx, ny, nz]` — a world point on the wall and its normal.
     * @return null when the geometry is degenerate (no fake scale is ever assigned).
     */
    fun deriveGeometry(
        imageWidth: Int,
        imageHeight: Int,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        glView: FloatArray,
        wallPlane: FloatArray,
    ): Geometry? {
        if (
            imageWidth < 32 || imageHeight < 32 ||
            !fx.isFinite() || !fy.isFinite() || fx <= 0f || fy <= 0f ||
            !cx.isFinite() || !cy.isFinite() ||
            glView.size != 16 || glView.any { !it.isFinite() } ||
            wallPlane.size < 6 || wallPlane.any { !it.isFinite() }
        ) return null

        val centerU = cx.coerceIn(1f, imageWidth - 2f)
        val centerV = cy.coerceIn(1f, imageHeight - 2f)
        val samples = floatArrayOf(
            centerU, centerV,
            imageWidth * EDGE_MARGIN, centerV,
            imageWidth * (1f - EDGE_MARGIN), centerV,
            centerU, imageHeight * EDGE_MARGIN,
            centerU, imageHeight * (1f - EDGE_MARGIN),
        )
        val cvView = RigidMath.glViewToCv(glView)
        val points = backProject(samples, cvView, wallPlane, fx, fy, cx, cy) ?: return null
        val c = points[0]; val l = points[1]; val r = points[2]; val t = points[3]; val b = points[4]

        val ex = normalize(sub(r, l)) ?: return null
        // Image top is +page-Y. Gram-Schmidt keeps the second axis in the wall plane while removing
        // skew induced by the camera's oblique perspective.
        val upApprox = sub(t, b)
        val ey = normalize(sub(upApprox, scale(ex, dot(upApprox, ex)))) ?: return null

        var halfW = min(dot(sub(r, c), ex), -dot(sub(l, c), ex)) * RECT_SAFETY
        var halfH = min(dot(sub(t, c), ey), -dot(sub(b, c), ey)) * RECT_SAFETY
        if (!halfW.isFinite() || !halfH.isFinite() || halfW * 2f < MIN_SIDE_M || halfH * 2f < MIN_SIDE_M) return null

        var corners: FloatArray? = null
        // Mid-edge measurements guarantee the rectangle on each principal axis, not its oblique
        // corners. Shrink conservatively until all four projected corners are in-frame.
        for (attempt in 0 until 10) {
            val physical = listOf(
                add(add(c, scale(ex, -halfW)), scale(ey, halfH)), // TL
                add(add(c, scale(ex, halfW)), scale(ey, halfH)), // TR
                add(add(c, scale(ex, halfW)), scale(ey, -halfH)), // BR
                add(add(c, scale(ex, -halfW)), scale(ey, -halfH)), // BL
            )
            val candidate = FloatArray(8)
            var ok = true
            for ((i, p) in physical.withIndex()) {
                val uv = project(p, fx, fy, cx, cy)
                if (uv == null || uv[0] < 1f || uv[1] < 1f || uv[0] > imageWidth - 2f || uv[1] > imageHeight - 2f) {
                    ok = false
                    break
                }
                candidate[i * 2] = uv[0]; candidate[i * 2 + 1] = uv[1]
            }
            if (ok) {
                corners = candidate
                break
            }
            halfW *= 0.88f
            halfH *= 0.88f
        }
        val src = corners ?: return null
        val widthMeters = halfW * 2f
        val heightMeters = halfH * 2f
        if (widthMeters < MIN_SIDE_M || heightMeters < MIN_SIDE_M) return null

        // KPM's centred page axes are +X right / +Y image-up; in the CV camera frame ex / ey are those
        // axes and ez = ex × ey points from the wall toward the camera. Convert once to OpenGL.
        val ez = normalize(cross(ex, ey)) ?: return null
        val cameraFromPageCv = floatArrayOf(
            ex[0], ex[1], ex[2], 0f,
            ey[0], ey[1], ey[2], 0f,
            ez[0], ez[1], ez[2], 0f,
            c[0], c[1], c[2], 1f,
        )
        val cameraFromPageGl = RigidMath.cvViewToGl(cameraFromPageCv)

        val sourceWidthPx = max(dist(src, 0, 1), dist(src, 3, 2)).roundToInt().coerceAtLeast(MIN_REFERENCE_DIM)
        var outW = min(MAX_REFERENCE_DIM, sourceWidthPx)
        var outH = (outW * heightMeters / widthMeters).roundToInt().coerceAtLeast(2)
        if (outH > MAX_REFERENCE_DIM) {
            val s = MAX_REFERENCE_DIM.toFloat() / outH.toFloat()
            outW = (outW * s).roundToInt().coerceAtLeast(2)
            outH = MAX_REFERENCE_DIM
        }
        if (outW < MIN_REFERENCE_DIM || outH < MIN_REFERENCE_DIM) return null

        val dpi = SphereSlamPoseMath.dpiForReferenceWidth(outW, widthMeters)
        return Geometry(
            sourceCorners = src,
            widthMeters = widthMeters,
            heightMeters = heightMeters,
            outputWidth = outW,
            outputHeight = outH,
            referenceDpi = dpi,
            pageGeometry = SphereSlamPoseMath.pageGeometry(outW, outH, dpi),
            cameraFromPageGl = cameraFromPageGl,
        )
    }

    /**
     * Resample the [geometry]'s source quadrilateral of a luma image into the metric page: TL, TR,
     * BR, BL map to `(0, 0)`, `(W−1, 0)`, `(W−1, H−1)`, `(0, H−1)`; bilinear, out-of-image samples
     * are 0 (OpenCV `warpPerspective` defaults).
     *
     * @param rowStride bytes per source row (`>= width`).
     * @return null for a malformed image or a degenerate quadrilateral.
     */
    fun rectify(luma: ByteArray, width: Int, height: Int, rowStride: Int = width, geometry: Geometry): Reference? {
        val out = warpQuadToRect(
            luma, width, height, rowStride, geometry.sourceCorners, geometry.outputWidth, geometry.outputHeight,
        ) ?: return null
        return Reference(
            luma = out,
            width = geometry.outputWidth,
            height = geometry.outputHeight,
            widthMeters = geometry.widthMeters,
            heightMeters = geometry.heightMeters,
            referenceDpi = geometry.referenceDpi,
            pageGeometry = geometry.pageGeometry,
        )
    }

    /** ARGB_8888 pixels (`Bitmap.getPixels`) → luma, `(77R + 150G + 29B + 128) >> 8`. */
    fun lumaFromArgb(pixels: IntArray): ByteArray = ByteArray(pixels.size) { i ->
        val p = pixels[i]
        ((77 * ((p shr 16) and 0xff) + 150 * ((p shr 8) and 0xff) + 29 * (p and 0xff) + 128) shr 8).toByte()
    }

    /**
     * Perspective-resample the quadrilateral [corners] (TL, TR, BR, BL as `[u0, v0, …]`) of a luma
     * image to an [outWidth] × [outHeight] rectangle. Bilinear; samples outside the image read 0.
     */
    fun warpQuadToRect(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        corners: FloatArray,
        outWidth: Int,
        outHeight: Int,
    ): ByteArray? {
        if (width <= 0 || height <= 0 || rowStride < width || outWidth <= 1 || outHeight <= 1) return null
        if (corners.size != 8 || corners.any { !it.isFinite() }) return null
        if (luma.size.toLong() < (height - 1).toLong() * rowStride + width) return null
        val maxX = (outWidth - 1).toDouble()
        val maxY = (outHeight - 1).toDouble()
        // H maps output (x, y) to source (u, v).
        val h = homography(
            doubleArrayOf(0.0, 0.0, maxX, 0.0, maxX, maxY, 0.0, maxY),
            DoubleArray(8) { corners[it].toDouble() },
        ) ?: return null
        val out = ByteArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val w = h[6] * x + h[7] * y + h[8]
                if (w == 0.0) continue
                val u = (h[0] * x + h[1] * y + h[2]) / w
                val v = (h[3] * x + h[4] * y + h[5]) / w
                out[y * outWidth + x] = bilinear(luma, width, height, rowStride, u, v).toByte()
            }
        }
        return out
    }

    private fun bilinear(luma: ByteArray, width: Int, height: Int, stride: Int, u: Double, v: Double): Int {
        val x0 = floor(u).toInt(); val y0 = floor(v).toInt()
        val fx = u - x0; val fy = v - y0
        fun px(x: Int, y: Int): Double =
            if (x < 0 || y < 0 || x >= width || y >= height) 0.0 else (luma[y * stride + x].toInt() and 0xff).toDouble()
        val top = px(x0, y0) * (1 - fx) + px(x0 + 1, y0) * fx
        val bottom = px(x0, y0 + 1) * (1 - fx) + px(x0 + 1, y0 + 1) * fx
        return (top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
    }

    /** 3×3 row-major homography mapping four [src] points onto four [dst] points (h33 = 1), or null. */
    internal fun homography(src: DoubleArray, dst: DoubleArray): DoubleArray? {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[2 * i]; val y = src[2 * i + 1]
            val u = dst[2 * i]; val v = dst[2 * i + 1]
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        // Gaussian elimination with partial pivoting on the 8×8 augmented system.
        for (col in 0 until 8) {
            var pivot = col
            for (r in col + 1 until 8) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivot][col])) pivot = r
            if (kotlin.math.abs(a[pivot][col]) < 1e-12) return null
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            for (r in 0 until 8) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (k in col until 9) a[r][k] -= f * a[col][k]
            }
        }
        val h = DoubleArray(9)
        for (i in 0 until 8) h[i] = a[i][8] / a[i][i]
        h[8] = 1.0
        return if (h.all { it.isFinite() }) h else null
    }

    /**
     * Back-project each pixel ray onto the wall plane in the CV camera frame. Null unless every
     * sample hits the plane in front of the camera within 0.1–10 m.
     */
    private fun backProject(
        pixels: FloatArray,
        cvView: FloatArray,
        wallPlane: FloatArray,
        fx: Float, fy: Float, cx: Float, cy: Float,
    ): List<FloatArray>? {
        val m = cvView
        val px = wallPlane[0]; val py = wallPlane[1]; val pz = wallPlane[2]
        val nx = wallPlane[3]; val ny = wallPlane[4]; val nz = wallPlane[5]
        val p = floatArrayOf(
            m[0] * px + m[4] * py + m[8] * pz + m[12],
            m[1] * px + m[5] * py + m[9] * pz + m[13],
            m[2] * px + m[6] * py + m[10] * pz + m[14],
        )
        val n = floatArrayOf(
            m[0] * nx + m[4] * ny + m[8] * nz,
            m[1] * nx + m[5] * ny + m[9] * nz,
            m[2] * nx + m[6] * ny + m[10] * nz,
        )
        val nDotP = dot(n, p)
        val out = ArrayList<FloatArray>(pixels.size / 2)
        for (i in 0 until pixels.size / 2) {
            val dx = (pixels[2 * i] - cx) / fx
            val dy = (pixels[2 * i + 1] - cy) / fy
            val nDotD = n[0] * dx + n[1] * dy + n[2]
            if (kotlin.math.abs(nDotD) < 1e-6f) return null
            val t = nDotP / nDotD
            if (t < 0.1f || t > 10f) return null
            out.add(floatArrayOf(t * dx, t * dy, t))
        }
        return out
    }

    private fun project(p: FloatArray, fx: Float, fy: Float, cx: Float, cy: Float): FloatArray? {
        val z = p[2]
        if (!z.isFinite() || z <= 1e-4f) return null
        val u = fx * p[0] / z + cx
        val v = fy * p[1] / z + cy
        return if (u.isFinite() && v.isFinite()) floatArrayOf(u, v) else null
    }

    private fun dist(c: FloatArray, a: Int, b: Int): Float = hypot(c[2 * a] - c[2 * b], c[2 * a + 1] - c[2 * b + 1])
    private fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )
    private fun normalize(a: FloatArray): FloatArray? {
        val n = sqrt(dot(a, a))
        if (!n.isFinite() || n <= 1e-5f) return null
        return scale(a, 1f / n)
    }
}

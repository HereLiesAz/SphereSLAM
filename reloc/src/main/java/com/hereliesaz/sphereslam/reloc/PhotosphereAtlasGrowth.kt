package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.sidecar.MetricPageRectification
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Chooses and rectifies a new KPM atlas page while keeping page 0's centred canonical wall frame
 * immutable — the self-growing multi-page planar atlas of [PhotosphereTrackingSession].
 *
 * The current accepted KPM pose already expresses camera-from-canonical. [propose] intersects its
 * optical axis with canonical `z = 0`, places an axis-aligned wall patch there, and projects that
 * rectangle back into the same frame; [rectify] then unwarps those pixels, so the new page is
 * Euclidean wall geometry rather than a perspective photograph pretending to be a flat page.
 *
 * Pure Kotlin: rectification uses [MetricPageRectification.warpQuadToRect] (bilinear homography
 * resample) instead of OpenCV. Ported from GraffitiXR `StandaloneAtlasGrowth`.
 */
@ExperimentalSphereSlamRelocApi
object PhotosphereAtlasGrowth {
    /** Most pages (including page 0) the atlas may hold. */
    const val MAX_PAGES = 12

    /** Inliers the accepted pose must have before it may grow the atlas. */
    const val MIN_GROW_INLIERS = 18

    /**
     * Largest reprojection error of a pose that may grow the atlas: mean squared, full-frame pixels²
     * (the unit of [PhotosphereTrackingSession.TrackedPose.reprojectionError]).
     */
    const val MAX_GROW_REPROJECTION_ERROR = 4.0f

    /** Minimum interval between growth attempts, milliseconds. */
    const val MIN_GROW_INTERVAL_MS = 3_000L

    /** Largest rectified page width, pixels. */
    const val MAX_REFERENCE_WIDTH_PX = 1024

    /** Smallest rectified page side, pixels. */
    const val MIN_REFERENCE_SIDE_PX = 64

    private const val MIN_BASELINE_RATIO = 0.45f
    private const val MIN_OVERLAP_RATIO = 0.15f
    private const val FRAME_MARGIN_RATIO = 0.06f
    private const val MIN_SCALE = 0.45f
    private const val SCALE_STEP = 0.85f

    /** An existing page's axis-aligned footprint in the canonical wall frame (pose units). */
    data class PageWindow(
        val pageNo: Int,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
    )

    /**
     * A proposed page.
     *
     * @property sourceCorners `[u0, v0, …, u3, v3]` — TL, TR, BR, BL in the live frame's pixels.
     */
    class Geometry(
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
        sourceCorners: FloatArray,
    ) {
        private val corners = sourceCorners.copyOf()

        init {
            require(sourceCorners.size == 8) { "sourceCorners must hold four (u, v) pairs" }
        }

        /** A fresh copy of the TL, TR, BR, BL corners. */
        val sourceCorners: FloatArray get() = corners.copyOf()
    }

    /** A rectified, tightly packed page image. */
    class RectifiedPage(val luma: ByteArray, val width: Int, val height: Int)

    /** Whether an accepted pose is good enough to grow the atlas (before any timing gate). */
    fun poseQualifies(inlierCount: Int, reprojectionError: Float, pageCount: Int): Boolean =
        pageCount + 1 < MAX_PAGES &&
            inlierCount >= MIN_GROW_INLIERS &&
            reprojectionError <= MAX_GROW_REPROJECTION_ERROR

    /**
     * Propose a new page around where the camera's optical axis meets the wall.
     *
     * @param cameraFromCanonicalOpenGl column-major GL camera-from-canonical of the accepted pose.
     * @param intrinsics the live frame's intrinsics (its pixel frame is the one [frameWidth] ×
     *   [frameHeight] describes).
     * @param rootWidthUnits / [rootHeightUnits] page 0's size; the new page is that size, shrunk in
     *   steps until all four corners project inside the frame (with a 6 % margin).
     * @return null unless the patch is far enough from every existing page (≥ 0.45 root widths), still
     *   overlaps one of them by ≥ 15 % (so KPM pages chain), and fits the frame at ≥ 45 % scale.
     */
    fun propose(
        cameraFromCanonicalOpenGl: FloatArray,
        intrinsics: CameraIntrinsics,
        frameWidth: Int,
        frameHeight: Int,
        rootWidthUnits: Float,
        rootHeightUnits: Float,
        existingPages: List<PageWindow>,
    ): Geometry? {
        if (
            cameraFromCanonicalOpenGl.size != 16 ||
            cameraFromCanonicalOpenGl.any { !it.isFinite() } ||
            !intrinsics.isValid ||
            frameWidth <= 0 ||
            frameHeight <= 0 ||
            !(rootWidthUnits > 0f) ||
            !(rootHeightUnits > 0f) ||
            existingPages.isEmpty()
        ) return null

        val center = backProjectToWall(intrinsics.cx, intrinsics.cy, cameraFromCanonicalOpenGl, intrinsics)
            ?: return null

        val nearest = existingPages.minOf { hypot(center[0] - it.centerX, center[1] - it.centerY) }
        if (nearest < rootWidthUnits * MIN_BASELINE_RATIO) return null

        var scale = 1f
        while (scale >= MIN_SCALE) {
            val width = rootWidthUnits * scale
            val height = rootHeightUnits * scale
            val cornersCanonical = floatArrayOf(
                center[0] - width * 0.5f, center[1] + height * 0.5f,
                center[0] + width * 0.5f, center[1] + height * 0.5f,
                center[0] + width * 0.5f, center[1] - height * 0.5f,
                center[0] - width * 0.5f, center[1] - height * 0.5f,
            )
            val corners = FloatArray(8)
            var allProjected = true
            for (i in 0 until 4) {
                val uv = projectWallPoint(cornersCanonical[2 * i], cornersCanonical[2 * i + 1], cameraFromCanonicalOpenGl, intrinsics)
                if (uv == null) {
                    allProjected = false
                    break
                }
                corners[2 * i] = uv[0]
                corners[2 * i + 1] = uv[1]
            }
            if (allProjected) {
                val marginX = frameWidth * FRAME_MARGIN_RATIO
                val marginY = frameHeight * FRAME_MARGIN_RATIO
                val inside = (0 until 4).all { i ->
                    corners[2 * i] in marginX..(frameWidth - marginX) &&
                        corners[2 * i + 1] in marginY..(frameHeight - marginY)
                }
                if (inside && existingPages.any { overlapRatio(center[0], center[1], width, height, it) >= MIN_OVERLAP_RATIO }) {
                    return Geometry(center[0], center[1], width, height, corners)
                }
            }
            scale *= SCALE_STEP
        }
        return null
    }

    /**
     * Unwarp [geometry]'s source quadrilateral of a tightly packed luma frame into a page whose pixel
     * aspect is the physical page aspect: width `min(longest horizontal edge, 1024)` (at least 64),
     * height from `geometry.height / geometry.width`. Null for a malformed frame or a degenerate quad.
     */
    fun rectify(frameLuma: ByteArray, frameWidth: Int, frameHeight: Int, geometry: Geometry): RectifiedPage? {
        if (frameWidth <= 0 || frameHeight <= 0 || frameLuma.size != frameWidth * frameHeight) return null
        val c = geometry.sourceCorners
        val top = hypot(c[2] - c[0], c[3] - c[1])
        val bottom = hypot(c[4] - c[6], c[5] - c[7])
        val edge = max(top, bottom)
        if (!edge.isFinite() || edge <= 0f) return null
        val targetWidth = minOf(edge.roundToInt(), MAX_REFERENCE_WIDTH_PX).coerceAtLeast(MIN_REFERENCE_SIDE_PX)
        val targetHeight = (targetWidth.toFloat() * geometry.height / geometry.width).roundToInt()
            .coerceAtLeast(MIN_REFERENCE_SIDE_PX)
        val luma = MetricPageRectification.warpQuadToRect(
            frameLuma, frameWidth, frameHeight, frameWidth, c, targetWidth, targetHeight,
        ) ?: return null
        return RectifiedPage(luma, targetWidth, targetHeight)
    }

    /** Column-major canonical-from-page for an axis-aligned page centred at ([centerX], [centerY], 0). */
    fun canonicalFromPage(centerX: Float, centerY: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        centerX, centerY, 0f, 1f,
    )

    /** Intersection area over the smaller page's area. */
    internal fun overlapRatio(cx: Float, cy: Float, width: Float, height: Float, other: PageWindow): Float {
        val left = maxOf(cx - width * 0.5f, other.centerX - other.width * 0.5f)
        val right = minOf(cx + width * 0.5f, other.centerX + other.width * 0.5f)
        val bottom = maxOf(cy - height * 0.5f, other.centerY - other.height * 0.5f)
        val top = minOf(cy + height * 0.5f, other.centerY + other.height * 0.5f)
        val intersection = maxOf(0f, right - left) * maxOf(0f, top - bottom)
        val denom = minOf(width * height, other.width * other.height)
        return if (denom > 0f) intersection / denom else 0f
    }

    /**
     * Pixel `[u, v]` (OpenCV convention) of canonical wall point ([x], [y], 0) through a GL
     * camera-from-canonical [view]; null behind the camera.
     */
    fun projectWallPoint(x: Float, y: Float, view: FloatArray, intrinsics: CameraIntrinsics): FloatArray? {
        val gx = view[0] * x + view[4] * y + view[12]
        val gy = view[1] * x + view[5] * y + view[13]
        val gz = view[2] * x + view[6] * y + view[14]
        val z = -gz
        if (!z.isFinite() || z <= 1e-4f) return null
        val u = intrinsics.fx * gx / z + intrinsics.cx
        val v = intrinsics.fy * (-gy) / z + intrinsics.cy
        return if (u.isFinite() && v.isFinite()) floatArrayOf(u, v) else null
    }

    /**
     * Canonical wall point `[x, y]` (on `z = 0`) seen at pixel ([u], [v]) through a GL
     * camera-from-canonical [view]; null when the ray is parallel to or points away from the wall.
     */
    fun backProjectToWall(u: Float, v: Float, view: FloatArray, intrinsics: CameraIntrinsics): FloatArray? {
        // camera_from_canonical in OpenCV coordinates, reconstructed from the OpenGL view.
        val r00 = view[0]; val r01 = view[4]; val r02 = view[8]
        val r10 = -view[1]; val r11 = -view[5]; val r12 = -view[9]
        val r20 = -view[2]; val r21 = -view[6]; val r22 = -view[10]
        val tx = view[12]; val ty = -view[13]; val tz = -view[14]

        // Canonical camera centre C = -Rᵀt.
        val cx = -(r00 * tx + r10 * ty + r20 * tz)
        val cy = -(r01 * tx + r11 * ty + r21 * tz)
        val cz = -(r02 * tx + r12 * ty + r22 * tz)

        val dxCam = (u - intrinsics.cx) / intrinsics.fx
        val dyCam = (v - intrinsics.cy) / intrinsics.fy
        val dx = r00 * dxCam + r10 * dyCam + r20
        val dy = r01 * dxCam + r11 * dyCam + r21
        val dz = r02 * dxCam + r12 * dyCam + r22
        if (abs(dz) < 1e-6f) return null
        val lambda = -cz / dz
        if (!lambda.isFinite() || lambda <= 0f) return null
        val x = cx + lambda * dx
        val y = cy + lambda * dy
        return if (x.isFinite() && y.isFinite()) floatArrayOf(x, y) else null
    }
}

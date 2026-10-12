package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Speed settings of the standalone planar matcher ([PhotosphereTrackingSession]).
 *
 * The full KPM solve (FREAK detection over a DoG pyramid, matching against every atlas page, ICP pose)
 * is the expensive step. Three independent levers cut how often and how much of it runs, without
 * changing the pose convention:
 *
 * 1. **Downscaled matching** ([matchDownscale]): KPM runs on a box-filtered `1/matchDownscale` luma
 *    with intrinsics scaled to match ([KpmMatchScale.scaleIntrinsics]). The 3-D pose a calibrated
 *    PnP/ICP recovers does not depend on image resolution, so the result is the same
 *    camera-from-wall transform. Reprojection errors stay in the pixels of the image each solve ran
 *    on (KPM: the downscaled image, as KPM's own ≤ 10 px² gate assumes; flow: the full frame), and
 *    [PoseAcceptanceConfig.maxReprojectionError] / `PhotosphereAtlasGrowth.MAX_GROW_REPROJECTION_ERROR`
 *    are tuned on that scale. Reporting KPM's error in full-frame px² (×`factor²`) against unchanged
 *    gates made them 4× stricter at the default 2× downscale (0.24.4): the atlas stopped growing and
 *    relocks were rejected.
 * 2. **Frame-to-frame flow tracking** ([flowTracking]): after a full solve, the KPM inlier
 *    correspondences (frame pixel ↔ page millimetre) are tracked with pyramidal Lucas–Kanade at full
 *    resolution and the pose is re-solved by PnP against the page's own reference coordinates
 *    (ARToolKit AR2-style template tracking). A full KPM solve runs again on loss, on too few
 *    inliers, and at least every [fullSolveEveryFrames] frames / [fullSolveIntervalMs] ms.
 * 3. **Focused page matching** ([focusedPageMatching]): with a multi-page atlas, while a lock is
 *    recent, the full solve first matches only the page under the predicted optical axis (or the last
 *    locked page). When that page misses, the frame reports no match and the next full solve
 *    matches all pages — a frame never pays for both a focused and an all-pages solve.
 * 4. **Oblique rectification** ([obliqueRectificationDeg]): when the predicted view of the wall is
 *    at least this oblique, the full solve matches a virtual frontal re-rendering of the frame instead
 *    (`ObliqueRectifier`) — KPM does not match strong foreshortening — and solves the real camera from
 *    the correspondences mapped back to real pixels. It replaces that frame's direct solve. The
 *    virtual view is rendered from the full-resolution frame, not its downscaled copy. With no
 *    oblique prediction, after [obliqueSearchAfterMisses] direct misses in a row every other full
 *    solve rectifies a guessed view instead ([obliqueSearchTiltsDeg] each way along the wall, then
 *    up and down it).
 * 5. **Previews** ([previewSearchAfterMisses]): each page's reference is rendered as this phone's
 *    camera would see it from the views of [anticipationResolution] (a half-sphere over the target:
 *    meridians cut by circles, nested half-spheres for distance) plus turned square-on views
 *    ([previewRotationStepDeg]), and KPM learns every preview as another image of the page with its
 *    features mapped back to the page, in a second matcher built in the background
 *    (`ReferencePreviews`, `PreviewMatcher`). After that many direct misses in a row, every other
 *    full solve matches the frame against the previews: a steep, near, far or turned view relocks
 *    with no position prediction. The phone's rotation is still known while lost, and with the target
 *    in view it fixes how the camera sees the page (tilt, side, turn); so the first search matches
 *    only the [previewNearest] previews nearest that view (sizes nearest the last lock's distance),
 *    each miss widens it by as many again, until all are searched. Nothing changes while the lock
 *    holds. The session throttles the resolution below its maximum by battery, heat and its own load
 *    ([anticipationThrottle]; [PhotosphereTrackingSession.anticipation]).
 * 6. **Reference scale** ([maxReferenceScale]): a reference much larger than the matched frame (a
 *    full-resolution camera still against a 320 × 240 match) puts the wall outside KPM's scale range;
 *    pages are registered from a copy whose long side is at most this many matcher long sides.
 *
 * Flow tracking needs the OpenCV Java native library loaded by the host (as [Relocalizer] does); when
 * it is not, flow tracking switches itself off and every frame takes the full KPM path.
 *
 * @property matchDownscale integer downscale of the luma KPM matches on (1 = full resolution).
 * @property minMatchShortSidePx the downscale is reduced until the matched image's short side is at
 *   least this many pixels (never below 1).
 * @property flowTracking track frame-to-frame between full KPM solves.
 * @property fullSolveEveryFrames full KPM solve after at most this many consecutive flow-tracked frames.
 * @property fullSolveIntervalMs full KPM solve at least this often while flow tracking, ms.
 * @property maxFlowOnlyMs when a periodic full solve misses, flow may keep the pose for at most this
 *   long after the last successful full solve, ms.
 * @property minFlowInliers fewest surviving flow/PnP inliers for a tracked pose.
 * @property minFlowInlierFraction fewest surviving inliers as a fraction of the seeded points.
 * @property maxFlowRmsErrorPx largest PnP RMS reprojection error of a tracked pose, tracked-frame px.
 * @property maxCorrespondences most KPM inlier correspondences requested per full solve to seed flow.
 * @property focusedPageMatching try the predicted page alone before matching the whole atlas.
 * @property focusWindowMs focused matching only within this long of the last full-solve lock, ms.
 * @property obliqueRectificationDeg rectify the full solve when the predicted view is at least this
 *   oblique (angle between the optical axis and the wall normal), degrees; 0 disables.
 * @property obliqueSearchAfterMisses direct full-solve misses in a row before guessed oblique views
 *   join the search; 0 disables the search.
 * @property obliqueSearchTiltsDeg the guessed views' angles from the wall normal, degrees, tried in
 *   order about the wall's vertical axis and then its horizontal one.
 * @property previewSearchAfterMisses direct full-solve misses in a row before the previews are
 *   searched; 0 builds no previews.
 * @property anticipationResolution the most previews anticipated per page ([AnticipationResolution]);
 *   the session's starting maximum ([PhotosphereTrackingSession.anticipationResolution] changes it).
 * @property anticipationNearestWidths the nearest half-sphere's radius, in target widths.
 * @property anticipationFarthestWidths the farthest half-sphere's radius, in target widths.
 * @property anticipationThrottle how far battery, heat and compute pressure may throttle the
 *   resolution; the session's starting value ([PhotosphereTrackingSession.anticipationThrottle]).
 * @property previewRotationStepDeg turned square-on previews (an extra to the half-spheres) every this
 *   many degrees, on the middle half-sphere; 0 none.
 * @property previewNearest previews the first search matches, nearest the predicted view; each
 *   missed search adds as many again. 0 searches them all every time.
 * @property previewMaxFeatures most features KPM keeps per preview.
 * @property previewAtlasPages build previews for pages the atlas grows, not only captured references.
 * @property maxReferenceScale register a reference whose long side exceeds this many times the matched
 *   image's long side from an area-downscaled copy of that size (page geometry in millimetres is
 *   unchanged); 0 registers references as given.
 */
@ExperimentalSphereSlamRelocApi
data class KpmMatchSpeedConfig(
    val matchDownscale: Int = 2,
    val minMatchShortSidePx: Int = 240,
    val flowTracking: Boolean = true,
    val fullSolveEveryFrames: Int = 10,
    val fullSolveIntervalMs: Long = 500L,
    val maxFlowOnlyMs: Long = 1_500L,
    val minFlowInliers: Int = 12,
    val minFlowInlierFraction: Float = 0.4f,
    val maxFlowRmsErrorPx: Float = 2.5f,
    val maxCorrespondences: Int = 200,
    val focusedPageMatching: Boolean = true,
    val focusWindowMs: Long = 1_000L,
    val obliqueRectificationDeg: Float = 30f,
    val obliqueSearchAfterMisses: Int = 3,
    val obliqueSearchTiltsDeg: List<Float> = listOf(45f, -45f, 65f, -65f),
    val previewSearchAfterMisses: Int = 2,
    val anticipationResolution: AnticipationResolution = AnticipationResolution(distance = 3, latitude = 8, longitude = 3),
    val anticipationNearestWidths: Float = 0.5f,
    val anticipationFarthestWidths: Float = 4f,
    val anticipationThrottle: AnticipationThrottle = AnticipationThrottle(),
    val previewRotationStepDeg: Float = 45f,
    val previewNearest: Int = 4,
    val previewMaxFeatures: Int = 600,
    val previewAtlasPages: Boolean = true,
    val maxReferenceScale: Float = 3f,
) {
    init {
        require(matchDownscale >= 1)
        require(minMatchShortSidePx >= 1)
        require(fullSolveEveryFrames >= 1)
        require(fullSolveIntervalMs >= 0L && maxFlowOnlyMs >= 0L && focusWindowMs >= 0L)
        require(minFlowInliers >= 4)
        require(minFlowInlierFraction in 0f..1f)
        require(maxFlowRmsErrorPx.isFinite() && maxFlowRmsErrorPx > 0f)
        require(maxCorrespondences >= 0)
        require(obliqueRectificationDeg.isFinite() && obliqueRectificationDeg >= 0f && obliqueRectificationDeg < 89f)
        require(obliqueSearchAfterMisses >= 0)
        require(previewSearchAfterMisses >= 0)
        require(anticipationNearestWidths.isFinite() && anticipationNearestWidths > 0f)
        require(anticipationFarthestWidths.isFinite() && anticipationFarthestWidths >= anticipationNearestWidths)
        require(previewRotationStepDeg.isFinite() && previewRotationStepDeg >= 0f && previewRotationStepDeg < 360f)
        require(previewNearest >= 0)
        require(previewMaxFeatures > 0)
        require(obliqueSearchTiltsDeg.all { it.isFinite() && kotlin.math.abs(it) < 89f })
        require(maxReferenceScale == 0f || (maxReferenceScale.isFinite() && maxReferenceScale >= 1f))
    }

    companion object {
        /** Every frame a full-resolution, all-pages KPM solve: the behaviour before these levers. */
        val FULL_KPM_EVERY_FRAME = KpmMatchSpeedConfig(
            matchDownscale = 1,
            flowTracking = false,
            focusedPageMatching = false,
        )
    }
}

/** How a visual planar pose was solved. */
@ExperimentalSphereSlamRelocApi
enum class VisualMatchMethod {
    /** Full KPM solve against every atlas page. */
    KPM_FULL,

    /** Full KPM solve against the predicted page only ([KpmMatchSpeedConfig.focusedPageMatching]). */
    KPM_FOCUSED,

    /** Frame-to-frame flow tracking + PnP since the last KPM solve ([KpmMatchSpeedConfig.flowTracking]). */
    FLOW,

    /** KPM on a virtual frontal view of an oblique wall ([KpmMatchSpeedConfig.obliqueRectificationDeg]). */
    KPM_RECTIFIED,

    /** KPM against previews of the references ([KpmMatchSpeedConfig.previewSearchAfterMisses]). */
    KPM_PREVIEW,
}

/**
 * Downscaled-matching geometry. Pixel coordinates use the pixel-centre convention KPM reports feature
 * positions in (pixel `i` spans `[i − ½, i + ½]`), so a box downscale by integer `f` maps
 * `x_small = (x + ½)/f − ½` exactly, for both the principal point and feature coordinates.
 */
internal object KpmMatchScale {

    /** The largest factor ≤ [requested] keeping the short side ≥ [minShortSidePx] (at least 1). */
    fun effectiveFactor(width: Int, height: Int, requested: Int, minShortSidePx: Int): Int {
        require(width > 0 && height > 0 && requested >= 1 && minShortSidePx >= 1)
        var f = requested
        val shortSide = min(width, height)
        while (f > 1 && shortSide / f < minShortSidePx) f--
        return f
    }

    /** Intrinsics of the [factor]-downscaled image (size floored; trailing partial blocks dropped). */
    fun scaleIntrinsics(intrinsics: CameraIntrinsics, factor: Int): CameraIntrinsics {
        require(factor >= 1)
        if (factor == 1) return intrinsics
        val f = factor.toFloat()
        return CameraIntrinsics(
            fx = intrinsics.fx / f,
            fy = intrinsics.fy / f,
            cx = (intrinsics.cx + 0.5f) / f - 0.5f,
            cy = (intrinsics.cy + 0.5f) / f - 0.5f,
            width = intrinsics.width / factor,
            height = intrinsics.height / factor,
        )
    }

    /** A downscaled-image coordinate in full-frame pixels. */
    fun toFramePixel(value: Float, factor: Int): Float = (value + 0.5f) * factor - 0.5f

    /**
     * KPM correspondence rows `(x, y, page x mm, page y mm)` with `x, y` in the [factor]-downscaled
     * image, as a new array with `x, y` in full-frame pixels (page coordinates unchanged).
     */
    fun correspondencesToFramePixels(correspondences: FloatArray, factor: Int): FloatArray {
        val out = correspondences.copyOf()
        for (i in 0 until out.size / 4) {
            out[i * 4] = toFramePixel(out[i * 4], factor)
            out[i * 4 + 1] = toFramePixel(out[i * 4 + 1], factor)
        }
        return out
    }

    /**
     * A mean squared reprojection error measured in the [factor]-downscaled image, in full-frame
     * pixels²: a residual of `d` downscaled pixels spans `d · factor` frame pixels.
     */
    fun errorToFramePixelsSq(meanSquaredErrorPx: Float, factor: Int): Float = meanSquaredErrorPx * factor * factor

    /**
     * Area downscale of tightly packed [src] (`width × height`) to `dstWidth × dstHeight` (each no
     * larger): every output pixel averages the source pixels its footprint covers, so the result spans
     * the same extent as [src]. Returns a new direct buffer ready for reading.
     */
    fun resampleArea(src: ByteArray, width: Int, height: Int, dstWidth: Int, dstHeight: Int): ByteBuffer {
        require(src.size >= width * height && dstWidth in 1..width && dstHeight in 1..height)
        val sx = width.toDouble() / dstWidth
        val sy = height.toDouble() / dstHeight
        val dst = ByteBuffer.allocateDirect(dstWidth * dstHeight)
        for (y in 0 until dstHeight) {
            val y0 = (y * sy).toInt()
            val y1 = maxOf(y0 + 1, minOf(height, ((y + 1) * sy).toInt()))
            for (x in 0 until dstWidth) {
                val x0 = (x * sx).toInt()
                val x1 = maxOf(x0 + 1, minOf(width, ((x + 1) * sx).toInt()))
                var sum = 0
                for (yy in y0 until y1) {
                    val row = yy * width
                    for (xx in x0 until x1) sum += src[row + xx].toInt() and 0xFF
                }
                val area = (y1 - y0) * (x1 - x0)
                dst.put(((sum + area / 2) / area).toByte())
            }
        }
        dst.flip()
        return dst
    }

    /** The size a `width × height` reference is registered at: long side ≤ [maxLongSide], aspect kept. */
    fun referenceSize(width: Int, height: Int, maxLongSide: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (maxLongSide <= 0 || long <= maxLongSide) return width to height
        val s = maxLongSide.toDouble() / long
        return maxOf(1, Math.round(width * s).toInt()) to maxOf(1, Math.round(height * s).toInt())
    }

    /**
     * Box-filter downscale of tightly packed [src] (`width × height`) by [factor] into [dst]
     * (`(width/factor) × (height/factor)` bytes from position 0); returns [dst] flipped for reading.
     */
    fun downsample(src: ByteArray, width: Int, height: Int, factor: Int, dst: ByteBuffer): ByteBuffer {
        require(factor >= 1 && src.size >= width * height)
        val ow = width / factor
        val oh = height / factor
        require(dst.capacity() >= ow * oh)
        dst.clear()
        if (factor == 1) {
            dst.put(src, 0, width * height)
        } else if (factor == 2) {
            for (y in 0 until oh) {
                var a = (2 * y) * width
                var b = a + width
                for (x in 0 until ow) {
                    val sum = (src[a].toInt() and 0xFF) + (src[a + 1].toInt() and 0xFF) +
                        (src[b].toInt() and 0xFF) + (src[b + 1].toInt() and 0xFF)
                    dst.put(((sum + 2) shr 2).toByte())
                    a += 2
                    b += 2
                }
            }
        } else {
            val area = factor * factor
            for (y in 0 until oh) {
                for (x in 0 until ow) {
                    var sum = 0
                    for (dy in 0 until factor) {
                        val row = (y * factor + dy) * width + x * factor
                        for (dx in 0 until factor) sum += src[row + dx].toInt() and 0xFF
                    }
                    dst.put(((sum + area / 2) / area).toByte())
                }
            }
        }
        dst.flip()
        return dst
    }
}

/**
 * When to run the full KPM solve versus frame-to-frame flow tracking. Pure state machine; times are
 * the caller's monotonic milliseconds.
 */
internal class KpmMatchScheduler(private val config: KpmMatchSpeedConfig) {

    enum class Decision {
        /** No usable flow seed: full solve; a miss is a miss. */
        ACQUIRE,

        /** Flow is healthy but a periodic full solve is due; flow may cover a miss. */
        REFRESH,

        /** Track frame-to-frame; on failure fall back to a full solve this same frame. */
        TRACK,
    }

    private var armed = false
    private var framesSinceAttempt = 0
    private var lastAttemptMs = Long.MIN_VALUE
    private var lastSuccessMs = Long.MIN_VALUE

    /** Whether a flow seed from a recent full solve is live. */
    val isArmed: Boolean get() = armed

    fun decide(nowMs: Long): Decision {
        if (!config.flowTracking || !armed) return Decision.ACQUIRE
        if (framesSinceAttempt >= config.fullSolveEveryFrames) return Decision.REFRESH
        if (lastAttemptMs == Long.MIN_VALUE || nowMs - lastAttemptMs >= config.fullSolveIntervalMs) return Decision.REFRESH
        return Decision.TRACK
    }

    /** A full solve ran; [seeded] is whether its correspondences armed the flow tracker. */
    fun onFullSolve(nowMs: Long, success: Boolean, seeded: Boolean) {
        lastAttemptMs = nowMs
        framesSinceAttempt = 0
        if (success) {
            lastSuccessMs = nowMs
            armed = seeded && config.flowTracking
        } else if (!flowMayCoverMiss(nowMs)) {
            armed = false
        }
    }

    /** After a missed REFRESH: may flow still supply this frame's pose? */
    fun flowMayCoverMiss(nowMs: Long): Boolean =
        armed && lastSuccessMs != Long.MIN_VALUE && nowMs - lastSuccessMs <= config.maxFlowOnlyMs

    /** A flow-tracking attempt finished. */
    fun onTracked(success: Boolean) {
        if (success) framesSinceAttempt++ else armed = false
    }

    /** Drop the flow seed (e.g. the pose gate rejected the pose it produced). */
    fun disarm() {
        armed = false
    }
}

/**
 * Which atlas pages to try first. Pure.
 *
 * Pages are axis-aligned windows in the canonical wall plane (`z = 0`, GL units). The predicted
 * optical axis — the camera's `−z` ray from a predicted camera-from-canonical view — is intersected
 * with the wall; pages are ranked by the hit point's normalized distance from their centre (`< 1`
 * means inside the page). The last locked page wins ties and keeps priority while the hit is still
 * inside it (hysteresis across overlapping pages). Without a usable hit, the last locked page goes
 * first, then the rest by page number.
 */
internal object KpmPagePriority {

    /** Page [pageNo] centred at ([centerX], [centerY]) spanning [width] × [height] (canonical units). */
    data class PageWindow(val pageNo: Int, val centerX: Float, val centerY: Float, val width: Float, val height: Float)

    /** Where the optical axis of column-major GL [cameraFromCanonical] meets `z = 0`, or null. */
    fun opticalAxisHit(cameraFromCanonical: FloatArray): FloatArray? {
        require(cameraFromCanonical.size == 16)
        val m = cameraFromCanonical
        if (m.any { !it.isFinite() }) return null
        // R is the upper 3×3 (column-major m[col*4+row]); centre C = −Rᵀt, axis d = Rᵀ·(0,0,−1).
        val tx = m[12]
        val ty = m[13]
        val tz = m[14]
        val c = FloatArray(3) { i -> -(m[i * 4] * tx + m[i * 4 + 1] * ty + m[i * 4 + 2] * tz) }
        val d = FloatArray(3) { i -> -m[i * 4 + 2] }
        if (abs(d[2]) < 1e-6f) return null
        val s = -c[2] / d[2]
        if (s <= 0f) return null
        return floatArrayOf(c[0] + s * d[0], c[1] + s * d[1])
    }

    fun normalizedDistance(page: PageWindow, x: Float, y: Float): Float {
        val hx = max(page.width * 0.5f, 1e-6f)
        val hy = max(page.height * 0.5f, 1e-6f)
        val dx = (x - page.centerX) / hx
        val dy = (y - page.centerY) / hy
        return sqrt(dx * dx + dy * dy)
    }

    fun order(pages: List<PageWindow>, predictedCameraFromCanonical: FloatArray?, lastLockedPage: Int?): List<Int> {
        val hit = predictedCameraFromCanonical?.let { opticalAxisHit(it) }
        if (hit == null) {
            return pages.map { it.pageNo }.sortedWith(compareBy<Int> { it != lastLockedPage }.thenBy { it })
        }
        val scored = pages.map { it.pageNo to normalizedDistance(it, hit[0], hit[1]) }
        val last = scored.firstOrNull { it.first == lastLockedPage }
        val sorted = scored.sortedWith(compareBy<Pair<Int, Float>> { it.second }.thenBy { it.first != lastLockedPage }.thenBy { it.first })
        return if (last != null && last.second <= INSIDE) {
            listOf(last.first) + sorted.map { it.first }.filter { it != last.first }
        } else {
            sorted.map { it.first }
        }
    }

    /**
     * The pages to try alone before an all-pages match: the top-ranked one, only for a multi-page
     * atlas and only while the last lock is recent (when lost, the whole atlas is searched at once).
     */
    fun focusCandidates(
        pages: List<PageWindow>,
        predictedCameraFromCanonical: FloatArray?,
        lastLockedPage: Int?,
        msSinceLastLock: Long?,
        config: KpmMatchSpeedConfig,
    ): List<Int> {
        if (!config.focusedPageMatching || pages.size < 2) return emptyList()
        if (lastLockedPage == null || msSinceLastLock == null || msSinceLastLock < 0L || msSinceLastLock > config.focusWindowMs) {
            return emptyList()
        }
        return order(pages, predictedCameraFromCanonical, lastLockedPage).take(1)
    }

    private const val INSIDE = 1f
}

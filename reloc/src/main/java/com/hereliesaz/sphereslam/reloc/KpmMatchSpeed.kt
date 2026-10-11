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
 *    camera-from-wall transform. Reported reprojection errors are mean squared errors in
 *    **full-frame** pixels² whichever path solved the pose (a downscaled KPM solve's error is scaled
 *    by `factor²`), so one [PoseAcceptanceConfig.maxReprojectionError] gates KPM and flow poses alike.
 *    KPM's own internal ≤ 10 px² gate still applies in the image it solved, i.e. ≤ `10·factor²`
 *    full-frame px², so at a downscale the session's acceptance gate is the binding one.
 * 2. **Frame-to-frame flow tracking** ([flowTracking]): after a full solve, the KPM inlier
 *    correspondences (frame pixel ↔ page millimetre) are tracked with pyramidal Lucas–Kanade at full
 *    resolution and the pose is re-solved by PnP against the page's own reference coordinates
 *    (ARToolKit AR2-style template tracking). A full KPM solve runs again on loss, on too few
 *    inliers, and at least every [fullSolveEveryFrames] frames / [fullSolveIntervalMs] ms.
 * 3. **Focused page matching** ([focusedPageMatching]): with a multi-page atlas, while a lock is
 *    recent, the full solve first matches only the page under the predicted optical axis (or the last
 *    locked page). When that page misses, the frame reports no match and the next full solve
 *    matches all pages — a frame never pays for both a focused and an all-pages solve.
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

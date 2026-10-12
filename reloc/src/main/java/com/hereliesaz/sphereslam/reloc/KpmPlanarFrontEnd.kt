package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.PlanarMatchRequest
import com.hereliesaz.sphereslam.SphereSlamStandaloneSession
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import java.nio.ByteBuffer

/**
 * The production [PlanarFrontEnd]: SphereSLAM's planar KPM made cheap per frame with the three
 * [KpmMatchSpeedConfig] levers — downscaled matching, frame-to-frame flow tracking between full
 * solves, and focused (predicted-page-first) matching on a multi-page atlas.
 *
 * Every pose it returns is a GL column-major camera-from-canonical produced by
 * [SphereSlamStandaloneSession]'s own page→canonical conversion, whichever path solved it, and every
 * [PlanarMatchResult.reprojectionError] is a mean squared reprojection error in the pixels the pose
 * was solved in: the matcher's (downscaled) image for KPM, the full frame for flow.
 *
 * Focused matching costs at most one single-page solve per frame: when the predicted page misses, the
 * frame reports no match and the **next** full solve searches every page (instead of running a second,
 * all-pages solve in the same frame).
 */
@ExperimentalSphereSlamRelocApi
internal class KpmPlanarFrontEnd(
    private val intrinsics: CameraIntrinsics,
    private val config: KpmMatchSpeedConfig,
    private val flowFactory: (CameraIntrinsics, KpmMatchSpeedConfig) -> FlowTracker = ::FlowPoseTracker,
    private val backendFactory: (CameraIntrinsics) -> KpmMatcherBackend = KpmMatcherBackend::standalone,
    // A forked backend (see [fork]) instead of a fresh one.
    forkedBackend: KpmMatcherBackend? = null,
) : PlanarFrontEnd {

    /** Downscale factor KPM matches at for this frame size. */
    val factor = KpmMatchScale.effectiveFactor(intrinsics.width, intrinsics.height, config.matchDownscale, config.minMatchShortSidePx)
    private val matchIntrinsics = KpmMatchScale.scaleIntrinsics(intrinsics, factor)
    private val backend = forkedBackend ?: backendFactory(matchIntrinsics)
    private val small: ByteBuffer? =
        if (factor > 1) ByteBuffer.allocateDirect(matchIntrinsics.width * matchIntrinsics.height) else null

    private val scheduler = KpmMatchScheduler(config)
    private val pages = linkedMapOf<Int, KpmPagePriority.PageWindow>()

    // Each page's placement, for re-expressing a predicted canonical pose in page millimetres.
    private class PageFrame(val canonicalFromPage: FloatArray, val centerXmm: Float, val centerYmm: Float)
    private val pageFrames = HashMap<Int, PageFrame>()
    private val matchK = floatArrayOf(matchIntrinsics.fx, matchIntrinsics.fy, matchIntrinsics.cx, matchIntrinsics.cy)
    private var rectifiedBuffer: ByteBuffer? = null
    private var flow: FlowTracker? = null
    private var flowUnavailable = !config.flowTracking
    private var lastLockedPage: Int? = null
    private var lastLockMs = Long.MIN_VALUE

    // The previous full solve tried only the predicted page and missed: search every page next time.
    private var focusMissPending = false

    /** Whether flow tracking has been switched off (disabled by config, or OpenCV is missing). */
    val isFlowUnavailable: Boolean get() = flowUnavailable

    override fun addPage(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        referenceWidthUnits: Float,
        physicallyMetric: Boolean,
        pageNo: Int,
        canonicalFromPage: FloatArray,
    ): PhotosphereTrackingSession.RegisteredPage {
        val r = backend.addReference(luma, width, height, referenceWidthUnits, physicallyMetric, pageNo, canonicalFromPage)
        pages[pageNo] = KpmPagePriority.PageWindow(
            pageNo, canonicalFromPage[12], canonicalFromPage[13], r.geometry.widthMeters, r.geometry.heightMeters,
        )
        pageFrames[pageNo] = PageFrame(canonicalFromPage.copyOf(), r.geometry.centerXmm, r.geometry.centerYmm)
        return r
    }

    override fun match(luma: ByteBuffer, timestampNs: Long): PlanarMatchResult? =
        fullSolve(luma, null, timestampNs, Long.MIN_VALUE, null)

    override fun match(input: PlanarFrameInput): PlanarMatchResult? {
        val now = input.nowMs
        when (scheduler.decide(now)) {
            KpmMatchScheduler.Decision.TRACK -> {
                val tracked = trackFlow(input)
                scheduler.onTracked(tracked != null)
                if (tracked != null) return tracked
                return fullSolve(input.luma, input.lumaBytes, input.timestampNs, now, input.predictedCameraFromCanonical)
            }
            KpmMatchScheduler.Decision.REFRESH -> {
                val solved = fullSolve(input.luma, input.lumaBytes, input.timestampNs, now, input.predictedCameraFromCanonical)
                if (solved != null || !scheduler.flowMayCoverMiss(now)) return solved
                // A periodic solve can miss on a blurred frame while flow is still healthy.
                return trackFlow(input).also { scheduler.onTracked(it != null) }
            }
            KpmMatchScheduler.Decision.ACQUIRE ->
                return fullSolve(input.luma, input.lumaBytes, input.timestampNs, now, input.predictedCameraFromCanonical)
        }
    }

    /**
     * A matcher over a native fork of this one's atlas (see [SphereSlamStandaloneSession.fork]), with
     * the same speed config and page windows but fresh scheduling/flow state (its first frame is a
     * full solve). Safe while another thread runs [match] here: it only reads the page windows, which
     * change solely through [addPage] before this matcher is published.
     */
    override fun fork(): PlanarFrontEnd? {
        val forked = backend.fork() ?: return null
        return KpmPlanarFrontEnd(intrinsics, config, flowFactory, backendFactory, forked).also {
            it.pages.putAll(pages)
            it.pageFrames.putAll(pageFrames)
        }
    }

    override fun onVerdict(accepted: Boolean) {
        if (!accepted) {
            scheduler.disarm()
            flow?.reset()
        }
    }

    private fun fullSolve(
        luma: ByteBuffer,
        lumaBytes: ByteArray?,
        timestampNs: Long,
        nowMs: Long,
        predicted: FloatArray?,
    ): PlanarMatchResult? {
        val matchLuma = if (small != null) {
            val bytes = lumaBytes ?: ByteArray(luma.remaining()).also { luma.duplicate().get(it) }
            KpmMatchScale.downsample(bytes, intrinsics.width, intrinsics.height, factor, small)
        } else {
            luma
        }
        val wantCorrespondences = !flowUnavailable && lumaBytes != null && config.maxCorrespondences > 0
        val maxCorr = if (wantCorrespondences) config.maxCorrespondences else 0

        rectificationPlan(predicted)?.let { plan ->
            return rectifiedSolve(plan, matchLuma, lumaBytes, timestampNs, nowMs, predicted!!, wantCorrespondences)
        }

        val msSinceLock = if (lastLockMs == Long.MIN_VALUE || nowMs == Long.MIN_VALUE) null else nowMs - lastLockMs
        val focus = if (focusMissPending) {
            emptyList()
        } else {
            KpmPagePriority.focusCandidates(pages.values.toList(), predicted, lastLockedPage, msSinceLock, config)
        }
        focusMissPending = false
        val method: VisualMatchMethod
        val pose: KpmMatcherBackend.Solve?
        if (focus.isNotEmpty()) {
            method = VisualMatchMethod.KPM_FOCUSED
            pose = focus.firstNotNullOfOrNull { page -> backend.match(matchLuma.duplicate(), timestampNs, page, maxCorr) }
            // Bounded cost: never a second (all-pages) solve on this frame; the next one searches all.
            if (pose == null) focusMissPending = true
        } else {
            method = VisualMatchMethod.KPM_FULL
            pose = backend.match(matchLuma.duplicate(), timestampNs, PlanarMatchRequest.ALL_PAGES, maxCorr)
        }
        if (pose == null) {
            if (nowMs != Long.MIN_VALUE) scheduler.onFullSolve(nowMs, success = false, seeded = false)
            if (!scheduler.isArmed) flow?.reset()
            return null
        }

        var seeded = false
        if (wantCorrespondences && lumaBytes != null) {
            val corr = KpmMatchScale.correspondencesToFramePixels(pose.correspondences, factor)
            seeded = withFlow { it.seed(lumaBytes, pose.pageNo, corr, pose.cameraFromPage3x4) } == true
        }
        if (nowMs != Long.MIN_VALUE) {
            scheduler.onFullSolve(nowMs, success = true, seeded = seeded)
            lastLockMs = nowMs
        }
        lastLockedPage = pose.pageNo
        return PlanarMatchResult(
            pageNo = pose.pageNo,
            cameraFromCanonical = pose.cameraFromCanonical,
            // Kept in the pixels KPM solved in (its own ≤ 10 px² gate and PoseAcceptanceConfig's default
            // were tuned on that scale); scaling by factor² would reject sound half-resolution locks.
            reprojectionError = pose.reprojectionError,
            inlierCount = pose.inlierCount,
            method = method,
        )
    }

    /** The rectification for this frame's predicted pose, or null when it is frontal enough (or unknown). */
    private fun rectificationPlan(predicted: FloatArray?): ObliqueRectifier.Plan? {
        if (predicted == null || config.obliqueRectificationDeg <= 0f) return null
        val pageNo = lastLockedPage?.takeIf { it in pageFrames } ?: pageFrames.keys.firstOrNull() ?: return null
        val frame = pageFrames.getValue(pageNo)
        val (r, t) = ObliqueRectifier.pagePose(predicted, frame.canonicalFromPage, frame.centerXmm, frame.centerYmm)
            ?: return null
        val pageCentre = doubleArrayOf(frame.centerXmm.toDouble(), frame.centerYmm.toDouble(), 0.0)
        return ObliqueRectifier.plan(r, t, matchK, config.obliqueRectificationDeg, pageCentre)
    }

    /**
     * The full solve of an oblique frame: KPM on its virtual frontal view, the real pose solved from
     * the correspondences mapped back. Same bookkeeping (scheduler, focus, flow seeding) as a direct
     * solve; its correspondences are always requested (the pose is solved from them).
     */
    private fun rectifiedSolve(
        plan: ObliqueRectifier.Plan,
        matchLuma: ByteBuffer,
        lumaBytes: ByteArray?,
        timestampNs: Long,
        nowMs: Long,
        predicted: FloatArray,
        wantCorrespondences: Boolean,
    ): PlanarMatchResult? {
        val w = matchIntrinsics.width
        val h = matchIntrinsics.height
        val src = if (small == null && lumaBytes != null) lumaBytes else ByteArray(w * h).also { matchLuma.duplicate().get(it) }
        val buffer = rectifiedBuffer ?: ByteBuffer.allocateDirect(w * h).also { rectifiedBuffer = it }
        ObliqueRectifier.warp(src, w, h, plan, buffer)
        val pageRequest = lastLockedPage?.takeIf { it in pageFrames } ?: PlanarMatchRequest.ALL_PAGES
        val raw = backend.match(buffer.duplicate(), timestampNs, pageRequest, maxOf(config.maxCorrespondences, MIN_RECTIFIED_CORRESPONDENCES))
        val solved = raw?.let { m ->
            val frame = pageFrames[m.pageNo] ?: return@let null
            val (pr, pt) = ObliqueRectifier.pagePose(predicted, frame.canonicalFromPage, frame.centerXmm, frame.centerYmm)
                ?: return@let null
            ObliqueRectifier.solve(plan, m.correspondences, matchK, pr, ObliqueRectifier.centreOf(pr, pt))
                ?.let { s -> backend.cameraFromCanonical(m.pageNo, s.cameraFromPage3x4)?.let { view -> Triple(m.pageNo, s, view) } }
        }
        if (solved == null) {
            if (nowMs != Long.MIN_VALUE) scheduler.onFullSolve(nowMs, success = false, seeded = false)
            if (!scheduler.isArmed) flow?.reset()
            return null
        }
        val (pageNo, s, view) = solved
        var seeded = false
        if (wantCorrespondences && lumaBytes != null) {
            val corr = KpmMatchScale.correspondencesToFramePixels(s.correspondences, factor)
            seeded = withFlow { it.seed(lumaBytes, pageNo, corr, s.cameraFromPage3x4) } == true
        }
        if (nowMs != Long.MIN_VALUE) {
            scheduler.onFullSolve(nowMs, success = true, seeded = seeded)
            lastLockMs = nowMs
        }
        lastLockedPage = pageNo
        return PlanarMatchResult(
            pageNo = pageNo,
            cameraFromCanonical = view,
            // Solved in the real matcher image's pixels, the scale KPM's own errors are reported in.
            reprojectionError = s.meanSquaredErrorPx,
            inlierCount = s.inliers,
            method = VisualMatchMethod.KPM_RECTIFIED,
        )
    }

    private fun trackFlow(input: PlanarFrameInput): PlanarMatchResult? {
        val tracked = withFlow { it.track(input.lumaBytes) } ?: return null
        val page = flow?.trackedPage ?: return null
        val view = backend.cameraFromCanonical(page, tracked.cameraFromPage3x4) ?: return null
        return PlanarMatchResult(
            pageNo = page,
            cameraFromCanonical = view,
            reprojectionError = tracked.meanSquaredErrorPx,
            inlierCount = tracked.inliers,
            method = VisualMatchMethod.FLOW,
        )
    }

    /** Runs [block] on the (lazily created) flow tracker; turns flow off for good without OpenCV. */
    private fun <T> withFlow(block: (FlowTracker) -> T): T? {
        if (flowUnavailable) return null
        return try {
            block(flow ?: flowFactory(intrinsics, config).also { flow = it })
        } catch (e: LinkageError) {
            flowUnavailable = true
            runCatching { flow?.close() }
            flow = null
            scheduler.disarm()
            null
        }
    }

    private companion object {
        /** Correspondences requested from a rectified solve (its pose is solved from them). */
        const val MIN_RECTIFIED_CORRESPONDENCES = 120
    }

    override fun close() {
        runCatching { flow?.close() }
        flow = null
        backend.close()
    }
}

/**
 * The planar KPM matcher [KpmPlanarFrontEnd] drives — [SphereSlamStandaloneSession] in production
 * ([standalone]); a seam so the scheduling, scaling and fallback logic is testable on the JVM.
 */
@ExperimentalSphereSlamRelocApi
internal interface KpmMatcherBackend : AutoCloseable {

    /** One KPM solve: the pose in both conventions, its error (solved-image px²) and correspondences. */
    class Solve(
        val pageNo: Int,
        val cameraFromCanonical: FloatArray,
        val reprojectionError: Float,
        val inlierCount: Int,
        val cameraFromPage3x4: FloatArray,
        /** Rows of (x, y in the solved image's pixels, page x mm, page y mm). */
        val correspondences: FloatArray,
    )

    fun addReference(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        referenceWidthUnits: Float,
        physicallyMetric: Boolean,
        pageNo: Int,
        canonicalFromPage: FloatArray,
    ): PhotosphereTrackingSession.RegisteredPage

    /** Match [pageNo] only, or every page for [PlanarMatchRequest.ALL_PAGES]. */
    fun match(luma: ByteBuffer, timestampNs: Long, pageNo: Int, maxCorrespondences: Int): Solve?

    fun cameraFromCanonical(pageNo: Int, cameraFromPage3x4: FloatArray): FloatArray?

    fun fork(): KpmMatcherBackend?

    companion object {
        fun standalone(matchIntrinsics: CameraIntrinsics): KpmMatcherBackend = StandaloneBackend(
            SphereSlamStandaloneSession(matchIntrinsics.width, matchIntrinsics.height, matchIntrinsics.toCalibration()),
        )
    }

    private class StandaloneBackend(private val session: SphereSlamStandaloneSession) : KpmMatcherBackend {
        override fun addReference(
            luma: ByteBuffer,
            width: Int,
            height: Int,
            referenceWidthUnits: Float,
            physicallyMetric: Boolean,
            pageNo: Int,
            canonicalFromPage: FloatArray,
        ): PhotosphereTrackingSession.RegisteredPage {
            val r = session.addReference(
                luma = luma,
                width = width,
                height = height,
                referenceWidthMeters = referenceWidthUnits,
                physicallyMetric = physicallyMetric,
                pageNo = pageNo,
                canonicalFromPage = canonicalFromPage,
            )
            return PhotosphereTrackingSession.RegisteredPage(r.pageNo, r.featureCount, r.geometry, r.physicallyMetric)
        }

        override fun match(luma: ByteBuffer, timestampNs: Long, pageNo: Int, maxCorrespondences: Int): Solve? {
            val p = session.match(luma, timestampNs, PlanarMatchRequest(pageNo, maxCorrespondences)) ?: return null
            return Solve(p.pageNo, p.cameraFromCanonical, p.reprojectionError, p.inlierCount, p.cameraFromPage3x4, p.correspondences)
        }

        override fun cameraFromCanonical(pageNo: Int, cameraFromPage3x4: FloatArray): FloatArray? =
            session.cameraFromCanonical(pageNo, cameraFromPage3x4)

        override fun fork(): KpmMatcherBackend? = session.fork()?.let(::StandaloneBackend)

        override fun close() = session.close()
    }
}

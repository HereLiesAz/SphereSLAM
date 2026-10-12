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

    // Full solves missed in a row, and the next steep-angle guess to rectify (see sweepPlan).
    private var missStreak = 0
    private var sweepTurn = 0
    private var sweepIndex = 0
    private val rect = RectStats()

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
        // Register a reference far larger than the matched frame from a smaller copy (KPM's scale range;
        // see KpmMatchSpeedConfig.maxReferenceScale). The copy spans the same page, so the page's
        // millimetre geometry — from the same referenceWidthUnits — is unchanged.
        val maxLong = if (config.maxReferenceScale > 0f) {
            (config.maxReferenceScale * maxOf(matchIntrinsics.width, matchIntrinsics.height)).toInt()
        } else {
            0
        }
        val (rw, rh) = KpmMatchScale.referenceSize(width, height, maxLong)
        val refLuma = if (rw == width && rh == height) {
            luma
        } else {
            val bytes = ByteArray(width * height).also { luma.duplicate().get(it) }
            KpmMatchScale.resampleArea(bytes, width, height, rw, rh)
        }
        val r = backend.addReference(refLuma, rw, rh, referenceWidthUnits, physicallyMetric, pageNo, canonicalFromPage)
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
        // Oblique: KPM matches a virtual frontal view rendered from the full frame (no downscale needed),
        // from the prediction, or from a guessed steep view once direct solves keep missing.
        val planned = rectificationPlan(predicted)?.let { it to predicted } ?: sweepPlan(predicted)?.let { it to null }
        planned?.let { (plan, prior) ->
            val frame = lumaBytes ?: ByteArray(luma.remaining()).also { luma.duplicate().get(it) }
            val wantCorr = !flowUnavailable && lumaBytes != null && config.maxCorrespondences > 0
            return rectifiedSolve(plan, frame, lumaBytes, timestampNs, nowMs, prior, wantCorr)
        }
        val matchLuma = if (small != null) {
            val bytes = lumaBytes ?: ByteArray(luma.remaining()).also { luma.duplicate().get(it) }
            KpmMatchScale.downsample(bytes, intrinsics.width, intrinsics.height, factor, small)
        } else {
            luma
        }
        val wantCorrespondences = !flowUnavailable && lumaBytes != null && config.maxCorrespondences > 0
        val maxCorr = if (wantCorrespondences) config.maxCorrespondences else 0

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
            missStreak++
            if (nowMs != Long.MIN_VALUE) scheduler.onFullSolve(nowMs, success = false, seeded = false)
            if (!scheduler.isArmed) flow?.reset()
            return null
        }
        missStreak = 0

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
        val frame = planPage()?.let { pageFrames.getValue(it) } ?: return null
        val (r, t) = ObliqueRectifier.pagePose(predicted, frame.canonicalFromPage, frame.centerXmm, frame.centerYmm)
            ?: return null
        val pageCentre = doubleArrayOf(frame.centerXmm.toDouble(), frame.centerYmm.toDouble(), 0.0)
        val plan = ObliqueRectifier.plan(r, t, matchK, config.obliqueRectificationDeg, pageCentre)
        if (plan == null) rect.frontal++ else rect.planned(plan.obliquityDeg)
        return plan
    }

    /**
     * A guessed steep view to rectify, when the prediction gave none and direct solves have missed
     * [KpmMatchSpeedConfig.obliqueSearchAfterMisses] times running: every other full solve, the next
     * of [ObliqueRectifier.hypotheses] in turn. A lost camera's prediction can say "frontal" from the
     * wrong place, or there is none; this keeps a steep relock possible either way, at no cost while
     * locked (flow tracks, and a hit resets the streak).
     */
    private fun sweepPlan(predicted: FloatArray?): ObliqueRectifier.Plan? {
        val after = config.obliqueSearchAfterMisses
        if (config.obliqueRectificationDeg <= 0f || after == 0 || config.obliqueSearchTiltsDeg.isEmpty() || missStreak < after) return null
        if (sweepTurn++ % 2 == 0) return null // alternate with the direct solve
        val frame = planPage()?.let { pageFrames.getValue(it) } ?: return null
        val target = doubleArrayOf(frame.centerXmm.toDouble(), frame.centerYmm.toDouble(), 0.0)
        // As far as the prediction puts the camera, else two page widths.
        val distance = predicted
            ?.let { ObliqueRectifier.pagePose(it, frame.canonicalFromPage, frame.centerXmm, frame.centerYmm) }
            ?.let { (r, t) -> ObliqueRectifier.centreOf(r, t) }
            ?.let { c -> kotlin.math.sqrt((c[0] - target[0]) * (c[0] - target[0]) + (c[1] - target[1]) * (c[1] - target[1]) + c[2] * c[2]) }
            ?.takeIf { it.isFinite() && it > 1.0 }
            ?: (4.0 * frame.centerXmm)
        val guesses = ObliqueRectifier.hypotheses(target, distance, config.obliqueSearchTiltsDeg.toFloatArray())
        if (guesses.isEmpty()) return null
        val (r, t) = guesses[sweepIndex++ % guesses.size]
        return ObliqueRectifier.plan(r, t, matchK, config.obliqueRectificationDeg, target)?.also { rect.swept++ }
    }

    private fun planPage(): Int? = lastLockedPage?.takeIf { it in pageFrames } ?: pageFrames.keys.firstOrNull()

    /** `rect{…}` for the session's periodic diagnostic, then reset; null before any full solve was planned. */
    override fun diagnosticsAndReset(): String? = rect.summaryAndReset()

    /**
     * The full solve of an oblique frame: KPM on its virtual frontal view, the real pose solved from
     * the correspondences mapped back. Same bookkeeping (scheduler, focus, flow seeding) as a direct
     * solve; its correspondences are always requested (the pose is solved from them).
     */
    private fun rectifiedSolve(
        plan: ObliqueRectifier.Plan,
        frame: ByteArray,
        lumaBytes: ByteArray?,
        timestampNs: Long,
        nowMs: Long,
        predicted: FloatArray?,
        wantCorrespondences: Boolean,
    ): PlanarMatchResult? {
        val w = matchIntrinsics.width
        val h = matchIntrinsics.height
        val buffer = rectifiedBuffer ?: ByteBuffer.allocateDirect(w * h).also { rectifiedBuffer = it }
        ObliqueRectifier.warp(frame, intrinsics.width, intrinsics.height, factor, w, h, plan, buffer)
        val pageRequest = lastLockedPage?.takeIf { it in pageFrames } ?: PlanarMatchRequest.ALL_PAGES
        val raw = backend.match(buffer.duplicate(), timestampNs, pageRequest, maxOf(config.maxCorrespondences, MIN_RECTIFIED_CORRESPONDENCES))
        if (raw != null) rect.kpm++
        val solved = raw?.let { m ->
            val frame = pageFrames[m.pageNo] ?: return@let null
            val prior = predicted?.let { ObliqueRectifier.pagePose(it, frame.canonicalFromPage, frame.centerXmm, frame.centerYmm) }
            ObliqueRectifier.solve(plan, m.correspondences, matchK, prior?.first, prior?.let { (pr, pt) -> ObliqueRectifier.centreOf(pr, pt) })
                ?.let { s -> backend.cameraFromCanonical(m.pageNo, s.cameraFromPage3x4)?.let { view -> Triple(m.pageNo, s, view) } }
        }
        if (solved == null) {
            missStreak++
            if (nowMs != Long.MIN_VALUE) scheduler.onFullSolve(nowMs, success = false, seeded = false)
            if (!scheduler.isArmed) flow?.reset()
            return null
        }
        missStreak = 0
        rect.solved++
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

    /** What the rectified path did since the last diagnostic. */
    private class RectStats {
        var planned = 0
        var swept = 0
        var kpm = 0
        var solved = 0
        var frontal = 0
        var maxObliquity = 0.0

        fun planned(obliquityDeg: Double) {
            planned++
            maxObliquity = maxOf(maxObliquity, obliquityDeg)
        }

        /**
         * `rect{planned=P swept=S kpm=K solved=V frontal=F maxObliq=D}`: P frames rectified from the
         * prediction (D its steepest, degrees), S from a guess; KPM matched K of those virtual views
         * and V gave a pose; F predictions were too frontal to rectify. Null when all are zero.
         */
        fun summaryAndReset(): String? {
            if (planned + swept + frontal == 0) return null
            val out = "rect{planned=$planned swept=$swept kpm=$kpm solved=$solved frontal=$frontal " +
                "maxObliq=${String.format(java.util.Locale.ROOT, "%.0f", maxObliquity)}}"
            planned = 0; swept = 0; kpm = 0; solved = 0; frontal = 0; maxObliquity = 0.0
            return out
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

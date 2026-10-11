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
 * [SphereSlamStandaloneSession]'s own page→canonical conversion, whichever path solved it.
 */
@ExperimentalSphereSlamRelocApi
internal class KpmPlanarFrontEnd(
    private val intrinsics: CameraIntrinsics,
    private val config: KpmMatchSpeedConfig,
    private val flowFactory: (CameraIntrinsics, KpmMatchSpeedConfig) -> FlowPoseTracker = ::FlowPoseTracker,
) : PlanarFrontEnd {

    /** Downscale factor KPM matches at for this frame size. */
    val factor = KpmMatchScale.effectiveFactor(intrinsics.width, intrinsics.height, config.matchDownscale, config.minMatchShortSidePx)
    private val matchIntrinsics = KpmMatchScale.scaleIntrinsics(intrinsics, factor)
    private val session = SphereSlamStandaloneSession(matchIntrinsics.width, matchIntrinsics.height, matchIntrinsics.toCalibration())
    private val small: ByteBuffer? =
        if (factor > 1) ByteBuffer.allocateDirect(matchIntrinsics.width * matchIntrinsics.height) else null

    private val scheduler = KpmMatchScheduler(config)
    private val pages = linkedMapOf<Int, KpmPagePriority.PageWindow>()
    private var flow: FlowPoseTracker? = null
    private var flowUnavailable = !config.flowTracking
    private var lastLockedPage: Int? = null
    private var lastLockMs = Long.MIN_VALUE

    override fun addPage(
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
        pages[pageNo] = KpmPagePriority.PageWindow(
            pageNo, canonicalFromPage[12], canonicalFromPage[13], r.geometry.widthMeters, r.geometry.heightMeters,
        )
        return PhotosphereTrackingSession.RegisteredPage(r.pageNo, r.featureCount, r.geometry, r.physicallyMetric)
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

        val msSinceLock = if (lastLockMs == Long.MIN_VALUE || nowMs == Long.MIN_VALUE) null else nowMs - lastLockMs
        val candidates = KpmPagePriority.focusCandidates(pages.values.toList(), predicted, lastLockedPage, msSinceLock, config)
        var method = VisualMatchMethod.KPM_FOCUSED
        var pose: SphereSlamStandaloneSession.Pose? = null
        for (page in candidates) {
            pose = session.match(matchLuma.duplicate(), timestampNs, PlanarMatchRequest(page, maxCorr))
            if (pose != null) break
        }
        if (pose == null) {
            method = VisualMatchMethod.KPM_FULL
            pose = session.match(matchLuma.duplicate(), timestampNs, PlanarMatchRequest(PlanarMatchRequest.ALL_PAGES, maxCorr))
        }
        if (pose == null) {
            if (nowMs != Long.MIN_VALUE) scheduler.onFullSolve(nowMs, success = false, seeded = false)
            if (!scheduler.isArmed) flow?.reset()
            return null
        }

        var seeded = false
        if (wantCorrespondences && lumaBytes != null) {
            val corr = pose.correspondences
            for (i in 0 until corr.size / 4) {
                corr[i * 4] = KpmMatchScale.toFramePixel(corr[i * 4], factor)
                corr[i * 4 + 1] = KpmMatchScale.toFramePixel(corr[i * 4 + 1], factor)
            }
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

    private fun trackFlow(input: PlanarFrameInput): PlanarMatchResult? {
        val tracked = withFlow { it.track(input.lumaBytes) } ?: return null
        val page = flow?.trackedPage ?: return null
        val view = session.cameraFromCanonical(page, tracked.cameraFromPage3x4) ?: return null
        return PlanarMatchResult(
            pageNo = page,
            cameraFromCanonical = view,
            reprojectionError = tracked.meanSquaredErrorPx,
            inlierCount = tracked.inliers,
            method = VisualMatchMethod.FLOW,
        )
    }

    /** Runs [block] on the (lazily created) flow tracker; turns flow off for good without OpenCV. */
    private fun <T> withFlow(block: (FlowPoseTracker) -> T): T? {
        if (flowUnavailable) return null
        return try {
            block(flow ?: flowFactory(intrinsics, config).also { flow = it })
        } catch (e: LinkageError) {
            flowUnavailable = true
            scheduler.disarm()
            null
        }
    }

    override fun close() {
        runCatching { flow?.close() }
        flow = null
        session.close()
    }
}

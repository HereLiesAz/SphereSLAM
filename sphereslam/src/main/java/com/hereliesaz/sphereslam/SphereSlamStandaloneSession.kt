package com.hereliesaz.sphereslam

import java.nio.ByteBuffer

/**
 * Synchronous wall-relative SphereSLAM/KPM session for the non-ARCore runtime.
 *
 * Unlike [SphereSlamTracker], this class is intended to be called from an existing camera-analysis
 * worker and returns the pose for that exact frame immediately. That makes visual-lock/loss state
 * explicit and lets the caller bridge a short miss with IMU orientation without an asynchronous
 * "latest observation" race.
 *
 * KPM supplies the 6-DoF camera-from-wall pose while a registered planar wall page is visible. This
 * is sufficient to drive a wall-locked overlay without an ARCore Session. It is not inertial dead
 * reckoning and it does not claim a pose when the wall cannot be matched.
 *
 * Public render poses use one convention: column-major 4x4 camera-from-canonical transforms. When
 * [Pose.physicallyMetric] is true, translation is in metres.
 */
class SphereSlamStandaloneSession internal constructor(
    val frameWidth: Int,
    val frameHeight: Int,
    val calibration: SphereSlamCalibration,
    private val engineFactory: EngineFactory,
) : AutoCloseable {

    /** Public constructor. Test/implementation injection is intentionally not part of the API. */
    constructor(
        frameWidth: Int,
        frameHeight: Int,
        calibration: SphereSlamCalibration,
    ) : this(
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        calibration = calibration,
        engineFactory = EngineFactory { width, height, c ->
            SphereSlam.create(width, height, c)
        },
    )

    internal fun interface EngineFactory {
        fun create(
            width: Int,
            height: Int,
            calibration: SphereSlamCalibration,
        ): SphereSlamEngine
    }

    /**
     * Metadata for one registered page.
     *
     * [canonicalFromPage] is defensively copied on input and output so callers cannot mutate the
     * session's atlas transform after registration.
     */
    class Reference internal constructor(
        val pageNo: Int,
        val imageNo: Int,
        val geometry: SphereSlamPoseMath.PageGeometry,
        canonicalFromPage: FloatArray,
        val featureCount: Int,
        val physicallyMetric: Boolean,
    ) {
        private val canonicalFromPageValue = canonicalFromPage.copyOf()

        /** Column-major rigid transform from this centered page frame into the canonical wall frame. */
        val canonicalFromPage: FloatArray
            get() = canonicalFromPageValue.copyOf()
    }

    /**
     * One matched frame in the canonical wall coordinate system.
     *
     * [cameraFromCanonical] is column-major 4x4 and is defensively copied on every read.
     */
    class Pose internal constructor(
        val timestampNs: Long,
        val pageNo: Int,
        cameraFromCanonical: FloatArray,
        val reprojectionError: Float,
        val inlierCount: Int,
        val reference: Reference,
        cameraFromPage3x4: FloatArray = FloatArray(0),
        correspondences: FloatArray = FloatArray(0),
    ) {
        private val cameraFromCanonicalValue = cameraFromCanonical.copyOf()
        private val cameraFromPage3x4Value = cameraFromPage3x4.copyOf()
        private val correspondencesValue = correspondences.copyOf()

        /**
         * The raw KPM row-major camera-from-page 3x4 (page millimetres, image-convention camera:
         * x right, y down, z forward) this pose was converted from; empty when unknown.
         */
        val cameraFromPage3x4: FloatArray
            get() = cameraFromPage3x4Value.copyOf()

        /** [PlanarMatch.correspondences] of the match (rows of frame x, y, page x, y mm), or empty. */
        val correspondences: FloatArray
            get() = correspondencesValue.copyOf()

        init {
            require(cameraFromCanonicalValue.size == 16)
            require(cameraFromCanonicalValue.all { it.isFinite() })
        }

        /** Column-major camera-from-canonical transform. */
        val cameraFromCanonical: FloatArray
            get() = cameraFromCanonicalValue.copyOf()

        /**
         * Legacy rendering name. Prefer [cameraFromCanonical], which states the transform direction
         * and coordinate frame explicitly.
         */
        @Deprecated("Use cameraFromCanonical", ReplaceWith("cameraFromCanonical"))
        val viewMatrix: FloatArray
            get() = cameraFromCanonical

        val physicallyMetric: Boolean
            get() = reference.physicallyMetric
    }

    private var engine: SphereSlamEngine = newEngine()
    private val references = linkedMapOf<Int, Reference>()
    private var closed = false

    val isReady: Boolean get() = !closed && engine.isReady
    val hasReference: Boolean get() = references.isNotEmpty()

    /**
     * Add a planar wall reference.
     *
     * @param referenceWidthMeters width represented by the whole reference image. Pass a measured
     * physical width and [physicallyMetric]=true for real metric translation. Callers may use a
     * normalized width such as 1f with [physicallyMetric]=false for visual-only registration.
     * @param canonicalFromPage column-major rigid transform placing this page in the canonical wall
     * frame. The value is copied before it is retained.
     * @throws IllegalArgumentException if [canonicalFromPage] is not a finite rigid transform
     * (affine bottom row, orthonormal proper rotation); nothing is registered in that case.
     */
    fun addReference(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        referenceWidthMeters: Float,
        physicallyMetric: Boolean,
        pageNo: Int = 0,
        imageNo: Int = 0,
        maxFeatures: Int = 5000,
        canonicalFromPage: FloatArray = SphereSlamPoseMath.identity4(),
    ): Reference {
        requireOpen()
        require(!references.containsKey(pageNo)) { "page $pageNo is already registered" }
        // Validate rigidity here, at registration, rather than on the first match that uses it.
        SphereSlamPoseMath.requireRigid4(canonicalFromPage, "canonicalFromPage", requireOrthonormal = true)
        val dpi = SphereSlamPoseMath.dpiForReferenceWidth(width, referenceWidthMeters)
        val geometry = SphereSlamPoseMath.pageGeometry(width, height, dpi)
        val featureCount = engine.addPage(
            luma,
            width,
            height,
            PlanarPage(
                pageNo = pageNo,
                imageNo = imageNo,
                referenceDpi = dpi,
                maxFeatures = maxFeatures,
            ),
        )
        return Reference(
            pageNo = pageNo,
            imageNo = imageNo,
            geometry = geometry,
            canonicalFromPage = canonicalFromPage,
            featureCount = featureCount,
            physicallyMetric = physicallyMetric,
        ).also { references[pageNo] = it }
    }

    /**
     * Adds a preview of registered page [pageNo]: [luma] ([width] × [height]) is the page's reference
     * image seen through a warp, and [pageFromView] (row-major 3×3) maps a preview pixel back to the
     * page's reference pixel. KPM learns the preview's features as image [imageNo] (≥ 1) of the page,
     * at the page's own millimetres, so matching a frame that looks like the preview solves the page's
     * pose. [reindex] false defers the matcher's re-indexing to the next view that asks, so a batch
     * indexes once; don't match in between. Returns the features added, or < 0 when the engine cannot
     * or KPM refused (−8: the session holds KPM's limit of 1024 reference images).
     */
    fun addReferenceView(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        pageNo: Int,
        imageNo: Int,
        pageFromView: FloatArray,
        maxFeatures: Int = 1000,
        reindex: Boolean = true,
    ): Int {
        requireOpen()
        val reference = requireNotNull(references[pageNo]) { "page $pageNo is not registered" }
        val g = reference.geometry
        val heightPx = Math.round(g.heightMeters * 1000f / 25.4f * g.referenceDpi)
        return engine.addPageView(luma, width, height, PlanarPageView(pageNo, imageNo, maxFeatures, pageFromView, heightPx, g.referenceDpi, reindex))
    }

    /**
     * Re-indexes every reference and view added so far: ends a batch of [addReferenceView] calls made
     * with `reindex = false`. Returns 0, or < 0 (unsupported or failed).
     */
    fun reindexReferences(): Int {
        requireOpen()
        return engine.reindex()
    }

    /**
     * Match one tightly packed direct luma frame.
     *
     * @return a canonical camera pose for this exact frame, or null when no registered page matched.
     */
    fun match(luma: ByteBuffer, timestampNs: Long): Pose? = match(luma, timestampNs, PlanarMatchRequest())

    /**
     * [match] with a [PlanarMatchRequest]: optionally restricted to one registered page and/or
     * returning inlier correspondences ([Pose.correspondences]). The pose convention is identical.
     */
    fun match(luma: ByteBuffer, timestampNs: Long, request: PlanarMatchRequest): Pose? {
        requireOpen()
        if (references.isEmpty()) return null
        if (request.pageNo != PlanarMatchRequest.ALL_PAGES && !references.containsKey(request.pageNo)) return null
        val match = engine.match(luma, request) ?: return null
        val reference = references[match.pageNo] ?: return null
        val cameraFromPage3x4 = match.cameraFromPage3x4
        return Pose(
            timestampNs = timestampNs,
            pageNo = match.pageNo,
            cameraFromCanonical = canonicalView(reference, cameraFromPage3x4),
            reprojectionError = match.reprojectionError,
            inlierCount = match.inlierCount,
            reference = reference,
            cameraFromPage3x4 = cameraFromPage3x4,
            correspondences = match.correspondences,
        )
    }

    /**
     * An independent session holding every page registered here (same frame size and calibration),
     * for growing the atlas off the matching thread: add pages to the fork with [addReference] (its
     * first add indexes the copied pages plus the new one), then swap it in and close this session.
     * A fork that is never added to matches nothing.
     *
     * May be called from another thread while this session's [match] runs (the native copy is
     * serialized against matching per session; nothing else here is mutated), but not concurrently
     * with [addReference], [reset] or [close] on this session. Returns null when this session is
     * closed or its engine cannot be cloned (e.g. a non-KPM engine); callers then rebuild from pages.
     */
    fun fork(): SphereSlamStandaloneSession? {
        if (closed) return null
        val engine = this.engine as? KpmSphereSlamEngine ?: return null
        val forked = engine.fork() ?: return null
        return SphereSlamStandaloneSession(
            frameWidth, frameHeight, calibration, forkedEngineFactory(forked, engineFactory), references.values.toList(),
        )
    }

    private constructor(
        frameWidth: Int,
        frameHeight: Int,
        calibration: SphereSlamCalibration,
        engineFactory: EngineFactory,
        pages: List<Reference>,
    ) : this(frameWidth, frameHeight, calibration, engineFactory) {
        pages.forEach { references[it.pageNo] = it }
    }

    private companion object {
        /** Hands out [forked] once (this fork's first engine), then defers to [fallback] (e.g. [reset]). */
        fun forkedEngineFactory(forked: SphereSlamEngine, fallback: EngineFactory): EngineFactory {
            var first: SphereSlamEngine? = forked
            return EngineFactory { w, h, c -> first?.also { first = null } ?: fallback.create(w, h, c) }
        }
    }

    /**
     * The column-major GL camera-from-canonical for a raw KPM-convention camera-from-page 3x4 of
     * registered page [pageNo] — the exact conversion [match] applies. Lets a caller that solved the
     * page pose itself (e.g. frame-to-frame tracking against [Pose.correspondences]) report it in the
     * same convention. Null when [pageNo] is not registered or the pose is not finite.
     */
    fun cameraFromCanonical(pageNo: Int, cameraFromPage3x4: FloatArray): FloatArray? {
        require(cameraFromPage3x4.size == 12) { "KPM pose must contain 12 floats" }
        val reference = references[pageNo] ?: return null
        if (cameraFromPage3x4.any { !it.isFinite() }) return null
        return canonicalView(reference, cameraFromPage3x4)
    }

    private fun canonicalView(reference: Reference, cameraFromPage3x4: FloatArray): FloatArray {
        val g = reference.geometry
        val cameraFromPage = SphereSlamPoseMath.pageToOpenGlViewMeters(
            cameraFromPage3x4,
            pageCenterXmm = g.centerXmm,
            pageCenterYmm = g.centerYmm,
        )
        return SphereSlamPoseMath.pageViewToCanonicalView(cameraFromPage, reference.canonicalFromPage)
    }

    /** Drop the atlas and create a fresh native matcher with the same live-camera calibration. */
    fun reset() {
        requireOpen()
        engine.close()
        references.clear()
        engine = newEngine()
    }

    override fun close() {
        if (closed) return
        closed = true
        references.clear()
        engine.close()
    }

    private fun newEngine(): SphereSlamEngine {
        require(frameWidth > 0 && frameHeight > 0)
        return engineFactory.create(frameWidth, frameHeight, calibration)
    }

    private fun requireOpen() {
        check(!closed) { "standalone SphereSLAM session is closed" }
        check(engine.isReady) { "standalone SphereSLAM native engine is unavailable" }
    }
}

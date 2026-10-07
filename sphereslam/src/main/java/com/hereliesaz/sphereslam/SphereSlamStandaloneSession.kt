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
    ) {
        private val cameraFromCanonicalValue = cameraFromCanonical.copyOf()

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
        require(canonicalFromPage.size == 16 && canonicalFromPage.all { it.isFinite() }) {
            "canonicalFromPage must be a finite 4x4 transform"
        }
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
     * Match one tightly packed direct luma frame.
     *
     * @return a canonical camera pose for this exact frame, or null when no registered page matched.
     */
    fun match(luma: ByteBuffer, timestampNs: Long): Pose? {
        requireOpen()
        if (references.isEmpty()) return null
        val match = engine.match(luma) ?: return null
        val reference = references[match.pageNo] ?: return null
        val g = reference.geometry
        val cameraFromPage = SphereSlamPoseMath.pageToOpenGlViewMeters(
            match.cameraFromPage3x4,
            pageCenterXmm = g.centerXmm,
            pageCenterYmm = g.centerYmm,
        )
        return Pose(
            timestampNs = timestampNs,
            pageNo = match.pageNo,
            cameraFromCanonical = SphereSlamPoseMath.pageViewToCanonicalView(
                cameraFromPage,
                reference.canonicalFromPage,
            ),
            reprojectionError = match.reprojectionError,
            inlierCount = match.inlierCount,
            reference = reference,
        )
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

package com.hereliesaz.sphereslam

import java.nio.ByteBuffer

/**
 * Pixel-space camera calibration for a SphereSLAM frame.
 *
 * Distortion is currently assumed zero. The values must describe the same orientation and pixel
 * dimensions as the luma frames passed to the engine.
 */
data class SphereSlamCalibration(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
) {
    init {
        require(fx.isFinite() && fy.isFinite() && fx > 0f && fy > 0f)
        require(cx.isFinite() && cy.isFinite())
    }
}

/**
 * One planar KPM reference image.
 *
 * KPM uses [referenceDpi] to map pixels onto its planar coordinate system in millimetres. The
 * returned translation scale is physically metric only when [referenceDpi] is physically correct.
 */
data class PlanarPage(
    val pageNo: Int,
    val imageNo: Int = 0,
    val referenceDpi: Float = 72f,
    val maxFeatures: Int = 5000,
) {
    init {
        require(pageNo >= 0)
        require(imageNo >= 0)
        require(referenceDpi.isFinite() && referenceDpi > 0f)
        require(maxFeatures > 0)
    }
}

/**
 * Result of calibrated planar KPM tracking.
 *
 * [cameraFromPage3x4] is artoolkitX's **row-major camera-from-page** 3x4 transform. Translation is in
 * KPM page millimetres. It is copied on input/output so consumers cannot mutate an engine result
 * after publication.
 */
class PlanarMatch(
    val pageNo: Int,
    cameraFromPage3x4: FloatArray,
    val reprojectionError: Float,
    val inlierCount: Int,
    correspondences: FloatArray,
) {
    /** A match without correspondences (the original constructor). */
    constructor(pageNo: Int, cameraFromPage3x4: FloatArray, reprojectionError: Float, inlierCount: Int) :
        this(pageNo, cameraFromPage3x4, reprojectionError, inlierCount, FloatArray(0))

    private val cameraFromPage3x4Value = cameraFromPage3x4.copyOf()
    private val correspondencesValue = correspondences.copyOf()

    init {
        require(pageNo >= 0)
        require(cameraFromPage3x4Value.size == 12)
        require(cameraFromPage3x4Value.all { it.isFinite() })
        require(reprojectionError.isFinite())
        require(inlierCount >= 0)
        require(correspondencesValue.size % 4 == 0) { "correspondences are rows of 4 floats" }
    }

    /**
     * Inlier correspondences of this match when requested ([PlanarMatchRequest.maxCorrespondences]),
     * else empty: rows of 4 floats — frame x, y (pixels of the matched frame) then page x, y (KPM page
     * millimetres, z = 0, the frame [cameraFromPage3x4] maps from). A fresh copy on every read.
     */
    val correspondences: FloatArray
        get() = correspondencesValue.copyOf()

    /** Number of rows in [correspondences]. */
    val correspondenceCount: Int
        get() = correspondencesValue.size / 4

    /** Row-major camera-from-page 3x4 transform. A fresh copy is returned on every read. */
    val cameraFromPage3x4: FloatArray
        get() = cameraFromPage3x4Value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PlanarMatch &&
            pageNo == other.pageNo &&
            cameraFromPage3x4Value.contentEquals(other.cameraFromPage3x4Value) &&
            reprojectionError == other.reprojectionError &&
            inlierCount == other.inlierCount &&
            correspondencesValue.contentEquals(other.correspondencesValue)

    override fun hashCode(): Int {
        var result = pageNo
        result = 31 * result + cameraFromPage3x4Value.contentHashCode()
        result = 31 * result + reprojectionError.hashCode()
        result = 31 * result + inlierCount
        result = 31 * result + correspondencesValue.contentHashCode()
        return result
    }
}

/**
 * Options for [SphereSlamEngine.match] beyond the plain all-pages match.
 *
 * @property pageNo `>= 0` restricts matching to that registered page (cheaper with a multi-page
 *   atlas: the query is searched and verified against one page only); `-1` matches every page.
 *   Engines without page restriction may ignore it and match all pages.
 * @property maxCorrespondences how many inlier correspondences to return in
 *   [PlanarMatch.correspondences] (0 = none). Engines may return fewer, or none.
 */
data class PlanarMatchRequest(
    val pageNo: Int = ALL_PAGES,
    val maxCorrespondences: Int = 0,
) {
    init {
        require(pageNo >= ALL_PAGES)
        require(maxCorrespondences >= 0)
    }

    companion object {
        const val ALL_PAGES = -1
    }
}

/**
 * Native calibrated planar-tracker API shared by both runtime modes.
 *
 * This is the lowest supported planar API. Most applications should prefer
 * [SphereSlamStandaloneSession] or [SphereSlamTracker].
 */
interface SphereSlamEngine : AutoCloseable {
    val frameWidth: Int
    val frameHeight: Int
    val calibration: SphereSlamCalibration
    val isReady: Boolean

    fun addPage(luma: ByteBuffer, width: Int, height: Int, page: PlanarPage): Int
    fun match(luma: ByteBuffer): PlanarMatch?

    /** [match] with a [PlanarMatchRequest]; the default ignores the request and matches all pages. */
    fun match(luma: ByteBuffer, request: PlanarMatchRequest): PlanarMatch? = match(luma)

    override fun close()
}

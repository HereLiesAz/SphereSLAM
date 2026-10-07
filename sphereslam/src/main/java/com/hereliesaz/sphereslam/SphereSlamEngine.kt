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
) {
    private val cameraFromPage3x4Value = cameraFromPage3x4.copyOf()

    init {
        require(pageNo >= 0)
        require(cameraFromPage3x4Value.size == 12)
        require(cameraFromPage3x4Value.all { it.isFinite() })
        require(reprojectionError.isFinite())
        require(inlierCount >= 0)
    }

    /** Row-major camera-from-page 3x4 transform. A fresh copy is returned on every read. */
    val cameraFromPage3x4: FloatArray
        get() = cameraFromPage3x4Value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PlanarMatch &&
            pageNo == other.pageNo &&
            cameraFromPage3x4Value.contentEquals(other.cameraFromPage3x4Value) &&
            reprojectionError == other.reprojectionError &&
            inlierCount == other.inlierCount

    override fun hashCode(): Int {
        var result = pageNo
        result = 31 * result + cameraFromPage3x4Value.contentHashCode()
        result = 31 * result + reprojectionError.hashCode()
        result = 31 * result + inlierCount
        return result
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

    override fun close()
}

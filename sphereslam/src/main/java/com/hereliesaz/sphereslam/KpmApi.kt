package com.hereliesaz.sphereslam

import com.hereliesaz.sphereslam.nativebridge.KpmBridge
import java.nio.ByteBuffer

internal interface KpmApi {
    fun create(width: Int, height: Int, calibration: SphereSlamCalibration): Long
    fun addPage(
        session: Long,
        luma: ByteBuffer,
        width: Int,
        height: Int,
        referenceDpi: Float,
        pageNo: Int,
        imageNo: Int,
        maxFeatures: Int,
    ): Int
    fun match(session: Long, luma: ByteBuffer, out: FloatArray): Int

    /**
     * [KpmBridge.matchPlanarEx]: optional single-page restriction ([pageNo] >= 0) and inlier
     * correspondences. The default ignores both (all pages, zero correspondences in out[14]).
     */
    fun matchEx(session: Long, luma: ByteBuffer, pageNo: Int, out: FloatArray, correspondences: FloatArray?): Int {
        val page = match(session, luma, out)
        if (out.size > 14) out[14] = 0f
        return page
    }

    fun destroy(session: Long)

    /** A copy of [session] (calibration + atlas) that is indexed on its first [addPage]; 0 = unsupported/failed. */
    fun clone(session: Long): Long = 0L
}

internal object NativeKpmApi : KpmApi {
    override fun create(width: Int, height: Int, calibration: SphereSlamCalibration): Long =
        KpmBridge.createCalibratedSession(
            width,
            height,
            calibration.fx,
            calibration.fy,
            calibration.cx,
            calibration.cy,
        )

    override fun addPage(
        session: Long,
        luma: ByteBuffer,
        width: Int,
        height: Int,
        referenceDpi: Float,
        pageNo: Int,
        imageNo: Int,
        maxFeatures: Int,
    ): Int = KpmBridge.addPlanarPage(
        session,
        luma,
        width,
        height,
        referenceDpi,
        pageNo,
        imageNo,
        maxFeatures,
    )

    override fun match(session: Long, luma: ByteBuffer, out: FloatArray): Int =
        KpmBridge.matchPlanar(session, luma, out)

    override fun matchEx(
        session: Long,
        luma: ByteBuffer,
        pageNo: Int,
        out: FloatArray,
        correspondences: FloatArray?,
    ): Int = KpmBridge.matchPlanarEx(session, luma, pageNo, out, correspondences)

    override fun destroy(session: Long) = KpmBridge.destroySession(session)

    override fun clone(session: Long): Long = KpmBridge.cloneSession(session)
}

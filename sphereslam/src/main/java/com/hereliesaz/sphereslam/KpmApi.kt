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

    /** [KpmBridge.reindex]; the default cannot (−1). */
    fun reindex(session: Long): Int = -1

    /** [KpmBridge.addPlanarPageView]; the default does not support previews. */
    fun addPageView(
        session: Long,
        luma: ByteBuffer,
        width: Int,
        height: Int,
        view: PlanarPageView,
    ): Int = -1

    /**
     * [KpmBridge.matchPlanarEx]: optional single-page restriction ([pageNo] >= 0) and inlier
     * correspondences. The default ignores both (all pages, zero correspondences in out[14]).
     */
    fun matchEx(session: Long, luma: ByteBuffer, pageNo: Int, out: FloatArray, correspondences: FloatArray?): Int {
        val page = match(session, luma, out)
        if (out.size > 14) out[14] = 0f
        return page
    }

    /**
     * [KpmBridge.matchPlanarImages]: [matchEx] against only the images named by [pageNos] / [imageNos].
     * The default cannot (−1).
     */
    fun matchImages(session: Long, luma: ByteBuffer, pageNos: IntArray, imageNos: IntArray, out: FloatArray, correspondences: FloatArray?): Int = -1

    fun destroy(session: Long)

    /** Remove every page from [session]'s atlas, keeping its calibration; false = unsupported/failed. */
    fun clear(session: Long): Boolean = false

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

    override fun reindex(session: Long): Int = KpmBridge.reindex(session)

    override fun addPageView(session: Long, luma: ByteBuffer, width: Int, height: Int, view: PlanarPageView): Int =
        KpmBridge.addPlanarPageView(
            session, luma, width, height, view.pageNo, view.imageNo, view.maxFeatures,
            view.pageFromView, view.pageHeightPixels, view.referenceDpi, view.reindex,
        )

    override fun matchEx(
        session: Long,
        luma: ByteBuffer,
        pageNo: Int,
        out: FloatArray,
        correspondences: FloatArray?,
    ): Int = KpmBridge.matchPlanarEx(session, luma, pageNo, out, correspondences)

    override fun matchImages(
        session: Long,
        luma: ByteBuffer,
        pageNos: IntArray,
        imageNos: IntArray,
        out: FloatArray,
        correspondences: FloatArray?,
    ): Int = KpmBridge.matchPlanarImages(session, luma, pageNos, imageNos, out, correspondences)

    override fun destroy(session: Long) = KpmBridge.destroySession(session)

    override fun clear(session: Long): Boolean = KpmBridge.clearPages(session)

    override fun clone(session: Long): Long = KpmBridge.cloneSession(session)
}

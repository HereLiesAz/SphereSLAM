package com.hereliesaz.sphereslam

import java.nio.ByteBuffer

internal class KpmSphereSlamEngine private constructor(
    override val frameWidth: Int,
    override val frameHeight: Int,
    override val calibration: SphereSlamCalibration,
    private val api: KpmApi,
    initialSession: Long,
) : SphereSlamEngine {
    constructor(
        frameWidth: Int,
        frameHeight: Int,
        calibration: SphereSlamCalibration,
        api: KpmApi,
    ) : this(frameWidth, frameHeight, calibration, api, api.create(frameWidth, frameHeight, calibration))

    // Volatile: [fork] may run on a background thread while the owner thread matches or closes.
    @Volatile
    private var session: Long = initialSession
    private val scratch = FloatArray(15)

    override val isReady: Boolean
        get() = session != 0L

    override fun addPage(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        page: PlanarPage,
    ): Int {
        val active = requireOpen()
        val packed = packedView(luma, width, height)
        val result = api.addPage(
            active,
            packed,
            width,
            height,
            page.referenceDpi,
            page.pageNo,
            page.imageNo,
            page.maxFeatures,
        )
        check(result >= 0) { "KPM failed to add planar page: error $result" }
        return result
    }

    override fun match(luma: ByteBuffer): PlanarMatch? {
        val active = requireOpen()
        val packed = packedView(luma, frameWidth, frameHeight)
        val pageNo = api.match(active, packed, scratch)
        if (pageNo < 0) return null

        return PlanarMatch(
            pageNo = pageNo,
            cameraFromPage3x4 = scratch.copyOfRange(0, 12),
            reprojectionError = scratch[12],
            inlierCount = scratch[13].toInt().coerceAtLeast(0),
        )
    }

    /**
     * An independent engine holding a copy of this one's atlas (same calibration), or null when the
     * native layer cannot clone. Safe to call from another thread while this engine matches: the
     * native source session is locked only for the copy. The fork's own index is built by its first
     * [addPage], on the caller's thread.
     */
    fun fork(): KpmSphereSlamEngine? {
        val active = session
        if (active == 0L) return null
        val cloned = api.clone(active)
        if (cloned == 0L) return null
        return KpmSphereSlamEngine(frameWidth, frameHeight, calibration, api, cloned)
    }

    override fun match(luma: ByteBuffer, request: PlanarMatchRequest): PlanarMatch? {
        if (request.pageNo == PlanarMatchRequest.ALL_PAGES && request.maxCorrespondences == 0) return match(luma)
        val active = requireOpen()
        val packed = packedView(luma, frameWidth, frameHeight)
        val corr = if (request.maxCorrespondences > 0) FloatArray(request.maxCorrespondences * 4) else null
        scratch[14] = 0f
        val pageNo = api.matchEx(active, packed, request.pageNo, scratch, corr)
        if (pageNo < 0) return null
        val written = scratch[14].toInt().coerceIn(0, request.maxCorrespondences)
        return PlanarMatch(
            pageNo = pageNo,
            cameraFromPage3x4 = scratch.copyOfRange(0, 12),
            reprojectionError = scratch[12],
            inlierCount = scratch[13].toInt().coerceAtLeast(0),
            correspondences = corr?.copyOf(written * 4) ?: FloatArray(0),
        )
    }

    override fun close() {
        val active = session
        if (active == 0L) return
        session = 0L
        api.destroy(active)
    }

    private fun requireOpen(): Long {
        check(session != 0L) { "SphereSLAM session is unavailable or already closed" }
        return session
    }

    private fun packedView(buffer: ByteBuffer, width: Int, height: Int): ByteBuffer {
        require(width > 0 && height > 0)
        require(buffer.isDirect) { "KPM requires a direct luminance ByteBuffer" }
        val required = width.toLong() * height.toLong()
        require(required <= Int.MAX_VALUE)
        require(buffer.remaining() >= required.toInt()) {
            "Luma buffer has ${buffer.remaining()} bytes; need $required"
        }
        return buffer.slice().apply { limit(required.toInt()) }
    }
}

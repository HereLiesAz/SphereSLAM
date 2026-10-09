package com.hereliesaz.sphereslam.nativebridge

import com.hereliesaz.sphereslam.common.InternalSphereSlamApi
import com.hereliesaz.sphereslam.common.util.NativeLibLoader
import java.nio.ByteBuffer

/**
 * Low-level JNI bridge to the forked artoolkitX KPM tracker.
 *
 * This is the native primitive used by :sphereslam in both hybrid and standalone tracking modes.
 * ARCore remains a separate first-class pose source in feature/ar when the device supports it.
 *
 * The pinned artoolkitX binary/FREAK matcher requires camera calibration even though it exposes a
 * homography-handle constructor, so runtime sessions are deliberately calibrated from fx/fy/cx/cy.
 * When the artoolkitX submodule is absent, every KPM entry point safely reports unavailable.
 *
 * ABI support: libsphereslam is built for `arm64-v8a` and `armeabi-v7a` only. On any other ABI
 * (e.g. x86/x86_64 emulators), or whenever the native library fails to load for any reason, class
 * initialization still succeeds and [isAvailable] returns false; no ExceptionInInitializerError is
 * thrown. Callers must check [isAvailable] before using the session entry points, which otherwise
 * fail with UnsatisfiedLinkError.
 */
@InternalSphereSlamApi
object KpmBridge {
    /** Whether libsphereslam loaded. Never throws; see [NativeLibLoader.loadAll]. */
    private val nativeLoaded: Boolean = NativeLibLoader.loadAll()

    fun isAvailable(): Boolean =
        nativeLoaded && runCatching { nativeKpmAvailable() }.getOrDefault(false)

    fun smokeTest(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && nativeKpmSmokeTest(width, height)

    /**
     * Creates an independent calibrated KPM session. Distortion is currently treated as zero; the
     * caller must supply intrinsics in the exact pixel coordinate system of the luma frames.
     */
    fun createCalibratedSession(
        width: Int,
        height: Int,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
    ): Long {
        require(width > 0 && height > 0)
        require(fx > 0f && fy > 0f)
        require(cx.isFinite() && cy.isFinite())
        return nativeCreateCalibratedSession(width, height, fx, fy, cx, cy)
    }

    /**
     * Adds one planar reference image to the session's KPM atlas.
     *
     * luma must be a direct, tightly packed width*height luminance buffer. KPM converts reference
     * pixels to planar millimetres using referenceDpi, so translation scale is only as accurate as
     * that value.
     */
    fun addPlanarPage(
        session: Long,
        luma: ByteBuffer,
        width: Int,
        height: Int,
        referenceDpi: Float,
        pageNo: Int,
        imageNo: Int,
        maxFeatures: Int,
    ): Int {
        require(session != 0L)
        require(luma.isDirect)
        require(width > 0 && height > 0)
        require(referenceDpi > 0f)
        require(pageNo >= 0)
        require(imageNo >= 0)
        require(maxFeatures > 0)
        return nativeAddPlanarPage(
            session,
            luma,
            width,
            height,
            referenceDpi,
            pageNo,
            imageNo,
            maxFeatures,
        )
    }

    /**
     * Matches one tightly packed direct luma frame.
     *
     * On success returns pageNo and writes 14 floats to out:
     * 0..11 = artoolkitX camera-from-reference-plane 3x4 transform (row-major),
     * 12 = reprojection error, 13 = inlier count.
     */
    fun matchPlanar(session: Long, luma: ByteBuffer, out: FloatArray): Int {
        require(session != 0L)
        require(luma.isDirect)
        require(out.size >= MATCH_OUTPUT_FLOATS)
        return nativeMatchPlanar(session, luma, out)
    }

    /**
     * Drops every page from the session's atlas, keeping its calibration. After this, [matchPlanar]
     * reports no match until a page is added again with [addPlanarPage]; previously added pages can
     * never match again. Returns false when the session is unknown or the matcher could not be
     * rebuilt.
     */
    fun clearPages(session: Long): Boolean {
        require(session != 0L)
        return nativeClearPages(session)
    }

    fun destroySession(session: Long) {
        if (session != 0L) nativeDestroySession(session)
    }

    private external fun nativeKpmAvailable(): Boolean
    private external fun nativeKpmSmokeTest(width: Int, height: Int): Boolean
    private external fun nativeCreateCalibratedSession(
        width: Int,
        height: Int,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
    ): Long
    private external fun nativeAddPlanarPage(
        session: Long,
        luma: ByteBuffer,
        width: Int,
        height: Int,
        referenceDpi: Float,
        pageNo: Int,
        imageNo: Int,
        maxFeatures: Int,
    ): Int
    private external fun nativeMatchPlanar(session: Long, luma: ByteBuffer, out: FloatArray): Int
    private external fun nativeClearPages(session: Long): Boolean
    private external fun nativeDestroySession(session: Long)

    const val MATCH_OUTPUT_FLOATS = 14
}

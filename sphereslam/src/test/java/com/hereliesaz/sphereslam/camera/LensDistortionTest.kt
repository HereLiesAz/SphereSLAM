package com.hereliesaz.sphereslam.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [LensDistortion]'s pure parts: correction-mode choice and the residual of the pinhole model. */
class LensDistortionTest {

    private val k = CameraIntrinsics(fx = 500f, fy = 500f, cx = 319.5f, cy = 239.5f, width = 640, height = 480)

    @Test
    fun `FAST is preferred for tracking, then HIGH_QUALITY, never OFF`() {
        val off = CameraMetadata.DISTORTION_CORRECTION_MODE_OFF
        val fast = CameraMetadata.DISTORTION_CORRECTION_MODE_FAST
        val hq = CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        assertEquals(fast, LensDistortion.chooseCorrectionMode(intArrayOf(off, hq, fast)))
        assertEquals(hq, LensDistortion.chooseCorrectionMode(intArrayOf(off, hq)))
        assertNull(LensDistortion.chooseCorrectionMode(intArrayOf(off)))
        assertNull(LensDistortion.chooseCorrectionMode(IntArray(0)))
    }

    @Test
    fun `pure radial distortion peaks at the corners as k1 r cubed`() {
        val k1 = 0.05f
        val residual = LensDistortion.maxResidualPx(floatArrayOf(k1, 0f, 0f, 0f, 0f), k)
        // Corner (0, 0): r = |(−319.5, −239.5)| / 500; displacement = k1 r³ · f.
        val r = kotlin.math.hypot(319.5, 239.5) / 500.0
        assertEquals(k1 * r * r * r * 500.0, residual.toDouble(), 1e-3)
    }

    @Test
    fun `tangential terms follow the Camera2 model`() {
        // κ4 alone: x_d − x = 2κ4·x·y, y_d − y = κ4(r² + 2y²).
        val d = LensDistortion.distort(floatArrayOf(0f, 0f, 0f, 0.01f, 0f), 0.3, 0.2)
        assertEquals(0.3 + 2 * 0.01 * 0.3 * 0.2, d[0], 1e-9)
        assertEquals(0.2 + 0.01 * (0.13 + 2 * 0.04), d[1], 1e-9)
        // κ5 alone mirrors it.
        val e = LensDistortion.distort(floatArrayOf(0f, 0f, 0f, 0f, 0.01f), 0.3, 0.2)
        assertEquals(0.3 + 0.01 * (0.13 + 2 * 0.09), e[0], 1e-9)
        assertEquals(0.2 + 2 * 0.01 * 0.3 * 0.2, e[1], 1e-9)
    }

    @Test
    fun `no or zero coefficients mean no residual`() {
        assertEquals(0f, LensDistortion.maxResidualPx(null, k), 0f)
        assertEquals(0f, LensDistortion.maxResidualPx(FloatArray(5), k), 0f)
    }

    @Test
    fun `the description names the applied correction or the residual`() {
        val profile = LensDistortion.Profile(floatArrayOf(0.05f, 0f, 0f, 0f, 0f), intArrayOf(0, 1))
        assertEquals("correction=FAST", LensDistortion.describe(profile, CameraMetadata.DISTORTION_CORRECTION_MODE_FAST, k))
        assertEquals("uncorrected residual≤12.7px", LensDistortion.describe(profile, null, k))
        assertEquals(
            "uncorrected coefficients=unreported",
            LensDistortion.describe(LensDistortion.Profile(null, IntArray(0)), null, k),
        )
    }
}

package com.hereliesaz.sphereslam.camera

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure math behind [CameraIntrinsicsEstimator] (ported from GraffitiXR, plus the aspect-crop fix). */
class CameraIntrinsicsEstimatorTest {

    @Test
    fun `prefers LENS_INTRINSIC_CALIBRATION when present, rescaled to the target size`() {
        // Native 4032x3024 calibration, requested at a 1/4-scale 1008x756 analysis frame.
        val result = CameraIntrinsicsEstimator.computeIntrinsics(
            calibration = floatArrayOf(3200f, 3200f, 2016f, 1512f, 0f),
            pixelArrayWidth = 4032,
            pixelArrayHeight = 3024,
            focalLengthMm = null,
            sensorWidthMm = null,
            sensorHeightMm = null,
            targetWidth = 1008,
            targetHeight = 756,
        )
        assertEquals(CameraIntrinsics(fx = 800f, fy = 800f, cx = 504f, cy = 378f, width = 1008, height = 756), result)
    }

    @Test
    fun `falls back to focal-length and sensor-size when no calibration is present`() {
        val result = CameraIntrinsicsEstimator.computeIntrinsics(
            calibration = null,
            pixelArrayWidth = 4032,
            pixelArrayHeight = 3024,
            focalLengthMm = 4.25f,
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            targetWidth = 4032,
            targetHeight = 3024,
        )
        requireNotNull(result)
        // fx = focalLengthMm * pixelArrayWidthPx / sensorWidthMm = 4.25 * 4032 / 6.4
        assertTrue(abs(result.fx - (4.25f * 4032f / 6.4f)) < 1e-2f)
        assertTrue(abs(result.fy - (4.25f * 3024f / 4.8f)) < 1e-2f)
        // Principal point assumed centered when falling back.
        assertEquals(2016f, result.cx, 1e-2f)
        assertEquals(1512f, result.cy, 1e-2f)
    }

    @Test
    fun `falls back when calibration has fewer than 4 elements`() {
        val result = CameraIntrinsicsEstimator.computeIntrinsics(
            calibration = floatArrayOf(1f, 2f, 3f), // truncated/malformed
            pixelArrayWidth = 4032,
            pixelArrayHeight = 3024,
            focalLengthMm = 4.25f,
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            targetWidth = 4032,
            targetHeight = 3024,
        )
        requireNotNull(result)
        assertTrue(abs(result.fx - (4.25f * 4032f / 6.4f)) < 1e-2f)
    }

    @Test
    fun `rescales the fallback estimate to a smaller analysis frame`() {
        val result = CameraIntrinsicsEstimator.computeIntrinsics(
            calibration = null,
            pixelArrayWidth = 4032,
            pixelArrayHeight = 3024,
            focalLengthMm = 4.25f,
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            targetWidth = 2016,
            targetHeight = 1512,
        )
        requireNotNull(result)
        assertTrue(abs(result.fx - (4.25f * 4032f / 6.4f) * 0.5f) < 1e-2f)
        assertEquals(1008f, result.cx, 1e-2f)
    }

    @Test
    fun `returns null when neither calibration nor a focal length is available`() {
        assertNull(
            CameraIntrinsicsEstimator.computeIntrinsics(
                calibration = null, pixelArrayWidth = 4032, pixelArrayHeight = 3024,
                focalLengthMm = null, sensorWidthMm = 6.4f, sensorHeightMm = 4.8f,
                targetWidth = 1920, targetHeight = 1080,
            ),
        )
    }

    @Test
    fun `returns null when the pixel array size is unavailable`() {
        assertNull(
            CameraIntrinsicsEstimator.computeIntrinsics(
                calibration = null, pixelArrayWidth = 0, pixelArrayHeight = 0,
                focalLengthMm = 4.25f, sensorWidthMm = 6.4f, sensorHeightMm = 4.8f,
                targetWidth = 1920, targetHeight = 1080,
            ),
        )
    }

    @Test
    fun `returns null when sensor physical size is unavailable and there is no calibration to fall back from`() {
        assertNull(
            CameraIntrinsicsEstimator.computeIntrinsics(
                calibration = null, pixelArrayWidth = 4032, pixelArrayHeight = 3024,
                focalLengthMm = 4.25f, sensorWidthMm = null, sensorHeightMm = null,
                targetWidth = 1920, targetHeight = 1080,
            ),
        )
    }

    @Test
    fun `returns null for a non-positive target size`() {
        val calibration = floatArrayOf(3200f, 3200f, 2016f, 1512f, 0f)
        assertNull(
            CameraIntrinsicsEstimator.computeIntrinsics(
                calibration = calibration, pixelArrayWidth = 4032, pixelArrayHeight = 3024,
                focalLengthMm = null, sensorWidthMm = null, sensorHeightMm = null,
                targetWidth = 0, targetHeight = 1080,
            ),
        )
        assertNull(
            CameraIntrinsicsEstimator.computeIntrinsics(
                calibration = calibration, pixelArrayWidth = 4032, pixelArrayHeight = 3024,
                focalLengthMm = null, sensorWidthMm = null, sensorHeightMm = null,
                targetWidth = 1920, targetHeight = -1,
            ),
        )
    }

    @Test
    fun `returns null when the computed focal length is non-positive`() {
        assertNull(
            CameraIntrinsicsEstimator.computeIntrinsics(
                calibration = null, pixelArrayWidth = 4032, pixelArrayHeight = 3024,
                focalLengthMm = 0f, sensorWidthMm = 6.4f, sensorHeightMm = 4.8f,
                targetWidth = 1920, targetHeight = 1080,
            ),
        )
    }

    @Test
    fun `a 16x9 stream from a 4x3 array crops vertically and scales uniformly`() {
        // 4000x3000 array, square pixels (fx == fy), centred principal point, 1920x1080 stream.
        val result = CameraIntrinsicsEstimator.computeIntrinsics(
            calibration = floatArrayOf(3000f, 3000f, 2000f, 1500f, 0f),
            pixelArrayWidth = 4000, pixelArrayHeight = 3000,
            focalLengthMm = null, sensorWidthMm = null, sensorHeightMm = null,
            targetWidth = 1920, targetHeight = 1080,
        )!!
        // Centred crop 4000x2250 (offset y = 375), uniform scale 1920/4000 = 0.48.
        assertEquals(1440f, result.fx, 1e-3f)
        assertEquals(1440f, result.fy, 1e-3f) // the old per-axis scale gave 1080 here
        assertEquals(960f, result.cx, 1e-3f)
        assertEquals(540f, result.cy, 1e-3f)
    }

    @Test
    fun `a decentred principal point shifts by the crop offset`() {
        val result = CameraIntrinsicsEstimator.computeIntrinsics(
            calibration = floatArrayOf(3000f, 3000f, 2010f, 1490f, 0f),
            pixelArrayWidth = 4000, pixelArrayHeight = 3000,
            focalLengthMm = null, sensorWidthMm = null, sensorHeightMm = null,
            targetWidth = 1920, targetHeight = 1080,
        )!!
        assertEquals(2010f * 0.48f, result.cx, 1e-3f)
        assertEquals((1490f - 375f) * 0.48f, result.cy, 1e-3f)
    }

    @Test
    fun `a narrower-than-array stream crops horizontally`() {
        // 1:1 stream from 4:3: crop 3000x3000 at x offset 500, scale 1000/3000.
        val result = CameraIntrinsicsEstimator.computeIntrinsics(
            calibration = null, pixelArrayWidth = 4000, pixelArrayHeight = 3000,
            focalLengthMm = 4f, sensorWidthMm = 6.4f, sensorHeightMm = 4.8f,
            targetWidth = 1000, targetHeight = 1000,
        )!!
        assertEquals(4f * 4000f / 6.4f / 3f, result.fx, 1e-2f)
        assertEquals(result.fx, result.fy, 1e-2f)
        assertEquals(500f, result.cx, 1e-3f)
        assertEquals(500f, result.cy, 1e-3f)
    }

    @Test
    fun `raw calibration computes per frame size without re-reading the camera`() {
        val raw = CameraIntrinsicsEstimator.RawCalibration(
            calibration = floatArrayOf(3000f, 3000f, 2000f, 1500f, 0f),
            pixelArrayWidth = 4032, pixelArrayHeight = 3024,
            focalLengthMm = null, sensorWidthMm = null, sensorHeightMm = null,
            calibrationArrayWidth = 4000, calibrationArrayHeight = 3000,
        )
        assertEquals(CameraIntrinsics(750f, 750f, 500f, 375f, 1000, 750), raw.intrinsicsFor(1000, 750))
        assertNull(raw.intrinsicsFor(0, 750))
    }
}

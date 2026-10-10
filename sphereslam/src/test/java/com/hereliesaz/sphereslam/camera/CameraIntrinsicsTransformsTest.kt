package com.hereliesaz.sphereslam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraIntrinsicsTransformsTest {

    @Test
    fun cropMovesPrincipalPointButKeepsFocalLength() {
        val raw = CameraIntrinsics(
            fx = 1000f,
            fy = 900f,
            cx = 960f,
            cy = 540f,
            width = 1920,
            height = 1080,
        )

        val cropped = CameraIntrinsicsTransforms.crop(
            intrinsics = raw,
            cropLeft = 240,
            cropTop = 90,
            cropWidth = 1440,
            cropHeight = 900,
        )

        assertEquals(1000f, cropped.fx, 0.0001f)
        assertEquals(900f, cropped.fy, 0.0001f)
        assertEquals(720f, cropped.cx, 0.0001f)
        assertEquals(450f, cropped.cy, 0.0001f)
        assertEquals(1440, cropped.width)
        assertEquals(900, cropped.height)
    }

    @Test
    fun rotateSwapsSizeAndMatchesCaptureRotation() {
        val raw = CameraIntrinsics(1000f, 900f, 950f, 530f, 1920, 1080)
        val r = CameraIntrinsicsTransforms.rotate(raw, 90)
        assertEquals(1080, r.width)
        assertEquals(1920, r.height)
        val k = CaptureRotation.rotateIntrinsics(1000f, 900f, 950f, 530f, 1920f, 1080f, 90)
        assertEquals(k[0], r.fx, 0f); assertEquals(k[1], r.fy, 0f)
        assertEquals(k[2], r.cx, 0f); assertEquals(k[3], r.cy, 0f)
    }

    @Test
    fun rescaleScalesEachAxis() {
        val r = CameraIntrinsicsTransforms.rescale(CameraIntrinsics(1000f, 1000f, 960f, 540f, 1920, 1080), 960, 540)
        assertEquals(CameraIntrinsics(500f, 500f, 480f, 270f, 960, 540), r)
    }

    @Test
    fun toCalibrationAndValidity() {
        val k = CameraIntrinsics(1000f, 1000f, 960f, 540f, 1920, 1080)
        assertEquals(true, k.isValid)
        assertEquals(960f, k.toCalibration().cx, 0f)
        assertEquals(false, CameraIntrinsics.UNKNOWN.isValid)
    }

    @Test
    fun screenIntrinsicsSwapAxesForAPortraitFitCenterView() {
        // Ported from GraffitiXR OverlayGyroCompensationMathTest: 4:3 frame in a 1080x2340 view,
        // scale = min(1080/3000, 2340/4000) = 0.36.
        val s = ScreenIntrinsics.fitCenter(3200f, 3100f, 4000, 3000, 1080, 2340)
        assertEquals(3100f * 0.36f, s[0], 0.01f)
        assertEquals(3200f * 0.36f, s[1], 0.01f)
        assertEquals(540f, s[2], 0f); assertEquals(1170f, s[3], 0f)
        val l = ScreenIntrinsics.fitCenter(3200f, 3100f, 4000, 3000, 2340, 1080)
        assertEquals(3200f * 0.36f, l[0], 0.01f)
        assertEquals(3100f * 0.36f, l[1], 0.01f)
        val f = ScreenIntrinsics.fallback(1080, 2340)
        assertEquals(true, f[0] > 0f && f[1] > 0f)
        assertEquals(540f, f[2], 0f); assertEquals(1170f, f[3], 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonQuarterTurnRotationIsRejected() {
        CaptureRotation.rotateIntrinsics(1f, 1f, 0f, 0f, 10f, 10f, 45)
    }
}

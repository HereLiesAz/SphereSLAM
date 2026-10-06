package com.hereliesaz.sphereslam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure matrix→(heading, elevation) seam. The `SensorManager` plumbing around it needs the
 * Android runtime, but this is where the geometry lives, so it is unit-tested directly.
 *
 * The seam reads only the third column of the device→ENU rotation matrix (indices 2,5,8) — the world
 * direction of the camera's −Z optical axis — so the tests set just those entries.
 */
class CameraAttitudeProviderTest {

    private fun matrixWithCameraAxis(east: Float, north: Float, up: Float): FloatArray {
        // Camera axis world vector = (-r[2], -r[5], -r[8]); invert to populate the matrix.
        val r = FloatArray(9)
        r[2] = -east
        r[5] = -north
        r[8] = -up
        return r
    }

    @Test
    fun `looking north at the horizon is heading 0 elevation 0`() {
        val (h, e) = CameraAttitudeProvider.cameraAxisHeadingElevation(
            matrixWithCameraAxis(east = 0f, north = 1f, up = 0f),
        )!!
        assertEquals(0f, h, 1e-3f)
        assertEquals(0f, e, 1e-3f)
    }

    @Test
    fun `looking east is heading 90`() {
        val (h, e) = CameraAttitudeProvider.cameraAxisHeadingElevation(
            matrixWithCameraAxis(east = 1f, north = 0f, up = 0f),
        )!!
        assertEquals(90f, h, 1e-3f)
        assertEquals(0f, e, 1e-3f)
    }

    @Test
    fun `tilting up 30 degrees toward north reads elevation +30`() {
        val rad = Math.toRadians(30.0)
        val (h, e) = CameraAttitudeProvider.cameraAxisHeadingElevation(
            matrixWithCameraAxis(
                east = 0f,
                north = kotlin.math.cos(rad).toFloat(),
                up = kotlin.math.sin(rad).toFloat(),
            ),
        )!!
        assertEquals(0f, h, 1e-3f)
        assertEquals(30f, e, 1e-3f)
    }

    @Test
    fun `near-vertical axis has no meaningful heading`() {
        assertNull(
            CameraAttitudeProvider.cameraAxisHeadingElevation(
                matrixWithCameraAxis(east = 0f, north = 0f, up = 1f),
            ),
        )
    }

    @Test
    fun `a malformed matrix returns null`() {
        assertNull(CameraAttitudeProvider.cameraAxisHeadingElevation(FloatArray(4)))
    }

    @Test
    fun `heading feeds straight into coverage`() {
        val attitude = CameraAttitudeProvider.cameraAxisHeadingElevation(
            matrixWithCameraAxis(east = 0f, north = 1f, up = 0f),
        )
        assertNotNull(attitude)
        val c = SphereCoverage(elevationBandCount = 3)
        assertEquals(true, c.observe(attitude!!.first, attitude.second))
    }
}

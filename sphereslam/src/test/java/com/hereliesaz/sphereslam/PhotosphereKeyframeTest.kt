package com.hereliesaz.sphereslam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PhotosphereKeyframeTest {

    private val fx = 500f
    private val fy = 500f
    private val cx = 320f
    private val cy = 240f

    /** Device→ENU row-major matrix for a natural-orientation rear camera at heading/elevation/roll. */
    private fun deviceToWorld(headingDeg: Double, elevationDeg: Double, rollDeg: Double): FloatArray {
        val h = Math.toRadians(headingDeg); val e = Math.toRadians(elevationDeg); val r = Math.toRadians(rollDeg)
        val f = doubleArrayOf(cos(e) * sin(h), cos(e) * cos(h), sin(e))
        val n = sqrt(f[1] * f[1] + f[0] * f[0])
        val r0 = doubleArrayOf(f[1] / n, -f[0] / n, 0.0)
        val u0 = doubleArrayOf(r0[1] * f[2] - r0[2] * f[1], r0[2] * f[0] - r0[0] * f[2], r0[0] * f[1] - r0[1] * f[0])
        val right = DoubleArray(3) { cos(r) * r0[it] + sin(r) * u0[it] }
        val up = DoubleArray(3) { -sin(r) * r0[it] + cos(r) * u0[it] }
        // Columns: device x = image right, device y = image up, device z = -forward.
        return FloatArray(9) { i ->
            val row = i / 3; val col = i % 3
            when (col) { 0 -> right[row]; 1 -> up[row]; else -> -f[row] }.toFloat()
        }
    }

    /** Independent reference: rotate the device-frame (GL) pixel ray by the device→world matrix. */
    private fun reference(u: Float, v: Float, r: FloatArray): SphereCoverage.Direction {
        val x = (u - cx) / fx; val y = -(v - cy) / fy; val z = -1f
        val d = floatArrayOf(r[0] * x + r[1] * y + r[2] * z, r[3] * x + r[4] * y + r[5] * z, r[6] * x + r[7] * y + r[8] * z)
        val n = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        var az = Math.toDegrees(atan2(d[0].toDouble(), d[1].toDouble())).toFloat()
        az = ((az % 360f) + 360f) % 360f
        return SphereCoverage.Direction(az, Math.toDegrees(asin((d[2] / n).toDouble())).toFloat())
    }

    private fun assertDirection(expected: SphereCoverage.Direction, actual: SphereCoverage.Direction?) {
        assertNotNull(actual)
        var da = (expected.azimuthDeg - actual!!.azimuthDeg) % 360f
        if (da > 180f) da -= 360f
        if (da < -180f) da += 360f
        assertEquals("azimuth", 0f, da, 1e-2f)
        assertEquals("elevation", expected.elevationDeg, actual.elevationDeg, 1e-2f)
    }

    @Test
    fun `the principal point maps to the camera axis`() {
        assertDirection(SphereCoverage.Direction(37f, 12f), PhotosphereMap.directionOfPixel(cx, cy, fx, fy, cx, cy, 37f, 12f, 25f))
    }

    @Test
    fun `pixel directions match the full rotation for every roll, including off-axis at high elevation`() {
        for (roll in doubleArrayOf(0.0, 30.0, 90.0, -45.0, 180.0)) for (elev in doubleArrayOf(0.0, 55.0, -30.0)) {
            val r = deviceToWorld(200.0, elev, roll)
            for ((u, v) in listOf(600f to 240f, 40f to 30f, 320f to 470f)) {
                assertDirection(
                    reference(u, v, r),
                    PhotosphereMap.directionOfPixel(u, v, fx, fy, cx, cy, 200f, elev.toFloat(), roll.toFloat()),
                )
            }
        }
    }

    @Test
    fun `with a 90 degree roll a horizontal tap offset moves elevation, not heading`() {
        val d = PhotosphereMap.directionOfPixel(cx + 100f, cy, fx, fy, cx, cy, 0f, 0f, 90f)!!
        assertEquals(0f, d.azimuthDeg, 1e-3f)
        assertEquals(Math.toDegrees(kotlin.math.atan(100.0 / fx)).toFloat(), d.elevationDeg, 1e-3f)
    }

    @Test
    fun `roll reads back from a device-to-world matrix`() {
        for (roll in doubleArrayOf(0.0, 20.0, -75.0, 120.0)) {
            val r = deviceToWorld(80.0, 15.0, roll)
            assertEquals(roll.toFloat(), CameraAttitudeProvider.cameraRollDegrees(r)!!, 1e-3f)
            val (h, e) = CameraAttitudeProvider.cameraAxisHeadingElevation(r)!!
            assertEquals(80f, h, 1e-3f); assertEquals(15f, e, 1e-3f)
        }
        assertNull(CameraAttitudeProvider.cameraRollDegrees(FloatArray(3)))
    }

    private fun keyframe(heading: Float, elevation: Float, fxv: Float = fx) = PhotosphereKeyframe(
        timestampNs = 1L,
        luma = ByteArray(4 * 3),
        width = 4,
        height = 3,
        intrinsics = floatArrayOf(fxv, fxv, 2f, 1.5f),
        headingDeg = heading,
        elevationDeg = elevation,
    )

    @Test
    fun `store anchors the map, records per tile, and reports first captures`() {
        val map = PhotosphereMap(sectorCount = 4, viewableHalfAngleDeg = 180f, elevationBandCount = 3, viewableElevationHalfAngleDeg = 60f)
        val store = PhotosphereKeyframeStore(map)
        val first = store.record(keyframe(10f, 0f), nowMs = 100L)!!
        assertTrue(map.hasWallHeading())
        assertTrue(first.firstCaptureForTile)
        val again = store.record(keyframe(12f, 0f), nowMs = 200L)!!
        assertEquals(first.tile, again.tile)
        assertFalse(again.firstCaptureForTile)
        assertEquals(12f, store.keyframe(first.tile)!!.headingDeg, 0f)
        assertEquals(1, store.keyframes().size)
        assertNull(store.record(keyframe(10f, 89f), nowMs = 300L)) // outside the elevation band
        store.clear()
        assertNull(store.latest)
    }

    @Test
    fun `view prefers the tile's own keyframe and otherwise pairs session intrinsics with the current attitude`() {
        val map = PhotosphereMap(sectorCount = 4, viewableHalfAngleDeg = 180f, elevationBandCount = 3, viewableElevationHalfAngleDeg = 60f)
        val store = PhotosphereKeyframeStore(map)
        assertNull(store.view(0f, 0f))
        store.record(keyframe(10f, 0f, fxv = 3f), nowMs = 1L)
        val own = store.view(15f, 2f)!!
        assertEquals(10f, own.headingDeg, 0f) // same tile -> its keyframe's attitude
        val fresh = store.view(190f, 0f, rollDeg = 4f)!!
        assertEquals(190f, fresh.headingDeg, 0f) // fresh tile -> current attitude
        assertEquals(4f, fresh.rollDeg, 0f)
        assertEquals(3f, fresh.fx, 0f) // ... with session intrinsics
    }

    @Test
    fun `placement at the image centre facing the wall is straight ahead`() {
        val map = PhotosphereMap()
        val store = PhotosphereKeyframeStore(map)
        store.record(keyframe(90f, 0f), nowMs = 1L)
        val p = PhotosphereFingerprintFrame.placement(map, store.view(90f, 0f)!!)!!
        val m = p.mapFromFingerprint
        assertEquals(0f, m[12], 1e-5f)
        assertEquals(-1f, m[14], 1e-5f) // unit radius toward -Z
        assertFalse(p.physicallyMetric)
        assertEquals(90f, p.direction.azimuthDeg, 1e-3f)
    }
}

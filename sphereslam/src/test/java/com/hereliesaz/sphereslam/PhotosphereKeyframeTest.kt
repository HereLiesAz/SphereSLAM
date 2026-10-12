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

    /** A 480×640 portrait frame, fx = fy = 500: ~51° × 65°, the app's display-upright analysis frame. */
    private fun portraitKeyframe(heading: Float, elevation: Float, roll: Float = 0f) = PhotosphereKeyframe(
        timestampNs = 1L,
        luma = ByteArray(480 * 640),
        width = 480,
        height = 640,
        intrinsics = floatArrayOf(500f, 500f, 240f, 320f),
        headingDeg = heading,
        elevationDeg = elevation,
        rollDeg = roll,
    )

    @Test
    fun `store anchors the map, records per tile, and reports first captures`() {
        val map = PhotosphereMap() // the whole sphere: 24 × 6 tiles of 15° × 30°
        val store = PhotosphereKeyframeStore(map)
        val first = store.record(portraitKeyframe(10f, 0f), nowMs = 100L)!!
        assertTrue(map.hasWallHeading())
        assertEquals(TileId(12, 3), first.tile)
        assertTrue(first.firstCaptureForTile) // wholly in frame: scanned at once
        val again = store.record(portraitKeyframe(12f, 0f), nowMs = 200L)!!
        assertEquals(first.tile, again.tile)
        assertFalse(again.firstCaptureForTile)
        assertEquals(12f, store.keyframe(first.tile)!!.headingDeg, 0f)
        assertEquals(1, store.keyframes().size)
        store.clear()
        assertNull(store.latest)
    }

    @Test
    fun `a keyframe whose axis is outside an anchored lattice changes nothing`() {
        // A front-only lattice: 12 sectors over ±90°, 3 bands over ±60°, anchored at 0°.
        val map = PhotosphereMap(
            sectorCount = 12, viewableHalfAngleDeg = 90f,
            elevationBandCount = 3, viewableElevationHalfAngleDeg = 60f,
        )
        map.setWallHeading(0f)
        val before = map.currentTiles()
        // Aimed at 100° (outside the arc), a portrait frame still reaches the arc's edge.
        assertNull(PhotosphereKeyframeStore(map).record(portraitKeyframe(100f, 0f), nowMs = 1L))
        assertEquals(before, map.currentTiles())
        assertTrue(map.regionsInProgress().isEmpty())
    }

    @Test
    fun `an out-of-band keyframe never anchors the wall heading`() {
        // A deliberately front-only lattice (±60° elevation).
        val map = PhotosphereMap(sectorCount = 12, viewableHalfAngleDeg = 90f, elevationBandCount = 3, viewableElevationHalfAngleDeg = 60f)
        val store = PhotosphereKeyframeStore(map)
        assertNull(store.record(portraitKeyframe(200f, 85f), nowMs = 1L)) // looking at the ceiling
        assertFalse(map.hasWallHeading())
        assertNull(store.latest)
        // The first in-band keyframe anchors (and scans its tile).
        val first = store.record(portraitKeyframe(10f, 0f), nowMs = 2L)!!
        assertTrue(first.firstCaptureForTile)
        assertEquals(10f, map.snapshot().wallHeadingDeg!!, 1e-6f)
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

    @Test
    fun `a keyframe completes the tiles it holds and advances the ones it only partly sees`() {
        val map = PhotosphereMap()
        val store = PhotosphereKeyframeStore(map)
        val r = store.record(portraitKeyframe(0f, 0f), nowMs = 1L)!!
        // ±25.5° × ±32.5° around (0°, 0°): the four 15° × 30° tiles touching the axis are whole.
        assertEquals(TileId(12, 3), r.tile)
        for (id in listOf(TileId(11, 3), TileId(11, 2), TileId(12, 2))) assertTrue("$id", id in r.coveredTiles)
        for (id in r.coveredTiles + r.tile) assertFalse("$id current", map.needsUpdate(id))
        assertEquals(1, store.keyframes().size) // only the axis tile keeps the keyframe
        // Sector 13 (15°–30°) is cut at 25.5°: partly scanned, still glowing, shown in progress.
        val partial = TileId(13, 3)
        assertTrue(partial in r.progressedTiles)
        assertTrue(map.needsUpdate(partial))
        assertTrue(map.scanProgress(partial) in 0.5f..0.89f)
        assertTrue(map.regionsInProgress().any { it.progress == map.scanProgress(partial) })
        // Turning 10° further completes it; it leaves the in-progress list.
        val next = store.record(portraitKeyframe(10f, 0f), nowMs = 2L)!!
        assertTrue(partial in next.firstCoveredTiles)
        assertFalse(map.needsUpdate(partial))
        assertEquals(1f, map.scanProgress(partial), 0f)
        assertTrue(map.regionsInProgress().none { it.progress >= 1f })
    }

    @Test
    fun `scan progress accumulates across views and resets when a tile goes stale`() {
        val map = PhotosphereMap()
        val lens = CoverageGlowProjection.Lens.fromIntrinsics(500f, 500f, 240f, 320f, 480, 640)
        map.setWallHeading(0f)
        val tile = TileId(14, 3) // 30°–45°, 0°–30°
        // Two views each holding only part of it: neither alone completes it, together they do.
        map.recordView(10f, 15f, 0f, lens) // reaches 35.5°
        val half = map.scanProgress(tile)
        assertTrue(half > 0f && half < 0.9f)
        map.recordView(60f, 15f, 0f, lens) // from 34.5°
        assertFalse(map.needsUpdate(tile))
        map.markNeedsUpdate(tile)
        assertEquals(0f, map.scanProgress(tile), 0f)
    }

    @Test
    fun `a level pan with keyframes wider apart than a sector leaves no gap in its band`() {
        val map = PhotosphereMap()
        val store = PhotosphereKeyframeStore(map)
        store.record(portraitKeyframe(0f, 0f), nowMs = 0L) // anchors the lattice at 0°
        // 20° per keyframe (fast pan, 250 ms keyframes at 80°/s) from -40° to +40°.
        for ((i, h) in (-40..40 step 20).withIndex()) store.record(portraitKeyframe(h.toFloat(), 0f), nowMs = i + 1L)
        val anchor = map.snapshot().wallHeadingDeg!!
        for (s in 0 until 24) for (band in 2..3) {
            val c = SphereGrid.signedDelta(map.tile(TileId(s, band))!!.center.azimuthDeg, anchor)
            if (c in -50f..50f) assertFalse("sector $s band $band (centre $c°) current", map.needsUpdate(TileId(s, band)))
        }
    }

    @Test
    fun `the ceiling and the floor can be scanned`() {
        val map = PhotosphereMap()
        val store = PhotosphereKeyframeStore(map)
        store.record(portraitKeyframe(0f, 0f), nowMs = 0L) // anchor
        // Look up and turn around, then down and turn around.
        for (h in 0 until 360 step 30) store.record(portraitKeyframe(h.toFloat(), 70f), nowMs = 1L)
        for (h in 0 until 360 step 30) store.record(portraitKeyframe(h.toFloat(), -70f), nowMs = 2L)
        for (s in 0 until 24) {
            assertFalse("zenith tile $s", map.needsUpdate(TileId(s, 5)))
            assertFalse("nadir tile $s", map.needsUpdate(TileId(s, 0)))
        }
    }

    @Test
    fun `roll is honoured`() {
        val map = PhotosphereMap()
        map.setWallHeading(0f)
        // A landscape 640×480 frame (~65° × 51°) aimed at sector 12's centre (7.5°, 15°) covers
        // sector 14 (30°–45°, 67 % in frame) upright, but only ~21 % of it when rolled 90°.
        val lens = CoverageGlowProjection.Lens.fromIntrinsics(500f, 500f, 320f, 240f, 640, 480)
        assertTrue(TileId(14, 3) in map.tilesCovered(7.5f, 15f, 0f, lens))
        assertFalse(TileId(14, 3) in map.tilesCovered(7.5f, 15f, 90f, lens))
        // The scan path (recordView) honours it too.
        val upright = PhotosphereMap().apply { setWallHeading(0f) }
        upright.recordView(7.5f, 15f, 0f, lens)
        val rolled = PhotosphereMap().apply { setWallHeading(0f) }
        rolled.recordView(7.5f, 15f, 90f, lens)
        val u = upright.scanProgress(TileId(14, 3))
        val r = rolled.scanProgress(TileId(14, 3))
        assertTrue("upright $u rolled $r", u > 0.6f && r < 0.35f)
    }

    @Test
    fun `scan progress is area-weighted`() {
        val map = PhotosphereMap()
        map.setWallHeading(0f)
        // Aimed 20° up at sector 12 (0°–15°), the landscape frame's top edge sits near 45.6°: it holds
        // the lower ~half of band 4 (30°–60°) by elevation, but more of its area — a cos(elevation)
        // sphere has more of it low down: (sin 45.6° − sin 30°) / (sin 60° − sin 30°) ≈ 0.59, not 0.52.
        val lens = CoverageGlowProjection.Lens.fromIntrinsics(500f, 500f, 320f, 240f, 640, 480)
        map.recordView(7.5f, 20f, 0f, lens)
        assertEquals(0.59f, map.scanProgress(TileId(12, 4)), 0.025f)
    }
}

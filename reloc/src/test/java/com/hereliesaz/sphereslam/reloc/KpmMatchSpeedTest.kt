package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.math.RigidMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

class KpmMatchSpeedTest {

    private val k = CameraIntrinsics(500f, 510f, 319.5f, 241f, 640, 480)

    // ---- scale ----------------------------------------------------------------------------------

    @Test
    fun `effective factor keeps the short side above the floor`() {
        assertEquals(2, KpmMatchScale.effectiveFactor(640, 480, 2, 240))
        assertEquals(1, KpmMatchScale.effectiveFactor(640, 480, 2, 241))
        assertEquals(2, KpmMatchScale.effectiveFactor(640, 480, 4, 200))
        assertEquals(4, KpmMatchScale.effectiveFactor(1920, 1080, 4, 240))
        assertEquals(1, KpmMatchScale.effectiveFactor(320, 240, 2, 240))
        assertEquals(1, KpmMatchScale.effectiveFactor(640, 480, 1, 240))
    }

    @Test
    fun `scaled intrinsics follow the box downscale pixel mapping`() {
        val s = KpmMatchScale.scaleIntrinsics(k, 2)
        assertEquals(320, s.width)
        assertEquals(240, s.height)
        assertEquals(250f, s.fx, 0f)
        assertEquals(255f, s.fy, 0f)
        assertEquals((319.5f + 0.5f) / 2f - 0.5f, s.cx, 1e-6f)
        assertEquals((241f + 0.5f) / 2f - 0.5f, s.cy, 1e-6f)
        assertEquals(k, KpmMatchScale.scaleIntrinsics(k, 1))
        // Odd sizes floor (trailing partial blocks are dropped by the downsampler too).
        assertEquals(213, KpmMatchScale.scaleIntrinsics(k.copy(width = 641), 3).width)
    }

    @Test
    fun `a planar pose solved on the downscaled image with scaled intrinsics equals the full-resolution pose`() {
        // Project wall points (z = 0) with full K, map each pixel into the f-downscaled image
        // (x_s = (x + ½)/f − ½), then solve the planar pose from each image with its own intrinsics
        // (homography DLT + decomposition). Both solves must recover the same rigid transform.
        val angle = 0.3
        val r = doubleArrayOf(
            cos(angle), 0.0, sin(angle),
            0.0, 1.0, 0.0,
            -sin(angle), 0.0, cos(angle),
        )
        val t = doubleArrayOf(12.0, -30.0, 900.0)
        val points = listOf(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(150.0, -80.0), doubleArrayOf(-220.0, 140.0),
            doubleArrayOf(90.0, 160.0), doubleArrayOf(-120.0, -150.0),
        )
        val full = points.map { p ->
            val x = r[0] * p[0] + r[1] * p[1] + t[0]
            val y = r[3] * p[0] + r[4] * p[1] + t[1]
            val z = r[6] * p[0] + r[7] * p[1] + t[2]
            doubleArrayOf(k.fx * x / z + k.cx, k.fy * y / z + k.cy)
        }
        val reference = planarPose(points, full, k)
        assertArrayEquals(r + t, reference, 1e-4)
        for (f in listOf(2, 3, 4)) {
            val ks = KpmMatchScale.scaleIntrinsics(k, f)
            val small = full.map { doubleArrayOf((it[0] + 0.5) / f - 0.5, (it[1] + 0.5) / f - 0.5) }
            assertArrayEquals("factor $f", reference, planarPose(points, small, ks), 1e-4)
        }
    }

    /** `[r00..r22, tx, ty, tz]` of the camera-from-plane pose from ≥ 4 plane↔pixel pairs (exact data). */
    private fun planarPose(plane: List<DoubleArray>, pixels: List<DoubleArray>, ki: CameraIntrinsics): DoubleArray {
        // Normalized image coordinates remove K, so H = [r1 r2 t] up to scale.
        val norm = pixels.map { doubleArrayOf((it[0] - ki.cx) / ki.fx, (it[1] - ki.cy) / ki.fy) }
        val a = Array(2 * plane.size) { DoubleArray(9) }
        for (i in plane.indices) {
            val (x, y) = plane[i][0] to plane[i][1]
            val (u, v) = norm[i][0] to norm[i][1]
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, -u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, -v)
        }
        // Least squares with h22 = 1: normal equations of the 8 unknowns.
        val ata = Array(8) { DoubleArray(9) }
        for (row in a) for (i in 0 until 8) {
            for (j in 0 until 8) ata[i][j] += row[i] * row[j]
            ata[i][8] += -row[i] * row[8]
        }
        for (c in 0 until 8) {
            val p = (c until 8).maxByOrNull { kotlin.math.abs(ata[it][c]) }!!
            val tmp = ata[c]; ata[c] = ata[p]; ata[p] = tmp
            for (rr in 0 until 8) if (rr != c) {
                val m = ata[rr][c] / ata[c][c]
                for (kk in c until 9) ata[rr][kk] -= m * ata[c][kk]
            }
        }
        val h = DoubleArray(9) { if (it == 8) 1.0 else ata[it][8] / ata[it][it] }
        val col = { c: Int -> doubleArrayOf(h[c], h[3 + c], h[6 + c]) }
        val n = kotlin.math.sqrt(col(0).sumOf { it * it })
        val sign = if (h[8] / n > 0) 1.0 else -1.0 // keep the plane in front of the camera
        val r1 = col(0).map { sign * it / n }
        val r2 = col(1).map { sign * it / n }
        val tt = col(2).map { sign * it / n }
        val r3 = listOf(r1[1] * r2[2] - r1[2] * r2[1], r1[2] * r2[0] - r1[0] * r2[2], r1[0] * r2[1] - r1[1] * r2[0])
        return doubleArrayOf(
            r1[0], r2[0], r3[0],
            r1[1], r2[1], r3[1],
            r1[2], r2[2], r3[2],
            tt[0], tt[1], tt[2],
        )
    }

    @Test
    fun `correspondences and errors convert to full-frame pixels`() {
        val corr = floatArrayOf(0f, 1f, 5f, 6f, 10f, 20f, 7f, 8f)
        val out = KpmMatchScale.correspondencesToFramePixels(corr, 2)
        assertArrayEquals(floatArrayOf(0.5f, 2.5f, 5f, 6f, 20.5f, 40.5f, 7f, 8f), out, 0f)
        assertEquals(0f, corr[1] - 1f, 0f) // input untouched
        assertEquals(3f * 9f, KpmMatchScale.errorToFramePixelsSq(3f, 3), 0f)
        assertEquals(3f, KpmMatchScale.errorToFramePixelsSq(3f, 1), 0f)
    }

    // ---- flow pose rules ------------------------------------------------------------------------

    @Test
    fun `seed rows outside the frame or non-finite are dropped`() {
        val corr = floatArrayOf(
            0f, 0f, 1f, 1f,
            639f, 479f, 1f, 1f,
            -0.1f, 5f, 1f, 1f,
            5f, 479.5f, 1f, 1f,
            Float.NaN, 5f, 1f, 1f,
            320f, 240f, 1f, 1f,
        )
        assertEquals(listOf(0, 1, 5), FlowPoseMath.usableSeedRows(corr, 640, 480))
    }

    @Test
    fun `forward-backward check keeps points that track both ways, in frame, and return close`() {
        val prev = doubleArrayOf(10.0, 10.0, 20.0, 20.0, 30.0, 30.0, 40.0, 40.0, 50.0, 50.0)
        val next = doubleArrayOf(12.0, 10.0, 22.0, 20.0, 700.0, 30.0, 42.0, 40.0, 52.0, 50.0)
        val back = doubleArrayOf(10.5, 10.0, 20.0, 20.0, 30.0, 30.0, 41.5, 40.0, 50.0, 50.0)
        val ok = byteArrayOf(1, 1, 1, 1, 0)
        val backOk = byteArrayOf(1, 1, 1, 1, 1)
        // 0: returns 0.5 px → kept; 1: exact → kept; 2: leaves the frame; 3: returns 1.5 px; 4: lost forward.
        assertEquals(listOf(0, 1), FlowPoseMath.forwardBackwardSurvivors(prev, next, back, ok, backOk, 640, 480, 1.0))
    }

    @Test
    fun `point floors, outlier pass and RMS limit`() {
        val cfg = KpmMatchSpeedConfig(minFlowInliers = 12, minFlowInlierFraction = 0.4f, maxFlowRmsErrorPx = 2f)
        assertTrue(FlowPoseMath.enoughPoints(12, 20, cfg))
        assertFalse(FlowPoseMath.enoughPoints(11, 20, cfg)) // absolute floor
        assertFalse(FlowPoseMath.enoughPoints(15, 40, cfg)) // fraction floor (16 of 40)
        assertTrue(FlowPoseMath.enoughPoints(16, 40, cfg))
        assertEquals(listOf(0, 2), FlowPoseMath.inlierIndices(doubleArrayOf(1.0, 9.5, 9.0), 3.0))
        assertEquals(3f, FlowPoseMath.acceptedMeanSquaredError(doubleArrayOf(2.0, 4.0), cfg)!!, 1e-6f)
        assertNull(FlowPoseMath.acceptedMeanSquaredError(doubleArrayOf(4.0, 5.0), cfg)) // RMS 2.12 > 2
        assertNull(FlowPoseMath.acceptedMeanSquaredError(doubleArrayOf(), cfg))
        assertNull(FlowPoseMath.acceptedMeanSquaredError(doubleArrayOf(Double.NaN), cfg))
    }

    @Test
    fun `toFramePixel inverts the box mapping`() {
        assertEquals(0.5f, KpmMatchScale.toFramePixel(0f, 2), 0f) // block (0,1) centre
        assertEquals(1f, KpmMatchScale.toFramePixel(0f, 3), 0f)
        assertEquals(7f, KpmMatchScale.toFramePixel(7f, 1), 0f)
    }

    @Test
    fun `downsample averages blocks with rounding`() {
        val w = 5
        val h = 4
        val src = ByteArray(w * h) { (it * 13 % 256).toByte() }
        val out = KpmMatchScale.downsample(src, w, h, 2, ByteBuffer.allocateDirect(4))
        assertEquals(4, out.remaining())
        for (y in 0 until 2) for (x in 0 until 2) {
            fun px(xx: Int, yy: Int) = src[yy * w + xx].toInt() and 0xFF
            val sum = px(2 * x, 2 * y) + px(2 * x + 1, 2 * y) + px(2 * x, 2 * y + 1) + px(2 * x + 1, 2 * y + 1)
            assertEquals((sum + 2) / 4, out.get(y * 2 + x).toInt() and 0xFF)
        }
        val big = ByteArray(36) { 200.toByte() }
        val three = KpmMatchScale.downsample(big, 6, 6, 3, ByteBuffer.allocateDirect(4))
        repeat(4) { assertEquals(200, three.get(it).toInt() and 0xFF) }
        val one = KpmMatchScale.downsample(src, w, h, 1, ByteBuffer.allocateDirect(w * h))
        assertEquals(w * h, one.remaining())
        assertEquals(src[7], one.get(7))
    }

    // ---- scheduling -----------------------------------------------------------------------------

    private val speed = KpmMatchSpeedConfig(fullSolveEveryFrames = 3, fullSolveIntervalMs = 500L, maxFlowOnlyMs = 1_000L)

    @Test
    fun `scheduler acquires until a seeded solve, then tracks between periodic refreshes`() {
        val s = KpmMatchScheduler(speed)
        assertEquals(KpmMatchScheduler.Decision.ACQUIRE, s.decide(0))
        s.onFullSolve(0, success = false, seeded = false)
        assertEquals(KpmMatchScheduler.Decision.ACQUIRE, s.decide(10))
        s.onFullSolve(10, success = true, seeded = false) // lock without correspondences
        assertEquals(KpmMatchScheduler.Decision.ACQUIRE, s.decide(20))
        s.onFullSolve(20, success = true, seeded = true)
        assertTrue(s.isArmed)
        repeat(3) {
            assertEquals(KpmMatchScheduler.Decision.TRACK, s.decide(30L + it))
            s.onTracked(true)
        }
        // Every N frames…
        assertEquals(KpmMatchScheduler.Decision.REFRESH, s.decide(40))
        s.onFullSolve(40, success = true, seeded = true)
        assertEquals(KpmMatchScheduler.Decision.TRACK, s.decide(50))
        // …or every T ms, whichever first.
        assertEquals(KpmMatchScheduler.Decision.REFRESH, s.decide(540))
    }

    @Test
    fun `flow loss or a gate rejection forces a full solve`() {
        val s = KpmMatchScheduler(speed)
        s.onFullSolve(0, success = true, seeded = true)
        assertEquals(KpmMatchScheduler.Decision.TRACK, s.decide(1))
        s.onTracked(false)
        assertEquals(KpmMatchScheduler.Decision.ACQUIRE, s.decide(2))
        s.onFullSolve(3, success = true, seeded = true)
        s.disarm()
        assertEquals(KpmMatchScheduler.Decision.ACQUIRE, s.decide(4))
    }

    @Test
    fun `a missed refresh may be covered by flow only for a bounded time`() {
        val s = KpmMatchScheduler(speed)
        s.onFullSolve(0, success = true, seeded = true)
        s.onFullSolve(600, success = false, seeded = false)
        assertTrue(s.flowMayCoverMiss(600))
        assertTrue(s.isArmed)
        s.onFullSolve(1_200, success = false, seeded = false)
        assertFalse(s.flowMayCoverMiss(1_200))
        assertFalse(s.isArmed)
        assertEquals(KpmMatchScheduler.Decision.ACQUIRE, s.decide(1_201))
    }

    @Test
    fun `strong flow covers missed refreshes for longer`() {
        val s = KpmMatchScheduler(speed.copy(maxFlowOnlyMs = 1_000L, strongFlowOnlyMs = 5_000L, strongFlowInliers = 30))
        s.onFullSolve(0, success = true, seeded = true)
        s.onTracked(success = true, inliers = 80) // a board filling the frame keeps many points
        s.onFullSolve(1_200, success = false, seeded = false)
        assertTrue(s.flowMayCoverMiss(1_200))
        assertTrue(s.flowMayCoverMiss(4_900))
        assertFalse(s.flowMayCoverMiss(5_100))
    }

    @Test
    fun `weak flow keeps the short limit`() {
        val s = KpmMatchScheduler(speed.copy(maxFlowOnlyMs = 1_000L, strongFlowOnlyMs = 5_000L, strongFlowInliers = 30))
        s.onFullSolve(0, success = true, seeded = true)
        s.onTracked(success = true, inliers = 14)
        assertFalse(s.flowMayCoverMiss(1_200))
    }

    @Test
    fun `flow disabled means a full solve every frame`() {
        val s = KpmMatchScheduler(KpmMatchSpeedConfig.FULL_KPM_EVERY_FRAME)
        s.onFullSolve(0, success = true, seeded = true)
        assertFalse(s.isArmed)
        assertEquals(KpmMatchScheduler.Decision.ACQUIRE, s.decide(1))
        assertEquals(1, KpmMatchSpeedConfig.FULL_KPM_EVERY_FRAME.matchDownscale)
    }

    // ---- page priority --------------------------------------------------------------------------

    /** GL view of a camera at (x, y, d) looking down −z at the wall, optionally yawed by [yawDeg]. */
    private fun viewAt(x: Float, y: Float, d: Float, yawDeg: Double = 0.0): FloatArray {
        val a = Math.toRadians(yawDeg)
        // camera_from_world = R · [I | −C], R = rotation about +y by −yaw (so the camera turns by +yaw).
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        val r = floatArrayOf(c, 0f, -s, 0f, 1f, 0f, s, 0f, c) // row-major
        val out = RigidMath.identity()
        for (row in 0..2) for (col in 0..2) out[col * 4 + row] = r[row * 3 + col]
        val cx = floatArrayOf(x, y, d)
        for (row in 0..2) out[12 + row] = -(r[row * 3] * cx[0] + r[row * 3 + 1] * cx[1] + r[row * 3 + 2] * cx[2])
        return out
    }

    private val atlas = listOf(
        KpmPagePriority.PageWindow(0, 0f, 0f, 1f, 0.75f),
        KpmPagePriority.PageWindow(1, 0.8f, 0f, 1f, 0.75f),
        KpmPagePriority.PageWindow(2, -0.8f, 0f, 1f, 0.75f),
        KpmPagePriority.PageWindow(3, 0f, 0.7f, 1f, 0.75f),
    )

    @Test
    fun `optical axis hits the wall under the camera`() {
        assertArrayEquals(floatArrayOf(0.3f, -0.2f), KpmPagePriority.opticalAxisHit(viewAt(0.3f, -0.2f, 2f))!!, 1e-5f)
        // Yawed 45° → the axis lands one depth to the side: x = −d·tan(45°) for this rotation sense.
        val hit = KpmPagePriority.opticalAxisHit(viewAt(0f, 0f, 1f, 45.0))!!
        assertEquals(1f, kotlin.math.abs(hit[0]), 1e-4f)
        // Looking away from the wall: no hit.
        assertNull(KpmPagePriority.opticalAxisHit(viewAt(0f, 0f, 1f, 180.0)))
    }

    @Test
    fun `pages are ordered by distance from the predicted hit`() {
        assertEquals(listOf(1, 0, 3, 2), KpmPagePriority.order(atlas, viewAt(0.9f, 0.1f, 2f), null))
        assertEquals(listOf(2, 0, 3, 1), KpmPagePriority.order(atlas, viewAt(-0.7f, 0f, 2f), null))
    }

    @Test
    fun `the last locked page keeps priority while the hit is inside it`() {
        // Hit at x = 0.45 lies inside both page 0 and page 1; page 1 is nearer, but 0 was locked.
        assertEquals(1, KpmPagePriority.order(atlas, viewAt(0.45f, 0f, 2f), null).first())
        assertEquals(0, KpmPagePriority.order(atlas, viewAt(0.45f, 0f, 2f), lastLockedPage = 0).first())
        // Once the hit leaves the locked page, the nearest page wins.
        assertEquals(1, KpmPagePriority.order(atlas, viewAt(0.9f, 0f, 2f), lastLockedPage = 0).first())
    }

    @Test
    fun `without a prediction the last locked page goes first`() {
        assertEquals(listOf(2, 0, 1, 3), KpmPagePriority.order(atlas, null, lastLockedPage = 2))
        assertEquals(listOf(0, 1, 2, 3), KpmPagePriority.order(atlas, null, lastLockedPage = null))
        assertEquals(listOf(3, 0, 1, 2), KpmPagePriority.order(atlas, viewAt(0f, 0f, 1f, 180.0), lastLockedPage = 3))
    }

    @Test
    fun `focused candidates only for a recent lock on a multi-page atlas`() {
        val cfg = KpmMatchSpeedConfig(focusWindowMs = 1_000L)
        val near = viewAt(0.9f, 0f, 2f)
        assertEquals(listOf(1), KpmPagePriority.focusCandidates(atlas, near, 0, 100L, cfg))
        assertTrue(KpmPagePriority.focusCandidates(atlas, near, 0, 1_001L, cfg).isEmpty()) // stale lock
        assertTrue(KpmPagePriority.focusCandidates(atlas, near, null, null, cfg).isEmpty()) // never locked
        assertTrue(KpmPagePriority.focusCandidates(atlas.take(1), near, 0, 100L, cfg).isEmpty()) // one page
        assertTrue(KpmPagePriority.focusCandidates(atlas, near, 0, 100L, cfg.copy(focusedPageMatching = false)).isEmpty())
    }
}

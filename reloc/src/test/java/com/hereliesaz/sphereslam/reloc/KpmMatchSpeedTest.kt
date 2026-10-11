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
    fun `the same pose projects consistently at both scales, so the solved pose is unchanged`() {
        // Any camera-from-page pose: a 3-D point projects with full K to x, and with scaled K to
        // exactly the downscaled image of x. A calibrated PnP on the downscaled image therefore
        // recovers the identical rigid transform — no pose correction is needed.
        val angle = 0.3
        val r = floatArrayOf(
            cos(angle).toFloat(), 0f, sin(angle).toFloat(),
            0f, 1f, 0f,
            -sin(angle).toFloat(), 0f, cos(angle).toFloat(),
        )
        val t = floatArrayOf(12f, -30f, 900f)
        val points = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(150f, -80f, 0f), floatArrayOf(-220f, 140f, 0f))
        for (f in listOf(2, 3, 4)) {
            val ks = KpmMatchScale.scaleIntrinsics(k, f)
            for (p in points) {
                val x = r[0] * p[0] + r[1] * p[1] + r[2] * p[2] + t[0]
                val y = r[3] * p[0] + r[4] * p[1] + r[5] * p[2] + t[1]
                val z = r[6] * p[0] + r[7] * p[1] + r[8] * p[2] + t[2]
                val u = k.fx * x / z + k.cx
                val v = k.fy * y / z + k.cy
                val us = ks.fx * x / z + ks.cx
                val vs = ks.fy * y / z + ks.cy
                assertEquals(u, KpmMatchScale.toFramePixel(us, f), 1e-3f)
                assertEquals(v, KpmMatchScale.toFramePixel(vs, f), 1e-3f)
            }
        }
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

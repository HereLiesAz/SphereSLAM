package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.math.RigidMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PlanarTrackerTest {

    private val k = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)
    private val frame = ByteArray(640 * 480)
    private var now = 10_000L

    /** OpenCV camera-from-object (row-major) of an upright camera 2 units in front of the plane. */
    private val facing = floatArrayOf(
        1f, 0f, 0f, 0.1f,
        0f, -1f, 0f, 0.2f,
        0f, 0f, -1f, 2f,
        0f, 0f, 0f, 1f,
    )

    private class FakeSolver(var referenceFeatures: Int = 100) : PlanarSolver {
        var next: PlanarSolve? = null
        var cleared = 0
        var closed = false
        override fun setReference(luma: ByteArray, width: Int, height: Int, halfWidth: Float, halfHeight: Float) = referenceFeatures
        override fun clearReference() { cleared++ }
        override fun solve(luma: ByteArray, width: Int, height: Int, intrinsics: CameraIntrinsics) = next
        override fun close() { closed = true }
    }

    private fun solve(
        matches: Int = 40,
        inliers: Int = 30,
        bounds: FloatArray = floatArrayOf(100f, 100f, 500f, 400f),
        pose: FloatArray = facing,
    ) = PlanarSolve(pose, matches, inliers, 1.5f, bounds)

    private fun yaw(deg: Double): AttitudeSample {
        val h = Math.toRadians(deg) / 2
        return AttitudeSample(floatArrayOf(0f, sin(h).toFloat(), 0f, cos(h).toFloat()), now)
    }

    private fun tracker(solver: FakeSolver = FakeSolver()) = PlanarTracker(PlanarTracker.Config(), { now }, solver) to solver

    @Test
    fun `a weak reference is refused`() {
        val (t, s) = tracker(FakeSolver(referenceFeatures = 29))
        assertFalse(t.setReference(frame, 640, 480, 0.5f, 0.4f))
        assertFalse(t.hasReference)
        assertEquals(1, s.cleared)
        s.next = solve()
        assertNull(t.track(frame, 640, 480, k))
    }

    @Test
    fun `vision pose is the OpenGL camera-from-plane view`() {
        val (t, s) = tracker()
        assertTrue(t.setReference(frame, 640, 480, 0.5f, 0.4f))
        s.next = solve()
        val pose = t.track(frame, 640, 480, k)!!
        assertEquals(PlanarTracker.Source.VISION, pose.source)
        assertEquals(30f / 40f, pose.confidence, 1e-6f)
        // Known answer: an upright camera in front of the plane has an identity GL rotation, the
        // plane's +Y stays up and the plane sits down -Z (in front of the GL camera).
        val expected = RigidMath.identity().also { it[12] = 0.1f; it[13] = -0.2f; it[14] = -2f }
        assertArrayEquals(expected, pose.viewMatrix, 1e-6f)
        assertArrayEquals(RigidMath.cvRowMajorToGlColumnMajor(facing), pose.viewMatrix, 0f)
    }

    @Test
    fun `count ratio and spread gates reject a solve`() {
        val (t, s) = tracker()
        t.setReference(frame, 640, 480, 0.5f, 0.4f)
        s.next = solve(matches = 11, inliers = 11)
        assertNull(t.track(frame, 640, 480, k))
        s.next = solve(matches = 40, inliers = 7)
        assertNull(t.track(frame, 640, 480, k))
        s.next = solve(matches = 40, inliers = 13) // ratio 0.325
        assertNull(t.track(frame, 640, 480, k))
        s.next = solve(bounds = floatArrayOf(100f, 100f, 250f, 400f)) // 150/640 < 0.25
        assertNull(t.track(frame, 640, 480, k))
        s.next = solve(bounds = floatArrayOf(100f, 100f, 500f, 210f)) // 110/480 < 0.25
        assertNull(t.track(frame, 640, 480, k))
        s.next = solve(matches = 40, inliers = 14) // ratio 0.35
        assertNotNull(t.track(frame, 640, 480, k))
    }

    @Test
    fun `a miss is gyro bridged with decaying confidence then dropped`() {
        val (t, s) = tracker()
        t.setReference(frame, 640, 480, 0.5f, 0.4f)
        s.next = solve()
        val locked = t.track(frame, 640, 480, k, yaw(0.0))!!
        s.next = null
        now += 100
        val bridged = t.track(frame, 640, 480, k, yaw(4.0))!!
        assertEquals(PlanarTracker.Source.GYRO_BRIDGE, bridged.source)
        assertEquals(0.75f * 0.75f, bridged.confidence, 1e-5f)
        assertEquals(4f, RigidMath.rotationDeltaDeg(locked.viewMatrix, bridged.viewMatrix), 1e-2f)
        assertArrayEquals(RigidMath.cameraCentre(locked.viewMatrix), RigidMath.cameraCentre(bridged.viewMatrix), 1e-5f)
        assertNull(t.track(frame, 640, 480, k, null)) // no attitude, nothing to bridge with
        now += 400
        assertNull(t.track(frame, 640, 480, k, yaw(5.0)))
        now -= 300 // even back inside the window, the held pose was dropped
        assertNull(t.track(frame, 640, 480, k, yaw(5.0)))
    }

    @Test
    fun `a lock without attitude does not reuse an older bridge`() {
        val (t, s) = tracker()
        t.setReference(frame, 640, 480, 0.5f, 0.4f)
        s.next = solve()
        t.track(frame, 640, 480, k, yaw(0.0))
        t.track(frame, 640, 480, k, null)
        s.next = null
        now += 50
        assertNull(t.track(frame, 640, 480, k, yaw(2.0)))
    }

    @Test
    fun `reset and close`() {
        val (t, s) = tracker()
        t.setReference(frame, 640, 480, 0.5f, 0.4f)
        t.reset()
        assertFalse(t.hasReference)
        s.next = solve()
        assertNull(t.track(frame, 640, 480, k))
        t.close()
        assertTrue(s.closed)
    }

    @Test
    fun `extent mapping and inlier bounds`() {
        assertArrayEquals(floatArrayOf(-0.5f, 0.4f, 0f), PlanarGeometry.pixelToExtents(0f, 0f, 200, 100, 0.5f, 0.4f), 1e-6f)
        assertArrayEquals(floatArrayOf(0.5f, -0.4f, 0f), PlanarGeometry.pixelToExtents(200f, 100f, 200, 100, 0.5f, 0.4f), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), PlanarGeometry.pixelToExtents(100f, 50f, 200, 100, 0.5f, 0.4f), 1e-6f)
        val pts = listOf(doubleArrayOf(10.0, 20.0), doubleArrayOf(50.0, 5.0), doubleArrayOf(30.0, 40.0), doubleArrayOf(999.0, 999.0))
        assertArrayEquals(floatArrayOf(10f, 5f, 50f, 40f), Relocalizer.inlierBounds(pts, intArrayOf(0, 1, 2)), 0f)
        assertArrayEquals(FloatArray(4), Relocalizer.inlierBounds(pts, IntArray(0)), 0f)
        val detail = RelocDetail(RelocResult(RigidMath.identity(), 6), 8, floatArrayOf(0f, 0f, 1f, 1f))
        assertEquals(0.75f, detail.inlierRatio, 0f)
    }
}

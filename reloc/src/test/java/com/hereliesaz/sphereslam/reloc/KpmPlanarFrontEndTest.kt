package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.PlanarMatchRequest
import com.hereliesaz.sphereslam.SphereSlamPoseMath
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.math.RigidMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * [KpmPlanarFrontEnd]'s scheduling, scaling and fallback logic against a fake KPM backend and a fake
 * flow tracker (the real ones need the native KPM and OpenCV libraries).
 */
class KpmPlanarFrontEndTest {

    private val k = CameraIntrinsics(500f, 500f, 319.5f, 239.5f, 640, 480)
    private val frameBytes = ByteArray(640 * 480) { (it % 251).toByte() }
    private val frameBuffer = ByteBuffer.allocateDirect(frameBytes.size).apply { put(frameBytes); flip() }
    private val view = RigidMath.identity().also { it[14] = -2f }

    private class Call(val pageNo: Int, val maxCorrespondences: Int, val lumaBytes: Int)

    private inner class FakeBackend(val intrinsics: CameraIntrinsics) : KpmMatcherBackend {
        val calls = mutableListOf<Call>()
        var answer: (pageNo: Int) -> KpmMatcherBackend.Solve? = { solve(0) }
        var closed = false

        override fun addReference(
            luma: ByteBuffer, width: Int, height: Int, referenceWidthUnits: Float,
            physicallyMetric: Boolean, pageNo: Int, canonicalFromPage: FloatArray,
        ): PhotosphereTrackingSession.RegisteredPage {
            val dpi = SphereSlamPoseMath.dpiForReferenceWidth(width, referenceWidthUnits)
            return PhotosphereTrackingSession.RegisteredPage(pageNo, 100, SphereSlamPoseMath.pageGeometry(width, height, dpi), physicallyMetric)
        }

        override fun match(luma: ByteBuffer, timestampNs: Long, pageNo: Int, maxCorrespondences: Int): KpmMatcherBackend.Solve? {
            calls += Call(pageNo, maxCorrespondences, luma.remaining())
            return answer(pageNo)
        }

        override fun cameraFromCanonical(pageNo: Int, cameraFromPage3x4: FloatArray): FloatArray = view.copyOf()
        override fun fork(): KpmMatcherBackend? = null
        override fun close() {
            closed = true
        }
    }

    /** A solve on [page] whose correspondences are in the downscaled image's pixels. */
    private fun solve(page: Int, error: Float = 2f) = KpmMatcherBackend.Solve(
        pageNo = page,
        cameraFromCanonical = view,
        reprojectionError = error,
        inlierCount = 40,
        cameraFromPage3x4 = FloatArray(12) { if (it % 5 == 0) 1f else 0f },
        correspondences = floatArrayOf(0f, 0f, 10f, 20f, 100f, 50f, 30f, 40f),
    )

    private class FakeFlow : FlowTracker {
        var seeded: FloatArray? = null
        var seeds = 0
        var tracks = 0
        var trackResult: FlowTracker.Result? = FlowTracker.Result(FloatArray(12), 1.5f, 30)
        override var trackedPage = -1
        override fun seed(luma: ByteArray, pageNo: Int, correspondences: FloatArray, cameraFromPage3x4: FloatArray): Boolean {
            seeds++
            seeded = correspondences.copyOf()
            trackedPage = pageNo
            return true
        }

        override fun track(luma: ByteArray): FlowTracker.Result? {
            tracks++
            return trackResult.also { if (it == null) trackedPage = -1 }
        }

        override fun reset() {
            trackedPage = -1
        }

        override fun close() {}
    }

    private var backend: FakeBackend? = null
    private val flow = FakeFlow()
    private var flowCreations = 0

    private fun frontEnd(
        config: KpmMatchSpeedConfig = KpmMatchSpeedConfig(),
        flowFactory: (CameraIntrinsics, KpmMatchSpeedConfig) -> FlowTracker = { _, _ -> flowCreations++; flow },
    ) = KpmPlanarFrontEnd(k, config, flowFactory, { mk -> FakeBackend(mk).also { backend = it } })

    private fun input(nowMs: Long, predicted: FloatArray? = null) =
        PlanarFrameInput(frameBuffer.duplicate(), frameBytes, nowMs * 1_000_000L, nowMs, predicted)

    @Test
    fun `KPM matches a half-resolution frame and reports its error in the matcher's pixels`() {
        val fe = frontEnd()
        assertEquals(2, fe.factor)
        assertEquals(320, backend!!.intrinsics.width)
        assertEquals(240, backend!!.intrinsics.height)
        val r = fe.match(input(0))!!
        assertEquals(320 * 240, backend!!.calls.single().lumaBytes)
        assertEquals(VisualMatchMethod.KPM_FULL, r.method)
        // Unscaled: the acceptance (10) and growth (4) gates are tuned on the matcher's pixels; ×factor²
        // made them 4× stricter at half resolution (no growth, rejected relocks).
        assertEquals(2f, r.reprojectionError, 0f)
        // Full-resolution matching reports KPM's error unchanged too.
        val full = frontEnd(KpmMatchSpeedConfig.FULL_KPM_EVERY_FRAME)
        assertEquals(2f, full.match(input(0))!!.reprojectionError, 0f)
    }

    @Test
    fun `flow is seeded with correspondences scaled back to full-frame pixels`() {
        val fe = frontEnd()
        fe.match(input(0))
        assertEquals(KpmMatchSpeedConfig().maxCorrespondences, backend!!.calls.single().maxCorrespondences)
        val seeded = flow.seeded!!
        // (x + ½)·2 − ½ for the image coordinates; page millimetres untouched.
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 10f, 20f, 200.5f, 100.5f, 30f, 40f), seeded, 0f)
    }

    @Test
    fun `flow tracks between periodic full solves and falls back to a full solve on loss`() {
        val cfg = KpmMatchSpeedConfig(fullSolveEveryFrames = 3, fullSolveIntervalMs = 10_000L)
        val fe = frontEnd(cfg)
        val methods = (0L until 6L).map { fe.match(input(it * 10))!!.method }
        assertEquals(
            listOf(
                VisualMatchMethod.KPM_FULL, // acquire + seed
                VisualMatchMethod.FLOW, VisualMatchMethod.FLOW, VisualMatchMethod.FLOW,
                VisualMatchMethod.KPM_FULL, // every 3 tracked frames
                VisualMatchMethod.FLOW,
            ),
            methods,
        )
        assertEquals(2, backend!!.calls.size)
        // Flow loss: a full solve on the same frame.
        flow.trackResult = null
        val r = fe.match(input(60))!!
        assertEquals(VisualMatchMethod.KPM_FULL, r.method)
        assertEquals(3, backend!!.calls.size)
        // Flow errors are already full-frame px².
        flow.trackResult = FlowTracker.Result(FloatArray(12), 1.5f, 30)
        assertEquals(1.5f, fe.match(input(70))!!.reprojectionError, 0f)
    }

    @Test
    fun `a gate rejection forces the next frame to a full solve`() {
        val fe = frontEnd()
        fe.match(input(0))
        assertEquals(VisualMatchMethod.FLOW, fe.match(input(10))!!.method)
        fe.onVerdict(false)
        assertEquals(VisualMatchMethod.KPM_FULL, fe.match(input(20))!!.method)
    }

    @Test
    fun `without OpenCV flow switches itself off for good and every frame is a full solve`() {
        val fe = frontEnd(flowFactory = { _, _ -> flowCreations++; throw UnsatisfiedLinkError("no opencv_java5") })
        repeat(4) { fe.match(input(it * 10L)) }
        assertTrue(fe.isFlowUnavailable)
        assertEquals(1, flowCreations) // not retried every frame
        assertEquals(4, backend!!.calls.size)
        assertTrue(backend!!.calls.all { it.pageNo == PlanarMatchRequest.ALL_PAGES })
        // No correspondences are requested once flow is off.
        assertEquals(0, backend!!.calls.last().maxCorrespondences)
    }

    @Test
    fun `a focused-page miss costs one solve and defers the all-pages search to the next frame`() {
        val fe = frontEnd(KpmMatchSpeedConfig(flowTracking = false))
        val window = RigidMath.identity()
        fe.addPage(ByteBuffer.allocateDirect(4), 2, 2, 1f, false, 0, window)
        fe.addPage(ByteBuffer.allocateDirect(4), 2, 2, 1f, false, 1, RigidMath.identity().also { it[12] = 1f })
        val b = backend!!
        b.answer = { solve(0) }
        assertNotNull(fe.match(input(0))) // all pages: nothing locked yet
        assertEquals(PlanarMatchRequest.ALL_PAGES, b.calls.last().pageNo)

        // Recent lock: the predicted page alone first.
        assertEquals(VisualMatchMethod.KPM_FOCUSED, fe.match(input(10))!!.method)
        assertEquals(0, b.calls.last().pageNo)

        // The focused page misses: no second solve this frame …
        b.answer = { page -> if (page == PlanarMatchRequest.ALL_PAGES) solve(1) else null }
        val before = b.calls.size
        assertNull(fe.match(input(20)))
        assertEquals(before + 1, b.calls.size)
        // … the next frame searches every page.
        val r = fe.match(input(30))!!
        assertEquals(VisualMatchMethod.KPM_FULL, r.method)
        assertEquals(PlanarMatchRequest.ALL_PAGES, b.calls.last().pageNo)
        assertEquals(1, r.pageNo)
        assertEquals(before + 2, b.calls.size)
    }

    @Test
    fun `close releases the backend`() {
        val fe = frontEnd()
        fe.close()
        assertTrue(backend!!.closed)
        assertFalse(fe.isFlowUnavailable)
    }
}

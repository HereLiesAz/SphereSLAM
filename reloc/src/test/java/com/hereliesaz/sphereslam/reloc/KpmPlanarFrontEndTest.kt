package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.PlanarImage
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
        val references = mutableListOf<Triple<Int, Int, Int>>() // width, height, luma bytes
        var lastLuma: ByteArray? = null

        override fun addReference(
            luma: ByteBuffer, width: Int, height: Int, referenceWidthUnits: Float,
            physicallyMetric: Boolean, pageNo: Int, canonicalFromPage: FloatArray,
        ): PhotosphereTrackingSession.RegisteredPage {
            references += Triple(width, height, luma.remaining())
            val dpi = SphereSlamPoseMath.dpiForReferenceWidth(width, referenceWidthUnits)
            return PhotosphereTrackingSession.RegisteredPage(pageNo, 100, SphereSlamPoseMath.pageGeometry(width, height, dpi), physicallyMetric)
        }

        override fun addView(luma: ByteBuffer, width: Int, height: Int, pageNo: Int, imageNo: Int, pageFromView: FloatArray, maxFeatures: Int, reindex: Boolean): Int = 50

        override fun match(luma: ByteBuffer, timestampNs: Long, pageNo: Int, maxCorrespondences: Int): KpmMatcherBackend.Solve? {
            calls += Call(pageNo, maxCorrespondences, luma.remaining())
            lastLuma = ByteArray(luma.remaining()).also { luma.duplicate().get(it) }
            return answer(pageNo)
        }

        val imageCalls = mutableListOf<List<PlanarImage>>()

        override fun matchImages(luma: ByteBuffer, timestampNs: Long, images: List<PlanarImage>, maxCorrespondences: Int): KpmMatcherBackend.Solve? {
            imageCalls += images
            return answer(images.first().pageNo)
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
        previews: ((CameraIntrinsics) -> PreviewMatcher?) = { null },
    ) = KpmPlanarFrontEnd(k, config, flowFactory, { mk -> FakeBackend(mk).also { backend = it } }, previewsFactory = previews)

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

    /**
     * A page (1 m wide) seen from [tiltDeg] off its normal, as the session predicts it: the
     * front end, its page centre (mm) and the predicted canonical view.
     */
    private fun obliqueScene(tiltDeg: Double): Triple<KpmPlanarFrontEnd, DoubleArray, Pair<DoubleArray, DoubleArray>> {
        val fe = frontEnd()
        val canonicalFromPage = RigidMath.identity().also { it[12] = 0.4f }
        val g = fe.addPage(ByteBuffer.allocateDirect(4), 800, 600, 1f, false, 0, canonicalFromPage).geometry
        val centre = doubleArrayOf(g.centerXmm.toDouble(), g.centerYmm.toDouble(), 0.0)
        return Triple(fe, centre, ObliqueGeometry.pose(tiltDeg, centre, 1_500.0))
    }

    private fun canonicalView(pose: Pair<DoubleArray, DoubleArray>, centre: DoubleArray) =
        SphereSlamPoseMath.pageViewToCanonicalView(
            SphereSlamPoseMath.pageToOpenGlViewMeters(ObliqueGeometry.pose3x4(pose.first, pose.second), centre[0].toFloat(), centre[1].toFloat()),
            RigidMath.identity().also { it[12] = 0.4f },
        )

    @Test
    fun `an oblique predicted view is matched rectified and solved in the real frame`() {
        val (fe, centre, truth) = obliqueScene(70.0)
        val mk = backend!!.intrinsics
        val km = floatArrayOf(mk.fx, mk.fy, mk.cx, mk.cy)
        val plan = ObliqueRectifier.plan(truth.first, truth.second, km, 30f, centre)!!
        val virtualFromReal = ObliqueRectifier.invert3(plan.realFromVirtual)!!
        // KPM's answer on the virtual frontal image: page points at their virtual pixels.
        val corr = ArrayList<Float>()
        for (gx in -8..8) for (gy in -8..8) {
            val px = centre[0] + gx * 30.0
            val py = centre[1] + gy * 30.0
            val real = ObliqueGeometry.project(km, truth.first, truth.second, px, py)
            if (real[0] < 0 || real[0] >= mk.width || real[1] < 0 || real[1] >= mk.height) continue
            val w = virtualFromReal[6] * real[0] + virtualFromReal[7] * real[1] + virtualFromReal[8]
            corr += ((virtualFromReal[0] * real[0] + virtualFromReal[1] * real[1] + virtualFromReal[2]) / w).toFloat()
            corr += ((virtualFromReal[3] * real[0] + virtualFromReal[4] * real[1] + virtualFromReal[5]) / w).toFloat()
            corr += px.toFloat()
            corr += py.toFloat()
        }
        assertTrue("visible ${corr.size / 4}", corr.size / 4 >= 20)
        // An all-pages request answers with the page it found.
        backend!!.answer = { KpmMatcherBackend.Solve(0, view, 0.5f, corr.size / 4, FloatArray(12), corr.toFloatArray()) }
        val r = fe.match(input(0, canonicalView(truth, centre)))!!
        assertEquals(VisualMatchMethod.KPM_RECTIFIED, r.method)
        val call = backend!!.calls.single()
        assertEquals(mk.width * mk.height, call.lumaBytes)
        // KPM saw the virtual view rendered from the full-resolution frame, not its downscaled copy.
        val expectedView = ObliqueRectifier.warp(
            frameBytes, k.width, k.height, fe.factor, mk.width, mk.height,
            ObliqueRectifier.plan(truth.first, truth.second, km, 30f, centre)!!, ByteBuffer.allocateDirect(mk.width * mk.height),
        ).let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        // (Within a level: the front end's plan comes from the float canonical round trip.)
        val seen = backend!!.lastLuma!!
        val off = expectedView.indices.count { kotlin.math.abs((expectedView[it].toInt() and 0xff) - (seen[it].toInt() and 0xff)) > 2 }
        assertEquals(0, off)
        assertTrue(call.maxCorrespondences >= KpmMatchSpeedConfig().maxCorrespondences)
        assertTrue(r.reprojectionError < 0.01f)
        // Flow is seeded in full-frame pixels of the real (not the virtual) image.
        val seeded = flow.seeded!!
        val expected = ObliqueGeometry.project(
            floatArrayOf(k.fx, k.fy, k.cx, k.cy), truth.first, truth.second, seeded[2].toDouble(), seeded[3].toDouble(),
        )
        assertEquals(expected[0], seeded[0].toDouble(), 0.1)
        assertEquals(expected[1], seeded[1].toDouble(), 0.1)
    }

    @Test
    fun `a frontal predicted view, or rectification off, matches the frame directly`() {
        val (fe, centre, frontal) = obliqueScene(10.0)
        assertEquals(VisualMatchMethod.KPM_FULL, fe.match(input(0, canonicalView(frontal, centre)))!!.method)

        val off = frontEnd(KpmMatchSpeedConfig(obliqueRectificationDeg = 0f))
        off.addPage(ByteBuffer.allocateDirect(4), 800, 600, 1f, false, 0, RigidMath.identity().also { it[12] = 0.4f })
        val oblique = ObliqueGeometry.pose(70.0, centre, 1_500.0)
        assertEquals(VisualMatchMethod.KPM_FULL, off.match(input(0, canonicalView(oblique, centre)))!!.method)
    }

    @Test
    fun `with no usable prediction, repeated misses bring guessed steep views into the search`() {
        val (fe, centre, _) = obliqueScene(50.0)
        val mk = backend!!.intrinsics
        val km = floatArrayOf(mk.fx, mk.fy, mk.cx, mk.cy)
        val b = backend!!
        b.answer = { null }
        // Three direct misses, then guesses alternate with direct solves.
        repeat(3) { assertNull(fe.match(input(it * 10L))) }
        assertEquals(null, fe.diagnosticsAndReset()) // nothing rectified yet, no prediction to judge
        assertNull(fe.match(input(30))) // direct
        // The first guess: 45° along the wall, two page widths out. The camera really is 50° along it.
        val truth = ObliqueGeometry.pose(50.0, centre, 1_200.0)
        val guess = ObliqueRectifier.hypotheses(centre, 4.0 * centre[0], KpmMatchSpeedConfig().obliqueSearchTiltsDeg.toFloatArray()).first()
        val plan = ObliqueRectifier.plan(guess.first, guess.second, km, 30f, centre)!!
        val virtualFromReal = ObliqueRectifier.invert3(plan.realFromVirtual)!!
        val corr = ArrayList<Float>()
        for (gx in -8..8) for (gy in -8..8) {
            val px = centre[0] + gx * 30.0
            val py = centre[1] + gy * 30.0
            val real = ObliqueGeometry.project(km, truth.first, truth.second, px, py)
            if (real[0] < 0 || real[0] >= mk.width || real[1] < 0 || real[1] >= mk.height) continue
            val w = virtualFromReal[6] * real[0] + virtualFromReal[7] * real[1] + virtualFromReal[8]
            corr += ((virtualFromReal[0] * real[0] + virtualFromReal[1] * real[1] + virtualFromReal[2]) / w).toFloat()
            corr += ((virtualFromReal[3] * real[0] + virtualFromReal[4] * real[1] + virtualFromReal[5]) / w).toFloat()
            corr += px.toFloat()
            corr += py.toFloat()
        }
        assertTrue("visible ${corr.size / 4}", corr.size / 4 >= 20)
        b.answer = { KpmMatcherBackend.Solve(0, view, 0.5f, corr.size / 4, FloatArray(12), corr.toFloatArray()) }
        val r = fe.match(input(40))!!
        assertEquals(VisualMatchMethod.KPM_RECTIFIED, r.method)
        assertTrue(r.reprojectionError < 0.01f)
        assertEquals("rect{planned=0 swept=1 kpm=1 solved=1 frontal=0 maxObliq=0}", fe.diagnosticsAndReset())
        // A hit ends the search: the next miss is direct again.
        b.answer = { null }
        flow.trackResult = null
        val before = b.calls.size
        assertNull(fe.match(input(50)))
        assertNull(fe.diagnosticsAndReset())
        assertEquals(before + 1, b.calls.size)
    }

    @Test
    fun `the guessed-view search can be switched off`() {
        val fe = frontEnd(KpmMatchSpeedConfig(obliqueSearchAfterMisses = 0))
        fe.addPage(ByteBuffer.allocateDirect(4), 800, 600, 1f, false, 0, RigidMath.identity())
        backend!!.answer = { null }
        repeat(8) { fe.match(input(it * 10L)) }
        assertNull(fe.diagnosticsAndReset())
    }

    @Test
    fun `once lost every other full solve searches the previews and a hit relocks there`() {
        val pb = FakeBackend(k).apply { answer = { null } }
        val matcher = PreviewMatcher(pb, KpmMatchSpeedConfig(), java.util.concurrent.Executor { it.run() }, useOpenCv = false)
        val fe = frontEnd(KpmMatchSpeedConfig(flowTracking = false, obliqueSearchAfterMisses = 0), previews = { matcher })
        fe.addPage(ByteBuffer.allocateDirect(400 * 300), 400, 300, 1f, false, 0, RigidMath.identity())
        assertTrue(matcher.isReady)
        val live = backend!!
        live.answer = { null }
        // Two direct misses, then previews and direct take turns.
        assertNull(fe.match(input(0))); assertNull(fe.match(input(10)))
        assertEquals(0, pb.calls.size)
        assertNull(fe.match(input(20)))
        assertEquals(1, pb.calls.size)
        assertNull(fe.match(input(30)))
        assertEquals(1, pb.calls.size)
        assertEquals(4, live.calls.size + pb.calls.size)
        pb.answer = { solve(0) }
        val r = fe.match(input(40))!!
        assertEquals(VisualMatchMethod.KPM_PREVIEW, r.method)
        assertTrue(fe.diagnosticsAndReset()!!.contains("previews{images=27 features=") )
        // A hit ends the search: back to direct solves.
        val before = pb.calls.size
        fe.match(input(50))
        assertEquals(before, pb.calls.size)
    }

    @Test
    fun `a lost search starts with the previews nearest the predicted view and widens on each miss`() {
        val pb = FakeBackend(k).apply { answer = { null } }
        val matcher = PreviewMatcher(pb, KpmMatchSpeedConfig(), java.util.concurrent.Executor { it.run() }, useOpenCv = false)
        val fe = frontEnd(
            KpmMatchSpeedConfig(flowTracking = false, obliqueSearchAfterMisses = 0, obliqueRectificationDeg = 0f),
            previews = { matcher },
        )
        val g = fe.addPage(ByteBuffer.allocateDirect(400 * 300), 400, 300, 1f, false, 0, RigidMath.identity().also { it[12] = 0.4f }).geometry
        val centre = doubleArrayOf(g.centerXmm.toDouble(), g.centerYmm.toDouble(), 0.0)
        // Upright (the synthetic pose is rolled half a turn), 58° off the wall's normal from its right.
        val predicted = canonicalView(ObliqueGeometry.pose(58.0, centre, 1_500.0, rollDeg = 180.0), centre)
        backend!!.answer = { null }
        val n = KpmMatchSpeedConfig().previewNearest
        val all = matcher.views.size
        var t = 0L
        repeat(2) { assertNull(fe.match(input(t, predicted))); t += 10 }
        // Preview searches (every other full solve) take the nearest n, then 2n, 3n …, then all.
        var expected = n
        while (expected < all) {
            assertNull(fe.match(input(t, predicted))); t += 10
            assertEquals(expected, pb.imageCalls.last().size)
            assertNull(fe.match(input(t, predicted))); t += 10 // direct
            expected += n
        }
        val guided = pb.imageCalls.size
        assertEquals(0, pb.calls.size)
        assertNull(fe.match(input(t, predicted))); t += 10
        assertEquals(guided, pb.imageCalls.size)
        assertEquals(1, pb.calls.size) // all of them
        // The first set was the side tilts on the camera's side, nearest first.
        val labels = pb.imageCalls.first().map { im -> matcher.views.single { it.imageNo == im.imageNo }.label }
        assertEquals("tilt side 60°", labels.first().replace("-", ""))
        assertTrue(labels.toString(), labels.take(2).all { it.startsWith("tilt side") })
        assertTrue(fe.diagnosticsAndReset()!!.contains("guided=$guided "))
        // A hit resets the search: the next one starts from the nearest n again.
        pb.answer = { solve(0) }
        assertNull(fe.match(input(t, predicted))); t += 10 // direct
        assertEquals(VisualMatchMethod.KPM_PREVIEW, fe.match(input(t, predicted))!!.method); t += 10
        pb.answer = { null }
        repeat(3) { fe.match(input(t, predicted)); t += 10 } // two direct misses, then the previews
        assertEquals(n, pb.imageCalls.last().size)
        // Without a prediction, every preview is searched.
        val before = pb.calls.size
        repeat(2) { fe.match(input(t)); t += 10 }
        assertEquals(before + 1, pb.calls.size)
    }

    @Test
    fun `previews off builds none`() {
        var asked = false
        val fe = frontEnd(KpmMatchSpeedConfig(previewSearchAfterMisses = 0), previews = { asked = true; null })
        fe.addPage(ByteBuffer.allocateDirect(4), 2, 2, 1f, false, 0, RigidMath.identity())
        assertTrue(!asked)
    }

    @Test
    fun `a frontal prediction is counted for the diagnostic`() {
        val (fe, centre, frontal) = obliqueScene(10.0)
        fe.match(input(0, canonicalView(frontal, centre)))
        assertEquals("rect{planned=0 swept=0 kpm=0 solved=0 frontal=1 maxObliq=0}", fe.diagnosticsAndReset())
    }

    @Test
    fun `a failed rectified solve returns no pose`() {
        val (fe, centre, truth) = obliqueScene(70.0)
        backend!!.answer = { null }
        assertNull(fe.match(input(0, canonicalView(truth, centre))))
        assertEquals(1, backend!!.calls.size)
    }

    @Test
    fun `a reference far larger than the matched frame is registered from a copy, same page geometry`() {
        val fe = frontEnd()
        val big = ByteBuffer.allocateDirect(4000 * 3000)
        val r = fe.addPage(big, 4000, 3000, 2f, true, 0, RigidMath.identity())
        // 3 × the 320 px matcher long side.
        assertEquals(Triple(960, 720, 960 * 720), backend!!.references.single())
        assertEquals(2f, r.geometry.widthMeters, 1e-5f)
        assertEquals(1.5f, r.geometry.heightMeters, 1e-5f)

        val asGiven = frontEnd(KpmMatchSpeedConfig(maxReferenceScale = 0f))
        asGiven.addPage(ByteBuffer.allocateDirect(4000 * 3000), 4000, 3000, 2f, true, 0, RigidMath.identity())
        assertEquals(Triple(4000, 3000, 4000 * 3000), backend!!.references.single())
        // Already small enough: untouched.
        val small = frontEnd()
        small.addPage(ByteBuffer.allocateDirect(800 * 600), 800, 600, 1f, false, 0, RigidMath.identity())
        assertEquals(Triple(800, 600, 800 * 600), backend!!.references.single())
    }

    @Test
    fun `close releases the backend`() {
        val fe = frontEnd()
        fe.close()
        assertTrue(backend!!.closed)
        assertFalse(fe.isFlowUnavailable)
    }
}

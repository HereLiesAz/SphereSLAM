package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.PlanarImage
import com.hereliesaz.sphereslam.SphereSlamPoseMath
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.math.hypot

/** [ReferencePreviews] geometry and [PreviewMatcher]'s build, on the JVM (OpenCV absent: the Kotlin warp). */
class ReferencePreviewsTest {

    private val w = 400
    private val h = 300

    /** Dark, with bright 13×13 dots on a grid: a dot survives even a 70° tilt and can be found again. */
    private fun reference(): ByteArray = ByteArray(w * h).also { img ->
        for (gy in 1..5) for (gx in 1..7) {
            val cx = gx * 50; val cy = gy * 50
            for (dy in -6..6) for (dx in -6..6) img[(cy + dy) * w + cx + dx] = 250.toByte()
        }
    }

    private fun at(img: ByteArray, iw: Int, x: Double, y: Double): Int =
        img[Math.round(y).toInt() * iw + Math.round(x).toInt()].toInt() and 0xff

    @Test
    fun `the default previews are four tilts each way about both axes, three sizes, seven turns`() {
        val plans = ReferencePreviews.plans(w, h, KpmMatchSpeedConfig())
        assertEquals(16, plans.count { it.kind == ReferencePreviews.Kind.TILT })
        assertEquals(3, plans.count { it.kind == ReferencePreviews.Kind.SIZE })
        assertEquals(7, plans.count { it.kind == ReferencePreviews.Kind.ROTATION })
        // None disables a family; too small a size is skipped.
        val none = ReferencePreviews.plans(w, h, KpmMatchSpeedConfig(previewTiltsDeg = emptyList(), previewScales = listOf(0.1f), previewRotationStepDeg = 0f))
        assertTrue(none.isEmpty())
    }

    @Test
    fun `every preview shows each reference point where its homography puts it, and maps back`() {
        val ref = reference()
        for (plan in ReferencePreviews.plans(w, h, KpmMatchSpeedConfig())) {
            val p = ReferencePreviews.render(ref, w, h, plan) // no OpenCV on the JVM: the Kotlin warp
            assertEquals(plan.width * plan.height, p.luma.size)
            var checked = 0
            for (gy in 1..5) for (gx in 1..7) {
                val v = ReferencePreviews.apply(plan.viewFromPage, gx * 50.0, gy * 50.0) ?: continue
                if (v[0] < 2 || v[1] < 2 || v[0] > plan.width - 3 || v[1] > plan.height - 3) continue
                // Shrunk previews blur a dot: it must still stand out from the dark ground.
                assertTrue("${plan.label} dot ($gx,$gy) at ${v.toList()}: ${at(p.luma, plan.width, v[0], v[1])}", at(p.luma, plan.width, v[0], v[1]) > 60)
                // pageFromView takes the preview pixel back to the reference pixel.
                val back = ReferencePreviews.apply(p.pageFromView.map { it.toDouble() }.toDoubleArray(), v[0], v[1])!!
                assertEquals(plan.label, gx * 50.0, back[0], 1e-3)
                assertEquals(plan.label, gy * 50.0, back[1], 1e-3)
                checked++
            }
            assertTrue("${plan.label}: $checked dots in view", checked >= 4)
        }
    }

    @Test
    fun `a tilt foreshortens across its axis and leaves the other`() {
        val side = ReferencePreviews.tiltHomography(w, h, 60.0, vertical = true)
        fun span(hm: DoubleArray, ax: Double, ay: Double, bx: Double, by: Double): Double {
            val a = ReferencePreviews.apply(hm, ax, ay)!!; val b = ReferencePreviews.apply(hm, bx, by)!!
            return hypot(a[0] - b[0], a[1] - b[1])
        }
        // About the vertical axis: a horizontal span near the centre shrinks by about cos 60°, a vertical one barely.
        val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
        assertEquals(0.5, span(side, cx - 10, cy, cx + 10, cy) / 20, 0.05)
        assertEquals(1.0, span(side, cx, cy - 10, cx, cy + 10) / 20, 0.05)
    }

    // --- the previews matcher -------------------------------------------------------------------

    private class Added(val pageNo: Int, val imageNo: Int, val reindex: Boolean, val pageFromView: FloatArray)

    /** The pixel-plane homography of a camera at rotation (page frame) [r] looking at the centre from f. */
    private fun cameraHomography(r: DoubleArray, f: Double): DoubleArray {
        val rp = ObliqueRectifier.mul3(r, ReferencePreviews.FRONTAL) // pixel plane (y down) → camera
        val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
        val k = doubleArrayOf(f, 0.0, cx, 0.0, f, cy, 0.0, 0.0, 1.0)
        val m = doubleArrayOf(rp[0], rp[1], 0.0, rp[3], rp[4], 0.0, rp[6], rp[7], f)
        return ObliqueRectifier.mul3(ObliqueRectifier.mul3(k, m), doubleArrayOf(1.0, 0.0, -cx, 0.0, 1.0, -cy, 0.0, 0.0, 1.0))
    }

    @Test
    fun `each preview is what a camera at its recorded rotation would see, up to scale and shift`() {
        val f = maxOf(w, h) * 1.2
        for (plan in ReferencePreviews.plans(w, h, KpmMatchSpeedConfig())) {
            val m = ObliqueRectifier.mul3(plan.viewFromPage, ObliqueRectifier.invert3(cameraHomography(plan.cameraFromPage, f))!!)
            val n = DoubleArray(9) { m[it] / m[8] }
            for (i in listOf(1, 3, 6, 7)) assertEquals("${plan.label} m[$i]", 0.0, n[i] / n[0], 1e-6)
            assertEquals(plan.label, 1.0, n[4] / n[0], 1e-6)
            if (plan.kind == ReferencePreviews.Kind.SIZE) assertEquals(plan.label, plan.scale, n[0], 1e-9)
        }
    }

    /** A camera's rotation (page frame) looking at the page from direction [dir] (page frame, toward the camera). */
    private fun lookingFrom(dir: DoubleArray): DoubleArray {
        val n = kotlin.math.sqrt(dir.sumOf { it * it })
        val fwd = DoubleArray(3) { -dir[it] / n }
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        // Image x along the page's x as far as it can be, image y down the page.
        val y0 = cross(fwd, doubleArrayOf(1.0, 0.0, 0.0)).let { v -> val l = kotlin.math.sqrt(v.sumOf { it * it }); DoubleArray(3) { v[it] / l } }
        val x0 = cross(y0, fwd)
        return doubleArrayOf(x0[0], x0[1], x0[2], y0[0], y0[1], y0[2], fwd[0], fwd[1], fwd[2])
    }

    private fun edge(plan: ReferencePreviews.Plan, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val a = ReferencePreviews.apply(plan.viewFromPage, ax, ay)!!
        val b = ReferencePreviews.apply(plan.viewFromPage, bx, by)!!
        return hypot(a[0] - b[0], a[1] - b[1])
    }

    @Test
    fun `the preview nearest a predicted view shows the near side of the wall larger`() {
        val plans = ReferencePreviews.plans(w, h, KpmMatchSpeedConfig())
        val views = plans.mapIndexed { i, p -> PreviewMatcher.View(0, i + 1, p.label, p.cameraFromPage, p.scale, w) }
        fun nearest(r: DoubleArray, scale: Double? = null) = plans[PreviewMatcher.nearest(views, { _, _ -> r to scale }, null).first().imageNo - 1]
        val a = Math.toRadians(58.0)
        // From the right, 58° off the normal: the 60° side tilt whose right edge is the taller.
        val right = nearest(lookingFrom(doubleArrayOf(kotlin.math.sin(a), 0.0, kotlin.math.cos(a))))
        assertTrue(right.label, right.label.startsWith("tilt side") && right.label.endsWith("60°"))
        assertTrue(edge(right, w - 1.0, 0.0, w - 1.0, h - 1.0) > 1.2 * edge(right, 0.0, 0.0, 0.0, h - 1.0))
        // The same from the left.
        val left = nearest(lookingFrom(doubleArrayOf(-kotlin.math.sin(a), 0.0, kotlin.math.cos(a))))
        assertTrue(edge(left, 0.0, 0.0, 0.0, h - 1.0) > 1.2 * edge(left, w - 1.0, 0.0, w - 1.0, h - 1.0))
        // From above: the up/down tilt whose top edge is the longer.
        val above = nearest(lookingFrom(doubleArrayOf(0.0, kotlin.math.sin(a), kotlin.math.cos(a))))
        assertTrue(above.label, above.label.startsWith("tilt up/down"))
        assertTrue(edge(above, 0.0, 0.0, w - 1.0, 0.0) > 1.2 * edge(above, 0.0, h - 1.0, w - 1.0, h - 1.0))
        // Square-on: the size nearest the predicted scale; turned, the nearest turn.
        assertEquals("size ×0.25", nearest(ReferencePreviews.FRONTAL, 0.3).label)
        assertEquals("size ×2.0", nearest(ReferencePreviews.FRONTAL, 1.8).label)
        val roll = doubleArrayOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        val turned = nearest(ObliqueRectifier.mul3(roll, ReferencePreviews.FRONTAL))
        assertEquals("turn 90°", turned.label)
        assertEquals(90.0, ReferencePreviews.viewAngleDeg(turned.cameraFromPage, ReferencePreviews.FRONTAL), 1e-9)
    }

    private class Backend : KpmMatcherBackend {
        val references = mutableListOf<Int>()
        val views = mutableListOf<Added>()
        var answer: KpmMatcherBackend.Solve? = null
        var matches = 0
        var closed = false
        override fun addReference(luma: ByteBuffer, width: Int, height: Int, referenceWidthUnits: Float, physicallyMetric: Boolean, pageNo: Int, canonicalFromPage: FloatArray): PhotosphereTrackingSession.RegisteredPage {
            references += pageNo
            return PhotosphereTrackingSession.RegisteredPage(pageNo, 300, SphereSlamPoseMath.pageGeometry(width, height, 72f), physicallyMetric)
        }
        override fun addView(luma: ByteBuffer, width: Int, height: Int, pageNo: Int, imageNo: Int, pageFromView: FloatArray, maxFeatures: Int, reindex: Boolean): Int {
            assertEquals(width * height, luma.remaining())
            views += Added(pageNo, imageNo, reindex, pageFromView)
            return 100
        }
        override fun match(luma: ByteBuffer, timestampNs: Long, pageNo: Int, maxCorrespondences: Int): KpmMatcherBackend.Solve? { matches++; return answer }
        val imageSets = mutableListOf<List<PlanarImage>>()
        override fun matchImages(luma: ByteBuffer, timestampNs: Long, images: List<PlanarImage>, maxCorrespondences: Int): KpmMatcherBackend.Solve? { imageSets += images; return answer }
        override fun cameraFromCanonical(pageNo: Int, cameraFromPage3x4: FloatArray): FloatArray = FloatArray(16)
        override fun fork(): KpmMatcherBackend? = null
        override fun close() { closed = true }
    }

    @Test
    fun `a page's previews build in one batch, indexed once, and only then are searched`() {
        val b = Backend()
        val queued = ArrayList<Runnable>()
        val m = PreviewMatcher(b, KpmMatchSpeedConfig(), Executor { queued += it }, useOpenCv = false)
        m.addPage(reference(), w, h, 1f, false, 0, FloatArray(16) { if (it % 5 == 0) 1f else 0f })
        // Queued, not built: nothing to search yet.
        assertTrue(!m.isReady)
        assertNull(m.match(ByteBuffer.allocateDirect(4), 0L, 0))
        assertEquals(0, b.matches)
        queued.removeAt(0).run()
        assertEquals(listOf(0), b.references)
        assertEquals(26, b.views.size)
        assertEquals((1..26).toList(), b.views.map { it.imageNo })
        assertEquals(listOf(false), b.views.dropLast(1).map { it.reindex }.distinct())
        assertTrue(b.views.last().reindex)
        assertTrue(m.isReady)
        assertEquals(27, m.images)
        assertEquals(300 + 26 * 100, m.features)
        m.match(ByteBuffer.allocateDirect(4), 0L, 0)
        assertEquals(1, b.matches)
        // Every preview's view is recorded; the page itself is not a preview.
        assertEquals((1..26).toList(), m.views.map { it.imageNo })
        assertEquals(listOf(w), m.views.map { it.referenceWidthPx }.distinct())
        // A chosen set is matched alone.
        m.match(ByteBuffer.allocateDirect(4), 0L, 0, listOf(PlanarImage(0, 3), PlanarImage(0, 7)))
        assertEquals(listOf(PlanarImage(0, 3), PlanarImage(0, 7)), b.imageSets.single())
        assertEquals(1, b.matches)
        // A second page continues the numbering per page and is not searched mid-build.
        m.addPage(reference(), w, h, 1f, false, 1, FloatArray(16))
        assertTrue(!m.isReady)
        queued.removeAt(0).run()
        assertEquals(1, b.views.filter { it.pageNo == 1 }.minOf { it.imageNo })
        // Shared by forks: closed with the last user.
        val shared = m.retain()
        m.release()
        assertTrue(!b.closed)
        shared.release()
        queued.forEach { it.run() }
        assertTrue(b.closed)
    }
}

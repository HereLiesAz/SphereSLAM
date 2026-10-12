package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.PlanarMatchRequest
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
        assertNull(m.match(ByteBuffer.allocateDirect(4), 0L, PlanarMatchRequest.ALL_PAGES, 0))
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
        m.match(ByteBuffer.allocateDirect(4), 0L, PlanarMatchRequest.ALL_PAGES, 0)
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

package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.AnticipatedView
import com.hereliesaz.sphereslam.PlanarImage
import com.hereliesaz.sphereslam.SphereSlamPoseMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/** [PreviewMatcher]: the host's views, learned in batches and searched as the host allows. */
class PreviewMatcherTest {

    private class Added(val pageNo: Int, val imageNo: Int, val reindex: Boolean, val pageFromView: FloatArray, val maxFeatures: Int)

    private class Backend : KpmMatcherBackend {
        val references = mutableListOf<Int>()
        val views = mutableListOf<Added>()
        var answer: KpmMatcherBackend.Solve? = null
        var matches = 0
        var reindexes = 0
        var closed = false
        var refuseAfter = Int.MAX_VALUE
        val imageSets = mutableListOf<List<PlanarImage>>()
        override fun addReference(luma: ByteBuffer, width: Int, height: Int, referenceWidthUnits: Float, physicallyMetric: Boolean, pageNo: Int, canonicalFromPage: FloatArray): PhotosphereTrackingSession.RegisteredPage {
            references += pageNo
            return PhotosphereTrackingSession.RegisteredPage(pageNo, 300, SphereSlamPoseMath.pageGeometry(width, height, 72f), physicallyMetric)
        }
        override fun addView(luma: ByteBuffer, width: Int, height: Int, pageNo: Int, imageNo: Int, pageFromView: FloatArray, maxFeatures: Int, reindex: Boolean): Int {
            assertEquals(width * height, luma.remaining())
            if (views.size >= refuseAfter) return -8
            views += Added(pageNo, imageNo, reindex, pageFromView, maxFeatures)
            return 100
        }
        override fun reindex(): Int { reindexes++; return 0 }
        override fun match(luma: ByteBuffer, timestampNs: Long, pageNo: Int, maxCorrespondences: Int): KpmMatcherBackend.Solve? { matches++; return answer }
        override fun matchImages(luma: ByteBuffer, timestampNs: Long, images: List<PlanarImage>, maxCorrespondences: Int): KpmMatcherBackend.Solve? { imageSets += images; return answer }
        override fun cameraFromCanonical(pageNo: Int, cameraFromPage3x4: FloatArray): FloatArray = FloatArray(16)
        override fun fork(): KpmMatcherBackend? = null
        override fun close() { closed = true }
    }

    private fun view(id: String, scale: Float = 1f, rotation: FloatArray = AnticipatedView.SQUARE_ON) =
        AnticipatedView(id, ByteArray(8 * 6), 8, 6, floatArrayOf(2f, 0f, 1f, 0f, 2f, 3f, 0f, 0f, 1f), rotation, scale)

    private val queued = ArrayList<Runnable>()
    private fun drain() { while (queued.isNotEmpty()) queued.removeAt(0).run() }

    private fun matcher(b: Backend) = PreviewMatcher(b, KpmMatchSpeedConfig(previewMaxFeatures = 321), Executor { queued += it })

    @Test
    fun `the host's views are learned in batches, each indexed once, and only then searched`() {
        val b = Backend()
        val m = matcher(b)
        m.addPage(ByteArray(40 * 30), 40, 30, 1f, false, 0, FloatArray(16), referenceScale = 0.5)
        m.addViews(0, List(10) { view("v$it", scale = 2f) })
        // Queued, not built: nothing to search yet.
        assertTrue(!m.isReady)
        assertNull(m.match(ByteBuffer.allocateDirect(4), 0L, 0))
        drain()
        assertEquals(listOf(0), b.references)
        assertEquals((1..10).toList(), b.views.map { it.imageNo })
        assertEquals(listOf(false), b.views.map { it.reindex }.distinct())
        assertEquals(3, b.reindexes) // 10 in batches of 4
        assertEquals(listOf(321), b.views.map { it.maxFeatures }.distinct())
        assertTrue(m.isReady)
        assertEquals(11, m.images)
        assertEquals(300 + 10 * 100, m.features)
        // Views map to the page the live matcher registered: half the host's reference here.
        assertArrayEquals(floatArrayOf(1f, 0f, 0.25f, 0f, 1f, 1.25f, 0f, 0f, 1f), b.views.first().pageFromView, 1e-6f)
        assertEquals(4.0, m.views.first().scale, 1e-9) // per registered pixel
        assertEquals(40, m.views.first().referenceWidthPx)
        // A view already held is skipped; a new one continues the numbering.
        m.addViews(0, listOf(view("v3"), view("w")))
        drain()
        assertEquals(11, b.views.size)
        assertEquals(11, b.views.last().imageNo)
    }

    @Test
    fun `the host chooses which views are searched, at once`() {
        val b = Backend()
        val m = matcher(b)
        m.addPage(ByteArray(40 * 30), 40, 30, 1f, false, 0, FloatArray(16))
        m.addViews(0, List(6) { view("v$it") })
        drain()
        assertEquals(6, m.searchable().size)
        m.setSearchable(setOf("v1", "v4", "elsewhere"))
        assertEquals(listOf("v1", "v4"), m.searchable().map { it.id })
        assertTrue(queued.isEmpty())
        m.setSearchable(emptySet())
        assertTrue(m.searchable().isEmpty())
        m.setSearchable(null)
        assertEquals(6, m.searchable().size)
        // A chosen set is matched alone; without one, everything held.
        m.match(ByteBuffer.allocateDirect(4), 0L, 0, listOf(PlanarImage(0, 2)))
        assertEquals(listOf(PlanarImage(0, 2)), b.imageSets.single())
        m.match(ByteBuffer.allocateDirect(4), 0L, 0)
        assertEquals(1, b.matches)
    }

    @Test
    fun `KPM's image limit stops the build, a featureless page takes no views, and forks share it`() {
        val b = Backend().apply { refuseAfter = 5 }
        val m = matcher(b)
        m.addPage(ByteArray(40 * 30), 40, 30, 1f, false, 0, FloatArray(16))
        m.addViews(0, List(9) { view("v$it") })
        drain()
        assertEquals(5, b.views.size)
        assertEquals(5, m.views.size)
        // Views of a page that never registered are dropped.
        m.addViews(7, listOf(view("x")))
        drain()
        assertTrue(m.views.none { it.pageNo == 7 })
        val shared = m.retain()
        m.release()
        assertTrue(!b.closed)
        shared.release()
        drain()
        assertTrue(b.closed)
    }

    @Test
    fun `views rank nearest the predicted view by turn and by size`() {
        fun yaw(deg: Double): DoubleArray {
            val a = Math.toRadians(deg)
            // A camera to the page's right by [deg], aimed at it, upright.
            val f = doubleArrayOf(-kotlin.math.sin(a), 0.0, -kotlin.math.cos(a))
            return doubleArrayOf(-f[2], 0.0, f[0], 0.0, -1.0, 0.0, f[0], f[1], f[2])
        }
        val views = listOf(
            PreviewMatcher.View(0, 1, "square near", PreviewMatcher.SQUARE_ON, 2.0, 100),
            PreviewMatcher.View(0, 2, "square far", PreviewMatcher.SQUARE_ON, 0.5, 100),
            PreviewMatcher.View(0, 3, "right 60", yaw(60.0), 1.0, 100),
            PreviewMatcher.View(0, 4, "right 30", yaw(30.0), 1.0, 100),
        )
        fun first(r: DoubleArray, scale: Double?) = PreviewMatcher.nearest(views, { _, _ -> r to scale }, null).first().id
        assertEquals(0.0, PreviewMatcher.viewAngleDeg(yaw(0.0), PreviewMatcher.SQUARE_ON), 1e-9)
        assertEquals("right 60", first(yaw(55.0), 1.0))
        assertEquals("right 30", first(yaw(35.0), null))
        assertEquals("square far", first(PreviewMatcher.SQUARE_ON, 0.6))
        assertEquals("square near", first(PreviewMatcher.SQUARE_ON, 1.7))
    }
}

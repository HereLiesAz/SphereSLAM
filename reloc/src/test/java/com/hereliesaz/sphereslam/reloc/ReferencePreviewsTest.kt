package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.PlanarImage
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.SphereSlamPoseMath
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

    /** The matched image's camera: 320 × 240, a phone's field of view. */
    private val camera = CameraIntrinsics(260f, 260f, 159.5f, 119.5f, 320, 240)

    /** Half-spheres 0.7 to 2 target widths, near enough that every view keeps the target large. */
    private val config = KpmMatchSpeedConfig(anticipationNearestWidths = 0.7f, anticipationFarthestWidths = 2f, previewRotationStepDeg = 0f)

    private fun plans(resolution: AnticipationResolution, c: KpmMatchSpeedConfig = config) = ReferencePreviews.plans(w, h, camera, c, resolution)

    /** Rotation (page → camera) of a camera at [dir] (page frame, toward the camera) aimed at the core, upright. */
    private fun lookingFrom(dir: DoubleArray): DoubleArray {
        val n = kotlin.math.sqrt(dir.sumOf { it * it })
        val f = DoubleArray(3) { -dir[it] / n }
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        val x = cross(f, doubleArrayOf(0.0, 1.0, 0.0)).let { v -> val l = kotlin.math.sqrt(v.sumOf { it * it }); DoubleArray(3) { v[it] / l } }
        val y = cross(f, x)
        return doubleArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], f[0], f[1], f[2])
    }

    private fun dir(tiltDeg: Double, azimuthDeg: Double): DoubleArray {
        val t = Math.toRadians(tiltDeg); val a = Math.toRadians(azimuthDeg)
        return doubleArrayOf(kotlin.math.sin(t) * kotlin.math.cos(a), kotlin.math.sin(t) * kotlin.math.sin(a), kotlin.math.cos(t))
    }

    private fun edge(plan: ReferencePreviews.Plan, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val a = ReferencePreviews.apply(plan.viewFromPage, ax, ay)!!
        val b = ReferencePreviews.apply(plan.viewFromPage, bx, by)!!
        return hypot(a[0] - b[0], a[1] - b[1])
    }

    @Test
    fun `each half-sphere has its pole and every cut of a meridian by a circle, none on the equator`() {
        val p = plans(AnticipationResolution(distance = 3, latitude = 4, longitude = 1))
        assertEquals(3 * (1 + 4 * 1), p.size)
        assertEquals(3, p.count { it.kind == ReferencePreviews.Kind.POLE })
        // One circle: halfway to the equator, on each of the four meridians, on each half-sphere.
        assertTrue(p.filter { it.kind == ReferencePreviews.Kind.CUT }.all { it.label.contains("tilt 45°") })
        assertEquals(setOf(0, 1, 2), p.map { it.site.shell }.toSet())
        assertEquals(setOf(0, 1, 2, 3), p.filter { it.site.circle == 1 }.map { it.site.meridian }.toSet())
        // Three circles split the quarter into four: 22.5°, 45°, 67.5°.
        val three = plans(AnticipationResolution(1, 4, 3), config.copy(anticipationNearestWidths = 1f, anticipationFarthestWidths = 1f))
        assertEquals(setOf("22.5", "45", "67.5"), three.filter { it.kind == ReferencePreviews.Kind.CUT }.map { it.label.substringAfter("tilt ").substringBefore("°") }.toSet())
        // Turned poles are an extra, on the middle half-sphere.
        val turned = plans(AnticipationResolution(3, 4, 1), config.copy(previewRotationStepDeg = 90f))
        assertEquals(3, turned.count { it.kind == ReferencePreviews.Kind.TURN })
        assertTrue(turned.filter { it.kind == ReferencePreviews.Kind.TURN }.all { it.site.shell == 1 && it.site.turn > 0 })
        // Nothing at distance 0; labels name views uniquely.
        assertTrue(plans(AnticipationResolution.NONE).isEmpty())
        assertEquals(p.size, p.map { it.label }.distinct().size)
        assertEquals(listOf(0.5, 2.0, 8.0), ReferencePreviews.shells(3, 0.5f, 8f).map { Math.round(it * 1e6) / 1e6 })
    }

    @Test
    fun `each preview is what this phone's camera sees from its place on the half-sphere, cropped`() {
        val res = AnticipationResolution(3, 8, 3)
        val radii = ReferencePreviews.shells(3, config.anticipationNearestWidths, config.anticipationFarthestWidths)
        for (plan in plans(res)) {
            val site = plan.site
            val tilt = site.circle * 90.0 / (res.longitude + 1)
            val azimuth = site.meridian * 360.0 / res.latitude
            val d = dir(tilt, azimuth)
            val r = lookingFrom(d)
            assertEquals(plan.label, 0.0, ReferencePreviews.viewAngleDeg(r, plan.cameraFromPage), 1e-4)
            val c = DoubleArray(3) { radii[site.shell] * d[it] }
            // Reference pixel → page (target widths, y up) → camera → this camera's pixels, up to the crop.
            fun project(x: Double, y: Double): DoubleArray {
                val p = doubleArrayOf((x - (w - 1) / 2.0) / w - c[0], -(y - (h - 1) / 2.0) / w - c[1], -c[2])
                val q = DoubleArray(3) { i -> r[i * 3] * p[0] + r[i * 3 + 1] * p[1] + r[i * 3 + 2] * p[2] }
                return doubleArrayOf(camera.fx * q[0] / q[2] + camera.cx, camera.fy * q[1] / q[2] + camera.cy)
            }
            val o = project(w / 2.0, h / 2.0)
            val v0 = ReferencePreviews.apply(plan.viewFromPage, w / 2.0, h / 2.0)!!
            for ((x, y) in listOf(10.0 to 10.0, 390.0 to 20.0, 200.0 to 290.0, 300.0 to 150.0)) {
                val e = project(x, y); val v = ReferencePreviews.apply(plan.viewFromPage, x, y)!!
                assertEquals(plan.label, e[0] - o[0], v[0] - v0[0], 1e-6)
                assertEquals(plan.label, e[1] - o[1], v[1] - v0[1], 1e-6)
            }
            // Within the camera's frame.
            assertTrue(plan.label, plan.width <= camera.width && plan.height <= camera.height)
            // Square-on, the target is fx / distance pixels wide.
            if (plan.kind == ReferencePreviews.Kind.POLE) assertEquals(plan.label, camera.fx / radii[site.shell] / w, plan.scale, 1e-9)
        }
    }

    @Test
    fun `the near side of the target looks larger, and a near view is cropped to the frame`() {
        val p = plans(AnticipationResolution(3, 4, 3))
        fun cut(shell: Int, circle: Int, meridian: Int) = p.single { it.site == ReferencePreviews.Site(shell, circle, meridian) }
        // From the right (meridian 0), 67.5° off the pole: the right edge is the taller.
        val right = cut(1, 3, 0)
        assertTrue(edge(right, w - 1.0, 0.0, w - 1.0, h - 1.0) > 1.3 * edge(right, 0.0, 0.0, 0.0, h - 1.0))
        // From above (meridian 1, 90°): the top edge is the longer.
        val above = cut(1, 3, 1)
        assertTrue(edge(above, 0.0, 0.0, w - 1.0, 0.0) > 1.3 * edge(above, 0.0, h - 1.0, w - 1.0, h - 1.0))
        // 0.7 widths away the target (371 px wide) overflows the 320 × 240 frame: the preview is the frame.
        val near = p.single { it.kind == ReferencePreviews.Kind.POLE && it.site.shell == 0 }
        assertEquals(320 to 240, near.width to near.height)
        // Too far to match: dropped.
        assertTrue(plans(AnticipationResolution(1, 0, 0), config.copy(anticipationNearestWidths = 6f, anticipationFarthestWidths = 6f)).isEmpty())
    }

    @Test
    fun `every preview shows each reference point where its homography puts it, and maps back`() {
        val ref = reference()
        for (plan in plans(AnticipationResolution(3, 4, 3), config.copy(previewRotationStepDeg = 90f))) {
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
    fun `the preview nearest a predicted view is the cut it was seen from`() {
        val res = AnticipationResolution(3, 8, 3)
        val p = plans(res)
        val views = p.mapIndexed { i, plan -> PreviewMatcher.View(0, i + 1, plan.label, plan.cameraFromPage, plan.scale, w, plan.site, res) }
        fun nearest(r: DoubleArray, scale: Double? = null) = p[PreviewMatcher.nearest(views, { _, _ -> r to scale }, null).first().imageNo - 1]
        // From 60° to the right, the 67.5° cut toward 0°; from 40° above, the 45° cut toward 90°.
        assertTrue(nearest(lookingFrom(dir(60.0, 0.0))).label.endsWith("tilt 67.5° toward 0°"))
        assertTrue(nearest(lookingFrom(dir(40.0, 90.0))).label.endsWith("tilt 45° toward 90°"))
        // Square-on, the pole whose size matches the predicted scale.
        val radii = ReferencePreviews.shells(3, config.anticipationNearestWidths, config.anticipationFarthestWidths)
        for ((i, r) in radii.withIndex()) {
            val pole = nearest(ReferencePreviews.FRONTAL, camera.fx / (r * 1.05) / w)
            assertEquals(ReferencePreviews.Site(i, 0, 0), pole.site)
        }
    }

    @Test
    fun `previews are built farthest first, so a partial build spreads over the views`() {
        val all = plans(AnticipationResolution(3, 8, 3), config.copy(previewRotationStepDeg = 45f))
        val ordered = ReferencePreviews.prioritized(all) { p, r, s -> PreviewMatcher.priorityCost(p.cameraFromPage, p.scale, r, s) }
        assertEquals(all.toSet(), ordered.toSet())
        // Each pick is at least as far from those before it as every later one is (farthest first).
        fun gap(i: Int, j: Int) = (listOf(ReferencePreviews.FRONTAL to 1.0) + ordered.take(i).map { it.cameraFromPage to it.scale })
            .minOf { (r, s) -> PreviewMatcher.priorityCost(ordered[j].cameraFromPage, ordered[j].scale, r, s) }
        for (i in 1 until ordered.size) assertEquals("pick $i", (i until ordered.size).maxOf { gap(i, it) }, gap(i, i), 1e-9)
        // Turns weigh a quarter: the steepest cuts come before any turned pole.
        assertEquals(ReferencePreviews.Kind.CUT, ordered.first().kind)
        assertTrue(ordered.indexOfFirst { it.kind == ReferencePreviews.Kind.TURN } > 8)
    }

    // --- the previews matcher -------------------------------------------------------------------

    private class Added(val pageNo: Int, val imageNo: Int, val reindex: Boolean, val pageFromView: FloatArray)

    private val small = AnticipationResolution(3, 4, 1) // 15 previews

    private fun status(max: AnticipationResolution, d: AnticipationDecimation = AnticipationDecimation.FULL) =
        AnticipationStatus(max, d, 1f, 0f, 0f, 0f)

    @Test
    fun `a page's previews build in batches, each indexed once, and only then are searched`() {
        val b = Backend()
        val queued = ArrayList<Runnable>()
        val m = PreviewMatcher(b, config.copy(anticipationResolution = small), camera, Executor { queued += it }, useOpenCv = false)
        m.addPage(reference(), w, h, 1f, false, 0, FloatArray(16) { if (it % 5 == 0) 1f else 0f })
        // Queued, not built: nothing to search yet.
        assertTrue(!m.isReady)
        assertNull(m.match(ByteBuffer.allocateDirect(4), 0L, 0))
        assertEquals(0, b.matches)
        queued.removeAt(0).run()
        assertEquals(listOf(0), b.references)
        assertEquals(15, b.views.size)
        assertEquals((1..15).toList(), b.views.map { it.imageNo })
        assertEquals(listOf(false), b.views.map { it.reindex }.distinct())
        assertEquals(4, b.reindexes) // 15 in batches of 4
        assertTrue(m.isReady)
        assertEquals(16, m.images)
        assertEquals(300 + 15 * 100, m.features)
        assertEquals(15, m.searchable().size)
        m.match(ByteBuffer.allocateDirect(4), 0L, 0)
        assertEquals(1, b.matches)
        // A chosen set is matched alone.
        m.match(ByteBuffer.allocateDirect(4), 0L, 0, listOf(PlanarImage(0, 3), PlanarImage(0, 7)))
        assertEquals(listOf(PlanarImage(0, 3), PlanarImage(0, 7)), b.imageSets.single())
        // A second page numbers its own previews and is not searched mid-build.
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

    @Test
    fun `a throttled anticipation searches a subset at once and builds nothing, lifting it reuses them`() {
        val b = Backend()
        val queued = ArrayList<Runnable>()
        fun drain() { while (queued.isNotEmpty()) queued.removeAt(0).run() }
        val chain = AnticipationDecimation.chain(small)
        // Starts throttled: only the subset is built.
        val m = PreviewMatcher(b, config.copy(anticipationResolution = small), camera, Executor { queued += it }, useOpenCv = false)
        m.setAnticipation(status(small, chain[1]))
        m.addPage(reference(), w, h, 1f, false, 0, FloatArray(16))
        drain()
        val subset = chain[1].of(small).views
        assertEquals(subset, b.views.size)
        assertEquals(subset, m.searchable().size)
        // Lower: narrower at once, nothing queued.
        m.setAnticipation(status(small, chain[2]))
        assertTrue(queued.isEmpty())
        assertEquals(chain[2].of(small).views, m.searchable().size)
        assertTrue(m.searchable().all { v -> chain[1].keeps(v.site!!, small) })
        // Lifted to full: the rest is built, numbering continued, nothing built twice.
        m.setAnticipation(status(small))
        drain()
        assertEquals(15, b.views.size)
        assertEquals((1..15).toList(), b.views.map { it.imageNo })
        assertEquals(15, m.searchable().size)
        // Down to none: nothing searched.
        m.setAnticipation(status(small, AnticipationDecimation.NONE))
        assertTrue(m.searchable().isEmpty())
        assertTrue(queued.isEmpty())
        // A new maximum re-places what is held by label and builds only the new views.
        val bigger = AnticipationResolution(3, 8, 1)
        m.setAnticipation(status(bigger))
        drain()
        assertEquals(bigger.views, m.searchable().size)
        assertEquals(bigger.views, b.views.size) // the 15 shared views were not rebuilt
    }

    @Test
    fun `a throttle that arrives mid-build stops it between batches`() {
        val b = Backend()
        val queued = ArrayList<Runnable>()
        lateinit var m: PreviewMatcher
        var batches = 0
        val chain = AnticipationDecimation.chain(small)
        val throttling = object : KpmMatcherBackend by b {
            override fun reindex(): Int {
                if (++batches == 1) m.setAnticipation(status(small, AnticipationDecimation.NONE))
                return b.reindex()
            }
        }
        m = PreviewMatcher(throttling, config.copy(anticipationResolution = small), camera, Executor { queued += it }, useOpenCv = false)
        m.addPage(reference(), w, h, 1f, false, 0, FloatArray(16))
        queued.removeAt(0).run()
        assertEquals(4, b.views.size) // one batch, then the throttle held
        assertEquals(1, b.reindexes)
        assertTrue(m.searchable().isEmpty())
        assertTrue(queued.isEmpty())
        assertEquals(AnticipationDecimation.NONE, chain.last())
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
        var reindexes = 0
        override fun reindex(): Int { reindexes++; return 0 }
        val imageSets = mutableListOf<List<PlanarImage>>()
        override fun matchImages(luma: ByteBuffer, timestampNs: Long, images: List<PlanarImage>, maxCorrespondences: Int): KpmMatcherBackend.Solve? { imageSets += images; return answer }
        override fun cameraFromCanonical(pageNo: Int, cameraFromPage3x4: FloatArray): FloatArray = FloatArray(16)
        override fun fork(): KpmMatcherBackend? = null
        override fun close() { closed = true }
    }

}

package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.PlanarImage
import com.hereliesaz.sphereslam.PlanarMatchRequest
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A second KPM matcher holding every page and its [ReferencePreviews]: a search that needs no
 * prediction of where the camera is. Kept apart from the live matcher because KPM's matcher checks
 * every reference image on each query, so a few dozen previews per page in the live matcher would
 * slow every solve; this one runs only while the target is lost.
 *
 * Built off the camera thread: [addPage] queues the page and its previews on [executor] and returns.
 * [match] answers only once a build has finished and none is running (a build re-indexes KPM, which
 * a query must not see half-done), so it never waits on one. Shared by a front end and its forks
 * ([retain] / [release]); the last release closes it.
 *
 * Each preview's [View] says which camera view it stands for, so a search can start with the previews
 * nearest the view the sensors predict ([nearest]) and match only those ([match] with `images`).
 */
@ExperimentalSphereSlamRelocApi
internal class PreviewMatcher(
    private val backend: KpmMatcherBackend,
    private val config: KpmMatchSpeedConfig,
    private val executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sphereslam-previews").apply { isDaemon = true } },
    private val useOpenCv: Boolean = true,
) {
    private val users = AtomicInteger(1)
    private val building = AtomicInteger(0)
    @Volatile private var built = false
    @Volatile private var closed = false
    private val nextImage = HashMap<Int, Int>()

    /**
     * A preview held: KPM image [imageNo] of page [pageNo], showing the page as a camera at
     * [cameraFromPage] would see it ([ReferencePreviews.Plan]), [scale] preview pixels per pixel of the
     * [referenceWidthPx]-wide reference.
     */
    class View(val pageNo: Int, val imageNo: Int, val label: String, val cameraFromPage: DoubleArray, val scale: Double, val referenceWidthPx: Int)

    /** Every preview held (not the pages themselves), replaced whole when a build finishes. */
    @Volatile var views: List<View> = emptyList()
        private set

    /** Reference images held (pages and previews) and their features, for the diagnostic. */
    @Volatile var images = 0
        private set
    @Volatile var features = 0
        private set
    @Volatile var buildMs = 0L
        private set

    val isReady: Boolean get() = built && building.get() == 0 && !closed

    fun retain(): PreviewMatcher = also { users.incrementAndGet() }

    fun release() {
        if (users.decrementAndGet() == 0) {
            closed = true
            executor.execute { runCatching { backend.close() } }
            (executor as? java.util.concurrent.ExecutorService)?.shutdown()
        }
    }

    /**
     * Queues page [pageNo] ([luma], [width] × [height], the copy the live matcher registered) and its
     * previews. [luma] is copied; the page registers with the same geometry and placement as the live
     * matcher's, so the two agree on every pose.
     */
    fun addPage(
        luma: ByteArray,
        width: Int,
        height: Int,
        referenceWidthUnits: Float,
        physicallyMetric: Boolean,
        pageNo: Int,
        canonicalFromPage: FloatArray,
    ) {
        if (closed) return
        val copy = luma.copyOf()
        val placement = canonicalFromPage.copyOf()
        building.incrementAndGet()
        executor.execute {
            try {
                if (!closed) build(copy, width, height, referenceWidthUnits, physicallyMetric, pageNo, placement)
            } finally {
                building.decrementAndGet()
            }
        }
    }

    private fun build(luma: ByteArray, width: Int, height: Int, units: Float, metric: Boolean, pageNo: Int, placement: FloatArray) {
        val t0 = System.nanoTime()
        val direct = ByteBuffer.allocateDirect(luma.size).apply { put(luma); flip() }
        try {
            val r = backend.addReference(direct, width, height, units, metric, pageNo, placement)
            images++
            features += r.featureCount
        } catch (_: Exception) {
            return // a featureless page: no previews either
        }
        val plans = ReferencePreviews.plans(width, height, config)
        val added = ArrayList<View>()
        plans.forEachIndexed { i, plan ->
            if (closed) return
            val preview = ReferencePreviews.render(luma, width, height, plan, useOpenCv)
            val buf = ByteBuffer.allocateDirect(preview.luma.size).apply { put(preview.luma); flip() }
            val imageNo = (nextImage[pageNo] ?: 1).also { nextImage[pageNo] = it + 1 }
            // Index once, with the last preview: re-indexing after each would rebuild KPM's database
            // a few dozen times.
            val n = backend.addView(buf, preview.width, preview.height, pageNo, imageNo, preview.pageFromView, config.previewMaxFeatures, reindex = i == plans.lastIndex)
            if (n == ERROR_FULL) return@forEachIndexed
            if (n > 0) {
                images++
                features += n
                added += View(pageNo, imageNo, plan.label, plan.cameraFromPage, plan.scale, width)
            }
        }
        views = views + added
        buildMs = (System.nanoTime() - t0) / 1_000_000L
        built = true
    }

    /**
     * The previews' best match for this frame, or null (none, or not ready): among [images] only when
     * given (see [nearest]), else every page and preview.
     */
    fun match(luma: ByteBuffer, timestampNs: Long, maxCorrespondences: Int, images: List<PlanarImage>? = null): KpmMatcherBackend.Solve? {
        if (!isReady) return null
        if (images != null) return backend.matchImages(luma, timestampNs, images, maxCorrespondences)
        return backend.match(luma, timestampNs, PlanarMatchRequest.ALL_PAGES, maxCorrespondences)
    }

    companion object {
        /** KPM refused: the session holds its limit of reference images. */
        private const val ERROR_FULL = -8

        /** Degrees of view angle that weigh as much as a doubling of size in [nearest]. */
        const val DEG_PER_OCTAVE = 30.0

        /**
         * [views] nearest first to the view the sensors predict. [predicted] gives, for a page (and its
         * reference's width), the camera's rotation from that page's frame (as [View.cameraFromPage])
         * and the [View.scale] that would look like the frame at its predicted distance (null when
         * unknown), or null
         * when there is no prediction for that page (its previews go last). Assuming the target is in
         * view, the camera's rotation alone fixes how it sees the page — the tilt, which side, and the
         * turn — so the distance is the angle between the two views, plus [DEG_PER_OCTAVE] per doubling
         * of size off the predicted scale. Ties go to [preferredPage].
         */
        fun nearest(views: List<View>, predicted: (pageNo: Int, referenceWidthPx: Int) -> Pair<DoubleArray, Double?>?, preferredPage: Int?): List<View> {
            val cache = HashMap<Int, Pair<DoubleArray, Double?>?>()
            fun cost(v: View): Double {
                val (r, scale) = cache.getOrPut(v.pageNo) { predicted(v.pageNo, v.referenceWidthPx) } ?: return Double.MAX_VALUE
                var c = ReferencePreviews.viewAngleDeg(r, v.cameraFromPage)
                if (scale != null && scale > 0.0 && scale.isFinite()) {
                    c += DEG_PER_OCTAVE * kotlin.math.abs(kotlin.math.ln(v.scale / scale) / kotlin.math.ln(2.0))
                }
                return c
            }
            return views.map { it to cost(it) }
                .sortedWith(compareBy<Pair<View, Double>>({ it.second }, { if (it.first.pageNo == preferredPage) 0 else 1 }, { it.first.pageNo }, { it.first.imageNo }))
                .map { it.first }
        }
    }
}

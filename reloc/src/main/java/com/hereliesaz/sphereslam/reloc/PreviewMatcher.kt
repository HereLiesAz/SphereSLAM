package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.AnticipatedView
import com.hereliesaz.sphereslam.PlanarImage
import com.hereliesaz.sphereslam.PlanarMatchRequest
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ln

/**
 * A second KPM matcher holding every page and the host's [AnticipatedView]s of them: a search that
 * needs no prediction of where the camera is. Kept apart from the live matcher because KPM's matcher
 * checks every reference image on each query, so many views per page in the live matcher would slow
 * every solve; this one runs only while the target is lost.
 *
 * SphereSLAM does not choose or render the views: the host adds them ([addViews]) and says which to
 * search ([setSearchable]), and may change either at any time (throttling is the host's). Built off
 * the camera thread: pages and views queue on [executor], views [BATCH] at a time, each batch indexed
 * once. [match] answers only once a page is registered and no build is running (a build re-indexes
 * KPM, which a query must not see half-done), so it never waits on one. Shared by a front end and its
 * forks ([retain] / [release]); the last release closes it.
 *
 * Each [View] says which camera view it stands for, so a search can start with the views nearest the
 * view the sensors predict ([nearest]) and match only those ([match] with `images`).
 */
@ExperimentalSphereSlamRelocApi
internal class PreviewMatcher(
    private val backend: KpmMatcherBackend,
    private val config: KpmMatchSpeedConfig,
    private val executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sphereslam-previews").apply { isDaemon = true } },
) {
    private val users = AtomicInteger(1)
    private val building = AtomicInteger(0)
    @Volatile private var built = false
    @Volatile private var closed = false

    // Per registered page: the views added and the next image number (the executor thread's alone).
    private class Page(val registeredWidth: Int, val referenceScale: Double) {
        val ids = HashSet<String>()
        var nextImage = 1
    }
    private val registered = HashMap<Int, Page>()

    /**
     * A view held: KPM image [imageNo] of page [pageNo], the host's view [id]: showing the page as a
     * camera at [cameraFromPage] would see it, [scale] view pixels per pixel of the
     * [referenceWidthPx]-wide reference the page was registered from.
     */
    class View(
        val pageNo: Int,
        val imageNo: Int,
        val id: String,
        val cameraFromPage: DoubleArray,
        val scale: Double,
        val referenceWidthPx: Int,
    )

    /** Every view held (not the pages themselves), replaced whole after each batch. */
    @Volatile var views: List<View> = emptyList()
        private set

    /** The host's choice of views to search: ids, or null for every view. */
    @Volatile var searchableIds: Set<String>? = null
        private set

    /** Reference images held (pages and views) and their features, for the diagnostic. */
    @Volatile var images = 0
        private set
    @Volatile var features = 0
        private set
    /** Time spent building, all batches. */
    @Volatile var buildMs = 0L
        private set

    val isReady: Boolean get() = built && building.get() == 0 && !closed

    /** The views the host allows searched. */
    fun searchable(): List<View> {
        val ids = searchableIds ?: return views
        return views.filter { it.id in ids }
    }

    /** Which views may be searched: their ids, or null for all. Applies at once. */
    fun setSearchable(ids: Set<String>?) {
        searchableIds = ids?.toSet()
    }

    fun retain(): PreviewMatcher = also { users.incrementAndGet() }

    fun release() {
        if (users.decrementAndGet() == 0) {
            closed = true
            executor.execute { runCatching { backend.close() } }
            (executor as? java.util.concurrent.ExecutorService)?.shutdown()
        }
    }

    /**
     * Queues page [pageNo] ([luma], [width] × [height], the copy the live matcher registered, which is
     * [referenceScale] × the host's reference) so views of it can be added. [luma] is copied; the page
     * registers with the same geometry and placement as the live matcher's, so the two agree on every
     * pose.
     */
    fun addPage(
        luma: ByteArray,
        width: Int,
        height: Int,
        referenceWidthUnits: Float,
        physicallyMetric: Boolean,
        pageNo: Int,
        canonicalFromPage: FloatArray,
        referenceScale: Double = 1.0,
    ) {
        val copy = luma.copyOf()
        val placement = canonicalFromPage.copyOf()
        schedule { register(copy, width, height, referenceWidthUnits, physicallyMetric, pageNo, placement, referenceScale) }
    }

    /**
     * Queues the host's [views] of page [pageNo] (registered by an earlier [addPage]); views whose id
     * the page already holds are skipped. Built [BATCH] at a time in the order given, each batch
     * indexed once; KPM stops taking views at its image limit.
     */
    fun addViews(pageNo: Int, views: List<AnticipatedView>) {
        if (views.isEmpty()) return
        val copy = views.toList()
        schedule { build(pageNo, copy) }
    }

    private fun schedule(work: () -> Unit) {
        if (closed) return
        building.incrementAndGet()
        try {
            executor.execute {
                try {
                    if (!closed) work()
                } finally {
                    building.decrementAndGet()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            building.decrementAndGet()
        }
    }

    private fun register(luma: ByteArray, width: Int, height: Int, units: Float, metric: Boolean, pageNo: Int, placement: FloatArray, referenceScale: Double) {
        val t0 = System.nanoTime()
        val direct = ByteBuffer.allocateDirect(luma.size).apply { put(luma); flip() }
        try {
            val r = backend.addReference(direct, width, height, units, metric, pageNo, placement)
            images++
            features += r.featureCount
        } catch (_: Exception) {
            return // a featureless page: no views of it either
        }
        registered[pageNo] = Page(width, referenceScale)
        buildMs += (System.nanoTime() - t0) / 1_000_000L
        built = true
    }

    private fun build(pageNo: Int, pending: List<AnticipatedView>) {
        val page = registered[pageNo] ?: return
        val s = page.referenceScale
        // Host reference pixel → registered pixel (pixel centres): x' = s·(x + ½) − ½.
        val toRegistered = floatArrayOf(s.toFloat(), 0f, (0.5 * s - 0.5).toFloat(), 0f, s.toFloat(), (0.5 * s - 0.5).toFloat(), 0f, 0f, 1f)
        val fresh = pending.filter { page.ids.add(it.id) }
        for (batch in fresh.chunked(BATCH)) {
            if (closed) return
            val t0 = System.nanoTime()
            val added = ArrayList<View>()
            var full = false
            for (v in batch) {
                val luma = v.luma
                val buf = ByteBuffer.allocateDirect(luma.size).apply { put(luma); flip() }
                val imageNo = page.nextImage++
                val pageFromView = mul3f(toRegistered, v.pageFromView)
                val n = backend.addView(buf, v.width, v.height, pageNo, imageNo, pageFromView, config.previewMaxFeatures, reindex = false)
                if (n == ERROR_FULL) {
                    full = true
                    break
                }
                if (n > 0) {
                    images++
                    features += n
                    val r = v.cameraFromPage
                    added += View(pageNo, imageNo, v.id, DoubleArray(9) { r[it].toDouble() }, v.scale / s, page.registeredWidth)
                }
            }
            // Index the batch once, whichever of its views succeeded.
            if (added.isNotEmpty()) backend.reindex()
            views = views + added
            buildMs += (System.nanoTime() - t0) / 1_000_000L
            if (full) return // KPM holds its limit of images
        }
    }

    /**
     * The views' best match for this frame, or null (none, or not ready): among [images] only when
     * given (see [nearest], [searchable]), else every page and view held.
     */
    fun match(luma: ByteBuffer, timestampNs: Long, maxCorrespondences: Int, images: List<PlanarImage>? = null): KpmMatcherBackend.Solve? {
        if (!isReady) return null
        if (images != null) return backend.matchImages(luma, timestampNs, images, maxCorrespondences)
        return backend.match(luma, timestampNs, PlanarMatchRequest.ALL_PAGES, maxCorrespondences)
    }

    companion object {
        /** KPM refused: the session holds its limit of reference images. */
        private const val ERROR_FULL = -8

        /** Views built and indexed together. */
        const val BATCH = 4

        /** Degrees of view angle that weigh as much as a doubling of size in [nearest]. */
        const val DEG_PER_OCTAVE = 30.0

        /** Square-on and upright ([AnticipatedView.SQUARE_ON]). */
        val SQUARE_ON = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0)

        /** Angle in degrees between two views (row-major rotations): how far a camera at one must turn to be at the other. */
        fun viewAngleDeg(a: DoubleArray, b: DoubleArray): Double {
            // trace(A·Bᵀ) = 1 + 2 cos θ.
            var trace = 0.0
            for (i in 0..2) for (k in 0..2) trace += a[i * 3 + k] * b[i * 3 + k]
            return Math.toDegrees(acos(((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
        }

        /**
         * How far apart two views are: the angle between their rotations, plus [DEG_PER_OCTAVE] per
         * doubling of size between [scaleA] and [scaleB] (none when [scaleB] is null or unusable).
         */
        fun viewCost(rotationA: DoubleArray, scaleA: Double, rotationB: DoubleArray, scaleB: Double?): Double {
            var c = viewAngleDeg(rotationA, rotationB)
            if (scaleB != null && scaleB > 0.0 && scaleB.isFinite()) c += DEG_PER_OCTAVE * abs(ln(scaleA / scaleB) / ln(2.0))
            return c
        }

        /**
         * [views] nearest first to the view the sensors predict. [predicted] gives, for a page (and its
         * registered reference's width), the camera's rotation from that page's frame (as
         * [View.cameraFromPage]) and the [View.scale] that would look like the frame at its predicted
         * distance (null when unknown), or null when there is no prediction for that page (its views go
         * last). Assuming the target is in view, the camera's rotation alone fixes how it sees the page,
         * so the distance is the angle between the two views plus [DEG_PER_OCTAVE] per doubling of size
         * off the predicted scale. Ties go to [preferredPage].
         */
        fun nearest(views: List<View>, predicted: (pageNo: Int, referenceWidthPx: Int) -> Pair<DoubleArray, Double?>?, preferredPage: Int?): List<View> {
            val cache = HashMap<Int, Pair<DoubleArray, Double?>?>()
            fun cost(v: View): Double {
                val (r, scale) = cache.getOrPut(v.pageNo) { predicted(v.pageNo, v.referenceWidthPx) } ?: return Double.MAX_VALUE
                return viewCost(v.cameraFromPage, v.scale, r, scale)
            }
            return views.map { it to cost(it) }
                .sortedWith(compareBy<Pair<View, Double>>({ it.second }, { if (it.first.pageNo == preferredPage) 0 else 1 }, { it.first.pageNo }, { it.first.imageNo }))
                .map { it.first }
        }

        private fun mul3f(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
            val r = i / 3; val c = i % 3
            a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
    }
}

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
 * [match] answers only once a page is registered and no build is running (a build re-indexes KPM,
 * which a query must not see half-done), so it never waits on one. Shared by a front end and its
 * forks ([retain] / [release]); the last release closes it.
 *
 * How many previews: [resolution] (anticipation resolution, 0–1) of each page's plans, in
 * [ReferencePreviews.prioritized] order so any share covers the views evenly. Previews are built
 * [BATCH] at a time, each batch indexed once, and the resolution is read again between batches:
 * raising it builds the rest, lowering it stops building and narrows [searchable] at once.
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

    // A registered page and how far its previews are built (the executor thread's alone).
    private class Source(val pageNo: Int, val luma: ByteArray, val width: Int, val height: Int, val plans: List<ReferencePreviews.Plan>) {
        var next = 0
        var nextImage = 1
    }
    private val sources = ArrayList<Source>()

    /**
     * A preview held: KPM image [imageNo] of page [pageNo], showing the page as a camera at
     * [cameraFromPage] would see it ([ReferencePreviews.Plan]), [scale] preview pixels per pixel of the
     * [referenceWidthPx]-wide reference. [rank] is its place among the page's [ofPage] plans.
     */
    class View(
        val pageNo: Int,
        val imageNo: Int,
        val label: String,
        val cameraFromPage: DoubleArray,
        val scale: Double,
        val referenceWidthPx: Int,
        val rank: Int = 0,
        val ofPage: Int = 1,
    )

    /** Every preview held (not the pages themselves), replaced whole after each batch. */
    @Volatile var views: List<View> = emptyList()
        private set

    /** Anticipation resolution: the share (0–1) of each page's previews built and searched. */
    @Volatile var resolution: Float = config.anticipationResolution
        private set

    /** Reference images held (pages and previews) and their features, for the diagnostic. */
    @Volatile var images = 0
        private set
    @Volatile var features = 0
        private set
    /** Time spent building, all batches. */
    @Volatile var buildMs = 0L
        private set

    val isReady: Boolean get() = built && building.get() == 0 && !closed

    /** The previews within [resolution]: the first share of each page's, in priority order. */
    fun searchable(): List<View> {
        val r = resolution
        return views.filter { it.rank < share(r, it.ofPage) }
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
     * Queues page [pageNo] ([luma], [width] × [height], the copy the live matcher registered) and its
     * previews. [luma] is copied (and kept, to build more previews when [resolution] rises); the page
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
    ) {
        val copy = luma.copyOf()
        val placement = canonicalFromPage.copyOf()
        schedule {
            if (register(copy, width, height, referenceWidthUnits, physicallyMetric, pageNo, placement)) extend()
        }
    }

    /** Sets [resolution] (clamped to 0–1): more previews are built in the background, fewer searched at once. */
    fun setResolution(value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (v == resolution) return
        val raised = v > resolution
        resolution = v
        if (raised) schedule { extend() }
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

    private fun register(luma: ByteArray, width: Int, height: Int, units: Float, metric: Boolean, pageNo: Int, placement: FloatArray): Boolean {
        val t0 = System.nanoTime()
        val direct = ByteBuffer.allocateDirect(luma.size).apply { put(luma); flip() }
        try {
            val r = backend.addReference(direct, width, height, units, metric, pageNo, placement)
            images++
            features += r.featureCount
        } catch (_: Exception) {
            return false // a featureless page: no previews either
        }
        val plans = ReferencePreviews.prioritized(ReferencePreviews.plans(width, height, config)) { plan, r, scale ->
            priorityCost(plan.cameraFromPage, plan.scale, r, scale)
        }
        sources += Source(pageNo, luma, width, height, plans)
        buildMs += (System.nanoTime() - t0) / 1_000_000L
        built = true
        return true
    }

    /** Builds previews up to [resolution], [BATCH] at a time, each batch indexed once. */
    private fun extend() {
        while (!closed) {
            val r = resolution
            val src = sources.firstOrNull { it.next < share(r, it.plans.size) } ?: return
            val t0 = System.nanoTime()
            val end = minOf(src.next + BATCH, share(r, src.plans.size))
            val added = ArrayList<View>()
            var full = false
            for (i in src.next until end) {
                if (closed) return
                val plan = src.plans[i]
                val preview = ReferencePreviews.render(src.luma, src.width, src.height, plan, useOpenCv)
                val buf = ByteBuffer.allocateDirect(preview.luma.size).apply { put(preview.luma); flip() }
                val imageNo = src.nextImage++
                val n = backend.addView(buf, preview.width, preview.height, src.pageNo, imageNo, preview.pageFromView, config.previewMaxFeatures, reindex = false)
                if (n == ERROR_FULL) {
                    full = true
                    break
                }
                if (n > 0) {
                    images++
                    features += n
                    added += View(src.pageNo, imageNo, plan.label, plan.cameraFromPage, plan.scale, src.width, i, src.plans.size)
                }
            }
            src.next = end
            // Index the batch once, whichever of its previews succeeded.
            if (added.isNotEmpty()) backend.reindex()
            views = views + added
            buildMs += (System.nanoTime() - t0) / 1_000_000L
            if (full) {
                // KPM holds its limit of images: build no more, for any page.
                sources.forEach { it.next = it.plans.size }
                return
            }
        }
    }

    /**
     * The previews' best match for this frame, or null (none, or not ready): among [images] only when
     * given (see [nearest], [searchable]), else every page and preview held.
     */
    fun match(luma: ByteBuffer, timestampNs: Long, maxCorrespondences: Int, images: List<PlanarImage>? = null): KpmMatcherBackend.Solve? {
        if (!isReady) return null
        if (images != null) return backend.matchImages(luma, timestampNs, images, maxCorrespondences)
        return backend.match(luma, timestampNs, PlanarMatchRequest.ALL_PAGES, maxCorrespondences)
    }

    companion object {
        /** KPM refused: the session holds its limit of reference images. */
        private const val ERROR_FULL = -8

        /** Previews built and indexed together; [resolution] is read again between batches. */
        const val BATCH = 4

        /** Degrees of view angle that weigh as much as a doubling of size in [nearest]. */
        const val DEG_PER_OCTAVE = 30.0

        /** How many of [count] plans [resolution] keeps. */
        fun share(resolution: Float, count: Int): Int =
            kotlin.math.ceil(resolution.toDouble() * count - 1e-6).toInt().coerceIn(0, count)

        /**
         * How far apart two views are: the angle between their rotations, plus [DEG_PER_OCTAVE] per
         * doubling of size between [scaleA] and [scaleB] (none when [scaleB] is null or unusable).
         */
        fun viewCost(rotationA: DoubleArray, scaleA: Double, rotationB: DoubleArray, scaleB: Double?): Double {
            var c = ReferencePreviews.viewAngleDeg(rotationA, rotationB)
            if (scaleB != null && scaleB > 0.0 && scaleB.isFinite()) {
                c += DEG_PER_OCTAVE * kotlin.math.abs(kotlin.math.ln(scaleA / scaleB) / kotlin.math.ln(2.0))
            }
            return c
        }

        /** How much a turn about the optical axis counts in [priorityCost]: KPM's features turn with it. */
        const val TURN_WEIGHT = 0.25

        /**
         * How far apart two views are for KPM, which decides the order previews are built in: the angle
         * between their optical axes (tilt and side, what KPM's features do not survive), plus
         * [TURN_WEIGHT] of the full angle between them (the turn, which they largely do), plus
         * [DEG_PER_OCTAVE] per doubling of size.
         */
        fun priorityCost(rotationA: DoubleArray, scaleA: Double, rotationB: DoubleArray, scaleB: Double): Double {
            var dot = 0.0
            for (k in 0..2) dot += rotationA[6 + k] * rotationB[6 + k]
            val axis = Math.toDegrees(kotlin.math.acos(dot.coerceIn(-1.0, 1.0)))
            return axis + TURN_WEIGHT * ReferencePreviews.viewAngleDeg(rotationA, rotationB) +
                DEG_PER_OCTAVE * kotlin.math.abs(kotlin.math.ln(scaleA / scaleB) / kotlin.math.ln(2.0))
        }

        /**
         * [views] nearest first to the view the sensors predict. [predicted] gives, for a page (and its
         * reference's width), the camera's rotation from that page's frame (as [View.cameraFromPage])
         * and the [View.scale] that would look like the frame at its predicted distance (null when
         * unknown), or null when there is no prediction for that page (its previews go last). Assuming the target is in
         * view, the camera's rotation alone fixes how it sees the page — the tilt, which side, and the
         * turn — so the distance is the angle between the two views, plus [DEG_PER_OCTAVE] per doubling
         * of size off the predicted scale. Ties go to [preferredPage].
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
    }
}

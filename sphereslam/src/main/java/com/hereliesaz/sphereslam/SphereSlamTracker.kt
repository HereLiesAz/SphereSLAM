package com.hereliesaz.sphereslam

import com.hereliesaz.sphereslam.nativebridge.KpmBridge
import java.nio.ByteOrder
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * KPM-backed wall tracker that runs beside ARCore.
 *
 * This class never produces the renderer's primary view/projection matrices. ARCore keeps that job.
 * SphereSLAM consumes copies of the same camera luminance frames on a private worker and publishes
 * wall-relative observations for relocalization and drift correction.
 *
 * The tracker holds at most one reference page: [setReference] replaces the native atlas and
 * [clearReference] empties it, so a previously registered page can never produce an observation
 * afterwards. Native KPM is reached only through a [SphereSlamEngine] (the same engine
 * [SphereSlamStandaloneSession] uses), owned and used solely on the tracker's worker thread. When the native engine is unavailable (see [SphereSlam.isAvailable]; e.g. on ABIs
 * other than arm64-v8a/armeabi-v7a) every call is a safe no-op and [isNativeAvailable] is false.
 * Calls racing or following [close] are ignored.
 */
@OptIn(ExperimentalSphereSlamApi::class)
class SphereSlamTracker internal constructor(
    private val engines: Engines,
    private val worker: ExecutorService,
) : AutoCloseable {

    /** Public tracker construction owns its private worker and the native KPM engine. */
    constructor() : this(Engines.native(), newWorker())

    /** Test seam; intentionally not part of the consumer API. */
    internal constructor(engines: Engines) : this(engines, newWorker())

    /**
     * Where the tracker gets its engine: availability of the native layer, and a calibrated
     * [SphereSlamEngine] for a camera (null when one cannot be created).
     */
    internal class Engines(
        val available: () -> Boolean,
        val create: (CameraModel) -> SphereSlamEngine?,
    ) {
        companion object {
            /** KPM engines over [api] (tests pass a fake). */
            fun kpm(api: KpmApi, available: () -> Boolean): Engines = Engines(available) { camera ->
                KpmSphereSlamEngine(camera.width, camera.height, camera.calibration(), api)
                    .takeIf { it.isReady }
            }

            // KpmBridge.isAvailable() never throws; runCatching also covers a linkage error raised by
            // a KpmBridge class that failed to initialize, so configure() degrades instead of crashing.
            fun native(): Engines = kpm(NativeKpmApi) { runCatching { KpmBridge.isAvailable() }.getOrDefault(false) }
        }
    }

    data class CameraModel(
        val width: Int,
        val height: Int,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
    ) {
        init {
            require(width > 0 && height > 0)
            require(fx.isFinite() && fy.isFinite() && fx > 0f && fy > 0f)
            require(cx.isFinite() && cy.isFinite())
        }

        internal fun calibration() = SphereSlamCalibration(fx, fy, cx, cy)
    }

    /**
     * One sidecar KPM observation.
     *
     * [cameraFromPage3x4] is the row-major artoolkitX camera-from-page transform and is copied on
     * input/output. Its translation is in KPM's page units (millimetres when reference DPI is
     * physically calibrated); it is not an ARCore/OpenGL view matrix.
     */
    class Observation internal constructor(
        val timestampNs: Long,
        val pageNo: Int,
        val error: Float,
        val inliers: Int,
        cameraFromPage3x4: FloatArray,
        /** Matched against one of the host's [AnticipatedView]s, not the reference itself. */
        val anticipated: Boolean = false,
    ) {
        private val cameraFromPage3x4Value = cameraFromPage3x4.copyOf()

        init {
            require(cameraFromPage3x4Value.size == 12)
        }

        val cameraFromPage3x4: FloatArray
            get() = cameraFromPage3x4Value.copyOf()

        override fun toString(): String =
            "Observation(timestampNs=$timestampNs, pageNo=$pageNo, error=$error, inliers=$inliers, anticipated=$anticipated)"

        companion object {
            /**
             * Build an observation outside the tracker — for host unit tests of code that consumes
             * observations (fusion policy, gating) and for replaying recorded KPM results. Production
             * observations come from [latestObservation]; the tracker never reads one built here.
             *
             * @param cameraFromPage3x4 row-major 3×4 camera-from-page, KPM page units (copied).
             * @throws IllegalArgumentException when [cameraFromPage3x4] is not length 12.
             */
            fun forTesting(
                timestampNs: Long,
                pageNo: Int = 0,
                error: Float,
                inliers: Int,
                cameraFromPage3x4: FloatArray,
            ): Observation = Observation(timestampNs, pageNo, error, inliers, cameraFromPage3x4)
        }
    }

    private class PendingFrame(val luma: ByteBuffer, val timestampNs: Long)

    private val closed = AtomicBoolean(false)
    private val latest = AtomicReference<Observation?>(null)
    private val pendingFrame = AtomicReference<PendingFrame?>(null)

    // A packed-frame array the worker has finished with (or a superseded pending frame's), reused by
    // the next submitFrame: steady state allocates no frame-sized array per frame.
    // Frames are packed straight into direct buffers (what KPM reads): one copy per frame, not two.
    private val spareLuma = AtomicReference<ByteBuffer?>(null)
    // Worker thread: the frame as bytes for the constellation fallback, filled only on a KPM miss.
    private var missBytes = ByteArray(0)
    private val marksWorkspace = Constellation.Workspace()
    private val drainScheduled = AtomicBoolean(false)

    @Volatile private var cameraModel: CameraModel? = null
    @Volatile private var engine: SphereSlamEngine? = null

    // Worker-thread only: the direct buffer KPM reads frames and references from, grown when needed.
    private var direct: ByteBuffer? = null

    // Worker-thread only: a second engine holding the reference and the host's anticipated views,
    // searched only after direct misses (KPM checks every image on each query, so views in the live
    // engine would slow every match). Rebuilt with each reference; the views are the host's.
    private var previewEngine: SphereSlamEngine? = null
    private var previewReference: PreviewReference? = null
    // The accepted reference, kept to start the previews engine when the first views arrive.
    private var previewSource: PreviewSource? = null

    private class PreviewSource(val camera: CameraModel, val luma: ByteArray, val width: Int, val height: Int, val page: PlanarPage)
    private var misses = 0
    // The reference as a handful of isolated marks (Constellation): centres in reference pixels
    // (y down) and page millimetres (y up); null when it is not one.
    private var referenceMarkPixels: List<FloatArray>? = null
    private var referenceMarkMillimetres: List<FloatArray>? = null
    private var referenceMarkPage = 0
    private var previewTurn = 0

    private class PreviewReference(val pageNo: Int, val heightPx: Int, val dpi: Float, val maxFeatures: Int) {
        val imageOf = HashMap<String, Int>()
        var nextImage = 1
        var full = false
    }

    @Volatile private var searchableIds: Set<String>? = null

    /**
     * Direct misses in a row before every other frame is matched against the host's anticipated
     * views ([addAnticipatedViews]) instead; 0 never searches them.
     */
    @Volatile var previewSearchAfterMisses: Int = 2
        set(value) {
            require(value >= 0)
            field = value
        }

    /**
     * When KPM misses and the reference is a handful of isolated marks ([Constellation]), match the
     * arrangement of the marks instead of their look. Read when a reference is set.
     */
    @Volatile var constellationFallback: Boolean = true
    /** Why the last [setReference] was not accepted (native code or cause), or null after a success. */
    @Volatile var lastReferenceError: String? = null
        private set

    /** The last exception a tracker task threw (caught so the host process survives), or null. */
    @Volatile var lastWorkerError: Throwable? = null
        private set

    @Volatile private var referenceReady: Boolean = false
    @Volatile private var referenceGeometry: SphereSlamPoseMath.PageGeometry? = null

    val isNativeAvailable: Boolean get() = engines.available()
    val isReferenceReady: Boolean get() = referenceReady
    fun latestObservation(): Observation? = latest.get()
    fun currentReferenceGeometry(): SphereSlamPoseMath.PageGeometry? = referenceGeometry

    fun configure(camera: CameraModel) {
        if (closed.get()) return
        if (camera == cameraModel && engine != null) return
        submit {
            if (closed.get()) return@submit
            if (camera == cameraModel && engine != null) return@submit
            startEngine(camera)
        }
    }

    /** Start a fresh wall atlas with this calibrated camera. */
    fun reset(camera: CameraModel) {
        if (closed.get()) return
        submit {
            if (closed.get()) return@submit
            startEngine(camera)
        }
    }

    /**
     * Register [luma] as the tracker's only reference page, replacing any earlier one in the native
     * atlas. Runs asynchronously on the tracker worker; [isReferenceReady] flips once it is accepted.
     */
    fun setReference(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        dpi: Float = 72f,
        pageNo: Int = 0,
        imageNo: Int = 0,
        maxFeatures: Int = 500,
    ) {
        if (closed.get()) return
        require(dpi > 0f)
        require(maxFeatures > 0)
        val packed = packLuma(luma, width, height, rowStride)
        submit {
            if (closed.get()) return@submit
            val active = engine
            val model = cameraModel
            if (active == null || model == null) {
                lastReferenceError = "no engine: configure() first, or KPM is unavailable"
                referenceReady = false
                referenceGeometry = null
                return@submit
            }
            // Replace, never append: drop any earlier page from the native atlas first.
            referenceReady = false
            val accepted = if (!active.clearPages()) {
                lastReferenceError = "clearing the previous reference failed"
                false
            } else {
                runCatching {
                    active.addPage(directCopy(packed), width, height, PlanarPage(pageNo, imageNo, dpi, maxFeatures)) > 0
                }.fold(
                    onSuccess = { ok -> lastReferenceError = if (ok) null else "the reference yielded no features"; ok },
                    onFailure = { e -> lastReferenceError = e.message ?: e.toString(); false },
                )
            }
            referenceReady = accepted
            val marks = if (accepted && constellationFallback) Constellation.extractMarks(packed, width, height) else emptyList()
            val mmPerPx = 25.4f / dpi
            referenceMarkPage = pageNo
            referenceMarkPixels = marks.takeIf { it.isNotEmpty() }?.map { floatArrayOf(it.x, it.y) }
            // KPM's own convention (kpmRefDataSet.cpp): pixel centres, image y flipped.
            referenceMarkMillimetres = marks.takeIf { it.isNotEmpty() }?.map { floatArrayOf((it.x + 0.5f) * mmPerPx, (height - 0.5f - it.y) * mmPerPx) }
            referenceGeometry = if (accepted) {
                SphereSlamPoseMath.pageGeometry(width, height, dpi)
            } else {
                null
            }
            latest.set(null)
            // Views belong to the reference they were rendered from: a new reference drops them.
            closePreviews()
            previewSource = if (accepted) PreviewSource(model, packed, width, height, PlanarPage(pageNo, imageNo, dpi, maxFeatures)) else null
        }
    }

    /**
     * Adds views of the reference that the host anticipates the camera may see ([AnticipatedView]:
     * the reference rendered from an angle, nearer, turned). They are learned in a second engine,
     * [PREVIEW_BATCH] at a time between frames on the tracker worker (no index rebuild: preview
     * matching builds its own matcher from the learned images), and searched only after [previewSearchAfterMisses] direct misses in a row;
     * a hit is published with [Observation.anticipated]. Which views, how many and when are the
     * host's; views whose id is already held are skipped, and a new [setReference] drops them all
     * (add the new reference's after it). Runs on the tracker worker.
     */
    fun addAnticipatedViews(views: List<AnticipatedView>) {
        if (closed.get() || views.isEmpty()) return
        val copy = views.toList()
        submit {
            if (closed.get()) return@submit
            buildPreviews(copy)
        }
    }

    /**
     * Which anticipated views may be searched: their ids, or null for every view. Applies from the
     * next search; views outside it stay learned, so widening it again costs nothing.
     */
    fun setSearchableAnticipatedViews(ids: Set<String>?) {
        searchableIds = ids?.toSet()
    }

    /** Worker thread: the previews engine, holding the reference, started on first use. */
    private fun startPreviews(): SphereSlamEngine? {
        previewEngine?.let { return it }
        val src = previewSource ?: return null
        val pe = engines.create(src.camera) ?: return null
        val page = src.page
        val ok = runCatching { pe.addPage(directCopy(src.luma), src.width, src.height, page) > 0 }.getOrDefault(false)
        if (!ok) {
            pe.close()
            return null
        }
        previewEngine = pe
        previewReference = PreviewReference(page.pageNo, src.height, page.referenceDpi, page.maxFeatures)
        return pe
    }

    /** Worker thread: learns [views] in the previews engine, [PREVIEW_BATCH] at a time, each batch indexed once. */
    private fun buildPreviews(views: List<AnticipatedView>) {
        val pe = startPreviews() ?: return
        val ref = previewReference ?: return
        val seen = HashSet(ref.imageOf.keys)
        for (batch in views.filter { seen.add(it.id) }.chunked(PREVIEW_BATCH)) {
            if (ref.full || closed.get()) return
            var added = 0
            for (v in batch) {
                val imageNo = ref.nextImage++
                val n = runCatching {
                    pe.addPageView(
                        directCopy(v.luma), v.width, v.height,
                        PlanarPageView(ref.pageNo, imageNo, ref.maxFeatures, v.pageFromView, ref.heightPx, ref.dpi, reindex = false),
                    )
                }.getOrDefault(-1)
                if (n == KPM_IMAGES_FULL) {
                    ref.full = true
                    break
                }
                if (n > 0) {
                    ref.imageOf[v.id] = imageNo
                    added++
                }
            }
        }
    }

    /**
     * Worker thread: the constellation fallback ([constellationFallback]) for a frame KPM missed —
     * the frame's marks matched by arrangement to a reference of a handful of isolated marks, posed
     * from their centres. Null when the reference is not one or nothing fits within
     * [CONSTELLATION_MAX_ERROR_PX2].
     */
    private fun matchConstellation(frame: ByteBuffer): PlanarMatch? {
        val refPx = referenceMarkPixels ?: return null
        val refMm = referenceMarkMillimetres ?: return null
        val model = cameraModel ?: return null
        val size = model.width * model.height
        if (frame.capacity() < size) return null
        if (missBytes.size != size) missBytes = ByteArray(size)
        frame.duplicate().apply { clear() }.get(missBytes, 0, size)
        val marks = Constellation.extractMarks(missBytes, model.width, model.height, marksWorkspace)
        if (marks.isEmpty()) return null
        val frame = marks.map { floatArrayOf(it.x, it.y) }
        val pairing = Constellation.match(refPx, frame) ?: return null
        // Four points fit a homography exactly: a fifth is what makes the error a test.
        if (pairing.reference.size < Constellation.MIN_POSE_POINTS) return null
        val page = pairing.reference.map { refMm[it] }
        val image = pairing.frame.map { frame[it] }
        val fit = Constellation.pose(page, image, floatArrayOf(model.fx, model.fy, model.cx, model.cy)) ?: return null
        if (fit.meanSquaredErrorPx > CONSTELLATION_MAX_ERROR_PX2) return null
        return PlanarMatch(referenceMarkPage, fit.cameraFromPage3x4, fit.meanSquaredErrorPx, page.size)
    }

    /** Worker thread: the host's searchable views' best match for [luma], or null. */
    private fun matchPreviews(luma: ByteBuffer): PlanarMatch? {
        val pe = previewEngine ?: return null
        val ref = previewReference ?: return null
        if (ref.imageOf.isEmpty()) return null
        val ids = searchableIds
        val images = (if (ids == null) ref.imageOf.values else ids.mapNotNull { ref.imageOf[it] })
            .map { PlanarImage(ref.pageNo, it) }
        if (images.isEmpty()) return null
        return runCatching { pe.match(luma, PlanarMatchRequest(images = images)) }.getOrNull()
    }

    private fun closePreviews() {
        previewEngine?.close()
        previewEngine = null
        previewReference = null
        misses = 0
        previewTurn = 0
    }

    /**
     * Stop publishing/matching the current page and remove it from the native atlas, keeping the
     * calibrated native handle. A later [setReference] starts from an empty atlas.
     */
    fun clearReference() {
        referenceReady = false
        referenceGeometry = null
        pendingFrame.set(null)
        latest.set(null)
        if (closed.get()) return
        submit {
            if (closed.get()) return@submit
            engine?.clearPages()
            referenceReady = false
            referenceGeometry = null
            latest.set(null)
            closePreviews()
            previewSource = null
        }
    }

    /**
     * Copy and submit one live frame without blocking on KPM. Only the newest unprocessed frame is
     * retained, so a slow matcher cannot build latency behind ARCore.
     */
    fun submitFrame(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        timestampNs: Long,
    ) {
        if (closed.get() || !referenceReady) return
        val model = cameraModel ?: return
        if (model.width != width || model.height != height) return
        val size = width * height
        val buffer = spareLuma.getAndSet(null)?.takeIf { it.capacity() == size }
            ?: ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        packLumaInto(luma, width, height, rowStride, buffer)
        // A superseded frame was never handed to the worker, so its array is free again.
        pendingFrame.getAndSet(PendingFrame(buffer, timestampNs))?.let { spareLuma.set(it.luma) }
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        val scheduled = submit {
            try {
                while (!closed.get()) {
                    val frame = pendingFrame.getAndSet(null) ?: break
                    val active = engine
                    if (active != null && referenceReady) {
                        // Lost (that many direct misses in a row): every other frame searches the host's
                        // anticipated views instead of the reference.
                        val after = previewSearchAfterMisses
                        val viaViews = after > 0 && misses >= after && previewEngine != null && previewTurn++ % 2 == 0
                        // A views frame searches the views only; the constellation backs up direct matches.
                        val m = if (viaViews) {
                            matchPreviews(frame.luma.duplicate())
                        } else {
                            active.match(frame.luma.duplicate()) ?: matchConstellation(frame.luma)
                        }
                        if (m != null) {
                            misses = 0
                            previewTurn = 0
                            latest.set(Observation(frame.timestampNs, m.pageNo, m.reprojectionError, m.inlierCount, m.cameraFromPage3x4, anticipated = viaViews))
                        } else {
                            misses++
                        }
                    }
                    spareLuma.set(frame.luma)
                }
            } finally {
                drainScheduled.set(false)
                if (!closed.get() && pendingFrame.get() != null) scheduleDrain()
            }
        }
        if (!scheduled) drainScheduled.set(false)
    }

    /**
     * Queue [task] on the worker. Returns false when the worker already shut down (a call racing
     * [close]); that is treated exactly like calling after close: ignored.
     */
    private fun submit(task: () -> Unit): Boolean = try {
        // An exception escaping a worker task would kill the host process: record it instead.
        worker.execute {
            try {
                task()
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                lastWorkerError = t
                runCatching { android.util.Log.w(TAG, "tracker task failed", t) }
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingFrame.set(null)
        submit {
            destroyHandle()
            latest.set(null)
        }
        worker.shutdown()
    }

    /** Worker thread: replace the engine with a fresh one calibrated for [camera] (none if unavailable). */
    private fun startEngine(camera: CameraModel) {
        destroyHandle()
        cameraModel = camera
        referenceReady = false
        referenceGeometry = null
        latest.set(null)
        engine = if (engines.available()) engines.create(camera) else null
    }

    private fun destroyHandle() {
        closePreviews()
        previewSource = null
        referenceMarkPixels = null
        referenceMarkMillimetres = null
        val active = engine
        engine = null
        referenceReady = false
        referenceGeometry = null
        active?.close()
    }

    /** Worker thread: [luma] in the reused direct buffer KPM requires. */
    private fun directCopy(luma: ByteArray): ByteBuffer {
        // Reused while it fits, but not kept at a size far beyond what is copied now (a large
        // reference or view must not pin its native memory for the tracker's life).
        val buffer = direct?.takeIf { it.capacity() >= luma.size && it.capacity() <= 4 * luma.size }
            ?: ByteBuffer.allocateDirect(luma.size).order(ByteOrder.nativeOrder()).also { direct = it }
        buffer.clear()
        buffer.put(luma)
        buffer.flip()
        return buffer
    }

    companion object {
        private const val TAG = "SphereSlamTracker"

        /** Views learned and indexed together. */
        private const val PREVIEW_BATCH = 4

        /** Largest mean squared reprojection error of a constellation pose, px² (KPM's gate). */
        private const val CONSTELLATION_MAX_ERROR_PX2 = 10f

        /** KPM refused a view: the engine holds its limit of reference images. */
        private const val KPM_IMAGES_FULL = -8

        private fun newWorker(): ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "sphereslam-kpm").apply { isDaemon = true }
            }

        /** [packLuma] into the direct buffer [out] (capacity exactly `width * height`), flipped for reading. */
        internal fun packLumaInto(source: ByteBuffer, width: Int, height: Int, rowStride: Int, out: ByteBuffer): ByteBuffer {
            require(width > 0 && height > 0)
            require(rowStride >= width)
            require(out.capacity() == width * height)
            val base = source.position()
            require(base + (height - 1) * rowStride + width <= source.limit())
            val duplicate = source.duplicate()
            out.clear()
            for (row in 0 until height) {
                duplicate.limit(base + row * rowStride + width)
                duplicate.position(base + row * rowStride)
                out.put(duplicate)
            }
            out.flip()
            return out
        }

        internal fun packLuma(source: ByteBuffer, width: Int, height: Int, rowStride: Int): ByteArray =
            packLumaInto(source, width, height, rowStride, ByteArray(width * height))

        /** [packLuma] into [out] (exactly `width * height` bytes); returns [out]. */
        internal fun packLumaInto(source: ByteBuffer, width: Int, height: Int, rowStride: Int, out: ByteArray): ByteArray {
            require(width > 0 && height > 0)
            require(rowStride >= width)
            require(out.size == width * height)
            val base = source.position()
            val requiredLastIndex = base + (height - 1) * rowStride + width
            require(requiredLastIndex <= source.limit())
            val duplicate = source.duplicate()
            var dst = 0
            for (row in 0 until height) {
                duplicate.position(base + row * rowStride)
                duplicate.get(out, dst, width)
                dst += width
            }
            return out
        }
    }
}

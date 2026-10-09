package com.hereliesaz.sphereslam

import com.hereliesaz.sphereslam.nativebridge.KpmBridge
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
 * afterwards. When the native engine is unavailable (see [SphereSlam.isAvailable]; e.g. on ABIs
 * other than arm64-v8a/armeabi-v7a) every call is a safe no-op and [isNativeAvailable] is false.
 * Calls racing or following [close] are ignored.
 */
class SphereSlamTracker internal constructor(
    private val native: Native,
    private val worker: ExecutorService,
) : AutoCloseable {

    /** Public tracker construction owns its private worker and native implementation. */
    constructor() : this(KpmNative, newWorker())

    /** Test seam; intentionally not part of the consumer API. */
    internal constructor(native: Native) : this(native, newWorker())

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
            require(fx > 0f && fy > 0f)
        }
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
    ) {
        private val cameraFromPage3x4Value = cameraFromPage3x4.copyOf()

        init {
            require(cameraFromPage3x4Value.size == 12)
        }

        val cameraFromPage3x4: FloatArray
            get() = cameraFromPage3x4Value.copyOf()

        @Deprecated("Use cameraFromPage3x4", ReplaceWith("cameraFromPage3x4"))
        val pageToCamera3x4: FloatArray
            get() = cameraFromPage3x4
    }

    internal interface Native {
        val available: Boolean
        fun create(camera: CameraModel): Long
        fun destroy(handle: Long)
        fun setReference(
            handle: Long,
            luma: ByteArray,
            width: Int,
            height: Int,
            dpi: Float,
            pageNo: Int,
            imageNo: Int,
            maxFeatures: Int,
        ): Boolean
        fun match(handle: Long, luma: ByteArray, timestampNs: Long): Observation?

        /** Remove every page from the handle's atlas. */
        fun clear(handle: Long): Boolean
    }

    private data class PendingFrame(val luma: ByteArray, val timestampNs: Long)

    private val closed = AtomicBoolean(false)
    private val latest = AtomicReference<Observation?>(null)
    private val pendingFrame = AtomicReference<PendingFrame?>(null)
    private val drainScheduled = AtomicBoolean(false)

    @Volatile private var cameraModel: CameraModel? = null
    @Volatile private var nativeHandle: Long = 0L
    @Volatile private var referenceReady: Boolean = false
    @Volatile private var referenceGeometry: SphereSlamPoseMath.PageGeometry? = null

    val isNativeAvailable: Boolean get() = native.available
    val isReferenceReady: Boolean get() = referenceReady
    fun latestObservation(): Observation? = latest.get()
    fun currentReferenceGeometry(): SphereSlamPoseMath.PageGeometry? = referenceGeometry

    fun configure(camera: CameraModel) {
        if (closed.get()) return
        if (camera == cameraModel && nativeHandle != 0L) return
        submit {
            if (closed.get()) return@submit
            if (camera == cameraModel && nativeHandle != 0L) return@submit
            destroyHandle()
            cameraModel = camera
            referenceReady = false
            referenceGeometry = null
            latest.set(null)
            nativeHandle = if (native.available) native.create(camera) else 0L
        }
    }

    /** Start a fresh wall atlas with this calibrated camera. */
    fun reset(camera: CameraModel) {
        if (closed.get()) return
        submit {
            if (closed.get()) return@submit
            destroyHandle()
            cameraModel = camera
            referenceReady = false
            referenceGeometry = null
            latest.set(null)
            nativeHandle = if (native.available) native.create(camera) else 0L
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
            val handle = nativeHandle
            val model = cameraModel
            if (handle == 0L || model == null) {
                referenceReady = false
                referenceGeometry = null
                return@submit
            }
            // Replace, never append: drop any earlier page from the native atlas first.
            referenceReady = false
            val accepted = native.clear(handle) && native.setReference(
                handle, packed, width, height, dpi, pageNo, imageNo, maxFeatures
            )
            referenceReady = accepted
            referenceGeometry = if (accepted) {
                SphereSlamPoseMath.pageGeometry(width, height, dpi)
            } else {
                null
            }
            latest.set(null)
        }
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
            val handle = nativeHandle
            if (handle != 0L) native.clear(handle)
            referenceReady = false
            referenceGeometry = null
            latest.set(null)
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
        pendingFrame.set(PendingFrame(packLuma(luma, width, height, rowStride), timestampNs))
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        val scheduled = submit {
            try {
                while (!closed.get()) {
                    val frame = pendingFrame.getAndSet(null) ?: break
                    val handle = nativeHandle
                    if (handle != 0L && referenceReady) {
                        native.match(handle, frame.luma, frame.timestampNs)?.let(latest::set)
                    }
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
        worker.execute(task)
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

    private fun destroyHandle() {
        val handle = nativeHandle
        nativeHandle = 0L
        referenceReady = false
        referenceGeometry = null
        if (handle != 0L) native.destroy(handle)
    }

    companion object {
        private fun newWorker(): ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "sphereslam-kpm").apply { isDaemon = true }
            }

        internal fun packLuma(source: ByteBuffer, width: Int, height: Int, rowStride: Int): ByteArray {
            require(width > 0 && height > 0)
            require(rowStride >= width)
            val base = source.position()
            val requiredLastIndex = base + (height - 1) * rowStride + width
            require(requiredLastIndex <= source.limit())
            val out = ByteArray(width * height)
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

    private object KpmNative : Native {
        // KpmBridge.isAvailable() never throws; runCatching also covers a linkage error raised by a
        // KpmBridge class that failed to initialize, so configure() degrades instead of crashing.
        override val available: Boolean
            get() = runCatching { KpmBridge.isAvailable() }.getOrDefault(false)

        override fun create(camera: CameraModel): Long = KpmBridge.createCalibratedSession(
            camera.width, camera.height, camera.fx, camera.fy, camera.cx, camera.cy
        )

        override fun destroy(handle: Long) = KpmBridge.destroySession(handle)

        override fun setReference(
            handle: Long,
            luma: ByteArray,
            width: Int,
            height: Int,
            dpi: Float,
            pageNo: Int,
            imageNo: Int,
            maxFeatures: Int,
        ): Boolean {
            val direct = ByteBuffer.allocateDirect(luma.size)
            direct.put(luma).rewind()
            return KpmBridge.addPlanarPage(
                handle,
                direct,
                width,
                height,
                dpi,
                pageNo,
                imageNo,
                maxFeatures,
            ) > 0
        }

        override fun clear(handle: Long): Boolean = KpmBridge.clearPages(handle)

        override fun match(handle: Long, luma: ByteArray, timestampNs: Long): Observation? {
            val direct = ByteBuffer.allocateDirect(luma.size)
            direct.put(luma).rewind()
            val out = FloatArray(KpmBridge.MATCH_OUTPUT_FLOATS)
            val pageNo = KpmBridge.matchPlanar(handle, direct, out)
            if (pageNo < 0) return null
            return Observation(
                timestampNs = timestampNs,
                pageNo = pageNo,
                error = out[12],
                inliers = out[13].toInt(),
                cameraFromPage3x4 = out.copyOfRange(0, 12),
            )
        }
    }
}

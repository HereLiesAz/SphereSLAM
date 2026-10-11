package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.math.RigidMath
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Builds planar matchers for [PhotosphereTrackingSession] off the camera-analysis thread and hands
 * them back for an atomic swap.
 *
 * The analyzer thread owns the live matcher and is the only thread that ever calls `match` on it.
 * Jobs run on a **serial** executor; a job creates (or [PlanarFrontEnd.fork]s) its own candidate
 * matcher, which no other thread can see until the analyzer [poll]s the finished [Outcome] and swaps
 * it in. Every matcher the analyzer stops using is [retire]d onto the same serial executor, so it is
 * closed only after any job that was forking it has finished — one native handle is never used by two
 * threads at once except the per-session-locked clone copy, and never after it is closed.
 *
 * State machine (all transitions under one small lock, never held while a job runs):
 * - [State.IDLE] — nothing in flight, nothing ready. [submitGrowth] is accepted only here.
 * - [State.BUILDING] / [State.GROWING] — a current-generation job is in flight.
 * - [State.READY] — its [Outcome] waits for [poll].
 *
 * [invalidate] bumps the generation: a ready outcome is discarded (its matcher retired) and an
 * in-flight job's outcome is discarded when it completes, so a candidate built for a matcher that has
 * since been dropped, rebuilt or closed can never be swapped in. [submitBuild] always invalidates
 * first, so a full build supersedes anything older.
 */
@ExperimentalSphereSlamRelocApi
internal class AtlasMatcherWorker(executor: Executor?) : AutoCloseable {

    enum class State { IDLE, BUILDING, GROWING, READY }

    /** What a finished job hands back. Owns its matcher until [poll] returns it. */
    sealed class Outcome {
        internal open fun release() {}

        /** A complete matcher (page 0 plus every kept page) for [intrinsics]. */
        class Built(
            val frontEnd: PlanarFrontEnd,
            val intrinsics: CameraIntrinsics,
            val root: PhotosphereTrackingSession.RegisteredPage,
            val diagnostics: List<String>,
        ) : Outcome() {
            override fun release() = frontEnd.close()
        }

        /** A full build failed (e.g. [ReferenceTooWeakException]); terminal for the session. */
        class BuildFailed(val error: Throwable) : Outcome()

        /** A grown matcher: the live one's pages plus [page]. Valid only if the kept pages still match. */
        class Grown(
            val frontEnd: PlanarFrontEnd,
            val page: PhotosphereTrackingSession.AtlasPage,
            val keptPageNumbers: List<Int>,
            val attitude: AttitudeSample?,
            val diagnostic: String,
        ) : Outcome() {
            override fun release() = frontEnd.close()
        }

        /** A growth attempt that produced nothing; the live matcher is untouched. */
        class GrowthSkipped(val diagnostic: String) : Outcome()
    }

    private val ownedExecutor: ExecutorService? = if (executor == null) newGrowthExecutor() else null
    private val executor: Executor = executor ?: ownedExecutor!!

    private val lock = Any()
    private var generation = 0L
    private var running: State? = null
    private var ready: Outcome? = null
    private var closed = false

    /** Current state (see the class doc). */
    val state: State
        get() = synchronized(lock) {
            when {
                ready != null -> State.READY
                running != null -> running!!
                else -> State.IDLE
            }
        }

    /** Whether a growth job may be submitted now. */
    val isIdle: Boolean get() = state == State.IDLE

    /** Whether a current-generation full build is in flight or waiting to be polled. */
    val isBuilding: Boolean
        get() = synchronized(lock) { running == State.BUILDING || ready is Outcome.Built || ready is Outcome.BuildFailed }

    /** Discard any ready or in-flight outcome. */
    fun invalidate() {
        val stale = synchronized(lock) {
            generation++
            running = null
            ready.also { ready = null }
        }
        stale?.let(::releaseOnWorker)
    }

    /** Invalidate, then build a complete matcher with [job]. False when closed. */
    fun submitBuild(job: () -> Outcome): Boolean {
        invalidate()
        return submit(State.BUILDING, job) { Outcome.BuildFailed(it) }
    }

    /** Run a growth [job] if [isIdle]; false otherwise (or when closed). */
    fun submitGrowth(job: () -> Outcome): Boolean =
        submit(State.GROWING, job) { Outcome.GrowthSkipped("SphereSLAM atlas growth failed: ${it.javaClass.simpleName}: ${it.message}") }

    /** The finished current-generation outcome, once; the caller then owns its matcher. */
    fun poll(): Outcome? = synchronized(lock) { ready.also { ready = null } }

    /** Close [frontEnd] on the worker, after any job already queued (e.g. one forking it). */
    fun retire(frontEnd: PlanarFrontEnd) {
        try {
            executor.execute { closeQuietly(frontEnd) }
        } catch (_: RejectedExecutionException) {
            closeQuietly(frontEnd)
        }
    }

    /** Invalidate, refuse further jobs and stop the owned executor once queued work has run. */
    override fun close() {
        invalidate()
        synchronized(lock) { closed = true }
        ownedExecutor?.shutdown()
    }

    private fun submit(kind: State, job: () -> Outcome, onError: (Throwable) -> Outcome): Boolean {
        val jobGeneration = synchronized(lock) {
            if (closed) return false
            if (kind == State.GROWING && (running != null || ready != null)) return false
            running = kind
            generation
        }
        val task = Runnable {
            val outcome = try {
                job()
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                onError(t)
            }
            val stale = synchronized(lock) {
                if (jobGeneration == generation && !closed) {
                    running = null
                    ready = outcome
                    false
                } else {
                    true
                }
            }
            if (stale) closeQuietly(outcome)
        }
        return try {
            executor.execute(task)
            true
        } catch (e: RejectedExecutionException) {
            synchronized(lock) { if (jobGeneration == generation) running = null }
            false
        }
    }

    private fun releaseOnWorker(outcome: Outcome) {
        try {
            executor.execute { closeQuietly(outcome) }
        } catch (_: RejectedExecutionException) {
            closeQuietly(outcome)
        }
    }

    companion object {
        private fun closeQuietly(frontEnd: PlanarFrontEnd) {
            try {
                frontEnd.close()
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
            }
        }

        private fun closeQuietly(outcome: Outcome) {
            try {
                outcome.release()
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
            }
        }

        /** One daemon thread, just below normal priority so it yields to the camera analyzer. */
        fun newGrowthExecutor(): ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "SphereSLAM-atlas").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }

        private fun direct(bytes: ByteArray): ByteBuffer =
            ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                flip()
            }

        private fun PlanarFrontEnd.addAtlasPage(page: PhotosphereTrackingSession.AtlasPage) = addPage(
            luma = direct(page.lumaRef()),
            width = page.width,
            height = page.height,
            referenceWidthUnits = page.referenceWidthUnits,
            physicallyMetric = page.physicallyMetric,
            pageNo = page.pageNo,
            canonicalFromPage = page.canonicalFromPageRef(),
        )

        private fun PlanarFrontEnd.addRoot(reference: PhotosphereTrackingSession.ReferenceImage) = addPage(
            luma = direct(reference.lumaRef()),
            width = reference.width,
            height = reference.height,
            referenceWidthUnits = reference.referenceWidthUnits,
            physicallyMetric = reference.physicallyMetric,
            pageNo = 0,
            canonicalFromPage = RigidMath.identity(),
        )

        /**
         * Full build job: a fresh matcher for [intrinsics] with page 0 ([reference], which must reach
         * [minKpmFeatures] or the build fails with [ReferenceTooWeakException]) and every page of
         * [pages]. Runs on the worker; the matcher is closed on any failure.
         */
        fun fullBuild(
            factory: PlanarFrontEndFactory,
            intrinsics: CameraIntrinsics,
            reference: PhotosphereTrackingSession.ReferenceImage,
            pages: List<PhotosphereTrackingSession.AtlasPage>,
            minKpmFeatures: Int,
        ): Outcome {
            val created = factory.create(intrinsics)
            try {
                val root = created.addRoot(reference)
                if (root.featureCount < minKpmFeatures) throw ReferenceTooWeakException(root.featureCount, minKpmFeatures)
                val diagnostics = ArrayList<String>(pages.size + 1)
                diagnostics += "SphereSLAM standalone reference features=${root.featureCount} minimum=$minKpmFeatures"
                pages.forEach { page ->
                    val added = created.addAtlasPage(page)
                    diagnostics += "SphereSLAM atlas page=${page.pageNo} features=${added.featureCount}"
                }
                return Outcome.Built(created, intrinsics, root, diagnostics)
            } catch (t: Throwable) {
                closeQuietly(created)
                throw t
            }
        }

        /**
         * Growth job: rectify [geometry] out of [frameLuma] (a private copy), gate it on
         * [targetQuality], then build a candidate matcher — a [PlanarFrontEnd.fork] of [live] plus the
         * page, or, when [live] cannot fork, a fresh full build — and keep it only if the new page
         * reaches the KPM feature floor. A rejected page just drops the candidate: [live] is never
         * touched, so nothing has to be rebuilt.
         */
        fun growth(
            live: PlanarFrontEnd,
            factory: PlanarFrontEndFactory,
            intrinsics: CameraIntrinsics,
            reference: PhotosphereTrackingSession.ReferenceImage,
            keptPages: List<PhotosphereTrackingSession.AtlasPage>,
            pageNo: Int,
            frameLuma: ByteArray,
            frameWidth: Int,
            frameHeight: Int,
            geometry: PhotosphereAtlasGrowth.Geometry,
            targetQuality: TargetQualityConfig,
            attitude: AttitudeSample?,
        ): Outcome {
            val center = "center=(${geometry.centerX},${geometry.centerY})"
            val rectified = PhotosphereAtlasGrowth.rectify(frameLuma, frameWidth, frameHeight, geometry)
                ?: return Outcome.GrowthSkipped("SphereSLAM atlas growth skipped reason=rectify $center")
            if (!TargetQuality.analyze(rectified.luma, rectified.width, rectified.height, targetQuality).acceptable) {
                return Outcome.GrowthSkipped("SphereSLAM atlas growth skipped reason=target-quality $center")
            }
            val page = PhotosphereTrackingSession.AtlasPage(
                pageNo = pageNo,
                luma = rectified.luma,
                width = rectified.width,
                height = rectified.height,
                referenceWidthUnits = geometry.width,
                physicallyMetric = reference.physicallyMetric,
                canonicalFromPage = PhotosphereAtlasGrowth.canonicalFromPage(geometry.centerX, geometry.centerY),
            )
            val forked = live.fork()
            val candidate = forked ?: factory.create(intrinsics)
            try {
                if (forked == null) {
                    candidate.addRoot(reference)
                    keptPages.forEach { candidate.addAtlasPage(it) }
                }
                val added = candidate.addAtlasPage(page)
                if (added.featureCount < targetQuality.minKpmFeatures) {
                    closeQuietly(candidate)
                    return Outcome.GrowthSkipped(
                        "SphereSLAM atlas growth rejected page=$pageNo features=${added.featureCount} " +
                            "minimum=${targetQuality.minKpmFeatures}",
                    )
                }
                return Outcome.Grown(
                    frontEnd = candidate,
                    page = page,
                    keptPageNumbers = keptPages.map { it.pageNo },
                    attitude = attitude,
                    diagnostic = "SphereSLAM atlas grew page=$pageNo $center features=${added.featureCount}" +
                        if (forked == null) " rebuilt=true" else "",
                )
            } catch (t: Throwable) {
                closeQuietly(candidate)
                throw t
            }
        }
    }
}

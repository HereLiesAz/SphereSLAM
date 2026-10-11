package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * [PhotosphereTrackingSession]'s photosphere localization layer: where the camera is and which way it
 * looks, from the photosphere itself, so the artwork's placement (`map_from_fingerprint`) can be
 * rendered with no wall in view.
 *
 * It keeps, on the analyzer thread:
 * - a **drift correction** `W` (world-side rotation): the gyro/compass base rotation `G` is corrected
 *   to `G · W`. The first wall lock of a precision layer defines it — that is what relates the gyro
 *   frame to the map the artwork is placed in — and every later visual fix (a recognised tile, or a
 *   lock) re-solves it for the frame it was measured on. Nothing is captured, fixed or rendered
 *   before that first lock, so every tile is stored in the corrected map frame;
 * - the **camera centre**: from wall locks and landmark (6-DoF) tile fixes;
 * - the **stations**: when a fix puts the camera more than the spawn distance from every station, a
 *   new one opens there and becomes active (earlier ones are kept); within half that of another
 *   station, that one becomes active again;
 * - the **anchor**: a station's first lock anchors its tiles around that view direction — the lock
 *   tile and its neighbours outlive the per-station tile cap, so the wall's surroundings stay
 *   recognisable however long the artist works away from it ([PhotosphereTileLocalizer.anchor]).
 *
 * Tile capture, comparison and localization run on a serial worker ([PhotosphereTileLocalizer] is only
 * touched there); a finished fix is adopted on the next frame unless a newer fix (or lock) has landed
 * since it was measured. Worker diagnostics are queued and delivered on the analyzer thread
 * ([drainDiagnostics]). A missing OpenCV runtime disables tile localization once.
 *
 * Rotations here are GL (x right, y up, z backward) row-major 3×3 world→camera unless named `cv`; the
 * localizer works in the OpenCV camera frame, related by `F = diag(1, −1, −1)` on the camera side.
 */
@ExperimentalSphereSlamRelocApi
internal class PhotosphereStationTracker(
    backend: TileFeatureBackend,
    private val depthSource: TileDepthSource?,
    executor: Executor?,
    private val config: Config,
) : AutoCloseable {

    /**
     * @property fixIntervalMs at most one localization attempt per this interval; none while the
     *   wall is locked (a lock is the better fix).
     * @property centreFreshMs tiles are captured only within this of the last centre measurement (a
     *   lock or a 6-DoF fix): a tile filed at a stale centre would poison rotation fits and
     *   triangulation.
     */
    data class Config(
        val fixIntervalMs: Long = 250L,
        val centreFreshMs: Long = 3_000L,
        val localizer: PhotosphereTileLocalizer.Config = PhotosphereTileLocalizer.Config(),
    )

    private class Adopted(val fix: PhotosphereTileLocalizer.Fix, val baseRotation: DoubleArray, val atMs: Long, val generation: Int)

    private val ownedExecutor: ExecutorService? = if (executor == null) newTileExecutor() else null
    private val executor: Executor = executor ?: ownedExecutor!!
    private val localizer = PhotosphereTileLocalizer(backend, config.localizer)
    private val localizeInFlight = AtomicBoolean(false)
    private val keyframeInFlight = AtomicBoolean(false)
    private val finished = AtomicReference<Adopted?>(null)
    private val diagnostics = ConcurrentLinkedQueue<String>()
    private val generation = AtomicInteger(0)
    @Volatile private var disabled = false

    // Analyzer-thread state.
    private var correction: DoubleArray? = null
    private var lastFixMs = Long.MIN_VALUE
    private var lastLockMs = Long.MIN_VALUE
    private var lastCentreMs = Long.MIN_VALUE
    private var lastAttemptMs = Long.MIN_VALUE
    private val centre = DoubleArray(3)
    private val stationCentres = ArrayList<DoubleArray>()
    private var activeStation = -1
    private var spawnDistance = DEFAULT_SPAWN_DISTANCE
    private var closed = false
    private var wallKey: List<Float>? = null
    private val anchoredStations = HashSet<Int>()

    // Counters for the periodic diagnostic (analyzer thread).
    private var rotationFixes = 0
    private var poseFixes = 0

    @Volatile private var tileCount = 0
    @Volatile private var landmarkCount = 0
    @Volatile private var pseudoCount = 0
    @Volatile private var anchorCount = 0

    /** Stations opened so far. */
    val stationCount: Int get() = stationCentres.size

    /** The active station index, or −1 before the first one. */
    val activeStationIndex: Int get() = activeStation

    /** Current camera centre estimate, world (map) frame. */
    val cameraCentre: DoubleArray get() = centre.copyOf()

    /** Whether a lock has related the gyro frame to the map (photosphere poses are possible). */
    val hasCorrection: Boolean get() = correction != null && !disabled

    init {
        openStation(DoubleArray(3))
    }

    /**
     * Station spacing in map units: metres for a physically metric reference, otherwise the given
     * multiple of the reference width (its map units). The same-standpoint radius is half of it.
     */
    fun setSpawnDistance(units: Double) {
        if (!(units > 0) || !units.isFinite()) return
        spawnDistance = units
        val radius = units / 2
        submit { localizer.standpointRadius = radius }
    }

    /** `G · W` when a correction exists, else null. [base] is the frame's GL base rotation. */
    fun correctedRotation(base: DoubleArray): DoubleArray? = correction?.let { mul3(base, it) }

    /** Worker diagnostics, delivered on the caller's (analyzer) thread. */
    fun drainDiagnostics(sink: (String) -> Unit) {
        while (true) sink(diagnostics.poll() ?: return)
    }

    /**
     * Per frame: adopt a finished fix, then start a localization attempt when one is due — not while
     * the wall is locked, and never before the first lock.
     *
     * @param base the frame's GL base rotation (row-major), or null without attitude.
     */
    fun onFrame(base: DoubleArray?, luma: ByteArray, intrinsics: CameraIntrinsics, nowMs: Long) {
        if (closed || disabled) return
        adopt()
        base ?: return
        val w = correction ?: return
        if (lastLockMs != Long.MIN_VALUE && nowMs - lastLockMs < 2 * config.fixIntervalMs) return
        if (localizeInFlight.get()) return
        if (lastAttemptMs != Long.MIN_VALUE && nowMs - lastAttemptMs < config.fixIntervalMs) return
        lastAttemptMs = nowMs
        val prior = cv(mul3(base, w))
        val priorCentre = centre.copyOf()
        val copy = luma.copyOf()
        val k = intrinsicsOf(intrinsics)
        val baseCopy = base.copyOf()
        val gen = generation.get()
        localizeInFlight.set(true)
        submit(onDone = { localizeInFlight.set(false) }) {
            val live = localizer.extract(copy, intrinsics.width, intrinsics.height) ?: return@submit
            live.use {
                val fix = localizer.localize(it, k, prior, priorCentre)
                if (fix != null) finished.set(Adopted(fix, baseCopy, nowMs, gen))
            }
        }
    }

    /** Whether a keyframe now would be offered as a tile (so the session only gates frames it needs). */
    fun wantsKeyframe(nowMs: Long): Boolean =
        !closed && !disabled && correction != null && !keyframeInFlight.get() &&
            lastCentreMs != Long.MIN_VALUE && nowMs - lastCentreMs <= config.centreFreshMs

    /** A photosphere keyframe at the current centre (GL base rotation [base]); see [wantsKeyframe]. */
    fun onKeyframe(base: DoubleArray, luma: ByteArray, intrinsics: CameraIntrinsics, nowMs: Long) {
        if (!wantsKeyframe(nowMs)) return
        val rotation = cv(correctedRotation(base) ?: return)
        val copy = luma.copyOf()
        val k = intrinsicsOf(intrinsics)
        val station = activeStation
        val tileCentre = centre.copyOf()
        val gen = generation.get()
        keyframeInFlight.set(true)
        submit(onDone = { keyframeInFlight.set(false) }) {
            if (gen != generation.get()) return@submit
            val s = localizer.stations.getOrNull(station) ?: return@submit
            // The spacing gate first: monocular depth runs once per new tile, not per keyframe.
            if (!localizer.wouldAccept(s, rotation)) return@submit
            val depth = depthSource?.let { src ->
                runCatching { src.inverseDepth(copy, intrinsics.width, intrinsics.height) }
                    .onFailure { diagnostics += "SphereSLAM tile depth failed: ${it.message}" }
                    .getOrNull()
            }
            if (localizer.addTile(s, copy, intrinsics.width, intrinsics.height, k, rotation, tileCentre, depth) != null) {
                refreshCounts()
            }
        }
    }

    /**
     * A confirmed wall lock: [renderedMapView] is the pose rendered for it (GL column-major view in the
     * map, with translation), measured on a frame with GL base rotation [base]. Using the rendered
     * (smoothed) pose keeps the hand-over to a photosphere pose free of a jump.
     */
    fun onLock(renderedMapView: FloatArray, base: DoubleArray, nowMs: Long) {
        if (closed) return
        val rotation = rotationOfView(renderedMapView)
        correction = mul3(transpose3(base), rotation)
        lastFixMs = nowMs
        lastLockMs = nowMs
        setCentre(centreOfView(renderedMapView), nowMs)
        // Once per station: its first lock's view direction anchors the tiles around it.
        val station = activeStation
        if (station >= 0 && anchoredStations.add(station)) {
            val rotationCv = cv(rotation)
            val gen = generation.get()
            submit {
                if (gen != generation.get()) return@submit
                localizer.stations.getOrNull(station)?.let { localizer.anchor(it, rotationCv) }
                refreshCounts()
            }
        }
    }

    /**
     * The photosphere pose for a frame with GL base rotation [base]: corrected rotation at the current
     * centre, as a GL column-major view; null before the first lock (or when disabled).
     */
    fun pose(base: DoubleArray): FloatArray? {
        if (disabled) return null
        val corrected = correctedRotation(base) ?: return null
        return viewOf(corrected, centre)
    }

    /**
     * The wall's placement in the map ([mapFromFingerprint], GL column-major; the wall is the
     * fingerprint frame's z = 0 plane) and its extent ([windows]: `centreX, centreY, width, height`
     * per page, canonical units) — the depth reference for tiles that see it. Null clears it.
     */
    fun setWall(mapFromFingerprint: FloatArray?, windows: List<FloatArray>) {
        if (closed) return
        val m = mapFromFingerprint?.takeIf { it.size == 16 && it.all(Float::isFinite) }
        val key = m?.let { it.toList() + windows.flatMap { w -> w.toList() } }
        if (key == wallKey) return
        wallKey = key
        val plane = m?.let(::wallPlaneOf)
        val onWall = m?.let { onWallTest(it, windows) }
        val gen = generation.get()
        submit {
            if (gen != generation.get()) return@submit
            localizer.setWallPlane(plane, onWall)
            refreshCounts()
        }
    }

    /**
     * Forget the correction, every station and tile (a new wall reference or map: centres and the
     * correction are in its terms). The photosphere restarts as one station at the origin, and nothing
     * is rendered or captured until the next lock.
     */
    fun reset() {
        generation.incrementAndGet()
        finished.set(null)
        correction = null
        lastFixMs = Long.MIN_VALUE
        lastLockMs = Long.MIN_VALUE
        lastCentreMs = Long.MIN_VALUE
        stationCentres.clear()
        anchoredStations.clear()
        activeStation = -1
        centre.fill(0.0)
        wallKey = null
        submit { localizer.clear(); refreshCounts() }
        openStation(DoubleArray(3))
    }

    /** `tiles{…}` for the periodic diagnostic. */
    fun summary(): String = String.format(
        Locale.US,
        "tiles{stations=%d active=%d tiles=%d anchored=%d landmarks=%d depthPoints=%d rotFixes=%d poseFixes=%d%s}",
        stationCentres.size, activeStation, tileCount, anchorCount, landmarkCount, pseudoCount, rotationFixes, poseFixes,
        if (disabled) " disabled" else "",
    ).also { rotationFixes = 0; poseFixes = 0 }

    private fun refreshCounts() {
        tileCount = localizer.stations.sumOf { it.tileCount }
        anchorCount = localizer.stations.sumOf { it.anchorTileCount }
        landmarkCount = localizer.stations.sumOf { st -> st.tiles.sumOf { it.landmarkCount } }
        pseudoCount = localizer.stations.sumOf { st -> st.tiles.sumOf { it.pseudoLandmarkCount } }
    }

    private fun adopt() {
        val done = finished.getAndSet(null) ?: return
        // Measured before the latest fix or lock (or for an earlier reference): stale.
        if (done.generation != generation.get() || done.atMs <= lastFixMs || correction == null) return
        correction = mul3(transpose3(done.baseRotation), glFromCv(done.fix.rotation))
        lastFixMs = done.atMs
        when (val fix = done.fix) {
            is PhotosphereTileLocalizer.Fix.Pose -> {
                poseFixes++
                setCentre(fix.centre, done.atMs)
            }
            is PhotosphereTileLocalizer.Fix.Rotation -> rotationFixes++
        }
    }

    private fun setCentre(c: DoubleArray, atMs: Long) {
        if (c.size != 3 || c.any { !it.isFinite() }) return
        c.copyInto(centre)
        lastCentreMs = atMs
        val (nearest, distance) = stationCentres.withIndex()
            .map { it.index to PhotosphereTileLocalizer.distance(it.value, c) }
            .minByOrNull { it.second } ?: return
        when {
            distance > spawnDistance -> openStation(c)
            distance <= spawnDistance / 2 && nearest != activeStation -> {
                activeStation = nearest
                diagnostics += "SphereSLAM station $nearest active again"
            }
        }
    }

    private fun openStation(c: DoubleArray) {
        val copy = c.copyOf()
        stationCentres += copy
        activeStation = stationCentres.size - 1
        val gen = generation.get()
        submit { if (gen == generation.get()) localizer.addStation(copy) }
        if (activeStation > 0) {
            diagnostics += String.format(
                Locale.US, "SphereSLAM station %d opened at (%.3f, %.3f, %.3f); earlier stations kept",
                activeStation, copy[0], copy[1], copy[2],
            )
        }
    }

    private fun submit(onDone: () -> Unit = {}, job: () -> Unit) {
        if (closed) {
            onDone()
            return
        }
        try {
            executor.execute {
                try {
                    if (!disabled) job()
                } catch (e: LinkageError) {
                    // No OpenCV runtime in this process: tile localization cannot work here.
                    if (!disabled) {
                        disabled = true
                        diagnostics += "SphereSLAM tile localization disabled: OpenCV unavailable (${e.message})"
                    }
                } catch (t: Throwable) {
                    if (t is VirtualMachineError) throw t
                    // A failing tile job (native feature code, a depth model) costs that tile or fix only.
                    diagnostics += "SphereSLAM tile job failed: ${t.javaClass.simpleName}: ${t.message}"
                } finally {
                    onDone()
                }
            }
        } catch (_: RejectedExecutionException) {
            onDone()
        }
    }

    override fun close() {
        if (closed) return
        submit { localizer.close() }
        closed = true
        ownedExecutor?.shutdown()
    }

    internal companion object {
        /** Default station spacing, map units (overridden by [setSpawnDistance]). */
        const val DEFAULT_SPAWN_DISTANCE = 0.3

        /** Extra margin around the wall's pages when testing whether a point lies on the wall. */
        const val WALL_MARGIN = 0.1f

        fun newTileExecutor(): ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "SphereSLAM-tiles").apply { isDaemon = true }
        }

        fun intrinsicsOf(i: CameraIntrinsics) = floatArrayOf(i.fx, i.fy, i.cx, i.cy)

        /** The fingerprint frame's z = 0 plane in the map, from a GL column-major map_from_fingerprint. */
        fun wallPlaneOf(m: FloatArray): PhotosphereGeometry.Plane? {
            val n = doubleArrayOf(m[8].toDouble(), m[9].toDouble(), m[10].toDouble())
            val len = kotlin.math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
            if (len < 1e-9) return null
            for (i in 0..2) n[i] /= len
            val offset = n[0] * m[12] + n[1] * m[13] + n[2] * m[14]
            return PhotosphereGeometry.Plane(n, offset)
        }

        /**
         * Whether a map point lies on the wall: mapped into the fingerprint frame (rigid inverse of
         * [m]) it must fall inside one of [windows] (with [WALL_MARGIN] of its size around it).
         */
        fun onWallTest(m: FloatArray, windows: List<FloatArray>): (DoubleArray) -> Boolean {
            val r = rotationOfView(m) // row-major rotation of map_from_fingerprint
            val t = doubleArrayOf(m[12].toDouble(), m[13].toDouble(), m[14].toDouble())
            val pages = windows.map { it.copyOf() }
            return { p ->
                val d = doubleArrayOf(p[0] - t[0], p[1] - t[1], p[2] - t[2])
                // fingerprint = Rᵀ (p − t)
                val x = r[0] * d[0] + r[3] * d[1] + r[6] * d[2]
                val y = r[1] * d[0] + r[4] * d[1] + r[7] * d[2]
                pages.any { w ->
                    abs(x - w[0]) <= w[2] * (0.5 + WALL_MARGIN) && abs(y - w[1]) <= w[3] * (0.5 + WALL_MARGIN)
                }
            }
        }

        /** Row-major rotation of a GL column-major matrix. */
        fun rotationOfView(view: FloatArray): DoubleArray =
            DoubleArray(9) { i -> view[(i % 3) * 4 + i / 3].toDouble() }

        /** World camera centre of a GL column-major view: `−Rᵀ t`. */
        fun centreOfView(view: FloatArray): DoubleArray {
            val r = rotationOfView(view)
            val t = doubleArrayOf(view[12].toDouble(), view[13].toDouble(), view[14].toDouble())
            return DoubleArray(3) { c -> -(r[c] * t[0] + r[3 + c] * t[1] + r[6 + c] * t[2]) }
        }

        /** GL column-major view from a row-major rotation and a world centre. */
        fun viewOf(r: DoubleArray, c: DoubleArray): FloatArray {
            val out = FloatArray(16)
            for (row in 0..2) {
                for (col in 0..2) out[col * 4 + row] = r[row * 3 + col].toFloat()
                out[12 + row] = -(r[row * 3] * c[0] + r[row * 3 + 1] * c[1] + r[row * 3 + 2] * c[2]).toFloat()
            }
            out[15] = 1f
            return out
        }

        /** OpenCV camera ↔ GL camera: negate the y and z rows. Its own inverse. */
        fun cv(gl: DoubleArray): DoubleArray = DoubleArray(9) { i -> if (i < 3) gl[i] else -gl[i] }
        fun glFromCv(cv: DoubleArray): DoubleArray = cv(cv)

        fun transpose3(a: DoubleArray) = DoubleArray(9) { i -> a[(i % 3) * 3 + i / 3] }
        fun mul3(a: DoubleArray, b: DoubleArray) = PhotosphereTileLocalizer.mul3(a, b)
    }
}

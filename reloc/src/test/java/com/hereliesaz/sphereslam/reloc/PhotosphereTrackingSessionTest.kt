package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.AnticipatedView
import com.hereliesaz.sphereslam.PhotosphereKeyframe
import com.hereliesaz.sphereslam.SphereSlamPoseMath
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.attitude.DisplayPosePredictor
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.math.RigidMath
import com.hereliesaz.sphereslam.math.RotationMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

class PhotosphereTrackingSessionTest {

    private val intrinsics = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)
    private val textured = noise(640 * 480, 1)
    private val flat = ByteArray(640 * 480) { 100 }
    private var nowNs = 1_000_000_000L

    private fun noise(n: Int, seed: Int): ByteArray {
        val r = Random(seed)
        return ByteArray(n) { r.nextInt(256).toByte() }
    }

    /** Attitude yawed [deg] about the device y axis. */
    private fun yaw(deg: Double): AttitudeSample {
        val h = Math.toRadians(deg) / 2
        return AttitudeSample(floatArrayOf(0f, kotlin.math.sin(h).toFloat(), 0f, kotlin.math.cos(h).toFloat()), nowNs)
    }

    /** GL camera-from-canonical for a camera at (x, y, d) looking down -z at the wall. */
    private fun viewAt(x: Float, y: Float, d: Float) = RigidMath.identity().also {
        it[12] = -x; it[13] = -y; it[14] = -d
    }

    private class FakeFrontEnd(
        private val featuresFor: (Int) -> Int,
        private val forkInto: ((FakeFrontEnd) -> Unit)? = null,
    ) : PlanarFrontEnd {
        val pages = mutableListOf<Pair<Int, FloatArray>>()
        var next: PlanarMatchResult? = null
        var closed = false
        var matches = 0
        var throwOnMatch: RuntimeException? = null

        override fun addPage(
            luma: ByteBuffer, width: Int, height: Int, referenceWidthUnits: Float,
            physicallyMetric: Boolean, pageNo: Int, canonicalFromPage: FloatArray,
        ): PhotosphereTrackingSession.RegisteredPage {
            check(pages.none { it.first == pageNo }) { "page $pageNo is already registered" }
            pages += pageNo to canonicalFromPage.copyOf()
            val dpi = SphereSlamPoseMath.dpiForReferenceWidth(width, referenceWidthUnits)
            return PhotosphereTrackingSession.RegisteredPage(
                pageNo, featuresFor(pageNo), SphereSlamPoseMath.pageGeometry(width, height, dpi), physicallyMetric,
            )
        }

        override fun match(luma: ByteBuffer, timestampNs: Long): PlanarMatchResult? {
            matches++
            throwOnMatch?.let { throw it }
            return next
        }

        val inputs = mutableListOf<PlanarFrameInput>()
        val verdicts = mutableListOf<Boolean>()

        override fun match(input: PlanarFrameInput): PlanarMatchResult? {
            inputs += input
            return match(input.luma, input.timestampNs)
        }

        override fun onVerdict(accepted: Boolean) {
            verdicts += accepted
        }

        // Each fake stands for its own previews matcher.
        private val key = Any()
        val viewsAdded = mutableListOf<Pair<Int, List<String>>>()
        var searchable: Set<String>? = setOf("unset")

        override val anticipationKey: Any get() = key

        override fun addAnticipatedViews(pageNo: Int, views: List<AnticipatedView>) {
            viewsAdded += pageNo to views.map { it.id }
        }

        override fun setSearchableViews(ids: Set<String>?) {
            searchable = ids
        }

        override fun fork(): PlanarFrontEnd? {
            val register = forkInto ?: return null
            check(!closed) { "forked a closed matcher" }
            return FakeFrontEnd(featuresFor, forkInto).also {
                it.pages += pages
                it.next = next
                register(it)
            }
        }

        override fun close() {
            closed = true
        }
    }

    /** Runs submitted tasks only when told to, like a background thread that has not got there yet. */
    private class QueueExecutor : java.util.concurrent.Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queue.addLast(command)
        }

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private class Recorder {
        val keyframes = mutableListOf<PhotosphereKeyframe>()
        val solved = mutableListOf<FloatArray>()
        val ready = mutableListOf<PhotosphereTrackingSession.RegisteredPage>()
        val grown = mutableListOf<PhotosphereTrackingSession.AtlasPage>()
        val failures = mutableListOf<TrackingFailureEvent>()
        val states = mutableListOf<TrackingState>()
        val fatal = mutableListOf<Throwable>()
        val diagnostics = mutableListOf<String>()
        val callbacks = PhotosphereTrackingSession.Callbacks(
            onKeyframe = { keyframes += it },
            onMapFromFingerprintSolved = { solved += it },
            onReferenceReady = { ready += it },
            onAtlasPageAdded = { p, _ -> grown += p },
            onFailure = { failures += it },
            onTrackingStateChanged = { states += it },
            onFatalError = { fatal += it },
            onDiagnostic = { diagnostics += it },
        )
    }

    private val fronts = mutableListOf<FakeFrontEnd>()
    private var featuresFor: (Int) -> Int = { 100 }
    private val rec = Recorder()

    private var forkable = false

    private fun session(
        config: PhotosphereTrackingSession.Config = PhotosphereTrackingSession.Config(),
        executor: java.util.concurrent.Executor = java.util.concurrent.Executor(Runnable::run),
    ) = PhotosphereTrackingSession(
        config, rec.callbacks, null, { nowNs },
        PlanarFrontEndFactory {
            FakeFrontEnd(featuresFor, if (forkable) { f -> fronts += f } else null).also { fronts += it }
        },
        executor,
    )

    /** A tile backend that never recognises anything: the photosphere layer runs on wall locks alone. */
    private val blindTiles = object : TileFeatureBackend {
        override fun extract(luma: ByteArray, width: Int, height: Int): TileFeatures? = null
        override fun match(query: TileFeatures, train: TileFeatures) = IntArray(0)
    }

    private fun tileSession(config: PhotosphereTrackingSession.Config = PhotosphereTrackingSession.Config()) =
        PhotosphereTrackingSession(
            config, rec.callbacks, null, { nowNs },
            PlanarFrontEndFactory { FakeFrontEnd(featuresFor, null).also { fronts += it } },
            java.util.concurrent.Executor(Runnable::run),
            blindTiles,
        )

    private fun frame(
        attitude: AttitudeSample? = yaw(0.0),
        luma: ByteArray = textured,
        k: CameraIntrinsics = intrinsics,
        heading: Float? = null,
    ) = PhotosphereTrackingSession.Frame(
        luma = if (luma.size == k.width * k.height) luma else noise(k.width * k.height, 2),
        intrinsics = k,
        timestampNs = nowNs,
        attitude = attitude,
        headingDeg = heading,
        elevationDeg = heading?.let { 0f },
    )

    private val reference = PhotosphereTrackingSession.ReferenceImage(noise(400 * 300, 3), 400, 300)

    private fun match(view: FloatArray, inliers: Int = 30, error: Float = 1f) = PlanarMatchResult(0, view, error, inliers)

    private fun advance(ms: Long) {
        nowNs += ms * 1_000_000L
    }

    @Test
    fun `base map is the absolute attitude, rotation only`() {
        val s = session()
        assertNull(s.onFrame(frame(attitude = null)).baseCameraFromMap)
        val first = s.onFrame(frame(yaw(0.0))).baseCameraFromMap!!
        val expected = PhotosphereTrackingSession.absoluteCameraRotation(RotationMath.IDENTITY_3X3, yaw(0.0).quaternion)
        for (row in 0..2) for (col in 0..2) assertEquals(expected[row * 3 + col], first[col * 4 + row], 1e-6f)
        val turned = s.onFrame(frame(yaw(20.0))).baseCameraFromMap!!
        assertEquals(20f, RigidMath.rotationDeltaDeg(first, turned), 1e-2f)
        assertEquals(0f, RigidMath.translationNorm(turned), 0f)
    }

    @Test
    fun `keyframes are cadenced and quality gated`() {
        val s = session()
        s.onFrame(frame(heading = 10f))
        assertEquals(1, rec.keyframes.size)
        advance(100)
        s.onFrame(frame(heading = 10f))
        assertEquals(1, rec.keyframes.size) // inside the 250 ms cadence
        advance(300)
        s.onFrame(frame(luma = flat, heading = 10f))
        assertEquals(1, rec.keyframes.size) // flat frame rejected
        advance(100)
        s.onFrame(frame(heading = 10f))
        assertEquals(1, rec.keyframes.size) // the rejected frame consumed the slot
        advance(300)
        s.onFrame(frame(heading = 10f))
        assertEquals(2, rec.keyframes.size)
        assertNotNull(rec.keyframes.last().cameraFromMap)
        s.onFrame(frame(heading = null).also { advance(300) })
        assertEquals(2, rec.keyframes.size) // no absolute attitude, no keyframe
    }

    @Test
    fun `no reference means no planar session and no pose`() {
        val s = session()
        val r = s.onFrame(frame())
        assertNull(r.pose)
        assertTrue(fronts.isEmpty())
        assertFalse(s.hasReference)
    }

    @Test
    fun `the host's anticipated views reach every matcher the session builds, and leave with their reference`() {
        fun view(id: String) = AnticipatedView(id, ByteArray(4), 2, 2, FloatArray(9) { if (it % 4 == 0) 1f else 0f }, AnticipatedView.SQUARE_ON, 1f)
        val s = session()
        s.onFrame(frame(yaw(0.0)))
        s.updatePrecisionLayer(reference)
        // Added before any matcher: kept, and given to the first one.
        s.addAnticipatedViews(0, listOf(view("a"), view("b")))
        s.setSearchableAnticipatedViews(setOf("a"))
        s.onFrame(frame(yaw(0.0)))
        val first = fronts.single()
        assertEquals(listOf(0 to listOf("a", "b")), first.viewsAdded)
        assertEquals(setOf("a"), first.searchable)
        // Added later: straight to the live matcher, once.
        s.addAnticipatedViews(0, listOf(view("c")))
        s.onFrame(frame(yaw(0.0)))
        assertEquals(listOf(0 to listOf("a", "b"), 0 to listOf("c")), first.viewsAdded)
        s.setSearchableAnticipatedViews(null)
        assertNull(first.searchable)
        // A rebuilt matcher gets every view again.
        s.updatePrecisionLayer(reference, forceRebuild = true)
        s.onFrame(frame(yaw(0.0)))
        val second = fronts.last()
        assertTrue(second !== first)
        assertEquals(listOf(0 to listOf("a", "b", "c")), second.viewsAdded)
        // A new reference drops them: they were rendered from the old one.
        s.updatePrecisionLayer(PhotosphereTrackingSession.ReferenceImage(noise(400 * 300, 9), 400, 300))
        s.onFrame(frame(yaw(0.0)))
        assertTrue(fronts.last().viewsAdded.isEmpty())
    }

    @Test
    fun `a lock solves map_from_fingerprint once and rebases into the base map`() {
        val s = session()
        s.onFrame(frame(yaw(0.0))) // defines the base map
        s.updatePrecisionLayer(reference)
        val wall = viewAt(0.2f, 0.1f, 2f)
        val r1 = run {
            s.onFrame(frame(yaw(15.0)))
            fronts.single().next = match(wall)
            s.onFrame(frame(yaw(15.0)))
        }
        assertTrue(r1.visualAccepted)
        assertNull(r1.pose) // confirming
        val r2 = s.onFrame(frame(yaw(15.0)))
        assertEquals(TrackingState.LOCKED, r2.state)
        assertEquals(1, rec.solved.size)
        val base = r2.baseCameraFromMap!!
        assertArrayEquals(RigidMath.multiply(RigidMath.rigidInverse(base), wall), rec.solved[0], 1e-4f)
        // With the solved bridge the rendered and accepted map views equal the base pose.
        assertArrayEquals(base, r2.pose!!.cameraFromMap, 1e-4f)
        assertArrayEquals(base, r2.acceptedCameraFromMap!!, 1e-4f)
        assertArrayEquals(wall, r2.acceptedCameraFromFingerprint!!, 0f)
        assertEquals(PhotosphereTrackingSession.PoseSource.KPM, r2.pose!!.source)
        assertEquals(2f * 2f / (500f / 240f * 480f), r2.pose!!.unitsPerPixel, 1e-6f)
        s.onFrame(frame(yaw(15.0)))
        assertEquals(1, rec.solved.size) // once per reference
        assertArrayEquals(rec.solved[0], s.mapFromFingerprint!!, 0f)
        assertEquals(1, rec.ready.size)
    }

    @Test
    fun `a brief miss is gyro bridged then dropped when holding is off`() {
        val s = session(PhotosphereTrackingSession.Config(holdOffWall = false))
        s.updatePrecisionLayer(reference)
        s.onFrame(frame(yaw(0.0)))
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        repeat(2) { s.onFrame(frame(yaw(0.0))) }
        val held = s.onFrame(frame(yaw(0.0))).pose!!
        fronts.single().next = null
        advance(100)
        val bridged = s.onFrame(frame(yaw(3.0)))
        assertEquals(TrackingState.IMU_BRIDGE, bridged.state)
        val pose = bridged.pose!!
        assertEquals(PhotosphereTrackingSession.PoseSource.IMU_BRIDGE, pose.source)
        assertEquals(3f, RigidMath.rotationDeltaDeg(held.cameraFromMap, pose.cameraFromMap), 1e-2f)
        assertArrayEquals(RigidMath.cameraCentre(held.cameraFromMap), RigidMath.cameraCentre(pose.cameraFromMap), 1e-5f)
        advance(100)
        s.onFrame(frame(yaw(4.0)))
        // Once before the lock, once for this miss streak (reported on change of reason only).
        assertEquals(2, rec.failures.count { it.reason == TrackingFailureReason.NO_CURRENT_PAGE_MATCH })
        advance(500) // past maxBridgeMs
        val dropped = s.onFrame(frame(yaw(5.0)))
        assertNull(dropped.pose)
        assertEquals(TrackingState.REACQUIRING, dropped.state)
        assertEquals(TrackingFailureReason.NO_CURRENT_PAGE_MATCH, dropped.failure!!.reason)
    }

    @Test
    fun `past the bridge the last lock is held through the gyro until a relock`() {
        val s = session()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame(yaw(0.0)))
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        repeat(2) { s.onFrame(frame(yaw(0.0))) }
        val locked = s.onFrame(frame(yaw(0.0))).pose!!
        fronts.single().next = null
        advance(100)
        assertEquals(TrackingState.IMU_BRIDGE, s.onFrame(frame(yaw(3.0))).state)
        advance(5_000) // wall long out of view
        val held = s.onFrame(frame(yaw(40.0)))
        assertEquals(TrackingState.REACQUIRING, held.state)
        val pose = held.pose!!
        assertEquals(PhotosphereTrackingSession.PoseSource.HELD, pose.source)
        assertEquals(40f, RigidMath.rotationDeltaDeg(locked.cameraFromMap, pose.cameraFromMap), 1e-2f)
        assertArrayEquals(RigidMath.cameraCentre(locked.cameraFromMap), RigidMath.cameraCentre(pose.cameraFromMap), 1e-5f)
        // Back on the wall: held while the relock confirms, then the visual pose takes over.
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        val confirming = s.onFrame(frame(yaw(0.0)))
        assertEquals(PhotosphereTrackingSession.PoseSource.HELD, confirming.pose!!.source)
        var relocked = confirming
        repeat(3) { relocked = s.onFrame(frame(yaw(0.0))) }
        assertEquals(TrackingState.LOCKED, relocked.state)
        assertEquals(PhotosphereTrackingSession.PoseSource.KPM, relocked.pose!!.source)
    }

    @Test
    fun `off the wall the photosphere places the artwork from the last visual fix`() {
        val s = tileSession()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame(yaw(0.0)))
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        repeat(2) { s.onFrame(frame(yaw(0.0))) }
        val locked = s.onFrame(frame(yaw(0.0))).pose!!
        assertEquals(1, s.stationCount)
        fronts.single().next = null
        advance(1_000) // past the bridge
        val off = s.onFrame(frame(yaw(30.0)))
        val pose = off.pose!!
        assertEquals(PhotosphereTrackingSession.PoseSource.PHOTOSPHERE, pose.source)
        assertEquals(30f, RigidMath.rotationDeltaDeg(locked.cameraFromMap, pose.cameraFromMap), 1e-2f)
        assertArrayEquals(RigidMath.cameraCentre(locked.cameraFromMap), RigidMath.cameraCentre(pose.cameraFromMap), 1e-4f)
        // Long after: still placed from the photosphere (gyro-carried since the last visual fix).
        advance(5_000)
        val later = s.onFrame(frame(yaw(50.0))).pose!!
        assertEquals(PhotosphereTrackingSession.PoseSource.PHOTOSPHERE, later.source)
        assertEquals(50f, RigidMath.rotationDeltaDeg(locked.cameraFromMap, later.cameraFromMap), 1e-2f)
    }

    @Test
    fun `nothing is placed from the photosphere before the first lock`() {
        val s = tileSession()
        // A host-supplied map (e.g. persisted) but no lock in this session yet.
        s.updatePrecisionLayer(reference, mapFromFingerprint = RigidMath.identity())
        s.onFrame(frame(yaw(0.0)))
        advance(1_000)
        assertNull(s.onFrame(frame(yaw(10.0))).pose)
    }

    @Test
    fun `a lock away from every station opens a new one and keeps the first`() {
        val s = tileSession()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame(yaw(0.0)))
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        repeat(3) { s.onFrame(frame(yaw(0.0))) } // solves map_from_fingerprint at the origin station
        assertEquals(1, s.stationCount)
        // The artist steps 0.5 units sideways (> stationSpawnDistance 0.3).
        fronts.single().next = match(viewAt(-0.5f, 0f, 2f))
        repeat(3) { s.onFrame(frame(yaw(0.0))) }
        assertEquals(2, s.stationCount)
        assertTrue(rec.diagnostics.any { it.startsWith("SphereSLAM station 1 opened") })
        // Back where it started: the first station is active again, no third one.
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        repeat(3) { s.onFrame(frame(yaw(0.0))) }
        assertEquals(2, s.stationCount)
        assertTrue(rec.diagnostics.any { it == "SphereSLAM station 0 active again" })
    }

    @Test
    fun `a precision-layer change ends the hold`() {
        val s = session()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame(yaw(0.0)))
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        repeat(3) { s.onFrame(frame(yaw(0.0))) }
        fronts.single().next = null
        advance(1_000)
        assertEquals(PhotosphereTrackingSession.PoseSource.HELD, s.onFrame(frame(yaw(10.0))).pose!!.source)
        s.updatePrecisionLayer(reference, forceRebuild = true)
        assertNull(s.onFrame(frame(yaw(10.0))).pose)
    }

    @Test
    fun `rejections are classified`() {
        val s = session()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        fronts.single().next = match(viewAt(0f, 0f, 2f), inliers = 2)
        val r = s.onFrame(frame())
        assertEquals(TrackingFailureReason.INSUFFICIENT_INLIERS, r.failure!!.reason)
        assertEquals(TrackingFailureSeverity.TRANSIENT, r.failure!!.severity)
    }

    @Test
    fun `planar session is rebuilt when intrinsics change`() {
        val s = session()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        s.onFrame(frame())
        assertEquals(1, fronts.size)
        val other = CameraIntrinsics(480f, 480f, 240f, 320f, 480, 640)
        s.onFrame(frame(k = other))
        assertEquals(2, fronts.size)
        assertTrue(fronts[0].closed)
        assertEquals(2, rec.ready.size)
    }

    @Test
    fun `a weak reference is a recoverable failure that a new reference clears`() {
        featuresFor = { 3 }
        val s = session()
        val base0 = s.onFrame(frame(yaw(0.0))).baseCameraFromMap!!
        s.updatePrecisionLayer(reference)
        val r = s.onFrame(frame(yaw(10.0)))
        assertEquals(TrackingState.FATAL, r.state)
        assertEquals(TrackingFailureReason.REFERENCE_TOO_WEAK, r.failure!!.reason)
        assertEquals(TrackingFailureSeverity.RECOVERABLE, r.failure!!.severity)
        assertTrue(rec.fatal.single() is ReferenceTooWeakException)
        assertTrue(s.isFatal)
        assertTrue(s.isRecoverable)
        assertTrue(fronts.single().closed)
        // The base map keeps running (same orientation), the precision layer stays off.
        val held = s.onFrame(frame(yaw(20.0)))
        assertNull(held.pose)
        assertEquals(TrackingState.FATAL, held.state)
        assertEquals(20f, RigidMath.rotationDeltaDeg(base0, held.baseCameraFromMap!!), 1e-2f)
        assertEquals(1, fronts.size) // no rebuild of the same weak reference
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        assertEquals(1, fronts.size) // same reference, nothing changed: still halted

        // A different reference recovers: state resets, the matcher is rebuilt and can lock.
        featuresFor = { 100 }
        val other = PhotosphereTrackingSession.ReferenceImage(noise(400 * 300, 9), 400, 300)
        s.updatePrecisionLayer(other)
        assertFalse(s.isFatal)
        assertEquals(TrackingState.INITIALIZING, s.trackingState)
        assertEquals(TrackingState.INITIALIZING, rec.states.last())
        s.onFrame(frame(yaw(20.0)))
        assertEquals(2, fronts.size)
        fronts.last().next = match(viewAt(0f, 0f, 2f))
        s.onFrame(frame(yaw(20.0)))
        val locked = s.onFrame(frame(yaw(20.0)))
        assertEquals(TrackingState.LOCKED, locked.state)
        assertNotNull(locked.pose)
        assertEquals(20f, RigidMath.rotationDeltaDeg(base0, locked.baseCameraFromMap!!), 1e-2f) // base map preserved
    }

    @Test
    fun `an internal failure is terminal and clears the display predictor`() {
        val s = session()
        val predictor = DisplayPosePredictor()
        s.displayPosePredictor = predictor
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        repeat(2) { s.onFrame(frame()) }
        assertTrue(predictor.hasPose)
        fronts.single().throwOnMatch = IllegalStateException("boom")
        val r = s.onFrame(frame())
        assertEquals(TrackingState.FATAL, r.state)
        assertEquals(TrackingFailureSeverity.FATAL, r.failure!!.severity)
        assertFalse(predictor.hasPose)
        assertFalse(s.isRecoverable)
        // Not recoverable: a new reference does not revive it.
        s.updatePrecisionLayer(PhotosphereTrackingSession.ReferenceImage(noise(400 * 300, 9), 400, 300))
        assertTrue(s.isFatal)
        assertEquals(TrackingState.FATAL, s.trackingState)
        assertNull(s.onFrame(frame()).baseCameraFromMap)
    }

    @Test
    fun `close clears the display predictor`() {
        val s = session()
        val predictor = DisplayPosePredictor()
        s.displayPosePredictor = predictor
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        repeat(2) { s.onFrame(frame()) }
        assertTrue(predictor.hasPose)
        s.close()
        assertFalse(predictor.hasPose)
    }

    @Test
    fun `a null map with the same reference keeps the solved map, a new reference re-solves`() {
        val s = session()
        s.onFrame(frame(yaw(0.0)))
        s.updatePrecisionLayer(reference)
        s.onFrame(frame(yaw(15.0)))
        val wall = viewAt(0.2f, 0.1f, 2f)
        fronts.single().next = match(wall)
        repeat(2) { s.onFrame(frame(yaw(15.0))) }
        val solved = rec.solved.single()

        s.updatePrecisionLayer(reference) // e.g. a host re-applying settings without a persisted map
        assertArrayEquals(solved, s.mapFromFingerprint!!, 0f)
        assertEquals(1, fronts.size) // nothing changed, no rebuild
        val r = s.onFrame(frame(yaw(15.0)))
        assertEquals(TrackingState.LOCKED, r.state)
        assertArrayEquals(r.acceptedCameraFromMap!!, r.pose!!.cameraFromMap, 1e-4f)
        assertArrayEquals(r.baseCameraFromMap!!, r.pose!!.cameraFromMap, 1e-4f) // still rebased, not gyro-only

        // A host map overrides it; passing null again returns to the session's solved map.
        val hostMap = viewAt(1f, 0f, 0f)
        s.updatePrecisionLayer(reference, mapFromFingerprint = hostMap)
        assertArrayEquals(hostMap, s.mapFromFingerprint!!, 0f)
        s.updatePrecisionLayer(reference)
        assertArrayEquals(solved, s.mapFromFingerprint!!, 0f)

        // A new reference drops the old reference's map and re-arms the solve.
        val other = PhotosphereTrackingSession.ReferenceImage(noise(400 * 300, 9), 400, 300)
        s.updatePrecisionLayer(other)
        assertNull(s.mapFromFingerprint)
        s.onFrame(frame(yaw(30.0)))
        fronts.last().next = match(wall)
        repeat(2) { s.onFrame(frame(yaw(30.0))) }
        assertEquals(2, rec.solved.size)
        assertNotNull(s.mapFromFingerprint)
    }

    @Test
    fun `the first lock refines a host-supplied map that disagrees, and keeps one that agrees`() {
        fun lockWith(hostMap: (FloatArray) -> FloatArray): Pair<PhotosphereTrackingSession, FloatArray> {
            fronts.clear(); rec.solved.clear()
            val s = session()
            val base = s.onFrame(frame(yaw(15.0))).baseCameraFromMap!!
            val wall = viewAt(0.2f, 0.1f, 2f)
            val exact = RigidMath.multiply(RigidMath.rigidInverse(base), wall)
            s.updatePrecisionLayer(reference, mapFromFingerprint = hostMap(exact))
            s.onFrame(frame(yaw(15.0)))
            fronts.single().next = match(wall)
            repeat(3) { s.onFrame(frame(yaw(15.0))) }
            return s to exact
        }
        // A provisional placement 10° off (e.g. a floor capture's guessed page orientation): replaced.
        val (off, exact) = lockWith { RigidMath.multiply(it, rotY(10.0)) }
        assertEquals(1, rec.solved.size)
        assertArrayEquals(exact, off.mapFromFingerprint!!, 1e-4f)
        // One within tolerance (a persisted solve, round-tripped): kept, nothing re-published.
        lockWith { RigidMath.multiply(it, rotY(0.5)) }
        assertTrue(rec.solved.isEmpty())
    }

    private fun rotY(deg: Double): FloatArray {
        val a = Math.toRadians(deg)
        val c = kotlin.math.cos(a).toFloat(); val sn = kotlin.math.sin(a).toFloat()
        return floatArrayOf(c, 0f, -sn, 0f, 0f, 1f, 0f, 0f, sn, 0f, c, 0f, 0f, 0f, 0f, 1f)
    }

    @Test
    fun `a reference swap resets tracking and needs a confirmed lock before solving the map`() {
        val s = session()
        s.onFrame(frame(yaw(0.0)))
        s.updatePrecisionLayer(reference)
        s.onFrame(frame(yaw(0.0)))
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        repeat(2) { s.onFrame(frame(yaw(0.0))) }
        assertEquals(TrackingState.LOCKED, s.trackingState)
        assertEquals(1, rec.solved.size)

        val other = PhotosphereTrackingSession.ReferenceImage(noise(400 * 300, 9), 400, 300)
        s.updatePrecisionLayer(other)
        assertEquals(TrackingState.INITIALIZING, s.trackingState)
        assertEquals(TrackingState.INITIALIZING, rec.states.last())
        s.onFrame(frame(yaw(0.0)))
        fronts.last().next = match(viewAt(0.5f, 0f, 2f))
        val first = s.onFrame(frame(yaw(0.0)))
        assertEquals(TrackingState.INITIALIZING, first.state) // one unconfirmed frame is not a lock
        assertNull(first.pose)
        assertEquals(1, rec.solved.size) // and does not fix the map bridge
        val second = s.onFrame(frame(yaw(0.0)))
        assertEquals(TrackingState.LOCKED, second.state)
        assertEquals(2, rec.solved.size)

        // Dropping the reference leaves no lock behind.
        s.updatePrecisionLayer(null)
        assertEquals(TrackingState.INITIALIZING, s.trackingState)
        assertNull(s.onFrame(frame(yaw(0.0))).pose)
    }

    @Test
    fun `the next visual pose after a gyro bridge blends from the bridged pose`() {
        val s = session()
        val predictor = DisplayPosePredictor()
        s.displayPosePredictor = predictor
        s.onFrame(frame(yaw(0.0)))
        s.updatePrecisionLayer(reference)
        s.onFrame(frame(yaw(0.0)))
        val wall = viewAt(0f, 0f, 2f)
        fronts.single().next = match(wall)
        repeat(3) { s.onFrame(frame(yaw(0.0))) }
        fronts.single().next = null
        advance(50)
        val bridged = s.onFrame(frame(yaw(8.0)))
        assertEquals(TrackingState.IMU_BRIDGE, bridged.state)
        assertEquals(bridged.pose!!.timestampNs, predictor.poseTimestampNs) // the predictor holds the bridged pose
        assertArrayEquals(bridged.pose!!.cameraFromMap, predictor.predict(yaw(8.0).quaternion), 1e-5f)

        // Vision returns where the gyro said the camera is: the overlay must not swing back.
        val truth = RotationMath.rotateAboutCameraCentre(wall, RotationMath.cameraRotationDelta(yaw(0.0).quaternion, yaw(8.0).quaternion))
        fronts.single().next = match(truth)
        advance(50)
        val back = s.onFrame(frame(yaw(8.0)))
        assertEquals(TrackingState.LOCKED, back.state)
        assertEquals(0f, RigidMath.rotationDeltaDeg(bridged.pose!!.cameraFromMap, back.pose!!.cameraFromMap), 0.05f)
        assertEquals(0f, RigidMath.rotationDeltaDeg(back.acceptedCameraFromMap!!, back.pose!!.cameraFromMap), 0.05f)
    }

    @Test
    fun `Config maxBridgeMs is the only bridge limit`() {
        val s = session(
            PhotosphereTrackingSession.Config(maxBridgeMs = 400L, trackingState = TrackingStateConfig(maxBridgeMs = 50L)),
        )
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        repeat(2) { s.onFrame(frame()) }
        fronts.single().next = null
        advance(100)
        assertEquals(TrackingState.IMU_BRIDGE, s.onFrame(frame()).state)
        advance(200) // 300 ms since the lock: past trackingState.maxBridgeMs, inside Config.maxBridgeMs
        assertEquals(TrackingState.IMU_BRIDGE, s.onFrame(frame()).state)
        advance(200) // 500 ms
        assertEquals(TrackingState.REACQUIRING, s.onFrame(frame()).state)
    }

    @Test
    fun `the base map follows a display rotation of the tracked frame`() {
        val s = session()
        fun f(att: AttitudeSample, displayDeg: Int) = PhotosphereTrackingSession.Frame(
            textured, intrinsics, nowNs, att,
            cameraFromDevice = com.hereliesaz.sphereslam.attitude.DeviceCameraRotation.cameraFromDeviceForDisplayFrame(displayDeg),
        )
        val upright = s.onFrame(f(yaw(0.0), 0)).baseCameraFromMap!!
        // Same device attitude, display turned 90°: the image (camera frame) is rolled by 90°.
        val rolled = s.onFrame(f(yaw(0.0), 90)).baseCameraFromMap!!
        val c90 = com.hereliesaz.sphereslam.attitude.DeviceCameraRotation.cameraFromDeviceForDisplayFrame(90)
        val uprightR = DoubleArray(9) { upright[(it % 3) * 4 + it / 3].toDouble() }
        for (row in 0..2) for (col in 0..2) {
            val expected = (0..2).sumOf { k -> c90[row * 3 + k] * uprightR[k * 3 + col] }
            assertEquals(expected.toFloat(), rolled[col * 4 + row], 1e-6f)
        }
        // Plus a yaw: C · R(q)ᵀ · W, absolute.
        val turned = s.onFrame(f(yaw(20.0), 90)).baseCameraFromMap!!
        val expected = PhotosphereTrackingSession.absoluteCameraRotation(c90, yaw(20.0).quaternion)
        for (row in 0..2) for (col in 0..2) assertEquals(expected[row * 3 + col], turned[col * 4 + row], 1e-6f)
        assertEquals(0f, RigidMath.translationNorm(turned), 0f)
    }

    @Test
    fun `the base map is gravity-level and north-referenced whatever the first frame`() {
        // Device in its natural orientation looking north at the horizon: device→ENU columns are
        // x→east, y→up, z→south (+90° about east). That camera IS the map frame (−Z north, +Y up).
        val half = (Math.sqrt(0.5)).toFloat()
        val northQ = floatArrayOf(half, 0f, 0f, half)
        val nadirFirst = session()
        // Started looking straight down (identity device attitude: device −Z = down).
        nadirFirst.onFrame(PhotosphereTrackingSession.Frame(textured, intrinsics, nowNs, AttitudeSample(floatArrayOf(0f, 0f, 0f, 1f), nowNs)))
        val north = nadirFirst.onFrame(
            PhotosphereTrackingSession.Frame(textured, intrinsics, nowNs, AttitudeSample(northQ, nowNs)),
        ).baseCameraFromMap!!
        assertEquals(0f, RigidMath.rotationDeltaDeg(RigidMath.identity(), north), 0.01f)
    }

    @Test
    fun `the page table and the live matcher stay consistent`() {
        val s = session()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        fronts.single().next = match(viewAt(0.6f, 0f, 1f))
        repeat(2) { s.onFrame(frame()) }
        val grown = rec.grown.single()
        assertEquals(2, fronts.size)

        // Handing the grown pages back changes nothing.
        s.updatePrecisionLayer(reference, atlasPages = s.atlasPages)
        assertEquals(2, fronts.size)
        assertFalse(fronts[1].closed)
        // Equal copies (e.g. reloaded from storage) are the same pages too.
        val copy = PhotosphereTrackingSession.AtlasPage(
            grown.pageNo, grown.luma, grown.width, grown.height, grown.referenceWidthUnits, grown.physicallyMetric, grown.canonicalFromPage,
        )
        s.updatePrecisionLayer(reference, atlasPages = listOf(copy))
        assertEquals(2, fronts.size)

        // Omitting them rebuilds a matcher that holds exactly page 0 …
        s.updatePrecisionLayer(reference, atlasPages = emptyList())
        assertTrue(s.atlasPages.isEmpty())
        s.onFrame(frame())
        assertEquals(listOf(0), fronts.last().pages.map { it.first })
        // … and later growth never reuses a page number (the fake rejects duplicates like KPM does).
        fronts.last().next = match(viewAt(0.6f, 0f, 1f))
        advance(PhotosphereAtlasGrowth.MIN_GROW_INTERVAL_MS + 1)
        repeat(2) { s.onFrame(frame()) }
        assertEquals(2, rec.grown.size)
        assertEquals(listOf(2), s.atlasPages.map { it.pageNo })
        assertTrue(rec.diagnostics.none { it.contains("atlas growth failed") })

        // New host pages with the same reference are loaded.
        val hostPage = PhotosphereTrackingSession.AtlasPage(
            7, noise(100 * 100, 4), 100, 100, 0.5f, false, RigidMath.identity().also { it[12] = -1f },
        )
        s.updatePrecisionLayer(reference, atlasPages = s.atlasPages + hostPage)
        s.onFrame(frame())
        assertEquals(listOf(0, 2, 7), fronts.last().pages.map { it.first })
    }

    @Test
    fun `precision layer swaps rebuild only on change`() {
        val s = session()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        val map = viewAt(1f, 0f, 0f)
        s.updatePrecisionLayer(reference, mapFromFingerprint = map)
        s.onFrame(frame())
        assertEquals(2, fronts.size) // map changed
        s.updatePrecisionLayer(reference, mapFromFingerprint = map.copyOf())
        s.onFrame(frame())
        assertEquals(2, fronts.size) // same map
        s.updatePrecisionLayer(reference, mapFromFingerprint = map, forceRebuild = true)
        s.onFrame(frame())
        assertEquals(3, fronts.size)
        s.updatePrecisionLayer(null)
        assertNull(s.onFrame(frame()).pose)
        assertTrue(fronts.last().closed)
    }

    @Test
    fun `an accepted off-centre pose grows a rectified atlas page by rebuilding when the matcher cannot fork`() {
        val s = session()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        fronts.single().next = match(viewAt(0.6f, 0f, 1f))
        repeat(2) { s.onFrame(frame()) }
        val grown = rec.grown.single()
        assertEquals(1, grown.pageNo)
        assertEquals(0.6f, grown.canonicalFromPage[12], 1e-4f)
        assertEquals(1f, grown.referenceWidthUnits, 1e-4f)
        assertEquals(500, grown.width)
        assertEquals(375, grown.height)
        assertEquals(2, fronts.size)
        assertTrue(fronts[0].closed) // the replaced matcher is retired
        assertFalse(fronts[1].closed)
        assertEquals(listOf(0, 1), fronts[1].pages.map { it.first })
        assertEquals(listOf(1), s.atlasPages.map { it.pageNo })
        fronts[1].next = match(viewAt(0.6f, 0f, 1f))
        s.onFrame(frame())
        assertEquals(1, fronts[1].matches) // the swapped-in matcher is the live one
        assertEquals(1, rec.grown.size) // growth interval
        // A rebuild re-registers the grown page.
        s.onFrame(frame(k = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480).copy(fx = 501f)))
        assertEquals(listOf(0, 1), fronts.last().pages.map { it.first })
    }

    @Test
    fun `the front end gets frame context, gate verdicts and a gyro-predicted pose`() {
        val s = session()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame(yaw(0.0)))
        val fe = fronts.single()
        assertNull(fe.inputs.single().predictedCameraFromCanonical) // nothing accepted yet
        assertTrue(fe.verdicts.isEmpty()) // no pose, no verdict
        assertEquals(nowNs / 1_000_000L, fe.inputs.single().nowMs)

        val wall = viewAt(0f, 0f, 2f)
        fe.next = match(wall)
        repeat(2) { s.onFrame(frame(yaw(0.0))) }
        assertEquals(listOf(true, true), fe.verdicts)

        // The next frame's prediction is the accepted pose rotated by the gyro change since.
        fe.next = null
        s.onFrame(frame(yaw(10.0)))
        val predicted = fe.inputs.last().predictedCameraFromCanonical!!
        assertEquals(10f, RigidMath.rotationAngleDeg(RigidMath.multiply(predicted, RigidMath.rigidInverse(wall))), 1e-2f)
        assertTrue(fe.inputs.last().lumaBytes.contentEquals(textured))

        // A gated-out pose is reported back as rejected.
        fe.next = match(wall, inliers = 1)
        s.onFrame(frame(yaw(10.0)))
        assertEquals(false, fe.verdicts.last())
    }

    @Test
    fun `flow-tracked poses carry their method and never grow the atlas`() {
        val s = session()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        fronts.single().next = PlanarMatchResult(0, viewAt(0.6f, 0f, 1f), 1f, 30, VisualMatchMethod.FLOW)
        val r = run {
            s.onFrame(frame())
            s.onFrame(frame())
        }
        assertEquals(VisualMatchMethod.FLOW, r.pose!!.matchMethod)
        assertEquals(PhotosphereTrackingSession.PoseSource.KPM, r.pose!!.source)
        assertTrue(rec.grown.isEmpty())
        // The same pose from a KPM solve does grow it.
        fronts.single().next = PlanarMatchResult(0, viewAt(0.6f, 0f, 1f), 1f, 30, VisualMatchMethod.KPM_FOCUSED)
        assertEquals(VisualMatchMethod.KPM_FOCUSED, s.onFrame(frame()).pose!!.matchMethod)
        assertEquals(1, rec.grown.size)
    }


    @Test
    fun `growth forks the live matcher and swaps the fork in`() {
        forkable = true
        val s = session()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        val live = fronts.single()
        live.next = match(viewAt(0.6f, 0f, 1f))
        repeat(2) { s.onFrame(frame()) }
        assertEquals(1, rec.grown.single().pageNo)
        assertEquals(2, fronts.size)
        val fork = fronts[1]
        assertEquals(listOf(0), live.pages.map { it.first }) // the live matcher is never added to
        assertEquals(listOf(0, 1), fork.pages.map { it.first })
        assertTrue(live.closed)
        assertFalse(fork.closed)
    }

    @Test
    fun `a weak grown page is dropped without touching or rebuilding the live matcher`() {
        forkable = true
        featuresFor = { if (it == 0) 100 else 5 }
        val s = session()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        val live = fronts.single()
        live.next = match(viewAt(0.6f, 0f, 1f))
        repeat(2) { s.onFrame(frame()) }
        assertTrue(rec.grown.isEmpty())
        assertTrue(s.atlasPages.isEmpty())
        assertEquals(2, fronts.size)
        assertTrue(fronts[1].closed) // the rejected fork
        assertFalse(live.closed)
        assertEquals(listOf(0), live.pages.map { it.first })
        assertTrue(rec.diagnostics.any { it.startsWith("SphereSLAM atlas growth rejected page=1 features=5") })
        val before = live.matches
        val r = s.onFrame(frame())
        assertEquals(2, fronts.size) // no full rebuild
        assertEquals(before + 1, live.matches)
        assertNotNull(r.pose)
    }

    @Test
    fun `builds and growth run off the frame thread and swap in when ready`() {
        forkable = true
        val worker = QueueExecutor()
        val s = session(executor = worker)
        s.updatePrecisionLayer(reference)
        // The first build is in flight: frames still get the base pose, but no planar pose.
        val waiting = s.onFrame(frame())
        assertNull(waiting.pose)
        assertNotNull(waiting.baseCameraFromMap)
        assertTrue(fronts.isEmpty())
        s.onFrame(frame())
        assertEquals(1, worker.queue.size) // not re-submitted while building
        worker.runAll()
        assertTrue(rec.ready.isEmpty()) // adopted on the frame thread, not the worker
        s.onFrame(frame())
        assertEquals(1, rec.ready.size)
        val live = fronts.single()
        live.next = match(viewAt(0.6f, 0f, 1f))
        repeat(2) { s.onFrame(frame()) } // confirm + lock: growth submitted
        assertEquals(1, worker.queue.size)
        assertEquals(1, fronts.size) // nothing built on the frame thread
        val matchesBefore = live.matches
        s.onFrame(frame()) // the analyzer keeps matching on the live matcher meanwhile
        assertEquals(matchesBefore + 1, live.matches)
        worker.runAll()
        assertEquals(2, fronts.size)
        assertTrue(rec.grown.isEmpty())
        s.onFrame(frame()) // the swap
        assertEquals(1, rec.grown.single().pageNo)
        assertTrue(worker.queue.isNotEmpty()) // the old matcher's close is queued on the worker...
        assertFalse(live.closed)
        worker.runAll()
        assertTrue(live.closed) // ...and runs there
    }

    @Test
    fun `a growth finished for a dropped matcher is discarded`() {
        forkable = true
        val worker = QueueExecutor()
        val s = session(executor = worker)
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        worker.runAll()
        s.onFrame(frame())
        fronts.single().next = match(viewAt(0.6f, 0f, 1f))
        repeat(2) { s.onFrame(frame()) }
        assertEquals(1, worker.queue.size) // growth pending
        s.updatePrecisionLayer(reference, forceRebuild = true) // invalidates it
        worker.runAll() // growth job (forks the old matcher before its queued close) + the close
        val fork = fronts[1]
        assertTrue(fork.closed)
        s.onFrame(frame())
        worker.runAll()
        s.onFrame(frame())
        assertTrue(rec.grown.isEmpty())
        assertTrue(s.atlasPages.isEmpty())
        assertEquals(listOf(0), fronts.last().pages.map { it.first })
        assertFalse(fronts.last().closed)
    }

    @Test
    fun `close retires matchers through the worker`() {
        val worker = QueueExecutor()
        val s = session(executor = worker)
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        worker.runAll()
        s.onFrame(frame())
        s.close()
        assertFalse(fronts.single().closed)
        worker.runAll()
        assertTrue(fronts.single().closed)
    }

    @Test
    fun `the periodic diagnostic carries per-stage timing`() {
        val s = session(PhotosphereTrackingSession.Config(metricsIntervalMs = 1_000L))
        s.updatePrecisionLayer(reference)
        s.onFrame(PhotosphereTrackingSession.Frame(textured, intrinsics, nowNs, yaw(0.0), convertDurationNs = 2_000_000L))
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        repeat(2) { s.onFrame(frame()) } // confirm + lock; the first lock reports at once
        val line = rec.diagnostics.last { it.startsWith("SphereSLAM standalone KPM") }
        assertTrue(line, line.startsWith("SphereSLAM standalone KPM page=0 inliers=30"))
        assertTrue(line, line.contains("timing{frames=3 attempts=3 matched=2 convertMs=2.0/2.0 setupMs="))
        assertTrue(line, line.contains(" matchMs=") && line.contains(" keyframeMs=") && line.contains(" growthMs="))
        fun kpmLines() = rec.diagnostics.count { it.startsWith("SphereSLAM standalone KPM") }
        val before = kpmLines()
        s.onFrame(frame())
        assertEquals("nothing more inside the interval", before, kpmLines())
        advance(1_100)
        fronts.single().next = null
        s.onFrame(frame()) // window: the locked frame above + this miss
        val windowed = rec.diagnostics.last { it.startsWith("SphereSLAM standalone KPM") }
        assertTrue(windowed, windowed.contains("page=0") && windowed.contains("timing{frames=2 attempts=2 matched=1 convertMs=-"))
        advance(1_100)
        s.onFrame(frame())
        val idle = rec.diagnostics.last { it.startsWith("SphereSLAM standalone KPM") }
        assertTrue(idle, idle.startsWith("SphereSLAM standalone KPM no-lock timing{frames=1 attempts=1 matched=0"))
    }

    @Test
    fun `a display pose predictor receives every rendered pose and is cleared without one`() {
        val s = session()
        val predictor = DisplayPosePredictor()
        s.displayPosePredictor = predictor
        s.updatePrecisionLayer(reference)
        s.onFrame(frame(yaw(0.0)))
        assertFalse(predictor.hasPose)
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        repeat(2) { s.onFrame(frame(yaw(10.0))) }
        val rendered = s.onFrame(frame(yaw(10.0))).pose!!
        assertEquals(rendered.timestampNs, predictor.poseTimestampNs)
        // Unrotated: the rendered pose itself; rotated 5° further: the bridge's answer.
        assertArrayEquals(rendered.cameraFromMap, predictor.predict(yaw(10.0).quaternion), 1e-5f)
        val turned = predictor.predict(yaw(15.0).quaternion)!!
        assertEquals(5f, RigidMath.rotationDeltaDeg(rendered.cameraFromMap, turned), 1e-2f)
        assertArrayEquals(RigidMath.cameraCentre(rendered.cameraFromMap), RigidMath.cameraCentre(turned), 1e-5f)
        // Lost past the bridge window: the held pose keeps feeding it.
        fronts.single().next = null
        advance(1_000)
        val held = s.onFrame(frame(yaw(10.0))).pose!!
        assertEquals(PhotosphereTrackingSession.PoseSource.HELD, held.source)
        assertEquals(held.timestampNs, predictor.poseTimestampNs)
    }

    @Test
    fun `without holding the display pose predictor is cleared past the bridge window`() {
        val s = session(PhotosphereTrackingSession.Config(holdOffWall = false))
        val predictor = DisplayPosePredictor()
        s.displayPosePredictor = predictor
        s.updatePrecisionLayer(reference)
        s.onFrame(frame(yaw(0.0)))
        fronts.single().next = match(viewAt(0f, 0f, 2f))
        repeat(3) { s.onFrame(frame(yaw(10.0))) }
        assertTrue(predictor.hasPose)
        fronts.single().next = null
        advance(1_000)
        s.onFrame(frame(yaw(10.0)))
        assertFalse(predictor.hasPose)
    }

    @Test
    fun `atlas growth can be disabled`() {
        val s = session(PhotosphereTrackingSession.Config(atlasGrowth = false))
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        fronts.single().next = match(viewAt(0.6f, 0f, 1f))
        repeat(3) { s.onFrame(frame()) }
        assertTrue(rec.grown.isEmpty())
    }

    @Test
    fun `close stops processing and releases the matcher`() {
        val s = session()
        s.updatePrecisionLayer(reference)
        s.onFrame(frame())
        s.close()
        assertTrue(fronts.single().closed)
        assertNull(s.onFrame(frame()).baseCameraFromMap)
    }

    @Test
    fun `static helpers`() {
        val proj = com.hereliesaz.sphereslam.camera.ProjectionMatrix.buildFrom(intrinsics)
        assertEquals(2f * 2f * (240f / 500f) / 480f, PhotosphereTrackingSession.unitsPerPixel(viewAt(0f, 0f, 2f), proj, 480), 1e-6f)
        assertEquals(0f, PhotosphereTrackingSession.unitsPerPixel(viewAt(0f, 0f, 2f), proj, 0), 0f)
        val cameraFromMap = RigidMath.identity().also { System.arraycopy(rotY(30f), 0, it, 0, 16) }
        val fp = viewAt(0.3f, 0f, 2f)
        val m = PhotosphereTrackingSession.solveMapFromFingerprint(cameraFromMap, fp)!!
        assertArrayEquals(fp, RigidMath.multiply(cameraFromMap, m), 1e-5f)
    }

    private fun rotY(deg: Float): FloatArray {
        val r = RotationMath.toRotationMatrix3x3(
            floatArrayOf(0f, kotlin.math.sin(Math.toRadians(deg / 2.0)).toFloat(), 0f, kotlin.math.cos(Math.toRadians(deg / 2.0)).toFloat()),
        )
        val out = RigidMath.identity()
        for (row in 0..2) for (col in 0..2) out[col * 4 + row] = r[row * 3 + col]
        return out
    }
}

package com.hereliesaz.sphereslam.reloc

import android.os.SystemClock
import com.hereliesaz.sphereslam.PhotosphereFingerprintFrame
import com.hereliesaz.sphereslam.PhotosphereKeyframe
import com.hereliesaz.sphereslam.SphereSlamPoseMath
import com.hereliesaz.sphereslam.SphereSlamStandaloneSession
import com.hereliesaz.sphereslam.attitude.AttitudeRotationBridge
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.attitude.DeviceCameraRotation
import com.hereliesaz.sphereslam.attitude.DisplayPosePredictor
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.camera.ProjectionMatrix
import com.hereliesaz.sphereslam.math.RigidMath
import com.hereliesaz.sphereslam.math.RotationMath
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs

/**
 * Standalone (no-ARCore) photosphere tracking runtime: frame in, pose out.
 *
 * Feed each display-upright camera frame — tightly packed luma, its [CameraIntrinsics], timestamp,
 * device attitude and the body→camera rotation — to [onFrame]. The session keeps two layers:
 *
 * - **Base photosphere map.** The first usable attitude sample defines the map orientation; every
 *   later frame gets a rotation-only GL `camera_from_map` from the attitude change
 *   ([FrameResult.baseCameraFromMap]). Translation stays exactly zero (fixed-standpoint model; never
 *   integrated from the accelerometer). Quality-gated [PhotosphereKeyframe]s are emitted at a fixed
 *   cadence through [Callbacks.onKeyframe], before any wall reference exists.
 * - **Planar precision layer.** With a wall [ReferenceImage] (plus optional atlas pages) set through
 *   [updatePrecisionLayer], each frame is matched by SphereSLAM's planar KPM
 *   ([SphereSlamStandaloneSession]) and gated by [RobustTrackingLoop] (age → acceptance → optional
 *   [correctAcceptedPose] → smoothing → state hysteresis). The KPM pose lives in the fingerprint
 *   (canonical wall) frame and is rebased into the base map through `map_from_fingerprint`, solved
 *   once per reference from a same-frame base + KPM pair (`inverse(camera_from_map) ·
 *   camera_from_fingerprint`, [Callbacks.onMapFromFingerprintSolved]) or supplied by the host.
 *
 * A brief visual miss after a lock is held for at most [Config.maxBridgeMs] by a gyro rotation bridge
 * (camera centre held). An accepted pose may grow a multi-page KPM atlas ([PhotosphereAtlasGrowth]):
 * a perspective-rectified wall patch next to the existing pages, kept only when it passes
 * [TargetQuality] and the KPM feature floor ([Callbacks.onAtlasPageAdded]). The planar session is
 * rebuilt whenever the frame intrinsics change.
 *
 * Failures are classified ([TrackingFailureClassifier]) and reported once per change of reason. Any
 * exception thrown while processing a frame (for example [ReferenceTooWeakException] or a native
 * failure) is terminal: the session reports it, enters [TrackingState.FATAL] and ignores later frames.
 *
 * Camera plumbing (CameraX `ImageProxy`, Camera2 characteristics), relocalizer/feature-map feeds,
 * depth inference and UI belong to the host: it reads [FrameResult] after each call.
 *
 * Thread-safe (methods synchronize), but drive [onFrame] from one camera-analysis worker. Close to
 * release the native matcher. Ported from GraffitiXR `SphereSlamStandaloneTrackingAnalyzer`.
 *
 * @param correctAcceptedPose optional post-acceptance correction of the accepted raw
 *   `camera_from_fingerprint` (GL, column-major), applied before smoothing; gating is unaffected (see
 *   [RobustTrackingLoop]). Return the input to leave it unchanged.
 * @param elapsedRealtimeNanos the clock frame timestamps are compared against and cadences are timed
 *   with; `SystemClock.elapsedRealtimeNanos` by default (CameraX `REALTIME` timestamps share it).
 */
@ExperimentalSphereSlamRelocApi
class PhotosphereTrackingSession internal constructor(
    private val config: Config,
    private val callbacks: Callbacks,
    private val correctAcceptedPose: ((FloatArray) -> FloatArray)?,
    private val elapsedRealtimeNanos: () -> Long,
    private val frontEndFactory: PlanarFrontEndFactory,
) : AutoCloseable {

    constructor(
        config: Config = Config(),
        callbacks: Callbacks = Callbacks(),
        correctAcceptedPose: ((FloatArray) -> FloatArray)? = null,
        elapsedRealtimeNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    ) : this(config, callbacks, correctAcceptedPose, elapsedRealtimeNanos, PlanarFrontEndFactory.KPM)

    /**
     * @property timestampSource the camera's timestamp source (Camera2 `SENSOR_INFO_TIMESTAMP_SOURCE`);
     *   frame age is only checked for [CameraTimestampSource.REALTIME].
     * @property maxBridgeMs longest gyro bridge after the last accepted frame, ms.
     * @property keyframeIntervalMs photosphere keyframe cadence, ms.
     * @property atlasGrowth whether accepted poses may grow the KPM atlas.
     * @property metricsIntervalMs cadence of the KPM match-metrics + per-stage timing diagnostic, ms.
     */
    data class Config(
        val timestampSource: CameraTimestampSource = CameraTimestampSource.UNKNOWN,
        val acceptance: PoseAcceptanceConfig = PoseAcceptanceConfig(),
        val observationAge: ObservationAgeConfig = ObservationAgeConfig(),
        val trackingState: TrackingStateConfig = TrackingStateConfig(),
        val targetQuality: TargetQualityConfig = TargetQualityConfig(),
        val maxBridgeMs: Long = 400L,
        val keyframeIntervalMs: Long = 250L,
        val atlasGrowth: Boolean = true,
        val metricsIntervalMs: Long = 5_000L,
    ) {
        init {
            require(maxBridgeMs >= 0L && keyframeIntervalMs >= 0L && metricsIntervalMs >= 0L)
        }
    }

    /** Host callbacks, all invoked synchronously on the [onFrame] / [updatePrecisionLayer] caller's thread. */
    class Callbacks(
        val onKeyframe: (PhotosphereKeyframe) -> Unit = {},
        val onMapFromFingerprintSolved: (mapFromFingerprint: FloatArray) -> Unit = {},
        val onReferenceReady: (RegisteredPage) -> Unit = {},
        val onAtlasPageAdded: (page: AtlasPage, attitude: AttitudeSample?) -> Unit = { _, _ -> },
        val onFailure: (TrackingFailureEvent) -> Unit = {},
        val onTrackingStateChanged: (TrackingState) -> Unit = {},
        val onDiagnostic: (String) -> Unit = {},
        val onFatalError: (Throwable) -> Unit = {},
    )

    /**
     * One camera frame.
     *
     * @param luma tightly packed, display-upright luma (`intrinsics.width * intrinsics.height`). Not
     *   copied: do not mutate it during [onFrame].
     * @param intrinsics the tracked frame's intrinsics; its size is the frame size.
     * @param timestampNs camera timestamp, ns.
     * @param attitude latest device attitude sampled before the frame is solved, or null.
     * @param cameraFromDevice row-major 3×3 GL-camera-from-device for the tracked image
     *   ([DeviceCameraRotation.cameraFromDevice] with the rotation applied to the buffer).
     * @param headingDeg / [elevationDeg] / [rollDeg] absolute camera attitude stamped on keyframes
     *   (`CameraAttitudeProvider`); no keyframes are emitted while heading or elevation is null.
     * @param convertDurationNs optional host-measured time spent producing [luma] (Y-plane pack /
     *   rotate), reported as `convertMs` in the periodic timing diagnostic; negative when unknown.
     */
    class Frame(
        val luma: ByteArray,
        val intrinsics: CameraIntrinsics,
        val timestampNs: Long,
        val attitude: AttitudeSample?,
        cameraFromDevice: FloatArray = RotationMath.IDENTITY_3X3,
        val headingDeg: Float? = null,
        val elevationDeg: Float? = null,
        val rollDeg: Float? = null,
        val convertDurationNs: Long = -1L,
    ) {
        val cameraFromDevice: FloatArray = cameraFromDevice.copyOf()

        init {
            require(intrinsics.isValid) { "intrinsics must be valid" }
            require(luma.size == intrinsics.width * intrinsics.height) { "luma must be width*height bytes" }
            require(cameraFromDevice.size == 9 && cameraFromDevice.all { it.isFinite() }) {
                "cameraFromDevice must be a finite row-major 3x3"
            }
        }
    }

    /**
     * Page 0 of the wall atlas: the captured planar reference. [referenceWidthUnits] is metres only
     * when [physicallyMetric]; a normalized width (e.g. 1) gives self-consistent visual registration.
     */
    class ReferenceImage(
        luma: ByteArray,
        val width: Int,
        val height: Int,
        val referenceWidthUnits: Float = 1f,
        val physicallyMetric: Boolean = false,
    ) {
        private val lumaValue = luma.copyOf()

        init {
            require(width > 0 && height > 0 && luma.size == width * height)
            require(referenceWidthUnits.isFinite() && referenceWidthUnits > 0f)
        }

        /** A fresh copy of the luma. */
        val luma: ByteArray get() = lumaValue.copyOf()
        internal fun lumaRef(): ByteArray = lumaValue
    }

    /** An additional atlas page (`pageNo > 0`) placed in the canonical wall frame by [canonicalFromPage]. */
    class AtlasPage(
        val pageNo: Int,
        luma: ByteArray,
        val width: Int,
        val height: Int,
        val referenceWidthUnits: Float,
        val physicallyMetric: Boolean,
        canonicalFromPage: FloatArray,
    ) {
        private val lumaValue = luma.copyOf()
        private val canonicalFromPageValue = canonicalFromPage.copyOf()

        init {
            require(pageNo > 0)
            require(width > 0 && height > 0 && luma.size == width * height)
            require(referenceWidthUnits.isFinite() && referenceWidthUnits > 0f)
            require(canonicalFromPage.size == 16 && canonicalFromPage.all { it.isFinite() })
        }

        /** A fresh copy of the luma. */
        val luma: ByteArray get() = lumaValue.copyOf()

        /** A fresh copy of the column-major canonical-from-page transform. */
        val canonicalFromPage: FloatArray get() = canonicalFromPageValue.copyOf()

        /** Page height in [referenceWidthUnits]' units. */
        val referenceHeightUnits: Float get() = referenceWidthUnits * height / width

        internal fun lumaRef(): ByteArray = lumaValue
        internal fun canonicalFromPageRef(): FloatArray = canonicalFromPageValue
    }

    /** A page the planar matcher registered. */
    data class RegisteredPage(
        val pageNo: Int,
        val featureCount: Int,
        val geometry: SphereSlamPoseMath.PageGeometry,
        val physicallyMetric: Boolean,
    )

    /** Where a [TrackedPose] came from. */
    enum class PoseSource {
        /** Planar KPM match of this frame. */
        KPM,

        /** Gyro-bridged hold of the last KPM pose. */
        IMU_BRIDGE,
    }

    /**
     * A pose to render.
     *
     * @property cameraFromMap column-major GL camera-from-base-map view.
     * @property projection [ProjectionMatrix.buildFrom] of [intrinsics] (the full tracked frame).
     * @property unitsPerPixel wall units spanned by one vertical frame pixel at the target depth.
     * @property observationAgeMs trusted frame age, or null.
     */
    class TrackedPose internal constructor(
        cameraFromMap: FloatArray,
        projection: FloatArray,
        val intrinsics: CameraIntrinsics,
        val timestampNs: Long,
        val pageNo: Int,
        val reprojectionError: Float,
        val inlierCount: Int,
        val unitsPerPixel: Float,
        val observationAgeMs: Float?,
        val matchDurationMs: Float,
        val source: PoseSource,
    ) {
        private val view = cameraFromMap.copyOf()
        private val proj = projection.copyOf()

        val cameraFromMap: FloatArray get() = view.copyOf()
        val projection: FloatArray get() = proj.copyOf()

        internal fun bridged(delta: FloatArray, projection: FloatArray, intrinsics: CameraIntrinsics, timestampNs: Long) =
            TrackedPose(
                RotationMath.rotateAboutCameraCentre(view, delta), projection, intrinsics, timestampNs, pageNo,
                reprojectionError, inlierCount, unitsPerPixel, observationAgeMs, matchDurationMs, PoseSource.IMU_BRIDGE,
            )
    }

    /**
     * The outcome of one [onFrame].
     *
     * @property pose what to render, or null.
     * @property state tracking state after the frame.
     * @property baseCameraFromMap rotation-only base photosphere pose, or null without attitude.
     * @property visualAccepted whether the planar pose passed [RobustTrackingLoop] this frame (also
     *   true for an accepted pose that is still confirming a lock).
     * @property acceptedCameraFromFingerprint the raw locked KPM camera-from-fingerprint (GL), or null.
     * @property acceptedCameraFromMap that pose rebased into the base map, or null when no
     *   `map_from_fingerprint` is known — e.g. to hand a relocalizer a current camera pose.
     * @property failure this frame's classified failure (reported through [Callbacks.onFailure] only
     *   when the reason changes), or null.
     * @property projection the projection of this frame's intrinsics.
     */
    class FrameResult internal constructor(
        val pose: TrackedPose?,
        val state: TrackingState,
        baseCameraFromMap: FloatArray?,
        val visualAccepted: Boolean,
        acceptedCameraFromFingerprint: FloatArray?,
        acceptedCameraFromMap: FloatArray?,
        val failure: TrackingFailureEvent?,
        projection: FloatArray?,
    ) {
        private val base = baseCameraFromMap?.copyOf()
        private val acceptedFp = acceptedCameraFromFingerprint?.copyOf()
        private val acceptedMap = acceptedCameraFromMap?.copyOf()
        private val proj = projection?.copyOf()

        val baseCameraFromMap: FloatArray? get() = base?.copyOf()
        val acceptedCameraFromFingerprint: FloatArray? get() = acceptedFp?.copyOf()
        val acceptedCameraFromMap: FloatArray? get() = acceptedMap?.copyOf()
        val projection: FloatArray? get() = proj?.copyOf()
    }

    private val agePolicy = ObservationAgePolicy(config.observationAge)
    private val acceptancePolicy = PoseAcceptancePolicy(config.acceptance)
    private val stabilizer = PoseStabilizer()
    private val stateMachine = TrackingStateMachine(config.trackingState)

    private var referenceImage: ReferenceImage? = null
    private var mapFromFingerprintValue: FloatArray? = null
    private val runtimeAtlasPages = linkedMapOf<Int, AtlasPage>()
    private var emittedMapBridgeForReference: ReferenceImage? = null

    private var frontEnd: PlanarFrontEnd? = null
    private var sessionKey: CameraIntrinsics? = null
    private var robustLoop: RobustTrackingLoop? = null

    private var imuBridge: AttitudeRotationBridge? = null
    private var imuBridgeReferenceAtMs = -1L
    private var lastGood: TrackedPose? = null

    private var photosphereReferenceQuaternion: FloatArray? = null
    private var photosphereCameraFromDevice: FloatArray? = null
    private var lastKeyframeMs = Long.MIN_VALUE
    private var lastAtlasGrowthMs = Long.MIN_VALUE
    private var lastMetricsMs = Long.MIN_VALUE

    private var lastFailureReason: TrackingFailureReason? = null
    private var lastReportedState: TrackingState? = null
    private var frameBuffer: ByteBuffer? = null
    private val timing = StageTiming()
    private var lastKpmMetrics: TrackedPose? = null
    private var metricsWindowStartMs = Long.MIN_VALUE
    private var closed = false
    private var fatal = false

    /** Current tracking state. */
    val trackingState: TrackingState @Synchronized get() = stateMachine.state

    /** True after a frame failed terminally. */
    val isFatal: Boolean @Synchronized get() = fatal

    /** Whether a wall reference is set. */
    val hasReference: Boolean @Synchronized get() = referenceImage != null

    /** The current `map_from_fingerprint` (column-major), solved or supplied, or null. */
    val mapFromFingerprint: FloatArray? @Synchronized get() = mapFromFingerprintValue?.copyOf()

    /** The atlas pages beyond page 0 (supplied or grown), by page number. */
    val atlasPages: List<AtlasPage> @Synchronized get() = runtimeAtlasPages.values.toList()

    /**
     * Swap the planar precision layer without touching the base photosphere (map orientation,
     * keyframe cadence and attitude reference survive).
     *
     * The planar session, smoothing and gyro bridge are rebuilt when [reference] is a different
     * object, when [mapFromFingerprint] differs (beyond 1e-5), or when [forceRebuild] is set (e.g. the
     * host's relocalizer seed changed). A new [reference] also re-arms the one-shot
     * `map_from_fingerprint` solve.
     *
     * @param mapFromFingerprint column-major map-from-fingerprint, or null to solve it on the next lock.
     */
    @Synchronized
    fun updatePrecisionLayer(
        reference: ReferenceImage?,
        atlasPages: List<AtlasPage> = emptyList(),
        mapFromFingerprint: FloatArray? = null,
        forceRebuild: Boolean = false,
    ) {
        if (closed) return
        require(atlasPages.map { it.pageNo }.distinct().size == atlasPages.size) { "duplicate atlas page numbers" }
        val referenceChanged = referenceImage !== reference
        val mapChanged = !sameTransform(mapFromFingerprintValue, mapFromFingerprint) &&
            !(mapFromFingerprintValue == null && mapFromFingerprint == null)
        referenceImage = reference
        mapFromFingerprintValue = mapFromFingerprint?.copyOf()
        runtimeAtlasPages.clear()
        atlasPages.sortedBy { it.pageNo }.forEach { runtimeAtlasPages[it.pageNo] = it }

        if (referenceChanged || mapChanged || forceRebuild) {
            if (referenceChanged) emittedMapBridgeForReference = null
            dropPlanarSession()
            callbacks.onDiagnostic(
                "SphereSLAM precision layer updated reference=${reference != null} " +
                    "mapFromFingerprint=${mapFromFingerprint != null}; base photosphere preserved",
            )
        }
    }

    /**
     * Optional display-rate pose hook. When set, every rendered pose is published to it together with
     * its frame's attitude and `cameraFromDevice` ([DisplayPosePredictor.update]), and it is cleared
     * whenever a frame yields no pose — so a GL thread can call
     * `predictInto(attitudeExtrapolator, now, out)` every draw and get the latest vision pose rotated
     * by the attitude change since its frame. Use the same attitude sensor as [Frame.attitude].
     * Safe to set from any thread.
     */
    @Volatile
    var displayPosePredictor: DisplayPosePredictor? = null

    /** Process one frame. After [close] or a fatal failure this returns an empty result. */
    @Synchronized
    fun onFrame(frame: Frame): FrameResult {
        if (closed || fatal) return FrameResult(null, stateMachine.state, null, false, null, null, null, null)
        return try {
            process(frame).also { result ->
                publishToPredictor(frame, result.pose)
                emitMetricsIfDue()
            }
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            fatal = true
            val event = TrackingFailureClassifier.fromThrowable(t)
            reportFailure(event)
            val state = stateMachine.onFatal()
            reportState(state)
            callbacks.onFatalError(t)
            FrameResult(null, state, null, false, null, null, event, null)
        }
    }

    private fun process(frame: Frame): FrameResult {
        val intrinsics = frame.intrinsics
        val frameStartMs = nowMs()
        val projection = ProjectionMatrix.buildFrom(intrinsics)
        timing.frames++
        if (frame.convertDurationNs >= 0L) timing.convert.add(frame.convertDurationNs)

        // Base runtime: the first usable attitude defines the map. Rotation-only until a geometric
        // layer supplies a baseline; zero translation is the fixed-standpoint model, not a guess.
        val cameraFromMap = photosphereCameraFromMap(frame.attitude, frame.cameraFromDevice)
        val keyframeStartNs = elapsedRealtimeNanos()
        captureKeyframe(frame, cameraFromMap, frameStartMs)
        timing.keyframe.add(elapsedRealtimeNanos() - keyframeStartNs)
        val reference = referenceImage
            ?: return FrameResult(null, stateMachine.state, cameraFromMap, false, null, null, null, projection)

        val setupStartNs = elapsedRealtimeNanos()
        val active = ensureSession(intrinsics, reference)
        val direct = directFrame(frame.luma)
        val matchStartNs = elapsedRealtimeNanos()
        timing.setup.add(matchStartNs - setupStartNs)
        val pose = active.match(direct, frame.timestampNs)
        val matchEndNs = elapsedRealtimeNanos()
        timing.match.add(matchEndNs - matchStartNs)
        val matchDurationMs = (matchEndNs - matchStartNs).toFloat() / 1_000_000f
        val observationAge = agePolicy.evaluate(frame.timestampNs, matchEndNs, config.timestampSource)

        val loop = requireNotNull(robustLoop) { "robust tracking loop not initialised" }
        val outcome = loop.onFrame(
            viewMatrix = pose?.cameraFromCanonical,
            inlierCount = pose?.inlierCount ?: 0,
            reprojectionError = pose?.reprojectionError ?: Float.MAX_VALUE,
            frameTimestampNs = frame.timestampNs,
            nowElapsedRealtimeNs = matchEndNs,
            timestampSource = config.timestampSource,
            nowMs = matchEndNs / 1_000_000L,
            bridgeAvailable = canBridge(frame.attitude, matchEndNs / 1_000_000L),
        )

        if (outcome.accepted) {
            val locked = requireNotNull(pose)
            lastFailureReason = null
            reportState(outcome.state)
            if (outcome.state != TrackingState.LOCKED) {
                return FrameResult(null, outcome.state, cameraFromMap, true, null, null, null, projection)
            }

            maybeEmitMapFromFingerprint(reference, cameraFromMap, locked.cameraFromCanonical)

            // The fingerprint never becomes the world: rebase the precision pose into the base map.
            val mapFromFp = mapFromFingerprintValue?.takeIf { it.size == 16 && it.all(Float::isFinite) }
            val acceptedRawMapView = mapFromFp?.let {
                PhotosphereFingerprintFrame.cameraFromMap(locked.cameraFromCanonical, it)
            }
            frame.attitude?.let { commitImuReference(it, frame.cameraFromDevice, frameStartMs) }
            val growthStartNs = elapsedRealtimeNanos()
            maybeGrowAtlas(active, reference, frame, locked)
            timing.growth.add(elapsedRealtimeNanos() - growthStartNs)

            val precisionRenderView = outcome.renderPose!!
            val renderView = mapFromFp?.let {
                PhotosphereFingerprintFrame.cameraFromMap(precisionRenderView, it)
            } ?: cameraFromMap
            if (renderView == null) {
                // No map bridge and no base pose: keep mapping, but never render an untruthful pose.
                return FrameResult(
                    null, outcome.state, null, true, locked.cameraFromCanonical, acceptedRawMapView, null, projection,
                )
            }

            val tracked = TrackedPose(
                cameraFromMap = renderView,
                projection = projection,
                intrinsics = intrinsics,
                timestampNs = frame.timestampNs,
                pageNo = locked.pageNo,
                reprojectionError = locked.reprojectionError,
                inlierCount = locked.inlierCount,
                // Scale is relative to the fingerprint plane, so measure depth in the precision frame.
                unitsPerPixel = unitsPerPixel(precisionRenderView, projection, intrinsics.height),
                observationAgeMs = observationAge.ageMs,
                matchDurationMs = matchDurationMs,
                source = PoseSource.KPM,
            )
            lastGood = tracked
            lastKpmMetrics = tracked
            return FrameResult(
                tracked, outcome.state, cameraFromMap, true, locked.cameraFromCanonical, acceptedRawMapView, null, projection,
            )
        }

        val rejection = outcome.rejection
        val failure = when {
            outcome.stale -> TrackingFailureClassifier.event(
                TrackingFailureReason.STALE_OBSERVATION,
                "ageMs=${observationAge.ageMs} matchMs=$matchDurationMs",
            )
            rejection != null -> TrackingFailureClassifier.fromPoseRejection(
                rejection,
                "page=${pose?.pageNo} inliers=${pose?.inlierCount} error=${pose?.reprojectionError}",
            )
            else -> TrackingFailureClassifier.event(
                TrackingFailureReason.NO_CURRENT_PAGE_MATCH,
                "matchMs=$matchDurationMs frameTimestampNs=${frame.timestampNs}",
            )
        }
        reportFailure(failure)
        reportState(outcome.state)
        val bridged = if (outcome.state == TrackingState.IMU_BRIDGE) {
            bridgeLastGood(frame, projection)
        } else {
            lastGood = null
            null
        }
        return FrameResult(bridged, outcome.state, cameraFromMap, false, null, null, failure, projection)
    }

    private fun photosphereCameraFromMap(attitude: AttitudeSample?, cameraFromDevice: FloatArray): FloatArray? {
        val qNow = attitude?.quaternion ?: return null
        val qRef = photosphereReferenceQuaternion
        val refCameraFromDevice = photosphereCameraFromDevice
        if (qRef == null || refCameraFromDevice == null) {
            photosphereReferenceQuaternion = qNow
            photosphereCameraFromDevice = cameraFromDevice.copyOf()
            return RigidMath.identity()
        }
        // Body-frame delta since the map reference, in the camera frame the reference was captured
        // under (fixed for the map's life, like the gyro bridge's reference).
        val delta = DeviceCameraRotation.cameraDelta(refCameraFromDevice, RotationMath.cameraRotationDelta(qRef, qNow))
        return viewFromRotation(delta)
    }

    private fun maybeEmitMapFromFingerprint(
        reference: ReferenceImage,
        cameraFromMap: FloatArray?,
        cameraFromFingerprint: FloatArray,
    ) {
        if (cameraFromMap == null || emittedMapBridgeForReference === reference) return
        if (cameraFromMap.any { !it.isFinite() } || cameraFromFingerprint.any { !it.isFinite() }) return
        val solved = solveMapFromFingerprint(cameraFromMap, cameraFromFingerprint) ?: return
        // Publish locally first so the very next precision pose is rebased even before the host persists it.
        mapFromFingerprintValue = solved.copyOf()
        emittedMapBridgeForReference = reference
        callbacks.onMapFromFingerprintSolved(solved)
        callbacks.onDiagnostic("SphereSLAM map_from_fingerprint solved from same-frame base+KPM pose")
    }

    private fun captureKeyframe(frame: Frame, cameraFromMap: FloatArray?, nowMs: Long) {
        val heading = frame.headingDeg?.takeIf { it.isFinite() } ?: return
        val elevation = frame.elevationDeg?.takeIf { it.isFinite() } ?: return
        val mapPose = cameraFromMap ?: return
        if (lastKeyframeMs != Long.MIN_VALUE && nowMs - lastKeyframeMs < config.keyframeIntervalMs) return
        // The slot is consumed even when the quality gate rejects the frame.
        lastKeyframeMs = nowMs
        val i = frame.intrinsics
        if (!TargetQuality.analyze(frame.luma, i.width, i.height, config.targetQuality).acceptable) return
        callbacks.onKeyframe(
            PhotosphereKeyframe(
                timestampNs = frame.timestampNs,
                luma = frame.luma,
                width = i.width,
                height = i.height,
                intrinsics = floatArrayOf(i.fx, i.fy, i.cx, i.cy),
                headingDeg = heading,
                elevationDeg = elevation,
                orientationQuaternion = frame.attitude?.quaternion,
                cameraFromMap = mapPose,
                rollDeg = frame.rollDeg?.takeIf { it.isFinite() } ?: 0f,
            ),
        )
    }

    private fun ensureSession(intrinsics: CameraIntrinsics, reference: ReferenceImage): PlanarFrontEnd {
        val existing = frontEnd
        if (existing != null && intrinsics == sessionKey) return existing

        existing?.close()
        frontEnd = null
        clearImuReference()
        lastGood = null
        stabilizer.reset()

        val created = frontEndFactory.create(intrinsics)
        try {
            val root = created.addPage(
                luma = direct(reference.lumaRef()),
                width = reference.width,
                height = reference.height,
                referenceWidthUnits = reference.referenceWidthUnits,
                physicallyMetric = reference.physicallyMetric,
                pageNo = 0,
                canonicalFromPage = RigidMath.identity(),
            )
            if (root.featureCount < config.targetQuality.minKpmFeatures) {
                throw ReferenceTooWeakException(root.featureCount, config.targetQuality.minKpmFeatures)
            }
            callbacks.onDiagnostic(
                "SphereSLAM standalone reference features=${root.featureCount} minimum=${config.targetQuality.minKpmFeatures}",
            )
            runtimeAtlasPages.values.forEach { page ->
                val added = created.addPage(
                    luma = direct(page.lumaRef()),
                    width = page.width,
                    height = page.height,
                    referenceWidthUnits = page.referenceWidthUnits,
                    physicallyMetric = page.physicallyMetric,
                    pageNo = page.pageNo,
                    canonicalFromPage = page.canonicalFromPageRef(),
                )
                callbacks.onDiagnostic("SphereSLAM atlas page=${page.pageNo} features=${added.featureCount}")
            }
            // referenceWidthUnits scales the acceptance translation-jump limit; grown pages share the
            // wall's scale. The stabilizer and state machine are shared across rebuilds.
            robustLoop = RobustTrackingLoop(
                referenceWidthUnits = root.geometry.widthMeters,
                acceptancePolicy = acceptancePolicy,
                agePolicy = agePolicy,
                stabilizer = stabilizer,
                stateMachine = stateMachine,
                correctAcceptedPose = correctAcceptedPose,
            )
            frontEnd = created
            sessionKey = intrinsics
            callbacks.onReferenceReady(root)
            return created
        } catch (t: Throwable) {
            created.close()
            throw t
        }
    }

    private fun maybeGrowAtlas(
        active: PlanarFrontEnd,
        reference: ReferenceImage,
        frame: Frame,
        pose: PlanarMatchResult,
    ) {
        if (!config.atlasGrowth) return
        if (!PhotosphereAtlasGrowth.poseQualifies(pose.inlierCount, pose.reprojectionError, runtimeAtlasPages.size)) return
        val now = nowMs()
        if (lastAtlasGrowthMs != Long.MIN_VALUE && now - lastAtlasGrowthMs < PhotosphereAtlasGrowth.MIN_GROW_INTERVAL_MS) return

        val rootWidth = reference.referenceWidthUnits
        val rootHeight = rootWidth * reference.height / reference.width
        val existing = buildList {
            add(PhotosphereAtlasGrowth.PageWindow(0, 0f, 0f, rootWidth, rootHeight))
            runtimeAtlasPages.values.forEach { page ->
                val m = page.canonicalFromPageRef()
                add(PhotosphereAtlasGrowth.PageWindow(page.pageNo, m[12], m[13], page.referenceWidthUnits, page.referenceHeightUnits))
            }
        }
        val i = frame.intrinsics
        val geometry = PhotosphereAtlasGrowth.propose(
            pose.cameraFromCanonical, i, i.width, i.height, rootWidth, rootHeight, existing,
        ) ?: return

        // Mark the attempt now so a low-texture edge of the wall cannot trigger this cost every frame.
        lastAtlasGrowthMs = now
        val rectified = PhotosphereAtlasGrowth.rectify(frame.luma, i.width, i.height, geometry) ?: return
        if (!TargetQuality.analyze(rectified.luma, rectified.width, rectified.height, config.targetQuality).acceptable) {
            callbacks.onDiagnostic(
                "SphereSLAM atlas growth skipped reason=target-quality center=(${geometry.centerX},${geometry.centerY})",
            )
            return
        }

        val pageNo = (runtimeAtlasPages.keys.maxOrNull() ?: 0) + 1
        val page = AtlasPage(
            pageNo = pageNo,
            luma = rectified.luma,
            width = rectified.width,
            height = rectified.height,
            referenceWidthUnits = geometry.width,
            physicallyMetric = reference.physicallyMetric,
            canonicalFromPage = PhotosphereAtlasGrowth.canonicalFromPage(geometry.centerX, geometry.centerY),
        )
        val added = active.addPage(
            luma = direct(page.lumaRef()),
            width = page.width,
            height = page.height,
            referenceWidthUnits = page.referenceWidthUnits,
            physicallyMetric = page.physicallyMetric,
            pageNo = pageNo,
            canonicalFromPage = page.canonicalFromPageRef(),
        )
        if (added.featureCount < config.targetQuality.minKpmFeatures) {
            // KPM cannot remove a page: rebuild on the next frame from the kept set, without this one.
            active.close()
            frontEnd = null
            sessionKey = null
            callbacks.onDiagnostic(
                "SphereSLAM atlas growth rejected page=$pageNo features=${added.featureCount} " +
                    "minimum=${config.targetQuality.minKpmFeatures}",
            )
            return
        }
        runtimeAtlasPages[pageNo] = page
        callbacks.onDiagnostic(
            "SphereSLAM atlas grew page=$pageNo center=(${geometry.centerX},${geometry.centerY}) features=${added.featureCount}",
        )
        callbacks.onAtlasPageAdded(page, frame.attitude)
    }

    private fun canBridge(attitude: AttitudeSample?, nowMs: Long): Boolean {
        if (lastGood == null || imuBridge == null || imuBridgeReferenceAtMs < 0L) return false
        val elapsed = nowMs - imuBridgeReferenceAtMs
        if (elapsed < 0L || elapsed > config.maxBridgeMs) return false
        return imuBridge?.cameraRotationDelta(attitude?.quaternion) != null
    }

    private fun bridgeLastGood(frame: Frame, projection: FloatArray): TrackedPose? {
        val held = lastGood ?: return null
        val delta = imuBridge?.cameraRotationDelta(frame.attitude?.quaternion) ?: return null
        return held.bridged(delta, projection, frame.intrinsics, frame.timestampNs)
    }

    private fun commitImuReference(sample: AttitudeSample, cameraFromDevice: FloatArray, atMs: Long) {
        imuBridge = AttitudeRotationBridge(cameraFromDevice).also { it.markReference(sample) }
        imuBridgeReferenceAtMs = atMs
    }

    private fun clearImuReference() {
        imuBridge = null
        imuBridgeReferenceAtMs = -1L
    }

    private fun dropPlanarSession() {
        frontEnd?.close()
        frontEnd = null
        sessionKey = null
        robustLoop = null
        lastGood = null
        stabilizer.reset()
        clearImuReference()
    }

    private fun reportFailure(event: TrackingFailureEvent) {
        if (event.reason == lastFailureReason) return
        lastFailureReason = event.reason
        callbacks.onFailure(event)
        callbacks.onDiagnostic(
            "SphereSLAM standalone failure=${event.reason} severity=${event.severity}" +
                if (event.diagnostic.isBlank()) "" else " ${event.diagnostic}",
        )
    }

    private fun reportState(state: TrackingState) {
        if (state == lastReportedState) return
        lastReportedState = state
        callbacks.onTrackingStateChanged(state)
        callbacks.onDiagnostic("SphereSLAM standalone state=$state")
    }

    private fun publishToPredictor(frame: Frame, pose: TrackedPose?) {
        val predictor = displayPosePredictor ?: return
        val attitude = frame.attitude
        if (pose == null || attitude == null) {
            predictor.clear()
            return
        }
        predictor.update(pose.cameraFromMap, attitude, frame.cameraFromDevice, pose.timestampNs)
    }

    /**
     * Every [Config.metricsIntervalMs] (and on the first lock): the last accepted KPM match in the
     * window, or `no-lock`, followed by per-stage timing — `mean/max` ms of convert (host-reported via
     * [Frame.convertDurationNs]), setup (matcher (re)build + frame copy), match, keyframe (quality
     * gate + callback) and inline atlas growth — over the frames since the previous line.
     */
    private fun emitMetricsIfDue() {
        val now = nowMs()
        if (metricsWindowStartMs == Long.MIN_VALUE) metricsWindowStartMs = now
        val due = if (lastMetricsMs == Long.MIN_VALUE) {
            lastKpmMetrics != null || now - metricsWindowStartMs >= config.metricsIntervalMs
        } else {
            now - lastMetricsMs >= config.metricsIntervalMs
        }
        if (!due) return
        lastMetricsMs = now
        val pose = lastKpmMetrics
        lastKpmMetrics = null
        val head = if (pose != null) {
            val age = pose.observationAgeMs?.let { String.format(Locale.US, "%.1f", it) } ?: "unavailable"
            "SphereSLAM standalone KPM page=${pose.pageNo} inliers=${pose.inlierCount} " +
                "error=${pose.reprojectionError} ageMs=$age matchMs=${String.format(Locale.US, "%.1f", pose.matchDurationMs)}"
        } else {
            "SphereSLAM standalone KPM no-lock"
        }
        callbacks.onDiagnostic("$head ${timing.summaryAndReset()}")
    }

    private fun nowMs(): Long = elapsedRealtimeNanos() / 1_000_000L

    private fun directFrame(bytes: ByteArray): ByteBuffer {
        var buffer = frameBuffer
        if (buffer == null || buffer.capacity() < bytes.size) {
            buffer = ByteBuffer.allocateDirect(bytes.size)
            frameBuffer = buffer
        }
        buffer.clear()
        buffer.put(bytes)
        buffer.flip()
        return buffer
    }

    private fun direct(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        dropPlanarSession()
        stateMachine.reset()
        lastFailureReason = null
        lastReportedState = null
        frameBuffer = null
        runtimeAtlasPages.clear()
    }

    companion object {
        /**
         * Wall units spanned by one vertical frame pixel at the depth of [cameraFromTarget]'s target
         * origin (`|t_z| · 2·tan(fovY/2) / height`); 0 for an unusable projection or height.
         */
        fun unitsPerPixel(cameraFromTarget: FloatArray, projection: FloatArray, frameHeightPixels: Int): Float {
            require(cameraFromTarget.size == 16 && projection.size == 16)
            if (frameHeightPixels <= 0 || projection[5] == 0f) return 0f
            val depth = abs(cameraFromTarget[14])
            return depth * 2f * (1f / projection[5]) / frameHeightPixels.toFloat()
        }

        /**
         * `map_from_fingerprint = inverse(camera_from_map) · camera_from_fingerprint` from a same-frame
         * base-map pose and planar pose (both column-major GL views); null when not finite.
         */
        fun solveMapFromFingerprint(cameraFromMap: FloatArray, cameraFromFingerprint: FloatArray): FloatArray? {
            require(cameraFromMap.size == 16 && cameraFromFingerprint.size == 16)
            val m = RigidMath.multiply(RigidMath.rigidInverse(cameraFromMap), cameraFromFingerprint)
            return m.takeIf { r -> r.all { it.isFinite() } }
        }

        private fun viewFromRotation(rotation: FloatArray): FloatArray {
            val out = RigidMath.identity()
            for (row in 0..2) for (col in 0..2) out[col * 4 + row] = rotation[row * 3 + col]
            return out
        }

        private fun sameTransform(a: FloatArray?, b: FloatArray?, epsilon: Float = 1e-5f): Boolean {
            if (a == null || b == null || a.size != 16 || b.size != 16) return false
            return a.indices.all { i -> a[i].isFinite() && b[i].isFinite() && abs(a[i] - b[i]) <= epsilon }
        }
    }
}

/** One planar match: GL column-major camera-from-canonical. */
@ExperimentalSphereSlamRelocApi
internal class PlanarMatchResult(
    val pageNo: Int,
    val cameraFromCanonical: FloatArray,
    val reprojectionError: Float,
    val inlierCount: Int,
)

/** The planar matcher a [PhotosphereTrackingSession] drives (KPM in production, a fake in tests). */
@ExperimentalSphereSlamRelocApi
internal interface PlanarFrontEnd : AutoCloseable {
    fun addPage(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        referenceWidthUnits: Float,
        physicallyMetric: Boolean,
        pageNo: Int,
        canonicalFromPage: FloatArray,
    ): PhotosphereTrackingSession.RegisteredPage

    fun match(luma: ByteBuffer, timestampNs: Long): PlanarMatchResult?
}

@ExperimentalSphereSlamRelocApi
internal fun interface PlanarFrontEndFactory {
    fun create(intrinsics: CameraIntrinsics): PlanarFrontEnd

    companion object {
        /** SphereSLAM's planar KPM ([SphereSlamStandaloneSession]) for the frame's intrinsics. */
        val KPM = PlanarFrontEndFactory { intrinsics ->
            val session = SphereSlamStandaloneSession(intrinsics.width, intrinsics.height, intrinsics.toCalibration())
            object : PlanarFrontEnd {
                override fun addPage(
                    luma: ByteBuffer,
                    width: Int,
                    height: Int,
                    referenceWidthUnits: Float,
                    physicallyMetric: Boolean,
                    pageNo: Int,
                    canonicalFromPage: FloatArray,
                ): PhotosphereTrackingSession.RegisteredPage {
                    val r = session.addReference(
                        luma = luma,
                        width = width,
                        height = height,
                        referenceWidthMeters = referenceWidthUnits,
                        physicallyMetric = physicallyMetric,
                        pageNo = pageNo,
                        canonicalFromPage = canonicalFromPage,
                    )
                    return PhotosphereTrackingSession.RegisteredPage(r.pageNo, r.featureCount, r.geometry, r.physicallyMetric)
                }

                override fun match(luma: ByteBuffer, timestampNs: Long): PlanarMatchResult? =
                    session.match(luma, timestampNs)?.let {
                        PlanarMatchResult(it.pageNo, it.cameraFromCanonical, it.reprojectionError, it.inlierCount)
                    }

                override fun close() = session.close()
            }
        }
    }
}

/** Per-stage timing accumulators for the periodic diagnostic. Not thread-safe (session lock). */
internal class StageTiming {
    class Stage(private val name: String) {
        var count = 0
            private set
        private var totalNs = 0L
        private var maxNs = 0L

        fun add(ns: Long) {
            if (ns < 0L) return
            count++
            totalNs += ns
            if (ns > maxNs) maxNs = ns
        }

        fun describe(): String =
            if (count == 0) "$name=-" else String.format(Locale.US, "%s=%.1f/%.1f", name, totalNs / 1e6 / count, maxNs / 1e6)

        fun reset() {
            count = 0; totalNs = 0L; maxNs = 0L
        }
    }

    var frames = 0
    val convert = Stage("convertMs")
    val setup = Stage("setupMs")
    val match = Stage("matchMs")
    val keyframe = Stage("keyframeMs")
    val growth = Stage("growthMs")
    private val stages = listOf(convert, setup, match, keyframe, growth)

    /** `timing{frames=N matched=M convertMs=mean/max setupMs=… matchMs=… keyframeMs=… growthMs=…}`; resets. */
    fun summaryAndReset(): String {
        val out = "timing{frames=$frames matched=${match.count} ${stages.joinToString(" ") { it.describe() }}}"
        frames = 0
        stages.forEach { it.reset() }
        return out
    }
}

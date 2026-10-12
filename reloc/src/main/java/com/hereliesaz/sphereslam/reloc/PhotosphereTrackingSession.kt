package com.hereliesaz.sphereslam.reloc

import android.os.SystemClock
import com.hereliesaz.sphereslam.AttitudeBasis
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
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Standalone (no-ARCore) photosphere tracking runtime: frame in, pose out.
 *
 * Feed each display-upright camera frame — tightly packed luma, its [CameraIntrinsics], timestamp,
 * device attitude and the body→camera rotation — to [onFrame]. The session keeps two layers:
 *
 * - **Base photosphere map.** The first usable attitude sample defines the map orientation; every
 *   later frame gets a rotation-only GL `camera_from_map` from the attitude change
 *   ([FrameResult.baseCameraFromMap]), expressed in the frame's own camera axes (so a display
 *   rotation since the first frame is honoured: `C_now · ΔR_body · C_firstᵀ`). Translation stays exactly zero (fixed-standpoint model; never
 *   integrated from the accelerometer). Quality-gated [PhotosphereKeyframe]s are emitted at a fixed
 *   cadence through [Callbacks.onKeyframe], before any wall reference exists.
 * - **Planar precision layer.** With a wall [ReferenceImage] (plus optional atlas pages) set through
 *   [updatePrecisionLayer], each frame is matched by SphereSLAM's planar KPM
 *   ([SphereSlamStandaloneSession]) and gated by [RobustTrackingLoop] (age → acceptance → optional
 *   [correctAcceptedPose] → smoothing → state hysteresis). The KPM pose lives in the fingerprint
 *   (canonical wall) frame and is rebased into the base map through `map_from_fingerprint`, solved
 *   once per reference from a same-frame base + KPM pair (`inverse(camera_from_map) ·
 *   camera_from_fingerprint`, [Callbacks.onMapFromFingerprintSolved]) or supplied by the host. Every
 *   lock — the first, and the first after any precision-layer change — needs the normal
 *   [TrackingStateConfig.confirmationFrames] before a pose is rendered or the map bridge is solved.
 *
 * A brief visual miss after a lock is held for at most [Config.maxBridgeMs] by a gyro rotation bridge
 * (camera centre held); the next visual pose blends from the bridged pose that was shown. Past that
 * window, with [Config.holdOffWall] (default), the last lock stays rendered through the same gyro
 * rotation ([PoseSource.HELD]) while the wall is out of view or re-confirming, until a visual relock
 * replaces it or the precision layer changes — the artwork stays put on the wall instead of
 * vanishing. A held pose keeps the camera centre where it was locked, so walking (rather than
 * turning) drifts it until the relock corrects it.
 *
 * **Photosphere localization** ([Config.tileLocalization], default on). The wall is one tile of the
 * photosphere; every other tile says where the camera is too. The first wall lock of a precision
 * layer relates the gyro frame to the map; from then on keyframes become tiles of the current
 * **station** (standpoint), each filed at the camera centre of the time, and a tile recognised in the
 * live frame fixes the camera rotation visually (correcting gyro/compass drift). With
 * `map_from_fingerprint` known that places the artwork with no wall in view ([PoseSource.PHOTOSPHERE],
 * which replaces the gyro-only [PoseSource.HELD] once a lock exists). When a wall lock (or a landmark
 * fix) puts the camera more than [Config.stationSpawnDistance] from every station, a new station opens
 * there and the earlier photospheres are kept: tiles looking the same way from two standpoints are a
 * stereo pair with a known baseline, triangulated into landmarks and a plane per tile, which give a
 * full 6-DoF fix from any position and predict how each tile looks from wherever the device moves. A
 * [TileDepthSource] (MiDaS) gives tiles 3D points from the first station on. Tile work runs on a
 * background worker (OpenCV ORB; disabled once if OpenCV is missing); fixes are adopted on the next
 * frame. Stations, tiles and the correction reset on a new reference or map (they are in its terms). An accepted pose may grow a multi-page KPM atlas ([PhotosphereAtlasGrowth]):
 * a perspective-rectified wall patch next to the existing pages, kept only when it passes
 * [TargetQuality] and the KPM feature floor ([Callbacks.onAtlasPageAdded]). The planar session is
 * rebuilt whenever the frame intrinsics change.
 *
 * Matcher builds never run on the [onFrame] thread: the initial build (and any rebuild after a
 * reference or intrinsics change) and every atlas-growth attempt (rectification, quality gate, page
 * feature extraction and index rebuild) run on a private background worker. Growth forks the live
 * matcher and adds the page to the fork while [onFrame] keeps matching on the live one; the finished
 * matcher is swapped in at the start of a later frame, and a rejected page simply drops the fork.
 * Until the first build lands, frames return the base pose only ([FrameResult.pose] null).
 *
 * Failures are classified ([TrackingFailureClassifier]) and reported once per change of reason. An
 * exception thrown while processing a frame enters [TrackingState.FATAL] (reported through
 * [Callbacks.onFailure] and [Callbacks.onFatalError]). A [TrackingFailureSeverity.FATAL] one (a native
 * or internal failure) is terminal: later frames are ignored. A [TrackingFailureSeverity.RECOVERABLE]
 * one — a [ReferenceTooWeakException] from a matcher build — only halts the precision layer: frames
 * keep producing the base pose and keyframes, and the next [updatePrecisionLayer] with a different
 * reference (or `forceRebuild`, or changed atlas pages) clears it and rebuilds, keeping the base map.
 *
 * Camera plumbing (CameraX `ImageProxy`, Camera2 characteristics), relocalizer/feature-map feeds,
 * depth inference and UI belong to the host: it reads [FrameResult] after each call.
 *
 * Thread-safe (methods synchronize), but drive [onFrame] from one camera-analysis worker. Close to
 * release the native matchers (closed on the background worker once any in-flight build finishes). Ported from GraffitiXR `SphereSlamStandaloneTrackingAnalyzer`.
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
    // Where matcher builds and atlas growth run. Null = a private background thread (production);
    // tests pass a direct executor so builds complete synchronously.
    atlasExecutor: Executor? = Executor(Runnable::run),
    // Photosphere tile features (OpenCV ORB in production); null disables tile localization.
    tileBackend: TileFeatureBackend? = null,
    tileDepthSource: TileDepthSource? = null,
) : AutoCloseable {

    constructor(
        config: Config = Config(),
        callbacks: Callbacks = Callbacks(),
        correctAcceptedPose: ((FloatArray) -> FloatArray)? = null,
        elapsedRealtimeNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
        tileDepthSource: TileDepthSource? = null,
    ) : this(
        config, callbacks, correctAcceptedPose, elapsedRealtimeNanos, PlanarFrontEndFactory.kpm(config.matchSpeed), null,
        OrbTileFeatureBackend(), tileDepthSource,
    )

    /**
     * @property timestampSource the camera's timestamp source (Camera2 `SENSOR_INFO_TIMESTAMP_SOURCE`);
     *   frame age is only checked for [CameraTimestampSource.REALTIME].
     * @property maxBridgeMs longest gyro bridge, measured from the last accepted frame, ms. This is
     *   the session's only bridge limit: it overrides [TrackingStateConfig.maxBridgeMs] in
     *   [trackingState]. Within it the tracking state is `IMU_BRIDGE` and a quick return blends.
     * @property holdOffWall past [maxBridgeMs], keep rendering instead of no pose: the photosphere pose
     *   once a lock has defined it ([PoseSource.PHOTOSPHERE]; it survives atlas-page changes and
     *   `forceRebuild`, not a new reference or map), else the last lock through the gyro rotation
     *   ([PoseSource.HELD], until a relock or any precision-layer change).
     * @property tileLocalization recognise photosphere tiles to fix the camera pose (see the class doc).
     * @property tileFixIntervalMs at most one tile localization attempt per this interval, ms (none
     *   while the wall is locked).
     * @property stationSpawnDistance camera distance from every station at which a new station opens:
     *   metres for a physically metric reference, reference widths otherwise.
     * @property keyframeIntervalMs photosphere keyframe cadence, ms.
     * @property atlasGrowth whether accepted poses may grow the KPM atlas.
     * @property metricsIntervalMs cadence of the KPM match-metrics + per-stage timing diagnostic, ms.
     * @property matchSpeed per-frame planar matching cost levers (downscaled KPM, flow tracking between
     *   full solves, focused page matching); [KpmMatchSpeedConfig.FULL_KPM_EVERY_FRAME] restores a
     *   full-resolution all-pages KPM solve on every frame.
     */
    data class Config(
        val timestampSource: CameraTimestampSource = CameraTimestampSource.UNKNOWN,
        val acceptance: PoseAcceptanceConfig = PoseAcceptanceConfig(),
        val observationAge: ObservationAgeConfig = ObservationAgeConfig(),
        val trackingState: TrackingStateConfig = TrackingStateConfig(),
        val targetQuality: TargetQualityConfig = TargetQualityConfig(),
        val maxBridgeMs: Long = 400L,
        val holdOffWall: Boolean = true,
        val tileLocalization: Boolean = true,
        val tileFixIntervalMs: Long = 250L,
        val stationSpawnDistance: Float = 0.3f,
        val keyframeIntervalMs: Long = 250L,
        val atlasGrowth: Boolean = true,
        val metricsIntervalMs: Long = 5_000L,
        val matchSpeed: KpmMatchSpeedConfig = KpmMatchSpeedConfig(),
    ) {
        init {
            require(maxBridgeMs >= 0L && keyframeIntervalMs >= 0L && metricsIntervalMs >= 0L)
            require(tileFixIntervalMs >= 0L)
            require(stationSpawnDistance.isFinite() && stationSpawnDistance > 0f)
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
     *   copied: do not mutate it during [onFrame]. Nothing keeps it after [onFrame] returns (keyframes
     *   and atlas growth copy what they need), so a pooled buffer
     *   (`camera.LumaFrameBufferPool`) may be reused for the next frame.
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

        /** Gyro-bridged hold of the last KPM pose within [Config.maxBridgeMs]. */
        IMU_BRIDGE,

        /**
         * The last KPM lock carried through the gyro rotation past the bridge window
         * ([Config.holdOffWall]): the wall is out of view or the relock is still confirming.
         */
        HELD,

        /**
         * Placed from the photosphere: the gyro/compass rotation corrected by the latest visual fix (a
         * recognised tile, or the last wall lock) at the latest known camera centre
         * ([Config.tileLocalization]); the wall is out of view or the relock is still confirming.
         */
        PHOTOSPHERE,
    }

    /**
     * A pose to render.
     *
     * @property cameraFromMap column-major GL camera-from-base-map view.
     * @property projection [ProjectionMatrix.buildFrom] of [intrinsics] (the full tracked frame).
     * @property unitsPerPixel wall units spanned by one vertical frame pixel at the target depth.
     * @property reprojectionError mean squared reprojection error of the visual solve, pixels² in the
     *   image it was solved in (KPM: the matcher's downscaled image; flow: the full frame).
     * @property observationAgeMs trusted frame age, or null.
     * @property matchMethod how the visual pose was solved (full / focused KPM or flow tracking); for
     *   an [PoseSource.IMU_BRIDGE] pose, the method of the held pose.
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
        val matchMethod: VisualMatchMethod = VisualMatchMethod.KPM_FULL,
    ) {
        private val view = cameraFromMap.copyOf()
        private val proj = projection.copyOf()

        val cameraFromMap: FloatArray get() = view.copyOf()
        val projection: FloatArray get() = proj.copyOf()

        internal fun bridged(
            delta: FloatArray,
            projection: FloatArray,
            intrinsics: CameraIntrinsics,
            timestampNs: Long,
            source: PoseSource = PoseSource.IMU_BRIDGE,
        ) =
            TrackedPose(
                RotationMath.rotateAboutCameraCentre(view, delta), projection, intrinsics, timestampNs, pageNo,
                reprojectionError, inlierCount, unitsPerPixel, observationAgeMs, matchDurationMs, source,
                matchMethod,
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
    // Recreated per matcher build with the reference's units (snap distance); reset on every rebuild.
    private var stabilizer = PoseStabilizer()
    // Config.maxBridgeMs is the one bridge limit (see Config); the state machine gets the same value.
    private val stateMachine = TrackingStateMachine(config.trackingState.copy(maxBridgeMs = config.maxBridgeMs))

    private var referenceImage: ReferenceImage? = null
    private var mapFromFingerprintValue: FloatArray? = null
    // The map_from_fingerprint this session solved for the current reference, if any.
    private var solvedMapForReference: FloatArray? = null
    // Whether a confirmed lock has checked the current map_from_fingerprint against the same-frame solve.
    private var mapCheckedOnLock = false
    private val runtimeAtlasPages = linkedMapOf<Int, AtlasPage>()
    // Highest page number any live / pending matcher holds, so growth never reuses one.
    private var highestMatcherPageNo = 0
    // Set while a RECOVERABLE failure (a too-weak reference) has halted the precision layer.
    private var suspendedFailure: TrackingFailureEvent? = null

    private var frontEnd: PlanarFrontEnd? = null
    private var sessionKey: CameraIntrinsics? = null
    private var buildKey: CameraIntrinsics? = null
    private val atlasWorker = AtlasMatcherWorker(atlasExecutor)
    private val stations: PhotosphereStationTracker? = tileBackend?.takeIf { config.tileLocalization }?.let {
        PhotosphereStationTracker(
            it,
            tileDepthSource,
            atlasExecutor,
            PhotosphereStationTracker.Config(fixIntervalMs = config.tileFixIntervalMs),
        )
    }
    private var robustLoop: RobustTrackingLoop? = null

    private var imuBridge: AttitudeRotationBridge? = null
    private var imuBridgeReferenceAtMs = -1L
    private var lastGood: TrackedPose? = null
    // lastGood's render pose in the fingerprint (precision) frame — the stabilizer's frame.
    private var lastGoodCanonical: FloatArray? = null
    private var lastAcceptedCameraFromCanonical: FloatArray? = null

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

    /**
     * True while the session is in [TrackingState.FATAL]: for good after a
     * [TrackingFailureSeverity.FATAL] failure, or until [updatePrecisionLayer] recovers a
     * [TrackingFailureSeverity.RECOVERABLE] one (see [isRecoverable]).
     */
    val isFatal: Boolean @Synchronized get() = fatal || suspendedFailure != null

    /** True when [isFatal] was caused by a recoverable failure that [updatePrecisionLayer] can clear. */
    val isRecoverable: Boolean @Synchronized get() = !fatal && suspendedFailure != null

    /** Whether a wall reference is set. */
    val hasReference: Boolean @Synchronized get() = referenceImage != null

    /** The current `map_from_fingerprint` (column-major), solved or supplied, or null. */
    val mapFromFingerprint: FloatArray? @Synchronized get() = mapFromFingerprintValue?.copyOf()

    /** The atlas pages beyond page 0 (supplied or grown), by page number. */
    val atlasPages: List<AtlasPage> @Synchronized get() = runtimeAtlasPages.values.toList()

    /** Photosphere stations opened so far (1 until the camera moves; 0 without tile localization). */
    val stationCount: Int @Synchronized get() = stations?.stationCount ?: 0

    /**
     * Swap the planar precision layer without touching the base photosphere (map orientation,
     * keyframe cadence and attitude reference survive).
     *
     * The planar session, smoothing, gyro bridge and tracking state are rebuilt — the next lock needs
     * the normal confirmation again — when [reference] is a different object, when the effective
     * `map_from_fingerprint` changes (beyond 1e-5), when [atlasPages] differs from [atlasPages]'s
     * current value, or when [forceRebuild] is set (e.g. the host's relocalizer seed changed).
     * Otherwise the call changes nothing.
     *
     * A rebuild also clears a [isRecoverable] failure: after a [ReferenceTooWeakException], pass a
     * different reference (or the previous one), new pages, or `forceRebuild = true` to resume.
     *
     * @param atlasPages the complete set of pages beyond page 0. Pages the session grew are reported
     *   through [Callbacks.onAtlasPageAdded] and [atlasPages]; pass them back (the same objects or
     *   equal copies) to keep them. Any other set rebuilds the matcher from exactly these pages.
     * @param mapFromFingerprint column-major map-from-fingerprint supplied by the host, or null for the
     *   session's own: with the same [reference], a map this session already solved for it is kept;
     *   otherwise it is solved on the next confirmed lock.
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
        if (referenceChanged) solvedMapForReference = null
        val effectiveMap = mapFromFingerprint?.copyOf() ?: solvedMapForReference?.copyOf()
        val mapChanged = !sameTransform(mapFromFingerprintValue, effectiveMap) &&
            !(mapFromFingerprintValue == null && effectiveMap == null)
        val sortedPages = atlasPages.sortedBy { it.pageNo }
        val pagesChanged = !samePages(runtimeAtlasPages.values.toList(), sortedPages)
        // The photosphere layer is in terms of the reference (units) and the map (the correction).
        if (referenceChanged || mapChanged) stations?.reset()
        referenceImage = reference
        mapFromFingerprintValue = effectiveMap
        if (referenceChanged || mapChanged) mapCheckedOnLock = false
        if (pagesChanged) {
            runtimeAtlasPages.clear()
            sortedPages.forEach { runtimeAtlasPages[it.pageNo] = it }
        }
        reference?.let { stations?.setSpawnDistance(spawnUnits(it)) }
        publishWall()

        if (referenceChanged || mapChanged || pagesChanged || forceRebuild) {
            dropPlanarSession()
            if (suspendedFailure != null) {
                suspendedFailure = null
                lastFailureReason = null
                callbacks.onDiagnostic("SphereSLAM precision layer recovered from a recoverable failure")
            }
            if (lastReportedState != null) reportState(stateMachine.state)
            callbacks.onDiagnostic(
                "SphereSLAM precision layer updated reference=${reference != null} " +
                    "mapFromFingerprint=${effectiveMap != null} pages=${runtimeAtlasPages.size}; base photosphere preserved",
            )
        }
    }

    /**
     * Optional display-rate pose hook. When set, every rendered pose is published to it together with
     * its frame's attitude and `cameraFromDevice` ([DisplayPosePredictor.update]), and it is cleared
     * whenever a frame yields no pose, on a fatal (or recoverable) failure and on [close] — so a GL thread can call
     * `predictInto(attitudeExtrapolator, now, out)` every draw and get the latest vision pose rotated
     * by the attitude change since its frame. Use the same attitude sensor as [Frame.attitude].
     * Safe to set from any thread.
     */
    @Volatile
    var displayPosePredictor: DisplayPosePredictor? = null

    private val governor = AnticipationGovernor(
        config.matchSpeed.anticipationResolution,
        config.matchSpeed.anticipationThrottle,
        null,
    )

    /**
     * The most the session anticipates the target's appearance ([AnticipationResolution]: half-spheres
     * of previews). Starts at [KpmMatchSpeedConfig.anticipationResolution]; a change applies from the
     * next frame, without a restart: missing previews are rendered and indexed in the background, a
     * few at a time. Safe to set from any thread.
     */
    var anticipationResolution: AnticipationResolution
        get() = governor.max
        set(value) {
            governor.max = value
        }

    /**
     * How far battery, heat and the session's own load throttle [anticipationResolution]. The session
     * re-reads the pressures every 2 s and searches the subset of the maximum's views that fits
     * ([AnticipationDecimation]: every other meridian, circle or half-sphere, and so on): lower at
     * once, higher after it has fitted for 10 s. Starts at [KpmMatchSpeedConfig.anticipationThrottle].
     * Safe to set from any thread.
     */
    var anticipationThrottle: AnticipationThrottle
        get() = governor.throttle
        set(value) {
            governor.throttle = value
        }

    /**
     * Where battery and heat pressure come from: [AndroidDevicePressure] for the device's own; null
     * (the default) for none, so only the session's load throttles. Safe to set from any thread.
     */
    var devicePressure: DevicePressureSource?
        get() = governor.source
        set(value) {
            governor.source = value
        }

    /** The resolution the session currently anticipates at, and the pressures that set it. */
    val anticipation: AnticipationStatus get() = governor.status

    /**
     * Process one frame. After [close] or a terminal ([TrackingFailureSeverity.FATAL]) failure this
     * returns an empty result; during a recoverable failure it returns the base pose only.
     */
    @Synchronized
    fun onFrame(frame: Frame): FrameResult {
        if (closed || fatal) return FrameResult(null, stateMachine.state, null, false, null, null, null, null)
        val startNs = elapsedRealtimeNanos()
        return try {
            process(frame).also { result ->
                publishToPredictor(frame, result.pose)
                emitMetricsIfDue()
                // The session's own load, for the anticipation throttle.
                governor.onFrame((elapsedRealtimeNanos() - startNs) / 1e6f, frame.timestampNs)
            }
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            fatal = true
            displayPosePredictor?.clear()
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
        stations?.let { tracker ->
            tracker.onFrame(cameraFromMap?.let(PhotosphereStationTracker::rotationOfView), frame.luma, intrinsics, frameStartMs)
            tracker.drainDiagnostics(callbacks.onDiagnostic)
        }
        val reference = referenceImage
            ?: return FrameResult(null, stateMachine.state, cameraFromMap, false, null, null, null, projection)
        // A recoverable failure halted the precision layer; the base map keeps running.
        if (suspendedFailure != null) {
            return FrameResult(null, stateMachine.state, cameraFromMap, false, null, null, null, projection)
        }

        val setupStartNs = elapsedRealtimeNanos()
        val active = try {
            ensureSession(intrinsics, reference)
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            val event = TrackingFailureClassifier.fromThrowable(t)
            if (event.severity != TrackingFailureSeverity.RECOVERABLE) throw t
            return suspendPrecisionLayer(t, event, cameraFromMap, projection)
        } ?: return FrameResult(null, stateMachine.state, cameraFromMap, false, null, null, null, projection)
        active.setAnticipation(governor.update(elapsedRealtimeNanos() / 1_000_000L))
        val direct = directFrame(frame.luma)
        val matchStartNs = elapsedRealtimeNanos()
        timing.setup.add(matchStartNs - setupStartNs)
        val pose = active.match(
            PlanarFrameInput(
                luma = direct,
                lumaBytes = frame.luma,
                timestampNs = frame.timestampNs,
                nowMs = matchStartNs / 1_000_000L,
                predictedCameraFromCanonical = predictedCameraFromCanonical(frame.attitude, cameraFromMap, frameStartMs),
            ),
        )
        val matchEndNs = elapsedRealtimeNanos()
        timing.match.add(matchEndNs - matchStartNs)
        if (pose != null) timing.matched++
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
        if (pose != null) active.onVerdict(outcome.accepted)

        if (outcome.accepted) {
            val locked = requireNotNull(pose)
            lastFailureReason = null
            reportState(outcome.state)
            if (outcome.state != TrackingState.LOCKED) {
                // Re-confirming a lock: keep the held pose on screen until it is confirmed.
                return FrameResult(
                    offWallPose(frame, projection, cameraFromMap, frameStartMs), outcome.state, cameraFromMap, true,
                    null, null, null, projection,
                )
            }

            lastAcceptedCameraFromCanonical = locked.cameraFromCanonical.copyOf()
            maybeEmitMapFromFingerprint(cameraFromMap, locked.cameraFromCanonical)

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
                matchMethod = locked.method,
            )
            lastGood = tracked
            lastGoodCanonical = precisionRenderView.copyOf()
            // Only a pose in the map (map_from_fingerprint known) relates the gyro frame to it.
            if (mapFromFp != null && cameraFromMap != null) {
                stations?.onLock(renderView, PhotosphereStationTracker.rotationOfView(cameraFromMap), frameStartMs)
            }
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
        val bridged = when {
            outcome.state == TrackingState.IMU_BRIDGE -> bridgeLastGood(frame, projection, loop)
            config.holdOffWall -> offWallPose(frame, projection, cameraFromMap, frameStartMs)
            else -> {
                lastGood = null
                lastGoodCanonical = null
                null
            }
        }
        return FrameResult(bridged, outcome.state, cameraFromMap, false, null, null, failure, projection)
    }

    /**
     * The base map pose from the absolute attitude: gravity-level and north-referenced (map +X east,
     * +Y up, −Z north — `AttitudeBasis`, the frame `PhotosphereFingerprintFrame` places fingerprints
     * in). Not the first frame's camera: a session started looking at a floor would put the map's −Z
     * straight down, a quarter turn from every provisional placement, and a frame that moved with the
     * start pose could never agree with a transform persisted from another session.
     */
    private fun photosphereCameraFromMap(attitude: AttitudeSample?, cameraFromDevice: FloatArray): FloatArray? {
        val qNow = attitude?.quaternion ?: return null
        return viewFromRotation(absoluteCameraRotation(cameraFromDevice, qNow))
    }

    /**
     * Solve `map_from_fingerprint` from the same-frame base and KPM poses on a confirmed lock: when
     * none is known, and once per reference/map to check a host-supplied one. A host's provisional
     * placement (`PhotosphereFingerprintFrame.placement`) guesses range and assumes the page faces
     * the camera; the solve replaces it when the two disagree by more than [MAP_REFINE_DEGREES] or
     * [MAP_REFINE_FRACTION] of the page distance.
     */
    private fun maybeEmitMapFromFingerprint(
        cameraFromMap: FloatArray?,
        cameraFromFingerprint: FloatArray,
    ) {
        if (cameraFromMap == null) return
        val current = mapFromFingerprintValue
        if (current != null && mapCheckedOnLock) return
        if (cameraFromMap.any { !it.isFinite() } || cameraFromFingerprint.any { !it.isFinite() }) return
        val solved = solveMapFromFingerprint(cameraFromMap, cameraFromFingerprint) ?: return
        mapCheckedOnLock = true
        if (current != null && !mapsDisagree(current, solved)) return
        // Publish locally first so the very next precision pose is rebased even before the host persists it.
        mapFromFingerprintValue = solved.copyOf()
        solvedMapForReference = solved.copyOf()
        publishWall()
        callbacks.onMapFromFingerprintSolved(solved)
        callbacks.onDiagnostic(
            if (current == null) "SphereSLAM map_from_fingerprint solved from same-frame base+KPM pose"
            else "SphereSLAM map_from_fingerprint refined from same-frame base+KPM pose",
        )
    }

    /**
     * A RECOVERABLE failure while (re)building the matcher: halt only the precision layer, report it
     * like a fatal error, and keep the base map running until [updatePrecisionLayer] recovers it.
     */
    private fun suspendPrecisionLayer(
        error: Throwable,
        event: TrackingFailureEvent,
        cameraFromMap: FloatArray?,
        projection: FloatArray,
    ): FrameResult {
        dropPlanarSession()
        suspendedFailure = event
        displayPosePredictor?.clear()
        reportFailure(event)
        val state = stateMachine.onFatal()
        reportState(state)
        callbacks.onFatalError(error)
        return FrameResult(null, state, cameraFromMap, false, null, null, event, projection)
    }

    private fun captureKeyframe(frame: Frame, cameraFromMap: FloatArray?, nowMs: Long) {
        val mapPose = cameraFromMap ?: return
        val heading = frame.headingDeg?.takeIf { it.isFinite() }
        val elevation = frame.elevationDeg?.takeIf { it.isFinite() }
        val forHost = heading != null && elevation != null
        val forTile = stations?.wantsKeyframe(nowMs) == true
        if (!forHost && !forTile) return
        if (lastKeyframeMs != Long.MIN_VALUE && nowMs - lastKeyframeMs < config.keyframeIntervalMs) return
        // The slot is consumed even when the quality gate rejects the frame.
        lastKeyframeMs = nowMs
        val i = frame.intrinsics
        if (!TargetQuality.analyze(frame.luma, i.width, i.height, config.targetQuality).acceptable) return
        // A usable keyframe is also offered as a tile of the current station.
        if (forTile) stations?.onKeyframe(PhotosphereStationTracker.rotationOfView(mapPose), frame.luma, i, nowMs)
        if (heading == null || elevation == null) return
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

    /**
     * The live matcher for [intrinsics], or null while one is being built in the background. Adopts
     * any finished worker outcome first (a build, or a grown atlas — the atomic swap).
     */
    private fun ensureSession(intrinsics: CameraIntrinsics, reference: ReferenceImage): PlanarFrontEnd? {
        adoptWorkerOutcome()
        val existing = frontEnd
        if (existing != null && intrinsics == sessionKey) return existing

        if (existing != null) {
            // Intrinsics changed: the old matcher's calibration is wrong for this frame. The rebuilt
            // matcher starts a fresh continuity baseline, so the next lock must be confirmed again.
            atlasWorker.retire(existing)
            frontEnd = null
            sessionKey = null
            robustLoop = null
            clearImuReference()
            lastGood = null
            lastGoodCanonical = null
            lastAcceptedCameraFromCanonical = null
            stabilizer.reset()
            stateMachine.reset()
            if (lastReportedState != null) reportState(stateMachine.state)
        }
        if (buildKey == intrinsics && atlasWorker.isBuilding) return null

        buildKey = intrinsics
        val pages = runtimeAtlasPages.values.toList()
        val minFeatures = config.targetQuality.minKpmFeatures
        atlasWorker.submitBuild { AtlasMatcherWorker.fullBuild(frontEndFactory, intrinsics, reference, pages, minFeatures) }
        adoptWorkerOutcome() // a synchronous executor has already finished
        return frontEnd?.takeIf { sessionKey == intrinsics }
    }

    /** Apply a finished worker outcome on this (the analyzer) thread; callbacks fire here. */
    private fun adoptWorkerOutcome() {
        when (val outcome = atlasWorker.poll() ?: return) {
            is AtlasMatcherWorker.Outcome.Built -> {
                buildKey = null
                frontEnd?.let(atlasWorker::retire)
                outcome.diagnostics.forEach(callbacks.onDiagnostic)
                // referenceWidthUnits scales the acceptance translation-jump limit and the smoothing
                // snap distance; grown pages share the wall's scale. Every rebuild starts unconfirmed.
                highestMatcherPageNo = maxOf(highestMatcherPageNo, runtimeAtlasPages.keys.maxOrNull() ?: 0)
                stabilizer = PoseStabilizer(
                    snapDistance = PoseBlend.snapDistanceFor(outcome.root.geometry.widthMeters, outcome.root.physicallyMetric),
                )
                robustLoop = RobustTrackingLoop(
                    referenceWidthUnits = outcome.root.geometry.widthMeters,
                    acceptancePolicy = acceptancePolicy,
                    agePolicy = agePolicy,
                    stabilizer = stabilizer,
                    stateMachine = stateMachine,
                    correctAcceptedPose = correctAcceptedPose,
                )
                frontEnd = outcome.frontEnd
                sessionKey = outcome.intrinsics
                callbacks.onReferenceReady(outcome.root)
            }
            is AtlasMatcherWorker.Outcome.BuildFailed -> {
                buildKey = null
                throw outcome.error
            }
            is AtlasMatcherWorker.Outcome.Grown -> {
                val live = frontEnd
                if (live == null || runtimeAtlasPages.keys.toList() != outcome.keptPageNumbers ||
                    runtimeAtlasPages.containsKey(outcome.page.pageNo)
                ) {
                    atlasWorker.retire(outcome.frontEnd)
                    callbacks.onDiagnostic("SphereSLAM atlas growth discarded page=${outcome.page.pageNo} (atlas changed)")
                    return
                }
                // The swap: same reference, calibration and wall frame, so tracking state carries over.
                frontEnd = outcome.frontEnd
                atlasWorker.retire(live)
                runtimeAtlasPages[outcome.page.pageNo] = outcome.page
                highestMatcherPageNo = maxOf(highestMatcherPageNo, outcome.page.pageNo)
                callbacks.onDiagnostic(outcome.diagnostic)
                callbacks.onAtlasPageAdded(outcome.page, outcome.attitude)
            }
            is AtlasMatcherWorker.Outcome.GrowthSkipped -> callbacks.onDiagnostic(outcome.diagnostic)
        }
    }

    private fun maybeGrowAtlas(
        active: PlanarFrontEnd,
        reference: ReferenceImage,
        frame: Frame,
        pose: PlanarMatchResult,
    ) {
        if (!config.atlasGrowth || !atlasWorker.isIdle) return
        // Grow only from a fresh KPM solve, never from a flow-tracked pose.
        if (pose.method == VisualMatchMethod.FLOW) return
        if (!PhotosphereAtlasGrowth.poseQualifies(pose.inlierCount, pose.reprojectionError, runtimeAtlasPages.size)) return
        val now = nowMs()
        if (lastAtlasGrowthMs != Long.MIN_VALUE && now - lastAtlasGrowthMs < PhotosphereAtlasGrowth.MIN_GROW_INTERVAL_MS) return
        val key = sessionKey ?: return

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
        // Everything else — rectification, the quality gate, page features and the index rebuild —
        // runs on the worker against a fork of the live matcher. Only the frame is copied here (the
        // caller may reuse its buffer once onFrame returns).
        val kept = runtimeAtlasPages.values.toList()
        // Never a number the live matcher (or an earlier build of this session) already holds.
        val pageNo = maxOf(runtimeAtlasPages.keys.maxOrNull() ?: 0, highestMatcherPageNo) + 1
        val luma = frame.luma.copyOf()
        val attitude = frame.attitude
        val targetQuality = config.targetQuality
        atlasWorker.submitGrowth {
            AtlasMatcherWorker.growth(
                active, frontEndFactory, key, reference, kept, pageNo, luma, i.width, i.height, geometry, targetQuality, attitude,
            )
        }
        adoptWorkerOutcome() // a synchronous executor has already finished
    }

    /** The last accepted camera-from-canonical carried through the gyro rotation since, or null. */
    private fun predictedCameraFromCanonical(attitude: AttitudeSample?, cameraFromMap: FloatArray?, nowMs: Long): FloatArray? {
        // Lost (or never locked): where the photosphere says the wall is seeds the relock.
        if (stateMachine.state != TrackingState.LOCKED && stateMachine.state != TrackingState.IMU_BRIDGE) {
            photosphereCameraFromCanonical(cameraFromMap, nowMs)?.let { return it }
        }
        val last = lastAcceptedCameraFromCanonical ?: return null
        val delta = imuBridge?.cameraRotationDelta(attitude?.quaternion) ?: return last
        return RotationMath.rotateAboutCameraCentre(last, delta)
    }

    private fun canBridge(attitude: AttitudeSample?, nowMs: Long): Boolean {
        if (lastGood == null || imuBridge == null || imuBridgeReferenceAtMs < 0L) return false
        val elapsed = nowMs - imuBridgeReferenceAtMs
        if (elapsed < 0L || elapsed > config.maxBridgeMs) return false
        return imuBridge?.cameraRotationDelta(attitude?.quaternion) != null
    }

    /**
     * The last good pose carried through the gyro rotation since it was accepted. The same rotation
     * of its precision-frame render pose becomes [loop]'s smoothing baseline, so the next visual pose
     * blends from what was shown during the bridge instead of swinging back.
     */
    private fun bridgeLastGood(frame: Frame, projection: FloatArray, loop: RobustTrackingLoop): TrackedPose? {
        val held = lastGood ?: return null
        val delta = imuBridge?.cameraRotationDelta(frame.attitude?.quaternion) ?: return null
        lastGoodCanonical?.let { loop.synchronizeRendered(RotationMath.rotateAboutCameraCentre(it, delta)) }
        return held.bridged(delta, projection, frame.intrinsics, frame.timestampNs)
    }

    /**
     * The pose to show with no accepted visual pose this frame ([Config.holdOffWall]): the photosphere
     * pose once a lock has defined it, else the gyro-held last lock, else none.
     */
    private fun offWallPose(frame: Frame, projection: FloatArray, cameraFromMap: FloatArray?, nowMs: Long): TrackedPose? {
        if (!config.holdOffWall) return null
        return photospherePose(frame, projection, cameraFromMap, nowMs) ?: holdLastGood(frame, projection)
    }

    /** Hand the wall (its placement and its pages' extent) to the photosphere layer. */
    private fun publishWall() {
        val tracker = stations ?: return
        val reference = referenceImage
        val windows = if (reference == null) emptyList() else buildList {
            add(floatArrayOf(0f, 0f, reference.referenceWidthUnits, reference.referenceWidthUnits * reference.height / reference.width))
            runtimeAtlasPages.values.forEach { page ->
                val m = page.canonicalFromPageRef()
                add(floatArrayOf(m[12], m[13], page.referenceWidthUnits, page.referenceHeightUnits))
            }
        }
        tracker.setWall(validMapFromFingerprint(), windows)
    }

    private fun validMapFromFingerprint(): FloatArray? =
        mapFromFingerprintValue?.takeIf { it.size == 16 && it.all(Float::isFinite) }

    /** The photosphere's camera_from_canonical (relock seed), or null. */
    private fun photosphereCameraFromCanonical(cameraFromMap: FloatArray?, nowMs: Long): FloatArray? {
        val tracker = stations ?: return null
        val base = cameraFromMap ?: return null
        val mapFromFp = validMapFromFingerprint() ?: return null
        val view = tracker.pose(PhotosphereTrackingSession.rotationOf(base)) ?: return null
        return RigidMath.multiply(view, mapFromFp)
    }

    /**
     * [PoseSource.PHOTOSPHERE]: the base rotation corrected by the latest visual fix, at the latest
     * known camera centre; needs `map_from_fingerprint` (else there is nothing to place).
     */
    private fun photospherePose(frame: Frame, projection: FloatArray, cameraFromMap: FloatArray?, nowMs: Long): TrackedPose? {
        val tracker = stations ?: return null
        val base = cameraFromMap ?: return null
        val mapFromFp = validMapFromFingerprint() ?: return null
        val view = tracker.pose(PhotosphereTrackingSession.rotationOf(base)) ?: return null
        val precision = RigidMath.multiply(view, mapFromFp)
        val held = lastGood
        return TrackedPose(
            cameraFromMap = view,
            projection = projection,
            intrinsics = frame.intrinsics,
            timestampNs = frame.timestampNs,
            pageNo = held?.pageNo ?: 0,
            reprojectionError = held?.reprojectionError ?: 0f,
            inlierCount = held?.inlierCount ?: 0,
            unitsPerPixel = unitsPerPixel(precision, projection, frame.intrinsics.height),
            observationAgeMs = null,
            matchDurationMs = 0f,
            source = PoseSource.PHOTOSPHERE,
            matchMethod = held?.matchMethod ?: VisualMatchMethod.KPM_FULL,
        )
    }

    /**
     * [Config.holdOffWall]: the last good pose carried through the gyro rotation since its lock, past
     * the bridge window. Unlike the bridge it does not re-seed the smoothing baseline: a relock after a
     * real loss is a genuine correction and must not be gated by the held (possibly drifted) pose.
     * Null when holding is off, nothing was locked since the last precision-layer change, or there is
     * no attitude.
     */
    private fun holdLastGood(frame: Frame, projection: FloatArray): TrackedPose? {
        if (!config.holdOffWall) return null
        val held = lastGood ?: return null
        val delta = imuBridge?.cameraRotationDelta(frame.attitude?.quaternion) ?: return null
        return held.bridged(delta, projection, frame.intrinsics, frame.timestampNs, PoseSource.HELD)
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
        atlasWorker.invalidate()
        frontEnd?.let(atlasWorker::retire)
        frontEnd = null
        sessionKey = null
        buildKey = null
        robustLoop = null
        lastGood = null
        lastGoodCanonical = null
        lastAcceptedCameraFromCanonical = null
        stabilizer.reset()
        // A new or rebuilt precision layer has no continuity baseline: the next lock is confirmed
        // from scratch (and only then may solve map_from_fingerprint). A terminal failure stays FATAL.
        if (!fatal) stateMachine.reset()
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
     * gate + callback) and atlas growth (only the analyzer-side cost of starting an attempt: the frame
     * copy and hand-off; the work itself runs on the background worker) — over the frames since the
     * previous line.
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
                "error=${pose.reprojectionError} ageMs=$age matchMs=${String.format(Locale.US, "%.1f", pose.matchDurationMs)} " +
                "method=${pose.matchMethod}"
        } else {
            "SphereSLAM standalone KPM no-lock"
        }
        val tiles = stations?.summary()?.let { " $it" } ?: ""
        val rect = frontEnd?.diagnosticsAndReset()?.let { " $it" } ?: ""
        callbacks.onDiagnostic("$head ${timing.summaryAndReset()}$tiles$rect")
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

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        displayPosePredictor?.clear()
        dropPlanarSession()
        atlasWorker.close()
        stations?.close()
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

        /**
         * The base map's camera rotation `camera_from_map = C · R(q)ᵀ · W`: `C` the frame's
         * camera-from-device (row-major 3×3), `R(q)` the device→East-North-Up attitude, `W` world from
         * the gravity-level, north-referenced map (`AttitudeBasis.WORLD_FROM_MAP`).
         */
        internal fun absoluteCameraRotation(cameraFromDevice: FloatArray, q: FloatArray): FloatArray {
            val deviceFromWorld = RotationMath.transposeMat3(RotationMath.toRotationMatrix3x3(RotationMath.normalize(q)))
            return RotationMath.multiplyMat3(
                RotationMath.multiplyMat3(cameraFromDevice, deviceFromWorld),
                AttitudeBasis.WORLD_FROM_MAP,
            )
        }

        /** [maybeEmitMapFromFingerprint] replaces a known map past this rotation difference, degrees. */
        const val MAP_REFINE_DEGREES = 2f

        /** ... or past this translation difference, as a fraction of the page's distance. */
        const val MAP_REFINE_FRACTION = 0.05f

        internal fun mapsDisagree(a: FloatArray, b: FloatArray): Boolean {
            val ra = rotationOf(a)
            val rb = rotationOf(b)
            // trace(Raᵀ·Rb) = 1 + 2 cos θ
            var trace = 0.0
            for (i in 0..2) for (k in 0..2) trace += ra[k * 3 + i] * rb[k * 3 + i]
            val angle = Math.toDegrees(kotlin.math.acos(((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
            if (angle > MAP_REFINE_DEGREES) return true
            val dx = a[12] - b[12]; val dy = a[13] - b[13]; val dz = a[14] - b[14]
            val dist = kotlin.math.sqrt(b[12] * b[12] + b[13] * b[13] + b[14] * b[14])
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) > MAP_REFINE_FRACTION * dist
        }

        private fun samePages(a: List<AtlasPage>, b: List<AtlasPage>): Boolean {
            if (a.size != b.size) return false
            return a.indices.all { i -> samePage(a[i], b[i]) }
        }

        private fun samePage(a: AtlasPage, b: AtlasPage): Boolean = a === b || (
            a.pageNo == b.pageNo && a.width == b.width && a.height == b.height &&
                a.referenceWidthUnits == b.referenceWidthUnits && a.physicallyMetric == b.physicallyMetric &&
                a.canonicalFromPageRef().contentEquals(b.canonicalFromPageRef()) &&
                a.lumaRef().contentEquals(b.lumaRef())
            )

        /** Station spacing in map units: [Config.stationSpawnDistance] metres, or reference widths. */
        private fun PhotosphereTrackingSession.spawnUnits(reference: ReferenceImage): Double =
            if (reference.physicallyMetric) config.stationSpawnDistance.toDouble()
            else config.stationSpawnDistance.toDouble() * reference.referenceWidthUnits

        /** Row-major rotation of a GL column-major view (Double, for the station tracker). */
        internal fun rotationOf(view: FloatArray): DoubleArray = PhotosphereStationTracker.rotationOfView(view)

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
    val method: VisualMatchMethod = VisualMatchMethod.KPM_FULL,
)

/**
 * One frame for [PlanarFrontEnd.match]: the packed full-resolution luma as a direct buffer and as
 * bytes, the session clock, and the predicted camera-from-canonical (last accepted pose carried by the
 * gyro) for page prioritisation, or null.
 */
@ExperimentalSphereSlamRelocApi
internal class PlanarFrameInput(
    val luma: ByteBuffer,
    val lumaBytes: ByteArray,
    val timestampNs: Long,
    val nowMs: Long,
    val predictedCameraFromCanonical: FloatArray?,
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

    /** Match one frame with the session's context; defaults to the plain [match]. */
    fun match(input: PlanarFrameInput): PlanarMatchResult? = match(input.luma, input.timestampNs)

    /** Whether the pose gate accepted the pose returned by the last [match] (only called for non-null poses). */
    fun onVerdict(accepted: Boolean) {}

    /**
     * An independent matcher holding every page added here, for growing the atlas on another thread
     * while this one keeps matching; null when unsupported (the caller then rebuilds from pages). Must
     * be safe to call concurrently with [match] on this matcher.
     */
    fun fork(): PlanarFrontEnd? = null

    /** A fragment for the session's periodic diagnostic line (counters since the last call), or null. */
    fun diagnosticsAndReset(): String? = null

    /** The anticipation the session currently allows, applied every frame (unchanged is free). */
    fun setAnticipation(status: AnticipationStatus) {}
}

@ExperimentalSphereSlamRelocApi
internal fun interface PlanarFrontEndFactory {
    fun create(intrinsics: CameraIntrinsics): PlanarFrontEnd

    companion object {
        /**
         * SphereSLAM's planar KPM for the frame's intrinsics with [KpmMatchSpeedConfig]'s levers
         * ([KpmPlanarFrontEnd]).
         */
        fun kpm(speed: KpmMatchSpeedConfig = KpmMatchSpeedConfig()) =
            PlanarFrontEndFactory { intrinsics -> KpmPlanarFrontEnd(intrinsics, speed) }

        /** [kpm] with the default [KpmMatchSpeedConfig]. */
        val KPM: PlanarFrontEndFactory = kpm()
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

    /** Frames whose matcher returned a pose (before gating); `match.count` is the attempts. */
    var matched = 0
    val convert = Stage("convertMs")
    val setup = Stage("setupMs")
    val match = Stage("matchMs")
    val keyframe = Stage("keyframeMs")
    val growth = Stage("growthMs")
    private val stages = listOf(convert, setup, match, keyframe, growth)

    /**
     * `timing{frames=N attempts=A matched=M convertMs=mean/max setupMs=… matchMs=… keyframeMs=… growthMs=…}`
     * — `attempts` frames ran the matcher, `matched` of them got a pose back; resets.
     */
    fun summaryAndReset(): String {
        val out = "timing{frames=$frames attempts=${match.count} matched=$matched ${stages.joinToString(" ") { it.describe() }}}"
        frames = 0
        matched = 0
        stages.forEach { it.reset() }
        return out
    }
}

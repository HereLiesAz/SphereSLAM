# SphereSLAM public API contract

This document defines what downstream applications should treat as SphereSLAM's supported API.

SphereSLAM is pre-1.0; released versions are the repository's git tags, and `CHANGELOG.md` records
what each one changed. The supported surface is being hardened now so that a future 1.0 can actually
carry a meaningful compatibility guarantee.

## 1. Module boundaries

### Supported consumer modules

#### `:sphereslam`

Primary planar tracking and coverage API.

Recommended entry points:

- `SphereSlam`
- `SphereSlamCalibration`
- `SphereSlamEngine`
- `PlanarPage`
- `PlanarMatch`
- `PlanarMatchRequest`
- `PlanarPageView`
- `PlanarImage`
- `SphereSlamStandaloneSession`
- `AnchoredStandaloneSession`
- `SphereSlamTracker`
- `SphereSlamPoseMath`
- `OverlayPlacement`
- `MetricAnchor`
- `SphereCoverage`
- `PhotosphereMap`
- `PhotosphereReviewLoop`
- `CameraAttitudeProvider`
- `AttitudeBasis`
- `CoverageGlowProjection`
- `PhotosphereKeyframe`, `PhotosphereKeyframeStore`, `PhotosphereView`, `PhotosphereFingerprintFrame`

Supporting packages (all pure / Android-light, no ARCore types):

- `com.hereliesaz.sphereslam.math` — `RotationMath` (quaternions, row-major 3×3, `rotationAboutZ`,
  `rotateAboutCameraCentre`) and `RigidMath` (column-major 4×4 rigid/similarity math, OpenGL ↔ OpenCV
  flips). `:reloc`'s `RotationDeltaMath` / `PoseMath` delegate to these.
- `com.hereliesaz.sphereslam.attitude` — `GameRotationAttitudeSource`, `AttitudeSample`,
  `DeviceCameraRotation`, `AttitudeRotationBridge`, `AttitudeExtrapolator`, `DisplayPosePredictor`.
- `com.hereliesaz.sphereslam.camera` — `CameraIntrinsics`, `CameraIntrinsicsEstimator`, `LensDistortion`,
  `CameraIntrinsicsTransforms`, `CaptureRotation`, `LumaFrameTransform` (plus
  `LumaFrameBufferPool` / `packCropAndRotateInto` for allocation-free camera loops), `ProjectionMatrix`,
  `ScreenIntrinsics` (`FIT_CENTER`), `CameraFillCenter` (`FILL_CENTER`: track the full frame, apply
  the display crop only where the frame meets the screen).
- `com.hereliesaz.sphereslam.sidecar` — `HybridPoseHistory`, `HybridPageFrame`,
  `HybridKpmCorrection`, `HybridAnchorFusion`, `HybridKpmOutcome`, `HybridKpmDiagnostics`,
  `MetricPageRectification`.
- `com.hereliesaz.sphereslam.gyro` — `GyroCompensationMath`.

`SphereSlamEngine` is the lowest supported planar abstraction. Most applications should use
`SphereSlamStandaloneSession` or `SphereSlamTracker` instead.

#### `:overlay`

Optional rendering helper:

- `CoverageGlowView`
- `CoverageGlowRenderer`

The renderer re-projects pre-sampled coverage tiles (`setRegions`) through a lens (`setLens`) with
the camera rotation it reads on the GL thread every draw (`rotationSource`, or the pushed
`setWorldFromCamera`), in one of four live styles (`FillMode.TRIANGLES`, `COMPLEMENT`, `OUTLINE`,
`COMPLEMENT_OUTLINED`). See [Coverage glow rotation](#coverage-glow-rotation) and
[Embedding the coverage overlay](#embedding-the-coverage-overlay).

Because public overlay methods use `:sphereslam` types, the Gradle dependency is published with
`api(project(":sphereslam"))`.

### Experimental modules

#### `:reloc`

The entire OpenCV-backed relocalization/photosphere surface is marked with
`ExperimentalSphereSlamRelocApi`.

This is deliberate. Types such as `Fingerprint`, `Relocalizer`, `TileFingerprint`,
`TileMatcher`, `RobustTrackingLoop`, and `SphereSlamSession` are useful to advanced consumers,
but their shape is still changing before 1.0.

Because these public signatures expose OpenCV classes, `:reloc` publishes OpenCV as an `api`
dependency rather than hiding it behind `implementation`.

OpenCV's Java native library must be loaded by the host before any `:reloc` type that allocates
OpenCV objects (for example `Relocalizer`) is constructed; SphereSLAM's internal native loader loads
only `libsphereslam` and does not load OpenCV. `Relocalizer` and `SphereSlamSession` are
`AutoCloseable`: close them to release their native buffers (the session releases the fingerprints
handed to `supplyTile`, not the caller's `Relocalizer`).

`SphereSlamSession.onFrame` returns a column-major, camera-from-map pose in the OpenGL eye frame
(x right, y up, looking down -z), the same convention as the planar layer. `Relocalizer` results
stay row-major in OpenCV's camera convention (y down, z forward).

Depth-backed tiles (`TileTriangulator`, `TileGate`, `TileFingerprint`, `DepthScaleFit`) are building
blocks: they are not yet wired into `SphereSlamSession`'s map growth.

Wall-map relocalization helpers: `WallFeatureMap` grows a confidence-weighted feature map in the
fingerprint frame from each `RelocResult` (`growFromReloc`, or the pure `grow` with your own matches)
and hands the in-view subset back to `Relocalizer` (`visibleIndices` → `toFingerprint`).
`RectifyHomography` and `MetricMarks` take **column-major** views (OpenGL for `RectifyHomography`,
OpenCV for `MetricMarks`); `WallFeatureMap` takes the row-major OpenCV `RelocResult.cameraFromObject`.
Fingerprint object points are in the capture camera's OpenCV frame. Only `WallFeatureMap`'s
`descriptorMat` / `matchDescriptors` / `growFromReloc` / `toFingerprint` need OpenCV's native library.

`PlanarTracker` tracks one captured planar reference per frame and returns a column-major OpenGL
camera-from-plane view in the half-extent units passed to `setReference` (plane +X right, +Y up,
`z = 0`), bridging a brief miss with device attitude. It needs OpenCV's native library like
`Relocalizer`. For KPM-based planar tracking without OpenCV use `SphereSlamStandaloneSession`
(isotropic, metric page geometry) instead.

`PhotosphereTrackingSession` is the standalone photosphere runtime: feed `onFrame(Frame(luma,
intrinsics, timestampNs, attitude, cameraFromDevice, headingDeg, elevationDeg, rollDeg,
convertDurationNs))` from the camera-analysis worker and render `FrameResult.pose?.cameraFromMap` (column-major GL
camera-from-base-map) with `pose.projection`. The base map is gravity-level and north-referenced
(+X east, +Y up, −Z north — `AttitudeBasis`, the frame `PhotosphereFingerprintFrame` places
fingerprints in) and rotation-only, whatever the first frame looked at; the planar KPM pose (camera-from-fingerprint) is rebased through
`map_from_fingerprint`, solved once per reference on the first confirmed lock
(`Callbacks.onMapFromFingerprintSolved`) or supplied to `updatePrecisionLayer`. A supplied map is
checked against the same-frame solve on the first confirmed lock after it is set, and replaced (and
re-published) when they differ by more than 2° or 5 % of the page distance — a provisional
`PhotosphereFingerprintFrame.placement` guesses the range and assumes the page faces the camera. Passing no map with
the same reference keeps the map the session solved for it; a host map overrides it; a new reference
re-solves. Any change of reference, effective map, atlas page set (`atlasPages` is the complete set —
pass `atlasPages` back to keep grown pages), `forceRebuild` or frame intrinsics rebuilds the planar
layer and resets the tracking state, so the next lock needs `confirmationFrames` again. The base map
rotation is `C · R(q)ᵀ · W` (each frame's own `cameraFromDevice` `C`, its device→ENU attitude `R(q)`,
`W = AttitudeBasis.WORLD_FROM_MAP`). The frame is only north-referenced when the attitude is
(`TYPE_ROTATION_VECTOR`); a game-rotation source's yaw origin is its own. `Config.maxBridgeMs`
(measured from the last accepted frame) is the only gyro-bridge limit; it overrides
`trackingState.maxBridgeMs`. After a bridge, the next visual pose is smoothed from the bridged pose
that was shown. Past the bridge window, `Config.holdOffWall` (default true) keeps the last lock
rendered through the gyro rotation (`PoseSource.HELD`, camera centre held) while the wall is out of
view or the relock is confirming, until a visual relock or a precision-layer change; `false` restores
no pose past the bridge.

Photosphere localization (`Config.tileLocalization`, default true; `tileFixIntervalMs`,
`stationSpawnDistance` — metres for a metric reference, reference widths otherwise): after the first
wall lock relates the gyro frame to the map, keyframes become tiles of the current station; a
recognised tile fixes the camera rotation, and with `map_from_fingerprint` known the artwork is placed
off the wall as `PoseSource.PHOTOSPHERE` (instead of `HELD`). A camera more than
`stationSpawnDistance` from every station opens a new station (earlier ones kept); same-direction
tiles from two standpoints are triangulated into landmarks and per-tile planes for 6-DoF fixes from
any position. The public constructor takes an optional `TileDepthSource` (monocular inverse depth per
new tile, `TileDepthMap`), calibrated to landmarks, overlapping tiles and the wall plane within its
extent. Needs OpenCV (ORB): without it tile localization disables itself once. `stationCount` reports
the stations. Stations, tiles and the correction reset on a new reference or map. An exception while processing a frame enters `TrackingState.FATAL`
(`Callbacks.onFatalError`): terminal for a `FATAL`-severity failure (native/internal); for the
`RECOVERABLE` `ReferenceTooWeakException` (surfacing on the first frame after the background build
finishes) only the precision layer halts — base pose and keyframes continue, `isRecoverable` is true,
and an `updatePrecisionLayer` with another reference, changed pages or `forceRebuild` resumes.
`TrackedPose.reprojectionError` is a mean squared error in pixels² of the image the pose was solved
in (KPM: the matcher's downscaled image; flow: the full frame). An exception inside a background
atlas-growth attempt is **not** fatal: it is reported as an `atlas growth failed` diagnostic and the
live matcher is kept. `PhotosphereAtlasGrowth`, `TargetQuality` and `TrackingFailureClassifier`
are usable on their own.

Per-frame matching cost is set by `Config.matchSpeed` (`KpmMatchSpeedConfig`): KPM matches a
half-resolution luma by default (intrinsics scaled to match, same pose convention), poses between full
solves come from Lucas–Kanade flow + PnP against the KPM inlier correspondences (full solve on loss
and at least every 10 frames / 500 ms), and a multi-page atlas is matched predicted-page-first (a
focused miss defers the all-pages search to the next frame).
`TrackedPose.matchMethod` (`VisualMatchMethod`) says which path produced a pose. Flow tracking needs
OpenCV's native library (`opencv_java5`) loaded, as for `Relocalizer`; without it flow switches itself
off and every frame is a full KPM solve. Flow poses never grow the atlas.
`KpmMatchSpeedConfig.FULL_KPM_EVERY_FRAME` restores the previous every-frame full-resolution solve.
`SphereSlamStandaloneSession.match(luma, timestampNs, PlanarMatchRequest(pageNo, maxCorrespondences))`
exposes the underlying single-page match and inlier correspondences (`PlanarMatchRequest(images = …)`
matches only the given `PlanarImage`s: pages and their `addReferenceView` previews), and
`SphereSlamStandaloneSession.cameraFromCanonical(pageNo, cameraFromPage3x4)` converts an externally
solved page pose with the same conversion as `match`.

Matcher builds and atlas growth run on the session's own background thread: growth forks the live
matcher (`SphereSlamStandaloneSession.fork()`), adds the page to the fork and swaps it in on a later
frame; `onFrame` never blocks on page feature extraction or index rebuilds. Until the first build
lands (and briefly after an intrinsics change) a frame carries only the base pose
(`FrameResult.pose == null`). `Frame.luma` is not retained after `onFrame`, so a pooled buffer
(`LumaFrameBufferPool`) may be reused for the next frame.

`displayPosePredictor` (a `:sphereslam` `DisplayPosePredictor`), when set, receives every rendered
pose (bridged ones included) with its frame's `attitude` and `cameraFromDevice` and is cleared when a
frame yields no pose, on a fatal or recoverable failure and on `close()`;
the GL thread then calls `predictInto(extrapolator, now, out)` per draw. Use the same attitude sensor
for `Frame.attitude` and the extrapolator.

`anticipationResolution` (an `AnticipationResolution`: half-spheres of previews by distance,
latitude and longitude) is the most the session anticipates; `anticipationThrottle` how far battery,
heat and its own load cut it; `devicePressure` where battery and heat come from
(`AndroidDevicePressure`); `anticipation` what it currently uses and why. Changes apply from the next
frame without a restart (README: Previews).

Every `Config.metricsIntervalMs` (5 s) `Callbacks.onDiagnostic` receives the last accepted KPM match
(`page inliers error ageMs matchMs method=`) or `KPM no-lock`, followed by
`timing{frames attempts matched convertMs setupMs matchMs keyframeMs growthMs}` (`attempts` frames
ran the matcher, `matched` got a pose back; `mean/max` ms over the
window; `convertMs` comes from `Frame.convertDurationNs`, `growthMs` is only the analyzer-side cost of
starting a growth attempt), then `tiles{…}`, `rect{planned swept kpm solved frontal maxObliq}` and
`previews{images features builtMs ready anticipation factor battery heat compute searchable tried guided solved}` when they apply (README: Diagnostics). The line is a diagnostic, not a stable format.

#### `:models`

Optional on-device model wrappers are marked with `ExperimentalSphereSlamModelsApi`. They are not
part of the compatibility-guaranteed planar API.

`DepthEstimator` runs MiDaS v2.1 Small (fp16 TFLite) on **LiteRT with the GPU delegate**;
`DepthEstimatorOptions(backend = GPU | CPU, cpuThreads = 2, allowFp16Precision = true,
gpuProgramCache = true)` selects the backend, and a GPU that fails to initialise falls back to the
XNNPACK CPU path (logged as an error; `activeBackend` reports which). All model work runs on the
estimator's own `SphereSLAM-depth` thread, so its methods may be called from any thread (they block;
do not call from the camera analyzer, GL or UI thread). It accepts a luma plane directly via
`estimateLuma`. `DepthMap` is MiDaS relative inverse depth, row-major, at most `outMaxDim` on the long
side, in the model's squashed square grid. `SuperPointDetector` and `LowLightEnhancer` stay on ONNX
Runtime (CPU). Model provenance and sizes: [MODELS.md](MODELS.md).

### Internal modules

- `:core:common`
- `:core:nativebridge`

Cross-module implementation declarations such as `NativeLibLoader` and `KpmBridge` are marked
with `InternalSphereSlamApi` at error-level opt-in.

They remain bytecode-visible only because separate Gradle modules must call across that boundary.
Application code must not bind directly to them.

## 2. Pose and transform conventions

Transform names follow **destinationFromSource**.

### Raw KPM result

`PlanarMatch.cameraFromPage3x4`

- row-major
- 3x4
- page -> camera
- translation is in KPM page units
- physically millimetric only when the reference DPI is physically calibrated

`SphereSlamTracker.Observation.cameraFromPage3x4` uses the same convention.

### Canonical render pose

`SphereSlamStandaloneSession.Pose.cameraFromCanonical`

- column-major
- 4x4
- canonical wall -> camera/view
- translation is metres only when `physicallyMetric == true`

### Content placement

`MetricAnchor.canonicalFromContent`

- column-major
- 4x4
- content-local -> canonical wall
- translation is metres

`AnchoredStandaloneSession.AnchoredPose.cameraFromContent`

- column-major
- 4x4
- content-local -> camera/view
- computed as `cameraFromCanonical * canonicalFromContent`

### Device → camera rotation

Attitude sensors report the device **body** frame (x right, y up, z out of the screen in the natural
orientation). The rotation into a rear camera's OpenGL frame depends on which image the pose is
solved in (`DeviceCameraRotation`):

| Tracked image | `cameraFromDevice` |
| --- | --- |
| raw sensor buffer | `R_z(sensorOrientation)` |
| display-upright image (buffer rotated CW by CameraX `rotationDegrees`) | `R_z(displayRotation)` = `bodyToDisplay` |
| buffer rotated CW by `r` | `R_z(sensorOrientation − r)` |

CameraX `rotationDegrees` itself is **not** the device→camera angle (it coincides with the raw-buffer
answer only at `ROTATION_0`). `AttitudePosePredictor(cameraFromDevice)` and
`AttitudeRotationBridge(cameraFromDevice)` take this matrix; the bridge's `bridgeFunction` plugs into
`RobustTrackingLoop(bridgeRotatedPose = …)`.

### Coverage glow rotation

`worldFromCamera` (coverage glow, `CoverageGlowProjection`, `CoverageGlowRenderer.RotationSource`,
`AttitudeExtrapolator.worldFromCameraAt`) is a **row-major 3×3 whose columns are the OpenGL camera
axes** — x right, y up, z backward (the camera looks down −z) — **in East-North-Up world**, the frame
of `TYPE_ROTATION_VECTOR`. It equals `deviceToWorld · cameraFromDeviceᵀ`
(`CoverageGlowProjection.worldFromCamera(deviceToWorld, cameraFromDevice)`); for a display-upright
preview `cameraFromDevice = DeviceCameraRotation.cameraFromDeviceForDisplayFrame(displayRotationDeg)`.
`worldFromCamera(heading, elevation, roll)` gives the same matrix from the `CameraAttitudeProvider`
angles, at every elevation including straight up and down — provided all three come from one sample
(`CameraAttitudeProvider.latestAttitude()`).

Times passed to `RotationSource.worldFromCamera(frameTimeNanos, out)`, `drawEmbedded(frameTimeNanos)`,
`AttitudeExtrapolator` and `DisplayPosePredictor.predictInto(extrapolator, targetTimeNs, out)` are in
the `SystemClock.elapsedRealtimeNanos()` base, the `SensorEvent.timestamp` base.

`CoverageGlowProjection.Lens` is the drawn surface's pinhole: `fromIntrinsics` (any principal point;
for a `FILL_CENTER` preview use `CameraFillCenter.intrinsics(frame, viewW, viewH)`) or
`fromFieldOfView` (centred).

### Display pose prediction

`DisplayPosePredictor.update(cameraFromWorld, frameAttitude, cameraFromDevice, frameTimestampNs)`
takes a column-major camera-from-world GL pose, the body→world quaternion `[x, y, z, w]` at that
frame, and the row-major GL-camera-from-device of the image the pose was solved in. `predictInto`
writes `[ΔR·R | ΔR·t]` with `ΔR = C · R(q_now)ᵀ · R(q_frame) · Cᵀ` (camera centre held). Any fixed
world frame works, but the frame and current attitude must come from the same sensor.

### Pixel directions

`PhotosphereMap.directionOfPixel` rotates a pinhole ray through heading, elevation and roll
(`CameraAttitudeProvider.cameraRollDegrees(latestAttitude().deviceToWorld, cameraFromDevice)` for the
image you track; `latestRollDegrees()` is the roll of the device's natural +x axis and equals it only
at display rotation 0). Do not add `atan` pixel offsets
to heading/elevation: that ignores roll and is wrong off-axis.

### Poles (floors and ceilings)

Near the zenith/nadir the camera-axis heading is dominated by sensor noise (its error grows as
`1/cos(elevation)`), but a heading, elevation and roll decoded from ONE rotation still describe it
exactly: roll is measured from the horizontal right of that same heading (`AttitudeBasis`). So:

- read the three from one `CameraAttitudeProvider.latestAttitude()` snapshot, never one getter each;
- never key, anchor or orient anything on heading alone. Use the full basis
  (`AttitudeBasis.basis`) or the pole-stable `AttitudeBasis.facingAzimuthDeg` — the bearing the camera
  faces: the axis heading at the horizon, the bearing the top of the image points along over a floor.

The library follows this: `PhotosphereMap.recordView` anchors an unanchored map at the facing
azimuth, `tilesInView` takes the roll, `PhotosphereKeyframeStore.view` always returns the live
attitude (a polar tile's keyframe may have been taken facing anywhere), and
`PhotosphereFingerprintFrame.placement` takes the page's +Y from the camera's image-up.

### Deprecated pose names

The following aliases remain temporarily for migration:

| Old | Replacement |
| --- | --- |
| `Pose.viewMatrix` | `Pose.cameraFromCanonical` |
| `AnchoredPose.viewFromContent` | `AnchoredPose.cameraFromContent` |
| `Observation.pageToCamera3x4` | `Observation.cameraFromPage3x4` |
| `OverlayPlacement.viewFromContent(...)` | `OverlayPlacement.cameraFromContent(...)` |
| `CoverageGlowProjection.projectRegions(regions, heading, elevation, hFov, vFov)` | `projectRegions(regions, worldFromCamera, lens)` or `prepareRegions` + `projectInto` |
| `CoverageGlowView.update(map, heading, elevation, hFov, vFov)` | `CoverageGlowView.updateRegions(map)` + `trackAtDisplayRate(source)`, or `update(map, worldFromCamera, lens)` |

The replacement names are not cosmetic: they make transform direction and coordinate frame explicit.

## 3. Ownership and mutability

Supported API values own their array-backed state.

The library defensively copies:

- `PlanarMatch.cameraFromPage3x4`
- `SphereSlamStandaloneSession.Reference.canonicalFromPage`
- `SphereSlamStandaloneSession.Pose.cameraFromCanonical`
- `SphereSlamTracker.Observation.cameraFromPage3x4`
- `MetricAnchor.canonicalFromContent`
- `AnchoredStandaloneSession.AnchoredPose.cameraFromContent`
- `PanoramaTile.representativeOrientation`
- all array fields in `PhotosphereMapSnapshot`
- `CoverageGlowRenderer.hazeColor`, `CoverageGlowRenderer.setWorldFromCamera`
- `AttitudeSample.quaternion`, `PhotosphereKeyframe` arrays, `HybridPoseHistory.Sample` matrices,
  `HybridKpmCorrection.Accepted` matrices, `MetricPageRectification.Geometry` arrays,
  `PhotosphereFingerprintFrame.Placement.mapFromFingerprint`, `GyroCompensationMath.Result.Within.homography`

Mutating an array supplied to or returned from these APIs does not mutate library state.

Deliberate exceptions, for allocation-free camera and render loops:

- `LumaFrameBufferPool` returns **pooled** arrays: the array in a returned `RotatedLuma` is overwritten
  `slots` calls later. Keep at most `slots − 1` frames; copy anything retained longer.
- `LumaFrameTransform.packCropAndRotateInto` / `packAndRotateInto` write into, and return, the
  caller's `destination` array.
- `*Into` / `*At` methods (`AttitudeExtrapolator`, `DisplayPosePredictor`,
  `PreparedRegions.projectInto`) write into caller-supplied `out` arrays.
- `PhotosphereTrackingSession.Frame.luma` is read during `onFrame` without a copy (do not mutate it
  until `onFrame` returns) and never retained afterwards.

## 4. Construction and lifecycle

### Standalone session

Consumer construction is:

~~~kotlin
SphereSlamStandaloneSession(width, height, calibration)
~~~

The engine-factory injection constructor is internal and exists only for library tests.

`SphereSlamStandaloneSession`, `AnchoredStandaloneSession`, `SphereSlamEngine`, and
`SphereSlamTracker` are `AutoCloseable`. Consumers should close them when the camera/tracking
lifecycle ends.

### Tracker

`SphereSlamTracker.Observation.forTesting(...)` builds an observation for host unit tests and
replay; production observations come only from the tracker.

Consumer construction is simply:

~~~kotlin
SphereSlamTracker()
~~~

The native implementation interface and executor injection are internal test seams.

`setReference` replaces the current reference; `clearReference` empties the native atlas. A call
that races `close()` is ignored rather than touching a released engine.

### Native availability

`SphereSlam.isOperational()` is the fail-closed probe: it requires `isAvailable()` and then creates,
checks and closes a calibrated native engine. It never throws.

The native engine ships for `arm64-v8a` and `armeabi-v7a` only. On any other ABI (for example
x86/x86_64 emulators), or on any other native load failure, `SphereSlam.isAvailable()` reports
false; neither it nor `KpmBridge` throws. The real KPM engine is compiled only when the pinned
artoolkitX submodule (`third_party/artoolkitx`) is checked out; without it the native build fails
unless stubs are requested explicitly (`-Psphereslam.allowKpmStubs=true`), which ship stub entry
points that report KPM unavailable. `libsphereslam.so` links libc++ statically. Building the native code requires CMake 4.4.4, and JitPack builds
must check the submodule out (see `jitpack.yml` and the README's *Building from source*). `:core:common` and `:core:nativebridge` neither depend on
nor load OpenCV.

### Input validation

- `SphereSlamStandaloneSession.addReference` throws `IllegalArgumentException` when
  `canonicalFromPage` is not a rigid transform.
- `CoverageGlowProjection.project` and `projectRegions` throw when a field of view is outside `(0, 180)` degrees.
  The full-rotation overloads throw for a non-finite or wrongly sized `worldFromCamera`;
  `CoverageGlowProjection.Lens` rejects non-positive scales; `prepareRegions` rejects a non-positive
  `maxSegmentDeg` or non-finite region bounds.
- `PhotosphereMap.fromSnapshot` rejects a non-finite `wallHeadingDeg` and normalizes a finite one.
- `SphereCoverage` and `PhotosphereMap` auto-anchor their wall heading only from an in-band sample.

## 5. Threading

- `SphereSlamStandaloneSession`: drive from one camera-analysis worker.
- `AnchoredStandaloneSession.match`: drive from the same camera worker as its base session.
- `SphereSlamTracker`: owns its worker; `submitFrame` is non-blocking and retains only the newest
  pending frame.
- `PhotosphereMap`, `PhotosphereReviewLoop`, and experimental `:reloc` state machines are not
  generally thread-safe unless explicitly documented otherwise.
- `CoverageGlowRenderer.setTriangles`, `setRegions`, `setLens`, `setWorldFromCamera`,
  `rotationSource`, `fillMode`, `hazeColor`, `hazeAlpha`, `outlineAlpha` and `outlineWidthPx` are safe
  to call from any thread; `createGlResources`, `drawEmbedded` and `releaseGlResources` must run on
  the GL thread, and `RotationSource.worldFromCamera` is invoked there.
- `AttitudeExtrapolator` and `DisplayPosePredictor` are thread-safe; their `*Into` / `*At` reads
  allocate nothing.
- `CameraAttitudeProvider` reads, `start` and `stop` are safe from any thread (`latestAttitude()` is
  one consistent sample; the single-angle getters may each see a different one); its
  `attitudeExtrapolator` is written on the sensor thread and read from any thread.
- `LumaFrameBufferPool` is synchronized; use one pool per analysis stream.
- `GameRotationAttitudeSource` reads and `AttitudeRotationBridge` are thread-safe.
- `PhotosphereKeyframeStore`, `HybridPoseHistory` and `HybridAnchorFusion` are not thread-safe.
- `PlanarTracker` is not thread-safe; drive it from one camera worker.
- `PhotosphereTrackingSession` synchronizes its methods; drive `onFrame` from one camera worker.
  `displayPosePredictor`, `anticipationResolution`, `anticipationThrottle` and `devicePressure` may be
  set from any thread; the predictor it feeds is read on the GL thread.
  Callbacks run synchronously on the `onFrame` thread, including outcomes of its background matcher
  builds and atlas growth.
- `SphereSlamStandaloneSession.fork()` may run on another thread while the session matches, but not
  concurrently with `addReference`, `reset` or `close` on it. Native KPM sessions each have their own
  lock, so different sessions run in parallel.

### Embedding the coverage overlay

`CoverageGlowRenderer` can draw inside a host renderer: call `createGlResources()` from the host's
`onSurfaceCreated` and `drawEmbedded()` from its `onDrawFrame` (for example before drawing artwork).
`drawEmbedded` never clears and blends premultiplied into the bound framebuffer and viewport. It
restores the GL state it touches (program, framebuffer, viewport, blend enable/function/equation,
depth/stencil/scissor/cull, colour mask, clear colour, array buffer, texture unit 0). Vertex data
comes from the renderer's own buffers; on an ES 3 context it draws in its own VAO and restores the
host's VAO binding, so host vertex-attribute state is untouched. On ES 2 it restores the enable flag
and format of the attribute locations it uses, but GLES20 cannot read back an attribute's pointer
offset — re-specify any pointer you keep on those locations (normally location 0) after the call.
After a GL context loss call `onGlContextLost()` (names are forgotten, never deleted);
`createGlResources()` in the new context's `onSurfaceCreated` starts a fresh generation. `FillMode.COMPLEMENT` draws a wash with holes:

~~~kotlin
renderer.fillMode = CoverageGlowRenderer.FillMode.COMPLEMENT
renderer.hazeColor = CoverageGlowRenderer.WHITE
renderer.setLens(CoverageGlowProjection.Lens.fromIntrinsics(shownIntrinsics))
renderer.rotationSource = CoverageGlowRenderer.RotationSource { t, out ->
    attitude.attitudeExtrapolator.worldFromCameraAt(t, cameraFromDisplay, out)
}
renderer.setRegions(map.currentRegions()) // per keyframe; re-projected every draw
~~~

Rotation convention: `worldFromCamera` is row-major 3×3 whose columns are the GL camera axes (x right,
y up, z backward) in East-North-Up world (`CoverageGlowProjection.worldFromCamera`). The
heading/elevation `projectRegions` and `CoverageGlowView.update(map, heading, ...)` are deprecated.

## 6. Experimental opt-in

### Reloc

~~~kotlin
@OptIn(ExperimentalSphereSlamRelocApi::class)
fun advancedRelocalization() {
    // ...
}
~~~

### Models

~~~kotlin
@OptIn(ExperimentalSphereSlamModelsApi::class)
fun modelHelpers() {
    // ...
}
~~~

These opt-ins acknowledge source/binary changes may occur before 1.0.

## 7. Compatibility policy

Before 1.0:

- the supported `:sphereslam` and `:overlay` surfaces are the compatibility target;
- intentional breaking changes must be documented and versioned;
- deprecated migration aliases are preferred before removal when practical;
- experimental modules may change without compatibility shims;
- internal modules have no consumer compatibility guarantee.

At 1.0, the supported public surface should be treated as semantically versioned and compatibility
changes should require the appropriate major-version policy.

## 8. 0.23.3 API-hardening notes

The 0.23.3 API-hardening pass made these intentional changes:

- JDK 21 is the build toolchain while Android bytecode remains Java 17 compatible.
- `SphereSlamStandaloneSession.EngineFactory` is internal.
- `SphereSlamTracker.Native` and worker injection are internal.
- JNI/native loader APIs are compiler-gated internal APIs.
- public pose/placement/snapshot arrays are defensively owned.
- pose names now use destination-from-source terminology.
- `:reloc` and `:models` are compiler-marked experimental.
- `:reloc` publishes OpenCV on the consumer compile classpath.
- `:overlay` publishes `:sphereslam` on the consumer compile classpath.
- the incorrect claim that the API was already “stable/frozen as of 1.0” was removed.

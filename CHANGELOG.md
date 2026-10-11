# Changelog

## Unreleased — audit fixes

### Coverage glow: display-rate, full-rotation tracking and styles (additive)

Why the glow felt loosely attached to the scene (diagnosis):

- **No roll.** `CoverageGlowProjection.projectRegions(heading, elevation, hFov, vFov)` builds a
  horizon-levelled camera basis, so any device tilt rotated the glow against the preview by the full
  roll angle (an error of `roll × distance from centre` on screen).
- **Pole dropout.** Heading is undefined near vertical, so the projection (and
  `CameraAttitudeProvider`'s heading) returned nothing when looking at the ceiling or floor, and the
  host fell back to a full wash.
- **Cadence and latency.** Hosts re-projected only when the vision analyzer delivered a tracked
  frame (analyzer rate, after the 40–150+ ms solve, nothing while untracked), from an attitude read at
  `SENSOR_DELAY_UI` (~16 Hz, up to ~60 ms stale). At a 90°/s pan that is 10–20° of lag, and the glow
  froze whenever tracking dropped.
- **Lens.** Only a centred principal point was supported (FOV in, no intrinsics).
- Tile sampling was adequate (4×4 per tile; meridians project straight, parallels are curves), but
  fixed per tile rather than per degree.

Additions:

- `CoverageGlowProjection.worldFromCamera(heading, elevation, roll)` (the `directionOfPixel` /
  `CameraAttitudeProvider` convention) and `worldFromCamera(deviceToWorld, cameraFromDevice)`:
  row-major 3×3 whose columns are the GL camera axes in East-North-Up.
- `CoverageGlowProjection.Lens` (`fromFieldOfView`, `fromIntrinsics` incl. off-centre principal
  point), `projectRegions(regions, worldFromCamera, lens, maxSegmentDeg = 5)`,
  `project(directions, worldFromCamera, lens)`, and `prepareRegions(...)` →
  `PreparedRegions.projectInto(worldFromCamera, lens, out)` (pre-sampled unit vectors, no trig or
  allocation per frame; tile edges cut into ≤ 5° segments; seam-exact across 0/360°).
  The heading/elevation `projectRegions` is **deprecated** (unchanged behaviour).
- `CoverageGlowRenderer`: display-rate re-projection on the GL thread — `setRegions`, `setLens`,
  `rotationSource: RotationSource` (queried every draw with the frame time) or `setWorldFromCamera`;
  `drawEmbedded(frameTimeNanos)`. New styles `FillMode.OUTLINE` (soft edge glow on the needs-update
  side of the current tiles) and `FillMode.COMPLEMENT_OUTLINED` (wash + edge), with `outlineAlpha`,
  `outlineWidthPx`; `hazeColor` / `hazeAlpha` are now safe to change from any thread while drawing,
  plus `setHazeColorArgb`. Defaults unchanged (`TRIANGLES`, hot pink at 0.2).
- `CoverageGlowView`: `updateRegions(map)`, `update(map, worldFromCamera, lens)`,
  `trackAtDisplayRate(source)`; the heading/elevation `update` is deprecated.
- `attitude.AttitudeExtrapolator`: timestamped attitude history — slerp inside it, constant-velocity
  extrapolation (capped, default 50 ms) past it; `quaternionAt`, `deviceToWorldAt`,
  `worldFromCameraAt`; thread-safe, allocation-free reads.
- `CameraAttitudeProvider`: `start(samplingPeriodUs)` (default unchanged), `latestSample()`, and
  `attitudeExtrapolator` fed by every sample.
- `attitude.DisplayPosePredictor`: rotates the last vision pose by the attitude change since its
  frame (same math as the IMU bridge, camera centre held, no re-referencing) for per-draw pose
  prediction between vision updates; `predictInto(currentAttitude, out)` /
  `predictInto(extrapolator, targetTimeNs, out)` are allocation-free and thread-safe.
- `:reloc` `PhotosphereTrackingSession.displayPosePredictor`: when set, every rendered pose is
  published to it with its frame's attitude and `cameraFromDevice` (cleared when a frame yields no
  pose), so the GL thread can draw the artwork pose at display rate between analyzer updates.
- `:reloc` `PhotosphereTrackingSession`: the periodic (`metricsIntervalMs`, 5 s) KPM diagnostic now
  carries per-stage timing over the window — `timing{frames matched convertMs setupMs matchMs
  keyframeMs growthMs}` as `mean/max` ms — and is also emitted without a lock (`KPM no-lock`).
  `Frame(convertDurationNs = …)` lets the host report its Y-plane pack/rotate time.

### App → library consolidation, part 2 (additive)

The SLAM pieces deferred from the first consolidation. All additions are new API.

- New `com.hereliesaz.sphereslam.camera.CameraFillCenter`: `FILL_CENTER` display math beside
  `ScreenIntrinsics.fitCenter` — `scale`, `intrinsics` (rescale to cover, then centre-crop, rounded at
  view resolution so registration stays within ½ view pixel), `projection`, `viewToFrameNormalized`
  and the new inverse `frameToViewNormalized`, `fieldOfViewDegrees`, `screenIntrinsics` /
  `screenIntrinsicsFallback`. `ScreenIntrinsics` gains `fillCenter(...)` / `fillCenterFallback(...)`
  delegates. Ported with its tests from GraffitiXR `rendering.CameraFillCenter`.
- `:reloc` `PhotosphereTrackingSession`: the standalone (no-ARCore) photosphere tracking runtime,
  frame in / pose out (packed display-upright luma + `CameraIntrinsics` + timestamp +
  `AttitudeSample` + body→camera rotation in; `FrameResult` / `TrackedPose` out, plus `Callbacks` for
  keyframes, `map_from_fingerprint`, reference/atlas registration, failures, state and diagnostics).
  Base rotation-only photosphere pose from the first attitude, quality-gated `PhotosphereKeyframe`s,
  same-frame `map_from_fingerprint` solve and rebasing of the planar KPM pose into the map,
  `RobustTrackingLoop` gating with an optional `correctAcceptedPose` hook, a ≤ 400 ms gyro bridge,
  planar-session rebuild on an intrinsics change, `unitsPerPixel`, and multi-page atlas growth.
  Ported from GraffitiXR `SphereSlamStandaloneTrackingAnalyzer`; CameraX, MobileGS, depth and UI stay
  in the host.
- `:reloc` `PhotosphereAtlasGrowth` (page proposal around the optical axis, overlap/baseline/margin
  gates, perspective rectification through `MetricPageRectification.warpQuadToRect` instead of
  OpenCV/Bitmap), `TargetQuality` / `TargetQualityConfig` / `TargetQualityReport` /
  `ReferenceTooWeakException` (keyframe and page quality gate), and the failure taxonomy
  `TrackingFailureReason` / `TrackingFailureSeverity` / `TrackingFailureEvent` /
  `TrackingFailureClassifier` (no user-facing strings). Ported from GraffitiXR `StandaloneAtlasGrowth`,
  `StandaloneTargetQuality` and `StandaloneFailure`.
- `:reloc` relocalizer convergence with GraffitiXR's native `MobileGS` (pure Kotlin, no new JNI):
  `WallPlane` (PCA plane fit, `transformedBy`), `RectifyHomography` (plane-induced homography
  between the fingerprint capture view and the live view, obliquity, `warpCurrentToReference`;
  **fix:** the native `computeRectifyHomography` used `R − t·nᵀ/d` for a plane written `nᵀX = d`,
  which is wrong whenever the cameras translate — the library uses `R + t·nᵀ/d`, pinned by a
  synthetic test), `WallFeatureMap` (the self-growing wall feature map of `growMapFromReloc`:
  confidence re-observation, capacity pruning, wall-plane back-projection, frustum gate
  `visibleIndices`, `toFingerprint` for `Relocalizer` (optionally merged with the base fingerprint),
  snapshot / native-blob round-trip with GraffitiXR's `WallFeatureMap` model and
  `exportWallFeatureMap` layout), and `InverseDepthMap` / `RadialDepthPlacement` (the
  `setLatestDepthMap` radial placement of off-wall features, now calibrated with the robust
  `DepthScaleFit` instead of plain least squares). `MetricMarks` adds single-view plane
  back-projection (`backProjectToPlane`, `backProjectToCameraPlane`), two-view triangulation through
  `TileTriangulator` (`triangulateTwoView`) and the display-orientation view helpers, ported from
  GraffitiXR `anchor.PlaneMarks` / `anchor.MetricMarks`.
- `:reloc` `PlanarTracker`: live planar tracking of one captured reference with a gyro bridge —
  packed luma + `CameraIntrinsics` (+ optional `AttitudeSample` and body→camera rotation) in, an
  OpenGL camera-from-plane view matrix in the caller's half-extent units out. Same vision algorithm
  and gates as GraffitiXR's native `HomographyTracker` (ORB, Lowe 0.75, `solvePnPRansac` + IPPE
  refine, ≥ 30 reference features, ≥ 12 matches, ≥ 8 inliers, inlier ratio ≥ 0.35, inlier spread
  ≥ 25 % per axis) on the OpenCV Java `Relocalizer`, with `BridgedHomographyTracker`'s ≤ 400 ms
  rotation-only bridge and linearly decaying confidence. Replaces the app's `HomographyTracker`
  JNI stack. Supporting additions: `Relocalizer.relocalizeDetailed` / `RelocDetail` (match count,
  inlier ratio and inlier bounding box), `PlanarGeometry.pixelToExtents` and
  `PlanarFingerprint.fromReferenceExtents` (reference stretched onto explicit half-extents).

### App → library consolidation (additive)

Generic code moved out of GraffitiXR. All additions are new API; nothing existing was removed or
changed incompatibly.

- New supported `com.hereliesaz.sphereslam.math.RotationMath` (quaternion / row-major 3×3 math,
  `rotationAboutZ`, `cameraRotationDelta`, public `rotateAboutCameraCentre`, `conjugateMat3`,
  `rotationAngleDegrees`). `:reloc` `RotationDeltaMath` delegates to it and gains `rotationAboutZ`
  and a public `rotateAboutCameraCentre`.
- New supported `com.hereliesaz.sphereslam.math.RigidMath` (column-major 4×4: `multiply`,
  `rigidInverse`, `similarityInverse`, `scaleOf`, `translationNorm`, `cameraCentre`,
  `rotationAngleDeg`, `rotationDeltaDeg`, quaternion helpers, and the OpenGL ↔ OpenCV flips
  `glViewToCv` / `cvViewToGl` / `cvRowMajorToGlColumnMajor`). `:reloc` `PoseMath` delegates to it
  and gains `scaleOf`, `similarityInverse`, `translationNorm`, `rotationAngleDeg`, `glViewToCv` and
  `cvRowMajorToGlColumnMajor`. The private copies in `SphereSlamPoseMath`, `OverlayPlacement`,
  `PoseBlend`, `PoseAcceptancePolicy` and `SphereSlamSession.openCvToOpenGlColumnMajor` now use it
  (same results; `PoseBlend` still uses the camera centre).
- New `com.hereliesaz.sphereslam.attitude` package: `GameRotationAttitudeSource`
  (`TYPE_GAME_ROTATION_VECTOR` quaternion, accuracy, sensor timestamps), `AttitudeSample`,
  `DeviceCameraRotation` (settles device→camera rotation: `R_z(sensorOrientation − appliedImageRotation)`;
  display-upright frame = `bodyToDisplay(displayRotation)`; CameraX `rotationDegrees` itself is not
  the device→camera angle), and `AttitudeRotationBridge` (incremental, camera-centre-holding rotation
  bridge with a `bridgeFunction` for `RobustTrackingLoop.bridgeRotatedPose`). `AttitudePosePredictor`
  gains `AttitudeSample` overloads. Tests pin `ROTATION_0/90/180/270` against a model of
  `SensorManager.remapCoordinateSystem`.
- `SphereSlam.isOperational(frameWidth = 640, frameHeight = 480)`: fail-closed probe that creates,
  checks and closes a calibrated native engine; never throws (replaces GraffitiXR's
  `SphereSlamRuntimeProbe`).
- `SphereSlamTracker.Observation.forTesting(...)`: public factory for host tests and replay
  (the constructor stays internal; the tracker never reads a built observation).
- New `com.hereliesaz.sphereslam.camera` package: `CameraIntrinsics`, `CameraIntrinsicsEstimator`
  (Camera2 characteristics → intrinsics, cached per camera id, `RawCalibration`; fix: an
  aspect-changing stream such as 16:9 from a 4:3 sensor is now a centred crop plus uniform scale
  instead of independent x/y scaling), `CameraIntrinsicsTransforms` (`crop`, `rescale`, `rotate`),
  `CaptureRotation` (`rotateIntrinsics`, `unrotateIntrinsics`, `rotatePixel`; non-quarter-turn angles
  now throw), `LumaFrameTransform` / `RotatedLuma` (strided Y-plane pack, crop, rotate),
  `ProjectionMatrix.buildFrom`, and `ScreenIntrinsics` (`fitCenter`, `fallback`).
- `PhotosphereMap.currentTiles()` / `currentRegions()` (the up-to-date complement of
  `tilesNeedingUpdate()` / `regionsNeedingUpdate()`), and `ProjectionMatrix.horizontalFovDegrees` /
  `verticalFovDegrees`.
- `:overlay` `CoverageGlowRenderer` can be embedded in a host renderer: `createGlResources()`,
  `drawEmbedded()` (non-clearing, premultiplied, draws into the currently bound framebuffer and
  viewport and restores the GL state it touches) and `releaseGlResources()`. New
  `FillMode.COMPLEMENT` draws a wash over everything except the supplied triangles (holes =
  `projectRegions(map.currentRegions(), …)`), using a private offscreen mask, so no stencil/depth
  buffer is needed; `FillMode.TRIANGLES` (default) keeps the haze behaviour. `CoverageGlowRenderer.WHITE`
  added. GL objects are deleted only on the EGL context that created them.
- New `com.hereliesaz.sphereslam.sidecar` package (matrices only, no ARCore types) for
  primary-tracker + KPM sidecar fusion: `HybridPoseHistory`, `HybridPageFrame`,
  `HybridKpmCorrection` (gates + solve, `Reject`, `Accepted`, `Decision`, public `solveRaw`),
  `HybridKpmOutcome` / `HybridKpmDiagnostics`, `HybridAnchorFusion` (anchor-local correction with
  repeated-observation agreement before a large move; `agrees`, `HYBRID_AGREEMENT_*`, `State`,
  `Diagnostics`), and `MetricPageRectification` (metric fronto-parallel page from a wall plane;
  luma arrays in, pure-Kotlin perspective resample instead of OpenCV/Bitmap).
- Photosphere keyframes: `PhotosphereKeyframe`, `PhotosphereKeyframeStore` (feeds a
  `PhotosphereMap`, anchors its wall heading, keeps the latest keyframe per tile, `view(...)`),
  `PhotosphereView`, and `PhotosphereFingerprintFrame` (`mapFromFingerprint`, `cameraFromMap`,
  `referencePixelToWall`, `kpmPageMillimetersToWall`, `placement`). New
  `PhotosphereMap.directionOfPixel(...)` rotates the pixel ray through heading, elevation **and
  roll** (fix: GraffitiXR added `atan` offsets to heading/elevation, wrong for a rolled camera and
  off-axis). `CameraAttitudeProvider` gains `latestRollDegrees()`, `latestDeviceToWorldMatrix()` and
  the pure `cameraRollDegrees(r, cameraFromDevice)`.
- `com.hereliesaz.sphereslam.gyro.GyroCompensationMath`: gyro stabilisation of a screen-space
  overlay (infinite homography `K·R·K⁻¹`, optional plane parallax term, body→display remap, release
  threshold, MiDaS relative inverse depth helper), ported with its tests from GraffitiXR.
- `:reloc` `Relocalizer` resolves the planar two-fold (flip) ambiguity: after RANSAC it re-solves the
  inliers with `SOLVEPNP_IPPE` and adopts a candidate only when its inlier reprojection error is
  strictly lower (as GraffitiXR's native `MobileGS::runRelocPass` and `HomographyTracker` do). New
  trailing constructor parameter `planarRefine` (default `DEFAULT_PLANAR_REFINE = true`); set it to
  false for the previous behaviour.

### Native, build, and packaging

- A native-load failure now degrades to "unavailable": `SphereSlam.isAvailable()` and `KpmBridge`
  never throw. The native engine ships for `arm64-v8a` and `armeabi-v7a` only; other ABIs report
  unavailable.
- `:core:common` and `:core:nativebridge` no longer depend on or load OpenCV; unused native links
  were removed. `:reloc` users must load `libopencv_java5` themselves (`:reloc` still publishes
  OpenCV with `api`).
- Removed the unused `:reloc` → `:models` dependency.
- Narrowed `consumer-rules.pro` (blanket keep removed).
- Removed unused version-catalog entries; the version is read from `version.properties` and the
  version files are in sync.

### `:sphereslam` / `:overlay`

- `SphereSlamTracker.setReference` replaces the reference, `clearReference` empties the native
  atlas, and calls racing `close()` are ignored.
- `SphereSlamStandaloneSession.addReference` throws `IllegalArgumentException` for a non-rigid
  `canonicalFromPage`.
- `CoverageGlowProjection.project` throws for a field of view outside `(0, 180)`.
- `PhotosphereMap.fromSnapshot` rejects a non-finite `wallHeadingDeg` and normalizes it; legacy
  snapshot "scanned" inference changed.
- `SphereCoverage` / `PhotosphereMap` auto-anchor only on an in-band sample (coverage anchor
  ordering).
- Coverage glow replaced by a coverage haze: a flat, uniform hot-pink fill (#FF69B4, alpha 0.2, no
  falloff) over every `PhotosphereMap` tile that still needs an update; accepted tiles stay clear.
  New `PhotosphereMap.regionsNeedingUpdate`, `SphereCoverage.TileRegion` and
  `CoverageGlowProjection.projectRegions`. Breaking: `CoverageGlowView.update` takes the
  `PhotosphereMap`; `CoverageGlowRenderer` takes `hazeColor`/`hazeAlpha` and `setTriangles`
  (`glowColor`, `pointSizePx`, `baseAlpha` and `setMarks` removed). Shader compile/link failures
  are logged and draw nothing.
- `CameraAttitudeProvider` is thread-safe; `start()` resets `isReliable`.
- Strengthened tests across these modules.

### `:reloc`

- Tracking state: new `TrackingStateConfig.maxBridgeMs` (default 1000 ms) times out an
  uninterrupted IMU bridge into `REACQUIRING`, then `LOST` after `lostAfterMs`. A bridge no longer
  re-arms once reacquiring.
- New `AttitudePosePredictor`, the bundled `EarlyPosePredictor` (rotation-only, holds the camera
  centre). `SphereSlamSession` draws a prediction only while `IMU_BRIDGE`.
- `SphereSlamSession.reset()` now also resets the robustness loop; new `trackingState`; the session
  is `AutoCloseable` and releases supplied fingerprints.
- `SphereSlamSession.onFrame` now returns the pose in the OpenGL eye frame (applies the
  `diag(1, -1, -1)` flip from OpenCV's camera convention, as the planar layer does).
- `RobustTrackingLoop` drops its continuity baseline on `LOST`, and during `INITIALIZING` a
  candidate that fails continuity re-seeds instead of being vetoed by an unconfirmed first pose.
- `PoseBlend.blend` / `diverged` use the camera centre (`-Rᵀt`) instead of the translation column.
- `Relocalizer`: releases per-frame Mats, is `AutoCloseable`, validates arguments before native
  allocation, drops the `Core.VERSION` no-op, exposes RANSAC thresholds as named defaults, and
  reports the measured inlier reprojection residual (`RelocResult.reprojectionErrorPx`,
  `TileMatcher.Match.reprojectionErrorPx`), which the session now gates on. The
  `assumedReprojectionError` session parameter was removed.
- `DepthScaleFit` is robust (least-median seed + iterative trimmed least squares) with a
  scale-invariant degeneracy check.
- Docs: depth-backed tile triangulation is described as building blocks (not yet wired).

### `:models`

- `LowLightEnhancer` reads the network input size from the graph (the bundled Zero-DCE is
  `[1,3,400,600]`), picks its output by name/shape, returns a bitmap the size of the input, recycles
  temporaries on all paths, and makes failure observable (`enhanceOrNull`, `lastEnhanceFailed`).
- `DepthEstimator` output never exceeds `outMaxDim`; the aspect squash is documented; `loadFrom`
  reloads when a different path is requested.
- All wrappers re-extract a model whose size differs from the bundled asset and write it atomically.
- `SuperPointDetector` recycles its scaled bitmap on error paths.

## 0.23.3 — Public API hardening

### API boundaries

- Defined `:sphereslam` and `:overlay` as the supported pre-1.0 consumer surface.
- Marked the entire `:reloc` surface with `ExperimentalSphereSlamRelocApi`.
- Marked optional `:models` helpers with `ExperimentalSphereSlamModelsApi`.
- Marked cross-module native implementation APIs with error-level `InternalSphereSlamApi`.
- Hid `SphereSlamStandaloneSession.EngineFactory`, `SphereSlamTracker.Native`, worker injection,
  GL vertex packing, sensor math, and other test/implementation seams from consumer API.

### Dependency metadata

- `:reloc` now publishes OpenCV with Gradle `api` because OpenCV types appear in its public
  experimental signatures.
- `:overlay` now publishes `:sphereslam` with Gradle `api` because overlay signatures use
  SphereSLAM public types.

### Pose naming

New explicit transform-direction names:

- `Pose.cameraFromCanonical`
- `AnchoredPose.cameraFromContent`
- `Observation.cameraFromPage3x4`
- `OverlayPlacement.cameraFromContent(...)`

Temporary deprecated migration aliases remain:

- `viewMatrix`
- `viewFromContent`
- `pageToCamera3x4`

### Ownership

Array-backed values in the supported API now defensively copy their state, including planar matches,
canonical poses, anchors, tracker observations, photosphere DTOs/snapshots, and glow colors.

### Documentation

- Rewrote the README around the actual supported/experimental module split.
- Added `docs/PUBLIC_API.md` with module boundaries, pose conventions, lifecycle/threading,
  ownership rules, experimental opt-ins, and migration notes.
- Removed the incorrect claim that the library was already “stable/frozen as of 1.0”.

### Build baseline

- JDK 21 toolchain.
- Android Java/Kotlin bytecode target remains 17.
- Dependency/toolchain versions are pinned to exact versions.
- JitPack no longer relies on its cached submodule gitdir for artoolkitX; it materializes and verifies
  the exact pinned commit.
- JitPack bootstraps the checksum-verified Gradle 9.8.0 distribution directly, avoiding failures from
  a corrupted/inaccessible cached wrapper JAR.
- JitPack also bootstraps checksum-verified CMake 4.4.4 and sets `cmake.dir` explicitly, because
  CMake 4.4.4 is newer than the CMake packages available in the stock JitPack Android SDK image.

# Changelog

## Unreleased — audit fixes

### App → library consolidation (additive)

Generic code moved out of GraffitiXR. All additions are new API; nothing existing was removed or
changed incompatibly.

- New supported `com.hereliesaz.sphereslam.math.RotationMath` (quaternion / row-major 3×3 math,
  `rotationAboutZ`, `cameraRotationDelta`, public `rotateAboutCameraCentre`, `conjugateMat3`,
  `rotationAngleDegrees`). `:reloc` `RotationDeltaMath` delegates to it and gains `rotationAboutZ`
  and a public `rotateAboutCameraCentre`.

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

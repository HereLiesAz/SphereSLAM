# Changelog

## Unreleased

- **Fix: native engine not loaded on the session path.** Only `KpmBridge.isAvailable()` (and so
  `SphereSlam.isAvailable()` / `isOperational()`) loaded `libsphereslam.so`. A host that created a
  session without calling one first (`SphereSlam.create`, `PhotosphereTrackingSession` on its first
  capture) failed with `UnsatisfiedLinkError` and went `FATAL` (`NATIVE_LIBRARY_UNAVAILABLE`).
  `KpmBridge` now loads the library when it is first used.
- **Test app.** New `:testapp` module (not published): an on-device test bench that tracks a
  captured target, draws its outline, grades most device checks itself from live telemetry and reports
  each run to this repository's issues through the workflows Worker (`docs/TESTAPP.md`).
- **Floors and ceilings track like walls.** Painting on a horizontal surface puts the camera at the
  photosphere's nadir or zenith, where the camera-axis heading is sensor noise. Several paths keyed or
  oriented on it:
  - The tracking session's base map was the first frame's camera, so a session started looking down
    put the map's −Z at the floor, a quarter turn from every provisional fingerprint placement (and
    no two sessions shared a frame). It is now gravity-level and north-referenced (+X east, +Y up,
    −Z north), the frame `PhotosphereFingerprintFrame` places in. A host-supplied
    `map_from_fingerprint` is checked against the same-frame solve on the first confirmed lock and
    replaced when they differ by more than 2° or 5 % of the page distance.
  - `PhotosphereFingerprintFrame.placement` oriented the page by the tapped azimuth's meridian,
    ignoring roll, and clamped elevation to ±89°. The page's +Y is now the camera's image-up (the
    rectified reference's own up), with no clamp: a floor capture lands the same way every time.
    `mapFromFingerprint(azimuthDeg, …)` takes a compass bearing (it was relative to the wall heading);
    a vector overload takes the ray and image-up.
  - `PhotosphereKeyframeStore.view` returned a stored keyframe's attitude for the current tile; at the
    poles that keyframe may have been taken facing anywhere, rotating the capture tap about the pole.
    It always returns the live attitude now (intrinsics still from the tile's keyframe).
  - `PhotosphereMap.tilesInView` ignored roll (new `rollDeg` parameter); `recordView` anchored an
    unanchored map at the noise-dominated heading — now at the facing azimuth.
  - `CameraAttitudeProvider` stopped updating inside ~0.006° of vertical, leaving stale values, and
    published heading, elevation and roll as separate reads. Every sample now decodes (heading from
    the device top edge straight up/down, roll measured from that heading), and `latestAttitude()`
    returns all three with the matrix as one snapshot. `worldFromCamera(heading, elevation, roll)` and
    `directionOfPixel` are defined at every elevation.
  - New `AttitudeBasis`: the camera basis behind every (heading, elevation, roll) triple,
    `facingAzimuthDeg` (pole-stable bearing), and the map frame convention. `PoleRobustnessTest`
    covers floor captures with jitter.

- **`SphereSlamTracker` goes through `SphereSlamEngine`.** The ARCore-side tracker had its own private
  JNI path to `KpmBridge` (session create/destroy, page add/clear, match, direct-buffer handling),
  duplicating the engine's. It now drives a `KpmSphereSlamEngine` on its worker thread, so native KPM
  sessions are owned in one place. New `SphereSlamEngine.clearPages()` (empty the atlas, keep the
  calibrated session); `KpmApi.clear`. No behaviour change: the tracker tests now run against the real
  engine and pin every native call's arguments and the output mapping.
- **Build: Maven Central mirror.** JitPack's 0.26.1/0.26.2 builds failed before compiling: Central
  answered JitPack's shared builders with HTTP 429 and Gradle disabled the repository. Plugin and
  dependency resolution now try Google's mirror of Central first, Central second.
- **Lens distortion.** New `camera.LensDistortion`: reads `LENS_DISTORTION` and
  `DISTORTION_CORRECTION_AVAILABLE_MODES` (API 28+), picks the correction mode a tracking stream should
  request (FAST, else HIGH_QUALITY), and measures how far the zero-distortion pinhole model is off on a
  camera that cannot correct (`maxResidualPx`, Camera2's κ1–κ5 model). `describe` gives a diagnostic
  line.
- **Oblique rectification reads the full frame.** The virtual frontal view was rendered from the
  half-resolution matcher image, so the foreshortened side of the wall — the side short of pixels —
  lost half of what the camera had. It is now rendered from the full-resolution frame, 2 × 2
  sub-samples per virtual pixel; the matcher image is no longer downscaled on rectified frames.
- **Reference scale.** A full-resolution reference (e.g. a 4000 × 3000 still) against a 320 × 240
  match put the wall at ~1/12 scale, outside KPM's range. `KpmPlanarFrontEnd.addPage` now registers a
  reference whose long side exceeds `KpmMatchSpeedConfig.maxReferenceScale` (default 3) × the
  matched image's long side from an area-downscaled copy of that size (960 px at the defaults). Page
  geometry in millimetres is unchanged; 0 restores registration as given.
- **Extreme perspectives.** KPM is not affine-invariant: past ~45° off the wall's normal the page
  stopped matching, where ARCore's tracking kept the artwork warped onto the wall. When the
  predicted view is at least `KpmMatchSpeedConfig.obliqueRectificationDeg` (default 30°, 0 disables)
  oblique, the full solve now matches a virtual frontal re-rendering of the frame (`ObliqueRectifier`:
  plane homography from the predicted pose) and solves the real pose from the correspondences mapped
  back — exact regardless of prediction error. Such poses report `VisualMatchMethod.KPM_RECTIFIED`
  and seed flow tracking as usual.
- **Fixes from an audit of the full-sphere / scan-progress release.**
  - `CoverageGlowRenderer`: in `OUTLINE`, scan progress now draws before any tile is current (it
    returned early with no boundary to glow along — exactly the first sweep).
  - `PhotosphereMap.tilesInView` also takes tiles whose centre lies in the camera's frustum, so tiles
    near the zenith/nadir — far apart in heading, close on screen — are matcher candidates.
  - `PhotosphereKeyframeStore.record` returns null before touching an anchored map when the axis is
    outside the lattice (it had already recorded the view).
  - `CoverageGlowView.updateRegions` / `update` feed scan progress; `setFillMode` swaps the regions
    before the mode.
  - Tests now pin `recordView`'s roll and `cos(elevation)` area weighting (both survived removal).
- **Anchor tiles.** A station's tile cap (64) dropped the oldest tile first, and the oldest are the
  ones around the wall, captured at the first lock, so a long session forgot exactly the tiles that
  re-find the wall. A station's first lock now anchors its view direction: tiles within
  `PhotosphereTileLocalizer.Config.anchorRadiusDeg` of it (24° = 2 × tile spacing: those viewing the
  wall and the ring bordering them), including those captured later, are never evicted. The periodic diagnostic gains
  `tiles{… anchored=N …}`.
- **The photosphere is a sphere.** `SphereCoverage` / `PhotosphereMap` defaulted to a front region —
  12 sectors over ±85° and one band (±60° when banded) — so the ceiling, the floor and everything
  behind the start lay outside the lattice: never scannable, glowing for good, and "100 %" meant a
  front arc. The defaults are now the whole sphere: 24 sectors × 6 bands, 15° × 30° tiles, wrapping
  in azimuth. Breaking for callers relying on the defaults (pass the old arcs explicitly to keep a
  front-only target).
- **Per-tile scan progress.** New `PhotosphereMap.recordView(heading, elevation, roll, lens)`: each
  view adds the share of every tile's area it has in frame (5° samples, area-weighted, union across
  views); a tile becomes current at `DEFAULT_COMPLETE_FRACTION` (90 %). `scanProgress(id)`,
  `regionsInProgress()` (`RegionProgress`). Stale / expired / reset tiles start over.
  `PhotosphereKeyframeStore.record` now scans through it: tiles complete as the artist sweeps across
  them (`Recorded.progressedTiles`; `firstCaptureForTile` means the axis tile's scan completed).
- **Glow shows the scan.** `CoverageGlowRenderer.setRegionProgress(regionsInProgress)`: a tile being
  scanned is drawn more opaque as it fills — from the wash toward `progressAlpha` (0.85), ten steps,
  every style — and opens to the camera once scanned.

## 0.25.0

- **The artwork stays on the wall while the wall is out of view.** Past `Config.maxBridgeMs` (400 ms)
  a miss used to return no pose, so the artwork vanished until a visual relock, although the gyro
  attitude was tracked every frame. New `Config.holdOffWall` (default `true`): the last lock is
  carried through the gyro rotation as `PoseSource.HELD` (camera centre held) while the wall is away
  and while a relock confirms, until a visual relock or a precision-layer change (reference, pages,
  map, intrinsics, `forceRebuild`). It does not re-seed the smoothing baseline, so a relock after a
  real loss corrects rather than being gated by a drifted hold. Walking instead of turning drifts the
  held pose until the relock. Breaking: `PoseSource` gained `HELD` (exhaustive `when`s need a branch).
  The `DisplayPosePredictor` is fed held poses too.
- **Photosphere localization: the photosphere places the camera, not only the wall.** The wall is one
  tile; every other tile now says where the camera is too (`Config.tileLocalization`, default on).
  - *Frame.* The first wall lock of a precision layer relates the gyro frame to the map (a drift
    correction of the gyro/compass base rotation); every later visual fix — a recognised tile or a
    lock — re-solves it. Nothing is captured, fixed or rendered from the photosphere before that
    lock, and a new reference or map resets it all.
  - *Tiles.* Keyframes (one per 12° of view direction) become tiles of the current **station**, each
    filed at the camera centre of the time (captured only within 3 s of a centre measurement). On a
    background worker, at most every `tileFixIntervalMs` (250 ms) and never while the wall is locked,
    the live frame is matched (ORB, behind a seam) against the tiles near the predicted view; a tile
    from the camera's standpoint gives its rotation by robust rotation-only fitting. A fix measured
    before a newer fix or lock is dropped.
  - *Off the wall.* With `map_from_fingerprint` known, a miss renders the corrected rotation at the
    latest known camera centre as new `PoseSource.PHOTOSPHERE` (taken from the rendered lock, so the
    hand-over does not jump); without a lock in this layer, the gyro-held `HELD` pose. While lost, the
    photosphere pose seeds the KPM relock prediction.
  - *Stations.* A wall lock (or a 6-DoF tile fix) more than `stationSpawnDistance` (0.3 m for a
    metric reference, 0.3 reference widths otherwise) from every station opens a new station there;
    the earlier photospheres are kept, and returning within half that reactivates the old one. Tiles
    from two standpoints looking the same way are a stereo pair with a known baseline: matched
    features are triangulated into world landmarks and a plane is fitted per tile, giving a full
    6-DoF fix from any position and the plane-induced homography that predicts how a tile looks from
    wherever the device moves.
  - *Monocular depth.* New `TileDepthSource` / `TileDepthMap` (public constructor parameter): a
    MiDaS map per new tile (after the spacing gate; one keyframe job in flight), calibrated with
    `DepthScaleFit` to the tile's landmarks, overlapping calibrated tiles from the same standpoint,
    then the wall plane — only where a ray meets the wall within its pages' extent — turns its
    features into 3D points from the first station on. A head-on wall (one depth) places its
    wall-consistent features on the wall.
  - Needs OpenCV (ORB): without the runtime, tile localization disables itself once with a
    diagnostic. Worker diagnostics are delivered on the `onFrame` thread.
  - Pure-Kotlin geometry (`PhotosphereGeometry`: rotation from bearings, RANSAC rotation, midpoint
    triangulation, plane fitting, plane homography, pose refinement) and the localizer are JVM-tested
    against synthetic scenes. `stationCount`; the periodic diagnostic gains `tiles{…}`.
  - Breaking: `PoseSource` gained `PHOTOSPHERE`.

## 0.24.6

Seen tiles persist: the glow no longer returns over scanned areas, and tracking re-acquires after
the wall leaves the screen.

- **Fix — a keyframe cleared only the tile under its view axis.** Tiles are ~14° × 40° against a
  ~50° × 65° frame, so everything else in view was never recorded and glowed again once it left the
  screen; a fast pan skipped whole sectors. New `PhotosphereMap.tilesCovered(heading, elevation,
  roll, lens)` returns every tile at least `DEFAULT_MIN_COVERED_FRACTION` (0.6) in frame (sampled
  every 5°, area-weighted); `PhotosphereKeyframeStore.record` marks them current too (the axis tile
  alone keeps the keyframe). `Recorded.coveredTiles` / `firstCoveredTiles` report them; a tile already
  covered is no longer a first capture when the axis reaches it.
- **Fix — regression in 0.24.4: KPM gates 4× stricter at the default 2× downscale.** 0.24.4 reported
  KPM's reprojection error in full-frame px² (×`factor²`) without rescaling
  `PoseAcceptanceConfig.maxReprojectionError` (10) or `PhotosphereAtlasGrowth.MAX_GROW_REPROJECTION_ERROR`
  (4), both tuned on the matcher's pixels: the atlas almost never grew (nothing beyond the reference
  page to relock on) and oblique re-entries were rejected as `EXCESSIVE_REPROJECTION_ERROR`. KPM's
  error is again reported in the pixels it was solved in (as through 0.24.3); flow's stays full-frame.

## 0.24.4

Fixes from a full audit of 0.24.3.

### `:reloc` `PhotosphereTrackingSession`

- **Fix — `updatePrecisionLayer(sameReference)` without a map deleted the solved
  `map_from_fingerprint` for good** (the one-shot solve never re-armed, so later poses were the
  gyro-only base pose labelled `KPM`). With the same reference and no map, the map the session solved
  for that reference is kept; a host map overrides it; a new reference re-solves.
- **Fix — a reference swap kept the tracking state `LOCKED`**: the new wall locked on one unconfirmed
  frame and the map bridge was solved from it; dropping the reference left `LOCKED` behind. Every
  precision-layer rebuild (reference / map / page-set change, `forceRebuild`, intrinsics change) now
  resets the state machine and needs `confirmationFrames` again.
- **Fix — `REFERENCE_TOO_WEAK` was reported `RECOVERABLE` but killed the session.** It now halts only
  the precision layer (`TrackingState.FATAL`, `onFatalError`, new `isRecoverable`); the base pose and
  keyframes continue, and `updatePrecisionLayer` with another reference, changed pages or
  `forceRebuild` resumes with the base map intact.
- **Fix — overlay swung back after a gyro bridge.** The bridged pose that was shown becomes the
  smoothing baseline (new `RobustTrackingLoop.synchronizeRendered`); the `DisplayPosePredictor`
  receives it and is now also cleared on fatal errors and `close()`.
- **Fix — page table drift.** `atlasPages` is the complete page set: a different set rebuilds the
  matcher from exactly those pages (the same objects or equal copies change nothing), and grown page
  numbers are never reused, so growth can no longer fail forever on a duplicate page number.
- **Fix — base map after a display rotation** used the first frame's `cameraFromDevice`; it is now
  `C_now · ΔR · C_refᵀ`.
- **Units — KPM reprojection error is reported in full-frame pixels²** (multiplied by `factor²` at a
  downscale), the same unit as flow poses, so `PoseAcceptanceConfig.maxReprojectionError` and
  `PhotosphereAtlasGrowth.MAX_GROW_REPROJECTION_ERROR` mean the same for every match method. At the
  default 2× downscale this tightens the acceptance gate on KPM poses from an effective 40 to 10
  full-frame px² (KPM's own ≤ 10 px² gate applies in the downscaled image); raise
  `maxReprojectionError` if half-resolution locks are now rejected on device.
- A focused-page miss no longer runs a second, all-pages solve in the same frame: the frame reports
  no match and the next full solve searches every page.
- `Config.maxBridgeMs` (from the last accepted frame) is the only bridge limit; it overrides
  `trackingState.maxBridgeMs`.
- Timing diagnostic: `timing{frames attempts matched …}` — `matched` now counts frames whose matcher
  returned a pose (it counted attempts).
- `PoseStabilizer(snapDistance)` / `PoseBlend.diverged(…, snapDistance)` / `snapDistanceFor`: the
  0.2 m snap applies only to metric poses; non-metric poses snap at 0.2 reference widths.
- `PlanarFingerprint.fromReference` releases its mask `Mat`.
- Tests: `KpmPlanarFrontEnd` (behind a backend/flow seam: downscale, correspondence scaling, flow
  scheduling and fallback, OpenCV-missing path, focused-miss deferral) and `FlowPoseMath` (the flow
  tracker's point-selection rules); the downscale test now solves and compares planar poses.

### `:sphereslam`

- `PhotosphereKeyframeStore.record` anchors the wall heading only from keyframes inside the viewable
  elevation arc (as `PhotosphereMap.markUpdated` does).
- `PhotosphereReviewLoop`: a tile confirmed in a frame stays current even when a changed neighbour
  propagates staleness in the same frame.
- Hybrid sidecar: `HybridKpmCorrection` gates on the confidence `HybridAnchorFusion` requires
  (`MIN_CONFIDENCE` 0.5, new `Reject.LOW_CONFIDENCE` / `HybridKpmOutcome.LOW_CONFIDENCE`), so a
  refused observation is never reported `ACCEPTED`; `Reject` is declared in gate order;
  `HybridAnchorFusion.MIN_INLIER_RATIO` / `COLD_SNAP_INLIER_RATIO` renamed `MIN_CONFIDENCE` /
  `COLD_SNAP_MIN_CONFIDENCE` (deprecated aliases kept). Effective acceptance is unchanged.
- `MetricPageRectification`: the page's corners map to the output's pixel edges and the page is cut
  to whole square pixels, so the warp, the DPI and the page geometry agree (was off by `W/(W−1)`).
- `CameraIntrinsicsEstimator` crops streams from `SENSOR_INFO_ACTIVE_ARRAY_SIZE` (Camera2's stream
  source), re-expressing the principal point from the pre-correction / pixel array.
- `CameraAttitudeProvider.latestSample().accuracy` carries the reported sensor accuracy; it and
  `GameRotationAttitudeSource` hold the application context's `SensorManager` (no pinned Activity).
- `SphereCoverage` / `SphereGrid` accept the documented `(0, 180]` / `(0, 90]` ranges (sub-degree
  values were rejected).
- `SphereSlamTracker` reuses its packed-frame arrays and JNI direct buffer;
  `LumaFrameTransform`'s `…Into` calls reuse band/row scratch per thread.
- Docs: `DeviceCameraRotation.toOpenCv` described correctly; README examples pass the roll of the
  tracked image (`cameraRollDegrees(latestDeviceToWorldMatrix(), cameraFromDisplay)`), not
  `latestRollDegrees()`.
- `KpmSphereSlamEngineTest`'s fake follows the native contract (a clone matches nothing until its
  first add).

### Native (`:core:nativebridge`)

- `maxFeatures` now caps the FREAK path (upstream honours it only for SURF): a spatially even subset
  is kept. A failed or featureless page is no longer merged into the atlas (nor copied into clones).
- Every JNI entry point catches all C++ exceptions and returns its error value. Not catchable:
  artoolkitX's `arMalloc` calls `exit(1)` when `malloc` fails.
- artoolkitX logs at WARN (no INFO line per match). Dead smoke test / homography constructor removed;
  `KpmBridge.isAvailable()` retries a failed library load.
- libc++ is linked statically (`c++_static`): no `libc++_shared.so` clash with OpenCV in consumers.
- A missing artoolkitX submodule fails the native build unless `-Psphereslam.allowKpmStubs=true`.
- Instrumented tests (`connectedAndroidTest`) for clone, destroy-while-matching, single-page
  matching, the feature cap and featureless pages. They need an arm device; CI does not run them.

### `:overlay`

- `CoverageGlowRenderer.drawEmbedded` draws from its own buffers and, on ES 3, its own VAO (host VAO
  restored); it sets and restores the blend equation. On ES 2 the attribute pointer offset cannot be
  read back — documented. Context loss: resource generations, `onGlContextLost()`, names never
  deleted on a suspected loss. Wash/edge shaders use highp where available.
- `CoverageGlowView` feeds `currentRegions()` to the COMPLEMENT / OUTLINE styles (`regionsFor`,
  `setFillMode`).

### `:models`

- Extracted models are refreshed on a SHA-256 content change (was: size only); the GPU program-cache
  token uses the same hash.

### Licensing, build and release

- SphereSLAM is licensed under Apache-2.0: `LICENSE`, `NOTICE` (artoolkitX LGPLv3 + static-linking
  exception with its source commit, MiDaS / SuperPoint MIT, OpenCV / LiteRT Apache-2.0, ONNX Runtime
  MIT) and `third_party_licenses/` ship in every AAR under `META-INF/sphereslam/<module>/`; the POM
  declares the license.
- Model provenance recorded in `docs/MODELS.md`: `superpoint.onnx` is rpautrat/SuperPoint
  `superpoint_v6_from_tf.pth` (MIT; `assets/superpoint.LICENSE.txt` added); `zerodce.onnx` was
  trained independently by HereLiesAz.
- CI workflow (`.github/workflows/ci.yml`): `./gradlew assemble test` with the submodule on PRs into
  and pushes to `main`.
- Consumers need `compileSdk` 37 (required by `androidx.core` 1.19.x, documented in the README).
- Corrected comments: version catalog pinning policy / LiteRT exception; JitPack builds the version
  in `version.properties` (it does not substitute the tag).

## 0.24.3

Everything below shipped in tag `0.24.3` (tags `0.24.0` and `0.24.1` were cut along the way without
sections of their own).

### Display-rate coverage glow, attitude extrapolation and pose prediction (additive)

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

Additions:

- `CoverageGlowProjection.worldFromCamera(heading, elevation, roll)` (the `directionOfPixel` /
  `CameraAttitudeProvider` convention) and `worldFromCamera(deviceToWorld, cameraFromDevice)`:
  row-major 3×3 whose columns are the GL camera axes (x right, y up, z backward) in East-North-Up.
- `CoverageGlowProjection.Lens` (`fromFieldOfView`, `fromIntrinsics` incl. off-centre principal
  point), `projectRegions(regions, worldFromCamera, lens, maxSegmentDeg = 5)`,
  `project(directions, worldFromCamera, lens)`, and `prepareRegions(...)` →
  `PreparedRegions.projectInto(worldFromCamera, lens, out)` (pre-sampled unit vectors, no trig or
  allocation per frame; tile edges cut into ≤ 5° segments; seam-exact across 0/360°).
- `:overlay` `CoverageGlowRenderer`: display-rate re-projection on the GL thread — `setRegions`,
  `setLens`, `rotationSource: RotationSource` (queried on every draw with the frame time, in the
  `SystemClock.elapsedRealtimeNanos()` base) or the push alternative `setWorldFromCamera`;
  `drawEmbedded(frameTimeNanos)`. New styles `FillMode.OUTLINE` (soft edge glow on the needs-update
  side of the current tiles) and `FillMode.COMPLEMENT_OUTLINED` (wash + edge), with `outlineAlpha` and
  `outlineWidthPx`. `hazeColor` / `hazeAlpha` are now safe to change from any thread while drawing,
  plus `setHazeColorArgb`. Defaults unchanged (`TRIANGLES`, hot pink at 0.2).
- `:overlay` `CoverageGlowView`: `updateRegions(map)`, `update(map, worldFromCamera, lens)`,
  `trackAtDisplayRate(source)`.
- `attitude.AttitudeExtrapolator`: timestamped attitude history — slerp inside it, constant-velocity
  extrapolation past it (capped, default 50 ms; no velocity across a > 100 ms sensor gap);
  `quaternionAt`, `deviceToWorldAt`, `worldFromCameraAt`; thread-safe, allocation-free reads.
- `CameraAttitudeProvider` (still `TYPE_ROTATION_VECTOR`, East-North-Up): `start(samplingPeriodUs)`
  (default unchanged, `SENSOR_DELAY_UI`), `latestSample()` (defined at the poles, unlike heading),
  and `attitudeExtrapolator`, fed by every sample.
- `attitude.DisplayPosePredictor`: rotates the last vision pose by the attitude change since its
  frame (same math as the IMU bridge, camera centre held, no re-referencing) for per-draw pose
  prediction between vision updates; `predictInto(currentAttitude, out)` /
  `predictInto(extrapolator, targetTimeNs, out)` are allocation-free and thread-safe.
- `:reloc` `PhotosphereTrackingSession.displayPosePredictor`: when set, every rendered pose is
  published to it with its frame's attitude and `cameraFromDevice` (cleared when a frame yields no
  pose), so the GL thread can draw the artwork pose at display rate between analyzer updates.

Deprecated (behaviour unchanged): the heading/elevation `CoverageGlowProjection.projectRegions` and
`CoverageGlowView.update(map, heading, elevation, hFov, vFov)`.

### Standalone tracking speed (`:reloc` `PhotosphereTrackingSession`)

Why standalone tracking felt low frame-rate (diagnosis): a full-resolution, all-pages native
`kpmMatching` ran on every analyzer frame (est. 40–150+ ms at 640×480, growing with atlas pages);
atlas growth ran on the analyzer thread (rectify + page quality gate + `kpmAddRefDataSet` feature
extraction + `kpmSetRefDataSet` index rebuild, ~100–400 ms per attempt, every ≥ 3 s while locked); a
page that failed the KPM feature floor closed the live matcher, so the *next* frame rebuilt KPM and
re-added every page inline (a multi-second hitch); the native bridge held one global lock across every
KPM call; and every frame allocated a fresh ~300 KB rotated luma array through a per-pixel
`ByteBuffer.get` loop.

**Cheaper matching** — new `Config.matchSpeed: KpmMatchSpeedConfig` (on by default; additive API):

- **Downscaled matching** (`matchDownscale = 2`, never below `minMatchShortSidePx = 240`): KPM runs
  on a box-filtered luma with intrinsics scaled in the pixel-centre convention
  (`fx' = fx/f`, `cx' = (cx + ½)/f − ½`). The calibrated pose is resolution-independent, so the
  returned camera-from-wall transform has the same convention; the reprojection error stays in the
  pixels KPM solved in. (Corrected in Unreleased: errors are now reported in full-frame pixels²; the
  claim made here that `PoseAcceptanceConfig` had been tuned on the downscaled scale was unsupported.)
  artoolkitX's own `kpmSetProcMode` is deliberately not used: in the pinned FREAK path the pose solve
  consumes unscaled query coordinates against full-size calibration, giving wrong poses for any
  reduced mode.
- **Frame-to-frame flow tracking** (`flowTracking`): the full solve's inlier correspondences (frame
  pixel ↔ page millimetre) are tracked at full resolution with forward–backward-checked pyramidal
  Lucas–Kanade and the pose is re-solved by iterative PnP against the page's own reference
  coordinates (AR2-style; no chained drift), with one residual-outlier pass. A full KPM solve runs on
  flow loss, too few inliers (`minFlowInliers`, `minFlowInlierFraction`), RMS error above
  `maxFlowRmsErrorPx`, a pose-gate rejection, and at least every `fullSolveEveryFrames` (10) frames /
  `fullSolveIntervalMs` (500 ms); a missed periodic solve may be covered by flow for at most
  `maxFlowOnlyMs` (1.5 s). Requires OpenCV's native library (`opencv_java5`, as `Relocalizer`);
  without it flow turns itself off. Flow poses never grow the atlas.
- **Focused page matching** (`focusedPageMatching`): with ≥ 2 pages and a lock within
  `focusWindowMs`, the page under the gyro-predicted optical axis (hysteresis on the last locked page)
  is matched alone first, falling back to all pages on a miss.
- `TrackedPose.matchMethod` / `VisualMatchMethod` (`KPM_FULL`, `KPM_FOCUSED`, `FLOW`).
  `KpmMatchSpeedConfig.FULL_KPM_EVERY_FRAME` restores the old every-frame full-resolution solve.

**No matcher work on the analyzer thread** — the initial build, rebuilds after a
reference/intrinsics change and every atlas-growth attempt run on a private single background thread
(`AtlasMatcherWorker`, internal). Growth copies the frame, then on the worker rectifies the page,
gates it, **forks** the live matcher (native clone of its atlas) and adds the page to the fork, while
`onFrame` keeps matching on the live matcher; the grown matcher is swapped in at the start of a later
frame and the old one is closed on the worker. A rejected page just drops the fork — the live matcher
is never touched and nothing is rebuilt. Outcomes are generation-checked, so a candidate built for a
matcher that has since been dropped, rebuilt or closed is discarded. All callbacks still fire on the
`onFrame` thread. The fork starts with fresh flow/scheduling state, so the first frame after a swap
is a full solve.

Behaviour changes:

- Until the first matcher build lands (and briefly after an intrinsics change), frames return the
  base pose with `FrameResult.pose == null`.
- A too-weak reference (`ReferenceTooWeakException`) fails the session on the frame *after* its build
  completes rather than the same frame.
- A growth attempt that throws is now a diagnostic (`atlas growth failed`), not a fatal error.
- `Frame.luma` is not retained after `onFrame` returns, so hosts may reuse a pooled buffer.

**Diagnostics** — the periodic (`metricsIntervalMs`, 5 s) KPM line now carries `method=` and
per-stage timing over the window, `timing{frames matched convertMs setupMs matchMs keyframeMs
growthMs}` as `mean/max` ms, and is also emitted without a lock (`KPM no-lock`).
`Frame(convertDurationNs = …)` lets the host report its Y-plane pack/rotate time.

**Supporting `:sphereslam` API** (all additive):

- `PlanarMatchRequest(pageNo, maxCorrespondences)`, `SphereSlamEngine.match(luma, request)` (the
  default ignores the request), `SphereSlamStandaloneSession.match(luma, timestampNs, request)`,
  `SphereSlamStandaloneSession.cameraFromCanonical(pageNo, cameraFromPage3x4)`,
  `Pose.cameraFromPage3x4` / `Pose.correspondences`, `PlanarMatch.correspondences` (old constructor
  kept).
- `SphereSlamStandaloneSession.fork()`: an independent session with every registered page, safe to
  create on another thread while this one matches; null for a non-KPM engine.
- `LumaFrameTransform`: rows are bulk-read and rotated in 16-row bands (byte-identical output, pinned
  against the old scalar loop). New `LumaFrameBufferPool(slots = 2)` (round-robin pooled output: a
  returned array is overwritten `slots` calls later, so keep at most `slots − 1` frames or copy) and
  `packCropAndRotateInto` / `packAndRotateInto` (caller-owned destination). The existing
  `packAndRotate` / `packCropAndRotate` still return a fresh array, so callers that retain frames are
  unaffected.

### Native (`KpmBridge`)

- **Fix — crash when adding the second atlas page.** Growing the atlas called `kpmSetRefDataSet` on
  a handle that already held pages; the FREAK database re-adds image id 0 and throws
  `"ID already exists"`, aborting the process. The matcher is now rebuilt from the retained
  calibration before the grown atlas is loaded. This applies whenever a session was already indexed,
  so it covers clones too.
- **Thread safety.** Sessions are addressed by never-reused ids mapped to shared owners, each with
  its **own** lock (the registry lock is held only for lookup); destroy unregisters and frees once
  in-flight calls return. One handle is still never used by two threads at once; different sessions
  now run in parallel. New `cloneSession` (calibration + deep copy of the atlas; the source is locked
  only for the copy).
- `matchPlanarEx`: single-page matchers built lazily from the already-extracted atlas features,
  inlier correspondence export, and the redundant per-page-slot ICP pose solves in the FREAK path
  skipped (`kpmSetMatchingSkipPage` on every slot but the first — same pose, N−1 fewer solves). The
  match and add paths catch C++ exceptions. (This originally read "C++ exceptions no longer cross
  JNI", which overclaimed: session creation, clone and map inserts were unguarded until Unreleased.)

### `:models` depth

- **`DepthEstimator` runs on the GPU (LiteRT).** Monocular depth moved from ONNX Runtime (CPU) to
  **LiteRT 1.4.2** (`com.google.ai.edge.litert:litert`, `litert-gpu`, `litert-gpu-api`) with the
  **GPU delegate** as the default: fp16 precision allowed, sustained-speed preference, compiled
  programs serialized under `codeCacheDir/sphereslam-depth-gpu` (token = model name + size + CRC32).
  Opening runs one warm-up inference; if the GPU delegate cannot be created, the device is on LiteRT's
  GPU compatibility block list, or that first run fails, it falls back to LiteRT's XNNPACK CPU path
  and logs an **error** (GPU is the expected path). `activeBackend` reports the backend truthfully.
- **Model:** `midas_v21_small_256_int8.onnx` (17.3 MB) is replaced by
  `midas_v21_small_256_fp16.tflite` (33.2 MB) — the official isl-org/MiDaS `v2_1` `model_opt.tflite`
  (MIT) with float16 weights (`models/tools/tflite_fp16_weights.py`); outputs match the fp32 file
  within 0.15 % and track the PyTorch reference far better than the old ONNX path did.
  `DepthEstimator.MODEL_ASSET` changed accordingly. Provenance, sha256 and the size change:
  [`docs/MODELS.md`](docs/MODELS.md).
- **Threading:** the estimator owns a single `SphereSLAM-depth` thread; the delegate is created, run
  and closed there, and `load` / `loadFrom` / `estimate` / `estimateLuma` / `close` hand their model
  work to it and block, so callers may use any (and changing) threads. `close()` ends the thread; a
  later `load()` starts a new one.
- **Options:** `DepthEstimatorOptions(backend = GPU | CPU, cpuThreads = 2, allowFp16Precision = true,
  gpuProgramCache = true)` as a new trailing constructor parameter (the 5-argument constructor is
  kept). The ONNX execution-provider options that existed briefly on this branch
  (`intraOpThreads`, `XNNPACK`, `NNAPI`, `allowSpinning`, `activeExecutionProvider`) never shipped
  in a release and are gone.
- New `estimateLuma(luma, width, height, rowStride, outMaxDim)` feeds a grey plane straight into the
  model (bilinear resample, grey in all three channels) without an ARGB `Bitmap` round trip, reusing
  its input buffers.
- Unchanged: `load`, `loadFrom` (now takes a `.tflite` path), `estimate`, `inputSize` (default 256),
  `MEAN`/`STD`, and `DepthMap` (MiDaS relative inverse depth, row-major, at most `outMaxDim` on the
  long side). Custom models may be NHWC or NCHW.
- `:models` now ships consumer R8 rules (LiteRT reflection/JNI and ONNX Runtime's JNI exception
  class). Do not add LiteRT 2.x (`litert:2.*`) to an app using `:models`: it drops the classic
  delegate API that `litert-gpu` 1.4.2 needs.
- `SuperPointDetector` and `LowLightEnhancer` (Zero-DCE) stay on ONNX Runtime, unchanged.

### Documentation

- README and `docs/PUBLIC_API.md` rewritten for the full-rotation glow, attitude extrapolation,
  display pose prediction, KPM speed levers, the background atlas worker and pooled luma; new
  **App integration** walkthrough; build-from-source notes (artoolkitX submodule, pinned CMake,
  JitPack). Stale heading/elevation-only glow examples removed; the ONNX-only depth notes are
  replaced by a LiteRT GPU depth section (model details stay in `docs/MODELS.md`).

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

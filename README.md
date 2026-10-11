# SphereSLAM

[![](https://jitpack.io/v/HereLiesAz/SphereSLAM.svg)](https://jitpack.io/#HereLiesAz/SphereSLAM)

Lightweight markerless 6-DoF tracking and relocalization for Android, including devices where ARCore
is unavailable or undesirable.

SphereSLAM has two layers:

1. **Planar KPM** — natural-feature tracking of one or more rectified planar references in one
   canonical wall frame. This is the supported consumer API in `:sphereslam`.
2. **Photosphere / depth relocalization** — orientation-indexed tiles, a standalone photosphere
   tracking runtime, PnP relocalization, robustness gates, depth-backed triangulation building blocks,
   and relock seeding. This lives in `:reloc` and is intentionally experimental while its
   OpenCV-facing surface evolves.

Around them sit a display-rate coverage overlay (`:overlay`), attitude extrapolation and per-draw pose
prediction (`:sphereslam` `attitude`), and optional on-device models (`:models`).

## API status

SphereSLAM is **pre-1.0**. There is no fictitious “frozen since 1.0” contract.

| Module | Status | Intended use |
| --- | --- | --- |
| `:sphereslam` | **Supported pre-1.0 API** | Planar tracking, standalone tracking, ARCore sidecar tracking, coverage, placement, attitude |
| `:overlay` | **Supported pre-1.0 API** | Optional coverage-glow UI |
| `:reloc` | **Experimental** | OpenCV-backed photosphere tracking / relocalization primitives |
| `:models` | **Experimental** | Optional model helpers: MiDaS depth on LiteRT GPU, SuperPoint / Zero-DCE on ONNX Runtime ([models](docs/MODELS.md)) |
| `:core:common` | **Internal** | Native loading/implementation support |
| `:core:nativebridge` | **Internal** | JNI/KPM bridge |

The internal modules are compiler-gated with an error-level opt-in marker. Application code should
not depend on them directly.

The complete contract, pose conventions, ownership rules, threading and migration notes are in
[docs/PUBLIC_API.md](docs/PUBLIC_API.md). A step-by-step host wiring guide is in
[App integration](#app-integration) below.

## Build baseline

- JDK 21 toolchain
- Java/Kotlin Android bytecode target 17
- Gradle 9.8
- Android Gradle Plugin 9.4.1
- CMake **4.4.4** exactly (pinned in `gradle/libs.versions.toml`; `CMakeLists.txt` requires it)
- compileSdk 37
- minSdk 26

**Consumers need `compileSdk` 37 too.** The AARs' metadata require it, and that requirement comes
from their dependency on `androidx.core:core` 1.19.x (whose own AAR metadata demands compileSdk 37),
so it would apply even if SphereSLAM's modules declared a lower value.

### Building from source

The native KPM engine is compiled from the **artoolkitX submodule** (`third_party/artoolkitx`, pinned
to `9d487c9f`). Clone with submodules:

~~~bash
git clone --recurse-submodules https://github.com/HereLiesAz/SphereSLAM.git
# or, in an existing clone:
git submodule update --init third_party/artoolkitx
~~~

Without the submodule the native build **fails** (CMake `FATAL_ERROR`) rather than shipping a library
whose KPM never works. For host/JVM-only work that does not need KPM, opt in to stub entry points with
`-Psphereslam.allowKpmStubs=true`: `SphereSlam.isAvailable()` / `isOperational()` then report false
and nothing ever matches.

The Android SDK must provide CMake 4.4.4 (`sdkmanager "cmake;4.4.4"`), or point `cmake.dir` in
`local.properties` at a standalone 4.4.4 install. JVM unit tests never load the native library, but
`./gradlew test` still configures the native build, so it needs the submodule (or
`-Psphereslam.allowKpmStubs=true`).

`libsphereslam.so` links libc++ statically (`ANDROID_STL=c++_static`) and ships no
`libc++_shared.so`, so it never collides with OpenCV's (which `:reloc` brings in) when a consumer
merges native libraries. The native session lifecycle (clone, destroy while matching, single-page
matching, feature cap) is covered by instrumented tests,
`./gradlew :core:nativebridge:connectedAndroidTest` on an arm64-v8a / armeabi-v7a device; the JVM
tests use fakes.

**JitPack** must build with the submodule checked out. `jitpack.yml` does this explicitly: it
materializes the exact gitlink commit into a clean checkout (JitPack's cached submodule gitdirs are
not trusted), downloads and SHA-256-verifies Gradle 9.8.0 and CMake 4.4.4, and points AGP at that CMake.
If you fork the build config, keep those steps; a JitPack artifact built without artoolkitX has a
non-functional native engine.

## Install

~~~kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.HereLiesAz.SphereSLAM:sphereslam:<tag>")
    // optional:
    implementation("com.github.HereLiesAz.SphereSLAM:overlay:<tag>") // coverage glow
    implementation("com.github.HereLiesAz.SphereSLAM:reloc:<tag>")   // photosphere tracking (experimental, OpenCV)
    implementation("com.github.HereLiesAz.SphereSLAM:models:<tag>")  // depth etc. (experimental)
}
~~~

## Standalone planar tracking

~~~kotlin
val session = SphereSlamStandaloneSession(
    frameWidth = frameW,
    frameHeight = frameH,
    calibration = SphereSlamCalibration(fx, fy, cx, cy),
)

val anchored = AnchoredStandaloneSession(session)

anchored.base.addReference(
    luma = referenceLuma,
    width = referenceWidth,
    height = referenceHeight,
    referenceWidthMeters = 0.30f,
    physicallyMetric = true,
)

anchored.place(
    OverlayPlacement.anchor(
        panXMeters = panX,
        panYMeters = panY,
        rotationZDeg = rotationDeg,
        halfWidthMeters = artworkWidthMeters / 2f,
        halfHeightMeters = artworkHeightMeters / 2f,
    )
)

anchored.match(frameLuma, timestampNs)?.let { matched ->
    // Column-major content -> camera/view transform.
    val cameraFromContent = matched.cameraFromContent
    val mvp = multiply(projectionMatrix, cameraFromContent)
    drawQuad(mvp, matched.halfWidthMeters, matched.halfHeightMeters)
}
~~~

A null match means no registered page was recognized in that frame. The placement remains retained
and is reapplied after reacquisition.

Lower-level hooks used by the photosphere runtime are public too:
`session.match(luma, timestampNs, PlanarMatchRequest(pageNo, maxCorrespondences))` matches one page
and returns its inlier correspondences (`Pose.correspondences`, rows of frame-pixel `x, y` then page-millimetre `x, y`),
`session.cameraFromCanonical(pageNo, cameraFromPage3x4)` converts an externally solved page pose, and
`session.fork()` clones the session (every registered page) for building a grown atlas on another
thread.

## ARCore sidecar tracking

`SphereSlamTracker` runs KPM asynchronously beside ARCore. It does **not** replace ARCore's primary
view matrix.

~~~kotlin
val tracker = SphereSlamTracker()
tracker.configure(
    SphereSlamTracker.CameraModel(
        width = frameW,
        height = frameH,
        fx = fx,
        fy = fy,
        cx = cx,
        cy = cy,
    )
)

tracker.setReference(referenceLuma, refW, refH, refRowStride)

tracker.submitFrame(luma, frameW, frameH, rowStride, timestampNs)

val observation = tracker.latestObservation()
val rawCameraFromPage = observation?.cameraFromPage3x4
~~~

`cameraFromPage3x4` is raw row-major KPM output. It is **not** an OpenGL or ARCore view matrix.

## Coverage glow

Every photosphere tile that still needs an update can be hazed; accepted tiles stay clear, so the
edge of the haze is the edge of what has been scanned. The geometry comes from the `PhotosphereMap`'s
own tiles and nothing else.

The glow is **re-projected on the GL thread on every display frame** from the newest device attitude,
honouring roll and working straight up or down, so it sticks to the scene like a photosphere app's
guide instead of trailing the vision analyzer.

### Rotation convention

Everything is expressed as `worldFromCamera`: a **row-major 3×3** whose **columns are the OpenGL
camera axes** (x right, y up, z backward — the camera looks down −z) written in the
**East-North-Up** world frame of `TYPE_ROTATION_VECTOR`. Build it with:

- `AttitudeExtrapolator.worldFromCameraAt(timeNs, cameraFromDevice, out)` — per frame, interpolated /
  extrapolated to the draw time (the normal path);
- `CoverageGlowProjection.worldFromCamera(deviceToWorld, cameraFromDevice)` — from one
  rotation-vector matrix;
- `CoverageGlowProjection.worldFromCamera(heading, elevation, roll)` — from the
  `CameraAttitudeProvider` / `PhotosphereMap.directionOfPixel` angles (undefined at the poles).

`cameraFromDevice` is the device-body → GL-camera rotation of the image the glow is drawn over: for a
display-upright preview, `DeviceCameraRotation.cameraFromDeviceForDisplayFrame(displayRotationDeg)`.

### Lens

`CoverageGlowProjection.Lens` describes what the drawn surface shows:

- `Lens.fromIntrinsics(intrinsics)` / `fromIntrinsics(fx, fy, cx, cy, width, height)` — exact,
  including an off-centre principal point. For a `FILL_CENTER` preview pass the intrinsics of the
  *displayed* crop: `CameraFillCenter.intrinsics(frameIntrinsics, viewWidth, viewHeight)`.
- `Lens.fromFieldOfView(horizontalFovDeg, verticalFovDeg)` — centred principal point.

### Renderer (display rate)

~~~kotlin
val attitude = CameraAttitudeProvider(context).apply { start(SensorManager.SENSOR_DELAY_GAME) }
val glow = CoverageGlowRenderer(CoverageGlowRenderer.WHITE, 0.14f).apply {
    fillMode = CoverageGlowRenderer.FillMode.COMPLEMENT_OUTLINED
}
// On layout / camera-intrinsics change:
glow.setLens(CoverageGlowProjection.Lens.fromIntrinsics(CameraFillCenter.intrinsics(frame, viewW, viewH)!!))
// Once (any thread): rotation for the frame being drawn, queried on the GL thread every draw.
val cameraFromDisplay = DeviceCameraRotation.cameraFromDeviceForDisplayFrame(displayRotationDeg)
glow.rotationSource = CoverageGlowRenderer.RotationSource { frameTimeNanos, out ->
    attitude.attitudeExtrapolator.worldFromCameraAt(frameTimeNanos + leadNs, cameraFromDisplay, out)
}
// Whenever the map changes (per keyframe), not per frame:
glow.setRegions(map.currentRegions())
~~~

- `frameTimeNanos` is in the `SystemClock.elapsedRealtimeNanos()` base (the `SensorEvent.timestamp`
  base). Do not use `System.nanoTime()` or Choreographer time.
- `leadNs = 0` is a good start. A small negative lead (e.g. −30 ms) times the glow to the preview frame
  actually on screen if it visibly leads the camera image during fast pans; the extrapolator
  interpolates inside its history and extrapolates at most 50 ms past the newest sample.
- `setRegions` pre-samples the tiles once (tile edges cut into ≤ 5° segments, seam-exact across
  0/360°); each draw is then a few hundred 3×3 multiplies with no allocation. When no
  `rotationSource` is set, push a rotation with `setWorldFromCamera(worldFromCamera)`.
- Use the **same sensor** (`TYPE_ROTATION_VECTOR`, East-North-Up) that stamps keyframe headings, so
  tiles and glow share one world frame. `CameraAttitudeProvider` deliberately stays on the
  magnetometer-fused rotation vector.

### Styles

`fillMode` (live, any thread) picks the style; feed the matching regions:

| `FillMode` | Draws | Feed `setRegions` with |
| --- | --- | --- |
| `TRIANGLES` (default) | flat haze over the supplied tiles | `map.regionsNeedingUpdate()` |
| `COMPLEMENT` | wash over everything except the supplied tiles (holes) | `map.currentRegions()` |
| `OUTLINE` | only a soft glowing band on the needs-update side of each boundary | `map.currentRegions()` |
| `COMPLEMENT_OUTLINED` | `COMPLEMENT` wash plus the `OUTLINE` edge on top | `map.currentRegions()` |

Colour and intensity are live too, from any thread while drawing: `hazeColor` (RGB floats) or
`setHazeColorArgb(argb)`, `hazeAlpha` (wash, default 0.2), `outlineAlpha` and `outlineWidthPx` (edge).
Defaults: hot pink `#FF69B4` at 0.2, `TRIANGLES`.

### Hosting

- **Inside your own GL renderer** (beneath the artwork): `createGlResources()` in `onSurfaceCreated`,
  `drawEmbedded()` (or `drawEmbedded(frameTimeNanos)`) in `onDrawFrame`, `releaseGlResources()` on the
  GL thread when done. It never clears, blends premultiplied into the bound framebuffer/viewport and
  restores the GL state it touches; complement/outline styles use a private offscreen mask, so no
  stencil or depth buffer is needed. On ES 3 it draws in its own VAO (host vertex state untouched);
  on ES 2, re-specify any vertex-attribute pointer you keep on the glow's attribute location after the
  call (GLES20 cannot read pointers back). After a context loss call `onGlContextLost()`.
- **As a drop-in view** above the preview, `:overlay`'s `CoverageGlowView`:

~~~kotlin
val haze = CoverageGlowView(context)
parent.addView(haze) // above the camera preview
haze.renderer.setLens(lens)
haze.trackAtDisplayRate { t, out -> attitude.attitudeExtrapolator.worldFromCameraAt(t, cameraFromDisplay, out) }
haze.updateRegions(map) // per keyframe; feeds the regions the current fillMode draws from
~~~

  Change the style with `haze.setFillMode(mode, map)` (or set `renderer.fillMode` and call
  `updateRegions` again): TRIANGLES takes `regionsNeedingUpdate()`, the COMPLEMENT / OUTLINE styles
  `currentRegions()`.

### Pure projection

`CoverageGlowProjection` is usable without the renderer:
`projectRegions(regions, worldFromCamera, lens)` returns NDC triangles,
`project(directions, worldFromCamera, lens)` places point marks, and
`prepareRegions(regions)` → `PreparedRegions.projectInto(worldFromCamera, lens, out)` is the
allocation-free per-frame form.

**Deprecated:** `CoverageGlowProjection.projectRegions(regions, heading, elevation, hFov, vFov)` and
`CoverageGlowView.update(map, heading, elevation, hFov, vFov)`. They still work unchanged, but build a
horizon-levelled camera (ignoring roll), return nothing near straight up/down, assume a centred
principal point, and only move when called. Migrate to `worldFromCamera` + `Lens`.

## Display-rate pose prediction

A vision solve lands tens of milliseconds after its frame, at analyzer rate. `attitude.DisplayPosePredictor`
holds the latest vision pose with the attitude **at its frame** and, on every draw, rotates it by the
attitude change since then (camera centre held — the gyro-bridge math, but without re-referencing, so
it never accumulates). Rotation, the dominant motion of a hand-held phone, then follows the device at
display rate; translation still updates at vision rate.

~~~kotlin
val predictor = DisplayPosePredictor()
// analyzer thread, per solved frame (column-major camera-from-world, GL eye frame):
predictor.update(cameraFromWorld, frameAttitudeQuaternion, cameraFromDevice, frameTimestampNs)
// GL thread, per draw:
if (predictor.predictInto(attitude.attitudeExtrapolator, SystemClock.elapsedRealtimeNanos(), viewOut)) draw(viewOut)
~~~

With `:reloc`'s `PhotosphereTrackingSession`, just set `session.displayPosePredictor = predictor`: every
rendered pose is published to it with its frame's attitude and `cameraFromDevice`, and it is cleared
whenever a frame yields no pose. The predictor and the extrapolator must use the same attitude sensor
as `Frame.attitude`. Both are thread-safe and their `*Into` / `*At` reads allocate nothing.

## Standalone photosphere tracking (`:reloc`, experimental)

For a phone without ARCore, `PhotosphereTrackingSession` is the whole frame-in / pose-out runtime:
a base photosphere pose from device attitude, a planar KPM precision layer rebased into it, a gyro
bridge across short misses, and a self-growing multi-page atlas.

~~~kotlin
val tracking = PhotosphereTrackingSession(
    PhotosphereTrackingSession.Config(timestampSource = CameraTimestampSource.REALTIME),
    PhotosphereTrackingSession.Callbacks(
        onKeyframe = { keyframe -> mapLock.withLock { keyframeStore.record(keyframe, nowMs) } },
        onMapFromFingerprintSolved = { persist(it) }, // hand it back via updatePrecisionLayer(…, mapFromFingerprint = it)
        onDiagnostic = { Log.d("SphereSLAM", it) },
    ),
)
tracking.displayPosePredictor = predictor
tracking.updatePrecisionLayer(PhotosphereTrackingSession.ReferenceImage(luma, w, h))
// per display-upright analysis frame:
val cameraFromDisplay = DeviceCameraRotation.cameraFromDeviceForDisplayFrame(displayRotationDeg)
val result = tracking.onFrame(
    PhotosphereTrackingSession.Frame(
        luma = packedLuma, intrinsics = frameIntrinsics, timestampNs = image.imageInfo.timestamp,
        attitude = attitude.latestSample(),
        cameraFromDevice = cameraFromDisplay,
        headingDeg = attitude.latestHeadingDegrees(), elevationDeg = attitude.latestElevationDegrees(),
        rollDeg = attitude.latestDeviceToWorldMatrix()?.let { CameraAttitudeProvider.cameraRollDegrees(it, cameraFromDisplay) },
        convertDurationNs = convertNs,
    ),
)
result.pose?.let { draw(it.cameraFromMap, it.projection) }
~~~

### Matching speed

`Config.matchSpeed: KpmMatchSpeedConfig` sets how much KPM work each frame costs (all on by default):

- **Downscaled matching** — KPM matches a box-filtered half-resolution luma (`matchDownscale = 2`,
  never below a 240 px short side) with intrinsics scaled to match. The pose convention is unchanged.
- **Flow tracking between full solves** — the full solve's inlier correspondences are tracked with
  forward–backward-checked pyramidal Lucas–Kanade and the pose re-solved by PnP against the page's
  reference coordinates (no chained drift). A full KPM solve runs on flow loss or weakness, a gate
  rejection, and at least every 10 frames / 500 ms. Flow poses never grow the atlas.
- **Focused pages** — with a multi-page atlas and a recent lock, the page under the gyro-predicted
  optical axis is matched alone first. If it misses, that frame reports no match and the next full
  solve searches all pages (a frame never pays for both).

`TrackedPose.matchMethod` (`VisualMatchMethod.KPM_FULL`, `KPM_FOCUSED`, `FLOW`) says which path
produced a pose. `KpmMatchSpeedConfig.FULL_KPM_EVERY_FRAME` restores a full-resolution all-pages
solve on every frame.

**Flow tracking needs OpenCV's native library** (`System.loadLibrary("opencv_java5")`, as for
`Relocalizer`). Without it flow switches itself off and every frame takes the full KPM path, so a host
that forgets to load OpenCV silently loses most of the speed-up.

### Background atlas worker

The analyzer thread never builds a matcher. The initial build, rebuilds after a reference or
intrinsics change, and every atlas-growth attempt (rectification, quality gate, page feature
extraction, index rebuild) run on the session's private background thread. Growth **forks** the live
matcher (`SphereSlamStandaloneSession.fork()`, a native clone of its atlas), adds the page to the fork
while `onFrame` keeps matching on the live one, and swaps the grown matcher in at the start of a later
frame; a rejected page just drops the fork. All callbacks still fire on the `onFrame` thread.

Behaviour to expect:

- **`result.pose == null` until the first build lands** (and briefly after an intrinsics change);
  frames meanwhile carry the base pose (`FrameResult.baseCameraFromMap`) only.
- **A too-weak reference fails on the frame after its build**, not the frame that requested it
  (`ReferenceTooWeakException` → `TrackingState.FATAL`, `Callbacks.onFatalError`, failure severity
  `RECOVERABLE`). Only the precision layer stops: frames keep returning the base pose and keyframes,
  `isRecoverable` is true, and the next `updatePrecisionLayer` with a different reference (or changed
  pages, or `forceRebuild = true`) resumes with the base map intact.
- **Every precision-layer rebuild starts unconfirmed.** A new or dropped reference, a different
  `map_from_fingerprint`, a changed page set, `forceRebuild` or an intrinsics change resets the
  tracking state to `INITIALIZING`; the next lock needs `confirmationFrames` accepted frames before a
  pose is drawn or `map_from_fingerprint` is solved.
- **`updatePrecisionLayer(reference)` with no map keeps the map the session already solved for that
  same reference**; a host-supplied map overrides it, and a new reference re-solves on its first
  confirmed lock.
- **`atlasPages` is the complete page set.** Pass `tracking.atlasPages` (or equal copies) back to keep
  the pages the session grew; any other set rebuilds the matcher from exactly those pages. Grown page
  numbers are never reused.
- **A growth attempt that throws is a diagnostic** (`atlas growth failed …`), not a fatal error.
- `Frame.luma` is not retained after `onFrame` returns, so the host may reuse a pooled buffer.

### Diagnostics

Every `metricsIntervalMs` (5 s) `Callbacks.onDiagnostic` receives a KPM line — the last accepted match
(`page inliers error ageMs matchMs method=`) or `KPM no-lock` — followed by per-stage timing over the
window, as `mean/max` milliseconds:

~~~text
SphereSLAM standalone KPM page=0 inliers=87 error=3.1 ageMs=41.2 matchMs=18.4 method=FLOW timing{frames=150 attempts=150 matched=141 convertMs=1.2/2.9 setupMs=0.3/1.1 matchMs=11.7/38.0 keyframeMs=0.4/6.2 growthMs=0.1/0.4}
~~~

`attempts` frames ran the matcher and `matched` of them got a pose back (before gating). `error` is
the mean squared reprojection error in pixels² of the image the pose was solved in (KPM: the
matcher's downscaled image; flow: the full frame), the unit `PoseAcceptanceConfig.maxReprojectionError`
gates. `convertMs` is whatever the host reports through `Frame(convertDurationNs = …)` (its Y-plane
pack/rotate); `setupMs` is matcher adoption plus the frame copy; `growthMs` is the analyzer-side
cost of starting a growth attempt (the work itself runs on the worker).

### Pooled luma

`LumaFrameTransform.packCropAndRotate` returns a fresh array per frame (≈300 KB at 640×480). For an
allocation-free camera loop use either:

- `LumaFrameBufferPool(slots = 2)` — round-robin pooled output. A returned array is overwritten
  `slots` calls later, so keep at most `slots − 1` frames; copy anything retained longer (a keyframe
  list, a queue to another thread). `PhotosphereTrackingSession` and `PhotosphereKeyframe` copy what
  they keep, so they are safe even with `slots = 1`.
- `packCropAndRotateInto(…, destination)` / `packAndRotateInto` — the returned `RotatedLuma.bytes`
  **is** your `destination`; reuse it only once nothing still reads the previous frame.

## Depth (`:models`, experimental)

`DepthEstimator` runs **MiDaS v2.1 Small** (256×256, fp16 weights,
`midas_v21_small_256_fp16.tflite`) on **LiteRT 1.4.2 with the GPU delegate**. If the GPU delegate
cannot be initialised (block-listed device, driver failure, failed warm-up inference) it falls back to
LiteRT's XNNPACK CPU path and logs an error; `activeBackend` says which backend is in use. Output is
MiDaS **relative inverse depth** (larger = nearer), a building block for `:reloc`'s
`RadialDepthPlacement` / `DepthScaleFit` (not yet wired into the session).

~~~kotlin
@OptIn(ExperimentalSphereSlamModelsApi::class)
val depth = DepthEstimator(
    context,
    options = DepthEstimatorOptions(backend = DepthEstimatorOptions.Backend.GPU), // the default
)
depthWorker.execute {
    if (!depth.load()) Log.w(TAG, "depth unavailable: ${depth.lastError}")
    Log.i(TAG, "depth backend=${depth.activeBackend}") // GPU expected; CPU = fallback
}
// later, on the depth worker, with a copy of a tracking frame:
val map: DepthMap? = depth.estimateLuma(lumaCopy, width, height) // ≤ 128 px long side by default
~~~

- The estimator owns its own `SphereSLAM-depth` thread: every method may be called from any thread
  and **blocks** until the model work is done, so call it from a low-priority worker — never from the
  camera analyzer, GL or UI thread. Calls are serialized.
- `estimateLuma` takes a grey plane (e.g. the display-upright tracking luma) with no `Bitmap` round
  trip; `estimate(bitmap)` takes ARGB. The frame is squashed to the model's square input, so map depth
  pixels back per axis (`u·W/N`, `v·H/N`). Copy a pooled luma buffer before handing it to the depth
  worker.
- `DepthEstimatorOptions`: `backend` (`GPU` default, `CPU`), `cpuThreads` (2, for the CPU path),
  `allowFp16Precision` (true), `gpuProgramCache` (true; compiled GPU programs cached under
  `codeCacheDir`, so only the first load pays shader compilation).
- Do not add LiteRT 2.x to an app using `:models` (it drops the delegate API `litert-gpu` 1.4.2
  needs). `:models` ships its own R8 keep rules.
- `SuperPointDetector` and `LowLightEnhancer` stay on ONNX Runtime (CPU).

Model provenance, licence, checksums, accuracy checks and the APK size impact are in
[docs/MODELS.md](docs/MODELS.md).

## App integration

The wiring a host app needs for standalone photosphere tracking with a display-rate glow and artwork.
`attitude` below is one `CameraAttitudeProvider` shared by everything.

**1. Load native libraries (once, e.g. `Application.onCreate`).**

~~~kotlin
System.loadLibrary("opencv_java5") // :reloc flow tracking, Relocalizer, PlanarTracker
check(SphereSlam.isOperational()) // libsphereslam + a calibrated KPM engine (false: no submodule/ABI)
~~~

SphereSLAM never loads OpenCV itself; `:reloc` brings the OpenCV AAR (`org.opencv:opencv`) onto your
classpath.

**2. Attitude sensor.** One `TYPE_ROTATION_VECTOR` source for keyframe headings, `Frame.attitude`,
the glow and the pose predictor, at display-overlay rate:

~~~kotlin
val attitude = CameraAttitudeProvider(context)
override fun onResume() { attitude.start(SensorManager.SENSOR_DELAY_GAME) } // or SENSOR_DELAY_FASTEST
override fun onPause() { attitude.stop() }
~~~

Every sample feeds `attitude.attitudeExtrapolator` with its `elapsedRealtimeNanos` timestamp.
`latestSample()` is defined at the poles; the heading/elevation reads are not.

**3. Renderer setup (GL thread).**

~~~kotlin
val glow = CoverageGlowRenderer().apply { fillMode = CoverageGlowRenderer.FillMode.COMPLEMENT_OUTLINED }
val predictor = DisplayPosePredictor()
@Volatile var cameraFromDisplay = DeviceCameraRotation.cameraFromDeviceForDisplayFrame(displayRotationDeg)

glow.rotationSource = CoverageGlowRenderer.RotationSource { t, out ->
    attitude.attitudeExtrapolator.worldFromCameraAt(t, cameraFromDisplay, out)
}

override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) { glow.createGlResources() /* + your GL */ }
~~~

**4. `setLens` on layout or intrinsics change** (view resized, camera rebound, display rotated —
also refresh `cameraFromDisplay` then):

~~~kotlin
val shown = CameraFillCenter.intrinsics(frameIntrinsics, viewWidth, viewHeight) ?: return
glow.setLens(CoverageGlowProjection.Lens.fromIntrinsics(shown))
projection = CameraFillCenter.projection(frameIntrinsics, viewWidth, viewHeight)!!
~~~

`frameIntrinsics` are those of the tracked (display-upright) frame, e.g.
`CameraIntrinsicsEstimator.estimate(context, cameraId, w, h)` rotated with `CaptureRotation`.

**5. Camera analyzer (one worker thread).**

~~~kotlin
val pool = LumaFrameBufferPool(slots = 2)
tracking.displayPosePredictor = predictor

fun analyze(image: ImageProxy) {
    val t0 = SystemClock.elapsedRealtimeNanos()
    val y = image.planes[0]
    val luma = pool.packAndRotate(y.buffer, image.width, image.height, y.rowStride, y.pixelStride,
        image.imageInfo.rotationDegrees)
    val convertNs = SystemClock.elapsedRealtimeNanos() - t0
    val result = tracking.onFrame(PhotosphereTrackingSession.Frame(luma.bytes, frameIntrinsics,
        image.imageInfo.timestamp, attitude.latestSample(), cameraFromDisplay,
        attitude.latestHeadingDegrees(), attitude.latestElevationDegrees(),
        // Roll of the tracked (display-upright) image, not of the device's natural +x axis:
        attitude.latestDeviceToWorldMatrix()?.let { CameraAttitudeProvider.cameraRollDegrees(it, cameraFromDisplay) },
        convertDurationNs = convertNs))
    image.close()
}
~~~

Use `CameraTimestampSource.REALTIME` in `Config` only when Camera2 reports
`SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` (frame-age gating depends on it).

**6. `setRegions` on map change.** Feed keyframes into a `PhotosphereKeyframeStore` /
`PhotosphereMap` (from `Callbacks.onKeyframe`) and, whenever the map changes, hand the glow its tiles
(guard map reads with the same lock as its writes):

~~~kotlin
onKeyframe = { kf ->
    val regions = synchronized(mapLock) { store.record(kf, nowMs); map.currentRegions() }
    glow.setRegions(regions) // regionsNeedingUpdate() for FillMode.TRIANGLES
}
~~~

**7. Pose prediction per draw (GL thread).**

~~~kotlin
override fun onDrawFrame(gl: GL10?) {
    val now = SystemClock.elapsedRealtimeNanos()
    drawCameraPreview()
    glow.drawEmbedded(now)
    if (predictor.predictInto(attitude.attitudeExtrapolator, now, view)) drawArtwork(projection, view)
}
~~~

`view` is column-major camera-from-map (GL eye frame), the same frame as `TrackedPose.cameraFromMap`.
When nothing is tracked the predictor is cleared and the artwork is hidden (or fall back to
`FrameResult.baseCameraFromMap` for map-anchored content).

**8. Depth (optional, off the analyzer thread).** See [Depth](#depth-models-experimental): load once,
then call `estimateLuma` on a copy of the tracking frame from a separate low-priority worker, never on
the analyzer or GL thread.

**9. Teardown.** `tracking.close()` (native matchers are released on its worker), `glow.releaseGlResources()`
on the GL thread, `attitude.stop()`, `depth.close()`.

## Toolkit packages

`:sphereslam` also ships the pure helpers hosts otherwise re-implement:

| Package | Contents |
| --- | --- |
| `math` | `RotationMath`, `RigidMath` (quaternions, rigid/similarity 4×4, GL ↔ CV flips) |
| `attitude` | `GameRotationAttitudeSource`, `DeviceCameraRotation`, `AttitudeRotationBridge`, `AttitudeExtrapolator`, `DisplayPosePredictor` |
| `camera` | `CameraIntrinsicsEstimator`, `CaptureRotation`, `LumaFrameTransform`, `LumaFrameBufferPool`, `ProjectionMatrix`, `ScreenIntrinsics` (`FIT_CENTER`), `CameraFillCenter` (`FILL_CENTER` projection, tap mapping, FOV) |
| `sidecar` | ARCore + KPM sidecar fusion: `HybridPoseHistory`, `HybridKpmCorrection`, `HybridAnchorFusion`, `MetricPageRectification` |
| `gyro` | `GyroCompensationMath` (gyro-stabilised screen overlays) |

Photosphere capture: `PhotosphereKeyframeStore`, `PhotosphereMap.directionOfPixel` (roll-aware) and
`PhotosphereFingerprintFrame`. The device → camera rotation convention is documented in
[docs/PUBLIC_API.md](docs/PUBLIC_API.md#device--camera-rotation).

~~~kotlin
// Hold a vision pose through a short dropout with the gyro (:sphereslam only).
val attitude = GameRotationAttitudeSource(context).apply { start() }
val bridge = AttitudeRotationBridge(
    DeviceCameraRotation.cameraFromDevice(sensorOrientation, imageRotationAppliedDeg = rotationDegrees)
)
// after each frame whose pose you drew from vision:
bridge.markReference(attitude.latestSample())
// on a frame with no vision pose: rotate the last drawn pose about its camera centre
lastPose = bridge.advance(lastPose, attitude.latestSample()?.quaternion) ?: lastPose
~~~

(`:reloc`'s `RobustTrackingLoop` accepts `bridge.bridgeFunction { attitude.latestSample() }` as its
`bridgeRotatedPose` to do this inside its tracking-state machine.)

## Native availability

The native engine ships for `arm64-v8a` and `armeabi-v7a` only. On any other ABI, on any other load
failure, or in a build without the artoolkitX submodule, `SphereSlam.isAvailable()` /
`isOperational()` return false instead of throwing. `SphereSlam.isOperational()` additionally creates
and closes a calibrated native engine (fail-closed, never throws).

Native KPM sessions are independent: each has its own lock, so a matcher can be built or cloned on one
thread while another session matches on a different thread. One session handle is still driven from
one thread at a time.

## Experimental relocalization API

The `:reloc` module intentionally exposes OpenCV-backed types. Its dependency metadata exports
OpenCV so those signatures are valid for consumers. SphereSLAM does not load OpenCV's native
library itself: load `libopencv_java5` (`System.loadLibrary("opencv_java5")`) before constructing a
`Relocalizer` or `PlanarTracker`, and before the first `PhotosphereTrackingSession.onFrame` if you want
flow tracking.

Using the module produces an opt-in warning. A consumer that knowingly accepts the pre-1.0
experimental contract can opt in:

~~~kotlin
@OptIn(ExperimentalSphereSlamRelocApi::class)
fun useReloc() {
    // ...
}
~~~

Wide-area relocalization: `WallFeatureMap` grows from every successful `RelocResult` and is matched
frustum-gated (`visibleIndices` → `toFingerprint`); `RectifyHomography` pre-cancels oblique
perspective; `MetricMarks` builds metric marks from a known plane or two views; `RadialDepthPlacement`
places off-wall features from a calibrated relative depth map.

`PlanarTracker` is the small case: track one captured reference image and get an OpenGL view matrix
per frame (`setReference(luma, w, h, halfW, halfH)`, then `track(luma, w, h, intrinsics, attitude)`),
with a short gyro bridge across misses.

The optional `:models` module follows the same policy with `ExperimentalSphereSlamModelsApi`.

## Pose naming

SphereSLAM uses transform-direction names instead of ambiguous “pose matrix” names:

- `cameraFromPage3x4`: page -> camera, row-major 3x4 KPM output.
- `cameraFromCanonical`: canonical wall -> camera/view, column-major 4x4.
- `canonicalFromContent`: content -> canonical wall, column-major 4x4.
- `cameraFromContent`: content -> camera/view, column-major 4x4.
- `cameraFromMap`: photosphere base map -> camera/view, column-major 4x4 (`:reloc`).
- `worldFromCamera`: GL camera axes in East-North-Up, row-major 3x3 (coverage glow).

Legacy `viewMatrix`, `viewFromContent`, and `pageToCamera3x4` accessors remain temporarily as
deprecated aliases.

## Project status

- Planar KPM tracking: built
- Multi-page canonical wall frame: built
- Physical-size placement/retention: built
- Angular coverage and display-rate coverage glow: built
- Display-rate pose prediction: built
- Standalone photosphere tracking (downscaled KPM, flow tracking, background atlas growth): built,
  experimental API
- Photosphere freshness/relock loop: built, experimental API
- Depth-backed tile triangulation/corroboration: building blocks (not yet wired), experimental API
- Full omnidirectional production map-growth pipeline: still evolving

## License

SphereSLAM is licensed under the [Apache License 2.0](LICENSE). Third-party components and their
licenses (artoolkitX — LGPLv3 with a static-linking exception, statically linked into
`libsphereslam.so`; the bundled MiDaS and SuperPoint model weights — MIT; OpenCV, LiteRT — Apache
2.0; ONNX Runtime — MIT) are listed in [NOTICE](NOTICE), with their texts in
[third_party_licenses/](third_party_licenses). Every published AAR carries LICENSE, NOTICE and those
texts under `META-INF/sphereslam/<module>/`.

Provenance: the planar engine originated in HereLiesAz/GraffitiXR and is maintained here as a
standalone reusable library.

## SphereSLAM

A low-tech, lightweight AR platform — markerless 6-DoF tracking and relocalization on everyday
Android devices, **without ARCore**. Built for the case where ARCore isn't available (or isn't
wanted) and a full SLAM stack is overkill: a flat reference in the scene is enough to anchor and
hold an overlay.

### Two approaches — both fully available

SphereSLAM ships **two tracking/relocalization strategies**, and the goal is for a consumer to
pick whichever fits the app, with both fully supported in this repository:

1. **Classic — planar KPM.** Natural-feature (KPM) tracking of one or more flat rectified
   reference pages on a single `z=0` canonical frame. 6-DoF while a page is visible, with a short
   rotation-only gyro bridge across brief dropouts. No guided scan, no depth. Lightweight,
   deterministic, and ideal when the thing being tracked is itself planar (a wall, a page, a
   poster).
2. **Spherical coverage.** The same planar tracker, plus a guided sweep that fills a surrounding
   feature map — using recorded gyro bearing and monocular (MiDaS) depth to place features in
   every direction around the viewpoint. This makes relocalization **fast and anticipatory**: the
   tracker can re-lock from the surroundings, not only when the original reference is back in
   frame. Best when the overlay is larger than the reference, or the user looks away and back a
   lot.

The two are not mutually exclusive — spherical coverage is a layer *on top of* the classic planar
tracker, so an app can run classic-only (lightest) or classic + spherical coverage (most robust
relocalization). The repository intends to carry **both, fully built and selectable**, not one
superseding the other.

### Status

| Piece | State |
|------|-------|
| Classic planar KPM | **present and wired** (`sphereslam/`) over the bundled native engine |
| Spherical coverage | **native API extracted** — per-keyframe orientation storage, MiDaS-depth radial map placement, and the map-reloc tuning counters all live in `core/nativebridge` |
| Native engine | **bundled** (`core/nativebridge`): artoolkitX KPM + MobileGS + OpenCV, with artoolkitX as a submodule |
| Standalone Gradle project | **present** — root project, version catalog, wrapper; the Kotlin layer of all three modules compiles and its unit tests pass |
| Native `.so` build / Maven publish | **pending on-device verification** — see Building |

This repository now carries the engine, not just a source snapshot. `core/nativebridge` holds the
native KPM/MobileGS C++ and JNI (package `com.hereliesaz.graffitixr.nativebridge`, kept verbatim so
the JNI symbol names stay valid); `core/common` carries only the handful of model/sensor/util
classes the engine needs, with GraffitiXR's Hilt, ARCore, and wearable/smart-glass couplings
removed (`SlamManager` now takes a `SensorSource` directly). `sphereslam/` is unchanged and depends
on `:core:nativebridge`.

### Building

The native engine depends on the pinned **artoolkitX** submodule and the **OpenCV** Maven
artifact's Prefab part. After cloning:

~~~bash
git submodule update --init --recursive   # fetches third_party/artoolkitx @ the pinned commit
./gradlew :sphereslam:assembleRelease      # NDK build (arm64-v8a, armeabi-v7a)
~~~

What is verified in CI / here: the Kotlin of `:core:common`, `:core:nativebridge`, and
`:sphereslam` compiles, and their unit tests pass (including `NativeMethodAritySignatureTest`, which
guards the JNI↔Kotlin boundary). What still needs a machine with the Android NDK and the submodule
checked out: the CMake/`.so` build and an on-device run — the extraction could not exercise those
remotely.

### Roadmap

1. **Snapshot the classic approach** — done.
2. **Build spherical coverage** (in GraffitiXR first) — done: per-keyframe gyro orientation, MiDaS
   depth wired into geometry, guided sweep + surrounding feature map. Stabilized the native API.
3. **Extract the full engine into this repo** — done (this): native engine bundled, dependencies
   decoupled, standalone Gradle project. Remaining: verify the NDK build on device, then wire a
   Maven publish and bring the app-layer spherical-coverage helpers (MiDaS `DepthEstimator`,
   `CompassHeadingProvider`, `SphereCoverage`) over as an optional module.

Provenance: the classic module was imported verbatim from `HereLiesAz/GraffitiXR` @ `df9f121`; the
native engine and spherical-coverage native API were extracted from GraffitiXR `main` after Phases
1b–4 of the sphere map merged.

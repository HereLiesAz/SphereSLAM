# SphereSLAM public API contract

This document defines what downstream applications should treat as SphereSLAM's supported API.

The current development version is **0.23.3** and remains pre-1.0. The supported surface is being
hardened now so that a future 1.0 can actually carry a meaningful compatibility guarantee.

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
- `CoverageGlowProjection`

`SphereSlamEngine` is the lowest supported planar abstraction. Most applications should use
`SphereSlamStandaloneSession` or `SphereSlamTracker` instead.

#### `:overlay`

Optional rendering helper:

- `CoverageGlowView`
- `CoverageGlowRenderer`

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

#### `:models`

Optional ONNX model wrappers are marked with `ExperimentalSphereSlamModelsApi`. They are not part
of the compatibility-guaranteed planar API.

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

### Deprecated pose names

The following aliases remain temporarily for migration:

| Old | Replacement |
| --- | --- |
| `Pose.viewMatrix` | `Pose.cameraFromCanonical` |
| `AnchoredPose.viewFromContent` | `AnchoredPose.cameraFromContent` |
| `Observation.pageToCamera3x4` | `Observation.cameraFromPage3x4` |
| `OverlayPlacement.viewFromContent(...)` | `OverlayPlacement.cameraFromContent(...)` |

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
- `CoverageGlowRenderer.glowColor`

Mutating an array supplied to or returned from these APIs does not mutate library state.

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

Consumer construction is simply:

~~~kotlin
SphereSlamTracker()
~~~

The native implementation interface and executor injection are internal test seams.

## 5. Threading

- `SphereSlamStandaloneSession`: drive from one camera-analysis worker.
- `AnchoredStandaloneSession.match`: drive from the same camera worker as its base session.
- `SphereSlamTracker`: owns its worker; `submitFrame` is non-blocking and retains only the newest
  pending frame.
- `PhotosphereMap`, `PhotosphereReviewLoop`, and experimental `:reloc` state machines are not
  generally thread-safe unless explicitly documented otherwise.
- `CoverageGlowRenderer.setMarks` is safe to call from any thread.

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

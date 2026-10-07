# SphereSLAM

[![](https://jitpack.io/v/HereLiesAz/SphereSLAM.svg)](https://jitpack.io/#HereLiesAz/SphereSLAM)

Lightweight markerless 6-DoF tracking and relocalization for Android, including devices where ARCore
is unavailable or undesirable.

SphereSLAM has two layers:

1. **Planar KPM** — natural-feature tracking of one or more rectified planar references in one
   canonical wall frame. This is the supported consumer API in `:sphereslam`.
2. **Photosphere / depth relocalization** — orientation-indexed tiles, PnP relocalization,
   robustness gates, depth-backed triangulation, and relock seeding. This lives in `:reloc` and is
   intentionally experimental while its OpenCV-facing fingerprint surface evolves.

## API status

SphereSLAM is **pre-1.0**. There is no fictitious “frozen since 1.0” contract.

| Module | Status | Intended use |
| --- | --- | --- |
| `:sphereslam` | **Supported pre-1.0 API** | Planar tracking, standalone tracking, ARCore sidecar tracking, coverage, placement |
| `:overlay` | **Supported pre-1.0 API** | Optional coverage-glow UI |
| `:reloc` | **Experimental** | OpenCV-backed photosphere/relocalization primitives |
| `:models` | **Experimental** | Optional ONNX model helpers |
| `:core:common` | **Internal** | Native loading/implementation support |
| `:core:nativebridge` | **Internal** | JNI/KPM bridge |

The internal modules are compiler-gated with an error-level opt-in marker. Application code should
not depend on them directly.

The complete contract, pose conventions, ownership rules, and migration notes are in
[docs/PUBLIC_API.md](docs/PUBLIC_API.md).

## Build baseline

- JDK 21 toolchain
- Java/Kotlin Android bytecode target 17
- Gradle 9.8
- Android Gradle Plugin 9.4.1
- compileSdk 37
- minSdk 26

## Install

~~~kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.HereLiesAz.SphereSLAM:sphereslam:<tag>")
}
~~~

Add the optional glow renderer only when needed:

~~~kotlin
dependencies {
    implementation("com.github.HereLiesAz.SphereSLAM:overlay:<tag>")
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

## Coverage and glow

~~~kotlin
val attitude = CameraAttitudeProvider(context).apply { start() }
val coverage = SphereCoverage(elevationBandCount = 3)

coverage.setWallHeading(headingWhenCapturedHeadOn)

val heading = attitude.latestHeadingDegrees() ?: return
val elevation = attitude.latestElevationDegrees() ?: 0f
coverage.observe(heading, elevation)

val marks = CoverageGlowProjection.project(
    directions = coverage.thinDirections(),
    cameraHeadingDeg = heading,
    cameraElevationDeg = elevation,
    horizontalFovDeg = previewHFovDeg,
    verticalFovDeg = previewVFovDeg,
)
~~~

Or use the optional `:overlay` artifact:

~~~kotlin
val glow = CoverageGlowView(context)
glow.update(
    coverage.thinDirections(),
    headingDeg,
    elevationDeg,
    horizontalFovDeg,
    verticalFovDeg,
)
~~~

## Experimental relocalization API

The `:reloc` module intentionally exposes OpenCV-backed types. Its dependency metadata exports
OpenCV so those signatures are valid for consumers.

~~~kotlin
dependencies {
    implementation("com.github.HereLiesAz.SphereSLAM:reloc:<tag>")
}
~~~

Using the module produces an opt-in warning. A consumer that knowingly accepts the pre-1.0
experimental contract can opt in:

~~~kotlin
@OptIn(ExperimentalSphereSlamRelocApi::class)
fun useReloc() {
    // ...
}
~~~

The optional `:models` module follows the same policy with
`ExperimentalSphereSlamModelsApi`.

## Pose naming

SphereSLAM uses transform-direction names instead of ambiguous “pose matrix” names:

- `cameraFromPage3x4`: page -> camera, row-major 3x4 KPM output.
- `cameraFromCanonical`: canonical wall -> camera/view, column-major 4x4.
- `canonicalFromContent`: content -> canonical wall, column-major 4x4.
- `cameraFromContent`: content -> camera/view, column-major 4x4.

Legacy `viewMatrix`, `viewFromContent`, and `pageToCamera3x4` accessors remain temporarily as
deprecated aliases.

## Project status

- Planar KPM tracking: built
- Multi-page canonical wall frame: built
- Physical-size placement/retention: built
- Angular coverage and glow: built
- Photosphere freshness/relock loop: built, experimental API
- Depth-backed tile triangulation/corroboration: built, experimental API
- Full omnidirectional production map-growth pipeline: still evolving

Provenance: the planar engine originated in HereLiesAz/GraffitiXR and is maintained here as a
standalone reusable library.

## SphereSLAM
[![](https://jitpack.io/v/HereLiesAz/SphereSLAM.svg)](https://jitpack.io/#HereLiesAz/SphereSLAM)

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
| Classic planar KPM | **built & published** — native artoolkitX KPM engine bundled in `:core:nativebridge`, published on JitPack |
| World-size retention | **built** — place content at a real-world size and hold it across tracking loss (`AnchoredStandaloneSession` + `OverlayPlacement`) |
| Spherical coverage | **built** — 2-D angular coverage + directional glow projection (`SphereCoverage`, `CameraAttitudeProvider`, `CoverageGlowProjection`); native surrounding feature-map growth is the remaining step |

### Usage

Add the dependency (JitPack):

~~~kotlin
dependencies {
    implementation("com.github.HereLiesAz.SphereSLAM:sphereslam:<tag>")
}
~~~

**1. Track a planar surface at real-world size, and hold placed content there.**

~~~kotlin
val session = SphereSlamStandaloneSession(frameW, frameH, SphereSlamCalibration(fx, fy, cx, cy))
val anchored = AnchoredStandaloneSession(session)

// Register the reference with its measured physical width (metres). Pass physicallyMetric = true
// for a real measurement, or a normalized width (e.g. 1f) + false for visual-only registration.
anchored.base.addReference(luma, w, h, referenceWidthMeters = 0.30f, physicallyMetric = true)

// Place the artwork once, at the size the user chose (half-extents in metres):
anchored.place(OverlayPlacement.anchor(panXMeters, panYMeters, rotationZDeg, halfWMeters, halfHMeters))

// Per camera frame: a null result means "not visible this frame" — the placement is retained, so
// the content snaps back to the same spot and size when the surface is seen again.
anchored.match(luma, timestampNs)?.let { ap ->
    val mvp = multiply(projectionMatrix, ap.viewFromContent)      // column-major
    drawQuad(mvp, halfWidth = ap.halfWidthMeters, halfHeight = ap.halfHeightMeters)
}
~~~

**2. Track how much of the surroundings have been mapped, and glow the gaps.**

~~~kotlin
val attitude = CameraAttitudeProvider(context).apply { start() }
val coverage = SphereCoverage(elevationBandCount = 3)
coverage.setWallHeading(headingWhenSurfaceCapturedHeadOn)

// Per keyframe, fold in where the camera looks:
val h = attitude.latestHeadingDegrees() ?: return
coverage.observe(h, attitude.latestElevationDegrees() ?: 0f)
val progress = coverage.coverageFraction()                       // 0..1 for a read-out

// To draw a "map more here" glow, project the unscanned directions to the screen:
val marks = CoverageGlowProjection.project(
    directions = coverage.thinDirections(),
    cameraHeadingDeg = attitude.latestHeadingDegrees() ?: 0f,
    cameraElevationDeg = attitude.latestElevationDegrees() ?: 0f,
    horizontalFovDeg = previewHFovDeg,
    verticalFovDeg = previewVFovDeg,
)
// marks[i].onScreen → draw a soft glow at (ndcX, ndcY); else an edge arrow toward (ndcX, ndcY).
~~~

Coverage is purely angular, so it behaves identically on a 3 m wall and a 30 cm canvas.

### Roadmap

- **Native surrounding feature-map growth** — populate the map omnidirectionally from bearing +
  monocular depth so relocalization can re-lock from the surroundings, not only the reference.
- **On-device tuning** — coverage arc extents, elevation bands, and glow feel.

Provenance: the classic module was imported from `HereLiesAz/GraffitiXR`; the native engine, the
world-size-retention and the coverage/glow API are built here.

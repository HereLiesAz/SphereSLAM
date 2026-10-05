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
| Classic planar KPM | **present as a source snapshot** (`sphereslam/`), imported verbatim from GraffitiXR |
| Spherical coverage | **in development** in GraffitiXR; lands here once its native API is stable |
| Standalone build / publish | **not yet** — see below |

The classic module is currently a **source snapshot, not yet a standalone-buildable library.** It
depends on the native engine through `com.hereliesaz.graffitixr.nativebridge.KpmBridge` (artoolkitX
KPM + OpenCV, built in GraffitiXR's `core:nativebridge`), and `build.gradle.kts` still references
GraffitiXR's module and version catalog. The follow-up extraction bundles the native KPM engine
and a standalone Gradle/publish setup so both approaches build and ship from here.

### Roadmap

1. **Snapshot the classic approach** — done; preserved intact before the spherical-coverage rework
   so it remains a first-class option.
2. **Build spherical coverage** (in GraffitiXR first) — record per-keyframe gyro orientation, wire
   monocular depth into geometry, add the guided sweep + surrounding feature map. This stabilizes
   the native KPM/depth API.
3. **Extract the full engine into this repo** — bundle the native engine (artoolkitX KPM + OpenCV)
   and a standalone publish setup, bringing **both** approaches here as a real, buildable,
   publishable library.

Provenance: the classic module was imported verbatim from `HereLiesAz/GraffitiXR` @ `df9f121`.

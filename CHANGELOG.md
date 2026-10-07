# Changelog

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
- Dependency/toolchain versions are pinned to current stable releases.
- JitPack no longer relies on its cached submodule gitdir for artoolkitX; it materializes and verifies
  the exact pinned commit.
- JitPack bootstraps the checksum-verified Gradle 9.8.0 distribution directly, avoiding failures from
  a corrupted/inaccessible cached wrapper JAR.

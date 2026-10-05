## SphereSLAM

A low-tech, light-weight AR platform. 

---

### Classic approach — source snapshot

`sphereslam/` is a verbatim source snapshot of the SphereSLAM module as it exists in
GraffitiXR (`HereLiesAz/GraffitiXR` @ `df9f121`), preserved **before** the spherical-coverage
rework so this library can later offer both approaches:

- **Classic (this snapshot):** planar KPM natural-feature tracking — one or more flat rectified
  reference pages on a single `z=0` canonical wall frame; 6-DoF while a page is visible, with a
  short rotation-only gyro bridge across dropouts. No scan, no depth.
- **Spherical coverage (planned):** the same tracker plus a guided sweep that fills a
  surrounding feature map, using recorded gyro bearing + monocular (MiDaS) depth for fast,
  anticipatory relocalization.

**This is a source snapshot, not yet a standalone-buildable library.** The module depends on
the native engine through `com.hereliesaz.graffitixr.nativebridge.KpmBridge` (artoolkitX KPM +
OpenCV, built in GraffitiXR's `core:nativebridge`), and `build.gradle.kts` still references
GraffitiXR's module (`:core:nativebridge`) and version catalog. Making it build on its own —
bundling the native KPM engine and a standalone Gradle/publish setup — is the follow-up
extraction step. The snapshot is committed first so the classic approach is preserved intact.

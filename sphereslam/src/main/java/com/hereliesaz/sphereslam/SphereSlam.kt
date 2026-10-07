package com.hereliesaz.sphereslam

import com.hereliesaz.sphereslam.nativebridge.KpmBridge

/**
 * Public entry point for the native SphereSLAM/KPM path — the **planar-wall** layer.
 *
 * On ARCore-capable devices this is a sibling relocalizer beside ARCore. On devices where ARCore
 * cannot run, the same native KPM engine is used by the CameraX standalone wall-tracking path.
 * ARCore remains owned by feature/ar and is never routed through this API.
 *
 * ## Choosing an entry point (two public layers, by design)
 *
 * SphereSLAM exposes two entry layers; pick by what you are tracking:
 *
 * - **Planar-wall KPM** — this object and its session family: [SphereSlam] →
 *   [SphereSlamEngine]; [SphereSlamTracker] (beside ARCore), [SphereSlamStandaloneSession] (non-ARCore
 *   runtime), [AnchoredStandaloneSession] (world-size retention), with poses via [SphereSlamPoseMath].
 *   Use this to anchor to a flat reference surface (a mural wall, a page).
 * - **Photosphere / depth** — `SphereSlamSession` in the `:reloc` module: an orientation-indexed tile
 *   map with relocalization, coverage glow, and depth-backed off-page 6-DoF. Use this to anchor across
 *   a space rather than a single plane. (That module depends on this one, not the reverse.)
 *
 * Both layers are stable, frozen public API as of 1.0.
 */
object SphereSlam {
    fun isAvailable(): Boolean = KpmBridge.isAvailable()

    fun smokeTest(width: Int, height: Int): Boolean = KpmBridge.smokeTest(width, height)

    fun create(
        frameWidth: Int,
        frameHeight: Int,
        calibration: SphereSlamCalibration,
    ): SphereSlamEngine {
        require(frameWidth > 0 && frameHeight > 0)
        return KpmSphereSlamEngine(frameWidth, frameHeight, calibration, NativeKpmApi)
    }
}

package com.hereliesaz.sphereslam

import com.hereliesaz.sphereslam.nativebridge.KpmBridge

/**
 * Public entry point for the native SphereSLAM/KPM path — the **planar-wall** layer.
 *
 * On ARCore-capable devices this is a sibling relocalizer beside ARCore. On devices where ARCore
 * cannot run, the same native KPM engine is used by the CameraX standalone wall-tracking path.
 * ARCore remains owned by feature/ar and is never routed through this API.
 *
 * ## Choosing an entry point
 *
 * SphereSLAM has a supported planar surface and an experimental photosphere surface:
 *
 * - **Planar-wall KPM** — this object and its session family: [SphereSlam] →
 *   [SphereSlamEngine]; [SphereSlamTracker] (beside ARCore), [SphereSlamStandaloneSession] (non-ARCore
 *   runtime), [AnchoredStandaloneSession] (world-size retention), with poses via [SphereSlamPoseMath].
 *   Use this to anchor to a flat reference surface (a mural wall, a page).
 * - **Photosphere / depth** — `SphereSlamSession` in `:reloc`. That module is intentionally
 *   experimental and compiler-marked as such because its OpenCV-facing fingerprint API is still
 *   evolving.
 *
 * SphereSLAM is pre-1.0. The `:sphereslam` surface is the compatibility target; it is not claimed
 * frozen until a 1.0 release actually exists.
 */
object SphereSlam {
    fun isAvailable(): Boolean = KpmBridge.isAvailable()

    internal fun smokeTest(width: Int, height: Int): Boolean = KpmBridge.smokeTest(width, height)

    fun create(
        frameWidth: Int,
        frameHeight: Int,
        calibration: SphereSlamCalibration,
    ): SphereSlamEngine {
        require(frameWidth > 0 && frameHeight > 0)
        return KpmSphereSlamEngine(frameWidth, frameHeight, calibration, NativeKpmApi)
    }
}

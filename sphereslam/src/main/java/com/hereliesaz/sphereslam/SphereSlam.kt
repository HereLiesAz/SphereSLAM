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

    /**
     * Fail-closed runtime capability probe, stronger than [isAvailable]: the native entry points must
     * be linked **and** a calibrated native KPM session must actually be creatable, ready, and
     * closable. Creates and closes a temporary [SphereSlamEngine] with a synthetic calibration (no
     * image is matched); real tracking still uses the camera's actual intrinsics.
     *
     * Never throws: any failure reports false. Call off the main thread if native session creation
     * cost matters (it allocates the KPM handle once).
     *
     * @param frameWidth probe frame width, pixels, `> 0`.
     * @param frameHeight probe frame height, pixels, `> 0`.
     */
    fun isOperational(frameWidth: Int = PROBE_WIDTH, frameHeight: Int = PROBE_HEIGHT): Boolean =
        runCatching { probe(frameWidth, frameHeight, ::isAvailable, ::create) }.getOrDefault(false)

    internal const val PROBE_WIDTH = 640
    internal const val PROBE_HEIGHT = 480

    /** The testable core of [isOperational]. */
    internal fun probe(
        frameWidth: Int,
        frameHeight: Int,
        available: () -> Boolean,
        create: (Int, Int, SphereSlamCalibration) -> SphereSlamEngine,
    ): Boolean {
        require(frameWidth > 0 && frameHeight > 0)
        if (!available()) return false
        val calibration = SphereSlamCalibration(
            fx = frameWidth.toFloat(),
            fy = frameWidth.toFloat(),
            cx = frameWidth / 2f,
            cy = frameHeight / 2f,
        )
        val engine = create(frameWidth, frameHeight, calibration)
        return try {
            engine.isReady
        } finally {
            engine.close()
        }
    }

    fun create(
        frameWidth: Int,
        frameHeight: Int,
        calibration: SphereSlamCalibration,
    ): SphereSlamEngine {
        require(frameWidth > 0 && frameHeight > 0)
        return KpmSphereSlamEngine(frameWidth, frameHeight, calibration, NativeKpmApi)
    }
}

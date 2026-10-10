package com.hereliesaz.sphereslam.sidecar

import com.hereliesaz.sphereslam.math.RigidMath

/**
 * Rigid frame conversions at the primary-tracker (ARCore) ↔ metric-KPM seam. Matrices only — the
 * host samples them from its tracker; column-major 4×4 throughout.
 */
object HybridPageFrame {
    /** World-from-page at capture, from the capture camera view and the metric camera-from-page. */
    fun worldFromPage(sensorView: FloatArray, cameraFromPage: FloatArray): FloatArray =
        RigidMath.multiply(RigidMath.rigidInverse(sensorView), cameraFromPage)

    /**
     * Immutable centred-page-from-artwork relation. Invariant under any global world rebase `G`:
     * `inv(G·worldFromPage) · (G·worldFromArtwork) == inv(worldFromPage) · worldFromArtwork`.
     */
    fun pageFromArtwork(worldFromPage: FloatArray, worldFromArtwork: FloatArray): FloatArray =
        RigidMath.multiply(RigidMath.rigidInverse(worldFromPage), worldFromArtwork)
}

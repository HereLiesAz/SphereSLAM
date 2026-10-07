package com.hereliesaz.sphereslam.reloc

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint3f

/**
 * The matchable surface a [Relocalizer] needs from a reference: feature [descriptors] and the 3D
 * [points3d] each descriptor was detected at, in some reference frame. PnP + RANSAC never assumed
 * those points were coplanar, so this is all the solver depends on — a planar page
 * ([PlanarFingerprint]) and a triangulated off-page tile ([TileFingerprint]) are the same thing to
 * it, differing only in where their points live.
 */
interface Fingerprint {
    /** Feature descriptors, one row each; type must match the live frame's descriptors. */
    val descriptors: Mat

    /** The 3D point each descriptor was detected at, parallel to [descriptors]' rows. */
    val points3d: MatOfPoint3f

    /** Descriptor matcher norm: binary (Hamming) for ORB, L2 for a float descriptor set. */
    val binaryDescriptors: Boolean
}

package com.hereliesaz.sphereslam.reloc

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint3f

/**
 * An off-page photosphere tile distilled for relocalization: feature [descriptors] and the genuinely
 * 3D [points3d] each was triangulated at, expressed in the tile's own local frame. Unlike
 * [PlanarFingerprint], the points are not coplanar — so a PnP solve against them recovers a full
 * 6-DoF camera-from-tile pose even with no reference surface in view.
 *
 * [anchorFromTile] places that local frame into the map/anchor frame. A camera-from-tile result
 * (see [RelocResult]) becomes camera-from-map as
 * `cameraFromTile * inverse(anchorFromTile)`; the transforms share the tile as their source and
 * therefore do not multiply directly. [confidence] carries each point's triangulation quality for a
 * gate to weigh.
 *
 * This is the geometric half of a depth-backed tile: the library fills [points3d] / [confidence]
 * (by triangulation, see `TileTriangulator`) and admits a tile on *geometry*. Whether a tile is
 * *trustworthy to anchor on* — the teleological judgment — is decided by a host's own layer through
 * a `corroborate()` seam and never lives here.
 *
 * @property descriptors feature descriptors, one row each.
 * @property points3d the 3D point each descriptor was triangulated at, in the tile's local frame.
 * @property anchorFromTile row-major 4×4 placing the tile frame in the map/anchor frame.
 * @property confidence per-point triangulation quality parallel to [points3d] (higher is better), or
 *   null when unweighted.
 * @property binaryDescriptors descriptor norm (see [Fingerprint]).
 * @throws IllegalArgumentException if [anchorFromTile] is not length 16, descriptor rows are not
 *   parallel to [points3d], or [confidence] is non-null and not parallel to [points3d].
 */
class TileFingerprint(
    override val descriptors: Mat,
    override val points3d: MatOfPoint3f,
    val anchorFromTile: FloatArray,
    val confidence: FloatArray? = null,
    override val binaryDescriptors: Boolean = true,
) : Fingerprint {
    init {
        val pointCount = points3d.toArray().size
        validate(
            pointCount = pointCount,
            confidence = confidence,
            anchorFromTile = anchorFromTile,
            descriptorCount = descriptors.rows(),
        )
    }

    /** Number of 3D points (descriptor rows) in the fingerprint. */
    val size: Int get() = points3d.toArray().size

    companion object {
        /**
         * Structural invariants, split out so they are testable without native OpenCV.
         *
         * [descriptorCount] defaults to [pointCount] so callers exercising the original validation
         * API remain source-compatible.
         */
        fun validate(
            pointCount: Int,
            confidence: FloatArray?,
            anchorFromTile: FloatArray,
            descriptorCount: Int = pointCount,
        ) {
            require(anchorFromTile.size == 16) {
                "anchorFromTile must be a row-major 4x4 (length 16), was ${anchorFromTile.size}"
            }
            require(descriptorCount == pointCount) {
                "descriptors must be parallel to points3d ($pointCount), had $descriptorCount rows"
            }
            require(confidence == null || confidence.size == pointCount) {
                "confidence must be parallel to points3d ($pointCount), was ${confidence?.size}"
            }
        }
    }
}

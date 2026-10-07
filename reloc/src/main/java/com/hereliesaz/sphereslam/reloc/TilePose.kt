package com.hereliesaz.sphereslam.reloc

/**
 * Pose assembly for depth-backed tiles. A PnP match against a [TileFingerprint] yields
 * `camera_from_tile` (see [RelocResult]); a tile also knows `anchor_from_tile`
 * ([TileFingerprint.anchorFromTile]). This lifts the former into the map/anchor frame so the overlay
 * renderer places content consistently no matter which tile happened to match.
 *
 * All matrices are **row-major** 4×4 (length 16), matching [RelocResult] and
 * [TileFingerprint.anchorFromTile]. Pure — unit-tested without OpenCV.
 */
object TilePose {

    /**
     * `camera_from_map = camera_from_tile · tile_from_map`, where `tile_from_map` is the inverse of
     * the tile's `anchor_from_tile` (anchor == map frame here). Composing through the tile frame
     * cancels it, leaving the camera pose expressed against the map.
     *
     * @param cameraFromTile row-major 4×4, a tile-relative PnP result.
     * @param anchorFromTile row-major 4×4, the tile's placement in the map frame.
     * @return row-major 4×4 `camera_from_map`.
     */
    fun cameraFromMap(cameraFromTile: FloatArray, anchorFromTile: FloatArray): FloatArray {
        require(cameraFromTile.size == 16) { "cameraFromTile must be length 16" }
        require(anchorFromTile.size == 16) { "anchorFromTile must be length 16" }
        return multiplyRowMajor4(cameraFromTile, rigidInverseRowMajor4(anchorFromTile))
    }

    /** Row-major 4×4 product `a · b`. */
    internal fun multiplyRowMajor4(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (row in 0 until 4) for (col in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[row * 4 + k] * b[k * 4 + col]
            r[row * 4 + col] = sum
        }
        return r
    }

    /** Inverse of a row-major rigid transform `[R|t]`: `[Rᵀ | −Rᵀt]`. */
    internal fun rigidInverseRowMajor4(m: FloatArray): FloatArray {
        val r = FloatArray(16)
        r[15] = 1f
        for (i in 0 until 3) for (j in 0 until 3) r[j * 4 + i] = m[i * 4 + j] // transpose rotation
        val tx = m[3]; val ty = m[7]; val tz = m[11]
        r[3] = -(r[0] * tx + r[1] * ty + r[2] * tz)
        r[7] = -(r[4] * tx + r[5] * ty + r[6] * tz)
        r[11] = -(r[8] * tx + r[9] * ty + r[10] * tz)
        return r
    }
}

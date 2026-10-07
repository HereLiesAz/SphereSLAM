package com.hereliesaz.sphereslam.reloc

import org.junit.Test

/**
 * The 3D-holder's structural invariants, exercised through the pure [TileFingerprint.validate] so
 * they run without native OpenCV (the full constructor needs a real `Mat`).
 */
class TileFingerprintTest {

    private fun identity16() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    @Test
    fun `accepts a 4x4 anchor and a null or parallel confidence`() {
        TileFingerprint.validate(pointCount = 3, confidence = null, anchorFromTile = identity16())
        TileFingerprint.validate(
            pointCount = 3,
            confidence = floatArrayOf(0.9f, 0.5f, 0.2f),
            anchorFromTile = identity16(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an anchor that is not 4x4`() {
        TileFingerprint.validate(pointCount = 0, confidence = null, anchorFromTile = FloatArray(12))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects descriptor rows not parallel to the points`() {
        TileFingerprint.validate(
            pointCount = 3,
            confidence = null,
            anchorFromTile = identity16(),
            descriptorCount = 2,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a confidence not parallel to the points`() {
        TileFingerprint.validate(
            pointCount = 3,
            confidence = floatArrayOf(0.9f, 0.5f),
            anchorFromTile = identity16(),
        )
    }
}

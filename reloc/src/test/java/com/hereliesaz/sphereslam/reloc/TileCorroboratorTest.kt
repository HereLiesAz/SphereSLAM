package com.hereliesaz.sphereslam.reloc

import org.junit.Test

class TileCorroboratorTest {

    @Test
    fun `parallel geometry arrays matching the fingerprint size are accepted`() {
        TileCandidate.validate(
            pointCount = 3,
            perPointParallaxDeg = floatArrayOf(3f, 4f, 5f),
            perPointReprojectionRmsPx = floatArrayOf(0.5f, 0.6f, 0.7f),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parallax array must match fingerprint size`() {
        TileCandidate.validate(
            pointCount = 3,
            perPointParallaxDeg = floatArrayOf(3f, 4f),
            perPointReprojectionRmsPx = floatArrayOf(0.5f, 0.6f, 0.7f),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reprojection array must match fingerprint size`() {
        TileCandidate.validate(
            pointCount = 3,
            perPointParallaxDeg = floatArrayOf(3f, 4f, 5f),
            perPointReprojectionRmsPx = floatArrayOf(0.5f, 0.6f),
        )
    }
}

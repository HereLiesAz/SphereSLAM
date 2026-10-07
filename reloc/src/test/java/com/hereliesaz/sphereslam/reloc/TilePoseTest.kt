package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TilePoseTest {

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** Row-major translation-only transform. */
    private fun translate(x: Float, y: Float, z: Float) = identity().also {
        it[3] = x; it[7] = y; it[11] = z
    }

    @Test
    fun `identity anchor leaves the camera-from-tile pose unchanged`() {
        val camFromTile = translate(0.1f, -0.2f, 0.3f)
        assertArrayEquals(camFromTile, TilePose.cameraFromMap(camFromTile, identity()), 1e-5f)
    }

    @Test
    fun `a translated tile shifts the pose by the inverse tile placement`() {
        // Camera at the tile origin, tile sitting at (2,0,0) in the map.
        val camFromMap = TilePose.cameraFromMap(identity(), translate(2f, 0f, 0f))
        // camera_from_map should carry translation -2 on X (a map point at x=2 maps to the camera origin).
        assertArrayEquals(translate(-2f, 0f, 0f), camFromMap, 1e-5f)
    }

    @Test
    fun `rigid inverse round-trips to identity`() {
        val m = translate(1f, 2f, 3f)
        val back = TilePose.multiplyRowMajor4(m, TilePose.rigidInverseRowMajor4(m))
        assertArrayEquals(identity(), back, 1e-5f)
    }
}

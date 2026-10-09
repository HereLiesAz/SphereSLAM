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

class TilePoseRotationTest {
    /** Row-major rotation of [deg] about +Z with translation (tx, ty, tz). */
    private fun rotZ(deg: Float, tx: Float = 0f, ty: Float = 0f, tz: Float = 0f): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = kotlin.math.cos(r).toFloat(); val s = kotlin.math.sin(r).toFloat()
        return floatArrayOf(
            c, -s, 0f, tx,
            s, c, 0f, ty,
            0f, 0f, 1f, tz,
            0f, 0f, 0f, 1f,
        )
    }

    private fun apply(m: FloatArray, p: FloatArray) = FloatArray(3) { row ->
        m[row * 4] * p[0] + m[row * 4 + 1] * p[1] + m[row * 4 + 2] * p[2] + m[row * 4 + 3]
    }

    @Test
    fun `a rotated and translated tile maps map points through the tile frame`() {
        val anchorFromTile = rotZ(90f, 2f, 1f, 0f)
        val cameraFromTile = rotZ(-30f, 0.1f, 0.2f, 1.5f)
        val camFromMap = TilePose.cameraFromMap(cameraFromTile, anchorFromTile)
        // A point given in tile coordinates must land at the same camera point either way.
        val pTile = floatArrayOf(0.3f, -0.4f, 0.5f)
        val pMap = apply(anchorFromTile, pTile)
        assertArrayEquals(apply(cameraFromTile, pTile), apply(camFromMap, pMap), 1e-5f)
    }

    @Test
    fun `rigid inverse of a rotation round-trips to identity`() {
        val m = rotZ(37f, 1f, -2f, 3f)
        val back = TilePose.multiplyRowMajor4(TilePose.rigidInverseRowMajor4(m), m)
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        assertArrayEquals(identity, back, 1e-5f)
    }
}

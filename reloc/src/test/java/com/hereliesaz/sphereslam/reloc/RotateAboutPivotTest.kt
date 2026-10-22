package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class RotateAboutPivotTest {

    /** World point of camera-space point [c] for column-major camera-from-world [v]: Rᵀ(c − t). */
    private fun worldOf(v: FloatArray, c: FloatArray): FloatArray {
        val d = floatArrayOf(c[0] - v[12], c[1] - v[13], c[2] - v[14])
        // R row r, col k is v[k*4 + r]; Rᵀ·d component i = Σ_r R[r][i]·d[r] = Σ_r v[i*4 + r]·d[r].
        return FloatArray(3) { i -> v[i * 4] * d[0] + v[i * 4 + 1] * d[1] + v[i * 4 + 2] * d[2] }
    }

    private fun yawDelta(deg: Double): FloatArray {
        val a = Math.toRadians(deg)
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
    }

    private val view = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0.1f, -0.05f, -0.3f, 1f)

    @Test
    fun `the pivot behind the lens stays put in the world`() {
        val pivot = 0.15f
        val after = RotationDeltaMath.rotateAboutPivot(view, yawDelta(12.0), pivot)
        val p = floatArrayOf(0f, 0f, pivot)
        assertArrayEquals(worldOf(view, p), worldOf(after, p), 1e-5f)
    }

    @Test
    fun `the lens moves on an arc of the pivot's radius`() {
        val pivot = 0.15f
        val after = RotationDeltaMath.rotateAboutPivot(view, yawDelta(12.0), pivot)
        val lensBefore = worldOf(view, floatArrayOf(0f, 0f, 0f))
        val lensAfter = worldOf(after, floatArrayOf(0f, 0f, 0f))
        val moved = sqrt((0..2).sumOf { ((lensAfter[it] - lensBefore[it]) * (lensAfter[it] - lensBefore[it])).toDouble() })
        // Chord of a 12° turn at radius 0.15: 2·0.15·sin(6°).
        assertEquals(2 * 0.15 * sin(Math.toRadians(6.0)), moved, 1e-5)
    }

    @Test
    fun `a zero pivot is a turn about the lens`() {
        val d = yawDelta(20.0)
        assertArrayEquals(RotationDeltaMath.rotateAboutCameraCentre(view, d), RotationDeltaMath.rotateAboutPivot(view, d, 0f), 0f)
    }
}

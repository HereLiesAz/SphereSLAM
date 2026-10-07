package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

private fun identityView() = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    0f, 0f, 0f, 1f,
)

class AttitudePosePredictorTest {

    private val identityQuat = floatArrayOf(0f, 0f, 0f, 1f)
    // 90° about Y: [0, sin45, 0, cos45].
    private val yaw90 = floatArrayOf(0f, sqrt(0.5f), 0f, sqrt(0.5f))

    @Test
    fun `predict is null until corrected`() {
        val p = AttitudePosePredictor()
        assertFalse(p.hasReference)
        assertNull(p.predict(identityQuat))
    }

    @Test
    fun `predicting at the reference attitude returns the reference pose unchanged`() {
        val p = AttitudePosePredictor()
        val view = identityView().also { it[12] = 0.3f } // some translation
        p.correct(view, identityQuat)
        assertTrue(p.hasReference)
        assertArrayEquals(view, p.predict(identityQuat), 1e-4f)
    }

    @Test
    fun `predicting at a rotated attitude changes the pose but keeps it rigid`() {
        val p = AttitudePosePredictor()
        p.correct(identityView(), identityQuat)
        val out = p.predict(yaw90)!!
        // It moved.
        assertFalse(identityView().contentEquals(out))
        // First rotation column stays unit length (still a rotation, not a skew/scale).
        val cx = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
        assertArrayEquals(floatArrayOf(1f), floatArrayOf(cx), 1e-4f)
    }

    @Test
    fun `reset clears the reference`() {
        val p = AttitudePosePredictor()
        p.correct(identityView(), identityQuat)
        p.reset()
        assertFalse(p.hasReference)
        assertNull(p.predict(identityQuat))
    }
}

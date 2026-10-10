package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.math.RotationMath
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RotationDeltaMathDelegationTest {

    @Test
    fun `rotationAboutZ delegates to the supported RotationMath`() {
        for (deg in intArrayOf(0, 90, 180, 270, 37, -90)) {
            assertArrayEquals(RotationMath.rotationAboutZ(deg), RotationDeltaMath.rotationAboutZ(deg), 0f)
        }
    }

    @Test
    fun `public rotateAboutCameraCentre matches the predictor's helper`() {
        val view = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0.2f, 0.1f, -2f, 1f)
        val delta = RotationDeltaMath.rotationAboutZ(25)
        assertArrayEquals(
            AttitudePosePredictor.rotateAboutCameraCentre(view, delta),
            RotationDeltaMath.rotateAboutCameraCentre(view, delta),
            0f,
        )
        // Equivalent to the original PoseMath.multiply formulation.
        val m = FloatArray(16)
        for (row in 0 until 3) for (col in 0 until 3) m[col * 4 + row] = delta[row * 3 + col]
        m[15] = 1f
        assertArrayEquals(PoseMath.multiply(m, view), RotationDeltaMath.rotateAboutCameraCentre(view, delta), 1e-6f)
    }
}

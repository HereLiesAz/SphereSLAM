package com.hereliesaz.sphereslam.attitude

import com.hereliesaz.sphereslam.math.RotationMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the device→camera rotation convention for every `Surface.ROTATION_*`. The remap model is
 * written from AOSP's `remapCoordinateSystem` rule, not from [RotationMath] (ported from
 * GraffitiXR's OverlayGyroCompensationMathTest).
 */
class DeviceCameraRotationTest {

    /**
     * Independent model of `SensorManager.remapCoordinateSystem(inR, X, Y, outR)`: X names the new
     * axis (and sign) device x lands on, Y the same for device y, device z completes the frame.
     * Returned as row-major `v_new = M · v_device`.
     */
    private fun remapLikeSensorManager(axisX: Int, axisY: Int): FloatArray {
        val m = FloatArray(9)
        fun place(axis: Int, deviceCol: Int) {
            val sign = if ((axis and 0x80) != 0) -1f else 1f
            val newRow = (axis and 0x3) - 1
            m[newRow * 3 + deviceCol] = sign
        }
        place(axisX, 0)
        place(axisY, 1)
        val c0 = floatArrayOf(m[0], m[3], m[6])
        val c1 = floatArrayOf(m[1], m[4], m[7])
        m[2] = c0[1] * c1[2] - c0[2] * c1[1]
        m[5] = c0[2] * c1[0] - c0[0] * c1[2]
        m[8] = c0[0] * c1[1] - c0[1] * c1[0]
        return m
    }

    private val axisX = 1
    private val axisY = 2
    private val minusX = 0x81
    private val minusY = 0x82

    @Test
    fun `ROTATION_0 is the identity`() {
        assertArrayEquals(remapLikeSensorManager(axisX, axisY), DeviceCameraRotation.bodyToDisplay(0), 0f)
        assertArrayEquals(RotationMath.IDENTITY_3X3, DeviceCameraRotation.bodyToDisplay(0), 0f)
    }

    @Test
    fun `ROTATION_90 matches remapCoordinateSystem(AXIS_Y, AXIS_MINUS_X)`() {
        val m = DeviceCameraRotation.bodyToDisplay(DeviceCameraRotation.surfaceRotationToDegrees(1))
        assertArrayEquals(remapLikeSensorManager(axisY, minusX), m, 0f)
        // Screen x = -body y, screen y = body x.
        assertArrayEquals(floatArrayOf(-2f, 1f, 3f), RotationMath.multiplyMat3Vec3(m, floatArrayOf(1f, 2f, 3f)), 0f)
    }

    @Test
    fun `ROTATION_180 matches remapCoordinateSystem(AXIS_MINUS_X, AXIS_MINUS_Y)`() {
        assertArrayEquals(
            remapLikeSensorManager(minusX, minusY),
            DeviceCameraRotation.bodyToDisplay(DeviceCameraRotation.surfaceRotationToDegrees(2)),
            0f,
        )
    }

    @Test
    fun `ROTATION_270 matches remapCoordinateSystem(AXIS_MINUS_Y, AXIS_X)`() {
        assertArrayEquals(
            remapLikeSensorManager(minusY, axisX),
            DeviceCameraRotation.bodyToDisplay(DeviceCameraRotation.surfaceRotationToDegrees(3)),
            0f,
        )
    }

    @Test
    fun `display-upright image via CameraX rotationDegrees equals bodyToDisplay for every rotation`() {
        for (s in intArrayOf(0, 90, 180, 270)) for (d in intArrayOf(0, 90, 180, 270)) {
            val k = DeviceCameraRotation.rearImageRotationDegrees(s, d)
            assertArrayEquals(
                "sensor $s display $d",
                DeviceCameraRotation.bodyToDisplay(d),
                DeviceCameraRotation.cameraFromDevice(s, imageRotationAppliedDeg = k),
                0f,
            )
        }
    }

    @Test
    fun `raw sensor buffer frame depends only on sensor orientation`() {
        // A sensorOrientation-90 rear camera: the raw buffer must turn 90 CW to be upright, so the
        // buffer's top edge is the device's right edge: camera +y = body +x, camera +x = body -y.
        val m = DeviceCameraRotation.cameraFromDevice(90)
        assertArrayEquals(floatArrayOf(-2f, 1f, 3f), RotationMath.multiplyMat3Vec3(m, floatArrayOf(1f, 2f, 3f)), 0f)
    }

    @Test
    fun `passing rotationDegrees as the device-to-camera angle is only right at ROTATION_0`() {
        val s = 90
        assertArrayEquals(
            DeviceCameraRotation.cameraFromDevice(s),
            RotationMath.rotationAboutZ(DeviceCameraRotation.rearImageRotationDegrees(s, 0)),
            0f,
        )
        val k90 = DeviceCameraRotation.rearImageRotationDegrees(s, 90) // 0
        assertEquals(0, k90)
        // At ROTATION_90 the display-upright frame is R_z(90), not R_z(k) = identity.
        assertArrayEquals(RotationMath.rotationAboutZ(90), DeviceCameraRotation.cameraFromDevice(s, k90), 0f)
    }

    @Test
    fun `toOpenCv flips the camera y and z rows so a roll changes sign`() {
        val gl = RotationMath.rotationAboutZ(90)
        val cv = DeviceCameraRotation.toOpenCv(gl)
        // D·R_z(θ)·D = R_z(-θ): the CV-frame rotation conjugated back is R_z(-90) seen from CV axes.
        val d = floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f)
        assertArrayEquals(RotationMath.rotationAboutZ(-90), RotationMath.multiplyMat3(cv, d), 1e-6f)
    }

    @Test
    fun `non quarter-turn angles and bad surface rotations are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { DeviceCameraRotation.bodyToDisplay(45) }
        assertThrows(IllegalArgumentException::class.java) { DeviceCameraRotation.surfaceRotationToDegrees(4) }
    }
}

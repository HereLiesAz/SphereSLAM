package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.attitude.AttitudeRotationBridge
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.attitude.DeviceCameraRotation
import com.hereliesaz.sphereslam.attitude.DisplayPosePredictor
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.math.RotationMath
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The display-rate predictor applies exactly the rotation `PhotosphereTrackingSession`'s IMU bridge does. */
class DisplayPosePredictorWiringTest {

    private fun about(x: Float, y: Float, z: Float, deg: Double): FloatArray {
        val h = Math.toRadians(deg / 2.0)
        return RotationMath.normalize(
            floatArrayOf((x * sin(h)).toFloat(), (y * sin(h)).toFloat(), (z * sin(h)).toFloat(), cos(h).toFloat()),
        )
    }

    @Test
    fun `predictor matches TrackedPose bridged for the same attitude change`() {
        val view = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0.8f, 0.6f, 0f, 0f, -0.6f, 0.8f, 0f, 0.3f, 0.1f, -2f, 1f)
        val intrinsics = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)
        val pose = PhotosphereTrackingSession.TrackedPose(
            view, FloatArray(16), intrinsics, 0L, 0, 1f, 40, 0.01f, null, 50f,
            PhotosphereTrackingSession.PoseSource.KPM,
        )
        val c = DeviceCameraRotation.cameraFromDevice(90)
        val frameAttitude = AttitudeSample(about(0f, 1f, 0f, 5.0), 0L)
        val now = about(0.3f, 0.9f, 0.3f, 25.0)
        // What the session does on a visual miss.
        val bridge = AttitudeRotationBridge(c).also { it.markReference(frameAttitude) }
        val bridged = pose.bridged(bridge.cameraRotationDelta(now)!!, FloatArray(16), intrinsics, 1L)

        val predictor = DisplayPosePredictor().apply { update(pose.cameraFromMap, frameAttitude, c, pose.timestampNs) }
        assertArrayEquals(bridged.cameraFromMap, predictor.predict(now), 1e-5f)
    }
}

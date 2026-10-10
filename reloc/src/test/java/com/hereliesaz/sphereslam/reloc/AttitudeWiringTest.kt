package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.attitude.AttitudeRotationBridge
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.attitude.DeviceCameraRotation
import com.hereliesaz.sphereslam.math.RigidMath
import com.hereliesaz.sphereslam.math.RotationMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The `:sphereslam` attitude types plug into the `:reloc` predictor and tracking loop. */
class AttitudeWiringTest {

    private fun yaw(deg: Float): FloatArray {
        val h = Math.toRadians(deg.toDouble() / 2.0)
        return floatArrayOf(0f, sin(h).toFloat(), 0f, cos(h).toFloat())
    }

    private val view = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0.2f, -0.1f, -1.5f, 1f)

    @Test
    fun `predictor and bridge agree for the same cameraFromDevice`() {
        val c = DeviceCameraRotation.cameraFromDevice(90)
        val predictor = AttitudePosePredictor(c)
        predictor.correct(view, AttitudeSample(yaw(0f), 0L))
        val bridge = AttitudeRotationBridge(c).also { it.markReference(yaw(0f)) }
        assertArrayEquals(bridge.advance(view, yaw(15f)), predictor.predict(AttitudeSample(yaw(15f), 1L)), 1e-6f)
    }

    @Test
    fun `RobustTrackingLoop renders the attitude-bridged pose on a miss`() {
        val bridge = AttitudeRotationBridge()
        var sample = AttitudeSample(yaw(0f), 0L)
        val loop = RobustTrackingLoop(
            stateMachine = TrackingStateMachine(TrackingStateConfig(confirmationFrames = 2, lostAfterMs = 1000L)),
            bridgeRotatedPose = bridge.bridgeFunction { sample },
        )
        fun good(nowMs: Long) = loop.onFrame(
            viewMatrix = view, inlierCount = 30, reprojectionError = 1f,
            frameTimestampNs = 0L, nowElapsedRealtimeNs = 0L,
            timestampSource = CameraTimestampSource.UNKNOWN, nowMs = nowMs,
        )
        good(0L); good(16L)
        bridge.markReference(sample)
        sample = AttitudeSample(yaw(20f), 1L)
        val out = loop.onFrame(
            viewMatrix = null, inlierCount = 0, reprojectionError = 0f,
            frameTimestampNs = 0L, nowElapsedRealtimeNs = 0L,
            timestampSource = CameraTimestampSource.REALTIME, nowMs = 32L, bridgeAvailable = true,
        )
        assertEquals(TrackingState.IMU_BRIDGE, out.state)
        val expected = RotationMath.rotateAboutCameraCentre(view, RotationMath.cameraRotationDelta(yaw(0f), yaw(20f)))
        assertArrayEquals(expected, out.renderPose, 1e-5f)
        assertArrayEquals(RigidMath.cameraCentre(view), RigidMath.cameraCentre(out.renderPose!!), 1e-5f)
    }
}

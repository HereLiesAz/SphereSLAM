package com.hereliesaz.sphereslam

import android.content.Context
import android.content.ContextWrapper
import android.hardware.SensorManager
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.attitude.GameRotationAttitudeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Context the sensor sources hold, and accuracy stamping (no Android runtime needed). */
class CameraAttitudeProviderContextTest {

    private class RecordingContext(private val app: Context?) : ContextWrapper(null) {
        val asked = mutableListOf<String>()
        override fun getApplicationContext(): Context? = app
        override fun getSystemService(name: String): Any? {
            asked += name
            return null
        }
    }

    @Test
    fun `sensor sources use the application context, never the activity`() {
        val app = RecordingContext(null)
        val activity = RecordingContext(app)
        CameraAttitudeProvider(activity)
        GameRotationAttitudeSource(activity)
        assertTrue(activity.asked.isEmpty())
        assertEquals(listOf(Context.SENSOR_SERVICE, Context.SENSOR_SERVICE), app.asked)
        // A context without an application context is used directly.
        val bare = RecordingContext(null)
        CameraAttitudeProvider(bare)
        assertEquals(listOf(Context.SENSOR_SERVICE), bare.asked)
    }

    @Test
    fun `reported accuracy is stamped on later samples`() {
        val provider = CameraAttitudeProvider(RecordingContext(null))
        provider.onRotationVector(floatArrayOf(0f, 0f, 0f, 1f), 10L)
        assertEquals(AttitudeSample.ACCURACY_UNKNOWN, provider.latestSample()!!.accuracy)
        provider.onRotationAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        provider.onRotationVector(floatArrayOf(0f, 0f, 0f, 1f), 20L)
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_HIGH, provider.latestSample()!!.accuracy)
        assertTrue(provider.isReliable)
        provider.onRotationAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_LOW)
        assertFalse(provider.isReliable)
    }
}

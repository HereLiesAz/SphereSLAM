package com.hereliesaz.sphereslam.attitude

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.hereliesaz.sphereslam.math.RotationMath

/**
 * Quaternion device attitude from Android's `TYPE_GAME_ROTATION_VECTOR` — the platform's own
 * gyro + accelerometer fusion (no magnetometer), for short rotation bridges and attitude-based pose
 * prediction.
 *
 * Why this sensor and not raw `TYPE_GYROSCOPE`: integrating angular velocity by hand duplicates,
 * with more risk and no per-device tuning, exactly what this sensor already is. Why not
 * `TYPE_ROTATION_VECTOR`: the magnetometer makes yaw jump near steel and rebar; a bridge needs smooth
 * *relative* rotation, not north. (For compass heading use
 * [com.hereliesaz.sphereslam.CameraAttitudeProvider].)
 *
 * Samples are in the device **body** frame (x right, y up, z out of the screen, in the device's
 * natural orientation). Convert a body-frame delta into a camera frame with
 * [DeviceCameraRotation.cameraFromDevice] — see that object for which angle to pass.
 *
 * Fails soft: no sensor ⇒ [isAvailable] false and [latestSample] null. Reads are whole-value swaps,
 * safe from any thread; [start]/[stop] are safe from any thread.
 *
 * Typical use with `:reloc`:
 * ```
 * val attitude = GameRotationAttitudeSource(context).apply { start() }
 * val bridge = AttitudeRotationBridge(DeviceCameraRotation.cameraFromDevice(sensorOrientation, imageRotationApplied))
 * val loop = RobustTrackingLoop(bridgeRotatedPose = bridge.bridgeFunction { attitude.latestSample() })
 * // after each accepted (rendered) frame: bridge.markReference(attitude.latestSample())
 * ```
 */
class GameRotationAttitudeSource(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val buffer = AttitudeSampleBuffer()
    private val lock = Any()
    @Volatile private var registered = false

    /** True when the device has a game-rotation-vector sensor. */
    val isAvailable: Boolean get() = sensor != null

    /** True between a successful [start] and [stop]. */
    val isRunning: Boolean get() = registered

    /** The latest sample, or null before the first sample, after [stop], or without the sensor. */
    fun latestSample(): AttitudeSample? = buffer.latest()

    /** The latest reported `SensorManager.SENSOR_STATUS_*` accuracy, or [AttitudeSample.ACCURACY_UNKNOWN]. */
    val accuracy: Int get() = buffer.accuracy

    /**
     * Begin sampling at [samplingPeriodUs] (default `SENSOR_DELAY_GAME`, ~20 ms: a bridge reads it
     * every camera frame). Idempotent. Discards any previous sample.
     *
     * @return true when the listener is registered.
     */
    fun start(samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME): Boolean {
        val sm = sensorManager ?: return false
        val s = sensor ?: return false
        synchronized(lock) {
            if (registered) return true
            buffer.clear()
            registered = sm.registerListener(this, s, samplingPeriodUs)
            return registered
        }
    }

    /** Stop sampling. Idempotent. Clears the last sample so a later [start] never reports stale data. */
    fun stop() {
        synchronized(lock) {
            if (!registered) return
            sensorManager?.unregisterListener(this)
            registered = false
            buffer.clear()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
        buffer.ingestRotationVector(event.values, event.timestamp)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_GAME_ROTATION_VECTOR) buffer.accuracy = accuracy
    }
}

/**
 * The thread-safe, Android-free state behind [GameRotationAttitudeSource] — the unit-testable seam
 * (a `SensorEvent` cannot be built in a JVM test).
 */
internal class AttitudeSampleBuffer {
    @Volatile private var sample: AttitudeSample? = null
    @Volatile var accuracy: Int = AttitudeSample.ACCURACY_UNKNOWN

    fun latest(): AttitudeSample? = sample

    fun clear() {
        sample = null
        accuracy = AttitudeSample.ACCURACY_UNKNOWN
    }

    /**
     * Ingest raw rotation-vector `values` (`[x, y, z]` or `[x, y, z, w, ...]`). A 3-element vector
     * (pre-API-18 layout) recovers `w = sqrt(1 − x² − y² − z²)`. Non-finite input is dropped.
     */
    fun ingestRotationVector(values: FloatArray, timestampNs: Long) {
        if (values.size < 3) return
        val x = values[0]; val y = values[1]; val z = values[2]
        val w = if (values.size >= 4) {
            values[3]
        } else {
            val t = 1f - x * x - y * y - z * z
            if (t > 0f) kotlin.math.sqrt(t) else 0f
        }
        val raw = floatArrayOf(x, y, z, w)
        if (raw.any { !it.isFinite() }) return
        sample = AttitudeSample(RotationMath.normalize(raw), timestampNs, accuracy)
    }
}

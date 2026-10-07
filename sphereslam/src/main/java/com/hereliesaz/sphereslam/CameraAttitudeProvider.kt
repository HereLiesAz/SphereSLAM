package com.hereliesaz.sphereslam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Absolute attitude of the rear camera's optical axis — compass heading and elevation — for the
 * guided-sweep coverage ([SphereCoverage]). Both coordinates come from one magnetometer-fused
 * `TYPE_ROTATION_VECTOR` sample, so they are world-anchored (north + gravity) and do not drift over a
 * long pivot the way a bare gyro would.
 *
 * "Heading" is where the **camera looks** (device −Z projected to the horizontal, 0 = north,
 * clockwise), and "elevation" is that axis's angle above (+) / below (−) the horizon — exactly the
 * pair [SphereCoverage.observe] consumes.
 *
 * Fails soft: no rotation-vector sensor ⇒ [isAvailable] false and the latest reads null, and a host
 * can fall back to unanchored (first-observation) coverage. All reads are whole-value swaps, safe
 * from a camera worker thread.
 *
 * Lifecycle: call [start] when sweeping begins and [stop] when it ends (or the screen turns off) to
 * release the sensor. Example:
 * ```
 * val attitude = CameraAttitudeProvider(context).apply { start() }
 * // per keyframe:
 * val h = attitude.latestHeadingDegrees() ?: return
 * val e = attitude.latestElevationDegrees() ?: 0f
 * coverage.observe(h, e)
 * ```
 *
 * @param context any Context; used only to obtain the system [SensorManager] (no reference retained
 *   beyond the sensor registration).
 */
class CameraAttitudeProvider(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** True when this device has a magnetometer-fused rotation-vector sensor. */
    val isAvailable: Boolean get() = rotationSensor != null

    @Volatile private var latestHeadingDeg: Float? = null
    @Volatile private var latestElevationDeg: Float? = null
    @Volatile private var reliable: Boolean = true
    private var registered = false

    /**
     * @return latest camera-axis compass heading in degrees `[0, 360)` (0 = north, clockwise), or null
     *   before the first sample, after [stop], or on a device without the sensor.
     */
    fun latestHeadingDegrees(): Float? = latestHeadingDeg

    /**
     * @return latest camera-axis elevation in degrees `[-90, 90]` (above the horizon positive), or
     *   null in the same cases as [latestHeadingDegrees].
     */
    fun latestElevationDegrees(): Float? = latestElevationDeg

    /** False once the system reports the magnetometer unreliable (needs a figure-8 recalibration). */
    val isReliable: Boolean get() = reliable

    /** Begin sampling. Safe to call repeatedly. UI rate suffices for keyframe-cadence coverage. */
    fun start() {
        val sm = sensorManager ?: return
        val sensor = rotationSensor ?: return
        if (registered) return
        registered = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    /** Stop sampling. Idempotent. Clears the last sample so a later [start] never reports stale data. */
    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
        latestHeadingDeg = null
        latestElevationDeg = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val r = FloatArray(9)
        // getRotationMatrixFromVector throws on a malformed vector; a size-5 vector (with an extra
        // estimated-heading-accuracy element) must be truncated to 4 first.
        val v = if (event.values.size > 4) event.values.copyOf(4) else event.values
        try {
            SensorManager.getRotationMatrixFromVector(r, v)
        } catch (_: IllegalArgumentException) {
            return
        }
        cameraAxisHeadingElevation(r)?.let { (h, e) ->
            latestHeadingDeg = h
            latestElevationDeg = e
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        }
    }

    companion object {
        /**
         * Camera-axis (heading, elevation) in degrees from a device→world East-North-Up rotation
         * matrix [r] (row-major 3×3), or null when the axis is too near vertical for a meaningful
         * heading. The rear camera looks along device −Z, whose world vector is the negated third
         * column `(-r[2], -r[5], -r[8])` = (east, north, up); heading is `atan2(east, north)`,
         * elevation is `asin(up)`. Pure math — the unit-testable seam, since `SensorEvent` and
         * `getRotationMatrixFromVector` need the Android runtime.
         *
         * @param r device→world East-North-Up rotation matrix, row-major 3×3 (length ≥ 9).
         * @return `(headingDeg in [0,360), elevationDeg in [-90,90])`, or null when [r] is too short or
         *   the camera axis is within ~0.006° of vertical (heading undefined there).
         */
        internal fun cameraAxisHeadingElevation(r: FloatArray): Pair<Float, Float>? {
            if (r.size < 9) return null
            val east = -r[2]
            val north = -r[5]
            val up = (-r[8]).coerceIn(-1f, 1f)
            if (east * east + north * north < 1e-8f) return null // near-vertical: heading undefined
            var heading = Math.toDegrees(kotlin.math.atan2(east.toDouble(), north.toDouble())).toFloat()
            heading = ((heading % 360f) + 360f) % 360f
            val elevation = Math.toDegrees(kotlin.math.asin(up.toDouble())).toFloat()
            return heading to elevation
        }
    }
}

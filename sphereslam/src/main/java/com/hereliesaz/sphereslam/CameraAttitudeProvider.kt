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
    @Volatile private var latestRollDeg: Float? = null
    @Volatile private var latestDeviceToWorld: FloatArray? = null
    @Volatile private var reliable: Boolean = true
    // Guarded by `lock`; volatile so unsynchronized readers see the latest registration state.
    @Volatile private var registered = false
    private val lock = Any()

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

    /**
     * @return latest roll of the device's +x axis (image-right for a rear camera solved in the
     *   natural-orientation frame) above the horizon, degrees `(-180, 180]`, or null in the same
     *   cases as [latestHeadingDegrees]. For another image frame use [cameraRollDegrees] with
     *   [latestDeviceToWorldMatrix] and its `cameraFromDevice` rotation.
     */
    fun latestRollDegrees(): Float? = latestRollDeg

    /** Latest device→world East-North-Up rotation (row-major 3×3, a fresh copy), or null. */
    fun latestDeviceToWorldMatrix(): FloatArray? = latestDeviceToWorld?.copyOf()

    /** False once the system reports the magnetometer unreliable (needs a figure-8 recalibration). */
    val isReliable: Boolean get() = reliable

    /**
     * Begin sampling. Safe to call repeatedly and from any thread. UI rate suffices for
     * keyframe-cadence coverage. A fresh start resets [isReliable] to true (the system re-reports
     * accuracy on registration) and discards any previous sample.
     */
    fun start() {
        val sm = sensorManager ?: return
        val sensor = rotationSensor ?: return
        synchronized(lock) {
            if (registered) return
            reliable = true
            latestHeadingDeg = null
            latestElevationDeg = null
            latestRollDeg = null
            latestDeviceToWorld = null
            registered = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    /**
     * Stop sampling. Idempotent and safe from any thread. Clears the last sample so a later [start]
     * never reports stale data.
     */
    fun stop() {
        synchronized(lock) {
            if (!registered) return
            sensorManager?.unregisterListener(this)
            registered = false
            latestHeadingDeg = null
            latestElevationDeg = null
            latestRollDeg = null
            latestDeviceToWorld = null
        }
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
            latestRollDeg = cameraRollDegrees(r)
            latestDeviceToWorld = r
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

        /**
         * Roll of the camera image's right axis above the horizon, degrees `(-180, 180]` — positive
         * when the right edge of the image points above the horizon (device turned counter-clockwise
         * as the user sees the screen). Pairs with [cameraAxisHeadingElevation] and
         * [PhotosphereMap.directionOfPixel].
         *
         * @param r device→world East-North-Up rotation matrix, row-major 3×3.
         * @param cameraFromDevice row-major 3×3 from the device body frame to the image's GL camera
         *   frame (`com.hereliesaz.sphereslam.attitude.DeviceCameraRotation`); identity = device +x is
         *   image-right.
         * @return the roll, or null when [r] is malformed or the camera axis is near vertical.
         */
        fun cameraRollDegrees(r: FloatArray, cameraFromDevice: FloatArray? = null): Float? {
            if (r.size < 9) return null
            val c = cameraFromDevice ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            if (c.size != 9) return null
            // Image right in device coordinates is the first row of cameraFromDevice.
            val dx = c[0]; val dy = c[1]; val dz = c[2]
            val right = floatArrayOf(
                r[0] * dx + r[1] * dy + r[2] * dz,
                r[3] * dx + r[4] * dy + r[5] * dz,
                r[6] * dx + r[7] * dy + r[8] * dz,
            )
            val f = floatArrayOf(-r[2], -r[5], -r[8]) // camera axis (device −Z) in world
            val hx = f[1]; val hy = -f[0] // forward × up, horizontal right
            val hn = kotlin.math.sqrt(hx * hx + hy * hy)
            if (hn < 1e-4f) return null
            val r0 = floatArrayOf(hx / hn, hy / hn, 0f)
            val u0 = floatArrayOf(
                r0[1] * f[2] - r0[2] * f[1],
                r0[2] * f[0] - r0[0] * f[2],
                r0[0] * f[1] - r0[1] * f[0],
            )
            val x = right[0] * r0[0] + right[1] * r0[1] + right[2] * r0[2]
            val y = right[0] * u0[0] + right[1] * u0[1] + right[2] * u0[2]
            return Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble())).toFloat()
        }
    }
}

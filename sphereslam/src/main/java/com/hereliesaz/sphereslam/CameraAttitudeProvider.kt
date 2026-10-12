package com.hereliesaz.sphereslam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.hereliesaz.sphereslam.attitude.AttitudeExtrapolator
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.attitude.AttitudeSampleBuffer

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
 * **Display-rate overlays.** Every sample is also pushed, with its sensor timestamp, into
 * [attitudeExtrapolator], so a render thread can ask for the camera rotation at its own frame time
 * (`attitudeExtrapolator.worldFromCameraAt(SystemClock.elapsedRealtimeNanos(), cameraFromDisplay, out)`)
 * — what `CoverageGlowRenderer.RotationSource` wants. Start with a faster rate for that
 * (`start(SensorManager.SENSOR_DELAY_GAME)` or `SENSOR_DELAY_FASTEST`); the default UI rate (~16 Hz)
 * is enough for keyframe stamping only.
 *
 * @param context any Context. Only its **application** context's [SensorManager] is held (a
 *   `SensorManager` obtained from an Activity references that Activity), so passing an Activity
 *   does not keep it alive; the provider itself stays registered with the sensor until [stop].
 */
class CameraAttitudeProvider(context: Context) : SensorEventListener {

    private val sensorManager = applicationSensorManager(context)
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** True when this device has a magnetometer-fused rotation-vector sensor. */
    val isAvailable: Boolean get() = rotationSensor != null

    /**
     * One sample's camera-axis heading, elevation and roll (degrees, as [latestHeadingDegrees],
     * [latestElevationDegrees], [latestRollDegrees]) and its device→world matrix, decoded together.
     * Near the zenith/nadir the angles are only meaningful as a set — heading alone is noise there
     * (see [cameraAxisHeadingElevation]) — so read them from one snapshot, not one getter each.
     */
    class Attitude internal constructor(
        val headingDeg: Float,
        val elevationDeg: Float,
        val rollDeg: Float,
        deviceToWorld: FloatArray,
    ) {
        private val matrix = deviceToWorld.copyOf()

        /** Device→world East-North-Up rotation (row-major 3×3, a fresh copy). */
        val deviceToWorld: FloatArray get() = matrix.copyOf()

        internal fun matrixRef(): FloatArray = matrix

        /** The pole-stable bearing the camera faces ([AttitudeBasis.facingAzimuthDeg]). */
        val facingAzimuthDeg: Float? get() = AttitudeBasis.facingAzimuthDeg(headingDeg, elevationDeg, rollDeg)
    }

    @Volatile private var latest: Attitude? = null
    private val samples = AttitudeSampleBuffer()

    /**
     * Timestamped history of this sensor's samples (`TYPE_ROTATION_VECTOR`, body→East-North-Up
     * quaternions), for per-frame rotation queries with interpolation / short extrapolation. Cleared
     * on [start] and [stop]. Thread-safe.
     */
    val attitudeExtrapolator: AttitudeExtrapolator = AttitudeExtrapolator()
    @Volatile private var reliable: Boolean = true
    // Guarded by `lock`; volatile so unsynchronized readers see the latest registration state.
    @Volatile private var registered = false
    private val lock = Any()

    /**
     * @return latest camera-axis compass heading in degrees `[0, 360)` (0 = north, clockwise), or null
     *   before the first sample, after [stop], or on a device without the sensor.
     */
    fun latestHeadingDegrees(): Float? = latest?.headingDeg

    /** The latest sample's heading, elevation, roll and matrix as one consistent snapshot, or null. */
    fun latestAttitude(): Attitude? = latest

    /**
     * @return latest camera-axis elevation in degrees `[-90, 90]` (above the horizon positive), or
     *   null in the same cases as [latestHeadingDegrees].
     */
    fun latestElevationDegrees(): Float? = latest?.elevationDeg

    /**
     * @return latest roll of the device's +x axis (image-right for a rear camera solved in the
     *   natural-orientation frame) above the horizon, degrees `(-180, 180]`, or null in the same
     *   cases as [latestHeadingDegrees]. For another image frame use [cameraRollDegrees] with
     *   [latestDeviceToWorldMatrix] and its `cameraFromDevice` rotation.
     */
    fun latestRollDegrees(): Float? = latest?.rollDeg

    /** Latest device→world East-North-Up rotation (row-major 3×3, a fresh copy), or null. */
    fun latestDeviceToWorldMatrix(): FloatArray? = latest?.deviceToWorld

    /**
     * Latest sample as a body→world (East-North-Up) quaternion with its `SensorEvent.timestamp`
     * (`elapsedRealtimeNanos` base) and the sensor's last reported accuracy, or null in the same cases as [latestHeadingDegrees]. Unlike the
     * heading, this is defined when looking straight up or down.
     */
    fun latestSample(): AttitudeSample? = samples.latest()

    /** False once the system reports the magnetometer unreliable (needs a figure-8 recalibration). */
    val isReliable: Boolean get() = reliable

    /**
     * Begin sampling at [samplingPeriodUs] (default `SENSOR_DELAY_UI`, ~16 Hz, enough for
     * keyframe-cadence coverage; use `SENSOR_DELAY_GAME` or faster to drive a display-rate overlay).
     * Safe to call repeatedly and from any thread; a call while already sampling keeps the current
     * rate. A fresh start resets [isReliable] to true (the system re-reports accuracy on
     * registration) and discards any previous sample.
     */
    @JvmOverloads
    fun start(samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_UI) {
        val sm = sensorManager ?: return
        val sensor = rotationSensor ?: return
        synchronized(lock) {
            if (registered) return
            reliable = true
            latest = null
            samples.clear()
            attitudeExtrapolator.clear()
            registered = sm.registerListener(this, sensor, samplingPeriodUs)
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
            latest = null
            samples.clear()
            attitudeExtrapolator.clear()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        onRotationVector(event.values, event.timestamp)
    }

    /** [onSensorChanged] body for one `TYPE_ROTATION_VECTOR` sample (the JVM-testable seam). */
    internal fun onRotationVector(values: FloatArray, timestampNs: Long) {
        val r = FloatArray(9)
        // getRotationMatrixFromVector throws on a malformed vector; a size-5 vector (with an extra
        // estimated-heading-accuracy element) must be truncated to 4 first.
        val v = if (values.size > 4) values.copyOf(4) else values
        try {
            SensorManager.getRotationMatrixFromVector(r, v)
        } catch (_: IllegalArgumentException) {
            return
        }
        samples.ingestRotationVector(values, timestampNs)
        samples.latest()?.let { attitudeExtrapolator.push(it) }
        val (h, e) = cameraAxisHeadingElevation(r) ?: return
        val roll = cameraRollDegrees(r) ?: return
        latest = Attitude(h, e, roll, r)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) onRotationAccuracy(accuracy)
    }

    /**
     * Record a reported `SENSOR_STATUS_*` accuracy: drives [isReliable] and is stamped on every later
     * [latestSample] (`AttitudeSample.accuracy`).
     */
    internal fun onRotationAccuracy(accuracy: Int) {
        reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        samples.accuracy = accuracy
    }

    companion object {
        /**
         * The [SensorManager] of [context]'s application context (falling back to [context] itself
         * when it has none), so a long-lived listener never pins an Activity.
         */
        internal fun applicationSensorManager(context: Context): SensorManager? =
            (context.applicationContext ?: context).getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        /**
         * Camera-axis (heading, elevation) in degrees from a device→world East-North-Up rotation
         * matrix [r] (row-major 3×3). The rear camera looks along device −Z, whose world vector is the
         * negated third column `(-r[2], -r[5], -r[8])` = (east, north, up); heading is
         * `atan2(east, north)`, elevation is `asin(up)`. Pure math — the unit-testable seam, since
         * `SensorEvent` and `getRotationMatrixFromVector` need the Android runtime.
         *
         * Defined at every attitude. Within ~0.006° of vertical, where the axis has no horizontal
         * part, heading is the bearing the device top points toward when looking down (away from it
         * when looking up) — the limit the axis heading approaches from there. Near the poles heading
         * is noise-dominated on its own; [cameraRollDegrees] is measured from the same heading, so the
         * (heading, elevation, roll) triple of one matrix stays exact (see [AttitudeBasis]).
         *
         * @param r device→world East-North-Up rotation matrix, row-major 3×3 (length ≥ 9).
         * @return `(headingDeg in [0,360), elevationDeg in [-90,90])`, or null when [r] is too short.
         */
        internal fun cameraAxisHeadingElevation(r: FloatArray): Pair<Float, Float>? {
            if (r.size < 9) return null
            val up = (-r[8]).coerceIn(-1f, 1f)
            var east = -r[2]
            var north = -r[5]
            if (east * east + north * north < 1e-8f) {
                // Straight down/up: the device +Y (top edge) world vector is horizontal here.
                val sign = if (up < 0f) 1f else -1f
                east = sign * r[1]
                north = sign * r[4]
            }
            var heading = Math.toDegrees(kotlin.math.atan2(east.toDouble(), north.toDouble())).toFloat()
            heading = ((heading % 360f) + 360f) % 360f
            val elevation = Math.toDegrees(kotlin.math.asin(up.toDouble())).toFloat()
            return heading to elevation
        }

        /**
         * Roll of the camera image's right axis above the horizon, degrees `(-180, 180]` — positive
         * when the right edge of the image points above the horizon (device turned counter-clockwise
         * as the user sees the screen). Measured from the horizontal right `(cos h, −sin h, 0)` of
         * [cameraAxisHeadingElevation]'s heading, so the triple rebuilds [r] exactly at every
         * attitude ([AttitudeBasis.basis], [PhotosphereMap.directionOfPixel]).
         *
         * @param r device→world East-North-Up rotation matrix, row-major 3×3.
         * @param cameraFromDevice row-major 3×3 from the device body frame to the image's GL camera
         *   frame (`com.hereliesaz.sphereslam.attitude.DeviceCameraRotation`); identity = device +x is
         *   image-right.
         * @return the roll, or null when [r] or [cameraFromDevice] is malformed.
         */
        fun cameraRollDegrees(r: FloatArray, cameraFromDevice: FloatArray? = null): Float? {
            if (r.size < 9) return null
            val c = cameraFromDevice ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            if (c.size != 9) return null
            val (headingDeg, _) = cameraAxisHeadingElevation(r) ?: return null
            // Image right in device coordinates is the first row of cameraFromDevice.
            val dx = c[0]; val dy = c[1]; val dz = c[2]
            val right = floatArrayOf(
                r[0] * dx + r[1] * dy + r[2] * dz,
                r[3] * dx + r[4] * dy + r[5] * dz,
                r[6] * dx + r[7] * dy + r[8] * dz,
            )
            val f = floatArrayOf(-r[2], -r[5], -r[8]) // camera axis (device −Z) in world
            val h = Math.toRadians(headingDeg.toDouble())
            val r0 = floatArrayOf(kotlin.math.cos(h).toFloat(), (-kotlin.math.sin(h)).toFloat(), 0f)
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

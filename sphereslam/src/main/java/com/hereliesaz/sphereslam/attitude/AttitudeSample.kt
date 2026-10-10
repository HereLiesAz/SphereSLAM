package com.hereliesaz.sphereslam.attitude

/**
 * One device-attitude sample: a unit quaternion `[x, y, z, w]` giving the device body frame's
 * orientation in a fixed world frame (Android's rotation-vector convention), with its sensor
 * timestamp and accuracy.
 *
 * For `TYPE_GAME_ROTATION_VECTOR` the world frame's yaw origin is arbitrary (no magnetometer), so
 * only **relative** attitude between two samples is meaningful — exactly what a rotation bridge or
 * [com.hereliesaz.sphereslam.math.RotationMath.cameraRotationDelta] needs.
 *
 * Immutable: [quaternion] returns a fresh copy.
 *
 * @property timestampNs the sensor event timestamp, nanoseconds (`SensorEvent.timestamp`; on
 *   current Android this is the `SystemClock.elapsedRealtimeNanos()` time base, the same base as
 *   CameraX / Camera2 `SENSOR_TIMESTAMP` with `REALTIME` timestamp source).
 * @property accuracy the latest `SensorManager.SENSOR_STATUS_*` accuracy reported for the sensor, or
 *   [ACCURACY_UNKNOWN] when none has been reported.
 */
class AttitudeSample(
    quaternion: FloatArray,
    val timestampNs: Long,
    val accuracy: Int = ACCURACY_UNKNOWN,
) {
    private val q: FloatArray = quaternion.copyOf()

    init {
        require(quaternion.size == 4) { "quaternion must be [x, y, z, w]" }
        require(quaternion.all { it.isFinite() }) { "quaternion must be finite" }
    }

    /** The unit quaternion `[x, y, z, w]` (a fresh copy). */
    val quaternion: FloatArray get() = q.copyOf()

    /** Nanoseconds between this sample and [nowNs] (same time base as [timestampNs]). */
    fun ageNs(nowNs: Long): Long = nowNs - timestampNs

    override fun toString(): String =
        "AttitudeSample(q=${q.contentToString()}, timestampNs=$timestampNs, accuracy=$accuracy)"

    companion object {
        /** No accuracy has been reported for the sensor yet. */
        const val ACCURACY_UNKNOWN: Int = -1
    }
}

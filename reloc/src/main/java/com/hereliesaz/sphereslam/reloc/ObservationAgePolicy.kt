package com.hereliesaz.sphereslam.reloc

/**
 * Source of a camera frame's timestamp. A frame age is only trustworthy when the camera stamps its
 * frames on the same clock the host compares against ([REALTIME]); otherwise the age is unknown and
 * must not be guessed.
 */
enum class CameraTimestampSource {
    REALTIME,
    UNKNOWN,
}

/**
 * @property maxRealtimeAgeMs frames older than this (ms) are stale and should be dropped (`> 0`).
 */
data class ObservationAgeConfig(
    val maxRealtimeAgeMs: Float = 250f,
) {
    init {
        require(maxRealtimeAgeMs.isFinite() && maxRealtimeAgeMs > 0f)
    }
}

/**
 * Result of [ObservationAgePolicy.evaluate]: [ageMs] is null when the age can't be trusted (non-realtime
 * timestamp source or an out-of-range timestamp), in which case [stale] is false (never guessed).
 */
data class ObservationAge(
    val ageMs: Float?,
    val stale: Boolean,
)

/**
 * Rejects relocalization poses solved on a stale frame — a pose for a frame that is already hundreds
 * of milliseconds old would snap a fast-moving overlay backward.
 *
 * Absolute age is only safe to compute when the camera declares a realtime timestamp source matching
 * the host's reference clock; [CameraTimestampSource.UNKNOWN] is intentionally not offset-calibrated
 * from callback arrival time.
 */
class ObservationAgePolicy(
    private val config: ObservationAgeConfig = ObservationAgeConfig(),
) {
    /**
     * @param frameTimestampNs the frame's timestamp, nanoseconds, on the realtime clock.
     * @param nowElapsedRealtimeNs the current time on the same clock, nanoseconds.
     * @param source the camera's timestamp source.
     * @return the age verdict; [ObservationAge.stale] is true only for a trusted age over the limit.
     */
    fun evaluate(
        frameTimestampNs: Long,
        nowElapsedRealtimeNs: Long,
        source: CameraTimestampSource,
    ): ObservationAge {
        if (
            source != CameraTimestampSource.REALTIME ||
            frameTimestampNs <= 0L ||
            nowElapsedRealtimeNs < frameTimestampNs
        ) {
            return ObservationAge(ageMs = null, stale = false)
        }
        val ageMs = (nowElapsedRealtimeNs - frameTimestampNs).toFloat() / 1_000_000f
        return ObservationAge(ageMs = ageMs, stale = ageMs > config.maxRealtimeAgeMs)
    }
}

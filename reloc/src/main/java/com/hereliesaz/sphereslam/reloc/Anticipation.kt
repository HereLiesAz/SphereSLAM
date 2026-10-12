package com.hereliesaz.sphereslam.reloc

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Anticipation resolution: how finely SphereSLAM anticipates the target's appearance with previews
 * (`ReferencePreviews`). Picture a half-sphere over the target, the target at its core and the
 * square-on view at its pole. [latitude] lines (meridians) run from the pole to the equator, cut by
 * [longitude] circles between them; a preview is taken at the pole and at every cut, aimed at the
 * core (none on the equator, where the target is edge-on). [distance] nests that many half-spheres,
 * from [KpmMatchSpeedConfig.anticipationNearestWidths] to
 * [KpmMatchSpeedConfig.anticipationFarthestWidths] target widths, so near and far views are
 * anticipated too. [roll] takes each of those views turned about its optical axis that many times,
 * evenly from upright (2: upright and upside down; 4: every quarter turn); the sensors already turn
 * the frame upright before a search ([KpmMatchSpeedConfig.previewRectify]), so 1 usually serves.
 * KPM matches up to about 30° of viewing angle, so circles 30° apart (longitude 2: 30°, 60°) and
 * meridians 60° apart (latitude 6) keep every view within reach of a preview. Previews per page:
 * `distance × (1 + latitude × longitude) × roll` ([views]).
 *
 * This is the maximum: the session throttles below it by [AnticipationThrottle].
 */
@ExperimentalSphereSlamRelocApi
data class AnticipationResolution(val distance: Int, val latitude: Int, val longitude: Int, val roll: Int = 1) {
    init {
        require(distance in 0..MAX_DISTANCE) { "distance must be 0..$MAX_DISTANCE" }
        require(latitude in 0..MAX_LATITUDE) { "latitude must be 0..$MAX_LATITUDE" }
        require(longitude in 0..MAX_LONGITUDE) { "longitude must be 0..$MAX_LONGITUDE" }
        require(roll in 1..MAX_ROLL) { "roll must be 1..$MAX_ROLL" }
    }

    /** Previews per page. */
    val views: Int get() = distance * (1 + latitude * longitude) * roll

    override fun toString(): String = "${distance}d×${latitude}lat×${longitude}lon×${roll}roll"

    companion object {
        const val MAX_DISTANCE = 8
        const val MAX_LATITUDE = 36
        const val MAX_LONGITUDE = 8
        const val MAX_ROLL = 12
        val NONE = AnticipationResolution(0, 0, 0)
    }
}

/**
 * How far each pressure may throttle the [AnticipationResolution]: at full pressure the number of
 * previews is cut by this share (1 to none, 0 ignores that pressure); the cuts multiply.
 *
 * @property battery the battery's discharge (none while charging; full when empty).
 * @property heat the device's thermal headroom (full at the point Android starts throttling).
 * @property compute the session's own load: none while its frames take under half the camera's frame
 *   interval, full when they take all of it.
 */
@ExperimentalSphereSlamRelocApi
data class AnticipationThrottle(val battery: Float = 0.5f, val heat: Float = 1f, val compute: Float = 0.5f) {
    init {
        require(battery in 0f..1f && heat in 0f..1f && compute in 0f..1f) { "throttle weights must be 0..1" }
    }

    companion object {
        /** Never throttle: always the full resolution. */
        val OFF = AnticipationThrottle(0f, 0f, 0f)
    }
}

/** Battery and heat pressure, each 0 (none) to 1 (full); see [AnticipationThrottle]. */
@ExperimentalSphereSlamRelocApi
data class DevicePressure(val battery: Float, val heat: Float) {
    companion object {
        val NONE = DevicePressure(0f, 0f)
    }
}

/** Reads [DevicePressure]; called at most every couple of seconds, on the camera thread. */
@ExperimentalSphereSlamRelocApi
fun interface DevicePressureSource {
    fun read(): DevicePressure
}

/**
 * The device's own pressure: battery from the sticky battery broadcast (none while charging), heat
 * from [PowerManager.getThermalHeadroom] (API 30+; Android throttles at 1), else
 * [PowerManager.getCurrentThermalStatus] (API 29: light ¼ … critical 1), else none.
 */
@ExperimentalSphereSlamRelocApi
class AndroidDevicePressure(context: Context) : DevicePressureSource {
    private val app = context.applicationContext
    private val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun read(): DevicePressure = DevicePressure(battery(), heat())

    private fun battery(): Float {
        val status = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            ?: return 0f
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (plugged || level < 0 || scale <= 0) return 0f
        return (1f - level.toFloat() / scale).coerceIn(0f, 1f)
    }

    private fun heat(): Float {
        val pm = power ?: return 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val headroom = runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
            if (headroom.isFinite()) return headroom.coerceIn(0f, 1f)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return (pm.currentThermalStatus / 4f).coerceIn(0f, 1f)
        }
        return 0f
    }
}

/**
 * A throttled subset of a resolution's views: every [shellStep]th half-sphere (centred on the middle
 * one), every [circleStep]th circle, every [meridianStep]th meridian (from 0, the page's right) and
 * every [rollStep]th roll (from upright).
 * The session only uses the nested subsets of [chain], so lowering builds nothing and raising again
 * reuses what was built. [NONE] keeps nothing.
 */
@ExperimentalSphereSlamRelocApi
data class AnticipationDecimation(val shellStep: Int, val circleStep: Int, val meridianStep: Int, val rollStep: Int = 1) {

    internal fun keeps(site: ReferencePreviews.Site, max: AnticipationResolution): Boolean {
        if (this == NONE) return false
        val mid = (max.distance - 1) / 2
        if ((site.shell - mid) % shellStep != 0) return false
        if (site.circle != 0 && site.circle % circleStep != 0) return false
        if (site.circle != 0 && site.meridian % meridianStep != 0) return false
        if (site.roll % rollStep != 0) return false
        return true
    }

    /** The resolution this subset amounts to: half-spheres, meridians, circles and rolls kept. */
    fun of(max: AnticipationResolution): AnticipationResolution {
        if (this == NONE || max.distance == 0) return AnticipationResolution.NONE
        val mid = (max.distance - 1) / 2
        val shells = (0 until max.distance).count { (it - mid) % shellStep == 0 }
        val circles = (1..max.longitude).count { it % circleStep == 0 }
        val meridians = (0 until max.latitude).count { it % meridianStep == 0 }
        val rolls = (0 until max.roll).count { it % rollStep == 0 }
        return AnticipationResolution(shells, meridians, circles, rolls)
    }

    companion object {
        val FULL = AnticipationDecimation(1, 1, 1, 1)
        val NONE = AnticipationDecimation(0, 0, 0, 0)

        /**
         * The throttled subsets of [max], from [FULL] down to [NONE], each a subset of the one before:
         * each step doubles one step (meridian, circle, half-sphere or roll), the one that keeps the most
         * views, so the chain thins evenly and any lower subset needs no preview a higher one lacks.
         */
        fun chain(max: AnticipationResolution): List<AnticipationDecimation> {
            if (max.views == 0) return listOf(NONE)
            val out = arrayListOf(FULL)
            var d = FULL
            while (true) {
                val views = d.of(max).views
                val next = listOf(
                    d.copy(meridianStep = d.meridianStep * 2),
                    d.copy(circleStep = d.circleStep * 2),
                    d.copy(shellStep = d.shellStep * 2),
                    d.copy(rollStep = d.rollStep * 2),
                ).filter { it.of(max).views < views }.maxByOrNull { it.of(max).views } ?: break
                d = next
                out += d
            }
            out += NONE
            return out
        }

        /** The first of [chain] with at most [factor] × [max]'s views ([NONE] when not one fits). */
        fun forFactor(max: AnticipationResolution, factor: Float): AnticipationDecimation {
            if (factor >= 1f) return if (max.views == 0) NONE else FULL
            val budget = factor * max.views
            return chain(max).first { it == NONE || it.of(max).views <= budget }
        }
    }
}

/** What the session anticipates at: the [max] resolution, the [effective] one and why. */
@ExperimentalSphereSlamRelocApi
data class AnticipationStatus(
    val max: AnticipationResolution,
    val decimation: AnticipationDecimation,
    val factor: Float,
    val batteryPressure: Float,
    val heatPressure: Float,
    val computePressure: Float,
) {
    val effective: AnticipationResolution get() = decimation.of(max)
}

/**
 * Throttles the anticipation resolution from battery, heat and compute pressure. [onFrame] takes each
 * frame's processing time; [update] re-reads the pressures every [intervalMs] and picks the subset of
 * the maximum that fits. A lower subset applies at once; a higher one only after it has been wanted
 * for [raiseAfterMs], so a pressure hovering at a step does not rebuild and drop previews.
 */
@ExperimentalSphereSlamRelocApi
internal class AnticipationGovernor(
    @Volatile var max: AnticipationResolution,
    @Volatile var throttle: AnticipationThrottle,
    @Volatile var source: DevicePressureSource?,
    private val intervalMs: Long = 2_000L,
    private val raiseAfterMs: Long = 10_000L,
) {
    private var utilization = 0f
    private var lastFrameNs = Long.MIN_VALUE
    private var lastUpdateMs = Long.MIN_VALUE
    private var raiseSinceMs = Long.MIN_VALUE
    private var device = DevicePressure.NONE
    private var appliedMax: AnticipationResolution? = null

    @Volatile var status: AnticipationStatus = AnticipationStatus(max, AnticipationDecimation.FULL, 1f, 0f, 0f, 0f)
        private set

    /** One frame took [workMs] to process; [timestampNs] is the camera's, for the frame interval. */
    fun onFrame(workMs: Float, timestampNs: Long) {
        val last = lastFrameNs
        lastFrameNs = timestampNs
        if (last == Long.MIN_VALUE || timestampNs <= last) return
        val frameMs = (timestampNs - last) / 1e6f
        if (frameMs > 1_000f) return // a pause, not a frame interval
        utilization += 0.1f * (workMs / frameMs - utilization)
    }

    /** The status for now: re-read and re-chosen every [intervalMs], or at once when [max] changed. */
    fun update(nowMs: Long): AnticipationStatus {
        val m = max
        if (m != appliedMax) {
            appliedMax = m
            lastUpdateMs = Long.MIN_VALUE
            raiseSinceMs = Long.MIN_VALUE
            status = status.copy(max = m, decimation = AnticipationDecimation.forFactor(m, status.factor))
        }
        if (lastUpdateMs != Long.MIN_VALUE && nowMs - lastUpdateMs < intervalMs) return status
        lastUpdateMs = nowMs
        device = source?.let { runCatching { it.read() }.getOrNull() } ?: DevicePressure.NONE
        val t = throttle
        val compute = ((utilization - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val factor = (1f - t.battery * device.battery) * (1f - t.heat * device.heat) * (1f - t.compute * compute)
        val wanted = AnticipationDecimation.forFactor(m, factor)
        val current = status.decimation
        val chosen = when {
            wanted.of(m).views <= current.of(m).views -> { raiseSinceMs = Long.MIN_VALUE; wanted }
            raiseSinceMs == Long.MIN_VALUE -> { raiseSinceMs = nowMs; current }
            nowMs - raiseSinceMs >= raiseAfterMs -> { raiseSinceMs = Long.MIN_VALUE; wanted }
            else -> current
        }
        status = AnticipationStatus(m, chosen, factor, device.battery, device.heat, compute)
        return status
    }
}

package com.hereliesaz.sphereslam.testapp

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * What the session did with one frame, for [RunMetrics]: the state, the pose's source and method,
 * its quality, how long the frame took, and the camera relative to the target (from the visual pose,
 * or the estimate when there is none: an estimate's rotation is the gyro's and is right, so its
 * viewing angle is a fair measure of the real one).
 *
 * @property timeMs frame time (its camera timestamp), ms.
 * @property cameraFromFingerprint GL column-major camera-from-fingerprint (the captured page is 1
 *   unit wide in that frame), or null when nothing is placed.
 * @property still the phone turned under [RunMetrics.STILL_RATE_DEG_S] since the last frame.
 */
class FrameObservation(
    val timeMs: Long,
    val state: String,
    val source: String?,
    val method: String?,
    val inliers: Int?,
    val errorPx2: Float?,
    val matchMs: Float?,
    val frameMs: Float,
    val cameraFromFingerprint: FloatArray?,
    val still: Boolean,
) {
    val visual: Boolean get() = source == "KPM"
}

/**
 * Continuous measures of a run, accumulated frame by frame from the first capture on. The same code
 * scores a live session and a replay, so they compare directly. Measures, in [Summary]:
 *
 * - locked %: share of time with a visual (KPM) pose; also per viewing-angle band ([BANDS]);
 * - steepest lock: the largest viewing angle of any visual pose;
 * - relocks: losses of the visual pose longer than [LOSS_MS] that ended, and the median / longest
 *   time to relock; losses still open at the end are counted apart;
 * - estimate error at relock: how far the estimate shown just before a relock was from the relocked
 *   pose (camera position in target widths, and angle);
 * - jitter: while the phone was still for [STILL_FRAMES]+ visual frames, the spread of the camera
 *   position, as a percentage of the target's width (median over such stretches);
 * - jumps: consecutive visual poses (≤ [JUMP_WINDOW_MS] apart) whose camera moved over
 *   [JUMP_WIDTHS] target widths or turned over [JUMP_DEG];
 * - reprojection error of the visual poses (median), frame and match time (median and 95th
 *   percentile), frames dropped, failures by reason.
 */
class RunMetrics {
    private var started = false
    private var lastTimeMs = Long.MIN_VALUE
    private var lastObs: FrameObservation? = null
    private var totalMs = 0L
    private var lockedMs = 0L
    private val bandTotal = LongArray(BANDS.size)
    private val bandLocked = LongArray(BANDS.size)
    private var steepest = 0f
    private var lossStartMs = Long.MIN_VALUE
    private var lastVisualMs = Long.MIN_VALUE
    private var lastVisualPose: FloatArray? = null
    private var lastEstimate: FloatArray? = null
    private val relockMs = ArrayList<Long>()
    private val relockErrWidths = ArrayList<Float>()
    private val relockErrDeg = ArrayList<Float>()
    private val stillCentres = ArrayList<FloatArray>()
    private val jitter = ArrayList<Float>()
    private var jumps = 0
    private val frameMs = ArrayList<Float>()
    private val matchMs = ArrayList<Float>()
    private val errors = ArrayList<Float>()
    private val timeline = ArrayList<TimelinePoint>()
    private var nextTimelineMs = Long.MIN_VALUE
    private val failures = linkedMapOf<String, Int>()
    var frames = 0
        private set
    var dropped = 0
        private set

    /** Starts measuring (the first target capture): before it there is nothing to lock on. */
    fun start() {
        started = true
    }

    fun onDropped(count: Int) {
        if (started) dropped += count
    }

    fun onFailure(reason: String) {
        if (started) failures[reason] = (failures[reason] ?: 0) + 1
    }

    fun observe(o: FrameObservation) {
        if (!started) return
        frames++
        frameMs += o.frameMs
        val prev = lastObs
        // Each frame stands for the time until the next one: credit the previous frame with it.
        if (prev != null && lastTimeMs != Long.MIN_VALUE) {
            val dt = (o.timeMs - lastTimeMs).coerceIn(0L, MAX_FRAME_GAP_MS)
            totalMs += dt
            if (prev.visual) lockedMs += dt
            angleOf(prev.cameraFromFingerprint)?.let { a ->
                val b = band(a)
                bandTotal[b] += dt
                if (prev.visual) bandLocked[b] += dt
            }
        }
        lastTimeMs = o.timeMs
        lastObs = o
        if (nextTimelineMs == Long.MIN_VALUE || o.timeMs >= nextTimelineMs) {
            timeline += TimelinePoint(o.timeMs, angleOf(o.cameraFromFingerprint), o.visual)
            nextTimelineMs = o.timeMs + TIMELINE_STEP_MS
        }

        val pose = o.cameraFromFingerprint
        if (o.visual && pose != null) {
            o.matchMs?.let { matchMs += it }
            o.errorPx2?.let { errors += it }
            angleOf(pose)?.let { steepest = maxOf(steepest, it) }
            // A loss ends.
            if (lossStartMs != Long.MIN_VALUE) {
                val lost = o.timeMs - lossStartMs
                if (lost >= LOSS_MS) {
                    relockMs += lost
                    lastEstimate?.let { est ->
                        relockErrWidths += distance(centre(est), centre(pose))
                        relockErrDeg += rotationDeg(est, pose)
                    }
                }
                lossStartMs = Long.MIN_VALUE
            }
            // Jumps between consecutive visual poses.
            val lp = lastVisualPose
            if (lp != null && o.timeMs - lastVisualMs <= JUMP_WINDOW_MS) {
                if (distance(centre(lp), centre(pose)) > JUMP_WIDTHS || rotationDeg(lp, pose) > JUMP_DEG) jumps++
            }
            // Jitter while still.
            if (o.still) stillCentres += centre(pose) else closeStillStretch()
            lastVisualPose = pose
            lastVisualMs = o.timeMs
            lastEstimate = null
        } else {
            closeStillStretch()
            if (lastVisualMs != Long.MIN_VALUE && lossStartMs == Long.MIN_VALUE) lossStartMs = lastVisualMs
            if (pose != null) lastEstimate = pose
        }
    }

    private fun closeStillStretch() {
        if (stillCentres.size >= STILL_FRAMES) {
            val n = stillCentres.size
            val mean = FloatArray(3) { i -> stillCentres.sumOf { it[i].toDouble() }.toFloat() / n }
            val sd = sqrt(stillCentres.sumOf { c -> (0..2).sumOf { i -> ((c[i] - mean[i]) * (c[i] - mean[i])).toDouble() } } / n).toFloat()
            jitter += sd
        }
        stillCentres.clear()
    }

    /** The measures so far; [endMs] closes a loss still open (its length counts as unresolved). */
    fun summary(endMs: Long = lastTimeMs): Summary {
        closeStillStretch()
        val openLoss = if (lossStartMs != Long.MIN_VALUE && endMs != Long.MIN_VALUE) endMs - lossStartMs else 0L
        return Summary(
            frames = frames,
            dropped = dropped,
            seconds = totalMs / 1000f,
            lockedPct = pct(lockedMs, totalMs),
            bandLockedPct = BANDS.indices.map { i -> if (bandTotal[i] < MIN_BAND_MS) null else pct(bandLocked[i], bandTotal[i]) },
            bandSeconds = bandTotal.map { it / 1000f },
            steepestLockDeg = steepest,
            relocks = relockMs.size,
            relockMedianMs = median(relockMs.map { it.toFloat() }),
            relockMaxMs = relockMs.maxOrNull()?.toFloat(),
            unresolvedLossMs = openLoss.takeIf { it >= LOSS_MS },
            relockErrorWidths = median(relockErrWidths),
            relockErrorDeg = median(relockErrDeg),
            jitterPct = median(jitter)?.let { it * 100f },
            jumps = jumps,
            frameMsP50 = percentile(frameMs, 50),
            frameMsP95 = percentile(frameMs, 95),
            matchMsP50 = percentile(matchMs, 50),
            matchMsP95 = percentile(matchMs, 95),
            errorPx2P50 = median(errors),
            failures = failures.toMap(),
            timeline = timeline.toList(),
        )
    }

    /** The measures of one run. Nullable ones had nothing to measure. */
    class Summary(
        val frames: Int,
        val dropped: Int,
        val seconds: Float,
        val lockedPct: Float?,
        val bandLockedPct: List<Float?>,
        val bandSeconds: List<Float>,
        val steepestLockDeg: Float,
        val relocks: Int,
        val relockMedianMs: Float?,
        val relockMaxMs: Float?,
        val unresolvedLossMs: Long?,
        val relockErrorWidths: Float?,
        val relockErrorDeg: Float?,
        val jitterPct: Float?,
        val jumps: Int,
        val frameMsP50: Float?,
        val frameMsP95: Float?,
        val matchMsP50: Float?,
        val matchMsP95: Float?,
        /** Median reprojection error of the visual poses, px². */
        val errorPx2P50: Float?,
        val failures: Map<String, Int>,
        /** The viewing angle and whether the pose was visual, every [TIMELINE_STEP_MS], for the charts. */
        val timeline: List<TimelinePoint> = emptyList(),
    ) {
        /** Locked share at viewing angles of 45° and more, time-weighted across those bands. */
        val steepLockedPct: Float?
            get() {
                val idx = BANDS.indices.filter { BANDS[it].first >= 45f }
                val total = idx.sumOf { bandSeconds[it].toDouble() }
                if (total * 1000 < MIN_BAND_MS) return null
                val locked = idx.sumOf { i -> (bandLockedPct[i] ?: 0f) / 100.0 * bandSeconds[i] }
                return (locked / total * 100).toFloat()
            }

        /** One line for the log and the report. */
        fun describe(): String = lines().joinToString(", ")

        /** Every measure, one per entry, in words. */
        fun lines(): List<String> = buildList {
            add("locked ${f0(lockedPct)}% (by angle " + BANDS.indices.joinToString(" ") { i -> "${bandName(i)}:${f0(bandLockedPct[i])}" } + ")")
            add("steepest lock ${f0(steepestLockDeg)}°")
            add("relocks $relocks (median ${f0(relockMedianMs)} ms; max ${f0(relockMaxMs)} ms" +
                (unresolvedLossMs?.let { "; unresolved $it ms" } ?: "") + ")")
            add("estimate off at relock ${f2(relockErrorWidths)} widths / ${f0(relockErrorDeg)}°")
            add("jitter ${f1(jitterPct)}%")
            add("jumps $jumps")
            add("error ${f1(errorPx2P50)} px²")
            add("frame ${f0(frameMsP50)}/${f0(frameMsP95)} ms")
            add("match ${f0(matchMsP50)}/${f0(matchMsP95)} ms")
            add("frames $frames; dropped $dropped")
            if (failures.isNotEmpty()) add("failures " + failures.entries.joinToString(" ") { (k, v) -> "$k=$v" })
        }
    }

    /** One sample of [Summary.timeline]: [angleDeg] null when nothing was placed. */
    class TimelinePoint(val timeMs: Long, val angleDeg: Float?, val visual: Boolean)

    companion object {
        const val TIMELINE_STEP_MS = 100L
        /** Viewing-angle bands, degrees from the target's normal. */
        val BANDS = listOf(0f to 15f, 15f to 30f, 30f to 45f, 45f to 60f, 60f to 90f)
        const val LOSS_MS = 300L
        const val MAX_FRAME_GAP_MS = 500L
        const val MIN_BAND_MS = 1_000L
        const val STILL_RATE_DEG_S = 3f
        const val STILL_FRAMES = 15
        const val JUMP_WINDOW_MS = 150L
        const val JUMP_WIDTHS = 0.25f
        const val JUMP_DEG = 8f

        fun bandName(i: Int): String = "${BANDS[i].first.toInt()}–${BANDS[i].second.toInt()}°"

        private fun band(angle: Float): Int = BANDS.indexOfFirst { angle < it.second }.let { if (it < 0) BANDS.size - 1 else it }

        /** Viewing angle: between the camera's axis and the target's normal (fingerprint z), degrees. */
        fun angleOf(view: FloatArray?): Float? {
            val m = view ?: return null
            if (m.size != 16 || !m[10].isFinite()) return null
            return Math.toDegrees(acos(abs(m[10]).toDouble().coerceIn(0.0, 1.0))).toFloat()
        }

        /** Camera centre in the fingerprint frame (target widths): −Rᵀt of a column-major view. */
        fun centre(m: FloatArray): FloatArray = FloatArray(3) { i ->
            -(m[i * 4] * m[12] + m[i * 4 + 1] * m[13] + m[i * 4 + 2] * m[14])
        }

        private fun distance(a: FloatArray, b: FloatArray): Float =
            sqrt((0..2).sumOf { ((a[it] - b[it]) * (a[it] - b[it])).toDouble() }).toFloat()

        /** Angle between the rotations of two column-major views, degrees. */
        fun rotationDeg(a: FloatArray, b: FloatArray): Float {
            var trace = 0.0
            for (c in 0..2) for (r in 0..2) trace += a[c * 4 + r] * b[c * 4 + r]
            return Math.toDegrees(acos(((trace - 1) / 2).coerceIn(-1.0, 1.0))).toFloat()
        }

        private fun pct(part: Long, whole: Long): Float? = if (whole <= 0) null else part * 100f / whole

        fun median(v: List<Float>): Float? = percentile(v, 50)

        fun percentile(v: List<Float>, p: Int): Float? {
            if (v.isEmpty()) return null
            val s = v.sorted()
            return s[((s.size - 1) * p / 100.0).toInt()]
        }

        fun f0(v: Float?): String = v?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "–"
        fun f1(v: Float?): String = v?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "–"
        fun f2(v: Float?): String = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "–"
    }
}

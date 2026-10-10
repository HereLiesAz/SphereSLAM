package com.hereliesaz.sphereslam.sidecar

import java.util.ArrayDeque

/**
 * Short timestamped history of the primary tracker's (e.g. ARCore's) solve-time geometry, for
 * pairing asynchronous KPM observations ([com.hereliesaz.sphereslam.SphereSlamTracker]) with the
 * pose at the **same** camera instant.
 *
 * KPM runs on its own worker. Using the render frame's view/backbone when its result arrives turns
 * ordinary hand motion during matcher latency into a false wall correction. Each submitted camera
 * image carries the sensor timestamp; this history resolves the primary view and artwork backbone
 * from that instant (or a tightly bounded nearest sample).
 *
 * Matrices are column-major 4×4. Not thread-safe; feed and query it from one thread (or guard it).
 *
 * @param capacity samples retained (oldest dropped first), `>= 2`.
 */
class HybridPoseHistory(
    private val capacity: Int = 48,
) {
    /**
     * One paired sample.
     *
     * @property viewMatrix camera-from-world view at [timestampNs] (column-major, OpenGL eye frame).
     * @property backboneMatrix world-from-artwork model matrix at [timestampNs].
     */
    class Sample(
        val timestampNs: Long,
        viewMatrix: FloatArray,
        backboneMatrix: FloatArray,
    ) {
        private val view = viewMatrix.copyOf()
        private val backbone = backboneMatrix.copyOf()

        init {
            require(viewMatrix.size == 16 && backboneMatrix.size == 16)
        }

        /** A fresh copy of the view matrix. */
        val viewMatrix: FloatArray get() = view.copyOf()

        /** A fresh copy of the backbone matrix. */
        val backboneMatrix: FloatArray get() = backbone.copyOf()
    }

    private val samples = ArrayDeque<Sample>(capacity)

    init {
        require(capacity >= 2)
    }

    /** Number of retained samples. */
    val size: Int get() = samples.size

    /** Drop every sample. */
    fun clear() = samples.clear()

    /**
     * Record a sample. Non-positive timestamps and non-finite or malformed matrices are ignored. A
     * timestamp earlier than the newest sample is a clock discontinuity (e.g. an ARCore epoch change):
     * the history is cleared first so samples from two clocks are never cross-paired.
     */
    fun add(timestampNs: Long, viewMatrix: FloatArray, backboneMatrix: FloatArray) {
        if (timestampNs <= 0L || viewMatrix.size != 16 || backboneMatrix.size != 16) return
        if (viewMatrix.any { !it.isFinite() } || backboneMatrix.any { !it.isFinite() }) return
        if (samples.isNotEmpty() && timestampNs < samples.last.timestampNs) samples.clear()
        samples.addLast(Sample(timestampNs, viewMatrix, backboneMatrix))
        while (samples.size > capacity) samples.removeFirst()
    }

    /** The sample nearest [timestampNs] within [maxDeltaNs], or null. */
    fun nearest(timestampNs: Long, maxDeltaNs: Long): Sample? {
        if (timestampNs <= 0L || maxDeltaNs < 0L || samples.isEmpty()) return null
        var best: Sample? = null
        var bestDelta = Long.MAX_VALUE
        for (sample in samples) {
            val delta = if (sample.timestampNs >= timestampNs) sample.timestampNs - timestampNs else timestampNs - sample.timestampNs
            if (delta < bestDelta) {
                best = sample
                bestDelta = delta
            }
        }
        return best?.takeIf { bestDelta <= maxDeltaNs }
    }
}

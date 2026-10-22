package com.hereliesaz.sphereslam.reloc

/**
 * Contrast stretch for dim, flat frames (a dark canvas in a dim room). KPM's corner detector and the
 * [TargetQuality] contrast and sharpness floors are absolute, so a frame whose luma spans only a
 * narrow band finds few features and fails the gate, though the detail is there. Its binary
 * descriptors compare pixel pairs, so a monotonic stretch leaves them unchanged while lifting more
 * corners over the detector's threshold.
 */
internal object LumaContrast {

    /** Percentiles mapped to 0 and 255; the tails beyond them clip. */
    private const val LOW_PERCENTILE = 0.02
    private const val HIGH_PERCENTILE = 0.98

    /** A frame whose middle 96 % already spans this many levels is left alone. */
    const val MIN_SPAN_LEFT_ALONE = 128

    /** A span this narrow is noise on a blank frame, not detail worth amplifying. */
    const val MIN_SPAN_STRETCHED = 8

    /**
     * The lookup table that stretches [luma]'s 2nd–98th percentile to 0..255, or null when the frame
     * already has the range (or too little to be worth it). Apply with [apply].
     */
    fun stretchLut(luma: ByteArray): ByteArray? {
        if (luma.isEmpty()) return null
        val histogram = IntArray(256)
        for (b in luma) histogram[b.toInt() and 0xFF]++
        val lowCount = (luma.size * LOW_PERCENTILE).toLong()
        val highCount = (luma.size * HIGH_PERCENTILE).toLong()
        var seen = 0L
        var low = 0
        var high = 255
        var lowFound = false
        for (v in 0..255) {
            seen += histogram[v]
            if (!lowFound && seen > lowCount) { low = v; lowFound = true }
            if (seen >= highCount) { high = v; break }
        }
        val span = high - low
        if (span >= MIN_SPAN_LEFT_ALONE || span < MIN_SPAN_STRETCHED) return null
        return ByteArray(256) { v ->
            (((v - low) * 255 + span / 2) / span).coerceIn(0, 255).toByte()
        }
    }

    /** A new array: [luma] through [lut]. */
    fun apply(luma: ByteArray, lut: ByteArray): ByteArray =
        ByteArray(luma.size) { lut[luma[it].toInt() and 0xFF] }

    /** [luma] stretched when it needs it, else [luma] itself. */
    fun stretchedOrSame(luma: ByteArray): ByteArray = stretchLut(luma)?.let { apply(luma, it) } ?: luma
}

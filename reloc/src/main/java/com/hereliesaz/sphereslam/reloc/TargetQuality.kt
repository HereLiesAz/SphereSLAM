package com.hereliesaz.sphereslam.reloc

import kotlin.math.sqrt

/** A reason a luma image is a weak planar tracking target (see [TargetQuality]). */
@ExperimentalSphereSlamRelocApi
enum class TargetQualityIssue {
    TOO_SMALL,
    LOW_CONTRAST,
    BLURRY,
    TOO_DARK,
    TOO_BRIGHT,
}

/**
 * Thresholds for [TargetQuality.analyze] and the KPM feature floor a registered reference must reach.
 *
 * @property minDimensionPx smallest acceptable side, pixels.
 * @property minPixels smallest acceptable area, pixels.
 * @property minLumaStdDev minimum luma standard deviation (contrast), 0..255 scale.
 * @property minLaplacianVariance minimum variance of the 4-neighbour Laplacian (sharpness).
 * @property maxClippedFraction fraction of near-black (≤ 8) or near-white (≥ 247) pixels above which
 *   [TargetQualityIssue.TOO_DARK] / [TargetQualityIssue.TOO_BRIGHT] are reported as warnings.
 * @property minKpmFeatures fewest KPM features a registered reference may have (`>= 4`).
 */
@ExperimentalSphereSlamRelocApi
data class TargetQualityConfig(
    val minDimensionPx: Int = 128,
    val minPixels: Int = 65_536,
    val minLumaStdDev: Float = 12f,
    val minLaplacianVariance: Float = 40f,
    val maxClippedFraction: Float = 0.60f,
    val minKpmFeatures: Int = 16,
) {
    init {
        require(minDimensionPx > 0)
        require(minPixels > 0)
        require(minLumaStdDev > 0f)
        require(minLaplacianVariance > 0f)
        require(maxClippedFraction in 0f..1f)
        require(minKpmFeatures >= 4)
    }
}

/**
 * Result of [TargetQuality.analyze]. [blockingIssues] make a target unusable ([acceptable] false);
 * [warnings] are advisory.
 */
@ExperimentalSphereSlamRelocApi
data class TargetQualityReport(
    val blockingIssues: Set<TargetQualityIssue>,
    val warnings: Set<TargetQualityIssue>,
    val lumaStdDev: Float,
    val laplacianVariance: Float,
    val darkFraction: Float,
    val brightFraction: Float,
) {
    val acceptable: Boolean get() = blockingIssues.isEmpty()
}

/** A registered reference produced fewer than [TargetQualityConfig.minKpmFeatures] KPM features. */
@ExperimentalSphereSlamRelocApi
class ReferenceTooWeakException(
    val featureCount: Int,
    val minimumFeatureCount: Int,
) : IllegalStateException(
    "KPM generated only $featureCount reference features; need at least $minimumFeatureCount",
)

/**
 * Cheap, pure-Kotlin quality gate for a tightly packed luma image used as a planar target or a
 * photosphere keyframe: size, contrast (luma standard deviation), sharpness (Laplacian variance) and
 * clipping. User-facing wording is the host's concern; this reports [TargetQualityIssue]s only.
 * Ported from GraffitiXR `StandaloneTargetQuality`.
 */
@ExperimentalSphereSlamRelocApi
object TargetQuality {

    /** @throws IllegalArgumentException when the size is non-positive or [luma] is not `width * height`. */
    fun analyze(
        luma: ByteArray,
        width: Int,
        height: Int,
        config: TargetQualityConfig = TargetQualityConfig(),
    ): TargetQualityReport {
        require(width > 0 && height > 0)
        require(luma.size == width * height)

        val blocking = linkedSetOf<TargetQualityIssue>()
        val warnings = linkedSetOf<TargetQualityIssue>()
        if (
            width < config.minDimensionPx ||
            height < config.minDimensionPx ||
            width.toLong() * height.toLong() < config.minPixels
        ) {
            blocking += TargetQualityIssue.TOO_SMALL
        }

        var sum = 0.0
        var sumSq = 0.0
        var dark = 0
        var bright = 0
        for (b in luma) {
            val v = b.toInt() and 0xff
            sum += v
            sumSq += v.toDouble() * v.toDouble()
            if (v <= 8) dark++
            if (v >= 247) bright++
        }
        val n = luma.size.toDouble()
        val mean = sum / n
        val stdDev = sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0)).toFloat()

        var lapSum = 0.0
        var lapSq = 0.0
        var lapCount = 0
        if (width >= 3 && height >= 3) {
            for (y in 1 until height - 1) {
                val row = y * width
                for (x in 1 until width - 1) {
                    val i = row + x
                    val lap = 4 * (luma[i].toInt() and 0xff) -
                        (luma[i - 1].toInt() and 0xff) -
                        (luma[i + 1].toInt() and 0xff) -
                        (luma[i - width].toInt() and 0xff) -
                        (luma[i + width].toInt() and 0xff)
                    lapSum += lap
                    lapSq += lap.toDouble() * lap.toDouble()
                    lapCount++
                }
            }
        }
        val lapVariance = if (lapCount > 0) {
            val lapMean = lapSum / lapCount
            (lapSq / lapCount - lapMean * lapMean).coerceAtLeast(0.0).toFloat()
        } else {
            0f
        }

        val darkFraction = dark.toFloat() / luma.size.toFloat()
        val brightFraction = bright.toFloat() / luma.size.toFloat()

        if (stdDev < config.minLumaStdDev) blocking += TargetQualityIssue.LOW_CONTRAST
        if (lapVariance < config.minLaplacianVariance) blocking += TargetQualityIssue.BLURRY
        if (darkFraction > config.maxClippedFraction) warnings += TargetQualityIssue.TOO_DARK
        if (brightFraction > config.maxClippedFraction) warnings += TargetQualityIssue.TOO_BRIGHT

        return TargetQualityReport(
            blockingIssues = blocking,
            warnings = warnings,
            lumaStdDev = stdDev,
            laplacianVariance = lapVariance,
            darkFraction = darkFraction,
            brightFraction = brightFraction,
        )
    }
}

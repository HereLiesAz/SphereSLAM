package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TargetQualityAndFailureTest {

    private fun noise(w: Int, h: Int) = Random(7).let { r -> ByteArray(w * h) { r.nextInt(256).toByte() } }

    @Test
    fun `textured target is acceptable`() {
        val report = TargetQuality.analyze(noise(320, 240), 320, 240)
        assertTrue(report.acceptable)
        assertTrue(report.warnings.isEmpty())
        assertTrue(report.lumaStdDev > 50f)
    }

    @Test
    fun `flat target is low contrast and blurry`() {
        val report = TargetQuality.analyze(ByteArray(320 * 240) { 90 }, 320, 240)
        assertEquals(setOf(TargetQualityIssue.LOW_CONTRAST, TargetQualityIssue.BLURRY), report.blockingIssues)
        assertEquals(0f, report.lumaStdDev, 0f)
    }

    @Test
    fun `small target is blocked and clipping only warns`() {
        assertTrue(TargetQualityIssue.TOO_SMALL in TargetQuality.analyze(noise(200, 100), 200, 100).blockingIssues)
        val dark = ByteArray(320 * 240) { i -> if (i % 4 == 0) 200.toByte() else 0 }
        val report = TargetQuality.analyze(dark, 320, 240)
        assertTrue(TargetQualityIssue.TOO_DARK in report.warnings)
        assertFalse(TargetQualityIssue.TOO_DARK in report.blockingIssues)
        assertTrue(
            TargetQuality.analyze(noise(200, 100), 200, 100, TargetQualityConfig(minDimensionPx = 64, minPixels = 1000)).acceptable,
        )
    }

    @Test
    fun `failure severities`() {
        assertEquals(TrackingFailureSeverity.TRANSIENT, TrackingFailureClassifier.severityOf(TrackingFailureReason.POSE_JUMP))
        assertEquals(TrackingFailureSeverity.RECOVERABLE, TrackingFailureClassifier.severityOf(TrackingFailureReason.REFERENCE_TOO_WEAK))
        assertEquals(TrackingFailureSeverity.FATAL, TrackingFailureClassifier.severityOf(TrackingFailureReason.INTERNAL_FAILURE))
        TrackingFailureReason.entries.forEach { TrackingFailureClassifier.event(it) }
    }

    @Test
    fun `pose rejections map onto reasons`() {
        val map = mapOf(
            PoseRejection.TOO_FEW_INLIERS to TrackingFailureReason.INSUFFICIENT_INLIERS,
            PoseRejection.EXCESSIVE_REPROJECTION_ERROR to TrackingFailureReason.EXCESSIVE_REPROJECTION_ERROR,
            PoseRejection.TRANSLATION_JUMP to TrackingFailureReason.POSE_JUMP,
            PoseRejection.ANGULAR_JUMP to TrackingFailureReason.POSE_JUMP,
            PoseRejection.NON_FINITE to TrackingFailureReason.POSE_JUMP,
        )
        map.forEach { (rejection, reason) ->
            val e = TrackingFailureClassifier.fromPoseRejection(rejection, "d")
            assertEquals(reason, e.reason)
            assertEquals(TrackingFailureSeverity.TRANSIENT, e.severity)
            assertEquals("d", e.diagnostic)
        }
    }

    @Test
    fun `throwables map onto reasons`() {
        assertEquals(
            TrackingFailureReason.NATIVE_LIBRARY_UNAVAILABLE,
            TrackingFailureClassifier.fromThrowable(UnsatisfiedLinkError("x")).reason,
        )
        val weak = TrackingFailureClassifier.fromThrowable(ReferenceTooWeakException(3, 16))
        assertEquals(TrackingFailureReason.REFERENCE_TOO_WEAK, weak.reason)
        assertEquals("features=3 minimum=16", weak.diagnostic)
        assertEquals(TrackingFailureReason.INTERNAL_FAILURE, TrackingFailureClassifier.fromThrowable(IllegalStateException()).reason)
    }
}

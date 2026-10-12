package com.hereliesaz.sphereslam.testapp

import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.math.RigidMath
import com.hereliesaz.sphereslam.reloc.PhotosphereTrackingSession
import kotlin.math.abs
import kotlin.math.acos

/**
 * One [PhotosphereTrackingSession] and what both live tracking and replay do around it: hand it a
 * captured target, feed it a [FrameRecord], place the result in the target's frame and score it in
 * [metrics]. Sharing this is what makes a replay comparable with the live run it came from.
 *
 * [clockNs] is the session's clock (live: `SystemClock.elapsedRealtimeNanos`; replay: a virtual
 * clock that runs at the recorded camera's pace). [callbacks] are the owner's; failures are also
 * counted into [metrics].
 */
class SessionDriver(
    config: PhotosphereTrackingSession.Config,
    clockNs: () -> Long,
    callbacks: PhotosphereTrackingSession.Callbacks,
    val metrics: RunMetrics = RunMetrics(),
) : AutoCloseable {

    val session = PhotosphereTrackingSession(
        config,
        PhotosphereTrackingSession.Callbacks(
            onKeyframe = callbacks.onKeyframe,
            onMapFromFingerprintSolved = callbacks.onMapFromFingerprintSolved,
            onReferenceReady = callbacks.onReferenceReady,
            onAtlasPageAdded = callbacks.onAtlasPageAdded,
            onFailure = { e ->
                metrics.onFailure(e.reason.name)
                callbacks.onFailure(e)
            },
            onTrackingStateChanged = callbacks.onTrackingStateChanged,
            onDiagnostic = callbacks.onDiagnostic,
            onFatalError = callbacks.onFatalError,
        ),
        elapsedRealtimeNanos = clockNs,
    )

    /** Page height / width of the captured reference (its width is 1 unit). */
    var pageAspect = 1f
        private set
    private var lastAttitude: AttitudeSample? = null

    /** The session's result for one frame, placed in the target's frame. */
    class Step(
        val result: PhotosphereTrackingSession.FrameResult,
        /** Column-major camera-from-fingerprint: the visual pose, else the session's estimate; null when nothing is placed. */
        val cameraFromFingerprint: FloatArray?,
        val observation: FrameObservation,
    )

    /** Gives the session [capture] as its target and starts the measures. */
    fun capture(capture: CaptureRecord) {
        pageAspect = 1f
        session.updatePrecisionLayer(PhotosphereTrackingSession.ReferenceImage(capture.crop, capture.side, capture.side))
        metrics.start()
    }

    /** Runs [frame] through the session; [frameMs] is filled in by the caller once it knows the frame's full cost. */
    fun step(frame: FrameRecord, frameMs: () -> Float): Step {
        val k = frame.intrinsics
        val result = session.onFrame(
            PhotosphereTrackingSession.Frame(
                luma = frame.luma,
                intrinsics = CameraIntrinsics(k[0], k[1], k[2], k[3], frame.width, frame.height),
                timestampNs = frame.timestampNs,
                attitude = frame.attitude,
                cameraFromDevice = frame.cameraFromDevice,
                headingDeg = frame.headingDeg,
                elevationDeg = frame.elevationDeg,
                rollDeg = frame.rollDeg,
                convertDurationNs = frame.convertNs,
            ),
        )
        val pose = result.pose
        val mapFromFp = session.mapFromFingerprint
        val cameraFromFp = when {
            pose != null && mapFromFp != null -> RigidMath.multiply(pose.cameraFromMap, mapFromFp)
            else -> result.acceptedCameraFromFingerprint
        }
        val still = isStill(lastAttitude, frame.attitude)
        lastAttitude = frame.attitude
        val visual = pose?.source == PhotosphereTrackingSession.PoseSource.KPM
        val observation = FrameObservation(
            timeMs = frame.timestampNs / 1_000_000L,
            state = result.state.name,
            source = pose?.source?.name,
            method = pose?.takeIf { visual }?.matchMethod?.name,
            inliers = pose?.takeIf { visual }?.inlierCount,
            errorPx2 = pose?.takeIf { visual }?.reprojectionError,
            matchMs = pose?.takeIf { visual }?.matchDurationMs,
            frameMs = frameMs(),
            cameraFromFingerprint = cameraFromFp,
            still = still,
        )
        metrics.observe(observation)
        return Step(result, cameraFromFp, observation)
    }

    override fun close() = session.close()

    companion object {
        /** The centre 60 % of the frame's shorter side, square: the target a capture takes. */
        fun crop(frame: FrameRecord, checkId: String): CaptureRecord {
            val side = (minOf(frame.width, frame.height) * 0.6f).toInt()
            val left = (frame.width - side) / 2
            val top = (frame.height - side) / 2
            val crop = ByteArray(side * side)
            for (row in 0 until side) System.arraycopy(frame.luma, (top + row) * frame.width + left, crop, row * side, side)
            return CaptureRecord(frame.timestampNs, checkId, side, crop)
        }

        /** Whether the phone turned slower than [RunMetrics.STILL_RATE_DEG_S] between two samples. */
        fun isStill(a: AttitudeSample?, b: AttitudeSample?): Boolean {
            if (a == null || b == null) return false
            val dt = (b.timestampNs - a.timestampNs) / 1e9
            if (dt <= 0.0) return false
            val qa = a.quaternion; val qb = b.quaternion
            val dot = abs(qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3]).toDouble().coerceAtMost(1.0)
            val deg = Math.toDegrees(2 * acos(dot))
            return deg / dt < RunMetrics.STILL_RATE_DEG_S
        }
    }
}

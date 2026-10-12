package com.hereliesaz.sphereslam.testapp

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hereliesaz.sphereslam.CameraAttitudeProvider
import com.hereliesaz.sphereslam.CoverageGlowProjection
import com.hereliesaz.sphereslam.attitude.DeviceCameraRotation
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.camera.CameraIntrinsicsEstimator
import com.hereliesaz.sphereslam.camera.CaptureRotation
import com.hereliesaz.sphereslam.camera.LumaFrameBufferPool
import com.hereliesaz.sphereslam.math.RigidMath
import com.hereliesaz.sphereslam.reloc.CameraTimestampSource
import com.hereliesaz.sphereslam.reloc.PhotosphereTrackingSession
import kotlin.math.acos

/** What the overlay draws and the HUD prints for the latest frame. */
class TrackedView(
    /** Column-major camera-from-fingerprint, or null when nothing is placed. */
    val cameraFromFingerprint: FloatArray?,
    val projection: FloatArray?,
    val frameWidth: Int,
    val frameHeight: Int,
    /** Page height / width of the captured reference (its width is 1 unit). */
    val pageAspect: Float,
    val hud: String,
)

/**
 * The SphereSLAM pipeline on the CameraX analysis thread: pack the Y plane display-upright, read
 * ONE attitude snapshot, run [PhotosphereTrackingSession.onFrame], and hand the result to the
 * checklist ([Sample]) and the UI ([TrackedView]). The app is locked to portrait, so the
 * display-upright frame is display rotation 0.
 */
class Tracker(
    private val context: Context,
    private val cameraId: String,
    private val realtimeTimestamps: Boolean,
    private val attitude: CameraAttitudeProvider,
    private val checklist: Checklist,
    private val log: (String) -> Unit,
    private val onView: (TrackedView) -> Unit,
    private val onChecklistChanged: () -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val pool = LumaFrameBufferPool(slots = 2)
    private val cameraFromDisplay = DeviceCameraRotation.cameraFromDeviceForDisplayFrame(0)
    @Volatile private var captureRequested = false
    private var pageAspect = 1f
    private var lastAxis: FloatArray? = null

    private val session = PhotosphereTrackingSession(
        PhotosphereTrackingSession.Config(
            timestampSource = if (realtimeTimestamps) CameraTimestampSource.REALTIME else CameraTimestampSource.UNKNOWN,
        ),
        PhotosphereTrackingSession.Callbacks(
            onMapFromFingerprintSolved = { mutate { checklist.onMapSolved() } },
            onAtlasPageAdded = { page, _ -> mutate { checklist.onAtlasPage(page.pageNo) } },
            onDiagnostic = { line -> log(line); mutate { checklist.onDiagnostic(line) } },
            onFatalError = { t -> log("fatal: ${t.javaClass.simpleName}: ${t.message}") },
            onFailure = { e -> log("failure: ${e.reason}") },
            onTrackingStateChanged = { s -> log("state: $s") },
        ),
    )

    /** Capture the centre of the next frame as the target. */
    fun requestCapture() {
        captureRequested = true
    }

    /** Checklist writes hold its monitor: the UI and the reporter read it from other threads. */
    private inline fun mutate(block: () -> Unit) {
        val changed = synchronized(checklist) {
            val before = checklist.changes
            block()
            checklist.changes != before
        }
        if (changed) onChecklistChanged()
    }

    override fun analyze(image: ImageProxy) {
        try {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val y = image.planes[0]
            val rotation = image.imageInfo.rotationDegrees
            val luma = pool.packAndRotate(y.buffer, image.width, image.height, y.rowStride, y.pixelStride, rotation)
            val convertNs = SystemClock.elapsedRealtimeNanos() - t0
            val raw = CameraIntrinsicsEstimator.estimate(context, cameraId, image.width, image.height) ?: return
            val r = CaptureRotation.rotateIntrinsics(raw.fx, raw.fy, raw.cx, raw.cy, image.width.toFloat(), image.height.toFloat(), rotation)
            val intrinsics = CameraIntrinsics(r[0], r[1], r[2], r[3], luma.width, luma.height)
            val snapshot = attitude.latestAttitude()
            val roll = snapshot?.let { CameraAttitudeProvider.cameraRollDegrees(it.deviceToWorld, cameraFromDisplay) }

            if (captureRequested) {
                captureRequested = false
                capture(luma.bytes, luma.width, luma.height)
                mutate { checklist.onCapture(SystemClock.elapsedRealtime(), snapshot?.elevationDeg) }
            }

            val result = session.onFrame(
                PhotosphereTrackingSession.Frame(
                    luma = luma.bytes,
                    intrinsics = intrinsics,
                    timestampNs = image.imageInfo.timestamp,
                    attitude = attitude.latestSample(),
                    cameraFromDevice = cameraFromDisplay,
                    headingDeg = snapshot?.headingDeg,
                    elevationDeg = snapshot?.elevationDeg,
                    rollDeg = roll,
                    convertDurationNs = convertNs,
                ),
            )
            val pose = result.pose
            val nowMs = SystemClock.elapsedRealtime()
            mutate {
                checklist.onAttitude(attitude.isAvailable, attitude.isReliable && snapshot != null)
                checklist.observe(
                    Sample(
                        timeMs = nowMs,
                        state = result.state.name,
                        source = pose?.source?.name,
                        method = pose?.takeIf { it.source == PhotosphereTrackingSession.PoseSource.KPM }?.matchMethod?.name,
                        matchMs = pose?.matchDurationMs,
                        inliers = pose?.inlierCount,
                        elevationDeg = snapshot?.elevationDeg,
                        headingDeg = snapshot?.headingDeg,
                        facingDeg = snapshot?.facingAzimuthDeg,
                        rebuildErrorDeg = snapshot?.let { rebuildError(it) },
                        axisStepDeg = snapshot?.let { axisStep(it.deviceToWorld) },
                    ),
                )
            }

            val mapFromFp = session.mapFromFingerprint
            val cameraFromFp = when {
                pose != null && mapFromFp != null -> RigidMath.multiply(pose.cameraFromMap, mapFromFp)
                else -> result.acceptedCameraFromFingerprint
            }
            onView(
                TrackedView(
                    cameraFromFingerprint = cameraFromFp,
                    projection = pose?.projection ?: result.projection,
                    frameWidth = luma.width,
                    frameHeight = luma.height,
                    pageAspect = pageAspect,
                    hud = hud(result, snapshot, roll),
                ),
            )
        } catch (t: Throwable) {
            log("analyzer: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            image.close()
        }
    }

    /** The centre 60 % of the frame's shorter side, square, as page 0 (1 unit wide). */
    private fun capture(luma: ByteArray, width: Int, height: Int) {
        val side = (minOf(width, height) * 0.6f).toInt()
        val left = (width - side) / 2
        val top = (height - side) / 2
        val crop = ByteArray(side * side)
        for (row in 0 until side) System.arraycopy(luma, (top + row) * width + left, crop, row * side, side)
        pageAspect = 1f
        session.updatePrecisionLayer(PhotosphereTrackingSession.ReferenceImage(crop, side, side))
        log("captured ${side}x$side target")
    }

    /** Angle between the sensor rotation and the one rebuilt from its (heading, elevation, roll). */
    private fun rebuildError(a: CameraAttitudeProvider.Attitude): Float? {
        val fromAngles = CoverageGlowProjection.worldFromCamera(a.headingDeg, a.elevationDeg, a.rollDeg) ?: return null
        val fromSensor = CoverageGlowProjection.worldFromCamera(a.deviceToWorld)
        var trace = 0.0
        for (i in 0..2) for (k in 0..2) trace += fromAngles[k * 3 + i] * fromSensor[k * 3 + i]
        return Math.toDegrees(acos(((trace - 1) / 2).coerceIn(-1.0, 1.0))).toFloat()
    }

    private fun axisStep(r: FloatArray): Float? {
        val axis = floatArrayOf(-r[2], -r[5], -r[8])
        val prev = lastAxis
        lastAxis = axis
        prev ?: return null
        val dot = (axis[0] * prev[0] + axis[1] * prev[1] + axis[2] * prev[2]).toDouble().coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot)).toFloat()
    }

    private fun hud(result: PhotosphereTrackingSession.FrameResult, a: CameraAttitudeProvider.Attitude?, roll: Float?): String {
        val p = result.pose
        return buildString {
            append("state ").append(result.state)
            if (p != null) {
                append("  ").append(p.source)
                if (p.source == PhotosphereTrackingSession.PoseSource.KPM) append(' ').append(p.matchMethod)
                append("\nmatch ").append("%.1f".format(p.matchDurationMs)).append(" ms  inliers ").append(p.inlierCount)
            }
            if (a != null) {
                append("\nhdg ").append("%.0f".format(a.headingDeg))
                append("  elev ").append("%.1f".format(a.elevationDeg))
                append("  roll ").append(roll?.let { "%.0f".format(it) } ?: "?")
                append("  facing ").append(a.facingAzimuthDeg?.let { "%.0f".format(it) } ?: "?")
            } else {
                append("\nno attitude")
            }
            result.failure?.let { append("\n").append(it.reason) }
        }
    }

    override fun close() = session.close()
}

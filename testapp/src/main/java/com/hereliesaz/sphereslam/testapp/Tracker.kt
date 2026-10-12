package com.hereliesaz.sphereslam.testapp

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hereliesaz.sphereslam.CameraAttitudeProvider
import com.hereliesaz.sphereslam.CoverageGlowProjection
import com.hereliesaz.sphereslam.attitude.DeviceCameraRotation
import com.hereliesaz.sphereslam.camera.CameraIntrinsicsEstimator
import com.hereliesaz.sphereslam.camera.CaptureRotation
import com.hereliesaz.sphereslam.camera.LumaFrameBufferPool
import com.hereliesaz.sphereslam.reloc.AndroidDevicePressure
import com.hereliesaz.sphereslam.reloc.CameraTimestampSource
import com.hereliesaz.sphereslam.reloc.PhotosphereTrackingSession
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.acos

/** What the overlay draws and the telemetry line prints for the latest frame. */
class TrackedView(
    /** Column-major camera-from-fingerprint, or null when nothing is placed. */
    val cameraFromFingerprint: FloatArray?,
    val projection: FloatArray?,
    val frameWidth: Int,
    val frameHeight: Int,
    /** Page height / width of the captured reference (its width is 1 unit). */
    val pageAspect: Float,
    /** One line: pose source and method, match time, inliers (empty without a pose). */
    val telemetry: String,
    /**
     * True when the outline is not this frame's visual match (a gyro bridge, a held lock or the
     * photosphere's placement): the overlay draws it dashed, as an estimate.
     */
    val estimated: Boolean,
    /** The session's tracking state (`INITIALIZING`, `LOCKED`, `IMU_BRIDGE`, `REACQUIRING`, `LOST`, `FATAL`). */
    val state: String,
    /** Camera elevation from the attitude snapshot, degrees; null without one. */
    val elevationDeg: Float?,
)

/**
 * The SphereSLAM pipeline on the camera, in two stages:
 *
 * 1. On the CameraX analysis thread, every frame: pack the Y plane display-upright, read one attitude
 *    snapshot, make a [FrameRecord] of everything the session will be given, and hand it to the
 *    [recorder] (when recording) and to stage 2. This is quick, so the camera never waits on tracking
 *    and a recording holds every frame the camera delivered.
 * 2. On the tracking thread, the latest frame only (older ones not yet started are dropped, as CameraX
 *    would): [SessionDriver.step], then the checklist ([Sample]) and the UI ([TrackedView]).
 *
 * The session is built from [knobs] ([Knobs.config]); [reference], when given, is the target to hold
 * from the first frame (a session restarted for new knob values keeps its target). The display-upright
 * frame follows [displayRotationDeg] (CameraX rotates the analysis image to the use case's target
 * rotation, which the activity keeps current).
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
    knobs: Map<String, Float> = Knobs.DEFAULTS,
    reference: CaptureRecord? = null,
    private val recorder: () -> RecordingWriter? = { null },
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val pool = LumaFrameBufferPool(slots = 2)

    /** The display's rotation, degrees (0, 90, 180, 270); set by the activity when it changes. */
    @Volatile var displayRotationDeg: Int = 0
    private var cameraFromDisplayDeg = 0
    private var cameraFromDisplay = DeviceCameraRotation.cameraFromDeviceForDisplayFrame(0)

    /** The lock check the next frame's capture is for (`lock_wall` …), or null. */
    @Volatile private var captureFor: String? = null
    /** A target to give the session before the next frame (set at construction for a restart). */
    @Volatile private var pendingReference: CaptureRecord? = reference

    /** The target the session holds now, for a restart to carry over. */
    @Volatile var reference: CaptureRecord? = null
        private set

    /** Stopped, rejected-capture and held-target state from the session's failure callbacks. */
    val failures = FailureLatch()
    private val diagnostics = DiagnosticFilter()
    private var lastAxis: FloatArray? = null

    private val driver = SessionDriver(
        Knobs.config(knobs, if (realtimeTimestamps) CameraTimestampSource.REALTIME else CameraTimestampSource.UNKNOWN),
        { SystemClock.elapsedRealtimeNanos() },
        PhotosphereTrackingSession.Callbacks(
            onMapFromFingerprintSolved = { mutate { checklist.onMapSolved() } },
            onAtlasPageAdded = { page, _ -> mutate { checklist.onAtlasPage(page.pageNo) } },
            onDiagnostic = { line ->
                diagnostics.filter(line)?.let(log)
                mutate { checklist.onDiagnostic(line) }
            },
            // Failures and state changes already reach the log through onDiagnostic; not logged twice.
            onFatalError = { t -> failures.onFatalError(t.javaClass.simpleName, sessionStopped()) },
            onFailure = { e ->
                failures.onFailure(e.reason.name, e.severity.name, SystemClock.elapsedRealtime())
                mutate { checklist.onFailure(e.reason.name, e.diagnostic) }
            },
        ),
    )

    init {
        // Battery and heat throttle the anticipation, as they would in an app.
        driver.session.devicePressure = AndroidDevicePressure(context)
    }

    private val session get() = driver.session

    /** Live measures of this session since its first target ([RunMetrics]); read under its own lock. */
    fun metricsSummary(): RunMetrics.Summary = synchronized(driver.metrics) { driver.metrics.summary() }

    // Stage 2: one tracking thread, fed the latest frame.
    private val trackingExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "sphereslam-test-tracking") }
    private class LiveFrame(val record: FrameRecord, val snapshot: CameraAttitudeProvider.Attitude?)
    private val latest = AtomicReference<LiveFrame?>(null)
    private val draining = AtomicBoolean(false)
    @Volatile private var closed = false

    /** Capture the centre of the next frame as the target for [checkId] (`lock_wall`, `lock_floor`, `lock_ceiling`). */
    fun requestCapture(checkId: String) {
        captureFor = checkId
    }

    /** The newest frame's timestamp, for events recorded outside the pipeline (a restart, new knobs). */
    @Volatile var lastFrameNs: Long = 0L
        private set

    // The callbacks run inside the session, on the tracking thread; `session` is assigned by then.
    private fun sessionStopped(): Boolean = session.isFatal && !session.isRecoverable

    /** Checklist writes hold its monitor: the UI and the reporter read it from other threads. */
    private inline fun mutate(block: () -> Unit) {
        val changed = synchronized(checklist) {
            val before = checklist.changes
            block()
            checklist.changes != before
        }
        if (changed) onChecklistChanged()
    }

    // --- stage 1: the analysis thread -----------------------------------------------------------

    override fun analyze(image: ImageProxy) {
        try {
            if (closed) return
            val t0 = SystemClock.elapsedRealtimeNanos()
            val y = image.planes[0]
            val rotation = image.imageInfo.rotationDegrees
            val packed = pool.packAndRotate(y.buffer, image.width, image.height, y.rowStride, y.pixelStride, rotation)
            // A copy of its own: the pool's buffer is reused by the next frame, this one may be queued.
            val luma = packed.bytes.copyOf(packed.width * packed.height)
            val convertNs = SystemClock.elapsedRealtimeNanos() - t0
            val raw = CameraIntrinsicsEstimator.estimate(context, cameraId, image.width, image.height) ?: return
            val r = CaptureRotation.rotateIntrinsics(raw.fx, raw.fy, raw.cx, raw.cy, image.width.toFloat(), image.height.toFloat(), rotation)
            val snapshot = attitude.latestAttitude()
            val displayDeg = displayRotationDeg
            if (displayDeg != cameraFromDisplayDeg) {
                cameraFromDisplay = DeviceCameraRotation.cameraFromDeviceForDisplayFrame(displayDeg)
                cameraFromDisplayDeg = displayDeg
            }
            val roll = snapshot?.let { CameraAttitudeProvider.cameraRollDegrees(it.deviceToWorld, cameraFromDisplay) }
            val record = FrameRecord(
                timestampNs = image.imageInfo.timestamp,
                width = packed.width,
                height = packed.height,
                intrinsics = floatArrayOf(r[0], r[1], r[2], r[3]),
                cameraFromDevice = cameraFromDisplay.copyOf(),
                attitude = attitude.latestSample(),
                headingDeg = snapshot?.headingDeg,
                elevationDeg = snapshot?.elevationDeg,
                rollDeg = roll,
                convertNs = convertNs,
                luma = luma,
            )
            lastFrameNs = record.timestampNs
            recorder()?.frame(record)
            if (latest.getAndSet(LiveFrame(record, snapshot)) != null) {
                synchronized(driver.metrics) { driver.metrics.onDropped(1) }
            }
            if (draining.compareAndSet(false, true)) trackingExecutor.execute(::drain)
        } catch (t: Throwable) {
            log("analyzer: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            image.close()
        }
    }

    // --- stage 2: the tracking thread -----------------------------------------------------------

    private fun drain() {
        while (true) {
            val f = latest.getAndSet(null)
            if (f == null) {
                draining.set(false)
                // A frame that arrived between the read and the flag: take it now.
                if (latest.get() != null && draining.compareAndSet(false, true)) continue
                return
            }
            if (closed) continue
            try {
                process(f)
            } catch (t: Throwable) {
                log("tracking: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun process(f: LiveFrame) {
        val frame = f.record
        val snapshot = f.snapshot
        val t0 = SystemClock.elapsedRealtimeNanos()

        pendingReference?.let { ref ->
            pendingReference = null
            synchronized(driver.metrics) { driver.capture(ref) }
            reference = ref
            failures.onCapture(ref.checkId)
            log("target carried over (${ref.checkId})")
        }
        captureFor?.let { checkId ->
            captureFor = null
            val capture = SessionDriver.crop(frame, checkId)
            failures.onCapture(checkId)
            mutate { checklist.onCapture(SystemClock.elapsedRealtime(), snapshot?.elevationDeg, checkId) }
            synchronized(driver.metrics) { driver.capture(capture) }
            reference = capture
            recorder()?.event(RecordEvent.Capture(capture))
            log("captured ${capture.side}x${capture.side} target")
        }

        val step = synchronized(driver.metrics) {
            // Frame time as replay measures it too: packing plus the session's work (not the wait in line).
            driver.step(frame) { (frame.convertNs + SystemClock.elapsedRealtimeNanos() - t0) / 1e6f }
        }
        val result = step.result
        val pose = result.pose
        val nowMs = SystemClock.elapsedRealtime()
        val frameMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
        mutate {
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
                    frameMs = frameMs,
                ),
            )
        }
        onView(
            TrackedView(
                cameraFromFingerprint = step.cameraFromFingerprint,
                projection = pose?.projection ?: result.projection,
                frameWidth = frame.width,
                frameHeight = frame.height,
                pageAspect = driver.pageAspect,
                telemetry = telemetry(result),
                estimated = pose?.source != PhotosphereTrackingSession.PoseSource.KPM,
                state = result.state.name,
                elevationDeg = snapshot?.elevationDeg,
            ),
        )
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

    private fun telemetry(result: PhotosphereTrackingSession.FrameResult): String {
        val p = result.pose
        return buildString {
            if (p != null) {
                append(p.source)
                // Match time and inliers belong to a visual match; an estimate has neither.
                if (p.source == PhotosphereTrackingSession.PoseSource.KPM) {
                    append(' ').append(p.matchMethod)
                    append(" · ").append(Checklist.fmt1(p.matchDurationMs)).append(" ms · ").append(p.inlierCount).append(" inliers")
                }
            }
        }
    }

    /** Stops taking frames and closes the session on its own thread, after any frame in progress. */
    override fun close() {
        closed = true
        trackingExecutor.execute { runCatching { driver.close() } }
        trackingExecutor.shutdown()
    }
}

/**
 * Keeps the run's log readable within its line limit: the session reports every transient miss
 * (`severity=TRANSIENT`, several a second while searching) as its own line, which pushed a step's
 * history out of the log in seconds. Those are counted by reason instead and added to the next
 * periodic `timing{…}` line as `transient{REASON=n …}`; every other line passes through.
 */
class DiagnosticFilter {
    private val transient = linkedMapOf<String, Int>()

    /** The line to log, or null when it was only counted. */
    fun filter(line: String): String? {
        if ("severity=TRANSIENT" in line) {
            val reason = REASON_RE.find(line)?.groupValues?.get(1) ?: "OTHER"
            transient[reason] = (transient[reason] ?: 0) + 1
            return null
        }
        if ("timing{" !in line || transient.isEmpty()) return line
        val counts = transient.entries.joinToString(" ") { (reason, n) -> "$reason=$n" }
        transient.clear()
        return "$line transient{$counts}"
    }

    private companion object {
        val REASON_RE = Regex("""failure=([A-Z_]+)""")
    }
}

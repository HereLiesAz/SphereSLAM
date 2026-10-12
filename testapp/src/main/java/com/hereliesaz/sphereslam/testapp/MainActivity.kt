package com.hereliesaz.sphereslam.testapp

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.hereliesaz.sphereslam.CameraAttitudeProvider
import com.hereliesaz.sphereslam.SphereSlam
import com.hereliesaz.sphereslam.camera.CameraIntrinsicsEstimator
import com.hereliesaz.sphereslam.camera.LensDistortion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SphereSLAM on-device test bench (docs/TESTAPP.md). The [Guide] walks the tester through one step at
 * a time; [Tracker] runs the library on the camera and feeds the [Checklist], which grades each step
 * from telemetry (or takes the tester's verdict where only a person can judge); [TestScreen] shows
 * the step, its live progress and the result; [Reporter] files every change to the repository's
 * issues.
 */
@ExperimentalCamera2Interop
class MainActivity : ComponentActivity() {

    private val checklist = Checklist()
    private val guide = Guide()
    private val logLines = ArrayDeque<String>()
    private lateinit var attitude: CameraAttitudeProvider
    private lateinit var reporter: Reporter
    private lateinit var analysisExecutor: ExecutorService
    private var tracker: Tracker? = null

    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var screen: TestScreen

    @Volatile private var lastView: TrackedView? = null
    @Volatile private var notesSnapshot = ""
    @Volatile private var reportState = "Report: nothing sent yet"
    private var cameraProblem: String? = null
    private var analysis: ImageAnalysis? = null
    private var cameraId: String? = null
    private var realtimeTimestamps = false
    private var lastLockedMs = Long.MIN_VALUE
    /** Shown on the card for a while after a launch that followed a crash. */
    private var crashNotice: String? = null
    private var crashNoticeUntilMs = 0L
    private var decidedStep = -1
    private var decidedAtMs = 0L
    private var shownStep = -1

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, TICK_MS)
        }
    }

    private val runInfo by lazy {
        RunInfo(
            runId = UUID.randomUUID().toString(),
            build = BuildConfig.SPHERESLAM_BUILD,
            libraryVersion = BuildConfig.SPHERESLAM_VERSION,
            appVersion = BuildConfig.VERSION_NAME,
            packageName = packageName,
            brand = Build.MANUFACTURER,
            model = Build.MODEL,
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            startedAt = Instant.now().toString(),
        )
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else cameraProblem = "Camera permission was denied. Grant it in Settings ▸ Apps ▸ SphereSLAM Test ▸ Permissions, then reopen the app."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        attitude = CameraAttitudeProvider(this)
        analysisExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "sphereslam-test-analysis") }
        reporter = Reporter(BuildConfig.REPORT_URL, BuildConfig.REPORT_KEY, ::reportBody) { ok, detail ->
            reportState = if (ok) "Report sent ${CLOCK.format(Instant.now())}" else "Report not sent ($detail); retrying on the next change"
        }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        overlay = OverlayView(this)
        val camera = FrameLayout(this)
        camera.addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        camera.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        screen = TestScreen(this, camera)
        screen.onJump = { i ->
            guide.goTo(i, now())
            screen.showOverview(false)
            render()
        }
        screen.onNotes = { text ->
            notesSnapshot = text
            reporter.schedule(NOTES_DEBOUNCE_MS)
        }
        setContentView(screen.root)
        guide.start(now())
        CrashLog.takePrevious(this)?.let { trace ->
            log("previous run crashed:\n$trace")
            crashNotice = "The app crashed last time. The stack trace is in this run's report, so it reaches the issue."
            crashNoticeUntilMs = now() + CRASH_NOTICE_MS
            reporter.schedule()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        attitude.start(android.hardware.SensorManager.SENSOR_DELAY_GAME)
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        attitude.stop()
        super.onPause()
    }

    override fun onStop() {
        reporter.flush()
        super.onStop()
    }

    override fun onDestroy() {
        tracker?.let { t -> analysisExecutor.execute { t.close() } }
        analysisExecutor.shutdown()
        reporter.close()
        super.onDestroy()
    }

    private fun now() = SystemClock.elapsedRealtime()

    // --- the guided run -------------------------------------------------------------------------

    private fun render() {
        val now = now()
        screen.layoutCheck()?.let { (clear, evidence) -> mutate { checklist.onLayout(clear, evidence) } }

        val statuses = synchronized(checklist) { guide.steps.map { guide.statusOf(it, checklist) } }
        val n = guide.steps.size
        val step = guide.current
        screen.header(
            // The build is always on screen: which APK is running is the first question about any report.
            stepLabel = (if (step == null) "Run complete" else "Step ${guide.index + 1} of $n") + " · " + BuildConfig.SPHERESLAM_BUILD,
            statuses = statuses,
            current = guide.index,
            report = reportState,
        )
        val v = lastView
        val tracking = trackingStatus(now)
        val (label, tone) = if (cameraProblem != null) "Camera unavailable" to Status.FAIL else Guide.describeState(tracking)
        screen.status(label, tone, v?.telemetry.orEmpty(), v?.elevationDeg?.let { Guide.describeElevation(it) }.orEmpty())
        if (screen.overviewOpen) {
            overlay.showCaptureGuide = false
            renderOverview()
            screen.actions(primary = Action("Back to step") { screen.showOverview(false); render() })
            return
        }
        if (step == null) {
            overlay.showCaptureGuide = false
            return renderFinished(statuses)
        }
        if (shownStep != guide.index) {
            shownStep = guide.index
            log("step ${guide.index + 1}: ${step.title}")
        }

        val status = statuses[guide.index]
        if (status == Status.UNTESTED) decidedStep = -1
        else if (decidedStep != guide.index) { decidedStep = guide.index; decidedAtMs = now }
        overlay.showCaptureGuide = step.kind == StepKind.CAPTURE && status == Status.UNTESTED

        val progress = synchronized(checklist) { progressLine(step, now) }
        val hint = synchronized(checklist) { if (guide.showHint(checklist, now)) step.hint else null }
        val result = synchronized(checklist) { resultLine(step, status) }
        val coach = cameraProblem?.let { Coach(it, blocking = true) }
            ?: synchronized(checklist) { guide.coach(step, checklist, tracking, now) }
        val notice = crashNotice?.takeIf { now < crashNoticeUntilMs }
        screen.card(
            title = step.title,
            body = step.instruction,
            progressLine = listOfNotNull(progress, hint).joinToString("\n").ifEmpty { null },
            result = result,
            resultTone = Palette.of(status).takeIf { status != Status.UNTESTED } ?: Palette.TEXT,
            coach = listOfNotNull(notice, coach?.message).joinToString("\n\n").ifEmpty { null },
            coachBlocking = coach?.blocking == true || notice != null,
        )

        val skip = Action("Skip") { mutate { guide.skip(checklist, now()) }; render() }
        val fix = coach?.action
        when {
            status == Status.UNTESTED && fix != null -> screen.actions(
                primary = Action(fix.label) { apply(fix, step) },
                tertiary = skip,
            )
            status == Status.UNTESTED -> when (step.kind) {
                StepKind.CAPTURE -> {
                    val surface = step.surface!!
                    val aim = surface.aimHint(v?.elevationDeg)
                    val capturing = synchronized(checklist) { checklist.msSincePendingCapture(now) != null }
                    screen.actions(
                        primary = Action(if (capturing) "Capture again" else "Capture", enabled = aim == null && tracker != null) {
                            tracker?.requestCapture(surface.checkId)
                            log("capture for ${surface.checkId}")
                        },
                        tertiary = skip,
                    )
                }
                StepKind.AUTO -> screen.actions(primary = null, tertiary = skip)
                StepKind.MANUAL -> {
                    val ready = synchronized(checklist) { guide.manualWait(step, checklist, now) } == null
                    screen.actions(
                        primary = Action("Yes, it did", enabled = ready) { verdict(step, Status.PASS) },
                        secondary = Action("No", enabled = ready) { verdict(step, Status.FAIL) },
                        tertiary = skip,
                    )
                }
            }
            else -> when (status) {
                Status.PASS, Status.SKIPPED -> {
                    screen.actions(primary = Action("Next") { advance() })
                    if (status == Status.PASS && now - decidedAtMs >= AUTO_ADVANCE_MS) advance()
                }
                Status.FAIL -> screen.actions(
                    primary = Action("Continue") { advance() },
                    secondary = Action("Retry") { mutate { guide.retry(checklist, now()) }; render() },
                )
                else -> Unit
            }
        }
    }

    /** What the tracker is doing, for the status line and the coach. */
    private fun trackingStatus(now: Long): TrackingStatus {
        val t = tracker
        val state = lastView?.state
        if (state == "LOCKED" || state == "IMU_BRIDGE") lastLockedMs = now
        val problem = t?.lastProblem
        return TrackingStatus(
            state = state,
            fatal = t?.fatal,
            problem = problem?.first,
            problemAgeMs = problem?.let { now - it.second } ?: Long.MAX_VALUE,
            msSinceLocked = if (lastLockedMs == Long.MIN_VALUE) null else now - lastLockedMs,
        )
    }

    private fun apply(action: CoachAction, step: Step) {
        when (action) {
            CoachAction.RESTART_TRACKING -> restartTracker()
            CoachAction.CAPTURE_TARGET -> {
                val surface = step.needs ?: step.surface ?: Surface.WALL
                val at = guide.steps.indexOfFirst { it.kind == StepKind.CAPTURE && it.surface == surface }
                mutate { checklist.reset(guide.steps[at].checks) }
                log("back to capture a ${surface.word} target from step ${guide.index + 1}")
                guide.goTo(at, now())
            }
        }
        render()
    }

    private fun advance() {
        guide.next(now())
        render()
    }

    private fun verdict(step: Step, status: Status) {
        mutate { for (id in step.checks) checklist.set(id, status, "tester: " + if (status == Status.PASS) "yes" else "no") }
        if (status == Status.PASS) advance() else render()
    }

    /** What the tester sees under the instruction while the step is undecided. */
    private fun progressLine(step: Step, now: Long): String? {
        if (guide.statusOf(step, checklist) != Status.UNTESTED) return null
        return when (step.kind) {
            StepKind.CAPTURE -> {
                val surface = step.surface!!
                val p = checklist.progress(surface.checkId, now)
                if (p == null || p.startsWith("Waiting")) surface.aimHint(lastView?.elevationDeg) ?: "Aimed correctly. Hold still and tap Capture."
                else p
            }
            StepKind.MANUAL -> guide.manualWait(step, checklist, now) ?: "Watch closely, then answer below."
            StepKind.AUTO -> step.checks.mapNotNull { checklist.progress(it, now) }.joinToString("\n").ifEmpty { null }
        }
    }

    private fun resultLine(step: Step, status: Status): CharSequence? {
        if (status == Status.UNTESTED) return null
        val head = when (status) {
            Status.PASS -> "Passed"
            Status.FAIL -> "Failed"
            else -> "Skipped"
        }
        return screen.verdict(head, status, step.checks.map { checklist[it] }.map { c -> "${c.title}: ${c.evidence}" to c.status })
    }

    private fun renderFinished(statuses: List<Status>) {
        val passed = statuses.count { it == Status.PASS }
        val failed = statuses.count { it == Status.FAIL }
        val skipped = statuses.count { it == Status.SKIPPED }
        val open = statuses.count { it == Status.UNTESTED }
        screen.card(
            title = "Run complete",
            body = "$passed passed, $failed failed, $skipped skipped" + (if (open > 0) ", $open not finished" else "") +
                ". The report is filed automatically; add notes below if anything looked wrong.",
            progressLine = null,
            result = reportState,
            resultTone = Palette.MUTED,
            showNotes = true,
        )
        screen.actions(
            primary = Action("Start over") {
                mutate { checklist.reset(guide.steps.drop(1).flatMap { it.checks }) }
                guide.goTo(1, now())
                render()
            },
            secondary = Action("Review steps") { screen.showOverview(true); render() },
        )
        if (shownStep != guide.steps.size) {
            shownStep = guide.steps.size
            log("run complete: $passed passed, $failed failed, $skipped skipped")
            reporter.flush()
        }
    }

    private fun renderOverview() {
        val rows = synchronized(checklist) {
            guide.steps.map { s ->
                Triple(s.title, guide.statusOf(s, checklist), s.checks.map { id ->
                    val c = checklist[id]
                    screen.mark(c.title + (if (c.evidence.isNotEmpty()) ": ${c.evidence}" else ""), c.status)
                })
            }
        }
        screen.overview(rows, guide.index)
    }

    /** Checklist writes hold its monitor (the analysis thread writes too) and schedule a report. */
    private inline fun mutate(block: () -> Unit) {
        val changed = synchronized(checklist) {
            val before = checklist.changes
            block()
            checklist.changes != before
        }
        if (changed) reporter.schedule()
    }

    // --- camera ---------------------------------------------------------------------------------

    private fun startCamera() {
        val cameraId = backCameraId() ?: run { cameraProblem = "This device has no back camera."; return }
        val characteristics = getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        val lens = LensDistortion.read(characteristics)
        val correction = lens.trackingCorrectionMode
        val realtime = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val nominal = CameraIntrinsicsEstimator.estimate(this, cameraId, 640, 480)
        mutate {
            checklist.onLens(
                mode = correction?.let { correctionName(it) },
                residualPx = nominal?.let { LensDistortion.maxResidualPx(lens.coefficients, it) },
                description = LensDistortion.describe(lens, correction, nominal),
            )
        }
        log("build ${BuildConfig.SPHERESLAM_BUILD} (versionCode ${BuildConfig.VERSION_CODE})")
        log("camera $cameraId realtime=$realtime lens=${synchronized(checklist) { checklist["lens_correction"].evidence }}")
        this.cameraId = cameraId
        realtimeTimestamps = realtime
        // The engine the session will need, probed up front so a missing native library fails the
        // device check instead of the first capture.
        analysisExecutor.execute {
            val ok = runCatching { SphereSlam.isOperational() }.getOrDefault(false)
            val evidence = if (ok) "libsphereslam loaded, KPM engine created"
            else "SphereSlam.isOperational() is false: libsphereslam not loadable on ${Build.SUPPORTED_ABIS.firstOrNull()} or built without KPM"
            log("native engine: $evidence")
            mutate { checklist.onNativeEngine(ok, evidence) }
        }
        val t = newTracker(cameraId, realtime)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = CameraSelector.Builder()
                .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId } }
                .build()
            val previewBuilder = Preview.Builder()
            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            if (correction != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(CaptureRequest.DISTORTION_CORRECTION_MODE, correction)
                Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(CaptureRequest.DISTORTION_CORRECTION_MODE, correction)
            }
            val previewUseCase = previewBuilder.build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = analysisBuilder.build().also { it.setAnalyzer(analysisExecutor, t) }
            this.analysis = analysis
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, previewUseCase, analysis)
            reporter.schedule()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun newTracker(cameraId: String, realtime: Boolean): Tracker =
        Tracker(this, cameraId, realtime, attitude, checklist, ::log,
            onView = { v -> lastView = v; overlay.show(v) },
            onChecklistChanged = { reporter.schedule() },
        ).also { tracker = it }

    /** A fresh session on the same camera; the old one is closed on the analysis thread it ran on. */
    private fun restartTracker() {
        val id = cameraId ?: return
        val old = tracker
        val fresh = newTracker(id, realtimeTimestamps)
        lastView = null
        lastLockedMs = Long.MIN_VALUE
        analysis?.setAnalyzer(analysisExecutor, fresh)
        old?.let { analysisExecutor.execute { it.close() } }
        log("tracking restarted")
    }

    private fun backCameraId(): String? {
        val manager = getSystemService(CameraManager::class.java)
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    private fun correctionName(mode: Int) = when (mode) {
        CameraMetadata.DISTORTION_CORRECTION_MODE_FAST -> "FAST"
        CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        else -> "mode$mode"
    }

    // --- report ---------------------------------------------------------------------------------

    private fun reportBody(): String {
        val log = synchronized(logLines) { logLines.joinToString("\n") }
        return synchronized(checklist) {
            ReportJson.build(runInfo, checklist.checks, Instant.now().toString(), notesSnapshot, log)
        }
    }

    private fun log(line: String) {
        Log.d("SphereSLAM-Test", line)
        synchronized(logLines) {
            logLines.addLast("${Instant.now()} $line")
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        }
    }

    private companion object {
        const val MAX_LOG_LINES = 200
        const val NOTES_DEBOUNCE_MS = 5_000L
        const val TICK_MS = 200L
        /** How long a passed step's result stays up before the next step. */
        const val AUTO_ADVANCE_MS = 1_800L
        const val CRASH_NOTICE_MS = 20_000L
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}

package com.hereliesaz.sphereslam.testapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.camera.CameraIntrinsicsEstimator
import com.hereliesaz.sphereslam.camera.LensDistortion
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import android.view.Surface as ViewSurface

/**
 * SphereSLAM on-device test bench (docs/TESTAPP.md). The [Guide] walks the tester through one step at
 * a time; [Tracker] runs the library on the camera and feeds the [Checklist], which grades each step
 * from telemetry (or takes the tester's verdict where only a person can judge); [TestScreen] shows
 * the step, its live progress, what is in the way and the result; [Reporter] files every change to
 * the repository's issues. The run itself lives in [TestApp.run], so a recreated activity carries on
 * with it.
 */
@ExperimentalCamera2Interop
class MainActivity : ComponentActivity() {

    private val app get() = application as TestApp
    private val run: Run get() = app.run
    private val checklist get() = run.checklist
    private val guide get() = run.guide

    private lateinit var attitude: CameraAttitudeProvider
    private lateinit var reporter: Reporter
    private lateinit var analysisExecutor: ExecutorService
    private var tracker: Tracker? = null

    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var screen: TestScreen

    @Volatile private var lastView: TrackedView? = null
    @Volatile private var lastFrameAtMs = Long.MIN_VALUE
    @Volatile private var reportState = "Report: nothing sent yet"

    // Camera.
    private var cameraId: String? = null
    private var realtimeTimestamps = false
    private var analysis: ImageAnalysis? = null
    private var cameraStarted = false
    private var cameraBoundAtMs = Long.MIN_VALUE
    private var cameraStartError: String? = null
    /** Whether the tester has answered the permission dialog; until then it can always be asked. */
    private var askedForCamera = false
    /** The lens probe's result (mode, residual, evidence), replayed on Retry and Start over. */
    private var lensResult: Triple<String?, Float?, String>? = null

    // The run on screen.
    private var lastLockedMs = Long.MIN_VALUE
    /** Shown on the card for a while after a launch that followed a crash. */
    private var crashNotice: String? = null
    private var crashNoticeUntilMs = 0L
    private var stepShown = -1
    private var stepShownAtMs = 0L
    private var verdictShown: Status? = null
    private var verdictShownAtMs = 0L
    /** Unblocked time on the current step, for its [Step.timeoutMs]. */
    private var stepActiveMs = 0L
    private var lastTickMs = Long.MIN_VALUE
    /** The step the tester jumped to from the overview: it doesn't auto-advance. */
    private var jumpedTo = -1
    /** The window geometry the layout check last measured; null re-measures. */
    private var layoutKey: String? = null

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, TICK_MS)
        }
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedForCamera = true
        if (granted) startCamera()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light icons on the app's dark bars, whatever the system theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        attitude = CameraAttitudeProvider(this)
        analysisExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "sphereslam-test-analysis") }
        CrashLog.endpoint = BuildConfig.REPORT_URL to BuildConfig.REPORT_KEY
        reporter = Reporter(BuildConfig.REPORT_URL, BuildConfig.REPORT_KEY, filesDir, { stamp -> run.reportBody(stamp) }) { ok, detail ->
            reportState = if (ok) "Report sent ${CLOCK.format(Instant.now())}" else "Report not sent ($detail); retrying"
        }

        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        overlay = OverlayView(this)
        val camera = FrameLayout(this)
        camera.addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        camera.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        screen = TestScreen(this, camera)
        screen.onJump = { i ->
            guide.goTo(i, now())
            jumpedTo = i
            screen.showOverview(false)
            render()
        }
        screen.onNotes = { text ->
            run.notes = text
            reporter.schedule(NOTES_DEBOUNCE_MS)
        }
        setContentView(screen.root)

        if (!app.launchHandled) {
            app.launchHandled = true
            handleLaunch()
        }
        probeApk()
        if (hasCameraPermission()) startCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    /** Once per process: start the run, and deliver what the previous process left behind. */
    private fun handleLaunch() {
        guide.start(now())
        run.log("build ${BuildConfig.SPHERESLAM_BUILD} (versionCode ${BuildConfig.VERSION_CODE})")
        app.openCvError?.let { run.log("OpenCV not loaded: $it (flow tracking and tile localization are off)") }
        val javaCrash = Reporter.hasPending(filesDir) && Reporter.lastBody(filesDir)?.contains("THE APP CRASHED") == true
        val exit = CrashLog.recoverExit(this)
        exit?.let { run.log(it) }
        if (javaCrash || exit != null) {
            crashNotice = "The app stopped last time. What it left is in that run's report, so it reaches the issue."
            crashNoticeUntilMs = now() + CRASH_NOTICE_MS
        }
        Reporter.resendPending(filesDir, BuildConfig.REPORT_URL, BuildConfig.REPORT_KEY)
    }

    override fun onResume() {
        super.onResume()
        attitude.start(SensorManager.SENSOR_DELAY_GAME)
        // Back from Settings with the camera now allowed: start without a relaunch.
        if (!cameraStarted && hasCameraPermission()) startCamera()
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

    /** Rotation (large screens ignore the portrait lock), folding, resizing: keep the run, re-aim the camera. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDisplayRotation()
        layoutKey = null
    }

    private fun now() = SystemClock.elapsedRealtime()

    // --- the guided run -------------------------------------------------------------------------

    private fun render() {
        val now = now()
        val dt = if (lastTickMs == Long.MIN_VALUE) 0L else (now - lastTickMs).coerceIn(0L, MAX_TICK_GAP_MS)
        lastTickMs = now
        checkLayout()
        mutate { checklist.onAttitude(attitude.isAvailable, attitude.latestSample()?.accuracy?.takeIf { it != AttitudeSample.ACCURACY_UNKNOWN }) }

        val statuses = synchronized(checklist) { guide.steps.map { guide.statusOf(it, checklist) } }
        val step = guide.current
        screen.header(
            // The build is always on screen: which APK is running is the first question about any report.
            stepLabel = (if (step == null) "Run complete" else "Step ${guide.index + 1} of ${guide.steps.size}") + " · " + BuildConfig.SPHERESLAM_BUILD,
            statuses = statuses,
            current = guide.index,
            report = reportState,
        )
        val v = lastView
        val tracking = trackingStatus(now)
        val camera = cameraCoach(now)
        val (label, tone) = if (camera != null) "Camera unavailable" to Status.FAIL else Guide.describeState(tracking)
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
        val status = statuses[guide.index]
        if (stepShown != guide.index) {
            stepShown = guide.index
            stepShownAtMs = now
            stepActiveMs = 0L
            verdictShown = status
            verdictShownAtMs = now
            if (jumpedTo != guide.index) jumpedTo = -1
            run.log("step ${guide.index + 1}: ${step.title}")
        }
        if (verdictShown != status) {
            verdictShown = status
            verdictShownAtMs = now
        }
        overlay.showCaptureGuide = step.kind == StepKind.CAPTURE && status == Status.UNTESTED

        val coach = camera ?: synchronized(checklist) { guide.coach(step, checklist, tracking, now) }
        applyTimeout(step, status, coach, dt)

        val progress = synchronized(checklist) { progressLine(step, now) }
        val hint = synchronized(checklist) { if (guide.showHint(checklist, now)) step.hint else null }
        val result = synchronized(checklist) { resultLine(step, status) }
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

        // Buttons that changed under the tester's finger (a new step, a new verdict) ignore taps for a
        // moment, so a tap meant for the old ones can't land on the new ones.
        val settled = now - stepShownAtMs >= TAP_GUARD_MS && now - verdictShownAtMs >= TAP_GUARD_MS
        val skip = Action("Skip", enabled = settled) { mutate { guide.skip(checklist, now()) }; render() }
        val again = Action(if (status == Status.FAIL) "Retry" else "Run again", enabled = settled) { retry(step) }
        val fix = coach?.action
        when {
            fix != null && (status == Status.UNTESTED || camera != null) -> screen.actions(
                primary = Action(fix.label, enabled = settled || camera != null) { apply(fix, step) },
                tertiary = skip.takeIf { status == Status.UNTESTED },
            )
            status == Status.UNTESTED -> when (step.kind) {
                StepKind.CAPTURE -> {
                    val surface = step.surface!!
                    val aim = surface.aimHint(v?.elevationDeg)
                    val capturing = synchronized(checklist) { checklist.msSincePendingCapture(now) != null }
                    screen.actions(
                        primary = Action(if (capturing) "Capture again" else "Capture", enabled = settled && aim == null && tracker != null) {
                            tracker?.requestCapture(surface.checkId)
                            run.log("capture for ${surface.checkId}")
                        },
                        tertiary = skip,
                    )
                }
                StepKind.AUTO -> screen.actions(primary = null, tertiary = skip)
                StepKind.MANUAL -> {
                    val ready = settled && synchronized(checklist) { guide.manualWait(step, checklist, now) } == null
                    screen.actions(
                        primary = Action("Yes, it did", enabled = ready) { verdict(step, Status.PASS) },
                        secondary = Action("No", enabled = ready) { verdict(step, Status.FAIL) },
                        tertiary = skip,
                    )
                }
            }
            status == Status.FAIL -> screen.actions(primary = Action("Continue", enabled = settled) { advance() }, secondary = again)
            else -> {
                screen.actions(primary = Action("Next", enabled = settled) { advance() }, secondary = again)
                if (status == Status.PASS && jumpedTo != guide.index && now - verdictShownAtMs >= AUTO_ADVANCE_MS) advance()
            }
        }
    }

    /**
     * Counts the time the step runs unblocked (no coach; a capture step only once its lock is
     * decided) and fails its undecided checks when that reaches [Step.timeoutMs]: telemetry that can
     * only pass a check would otherwise leave a broken feature waiting forever.
     */
    private fun applyTimeout(step: Step, status: Status, coach: Coach?, dt: Long) {
        val limit = step.timeoutMs ?: return
        if (status != Status.UNTESTED || coach != null) return
        val lockCheck = step.surface?.checkId
        if (step.kind == StepKind.CAPTURE && lockCheck != null &&
            synchronized(checklist) { checklist[lockCheck].status } == Status.UNTESTED) return
        stepActiveMs += dt
        if (stepActiveMs < limit) return
        val now = now()
        mutate {
            for (id in step.checks) {
                if (checklist[id].status != Status.UNTESTED) continue
                val seen = checklist.progress(id, now)?.let { " ($it)" }.orEmpty()
                checklist.timeOut(id, "not seen in ${limit / 1000} s of unblocked tracking$seen")
            }
        }
        run.log("step ${guide.index + 1} timed out after ${limit / 1000} s")
    }

    private fun advance() {
        guide.next(now())
        render()
    }

    /** The step again from scratch; checks decided once at start-up are decided again, not left waiting. */
    private fun retry(step: Step) {
        mutate { guide.retry(checklist, now()) }
        if ("native_packaged" in step.checks) probeApk()
        if ("lens_correction" in step.checks) replayLens()
        if ("layout_clear" in step.checks) layoutKey = null
        stepActiveMs = 0L
        jumpedTo = guide.index
        run.log("step ${guide.index + 1} run again")
        render()
    }

    private fun verdict(step: Step, status: Status) {
        mutate { for (id in step.checks) checklist.set(id, status, "tester: " + if (status == Status.PASS) "yes" else "no") }
        if (status == Status.PASS) advance() else render()
    }

    /** What the tracker is doing, for the status line and the coach. */
    private fun trackingStatus(now: Long): TrackingStatus {
        val failures = tracker?.failures
        val state = lastView?.state
        if (state == "LOCKED" || state == "IMU_BRIDGE") lastLockedMs = now
        val problem = failures?.problem
        return TrackingStatus(
            state = state,
            fatal = failures?.fatal,
            problem = problem?.first,
            problemAgeMs = problem?.let { now - it.second } ?: Long.MAX_VALUE,
            msSinceLocked = if (lastLockedMs == Long.MIN_VALUE) null else now - lastLockedMs,
            reference = failures?.reference,
        )
    }

    private fun cameraCoach(now: Long): Coach? {
        val canAsk = !askedForCamera || shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        val waiting = if (cameraBoundAtMs == Long.MIN_VALUE) null else now - maxOf(cameraBoundAtMs, lastFrameAtMs)
        return Guide.cameraCoach(hasCameraPermission(), canAsk, cameraStartError, waiting)
    }

    private fun apply(action: CoachAction, step: Step) {
        when (action) {
            CoachAction.RESTART_TRACKING -> restartTracker()
            CoachAction.CAPTURE_TARGET -> {
                val surface = step.needs ?: step.surface ?: Surface.WALL
                val at = guide.steps.indexOfFirst { it.kind == StepKind.CAPTURE && it.surface == surface }
                mutate { checklist.reset(guide.steps[at].checks) }
                run.log("back to capture a ${surface.word} target from step ${guide.index + 1}")
                guide.goTo(at, now())
            }
            CoachAction.GRANT_CAMERA -> permission.launch(Manifest.permission.CAMERA)
            CoachAction.OPEN_SETTINGS -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
            CoachAction.RESTART_CAMERA -> {
                cameraStarted = false
                cameraBoundAtMs = Long.MIN_VALUE
                startCamera()
            }
        }
        render()
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
            primary = Action("Start over") { startOver() },
            secondary = Action("Review steps") { screen.showOverview(true); render() },
        )
        if (stepShown != guide.steps.size) {
            stepShown = guide.steps.size
            run.log("run complete: $passed passed, $failed failed, $skipped skipped")
            reporter.flush()
        }
    }

    /** A new run with its own report; the finished one stays as it was filed. */
    private fun startOver() {
        reporter.flush()
        app.run = Run.start(this)
        guide.start(now())
        run.log("build ${BuildConfig.SPHERESLAM_BUILD} (versionCode ${BuildConfig.VERSION_CODE}); started over")
        stepShown = -1
        jumpedTo = -1
        layoutKey = null
        probeApk()
        replayLens()
        restartTracker()
        render()
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

    /** Measured again only when the window or its insets change, against the platform's own insets. */
    private fun checkLayout() {
        val bands = screen.platformBands() ?: return
        val root = screen.root
        val key = "$bands ${root.width}x${root.height} ${root.paddingLeft},${root.paddingTop},${root.paddingRight},${root.paddingBottom}"
        if (key == layoutKey) return
        val (clear, evidence) = screen.layoutCheck(bands) ?: return
        layoutKey = key
        mutate { checklist.onLayout(clear, evidence) }
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

    // --- probes and camera ----------------------------------------------------------------------

    /** Whether the APK carries libsphereslam for this device's ABI, without loading it. */
    private fun probeApk() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val inApk = runCatching { ZipFile(applicationInfo.sourceDir).use { it.getEntry("lib/$abi/libsphereslam.so") != null } }.getOrDefault(false)
        val extracted = File(applicationInfo.nativeLibraryDir, "libsphereslam.so").exists()
        val present = inApk || extracted
        mutate {
            checklist.onNativePackaged(present, if (present) "lib/$abi/libsphereslam.so present" else "no libsphereslam.so for $abi in the APK")
        }
    }

    private fun replayLens() {
        lensResult?.let { (mode, residual, text) -> mutate { checklist.onLens(mode, residual, text) } }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        cameraStartError = null
        try {
            val id = backCameraId() ?: throw IllegalStateException("this device has no back camera")
            val characteristics = getSystemService(CameraManager::class.java).getCameraCharacteristics(id)
            val lens = LensDistortion.read(characteristics)
            val correction = lens.trackingCorrectionMode
            val realtime = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            val nominal = CameraIntrinsicsEstimator.estimate(this, id, 640, 480)
            lensResult = Triple(
                correction?.let { correctionName(it) },
                nominal?.let { LensDistortion.maxResidualPx(lens.coefficients, it) },
                LensDistortion.describe(lens, correction, nominal),
            )
            replayLens()
            run.log("camera $id realtime=$realtime lens=${lensResult?.third}")
            cameraId = id
            realtimeTimestamps = realtime
            val t = tracker ?: newTracker(id, realtime)

            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    val selector = CameraSelector.Builder()
                        .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
                        .build()
                    val previewBuilder = Preview.Builder()
                    val analysisBuilder = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(displayRotation())
                    if (correction != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(CaptureRequest.DISTORTION_CORRECTION_MODE, correction)
                        Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(CaptureRequest.DISTORTION_CORRECTION_MODE, correction)
                    }
                    val previewUseCase = previewBuilder.build().also { it.surfaceProvider = preview.surfaceProvider }
                    val useCase = analysisBuilder.build().also { it.setAnalyzer(analysisExecutor, tracker ?: t) }
                    analysis = useCase
                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, previewUseCase, useCase)
                    applyDisplayRotation()
                    cameraBoundAtMs = now()
                    reporter.schedule()
                } catch (e: Exception) {
                    cameraFailed(e)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            cameraFailed(e)
        }
    }

    private fun cameraFailed(e: Exception) {
        cameraStartError = "${e.javaClass.simpleName}: ${e.message}"
        run.log("camera start failed: $cameraStartError")
    }

    private fun newTracker(cameraId: String, realtime: Boolean): Tracker =
        Tracker(this, cameraId, realtime, attitude, checklist, run::log,
            onView = { v -> lastView = v; lastFrameAtMs = now(); overlay.show(v) },
            onChecklistChanged = { reporter.schedule() },
        ).also {
            it.displayRotationDeg = displayRotationDegrees()
            tracker = it
        }

    /** A fresh session on the same camera; the old one is closed on the analysis thread it ran on. */
    private fun restartTracker() {
        val id = cameraId ?: return
        val old = tracker
        val fresh = newTracker(id, realtimeTimestamps)
        lastView = null
        lastLockedMs = Long.MIN_VALUE
        analysis?.setAnalyzer(analysisExecutor, fresh)
        old?.let { if (!analysisExecutor.isShutdown) analysisExecutor.execute { it.close() } }
        run.log("tracking restarted")
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation else windowManager.defaultDisplay.rotation)
            ?: ViewSurface.ROTATION_0

    private fun displayRotationDegrees(): Int = when (displayRotation()) {
        ViewSurface.ROTATION_90 -> 90
        ViewSurface.ROTATION_180 -> 180
        ViewSurface.ROTATION_270 -> 270
        else -> 0
    }

    private fun applyDisplayRotation() {
        analysis?.targetRotation = displayRotation()
        tracker?.displayRotationDeg = displayRotationDegrees()
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

    internal companion object {
        const val NOTES_DEBOUNCE_MS = 5_000L
        const val TICK_MS = 200L
        /** A longer gap between ticks (paused, backgrounded) doesn't count toward a step's timeout. */
        const val MAX_TICK_GAP_MS = 1_000L
        /** How long a passed step's result stays up before the next step. */
        const val AUTO_ADVANCE_MS = 1_800L
        /** Taps ignored this long after the buttons change under the finger. */
        const val TAP_GUARD_MS = 700L
        const val CRASH_NOTICE_MS = 20_000L
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}

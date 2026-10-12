package com.hereliesaz.sphereslam.testapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
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
import com.hereliesaz.sphereslam.camera.CameraIntrinsicsEstimator
import com.hereliesaz.sphereslam.camera.LensDistortion
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SphereSLAM on-device test bench (docs/TESTAPP.md): the camera, a captured target tracked by
 * [Tracker], its outline ([OverlayView]), a live HUD, and the [Checklist] — which grades most checks
 * itself and takes the tester's verdict on the visual ones. Every change is reported to the
 * repository's issues ([Reporter]); nothing to copy or paste.
 */
@ExperimentalCamera2Interop
class MainActivity : ComponentActivity() {

    private val checklist = Checklist()
    private val logLines = ArrayDeque<String>()
    private lateinit var attitude: CameraAttitudeProvider
    private lateinit var reporter: Reporter
    private lateinit var analysisExecutor: ExecutorService
    private var tracker: Tracker? = null

    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var hud: TextView
    private lateinit var reportStatus: TextView
    private lateinit var panel: ScrollView
    private lateinit var rows: LinearLayout
    private lateinit var notes: EditText

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
        if (granted) startCamera() else hud.text = "Camera permission is needed."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attitude = CameraAttitudeProvider(this)
        analysisExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "sphereslam-test-analysis") }
        reporter = Reporter(BuildConfig.REPORT_URL, BuildConfig.REPORT_KEY, ::reportBody) { ok, detail ->
            runOnUiThread { reportStatus.text = if (ok) "Reported ${Instant.now()}" else "Report failed: $detail" }
        }
        setContentView(buildLayout())
        renderChecklist()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        attitude.start(android.hardware.SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onPause() {
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

    // --- camera ---------------------------------------------------------------------------------

    private fun startCamera() {
        val cameraId = backCameraId() ?: run { hud.text = "No back camera."; return }
        val characteristics = getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        val lens = LensDistortion.read(characteristics)
        val correction = lens.trackingCorrectionMode
        val realtime = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val nominal = CameraIntrinsicsEstimator.estimate(this, cameraId, 640, 480)
        checklist.onLens(
            mode = correction?.let { correctionName(it) },
            residualPx = nominal?.let { LensDistortion.maxResidualPx(lens.coefficients, it) },
            description = LensDistortion.describe(lens, correction, nominal),
        )
        log("camera $cameraId realtime=$realtime lens=${checklist["lens_correction"].evidence}")

        val t = Tracker(this, cameraId, realtime, attitude, checklist, ::log,
            onView = { v -> overlay.show(v); runOnUiThread { hud.text = v.hud } },
            onChecklistChanged = { runOnUiThread { renderChecklist() }; reporter.schedule() },
        )
        tracker = t

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
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, previewUseCase, analysis)
            reporter.schedule()
        }, ContextCompat.getMainExecutor(this))
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
        val notesText = runCatching { notesSnapshot }.getOrDefault("")
        return synchronized(checklist) {
            ReportJson.build(runInfo, checklist.checks, Instant.now().toString(), notesText, log)
        }
    }

    @Volatile private var notesSnapshot = ""

    private fun log(line: String) {
        Log.d("SphereSLAM-Test", line)
        synchronized(logLines) {
            logLines.addLast("${Instant.now()} $line")
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        }
    }

    // --- UI -------------------------------------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun buildLayout(): View {
        val root = FrameLayout(this)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        overlay = OverlayView(this)
        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(24, 48, 24, 16)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        reportStatus = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 11f
            text = "Not reported yet"
        }
        val capture = button("Capture") { tracker?.requestCapture() }
        val toggle = button("Checklist") { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(24, 16, 24, 32)
            addView(LinearLayout(context).apply {
                addView(capture, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(toggle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
            addView(reportStatus)
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        notes = EditText(this).apply {
            hint = "Notes for this run (sent with the report)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    notesSnapshot = s?.toString().orEmpty()
                    reporter.schedule(NOTES_DEBOUNCE_MS)
                }
            })
        }
        panel = ScrollView(this).apply {
            setBackgroundColor(Color.argb(225, 10, 10, 10))
            visibility = View.GONE
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(rows)
                addView(notes)
            })
        }
        root.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(hud, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = 260; bottomMargin = 260 })
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        return root
    }

    @SuppressLint("SetTextI18n")
    private fun renderChecklist() {
        rows.removeAllViews()
        val snapshot = synchronized(checklist) { checklist.checks.map { Triple(it, it.status, it.evidence) } }
        for ((check, status, evidence) in snapshot) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
            row.addView(TextView(this).apply {
                text = "${symbol(status)}  ${check.title}"
                setTextColor(Color.WHITE)
                textSize = 14f
            })
            row.addView(TextView(this).apply {
                text = if (evidence.isNotEmpty()) evidence else check.howTo
                setTextColor(Color.LTGRAY)
                textSize = 12f
            })
            if (check.manual) {
                row.addView(LinearLayout(this).apply {
                    addView(button("Pass") { verdict(check.id, Status.PASS) })
                    addView(button("Fail") { verdict(check.id, Status.FAIL) })
                })
            }
            rows.addView(row)
        }
    }

    private fun verdict(id: String, status: Status) {
        synchronized(checklist) { checklist.set(id, status, "tester: ${status.name.lowercase()}") }
        renderChecklist()
        reporter.schedule()
    }

    private fun symbol(s: Status) = when (s) {
        Status.PASS -> "✅"
        Status.FAIL -> "❌"
        Status.SKIPPED -> "➖"
        Status.UNTESTED -> "⬜"
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private companion object {
        const val MAX_LOG_LINES = 200
        const val NOTES_DEBOUNCE_MS = 5_000L
    }
}

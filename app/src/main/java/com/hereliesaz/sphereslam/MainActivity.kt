package com.hereliesaz.sphereslam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hereliesaz.sphereslam.SphereCameraManager
import com.hereliesaz.sphereslam.SphereSLAM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val TAG = "MainActivity"
    private val CAMERA_PERMISSION_REQUEST_CODE = 100

    enum class AppMode { CREATION, TRACKING }
    private var currentMode = AppMode.CREATION
    
    private lateinit var cameraManager: SphereCameraManager
    private lateinit var sphereSLAM: SphereSLAM
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var rotationVector: Sensor? = null

    private lateinit var surfaceView: SurfaceView
    private lateinit var centerCircle: View
    private lateinit var fpsText: TextView
    private lateinit var statsText: TextView
    private lateinit var instructionText: TextView
    private lateinit var modeButton: Button
    private lateinit var actionButton: Button
    
    private var lastFrameTime = 0L
    private var statsTextUpdateFrameCounter: Int = 0

    // Creation State
    private val targetPositions = mutableListOf<FloatArray>()
    private val capturedFlags = mutableListOf<Boolean>()
    private var totalCoverage = 0
    private var isScanning = false
    private var currentRemappedMatrix = FloatArray(9)

    // Frame Queue
    private data class QueuedFrame(
        val image: android.media.Image, val address: Long, val timestamp: Double,
        val width: Int, val height: Int, val stride: Int
    )
    private val frameQueue = LinkedBlockingQueue<QueuedFrame>(2)
    private var framesProcessedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        centerCircle = findViewById(R.id.centerCircle)
        surfaceView.holder.addCallback(this)
        fpsText = findViewById(R.id.fpsText)
        statsText = findViewById(R.id.statsText)
        instructionText = findViewById(R.id.instructionText)
        
        modeButton = findViewById(R.id.resetButton)
        modeButton.text = "Switch to Tracking"
        
        actionButton = findViewById(R.id.captureButton)
        actionButton.text = "Start Creation"

        modeButton.setOnClickListener { toggleMode() }
        actionButton.setOnClickListener { handleAction() }

        findViewById<Button>(R.id.logsButton).setOnClickListener {
            LogBottomSheetFragment().show(supportFragmentManager, "LogBottomSheet")
        }

        sphereSLAM = SphereSLAM(this)

        // Set target dot size based on the UI circle overlay
        centerCircle.post {
            val sizePx = centerCircle.width.toFloat()
            sphereSLAM.setTargetSize(sizePx * 1.5f) // Make it slightly larger than the circle for easier alignment
        }

        initCaptureTargets()

        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val frame = frameQueue.take()
                    sphereSLAM.processFrame(frame.address, frame.timestamp, frame.width, frame.height, frame.stride)
                    frame.image.close()
                    framesProcessedCount++
                } catch (e: Exception) { LogManager.e(TAG, "Error processing frame", e) }
            }
        }

        cameraManager = SphereCameraManager(this) { image ->
            val address = sphereSLAM.getBufferAddress(image.planes[0].buffer)
            if (address != 0L) {
                val frame = QueuedFrame(image, address, image.timestamp.toDouble(), image.width, image.height, image.planes[0].rowStride)
                if (!frameQueue.offer(frame)) {
                    frameQueue.poll()?.image?.close()
                    frameQueue.offer(frame)
                }
            } else { image.close() }
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        updateUiForMode()

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
    }

    private fun initCaptureTargets() {
        targetPositions.clear()
        capturedFlags.clear()
        val radius = 5.0f
        
        // 18 targets total for better UX
        // Horizon (8)
        for (i in 0 until 8) {
            val a = (2 * PI * i / 8).toFloat()
            targetPositions.add(floatArrayOf(radius * cos(a), radius * sin(a), 0f))
            capturedFlags.add(false)
        }
        // Upper 45 deg (4)
        for (i in 0 until 4) {
            val a = (2 * PI * i / 4).toFloat()
            val r = radius * 0.707f
            targetPositions.add(floatArrayOf(r * cos(a), r * sin(a), radius * 0.707f))
            capturedFlags.add(false)
        }
        // Lower 45 deg (4)
        for (i in 0 until 4) {
            val a = (2 * PI * i / 4).toFloat()
            val r = radius * 0.707f
            targetPositions.add(floatArrayOf(r * cos(a), r * sin(a), -radius * 0.707f))
            capturedFlags.add(false)
        }
        // Poles (2)
        targetPositions.add(floatArrayOf(0f, 0f, radius))
        capturedFlags.add(false)
        targetPositions.add(floatArrayOf(0f, 0f, -radius))
        capturedFlags.add(false)
        
        syncTargetsToNative()
    }

    private fun syncTargetsToNative() {
        val posArray = FloatArray(targetPositions.size * 3)
        for (i in targetPositions.indices) {
            posArray[i * 3] = targetPositions[i][0]
            posArray[i * 3 + 1] = targetPositions[i][1]
            posArray[i * 3 + 2] = targetPositions[i][2]
        }
        sphereSLAM.setCaptureTargets(posArray, capturedFlags.toBooleanArray())
    }

    private fun toggleMode() {
        currentMode = if (currentMode == AppMode.CREATION) AppMode.TRACKING else AppMode.CREATION
        updateUiForMode()
        sphereSLAM.resetSystem()
        framesProcessedCount = 0
    }

    private fun updateUiForMode() {
        runOnUiThread {
            if (currentMode == AppMode.CREATION) {
                modeButton.text = "Switch to Tracking"
                actionButton.text = "Start Scan"
                instructionText.text = "Align red dots with the center circle"
                centerCircle.visibility = View.VISIBLE
                resetCoverage()
            } else {
                modeButton.text = "Switch to Creation"
                actionButton.text = "Start Tracking"
                instructionText.text = "Tracking Mode: Move camera to initialize"
                centerCircle.visibility = View.GONE
            }
        }
    }

    private fun handleAction() {
        if (currentMode == AppMode.CREATION) {
            if (!isScanning) {
                isScanning = true
                actionButton.text = "Finish & Stitch"
                Toast.makeText(this, "Collect all red dots", Toast.LENGTH_SHORT).show()
            } else {
                isScanning = false
                actionButton.text = "Start Scan"
                generatePhotosphere()
            }
        } else {
            sphereSLAM.resetSystem()
            Toast.makeText(this, "SLAM System Reset", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetCoverage() {
        for (i in capturedFlags.indices) capturedFlags[i] = false
        totalCoverage = 0
        syncTargetsToNative()
    }

    private fun generatePhotosphere() {
        Toast.makeText(this, "Stitching Photosphere...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return@launch
            val destDir = File(documentsDir, "SphereSLAM_Captures/$timestamp").apply { mkdirs() }
            try {
                val photosphereFile = File(destDir, "photosphere.jpg")
                sphereSLAM.savePhotosphere(photosphereFile.absolutePath)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Creation Complete!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) { LogManager.e(TAG, "Stitching failed", e) }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            updatePoseFromIMU(event.values)
            if (currentMode == AppMode.CREATION && isScanning) {
                checkHit()
            }
        }
        sphereSLAM.processIMU(event.sensor.type, event.values[0], event.values[1], event.values[2], event.timestamp)
    }

    private fun updatePoseFromIMU(rotationVector: FloatArray) {
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, rotationVector)
        
        // Remap to account for 90-degree clockwise image rotation
        val outR = FloatArray(9)
        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, outR)
        currentRemappedMatrix = outR
        
        // OpenGL View Matrix (Transpose of rotation)
        val viewMatrix = FloatArray(16)
        viewMatrix[0] = outR[0]; viewMatrix[1] = outR[3]; viewMatrix[2] = outR[6]; viewMatrix[3] = 0f
        viewMatrix[4] = outR[1]; viewMatrix[5] = outR[4]; viewMatrix[6] = outR[7]; viewMatrix[7] = 0f
        viewMatrix[8] = outR[2]; viewMatrix[9] = outR[5]; viewMatrix[10] = outR[8]; viewMatrix[11] = 0f
        viewMatrix[12] = 0f; viewMatrix[13] = 0f; viewMatrix[14] = 0f; viewMatrix[15] = 1f
        
        sphereSLAM.setCameraPose(viewMatrix)
    }

    private fun checkHit() {
        // Look vector is the remapped "Forward" direction (-Z in camera space)
        // Which is column 2 of the remapped rotation matrix
        val lx = -currentRemappedMatrix[2]
        val ly = -currentRemappedMatrix[5]
        val lz = -currentRemappedMatrix[8]

        var hitChanged = false
        for (i in targetPositions.indices) {
            if (capturedFlags[i]) continue
            val tx = targetPositions[i][0]; val ty = targetPositions[i][1]; val tz = targetPositions[i][2]
            val dist = sqrt(tx*tx + ty*ty + tz*tz)
            val dot = lx * (tx/dist) + ly * (ty/dist) + lz * (tz/dist)
            
            if (dot > 0.97) { // Approx 14 degrees threshold
                capturedFlags[i] = true
                totalCoverage++
                hitChanged = true
            }
        }
        if (hitChanged) {
            syncTargetsToNative()
            val percentage = (totalCoverage.toFloat() / targetPositions.size * 100).toInt()
            runOnUiThread { 
                instructionText.text = "Scanning: $percentage% ($totalCoverage/${targetPositions.size})" 
                if (percentage >= 100) {
                    instructionText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                    instructionText.text = "Ready to Stitch!"
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onResume() {
        super.onResume()
        cameraManager.startBackgroundThread()
        if (allPermissionsGranted()) startCamera()
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_GAME)
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(this)
        cameraManager.closeCamera()
        cameraManager.stopBackgroundThread()
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { sphereSLAM.setNativeWindow(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { sphereSLAM.setNativeWindow(null) }

    override fun doFrame(frameTimeNanos: Long) {
        sphereSLAM.renderFrame()
        val state = sphereSLAM.getTrackingState()
        val stateStr = when(state) { -1 -> "READY?"; 0 -> "NO IMGS"; 1 -> "INIT"; 2 -> "TRACKING"; 3 -> "LOST"; else -> "???" }
        runOnUiThread {
            fpsText.text = "Mode: ${currentMode.name} | SLAM: $stateStr"
            if (currentMode == AppMode.TRACKING) {
                instructionText.text = when(state) { 1 -> "Move side-to-side"; 2 -> "Tracking Active"; 3 -> "Lost"; else -> "Tracking Mode" }
            }
        }
        if (++statsTextUpdateFrameCounter % 60 == 0) statsText.text = sphereSLAM.getMapStats()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startCamera() { cameraManager.openCamera() }
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

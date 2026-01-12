package com.hereliesaz.sphereslam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.Image
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
import android.widget.ProgressBar
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
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Technical Implementation of Google Photo Sphere (LightCycle) Capture.
 * 
 * Key Features:
 * 1. Fibonacci Lattice for uniform target distribution.
 * 2. Auto-shutter state machine (Alignment + Stability).
 * 3. AR-fixed disappearing targets.
 * 4. Concentric circular progress indicator.
 */
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
    private lateinit var captureProgressBar: ProgressBar
    private lateinit var fpsText: TextView
    private lateinit var statsText: TextView
    private lateinit var instructionText: TextView
    private lateinit var modeButton: Button
    private lateinit var actionButton: Button
    
    private var statsTextUpdateFrameCounter: Int = 0

    // --- LightCycle Core State ---
    private val targetVectors = mutableListOf<FloatArray>()
    private val targetCaptured = mutableListOf<Boolean>()
    private var totalCapturedCount = 0
    private var isScanning = false
    private var currentOrientationMatrix = FloatArray(9)
    
    // LightCycle Constants
    private val NUM_TARGETS = 26 
    private val GOLDEN_RATIO = (1.0 + sqrt(5.0)) / 2.0
    private val CAPTURE_THRESHOLD_RAD = 0.05f // ~2.8 degrees
    private val STABILITY_THRESHOLD = 0.12f // Gyro magnitude rad/s
    private var currentGyroMagnitude = 0f

    private var mLastImage: Image? = null
    private val mImageMutex = Any()

    // Frame Queue
    private data class QueuedFrame(
        val image: android.media.Image, val address: Long, val timestamp: Double,
        val width: Int, val height: Int, val stride: Int
    )
    private val frameQueue = LinkedBlockingQueue<QueuedFrame>(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        centerCircle = findViewById(R.id.centerCircle)
        captureProgressBar = findViewById(R.id.captureProgressBar)
        surfaceView.holder.addCallback(this)
        fpsText = findViewById(R.id.fpsText)
        statsText = findViewById(R.id.statsText)
        instructionText = findViewById(R.id.instructionText)
        
        modeButton = findViewById(R.id.resetButton)
        actionButton = findViewById(R.id.captureButton)

        modeButton.setOnClickListener { toggleMode() }
        actionButton.setOnClickListener { handleAction() }

        findViewById<Button>(R.id.logsButton).setOnClickListener {
            LogBottomSheetFragment().show(supportFragmentManager, "LogBottomSheet")
        }

        sphereSLAM = SphereSLAM(this)

        centerCircle.post {
            sphereSLAM.setTargetSize(centerCircle.width.toFloat())
        }

        initLightCycleTargets()

        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val frame = frameQueue.take()
                    sphereSLAM.processFrame(frame.address, frame.timestamp, frame.width, frame.height, frame.stride)
                    
                    synchronized(mImageMutex) {
                        mLastImage?.close()
                        mLastImage = frame.image
                    }
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

    /**
     * Implements Fibonacci Lattice distribution exactly as defined in Section 4.1 of doc.
     */
    private fun initLightCycleTargets() {
        targetVectors.clear()
        targetCaptured.clear()
        val radius = 5.0f
        
        for (i in 0 until NUM_TARGETS) {
            // y goes from 1 to -1 (Vertical axis in doc)
            val docY = 1.0f - (i / (NUM_TARGETS - 1).toFloat()) * 2.0f
            val docRadius = sqrt(1.0f - docY * docY)
            val theta = (2.0 * PI * i / GOLDEN_RATIO).toFloat()

            val docX = (cos(theta.toDouble()) * docRadius).toFloat()
            val docZ = (sin(theta.toDouble()) * docRadius).toFloat()
            
            // Map doc coords to Android World coords: docY -> WorldZ (Up)
            targetVectors.add(floatArrayOf(docX * radius, docZ * radius, docY * radius))
            targetCaptured.add(false)
        }
        
        syncTargetsToNative()
    }

    private fun syncTargetsToNative() {
        // Implementation: Disappearing dots. Only send uncaptured targets to native renderer.
        val uncapturedPositions = mutableListOf<Float>()
        val dummyCaptured = mutableListOf<Boolean>()
        
        for (i in targetVectors.indices) {
            if (!targetCaptured[i]) {
                uncapturedPositions.add(targetVectors[i][0])
                uncapturedPositions.add(targetVectors[i][1])
                uncapturedPositions.add(targetVectors[i][2])
                dummyCaptured.add(false)
            }
        }
        
        sphereSLAM.setCaptureTargets(uncapturedPositions.toFloatArray(), dummyCaptured.toBooleanArray())
    }

    private fun toggleMode() {
        currentMode = if (currentMode == AppMode.CREATION) AppMode.TRACKING else AppMode.CREATION
        updateUiForMode()
        sphereSLAM.resetSystem()
    }

    private fun updateUiForMode() {
        runOnUiThread {
            if (currentMode == AppMode.CREATION) {
                modeButton.text = "Switch to Tracking"
                actionButton.text = "Start Creation"
                instructionText.text = "Align the dot inside the circle"
                centerCircle.visibility = View.VISIBLE
                captureProgressBar.visibility = View.GONE
                resetLightCycle()
            } else {
                modeButton.text = "Switch to Creation"
                actionButton.text = "Start Tracking"
                instructionText.text = "Tracking Mode"
                centerCircle.visibility = View.GONE
                captureProgressBar.visibility = View.GONE
            }
        }
    }

    private fun handleAction() {
        if (currentMode == AppMode.CREATION) {
            if (!isScanning) {
                isScanning = true
                actionButton.text = "Stop & Stitch"
                captureProgressBar.visibility = View.VISIBLE
                captureProgressBar.progress = 0
                Toast.makeText(this, "Auto-shutter active", Toast.LENGTH_SHORT).show()
            } else {
                isScanning = false
                actionButton.text = "Start Creation"
                captureProgressBar.visibility = View.GONE
                runStitchingPipeline()
            }
        } else {
            sphereSLAM.resetSystem()
        }
    }

    private fun resetLightCycle() {
        for (i in targetCaptured.indices) targetCaptured[i] = false
        totalCapturedCount = 0
        syncTargetsToNative()
    }

    private fun runStitchingPipeline() {
        Toast.makeText(this, "Stitching...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return@launch
            val destDir = File(documentsDir, "SphereSLAM_Captures/$timestamp").apply { mkdirs() }
            try {
                val photosphereFile = File(destDir, "photosphere.jpg")
                sphereSLAM.savePhotosphere(photosphereFile.absolutePath)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Photosphere Saved!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) { LogManager.e(TAG, "Stitching failed", e) }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, event.values)
                val outR = FloatArray(9)
                SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, outR)
                currentOrientationMatrix = outR
                
                // Drive AR renderer
                val viewM = FloatArray(16)
                viewM[0] = outR[0]; viewM[1] = outR[3]; viewM[2] = outR[6]; viewM[3] = 0f
                viewM[4] = outR[1]; viewM[5] = outR[4]; viewM[6] = outR[7]; viewM[7] = 0f
                viewM[8] = outR[2]; viewM[9] = outR[5]; viewM[10] = outR[8]; viewM[11] = 0f
                viewM[12] = 0f; viewM[13] = 0f; viewM[14] = 0f; viewM[15] = 1f
                sphereSLAM.setCameraPose(viewM)
                
                if (currentMode == AppMode.CREATION && isScanning) {
                    processAutoShutter()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                currentGyroMagnitude = sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2])
            }
        }
        sphereSLAM.processIMU(event.sensor.type, event.values[0], event.values[1], event.values[2], event.timestamp)
    }

    /**
     * Implements Section 4.2 Auto-Shutter logic from doc.
     */
    private fun processAutoShutter() {
        val camLX = -currentOrientationMatrix[2]
        val camLY = -currentOrientationMatrix[5]
        val camLZ = -currentOrientationMatrix[8]

        var capturedOne = false
        for (i in targetVectors.indices) {
            if (targetCaptured[i]) continue
            
            val tv = targetVectors[i]
            val mag = sqrt(tv[0]*tv[0] + tv[1]*tv[1] + tv[2]*tv[2])
            val dot = camLX*(tv[0]/mag) + camLY*(tv[1]/mag) + camLZ*(tv[2]/mag)
            val alpha = acos(dot.toDouble().coerceIn(-1.0, 1.0)).toFloat()
            
            if (alpha < CAPTURE_THRESHOLD_RAD && currentGyroMagnitude < STABILITY_THRESHOLD) {
                synchronized(mImageMutex) {
                    val image = mLastImage
                    if (image != null) {
                        try {
                            val yBuffer = image.planes[0].buffer
                            val uBuffer = image.planes[1].buffer
                            val vBuffer = image.planes[2].buffer
                            
                            val ySize = yBuffer.remaining()
                            val uSize = uBuffer.remaining()
                            val vSize = vBuffer.remaining()
                            
                            val yvu = ByteArray(ySize + uSize + vSize)
                            yBuffer.get(yvu, 0, ySize)
                            vBuffer.get(yvu, ySize, vSize)
                            uBuffer.get(yvu, ySize + vSize, uSize)
                            
                            targetCaptured[i] = true
                            totalCapturedCount++
                            capturedOne = true
                            sphereSLAM.addFrameToMosaic(yvu, currentOrientationMatrix)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error extracting bytes from image", e)
                        }
                    }
                }
                break 
            }
        }
        
        if (capturedOne) {
            syncTargetsToNative()
            runOnUiThread {
                val progress = (totalCapturedCount.toFloat() / NUM_TARGETS * 100).toInt()
                captureProgressBar.progress = progress
                instructionText.text = "Captured $totalCapturedCount/$NUM_TARGETS"
                if (progress >= 100) {
                    instructionText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                    instructionText.text = "Done! Tap Stop & Stitch"
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
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
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
        fpsText.text = "Mode: ${currentMode.name} | SLAM State: $state"
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startCamera() { cameraManager.openCamera() }
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

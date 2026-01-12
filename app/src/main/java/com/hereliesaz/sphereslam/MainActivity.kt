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

class MainActivity : AppCompatActivity(), SensorEventListener, SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val TAG = "MainActivity"
    private val CAMERA_PERMISSION_REQUEST_CODE = 100

    enum class AppMode {
        CREATION,   // Mode to capture and stitch a photosphere
        TRACKING    // Mode to perform SLAM tracking
    }

    private var currentMode = AppMode.CREATION
    
    private lateinit var cameraManager: SphereCameraManager
    private lateinit var sphereSLAM: SphereSLAM
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var rotationVector: Sensor? = null

    private lateinit var surfaceView: SurfaceView
    private lateinit var fpsText: TextView
    private lateinit var statsText: TextView
    private lateinit var instructionText: TextView
    private lateinit var modeButton: Button
    private lateinit var actionButton: Button
    
    private var lastFrameTime = 0L
    private var statsTextUpdateFrameCounter: Int = 0

    // Creation State
    private val coverageGrid = Array(8) { BooleanArray(16) }
    private var totalCoverage = 0
    private val MAX_CELLS = 8 * 16

    // Frame Queue
    private data class QueuedFrame(
        val image: android.media.Image,
        val address: Long,
        val timestamp: Double,
        val width: Int,
        val height: Int,
        val stride: Int
    )
    private val frameQueue = LinkedBlockingQueue<QueuedFrame>(2)
    private var framesProcessedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        surfaceView.holder.addCallback(this)
        fpsText = findViewById(R.id.fpsText)
        statsText = findViewById(R.id.statsText)
        instructionText = findViewById(R.id.instructionText)
        
        modeButton = findViewById(R.id.resetButton) // Reusing as Mode Toggle for now
        modeButton.text = "Switch to Tracking"
        
        actionButton = findViewById(R.id.captureButton)
        actionButton.text = "Start Creation"

        modeButton.setOnClickListener { toggleMode() }
        actionButton.setOnClickListener { handleAction() }

        findViewById<Button>(R.id.logsButton).setOnClickListener {
            LogBottomSheetFragment().show(supportFragmentManager, "LogBottomSheet")
        }

        sphereSLAM = SphereSLAM(this)

        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val frame = frameQueue.take()
                    // Only process SLAM if in Tracking mode
                    if (currentMode == AppMode.TRACKING) {
                        sphereSLAM.processFrame(frame.address, frame.timestamp, frame.width, frame.height, frame.stride)
                    } else {
                        // In Creation mode, we might want a separate "CaptureFrame" logic
                        // For now, we still process to keep Vulkan buffers alive, but we could optimize.
                        sphereSLAM.processFrame(frame.address, frame.timestamp, frame.width, frame.height, frame.stride)
                    }
                    frame.image.close()
                    framesProcessedCount++
                } catch (e: Exception) {
                    LogManager.e(TAG, "Error processing frame", e)
                }
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
            } else {
                image.close()
            }
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        updateUiForMode()

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
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
                instructionText.text = "Creation Mode: Rotate to scan 360°"
                resetCoverage()
            } else {
                modeButton.text = "Switch to Creation"
                actionButton.text = "Start Tracking"
                instructionText.text = "Tracking Mode: Move camera to initialize SLAM"
            }
        }
    }

    private var isScanning = false

    private fun handleAction() {
        if (currentMode == AppMode.CREATION) {
            if (!isScanning) {
                isScanning = true
                actionButton.text = "Finish & Stitch"
                Toast.makeText(this, "Slowly rotate in all directions", Toast.LENGTH_SHORT).show()
            } else {
                isScanning = false
                actionButton.text = "Start Scan"
                generatePhotosphere()
            }
        } else {
            // Tracking mode action (e.g. Relocalize or Reset)
            sphereSLAM.resetSystem()
            Toast.makeText(this, "SLAM System Reset", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetCoverage() {
        for (i in 0 until 8) for (j in 0 until 16) coverageGrid[i][j] = false
        totalCoverage = 0
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
                    // Auto-switch to tracking after successful creation?
                    // toggleMode() 
                }
            } catch (e: Exception) { LogManager.e(TAG, "Stitching failed", e) }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            
            if (currentMode == AppMode.CREATION && isScanning) {
                updateCoverage(orientation[0], orientation[1])
            }
        }
        sphereSLAM.processIMU(event.sensor.type, event.values[0], event.values[1], event.values[2], event.timestamp)
    }

    private fun updateCoverage(azimuth: Float, pitch: Float) {
        val col = (((azimuth + PI) / (2 * PI)) * 16).toInt().coerceIn(0, 15)
        val row = (((pitch + PI/2) / PI) * 8).toInt().coerceIn(0, 7)
        if (!coverageGrid[row][col]) {
            coverageGrid[row][col] = true
            totalCoverage++
            val percentage = (totalCoverage.toFloat() / MAX_CELLS * 100).toInt()
            runOnUiThread {
                instructionText.text = "Scanning: $percentage% ($totalCoverage/$MAX_CELLS)"
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
        sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_UI)
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
        val stateStr = when(state) {
            -1 -> "READY?"
            0 -> "NO IMGS"
            1 -> "INIT"
            2 -> "TRACKING"
            3 -> "LOST"
            else -> "???"
        }
        
        runOnUiThread {
            fpsText.text = "Mode: ${currentMode.name} | SLAM: $stateStr"
            if (currentMode == AppMode.TRACKING && !isScanning) {
                instructionText.text = when(state) {
                    1 -> "Move camera slowly side-to-side to initialize"
                    2 -> "Tracking Active"
                    3 -> "Lost. Return to start"
                    else -> instructionText.text
                }
            }
        }

        if (++statsTextUpdateFrameCounter % 60 == 0) statsText.text = sphereSLAM.getMapStats()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startCamera() { cameraManager.openCamera() }
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

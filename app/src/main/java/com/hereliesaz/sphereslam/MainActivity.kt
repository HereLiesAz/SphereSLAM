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
import kotlin.math.acos
import kotlin.math.sqrt

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
    private lateinit var centerCircle: View
    private lateinit var fpsText: TextView
    private lateinit var statsText: TextView
    private lateinit var instructionText: TextView
    private lateinit var modeButton: Button
    private lateinit var actionButton: Button
    
    private var lastFrameTime = 0L
    private var statsTextUpdateFrameCounter: Int = 0

    // Creation State
    private val targetPositions = mutableListOf<FloatArray>() // 3D points
    private val capturedFlags = mutableListOf<Boolean>()
    private var totalCoverage = 0
    private var isScanning = false

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

        // Initialize capture targets (dots on a sphere)
        initCaptureTargets()

        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val frame = frameQueue.take()
                    if (currentMode == AppMode.TRACKING) {
                        sphereSLAM.processFrame(frame.address, frame.timestamp, frame.width, frame.height, frame.stride)
                    } else {
                        // In creation mode, we still process to update the background texture
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

    private fun initCaptureTargets() {
        targetPositions.clear()
        capturedFlags.clear()
        val radius = 5.0f // 5 meters away
        val rows = 6
        val cols = 12
        for (i in 1..rows) {
            val phi = (PI * i / (rows + 1)).toFloat()
            for (j in 0 until cols) {
                val theta = (2 * PI * j / cols).toFloat()
                val x = radius * sin(phi) * cos(theta)
                val y = radius * cos(phi)
                val z = radius * sin(phi) * sin(theta)
                targetPositions.add(floatArrayOf(x, y, z))
                capturedFlags.add(false)
            }
        }
        // Top and bottom dots
        targetPositions.add(floatArrayOf(0f, radius, 0f))
        capturedFlags.add(false)
        targetPositions.add(floatArrayOf(0f, -radius, 0f))
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
                instructionText.text = "Align dots with the center circle"
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
                Toast.makeText(this, "Point the phone at the dots", Toast.LENGTH_SHORT).show()
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
            if (currentMode == AppMode.CREATION && isScanning) {
                checkHit(event.values)
            }
        }
        sphereSLAM.processIMU(event.sensor.type, event.values[0], event.values[1], event.values[2], event.timestamp)
    }

    private fun checkHit(rotationVector: FloatArray) {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
        
        // Current look vector in world space (typically -Z in camera space)
        // Camera space -Z is (0, 0, -1). 
        // World space vector = R * [0, 0, -1]^T
        val lookX = -rotationMatrix[2]
        val lookY = -rotationMatrix[5]
        val lookZ = -rotationMatrix[8]

        var hitChanged = false
        for (i in targetPositions.indices) {
            if (capturedFlags[i]) continue
            
            val tx = targetPositions[i][0]
            val ty = targetPositions[i][1]
            val tz = targetPositions[i][2]
            val dist = sqrt(tx*tx + ty*ty + tz*tz)
            
            // Normalize target vector
            val nx = tx / dist
            val ny = ty / dist
            val nz = tz / dist
            
            // Cosine similarity
            val dot = lookX * nx + lookY * ny + lookZ * nz
            if (dot > 0.98) { // ~11 degrees threshold
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
            if (currentMode == AppMode.TRACKING) {
                instructionText.text = when(state) {
                    1 -> "Move camera slowly side-to-side to initialize"
                    2 -> "Tracking Active"
                    3 -> "Lost. Return to start"
                    else -> "Tracking Mode"
                }
            }
        }

        if (++statsTextUpdateFrameCounter % 60 == 0) statsText.text = sphereSLAM.getMapStats()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startCamera() { cameraManager.openCamera() }
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

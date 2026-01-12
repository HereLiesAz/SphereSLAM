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
import kotlin.math.atan2
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val TAG = "MainActivity"
    private val CAMERA_PERMISSION_REQUEST_CODE = 100
    private val CREATE_FILE_REQUEST_CODE = 101
    private val OPEN_FILE_REQUEST_CODE = 102
    
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
    private lateinit var captureButton: Button
    
    private var lastFrameTime = 0L
    private var statsTextUpdateFrameCounter: Int = 0

    // Guided Capture State
    private var isCapturing = false
    private val coverageGrid = Array(8) { BooleanArray(16) } // 8 rows (altitude), 16 cols (azimuth)
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
        instructionText = findViewById(R.id.instructionText) ?: statsText // Fallback if not in layout
        captureButton = findViewById(R.id.captureButton)

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            sphereSLAM.resetSystem()
            framesProcessedCount = 0
            resetCoverage()
            isCapturing = false
            captureButton.text = "Start Capture"
            Toast.makeText(this, "System Reset", Toast.LENGTH_SHORT).show()
        }

        captureButton.setOnClickListener {
            toggleCapture()
        }

        findViewById<Button>(R.id.saveMapButton).setOnClickListener { saveMapExplicit() }
        findViewById<Button>(R.id.loadMapButton).setOnClickListener { loadMapExplicit() }
        findViewById<Button>(R.id.logsButton).setOnClickListener {
            LogBottomSheetFragment().show(supportFragmentManager, "LogBottomSheet")
        }

        sphereSLAM = SphereSLAM(this)

        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val frame = frameQueue.take()
                    sphereSLAM.processFrame(frame.address, frame.timestamp, frame.width, frame.height, frame.stride)
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

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
    }

    private fun toggleCapture() {
        val state = sphereSLAM.getTrackingState()
        if (state != 2) {
            Toast.makeText(this, "Wait for TRACKING state (Move camera slowly)", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isCapturing) {
            isCapturing = true
            captureButton.text = "Finish & Stitch"
            resetCoverage()
            Toast.makeText(this, "Slowly rotate to cover all directions", Toast.LENGTH_LONG).show()
        } else {
            isCapturing = false
            captureButton.text = "Start Capture"
            capturePhotosphere()
        }
    }

    private fun resetCoverage() {
        for (i in 0 until 8) for (j in 0 until 16) coverageGrid[i][j] = false
        totalCoverage = 0
    }

    private fun updateCoverage(azimuth: Float, pitch: Float) {
        if (!isCapturing) return
        
        // Map angles to grid
        // Azimuth: -PI to PI -> 0 to 15
        val col = (((azimuth + PI) / (2 * PI)) * 16).toInt().coerceIn(0, 15)
        // Pitch: -PI/2 to PI/2 -> 0 to 7
        val row = (((pitch + PI/2) / PI) * 8).toInt().coerceIn(0, 7)

        if (!coverageGrid[row][col]) {
            coverageGrid[row][col] = true
            totalCoverage++
            updateInstruction()
        }
    }

    private fun updateInstruction() {
        val percentage = (totalCoverage.toFloat() / MAX_CELLS * 100).toInt()
        runOnUiThread {
            instructionText.text = "Coverage: $percentage% ($totalCoverage/$MAX_CELLS)"
            if (percentage >= 95) {
                instructionText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                instructionText.text = "Done! Tap Finish to Stitch."
            } else {
                instructionText.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
        }
    }

    private fun capturePhotosphere() {
        Toast.makeText(this, "Stitching Photosphere...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return@launch
            val destDir = File(documentsDir, "SphereSLAM_Captures/$timestamp").apply { mkdirs() }

            withContext(Dispatchers.IO) {
                try {
                    sphereSLAM.saveMap(File(cacheDir, "map_$timestamp.bin").absolutePath)
                    copyCacheContents(destDir)
                    val photosphereFile = File(destDir, "photosphere.jpg")
                    sphereSLAM.savePhotosphere(photosphereFile.absolutePath)
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Photosphere Saved to Documents!", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    LogManager.e(TAG, "Capture failed", e)
                }
            }
            captureScreenshot(destDir)
        }
    }

    private suspend fun copyCacheContents(destDir: File) {
        cacheDir.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(destDir, it.name), true) }
    }

    private fun captureScreenshot(destDir: File) {
        val screenshotFile = File(destDir, "preview.jpg")
        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surfaceView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                lifecycleScope.launch(Dispatchers.IO) {
                    FileOutputStream(screenshotFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bitmap.recycle()
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            // orientation[0] is azimuth, [1] is pitch, [2] is roll
            updateCoverage(orientation[0], orientation[1])
        } else {
            sphereSLAM.processIMU(event.sensor.type, event.values[0], event.values[1], event.values[2], event.timestamp)
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
            1 -> "INIT..."
            2 -> "TRACKING"
            3 -> "LOST"
            else -> "???"
        }
        fpsText.text = "State: $stateStr | Processed: $framesProcessedCount"
        if (++statsTextUpdateFrameCounter % 60 == 0) statsText.text = sphereSLAM.getMapStats()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startCamera() { cameraManager.openCamera() }
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun saveMapExplicit() { /* ... Same as before ... */ }
    private fun loadMapExplicit() { /* ... Same as before ... */ }
}

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

    private lateinit var surfaceView: SurfaceView
    private lateinit var fpsText: TextView
    private lateinit var statsText: TextView
    private var lastFrameTime = 0L
    private var statsTextUpdateFrameCounter: Int = 0

    // Touch handling
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Frame Queue for Safe Image Reading
    private data class QueuedFrame(
        val image: android.media.Image,
        val address: Long,
        val timestamp: Double,
        val width: Int,
        val height: Int,
        val stride: Int
    )
    private val frameQueue = LinkedBlockingQueue<QueuedFrame>(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            window.setSustainedPerformanceMode(true)
        }

        surfaceView = findViewById(R.id.surfaceView)
        surfaceView.holder.addCallback(this)
        fpsText = findViewById(R.id.fpsText)
        statsText = findViewById(R.id.statsText)

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            sphereSLAM.resetSystem()
        }

        findViewById<Button>(R.id.captureButton).setOnClickListener {
            capturePhotosphere()
        }

        findViewById<Button>(R.id.saveMapButton).setOnClickListener {
            saveMapExplicit()
        }

        findViewById<Button>(R.id.loadMapButton).setOnClickListener {
            loadMapExplicit()
        }

        findViewById<Button>(R.id.logsButton).setOnClickListener {
            val bottomSheet = LogBottomSheetFragment()
            bottomSheet.show(supportFragmentManager, "LogBottomSheet")
        }

        // Initialize SphereSLAM Library
        sphereSLAM = SphereSLAM(this)

        // Start frame processing loop
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val frame = frameQueue.take() // Blocks until a frame is available
                    sphereSLAM.processFrame(
                        frame.address,
                        frame.timestamp,
                        frame.width,
                        frame.height,
                        frame.stride
                    )
                    frame.image.close()
                } catch (e: Exception) {
                    LogManager.e(TAG, "Error processing frame", e)
                }
            }
        }

        cameraManager = SphereCameraManager(this) { image ->
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val width = image.width
                val height = image.height
                val stride = plane.rowStride

                // Get memory address via JNI helper
                val address = sphereSLAM.getBufferAddress(buffer)

                if (address != 0L) {
                    val frame = QueuedFrame(image, address, image.timestamp.toDouble(), width, height, stride)
                    if (!frameQueue.offer(frame)) {
                        // Queue is full, drop oldest frame to make space (Drop Oldest Strategy)
                        val oldFrame = frameQueue.poll()
                        oldFrame?.image?.close()
                        if (!frameQueue.offer(frame)) {
                            // Still couldn't add (unlikely), drop current
                            image.close()
                        }
                    }
                } else {
                    LogManager.e(TAG, "Failed to get buffer address, dropping frame")
                    image.close()
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "Error queuing frame", e)
                image.close()
            }
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun saveMapExplicit() {
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_TITLE, "map_${System.currentTimeMillis()}.bin")
        }
        startActivityForResult(intent, CREATE_FILE_REQUEST_CODE)
    }

    private fun loadMapExplicit() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream" // Adjust if needed
        }
        startActivityForResult(intent, OPEN_FILE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            data.data?.let { uri ->
                lifecycleScope.launch(Dispatchers.IO) {
                    if (requestCode == CREATE_FILE_REQUEST_CODE) {
                        try {
                            // We need a file path for native code. But SAF gives URI.
                            // Native code cannot write directly to SAF URI without heavy modification (passing FD).
                            // Strategy: Save to Cache first, then copy stream to URI.
                            val tempFile = File(cacheDir, "temp_save_map.bin")
                            sphereSLAM.saveMap(tempFile.absolutePath)

                            contentResolver.openOutputStream(uri)?.use { output ->
                                tempFile.inputStream().use { input ->
                                    input.copyTo(output)
                                }
                            }
                            tempFile.delete()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Map saved successfully", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            LogManager.e(TAG, "Failed to save map", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Failed to save map", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else if (requestCode == OPEN_FILE_REQUEST_CODE) {
                        try {
                            // Strategy: Copy from URI to Cache, then load from path.
                            val tempFile = File(cacheDir, "temp_load_map.bin")
                            contentResolver.openInputStream(uri)?.use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            val success = sphereSLAM.loadMap(tempFile.absolutePath)
                            tempFile.delete()
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    Toast.makeText(this@MainActivity, "Map loaded successfully", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "Failed to load map structure", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            LogManager.e(TAG, "Failed to load map", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Failed to load map", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun capturePhotosphere() {
        Toast.makeText(this, "Stitching Photosphere... This may take a moment.", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

            // Prepare Destination Directory
            val documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (documentsDir == null) {
                Toast.makeText(this@MainActivity, "External storage not available.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val destDir = File(documentsDir, "$CAPTURE_DIR_NAME/$timestamp")
            if (!destDir.exists() && !destDir.mkdirs()) {
                Toast.makeText(this@MainActivity, "Failed to create capture directory.", Toast.LENGTH_LONG).show()
                return@launch
            }

            withContext(Dispatchers.IO) {
                // 1. Trigger Map Save in Native (Persist State)
                val mapFileName = "$MAP_FILE_PREFIX$timestamp$MAP_FILE_SUFFIX"
                val mapFile = File(cacheDir, mapFileName)
                sphereSLAM.saveMap(mapFile.absolutePath)

                // 2. Copy cache contents
                copyCacheContents(destDir)

                // 3. Capture Visual Photosphere (Native)
                // This now stitches all KeyFrames if not in CubeMap mode (Photosphere Creator)
                val photosphereFile = File(destDir, PHOTOSPHERE_FILE_NAME)
                sphereSLAM.savePhotosphere(photosphereFile.absolutePath)
            }

            // 4. Capture Visual Preview (Screenshot)
            captureScreenshot(destDir)

            // Notify user
            Toast.makeText(this@MainActivity, "Photosphere Saved!", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun copyCacheContents(destDir: File) {
        try {
            // The prompt asks to copy contents of SphereSLAM cache directory
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.copyTo(File(destDir, file.name), overwrite = true)
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Cache copied to ${destDir.absolutePath}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IOException) {
            LogManager.e(TAG, "Failed to copy cache", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Failed to copy cache", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun captureScreenshot(destDir: File) {
        val screenshotFile = File(destDir, PREVIEW_FILE_NAME)
        try {
            val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(surfaceView, bitmap, { copyResult ->
                // Move blocking compression/IO to background thread
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        if (copyResult == PixelCopy.SUCCESS) {
                            try {
                                FileOutputStream(screenshotFile).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                }
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Screenshot saved", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: IOException) {
                                LogManager.e(TAG, "Failed to save screenshot", e)
                            }
                        } else {
                            LogManager.e(TAG, "PixelCopy failed with result: $copyResult")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Screenshot failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
             LogManager.e(TAG, "Failed to create bitmap or request PixelCopy", e)
        }
    }

    override fun onResume() {
        super.onResume()
        cameraManager.startBackgroundThread()
        if (allPermissionsGranted()) {
            cameraManager.openCamera()
        }
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        }
        gyroscope?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(this)
        cameraManager.closeCamera()
        cameraManager.stopBackgroundThread()
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Drain queue and close images to prevent leaks
        while (true) {
            val frame = frameQueue.poll() ?: break
            try {
                frame.image.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
        sphereSLAM.cleanup()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let {
            val x = it.x
            val y = it.y

            when (it.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = x
                    lastTouchY = y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY
                    sphereSLAM.manipulateView(dx, dy)
                    lastTouchX = x
                    lastTouchY = y
                }
            }
        }
        return true
    }

    private fun startCamera() {
        Toast.makeText(this, "Camera Started", Toast.LENGTH_SHORT).show()
        cameraManager.openCamera()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER || it.sensor.type == Sensor.TYPE_GYROSCOPE) {
                sphereSLAM.processIMU(it.sensor.type, it.values[0], it.values[1], it.values[2], it.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Do nothing
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        sphereSLAM.setNativeWindow(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        sphereSLAM.setNativeWindow(null)
    }

    override fun doFrame(frameTimeNanos: Long) {
        sphereSLAM.renderFrame()

        val state = sphereSLAM.getTrackingState()
        val stateStr = when(state) {
            -1 -> "SYSTEM NOT READY"
            0 -> "NO IMAGES"
            1 -> "NOT INITIALIZED"
            2 -> "TRACKING"
            3 -> "LOST"
            else -> "UNKNOWN"
        }
        fpsText.text = "State: $stateStr"

        statsTextUpdateFrameCounter++
        if (statsTextUpdateFrameCounter % 60 == 0) {
             statsText.text = sphereSLAM.getMapStats()
        }

        lastFrameTime = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val CAPTURE_DIR_NAME = "SphereSLAM_Captures"
        private const val MAP_FILE_PREFIX = "map_"
        private const val MAP_FILE_SUFFIX = ".bin"
        private const val PHOTOSPHERE_FILE_NAME = "photosphere.ppm"
        private const val PREVIEW_FILE_NAME = "preview.jpg"
    }
}

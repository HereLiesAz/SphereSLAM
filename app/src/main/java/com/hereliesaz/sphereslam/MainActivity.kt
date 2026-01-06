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

class MainActivity : AppCompatActivity(), SensorEventListener, SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val TAG = "MainActivity"
    private val CAMERA_PERMISSION_REQUEST_CODE = 100
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

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            saveState()
        }

        // Initialize SphereSLAM Library
        sphereSLAM = SphereSLAM(this)

        cameraManager = SphereCameraManager(this) { image ->
            sphereSLAM.processFrame(0L, image.timestamp.toDouble())
            image.close()
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

    private fun saveState() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        // 1. Trigger Save in Native
        val mapFileName = "map_$timestamp.bin"
        val mapFile = File(cacheDir, mapFileName)
        sphereSLAM.saveMap(mapFile.absolutePath)

        // 2. Prepare Destination Directory
        val documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        if (documentsDir == null) {
            Toast.makeText(this, "External storage not available.", Toast.LENGTH_LONG).show()
            return
        }
        val destDir = File(documentsDir, "SphereSLAM_Saves/$timestamp")
        if (!destDir.exists() && !destDir.mkdirs()) {
            Toast.makeText(this, "Failed to create save directory.", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            copyCacheContents(destDir)
        }

        // Screenshot needs to happen on main/UI thread logic for PixelCopy setup
        captureScreenshot(destDir)
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
            Log.e(TAG, "Failed to copy cache", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Failed to copy cache", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun captureScreenshot(destDir: File) {
        val screenshotFile = File(destDir, "preview.jpg")
        try {
            val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(surfaceView, bitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    try {
                        FileOutputStream(screenshotFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        runOnUiThread {
                            Toast.makeText(this, "Screenshot saved", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to save screenshot", e)
                    }
                } else {
                    Log.e(TAG, "PixelCopy failed with result: $copyResult")
                    runOnUiThread {
                        Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
             Log.e(TAG, "Failed to create bitmap or request PixelCopy", e)
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
    }
}

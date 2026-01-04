package com.example.sphereslam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.Image
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Window
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SensorEventListener, SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val CAMERA_PERMISSION_REQUEST_CODE = 100
    private lateinit var cameraManager: SphereCameraManager
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    private lateinit var surfaceView: SurfaceView
    private lateinit var fpsText: TextView
    private lateinit var statsText: TextView
    private var lastFrameTime = 0L

    // Touch handling
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Sustained Performance Mode
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            window.setSustainedPerformanceMode(true)
        }

        surfaceView = findViewById(R.id.surfaceView)
        surfaceView.holder.addCallback(this)
        fpsText = findViewById(R.id.fpsText)
        statsText = findViewById(R.id.statsText)

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            resetSystem()
        }

        // Initialize Native Systems
        initNative(assets)

        cameraManager = SphereCameraManager(this) { image ->
            processFrame(0L, image.timestamp.toDouble())
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
                    manipulateView(dx, dy)
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
                processIMU(it.sensor.type, it.values[0], it.values[1], it.values[2], it.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Do nothing
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        setNativeWindow(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        setNativeWindow(null)
    }

    override fun doFrame(frameTimeNanos: Long) {
        renderFrame()

        val state = getTrackingState()
        val stateStr = when(state) {
            -1 -> "SYSTEM NOT READY"
            0 -> "NO IMAGES"
            1 -> "NOT INITIALIZED"
            2 -> "TRACKING"
            3 -> "LOST"
            else -> "UNKNOWN"
        }
        fpsText.text = "State: $stateStr"

        // Update stats occasionally
        // Update stats every 30 frames for example
        if (frameCount++ % 30 == 0) {
             statsText.text = getMapStats()
        }

        lastFrameTime = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    // Native Methods Declaration
    external fun stringFromJNI(): String
    external fun initNative(assetManager: AssetManager)
    external fun processFrame(matAddr: Long, timestamp: Double)
    external fun processIMU(type: Int, x: Float, y: Float, z: Float, timestamp: Long)
    external fun setNativeWindow(surface: Surface?)
    external fun renderFrame()
    external fun manipulateView(dx: Float, dy: Float)
    external fun getTrackingState(): Int
    external fun resetSystem()
    external fun getMapStats(): String

    companion object {
        init {
            System.loadLibrary("sphereslam")
        }
        // Removed WRITE_EXTERNAL_STORAGE as it's not in Manifest anymore
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

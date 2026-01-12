package com.hereliesaz.sphereslam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.Executor
import kotlin.math.abs

class SphereCameraManager(
    private val context: Context,
    private val onImageAvailable: (Image) -> Unit
) {

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageReaderYUV: ImageReader? = null
    private var imageReaderJPEG: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var isOpening = false

    companion object {
        private const val TAG = "SphereCameraManager"
        private val TARGET_YUV_SIZE = Size(1280, 720)
        private const val MAX_JPEG_PIXELS = 4096 * 3072 // Limit to ~12MP for stability
        private const val MAX_IMAGES = 5 // Increased to avoid "maxImages has already been acquired"
    }

    fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while stopping background thread", e)
        }
    }

    private fun chooseOptimalSize(choices: Array<Size>, target: Size, maxPixels: Int = Int.MAX_VALUE): Size {
        val filtered = choices.filter { it.width * it.height <= maxPixels }
        val listToUse = if (filtered.isNotEmpty()) filtered else choices.toList()
        
        return listToUse.minByOrNull { 
            abs(it.width * it.height - target.width * target.height) + 
            abs(it.width.toFloat() / it.height - target.width.toFloat() / target.height).toInt() * 1000
        } ?: choices[0]
    }

    fun openCamera() {
        if (cameraDevice != null || isOpening) {
            Log.d(TAG, "Camera already open or opening")
            return
        }
        
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.getOrNull(0) ?: return
            val characteristics = manager.getCameraCharacteristics(cameraId)

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            
            // Choose resolutions dynamically
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)

            val yuvSize = chooseOptimalSize(yuvSizes, TARGET_YUV_SIZE)
            // Limit JPEG size to avoid memory/driver issues during SLAM
            val jpegSize = chooseOptimalSize(jpegSizes, Size(3840, 2160), MAX_JPEG_PIXELS)

            Log.d(TAG, "Selected YUV size: $yuvSize, JPEG size: $jpegSize")

            imageReaderYUV?.close()
            imageReaderYUV = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, MAX_IMAGES)
            imageReaderYUV?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        onImageAvailable(image)
                    }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Too many images acquired from YUV Reader", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring YUV image", e)
                }
            }, backgroundHandler)

            imageReaderJPEG?.close()
            imageReaderJPEG = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            imageReaderJPEG?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    image?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring/closing JPEG image", e)
                }
            }, backgroundHandler)

            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                isOpening = true
                manager.openCamera(cameraId, stateCallback, backgroundHandler)
            } else {
                Log.e(TAG, "Camera permission not granted")
            }

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot access camera", e)
            isOpening = false
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            isOpening = false
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "Camera opened")
            cameraDevice = camera
            isOpening = false
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera disconnected")
            isOpening = false
            camera.close()
            if (cameraDevice == camera) cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            isOpening = false
            camera.close()
            if (cameraDevice == camera) cameraDevice = null
        }
    }

    private fun createCameraPreviewSession() {
        val device = cameraDevice ?: return
        try {
            val yuvSurface = imageReaderYUV?.surface
            val jpegSurface = imageReaderJPEG?.surface

            val surfaces = mutableListOf<Surface>()
            if (yuvSurface?.isValid == true) surfaces.add(yuvSurface)
            // Some devices fail if we have a JPEG surface in a regular session if not used properly.
            // For now, let's keep it but handle configuration failures more gracefully.
            if (jpegSurface?.isValid == true) surfaces.add(jpegSurface)

            if (surfaces.isEmpty()) {
                Log.e(TAG, "No valid surfaces for capture session")
                return
            }

            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    val currentDevice = cameraDevice
                    if (currentDevice == null) return
                    cameraCaptureSession = session
                    try {
                        val captureRequest = currentDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        imageReaderYUV?.surface?.let { if (it.isValid) captureRequest.addTarget(it) }

                        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                        session.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
                        Log.d(TAG, "Capture session configured and repeating request started")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting repeating request", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Configuration failed: Failed to create capture session")
                    // If it fails with both, try YUV only as a fallback
                    if (surfaces.size > 1) {
                        Log.w(TAG, "Retrying with YUV surface only...")
                        createYuvOnlySession()
                    }
                }

                override fun onClosed(session: CameraCaptureSession) {
                    super.onClosed(session)
                    if (cameraCaptureSession == session) cameraCaptureSession = null
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = surfaces.map { OutputConfiguration(it) }
                val executor = Executor { command -> backgroundHandler?.post(command) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    executor,
                    sessionCallback
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces, sessionCallback, backgroundHandler)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error creating session", e)
        }
    }

    private fun createYuvOnlySession() {
        val device = cameraDevice ?: return
        val yuvSurface = imageReaderYUV?.surface ?: return
        if (!yuvSurface.isValid) return

        try {
            val surfaces = listOf(yuvSurface)
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraCaptureSession = session
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        request.addTarget(yuvSurface)
                        session.setRepeatingRequest(request.build(), null, backgroundHandler)
                        Log.d(TAG, "YUV-only session configured")
                    } catch (e: Exception) { Log.e(TAG, "Error in YUV-only session", e) }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "YUV-only configuration failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating YUV-only session", e)
        }
    }

    fun closeCamera() {
        isOpening = false
        try {
            cameraCaptureSession?.stopRepeating()
            cameraCaptureSession?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing capture session", e)
        } finally {
            cameraCaptureSession = null
        }

        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera device", e)
        } finally {
            cameraDevice = null
        }

        imageReaderYUV?.close()
        imageReaderYUV = null
        imageReaderJPEG?.close()
        imageReaderJPEG = null
    }
}

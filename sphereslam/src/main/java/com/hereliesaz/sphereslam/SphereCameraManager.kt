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
            imageReaderYUV = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2)
            imageReaderYUV?.setOnImageAvailableListener({ reader ->
                val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
                if (image != null) {
                    onImageAvailable(image)
                }
            }, backgroundHandler)

            imageReaderJPEG?.close()
            imageReaderJPEG = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            imageReaderJPEG?.setOnImageAvailableListener({ reader ->
                val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
                // Process image for Photosphere capture if needed
                image?.close()
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
            if (jpegSurface?.isValid == true) surfaces.add(jpegSurface)

            if (surfaces.isEmpty()) {
                Log.e(TAG, "No valid surfaces for capture session")
                return
            }

            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    val currentDevice = cameraDevice
                    if (currentDevice == null) {
                        Log.w(TAG, "CameraDevice is null in onConfigured")
                        return
                    }
                    cameraCaptureSession = session
                    try {
                        // Use TEMPLATE_PREVIEW instead of TEMPLATE_RECORD for better compatibility
                        val captureRequest = currentDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        imageReaderYUV?.surface?.let { if (it.isValid) captureRequest.addTarget(it) }

                        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                        session.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
                        Log.d(TAG, "Capture session configured and repeating request started")
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Camera access exception during session config", e)
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "Session or device state error", e)
                    } catch (e: Exception) {
                        Log.e(TAG, "Unexpected error in onConfigured", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Configuration failed: Failed to create capture session")
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

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception in createCameraPreviewSession", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid surfaces provided to createCaptureSession", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error creating session", e)
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

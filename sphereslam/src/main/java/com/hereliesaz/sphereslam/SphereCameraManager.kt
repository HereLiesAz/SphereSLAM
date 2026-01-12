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
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.Arrays
import java.util.Collections
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

    companion object {
        private const val TAG = "SphereCameraManager"
        private val TARGET_YUV_SIZE = Size(1280, 720)
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

    private fun chooseOptimalSize(choices: Array<Size>, target: Size): Size {
        return choices.minByOrNull { 
            abs(it.width * it.height - target.width * target.height) + 
            abs(it.width.toFloat() / it.height - target.width.toFloat() / target.height).toInt() * 1000
        } ?: choices[0]
    }

    fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.getOrNull(0) ?: return
            val characteristics = manager.getCameraCharacteristics(cameraId)

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            
            // Choose resolutions dynamically
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)

            val yuvSize = chooseOptimalSize(yuvSizes, TARGET_YUV_SIZE)
            val jpegSize = jpegSizes.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)

            Log.d(TAG, "Selected YUV size: $yuvSize, JPEG size: $jpegSize")

            imageReaderYUV?.close()
            imageReaderYUV = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2)
            imageReaderYUV?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    onImageAvailable(image)
                }
            }, backgroundHandler)

            imageReaderJPEG?.close()
            imageReaderJPEG = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            imageReaderJPEG?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                // Process image for Photosphere capture
                image?.close()
            }, backgroundHandler)

            try {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    manager.openCamera(cameraId, stateCallback, backgroundHandler)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Camera permission not granted", e)
            }

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot access camera", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "Camera opened")
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera disconnected")
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            camera.close()
            cameraDevice = null
        }
    }

    private fun createCameraPreviewSession() {
        val device = cameraDevice ?: return
        try {
            val surfaces = ArrayList<Surface>()
            imageReaderYUV?.surface?.let { surfaces.add(it) }
            imageReaderJPEG?.surface?.let { surfaces.add(it) }

            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    cameraCaptureSession = session
                    try {
                        val captureRequest = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        imageReaderYUV?.surface?.let { captureRequest.addTarget(it) }

                        // Set high sampling rate for SLAM
                        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

                        session.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
                        Log.d(TAG, "Capture session configured and repeating request started")
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Camera access exception during session config", e)
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "Session or device state error", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Configuration failed: Failed to create capture session")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception in createCameraPreviewSession", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid surfaces provided to createCaptureSession", e)
        }
    }

    fun closeCamera() {
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

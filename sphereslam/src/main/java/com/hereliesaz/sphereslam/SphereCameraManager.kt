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
    }

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
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

    fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0] // Usually back camera
            val characteristics = manager.getCameraCharacteristics(cameraId)

            // Choose resolutions (simplified for this blueprint)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val yuvSize = Size(1280, 720)
            val jpegSize = Size(3840, 2160) // 4K

            imageReaderYUV = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2)
            imageReaderYUV?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    onImageAvailable(image)
                }
            }, backgroundHandler)

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
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice?.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice?.close()
            cameraDevice = null
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val surfaces = ArrayList<Surface>()
            imageReaderYUV?.surface?.let { surfaces.add(it) }
            imageReaderJPEG?.surface?.let { surfaces.add(it) }

            cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    cameraCaptureSession = session
                    try {
                        val captureRequest = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        imageReaderYUV?.surface?.let { captureRequest.addTarget(it) }

                        // Set high sampling rate for SLAM
                        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

                        session.setRepeatingRequest(captureRequest.build(), null, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Camera access exception", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Configuration failed")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception", e)
        }
    }

    fun closeCamera() {
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReaderYUV?.close()
        imageReaderJPEG?.close()
    }
}

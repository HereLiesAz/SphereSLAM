package com.hereliesaz.sphereslam

import android.graphics.SurfaceTexture
import android.util.Log

/**
 * Reconstructed MosaicFrameProcessor (Section 2.2).
 * Manages the camera preview stream and passes frames to the C++ engine.
 */
class MosaicFrameProcessor(
    private val targetManager: LightCycleTargetManager,
    private val sphereSLAM: SphereSLAM
) : SurfaceTexture.OnFrameAvailableListener {
      
    private var mIsMosaicMemoryAllocated = false
    private val TAG = "MosaicFrameProcessor"
    
    // YUV Data Buffer (placeholder for actual buffer management)
    private var mCurrYUV: ByteArray? = null

    fun initialize(width: Int, height: Int) {
        sphereSLAM.allocateMosaicMemory(width, height)
        mIsMosaicMemoryAllocated = true
        Log.i(TAG, "Mosaic memory allocated: $width x $height")
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (!mIsMosaicMemoryAllocated) return

        // Note: In a full impl, SensorFusion would be a separate module.
        // For now, we assume currentOrientationMatrix is updated by MainActivity via sensors.
    }

    /**
     * Called by MainActivity when a frame is ready and alignment is checked.
     */
    fun processFrame(rotationMatrix: FloatArray, yuvData: ByteArray) {
        mCurrYUV = yuvData
        
        // Section 2.2: Check if we are pointing at a target (Blue Dot)
        if (targetManager.checkAlignment(rotationMatrix)) {
            
            // Extract 3x3 rotation from 4x4
            val rot3x3 = FloatArray(9)
            rot3x3[0] = rotationMatrix[0]; rot3x3[1] = rotationMatrix[1]; rot3x3[2] = rotationMatrix[2]
            rot3x3[3] = rotationMatrix[4]; rot3x3[4] = rotationMatrix[5]; rot3x3[5] = rotationMatrix[6]
            rot3x3[6] = rotationMatrix[8]; rot3x3[7] = rotationMatrix[9]; rot3x3[8] = rotationMatrix[10]

            val retCode = sphereSLAM.addFrameToMosaic(yuvData, rot3x3)

            if (retCode == 0) { // MOSAIC_RET_OK
                targetManager.markCurrentTargetCaptured()
                Log.d(TAG, "Frame captured and added to mosaic.")
            } else if (retCode == -2) { // MOSAIC_RET_FEW_INLIERS
                Log.w(TAG, "Few inliers detected. Hold Still or find more texture.")
            }
        }
    }

    fun freeMemory() {
        if (mIsMosaicMemoryAllocated) {
            sphereSLAM.freeMosaicMemory()
            mIsMosaicMemoryAllocated = false
        }
    }
}

package com.hereliesaz.sphereslam

import android.content.Context
import android.content.res.AssetManager
import android.view.Surface

class SphereSLAM(private val context: Context) {

    init {
        // Initialize native systems with cache dir
        initNative(context.assets, context.cacheDir.absolutePath)
    }

    // Native Interface
    external fun initNative(assetManager: AssetManager, cacheDir: String)
    external fun destroyNative()
    external fun processFrame(matAddr: Long, timestamp: Double)
    external fun processIMU(type: Int, x: Float, y: Float, z: Float, timestamp: Long)
    external fun setNativeWindow(surface: Surface?)
    external fun renderFrame()
    external fun manipulateView(dx: Float, dy: Float)
    external fun getTrackingState(): Int
    external fun resetSystem()
    external fun getMapStats(): String
    external fun saveMap(filePath: String)

    fun cleanup() {
        destroyNative()
    }

    companion object {
        init {
            System.loadLibrary("sphereslam")
        }
    }
}

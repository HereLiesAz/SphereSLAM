package com.hereliesaz.sphereslam.testapp

import android.app.Application
import android.util.Log

class TestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Flow tracking and tile localization need OpenCV's native library (README: Matching speed).
        runCatching { System.loadLibrary("opencv_java5") }
            .onFailure { Log.w("SphereSLAM-Test", "OpenCV not loaded; flow and tiles disable themselves", it) }
    }
}

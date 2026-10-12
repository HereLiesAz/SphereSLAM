package com.hereliesaz.sphereslam.testapp

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

class TestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Flow tracking and tile localization need OpenCV's native library (README: Matching speed).
        runCatching { System.loadLibrary("opencv_java5") }
            .onFailure { Log.w("SphereSLAM-Test", "OpenCV not loaded; flow and tiles disable themselves", it) }
    }
}

/**
 * Keeps the last crash: the uncaught-exception handler writes the stack trace to a file before the
 * process dies, and the next launch puts it in that run's report ([takePrevious]). A crash can't be
 * reported by the process that is crashing, so this is how one reaches the issue at all.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val MAX_CHARS = 8_000

    fun install(context: Context) {
        val dir = context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(dir, FILE).writeText("${Instant.now()} on ${thread.name}\n" + error.stackTraceToString().take(MAX_CHARS))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The previous run's crash, once (the file is removed), or null if it didn't crash. */
    fun takePrevious(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull().also { file.delete() }
    }
}

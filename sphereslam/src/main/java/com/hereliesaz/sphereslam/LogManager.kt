package com.hereliesaz.sphereslam

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogManager {
    private const val TAG = "SphereSLAM-App"

    private val _logs = MutableSharedFlow<LogEntry>(
        replay = 1000,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val logs = _logs.asSharedFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, msg: String) {
        log(Log.DEBUG, tag, msg)
    }

    fun i(tag: String, msg: String) {
        log(Log.INFO, tag, msg)
    }

    fun w(tag: String, msg: String) {
        log(Log.WARN, tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val message = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        log(Log.ERROR, tag, message)
    }

    fun log(level: Int, tag: String, msg: String) {
        // Log to Android Logcat
        when (level) {
            Log.VERBOSE -> Log.v(tag, msg)
            Log.DEBUG -> Log.d(tag, msg)
            Log.INFO -> Log.i(tag, msg)
            Log.WARN -> Log.w(tag, msg)
            Log.ERROR -> Log.e(tag, msg)
            else -> Log.d(tag, msg)
        }

        // Emit to internal flow
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            timeString = dateFormat.format(Date()),
            level = level,
            tag = tag,
            message = msg
        )
        // emit is suspend, tryEmit is non-blocking. Since we have a buffer, tryEmit is fine.
        _logs.tryEmit(entry)
    }
}

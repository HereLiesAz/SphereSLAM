package com.hereliesaz.sphereslam

import android.util.Log
import androidx.annotation.ColorInt

data class LogEntry(
    val timestamp: Long,
    val timeString: String,
    val level: Int,
    val tag: String,
    val message: String
) {
    fun isErrorOrWarning(): Boolean {
        return level == Log.WARN || level == Log.ERROR || level == Log.ASSERT
    }
}

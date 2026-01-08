package com.hereliesaz.sphereslam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

class LogViewModel : ViewModel() {
    // Expose the raw flow directly. The UI will collect and append.
    // This avoids accumulating a duplicate list in the ViewModel and copying it on every emit.
    val allLogs: Flow<LogEntry> = LogManager.logs

    val errorLogs: Flow<LogEntry> = LogManager.logs.filter { it.isErrorOrWarning() }
}

package com.hereliesaz.sphereslam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LogViewModel : ViewModel() {
    private val _allLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val allLogs: StateFlow<List<LogEntry>> = _allLogs

    private val _errorLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val errorLogs: StateFlow<List<LogEntry>> = _errorLogs

    init {
        viewModelScope.launch {
            val buffer = ArrayList<LogEntry>()
            LogManager.logs.collect { entry ->
                buffer.add(entry)
                // In a real app, might want to optimize this to not emit on every single log if high frequency
                // But for now, simple implementation
                val newAll = ArrayList(_allLogs.value)
                newAll.add(entry)
                _allLogs.value = newAll

                if (entry.isErrorOrWarning()) {
                    val newErrors = ArrayList(_errorLogs.value)
                    newErrors.add(entry)
                    _errorLogs.value = newErrors
                }
            }
        }
    }
}

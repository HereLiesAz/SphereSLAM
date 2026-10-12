package com.hereliesaz.sphereslam.testapp

/**
 * What the session's failure callbacks mean for the run, kept apart from [Tracker] so it can be tested
 * on the JVM with the library's real callback order.
 *
 * The session reports a rejected capture (a RECOVERABLE failure such as `REFERENCE_TOO_WEAK`) through
 * `onFailure` and then `onFatalError` too, while it suspends only the precision layer and keeps
 * running. So `onFatalError` alone doesn't mean "stopped": [onFatalError] takes the session's own
 * verdict ([sessionStopped]: `isFatal && !isRecoverable`).
 */
class FailureLatch {
    /** Why tracking stopped for good, or null while it runs. */
    @Volatile var fatal: String? = null
        private set

    /** The latest recoverable failure (by reason name) and when it happened, or null. */
    @Volatile var problem: Pair<String, Long>? = null
        private set

    /** The lock check whose target the session holds (`lock_wall` …); null when it holds none. */
    @Volatile var reference: String? = null
        private set

    /** A new target for [checkId] went to the session: earlier problems were about the old one. */
    fun onCapture(checkId: String) {
        problem = null
        reference = checkId
    }

    /** `onFailure`, with the severity's name (`FATAL`, `RECOVERABLE`, `TRANSIENT`). */
    fun onFailure(reason: String, severity: String, nowMs: Long) {
        when (severity) {
            "FATAL" -> if (fatal == null) fatal = reason
            // The session dropped the precision layer: no target is held until the next capture.
            "RECOVERABLE" -> { problem = reason to nowMs; reference = null }
        }
    }

    /** `onFatalError`: a stop only when the session itself says it cannot continue. */
    fun onFatalError(name: String, sessionStopped: Boolean) {
        if (sessionStopped && fatal == null) fatal = name
    }
}

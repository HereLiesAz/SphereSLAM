package com.hereliesaz.sphereslam.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [FailureLatch] fed in the order the session calls back: `onFailure`, then `onFatalError`. */
class FailureLatchTest {

    @Test
    fun `a rejected capture is a problem, not a stop`() {
        val l = FailureLatch()
        l.onCapture("lock_wall")
        l.onFailure("REFERENCE_TOO_WEAK", "RECOVERABLE", 100L)
        // The session suspends its precision layer and calls onFatalError too, but keeps running.
        l.onFatalError("IllegalStateException", sessionStopped = false)
        assertNull(l.fatal)
        assertEquals("REFERENCE_TOO_WEAK" to 100L, l.problem)
        assertNull(l.reference)
        // The next capture clears it and holds the new target.
        l.onCapture("lock_wall")
        assertNull(l.problem)
        assertEquals("lock_wall", l.reference)
    }

    @Test
    fun `a fatal failure stops tracking and keeps its first reason`() {
        val l = FailureLatch()
        l.onFailure("NATIVE_LIBRARY_UNAVAILABLE", "FATAL", 0L)
        l.onFatalError("UnsatisfiedLinkError", sessionStopped = true)
        assertEquals("NATIVE_LIBRARY_UNAVAILABLE", l.fatal)
    }

    @Test
    fun `a stop reported only through onFatalError still latches`() {
        val l = FailureLatch()
        l.onFatalError("UnsatisfiedLinkError", sessionStopped = true)
        assertEquals("UnsatisfiedLinkError", l.fatal)
    }

    @Test
    fun `transient failures change nothing`() {
        val l = FailureLatch()
        l.onCapture("lock_floor")
        l.onFailure("TRACKING_LOST", "TRANSIENT", 5L)
        assertNull(l.fatal)
        assertNull(l.problem)
        assertEquals("lock_floor", l.reference)
    }
}

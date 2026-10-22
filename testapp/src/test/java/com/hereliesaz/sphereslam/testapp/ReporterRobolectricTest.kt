package com.hereliesaz.sphereslam.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A JVM test run must never reach the live report Worker (it filed fake "Device test" issues). */
@RunWith(RobolectricTestRunner::class)
class ReporterRobolectricTest {
    @Test
    fun `under Robolectric post sends nothing and reports success`() {
        assertTrue(Reporter.isRobolectric)
        // An address nothing listens on: a real send would fail with a connection error.
        val (ok, detail) = Reporter.post("http://127.0.0.1:9/test-report/SphereSLAM", "Basic x", "{}", 1_000)
        assertTrue(ok)
        assertEquals("not sent: Robolectric test run", detail)
    }
}

package com.hereliesaz.sphereslam.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReporterTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `retries back off from 10 s and cap at 5 min`() {
        assertEquals(10_000L, Reporter.retryDelayMs(1))
        assertEquals(20_000L, Reporter.retryDelayMs(2))
        assertEquals(160_000L, Reporter.retryDelayMs(5))
        assertEquals(300_000L, Reporter.retryDelayMs(6))
        assertEquals(300_000L, Reporter.retryDelayMs(40))
    }

    @Test
    fun `a crash heads the notes and its trace follows the log`() {
        val trace = (1..10).joinToString("\n") { "line $it" }
        val notes = ReportJson.notesWithCrash("outline drifted", trace)
        assertTrue(notes, notes.startsWith("THE APP CRASHED\nline 1\nline 2\nline 3\nline 4\n\noutline drifted"))
        assertFalse(notes.contains("line 5"))
        assertEquals("plain", ReportJson.notesWithCrash("plain", null))
        assertTrue(ReportJson.logWithCrash("log", trace).endsWith("--- crash ---\n$trace"))
    }

    @Test
    fun `a body persisted is pending until its own run is delivered`() {
        val dir = tmp.root
        val a = """{"packageName":"p","runId":"run-a","notes":""}"""
        val b = """{"packageName":"p","runId":"run-b","notes":""}"""
        assertEquals("run-a", Reporter.runIdOf(a))
        assertNull(Reporter.lastBody(dir))
        Reporter.persist(dir, a)
        assertTrue(Reporter.hasPending(dir))
        Reporter.persist(dir, b)
        // A late delivery of the older run doesn't clear the newer one's marker.
        Reporter.markDelivered(dir, "run-a")
        assertTrue(Reporter.hasPending(dir))
        Reporter.markDelivered(dir, "run-b")
        assertFalse(Reporter.hasPending(dir))
        assertEquals(b, Reporter.lastBody(dir))
    }
}

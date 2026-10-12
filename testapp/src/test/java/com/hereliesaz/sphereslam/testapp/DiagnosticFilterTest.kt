package com.hereliesaz.sphereslam.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticFilterTest {

    @Test
    fun `transient misses are counted onto the next timing line, everything else passes`() {
        val f = DiagnosticFilter()
        assertNull(f.filter("SphereSLAM standalone failure=STALE_OBSERVATION severity=TRANSIENT ageMs=356.1"))
        assertNull(f.filter("SphereSLAM standalone failure=NO_CURRENT_PAGE_MATCH severity=TRANSIENT matchMs=208.5"))
        assertNull(f.filter("SphereSLAM standalone failure=STALE_OBSERVATION severity=TRANSIENT ageMs=329.4"))
        assertEquals("SphereSLAM standalone state=LOCKED", f.filter("SphereSLAM standalone state=LOCKED"))
        assertEquals(
            "SphereSLAM standalone KPM no-lock timing{frames=20} tiles{rotFixes=0 poseFixes=15} transient{STALE_OBSERVATION=2 NO_CURRENT_PAGE_MATCH=1}",
            f.filter("SphereSLAM standalone KPM no-lock timing{frames=20} tiles{rotFixes=0 poseFixes=15}"),
        )
        // Counted once: the next timing line has nothing to add.
        assertEquals("x timing{frames=1}", f.filter("x timing{frames=1}"))
        // A fatal failure is never folded away.
        val fatal = "SphereSLAM standalone failure=NATIVE_LIBRARY_UNAVAILABLE severity=FATAL"
        assertEquals(fatal, f.filter(fatal))
    }

    @Test
    fun `the run's log stays under its size cap, newest lines kept`() {
        val run = Run(RunInfo("run-12345678", "b", "v", "a", "p", "B", "M", "16", 36, "t"))
        val line = "x".repeat(1_000)
        repeat(200) { run.log("$it $line") }
        val text = run.logText()
        assert(text.length <= Run.MAX_LOG_CHARS) { "${text.length}" }
        assert(text.lines().last().contains(" 199 ")) { text.lines().last().take(60) }
    }
}

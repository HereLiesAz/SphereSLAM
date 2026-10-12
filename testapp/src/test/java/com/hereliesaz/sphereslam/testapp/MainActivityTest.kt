package com.hereliesaz.sphereslam.testapp

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The real activity, without the camera permission (so no camera or native session starts): the
 * coach asks for the camera, the overview opens and closes, Skip walks to the end, and Start over
 * begins a new run with its own id.
 */
@OptIn(ExperimentalCamera2Interop::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class MainActivityTest {

    private fun idle(ms: Long = MainActivity.TICK_MS) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun texts(v: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (v is TextView && v.isShown) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun MainActivity.button(label: String): TextView? =
        texts(window.decorView).firstOrNull { it.text.toString() == label && it.isClickable }

    /** Taps [label] once the tap guard has passed. */
    private fun MainActivity.tap(label: String) {
        SystemClock.sleep(MainActivity.TAP_GUARD_MS)
        idle()
        val b = button(label)
        assertNotNull("no \"$label\" among ${texts(window.decorView).map { it.text }}", b)
        assertTrue("\"$label\" is disabled", b!!.isEnabled)
        b.performClick()
        idle()
    }

    @Test
    fun `without the camera the coach asks for it, and the run can still be walked to the end`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val shown = texts(activity.window.decorView).joinToString("\n") { it.text }
        assertTrue(shown, shown.contains("Tap Allow camera"))
        assertNotNull(activity.button("Allow camera"))
        assertTrue(shown, shown.contains(BuildConfig.SPHERESLAM_BUILD))

        // The overview replaces the step and gives it back.
        activity.tap("All steps")
        assertTrue(activity.button("All steps") == null)
        activity.tap("Back to step")
        assertNotNull(activity.button("All steps"))

        val app = activity.application as TestApp
        // Skip every step to the end.
        val steps = app.run.guide.steps.size
        repeat(steps) { activity.tap("Skip") }
        assertTrue(app.run.guide.finished)
        val runId = app.run.info.runId
        activity.tap("Start over")
        assertNotEquals(runId, app.run.info.runId)
        assertEquals(0, app.run.guide.index)
    }
}

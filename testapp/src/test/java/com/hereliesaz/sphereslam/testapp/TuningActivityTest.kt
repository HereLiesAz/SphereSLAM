package com.hereliesaz.sphereslam.testapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The tuning screen over a real (tiny) recording: the list, the sweep setup and a saved result's
 * ranking and detail. Each page is drawn to `testapp/build/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TuningActivityTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun texts(v: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (v is TextView && v.isShown) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun TuningActivity.tap(label: String) {
        // The label itself, or the card it sits on.
        var v: View? = texts(window.decorView).firstOrNull { it.text.toString() == label }
        while (v != null && !v.isClickable) v = v.parent as? View
        assertTrue("no \"$label\" among ${texts(window.decorView).map { it.text }}", v != null)
        v!!.performClick()
        idle()
    }

    private fun TuningActivity.shot(name: String) {
        idle()
        val root = window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/screenshots").apply { mkdirs() }.let { File(it, "$name.png").outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) } }
    }

    private fun record(app: TestApp): File {
        val w = app.tuning.startRecording("0", true)
        repeat(30) { i ->
            val ts = 1_000_000_000L + i * 33_333_333L
            if (i == 3) w.event(RecordEvent.Capture(CaptureRecord(ts, "lock_wall", 8, ByteArray(64))))
            w.frame(FrameRecord(ts, 32, 24, floatArrayOf(30f, 30f, 16f, 12f), FloatArray(9) { if (it % 4 == 0) 1f else 0f },
                AttitudeSample(floatArrayOf(0f, 0f, 0f, 1f), ts, 3), 0f, 0f, 0f, 1_000_000L, ByteArray(32 * 24) { it.toByte() }))
            Thread.sleep(1)
        }
        return app.tuning.stopRecording()!!
    }

    @Test
    fun `recordings, sweep setup and results`() {
        val controller = Robolectric.buildActivity(TuningActivity::class.java)
        val app = controller.get().let { it.application as TestApp }
        app.tuning.recordings().forEach { app.tuning.delete(it.file) }
        val file = record(app)
        // A saved sweep over it, as a finished job leaves one.
        fun summary(locked: Float) = RunMetrics.Summary(300, 4, 10f, locked, listOf(100f, 90f, locked, locked / 2, null), listOf(3f, 2f, 2f, 2f, 0.2f),
            52f, 3, 640f, 1900f, null, 0.18f, 3f, 0.9f, 0, 31f, 58f, 14f, 40f, 2.1f, mapOf("STALE_OBSERVATION" to 41),
            (0 until 100).map { RunMetrics.TimelinePoint(it * 100L, 10f + it * 0.5f, it % 10 < 7) })
        val base = Knobs.DEFAULTS
        app.tuning.resultsFile(file).writeText(SavedSweep(base, mapOf("oblique.rectifyDeg" to listOf(20f, 30f, 40f)), listOf(
            SweepResult(base, summary(58f)), SweepResult(base + ("oblique.rectifyDeg" to 20f), summary(71f)), SweepResult(base + ("oblique.rectifyDeg" to 40f), summary(49f)),
        ), "2026-10-12T17:40:00Z").toJson().toString())

        val a = controller.setup().get()
        a.shot("9-recordings")
        assertTrue(texts(a.window.decorView).any { it.text.contains("30 frames") })
        a.tap("Sweep")
        a.shot("10-sweep-setup")
        assertTrue(texts(a.window.decorView).any { it.text.startsWith("3 replays") })
        a.onBackPressedDispatcher.onBackPressed(); idle()
        a.tap("Last results")
        a.shot("11-results")
        assertTrue(texts(a.window.decorView).any { it.text.startsWith("1. Rectify from 20°") })
        a.tap("1. Rectify from 20°")
        a.shot("12-detail")
        a.tap("Use these live")
        assertTrue(app.tuning.knobs["oblique.rectifyDeg"] == 20f)
        app.tuning.knobs = Knobs.DEFAULTS
    }
}

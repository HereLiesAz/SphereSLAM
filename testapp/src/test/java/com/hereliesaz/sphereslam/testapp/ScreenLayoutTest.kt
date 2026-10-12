package com.hereliesaz.sphereslam.testapp

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
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
 * Lays the real [TestScreen] out at phone size with a status bar, a display cutout, a navigation bar
 * and (for the notes) a keyboard, and asserts that no control lands in any of them. Each case also
 * writes a screenshot, with the system-bar bands shaded, to `testapp/build/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenLayoutTest {

    private val density = 3f
    private val statusPx = (32 * density).toInt()
    private val navPx = (48 * density).toInt()

    private fun bars(imeDp: Int = 0): WindowInsetsCompat = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0))
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navPx))
        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, statusPx, 0, 0))
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, (imeDp * density).toInt()))
        .build()

    private fun render(name: String, imeDp: Int = 0, setUp: (TestScreen) -> Unit): TestScreen {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val camera = object : View(activity) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f }
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.rgb(70, 66, 60))
                val s = minOf(width, height) * 0.6f
                canvas.drawRect((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2, p)
            }
        }
        val screen = TestScreen(activity, camera) { bars(imeDp) }
        activity.setContentView(screen.root)
        setUp(screen)
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            screen.padding.update()
            screen.root.requestLayout()
        }
        shadowOf(Looper.getMainLooper()).idle()

        val window = screen.root.rootView
        val bmp = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        window.draw(canvas)
        // The system bars as a phone draws them: translucent bands over the app.
        val band = Paint().apply { color = Color.argb(150, 255, 0, 80) }
        canvas.drawRect(0f, 0f, window.width.toFloat(), statusPx.toFloat(), band)
        val bottom = maxOf(navPx, (imeDp * density).toInt())
        canvas.drawRect(0f, (window.height - bottom).toFloat(), window.width.toFloat(), window.height.toFloat(), band)
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return screen
    }

    private fun assertClear(screen: TestScreen, bottomPx: Int) {
        val (clear, evidence) = screen.layoutCheck(android.graphics.Rect(0, statusPx, 0, bottomPx))!!
        assertTrue(evidence, clear)
        assertEquals(evidence, statusPx, screen.root.paddingTop)
        assertEquals(evidence, bottomPx, screen.root.paddingBottom)
    }

    private val wall = Guide.STEPS[1]

    @Test
    fun `capture step clears the status and navigation bars`() {
        val s = render("1-capture") { screen ->
            screen.header("Step 2 of 15", Guide.STEPS.indices.map { if (it == 0) Status.PASS else Status.UNTESTED }, 1, "Report sent 14:02:11")
            screen.status("Not locked", Status.UNTESTED, "", "41° up")
            screen.card(wall.title, wall.instruction, "Tilt down 11° to face the wall", null, Palette.TEXT)
            screen.actions(primary = Action("Capture", enabled = false) {}, tertiary = Action("Skip") {})
        }
        assertClear(s, navPx)
    }

    @Test
    fun `manual step with a countdown`() {
        val step = Guide.STEPS.first { it.awayMs > 0 }
        val s = render("2-manual") { screen ->
            screen.header("Step 12 of 15", Guide.STEPS.indices.map { if (it < 11) Status.PASS else Status.UNTESTED }, 11, "Report sent 14:09:40")
            screen.status("Target lost, searching", Status.UNTESTED, "PHOTOSPHERE · 4.1 ms · 0 inliers", "level")
            screen.card(step.title, step.instruction, "Keep looking away: 42 s left", null, Palette.TEXT)
            screen.actions(primary = Action("Yes, it did", enabled = false) {}, secondary = Action("No", enabled = false) {}, tertiary = Action("Skip") {})
        }
        assertClear(s, navPx)
    }

    @Test
    fun `failed step shows its evidence and Retry`() {
        val step = Guide.STEPS[2]
        val s = render("3-failed") { screen ->
            screen.header("Step 3 of 15", Guide.STEPS.indices.map { when { it < 2 -> Status.PASS; it == 2 -> Status.FAIL; else -> Status.UNTESTED } }, 2, "Report sent 14:04:02")
            screen.status("Locked on target", Status.PASS, "KPM KPM_FULL · 41.7 ms · 88 inliers", "level")
            screen.card(step.title, step.instruction, null,
                screen.verdict("Failed", Status.FAIL, listOf(
                    "KPM/flow median match time fits a 30 fps frame: p50 41.7 ms, p95 63.0 ms over 100 poses" to Status.FAIL,
                    "Flow tracking runs between full solves: FLOW poses while locked" to Status.PASS,
                )),
                Palette.FAIL)
            screen.actions(primary = Action("Continue") {}, secondary = Action("Retry") {})
        }
        assertClear(s, navPx)
    }

    @Test
    fun `finished run with the keyboard open over the notes`() {
        val s = render("4-finished-keyboard", imeDp = 300) { screen ->
            screen.header("Run complete", Guide.STEPS.indices.map { if (it == 14) Status.SKIPPED else Status.PASS }, 15, "Report sent 14:20:13")
            screen.status("Locked on target", Status.PASS, "KPM FLOW · 6.3 ms · 211 inliers", "88° down")
            screen.card("Run complete", "14 passed, 0 failed, 1 skipped. The report is filed automatically; add notes below if anything looked wrong.",
                null, "Report sent 14:20:13", Palette.MUTED, showNotes = true)
            screen.actions(primary = Action("Start over") {}, secondary = Action("Review steps") {})
        }
        assertClear(s, (300 * density).toInt())
    }

    @Test
    fun `stopped tracking says why and offers the fix`() {
        val step = Guide.STEPS[2]
        val c = Checklist().apply { set("lock_wall", Status.PASS, "locked") }
        val coach = Guide().coach(step, c, TrackingStatus("FATAL", "NATIVE_LIBRARY_UNAVAILABLE", null, Long.MAX_VALUE, null), 0L)!!
        val s = render("6-stopped") { screen ->
            screen.header("Step 3 of 15", Guide.STEPS.indices.map { if (it < 2) Status.PASS else Status.UNTESTED }, 2, "Report sent 09:32:29")
            screen.status("Tracking stopped", Status.FAIL, "", "6° up")
            screen.card(step.title, step.instruction, "0 of 100 tracked frames", null, Palette.TEXT, coach = coach.message, coachBlocking = coach.blocking)
            screen.actions(primary = Action(coach.action!!.label) {}, tertiary = Action("Skip") {})
        }
        assertClear(s, navPx)
    }

    @Test
    fun `not locked says where to point`() {
        val step = Guide.STEPS[2]
        val c = Checklist().apply { set("lock_wall", Status.PASS, "locked") }
        val coach = Guide().coach(step, c, TrackingStatus("REACQUIRING", null, null, Long.MAX_VALUE, 25_000L), 0L)!!
        val s = render("7-not-locked") { screen ->
            screen.header("Step 3 of 15", Guide.STEPS.indices.map { if (it < 2) Status.PASS else Status.UNTESTED }, 2, "Report sent 09:40:12")
            screen.status("Target lost, searching", Status.UNTESTED, "PHOTOSPHERE · 3.2 ms · 0 inliers", "level")
            screen.card(step.title, step.instruction, "37 of 100 tracked frames, median 9.4 ms", null, Palette.TEXT, coach = coach.message, coachBlocking = coach.blocking)
            screen.actions(primary = Action(coach.action!!.label) {}, tertiary = Action("Skip") {})
        }
        assertClear(s, navPx)
    }

    @Test
    fun `overview lists every step`() {
        val s = render("5-overview") { screen ->
            screen.header("Step 6 of 15", Guide.STEPS.indices.map { if (it < 5) Status.PASS else Status.UNTESTED }, 5, "Report sent 14:06:30")
            screen.status("Locked on target", Status.PASS, "KPM FLOW · 7.9 ms · 160 inliers", "level")
            screen.showOverview(true)
            val c = Checklist()
            screen.overview(Guide.STEPS.map { st -> Triple(st.title, if (Guide.STEPS.indexOf(st) < 5) Status.PASS else Status.UNTESTED, st.checks.map { screen.mark(c[it].title, if (Guide.STEPS.indexOf(st) < 5) Status.PASS else Status.UNTESTED) }) }, 5)
            screen.actions(primary = Action("Back to step") {})
        }
        assertClear(s, navPx)
    }
}

package com.hereliesaz.sphereslam.testapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * The live tuning panel, in the step card's place: the live measures of the running session, the
 * recording state, and one row per [Knob] with a slider. A change applies at once (the activity
 * restarts the session with the new values, keeping its target) and is recorded when recording.
 */
class TunePanel(private val context: Context) {
    var onKnob: (id: String, value: Float) -> Unit = { _, _ -> }
    var onReset: () -> Unit = {}

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = context.dp(16)
        setPadding(p, context.dp(12), p, p)
    }
    val view = ScrollView(context).apply {
        addView(content)
        setBackgroundColor(Palette.BG)
        visibility = View.GONE
    }

    private class Stat(val value: TextView, val label: TextView)
    private val stats = LinkedHashMap<String, Stat>()
    private val liveNote = Widgets.text(context, 12f, Palette.MUTED)
    private val recordingLine = Widgets.text(context, 14f, Palette.MUTED)
    private val applyingLine = Widgets.text(context, 13f, Palette.GOLD)

    private class Row(val knob: Knob, val value: TextView, val slider: SeekBar) {
        var dragging = false
    }
    private val rows = LinkedHashMap<String, Row>()

    init {
        content.addView(heading("Live measures"))
        content.addView(liveNote.apply { text = "Since the target was captured." }, Widgets.lp(context, top = 2, bottom = 8))
        val grid = GridLayout(context).apply { columnCount = 3 }
        for (name in listOf("Locked", "At 45°+", "Steepest lock", "Relock (median)", "Jitter", "Frame (median)")) {
            val v = Widgets.text(context, 20f, Palette.TEXT).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); text = "–" }
            val l = Widgets.text(context, 12f, Palette.MUTED).apply { text = name }
            stats[name] = Stat(v, l)
            grid.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, context.dp(6), context.dp(8), context.dp(6))
                addView(v); addView(l)
            }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
        content.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(recordingLine, Widgets.lp(context, top = 10))
        content.addView(applyingLine.apply { visibility = View.GONE }, Widgets.lp(context, top = 4))

        var group: String? = null
        for (k in Knobs.ALL) {
            if (k.group != group) {
                group = k.group
                content.addView(heading(k.group), Widgets.lp(context, top = 20, bottom = 4))
            }
            content.addView(knobRow(k), Widgets.lp(context, top = 6))
        }
        content.addView(Widgets.flatButton(context, "Reset all to the library's defaults").apply {
            setOnClickListener { onReset() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, context.dp(48)).apply { topMargin = context.dp(16) })
    }

    private fun heading(t: String) = Widgets.text(context, 13f, Palette.GOLD).apply {
        text = t.uppercase(Locale.ROOT)
        letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun knobRow(k: Knob): View {
        val label = Widgets.text(context, 15f, Palette.TEXT).apply { text = k.label }
        val value = Widgets.text(context, 15f, Palette.GOLD).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.END
        }
        val explain = Widgets.text(context, 12f, Palette.MUTED).apply { text = k.explain }
        val steps = Math.round((k.max - k.min) / k.step)
        val slider = SeekBar(context).apply {
            max = steps
            progressTintList = ColorStateList.valueOf(Palette.GOLD)
            thumbTintList = ColorStateList.valueOf(Palette.GOLD)
            progressBackgroundTintList = ColorStateList.valueOf(Palette.SEGMENT_TODO)
            contentDescription = k.label
        }
        val row = Row(k, value, slider)
        rows[k.id] = row
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = k.snap(k.min + progress * k.step)
                value.text = k.format(v)
                onKnob(k.id, v)
            }
            override fun onStartTrackingTouch(s: SeekBar) { row.dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) { row.dragging = false }
        })
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(value, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(12) })
            })
            addView(explain, Widgets.lp(context, top = 2))
            addView(slider, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(40)))
        }
    }

    /**
     * Shows [values] (the knobs in effect), the live [summary] (null before a target), the recording
     * line, and whether a change is waiting to be applied.
     */
    fun bind(values: Map<String, Float>, summary: RunMetrics.Summary?, recording: String, recordingActive: Boolean, applying: Boolean) {
        for (row in rows.values) {
            val v = row.knob.snap(values[row.knob.id] ?: row.knob.default)
            val text = row.knob.format(v) + if (v == row.knob.default) "" else "  ·  default ${row.knob.format(row.knob.default)}"
            if (row.value.text.toString() != text) row.value.text = text
            if (!row.dragging) {
                val p = Math.round((v - row.knob.min) / row.knob.step)
                if (row.slider.progress != p) row.slider.progress = p
            }
        }
        fun set(name: String, v: String) { stats[name]?.value?.let { if (it.text.toString() != v) it.text = v } }
        set("Locked", summary?.lockedPct?.let { "${RunMetrics.f0(it)}%" } ?: "–")
        set("At 45°+", summary?.steepLockedPct?.let { "${RunMetrics.f0(it)}%" } ?: "–")
        set("Steepest lock", summary?.takeIf { it.frames > 0 }?.let { "${RunMetrics.f0(it.steepestLockDeg)}°" } ?: "–")
        set("Relock (median)", summary?.relockMedianMs?.let { "${RunMetrics.f0(it)} ms" } ?: "–")
        set("Jitter", summary?.jitterPct?.let { "${RunMetrics.f1(it)}%" } ?: "–")
        set("Frame (median)", summary?.frameMsP50?.let { "${RunMetrics.f0(it)} ms" } ?: "–")
        liveNote.text = if (summary == null || summary.frames == 0) "Capture a target to start measuring."
        else "Since the target was captured: ${RunMetrics.f0(summary.seconds)} s, ${summary.relocks} relocks, ${summary.jumps} jumps, ${summary.dropped} frames skipped."
        if (recordingLine.text.toString() != recording) recordingLine.text = recording
        recordingLine.setTextColor(if (recordingActive) Palette.GOLD else Palette.MUTED)
        applyingLine.visibility = if (applying) View.VISIBLE else View.GONE
        applyingLine.text = "Applying: tracking restarts with these settings, keeping its target."
    }
}

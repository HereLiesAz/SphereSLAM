package com.hereliesaz.sphereslam.testapp

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Recordings and sweeps (docs/TESTAPP.md, Tuning). One screen at a time, like the guided run:
 *
 * - **Recordings**: what is on the phone, newest first, with what each holds; a running sweep on top.
 * - **Sweep setup**: up to three knobs to vary, each with its values; the base is the recording's own
 *   settings (or the live ones); the run count and an estimate of the time it takes.
 * - **Running**: which configuration, how far, what has scored so far; leaving the screen does not
 *   stop it.
 * - **Results**: every configuration ranked by the chosen measure, the report's state, and the top one
 *   applied to live tracking with one tap.
 * - **Detail**: one configuration against the baseline: locked % by viewing angle, both runs over
 *   time, every measure.
 */
@SuppressLint("SetTextI18n")
class TuningActivity : ComponentActivity() {

    private val app get() = application as TestApp
    private val tuning get() = app.tuning

    private sealed class Page {
        object List : Page()
        class Setup(val info: RecordingInfo) : Page()
        object Running : Page()
        class Results(val info: RecordingInfo, val saved: SavedSweep) : Page()
        class Detail(val info: RecordingInfo, val saved: SavedSweep, val result: SweepResult) : Page()
    }

    private var page: Page = Page.List
    private var ranking = Ranking.LOCKED

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var primary: TextView
    private lateinit var secondary: TextView
    private lateinit var tertiary: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (page == Page.Running) renderRunning(rebuild = false)
            keepScreenOn(tuning.job?.state == SweepJob.State.RUNNING)
            ui.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.SURFACE)
        }
        title = Widgets.text(this, 17f, Palette.TEXT).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        subtitle = Widgets.text(this, 13f, Palette.MUTED)
        val appBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(16), dp(8))
            addView(Widgets.flatButton(this@TuningActivity, "‹ Back").apply {
                contentDescription = "Back"
                setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)))
            addView(LinearLayout(this@TuningActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), 0, 0, 0)
                addView(title); addView(subtitle)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        scroll = ScrollView(this).apply { addView(body); setBackgroundColor(Palette.BG) }
        tertiary = Widgets.flatButton(this, "")
        secondary = Widgets.outlinedButton(this, "")
        primary = Widgets.filledButton(this, "")
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            addView(tertiary, LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)))
            addView(View(this@TuningActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(secondary, LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)).apply { marginEnd = dp(10) })
            addView(primary, LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)))
        }
        root.addView(appBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(Palette.OUTLINE) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
        root.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        SystemBarPadding(root).attach()
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (val p = page) {
                    is Page.Detail -> show(Page.Results(p.info, p.saved))
                    Page.List -> finish()
                    else -> show(Page.List)
                }
            }
        })
        show(if (tuning.job?.state == SweepJob.State.RUNNING) Page.Running else Page.List)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun show(p: Page) {
        page = p
        body.removeAllViews()
        scroll.scrollTo(0, 0)
        when (p) {
            Page.List -> renderList()
            is Page.Setup -> renderSetup(p.info)
            Page.Running -> renderRunning(rebuild = true)
            is Page.Results -> renderResults(p.info, p.saved)
            is Page.Detail -> renderDetail(p.info, p.saved, p.result)
        }
    }

    // --- recordings -----------------------------------------------------------------------------

    private fun renderList() {
        title.text = "Recordings"
        subtitle.text = "Replay the library over what the camera saw"
        val job = tuning.job
        if (job != null && job.state == SweepJob.State.RUNNING) {
            body.addView(card {
                addView(heading("Sweep running"))
                addView(line("${job.recording.name}: configuration ${job.run + 1} of ${job.sweep.configs.size}"))
                isClickable = true
                setOnClickListener { show(Page.Running) }
            }, Widgets.lp(this, bottom = 12))
        }
        val list = tuning.recordings()
        if (list.isEmpty()) {
            body.addView(line(
                "No recordings yet. In the test, open Tune and tap Record, capture a target and do what you want " +
                    "to tune (walk to a steep angle, look away and back). Stop, then come back here to sweep it.",
                Palette.TEXT,
            ))
        } else {
            body.addView(line("Tap a recording to set up a sweep over it.", Palette.MUTED), Widgets.lp(this, bottom = 8))
        }
        for (info in list) {
            val saved = tuning.results(info.file)
            body.addView(card {
                addView(Widgets.text(this@TuningActivity, 16f, Palette.TEXT).apply { text = stamp(info.header.startedAt) })
                addView(line(info.describe()))
                addView(line("Recorded on ${info.header.build} · ${Knobs.describe(info.header.knobs)}"))
                if (info.captures == 0) addView(line("No target was captured, so nothing can be measured.", Palette.FAIL))
                addView(LinearLayout(this@TuningActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    if (saved != null) addView(Widgets.flatButton(this@TuningActivity, "Last results").apply {
                        setOnClickListener { show(Page.Results(info, saved)) }
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)))
                    addView(View(this@TuningActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(Widgets.flatButton(this@TuningActivity, "Delete").apply {
                        setTextColor(Palette.FAIL)
                        setOnClickListener {
                            if (tuning.job?.let { it.state == SweepJob.State.RUNNING && it.recording.file == info.file } == true) return@setOnClickListener
                            tuning.delete(info.file)
                            show(Page.List)
                        }
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)))
                    addView(Widgets.outlinedButton(this@TuningActivity, "Sweep").apply {
                        Widgets.enable(this, info.captures > 0 && tuning.job?.state != SweepJob.State.RUNNING)
                        setOnClickListener { show(Page.Setup(info)) }
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)).apply { marginStart = dp(8) })
                }, Widgets.lp(this@TuningActivity, top = 4))
            }, Widgets.lp(this, bottom = 10))
        }
        actions(primary = null, tertiary = null)
    }

    // --- setup ----------------------------------------------------------------------------------

    private val chosen = LinkedHashMap<String, String>()
    private var baseLive = false

    private fun renderSetup(info: RecordingInfo) {
        title.text = "New sweep"
        subtitle.text = "${stamp(info.header.startedAt)} · ${Tuning.formatDuration(info.durationMs)}"
        if (chosen.isEmpty()) suggestedAxis(info)
        val base = if (baseLive) tuning.knobs else Knobs.DEFAULTS + info.header.knobs
        body.addView(line(
            "Every combination of the values below is replayed over the recording, on top of the base " +
                "settings, plus the base itself as the baseline. Vary up to $MAX_AXES settings.",
            Palette.TEXT,
        ), Widgets.lp(this, bottom = 12))
        body.addView(heading("Base"))
        body.addView(choice("As recorded: ${Knobs.describe(Knobs.DEFAULTS + info.header.knobs)}", !baseLive) { baseLive = false; show(page) })
        body.addView(choice("Live now: ${Knobs.describe(tuning.knobs)}", baseLive) { baseLive = true; show(page) })
        body.addView(heading("Vary"), Widgets.lp(this, top = 16))
        val summary = line("", Palette.GOLD)
        fun refresh() {
            val sweep = parseSweep(base)
            summary.text = when {
                chosen.isEmpty() -> "Choose at least one setting to vary."
                sweep == null -> "Check the values: each must be numbers in the setting's range, separated by commas."
                else -> {
                    val n = sweep.configs.size
                    "$n replays, about ${Tuning.formatDuration((info.durationMs * n * 1.3).toLong())} in all (each replay runs at the camera's pace)." +
                        if (n >= Sweep.MAX_RUNS) " Capped at ${Sweep.MAX_RUNS}." else ""
                }
            }
            actions(
                primary = Action("Start sweep", enabled = sweep != null && sweep.configs.size > 1) { start(info, sweep!!) },
                tertiary = Action("Cancel") { show(Page.List) },
            )
        }
        var group: String? = null
        for (k in Knobs.ALL) {
            if (k.group != group) {
                group = k.group
                body.addView(Widgets.text(this, 12f, Palette.MUTED).apply { text = k.group }, Widgets.lp(this, top = 10, bottom = 2))
            }
            val selected = k.id in chosen
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = if (selected) Widgets.rounded(Palette.SURFACE_HI, dp(10f)) else null
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isClickable = true
                setOnClickListener {
                    if (selected) chosen.remove(k.id) else if (chosen.size < MAX_AXES) chosen[k.id] = suggest(k, base[k.id] ?: k.default)
                    show(page)
                }
                addView(LinearLayout(this@TuningActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(Widgets.text(this@TuningActivity, 16f, if (selected) Palette.GOLD else Palette.MUTED).apply {
                        text = if (selected) "✓" else "○"
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(dp(28), WRAP_CONTENT))
                    addView(Widgets.text(this@TuningActivity, 15f, Palette.TEXT).apply { text = k.label }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(Widgets.text(this@TuningActivity, 13f, Palette.MUTED).apply { text = "base ${k.format(base[k.id] ?: k.default)}" })
                })
                if (selected) {
                    addView(EditText(this@TuningActivity).apply {
                        setText(chosen[k.id])
                        inputType = InputType.TYPE_CLASS_TEXT
                        setTextColor(Palette.TEXT)
                        setHintTextColor(Palette.MUTED)
                        textSize = 15f
                        hint = "values, e.g. ${suggest(k, k.default)}"
                        backgroundTintList = ColorStateList.valueOf(Palette.GOLD)
                        contentDescription = "Values for ${k.label}"
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                            override fun afterTextChanged(s: Editable?) { chosen[k.id] = s.toString(); refresh() }
                        })
                    }, Widgets.lp(this@TuningActivity, top = 4))
                    addView(Widgets.text(this@TuningActivity, 12f, Palette.MUTED).apply {
                        text = "${k.format(k.min)} to ${k.format(k.max)} in steps of ${k.format(k.step).removeSuffix(k.unit)}${k.unit}" +
                            if (k.isSwitch) " (0 off, 1 on)" else ""
                    })
                }
            }
            body.addView(row, Widgets.lp(this, top = 2))
        }
        body.addView(summary, Widgets.lp(this, top = 16))
        refresh()
    }

    /** Three values around [base]: two steps down, base, two steps up (both states for a switch). */
    private fun suggest(k: Knob, base: Float): String {
        val values = if (k.isSwitch) listOf(0f, 1f) else listOf(base - 2 * k.step, base, base + 2 * k.step).map { k.snap(it) }.distinct()
        return values.joinToString(", ") { if (it % 1f == 0f) it.toInt().toString() else String.format(Locale.ROOT, "%.1f", it) }
    }

    /** The steep-angle knob first: it is what the device runs are failing on. */
    private fun suggestedAxis(info: RecordingInfo) {
        val k = Knobs["oblique.rectifyDeg"] ?: return
        chosen[k.id] = suggest(k, info.header.knobs[k.id] ?: k.default)
    }

    private fun parseSweep(base: Map<String, Float>): Sweep? {
        if (chosen.isEmpty()) return null
        val axes = LinkedHashMap<String, List<Float>>()
        for ((id, text) in chosen) {
            val k = Knobs[id] ?: return null
            val values = text.split(',', ' ', ';').filter { it.isNotBlank() }.map { it.trim().toFloatOrNull() ?: return null }
            if (values.isEmpty() || values.any { it < k.min || it > k.max }) return null
            axes[id] = values.map { k.snap(it) }.distinct()
        }
        return Sweep(base, axes)
    }

    private fun start(info: RecordingInfo, sweep: Sweep) {
        val job = SweepJob(tuning, info, sweep) { tables, notes ->
            Reporter.post(BuildConfig.REPORT_URL, Reporter.authHeader(BuildConfig.REPORT_KEY), SweepJob.reportBody(tables, notes, packageName))
        }
        tuning.job = job
        job.start()
        app.run.log("sweep started over ${info.name}: ${sweep.configs.size} configurations")
        show(Page.Running)
    }

    // --- running --------------------------------------------------------------------------------

    private var runningKey = ""
    private val runningLines = ArrayList<TextView>()
    private var overall: ProgressBar? = null
    private var current: ProgressBar? = null

    private fun renderRunning(rebuild: Boolean) {
        val job = tuning.job ?: return show(Page.List)
        if (job.state == SweepJob.State.DONE) {
            tuning.results(job.recording.file)?.let { return show(Page.Results(job.recording, it)) }
        }
        val n = job.sweep.configs.size
        if (rebuild || runningKey.isEmpty()) {
            title.text = "Sweep"
            subtitle.text = "${stamp(job.recording.header.startedAt)} · $n configurations"
            runningLines.clear()
            repeat(3) { runningLines += line("", Palette.TEXT) }
            body.addView(runningLines[0])
            overall = bar().also { body.addView(it, Widgets.lp(this, top = 8)) }
            body.addView(runningLines[1], Widgets.lp(this, top = 12))
            current = bar().also { body.addView(it, Widgets.lp(this, top = 8)) }
            body.addView(runningLines[2], Widgets.lp(this, top = 12))
            body.addView(line(
                "The screen stays on while the sweep runs. You can leave this screen; the sweep carries on and " +
                    "its results are reported to the build's issue when it finishes.",
                Palette.MUTED,
            ), Widgets.lp(this, top = 16))
        }
        val elapsed = System.currentTimeMillis() - job.startedAtMs
        val doneFrac = (job.run + job.progress) / n
        val left = if (doneFrac > 0.02f) ((elapsed / doneFrac) * (1 - doneFrac)).toLong() else null
        runningLines[0].text = when (job.state) {
            SweepJob.State.RUNNING -> "Configuration ${job.run + 1} of $n · ${Tuning.formatDuration(elapsed)} so far" +
                (left?.let { " · about ${Tuning.formatDuration(it)} left" } ?: "")
            SweepJob.State.CANCELLED -> "Cancelled after ${job.results.size} of $n configurations."
            SweepJob.State.FAILED -> "Stopped: ${job.error}"
            SweepJob.State.DONE -> "Done."
        }
        runningLines[0].setTextColor(if (job.state == SweepJob.State.FAILED) Palette.FAIL else Palette.TEXT)
        overall?.progress = (doneFrac * 1000).toInt()
        current?.progress = (job.progress * 1000).toInt()
        runningLines[1].text = job.sweep.configs.getOrNull(job.run)?.let { "Now: " + SweepResult(it, RunMetrics().summary()).label(job.sweep.base) } ?: ""
        val partial = Ranking.LOCKED.sort(job.results)
        runningLines[2].text = if (partial.isEmpty()) "Scores appear as each configuration finishes." else
            "So far, by locked %:\n" + partial.take(5).joinToString("\n") { r ->
                "${RunMetrics.f0(r.summary.lockedPct)}%  ${r.label(job.sweep.base)}"
            }
        runningKey = "${job.run}"
        if (job.state == SweepJob.State.RUNNING) {
            actions(primary = null, secondary = Action("Cancel sweep") { job.cancel() })
        } else {
            actions(primary = Action("Recordings") { show(Page.List) })
        }
    }

    private fun bar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(Palette.GOLD)
        progressBackgroundTintList = ColorStateList.valueOf(Palette.SEGMENT_TODO)
    }

    // --- results --------------------------------------------------------------------------------

    private fun renderResults(info: RecordingInfo, saved: SavedSweep) {
        title.text = "Results"
        subtitle.text = "${stamp(info.header.startedAt)} · ${saved.results.size} configurations"
        tuning.job?.takeIf { it.recording.file == info.file }?.let { job ->
            body.addView(line(job.reportState, Palette.MUTED), Widgets.lp(this, bottom = 8))
        }
        body.addView(Widgets.text(this, 12f, Palette.MUTED).apply { text = "Rank by" })
        body.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@TuningActivity).apply {
                for (r in Ranking.entries) {
                    val b = if (r == ranking) Widgets.filledButton(this@TuningActivity, r.label) else Widgets.outlinedButton(this@TuningActivity, r.label)
                    b.setOnClickListener { ranking = r; show(page) }
                    addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) })
                }
            })
        }, Widgets.lp(this, top = 4, bottom = 12))
        val sorted = ranking.sort(saved.results)
        sorted.forEachIndexed { n, r ->
            val s = r.summary
            body.addView(card {
                addView(Widgets.text(this@TuningActivity, 15f, if (n == 0) Palette.GOLD else Palette.TEXT).apply {
                    text = "${n + 1}. ${r.label(saved.base)}"
                })
                addView(line(
                    "Locked ${RunMetrics.f0(s.lockedPct)}% · at 45°+ ${RunMetrics.f0(s.steepLockedPct)}% · steepest ${RunMetrics.f0(s.steepestLockDeg)}° · " +
                        "relock ${RunMetrics.f0(s.relockMedianMs)} ms · jitter ${RunMetrics.f1(s.jitterPct)}% · frame ${RunMetrics.f0(s.frameMsP50)} ms",
                ))
                isClickable = true
                setOnClickListener { show(Page.Detail(info, saved, r)) }
            }, Widgets.lp(this, bottom = 8))
        }
        val best = sorted.firstOrNull()
        actions(
            primary = best?.takeIf { it.values != saved.base }?.let { Action("Use #1 live") { useLive(it.values) } },
            secondary = Action("New sweep") { show(Page.Setup(info)) },
        )
    }

    private fun renderDetail(info: RecordingInfo, saved: SavedSweep, r: SweepResult) {
        title.text = r.label(saved.base)
        subtitle.text = stamp(info.header.startedAt)
        val baseline = saved.results.firstOrNull { it.values == saved.base }?.takeIf { it !== r }
        body.addView(heading("Locked % by viewing angle"))
        body.addView(line(if (baseline != null) "Gold: this configuration. Grey: the baseline." else "This configuration.", Palette.MUTED))
        body.addView(BandChartView(this).apply { set(r.summary, baseline?.summary) }, Widgets.lp(this, top = 8))
        body.addView(heading("Over time"), Widgets.lp(this, top = 16))
        body.addView(line("Viewing angle; gold while locked, grey while estimated.", Palette.MUTED))
        body.addView(line("This configuration", Palette.TEXT), Widgets.lp(this, top = 8))
        body.addView(TimelineView(this).apply { set(r.summary.timeline, "This configuration") })
        if (baseline != null) {
            body.addView(line("Baseline", Palette.TEXT), Widgets.lp(this, top = 8))
            body.addView(TimelineView(this).apply { set(baseline.summary.timeline, "Baseline") })
        }
        body.addView(heading("Every measure"), Widgets.lp(this, top = 16))
        body.addView(line(r.summary.lines().joinToString("\n"), Palette.TEXT))
        body.addView(heading("Settings"), Widgets.lp(this, top = 16))
        body.addView(line(Knobs.describe(r.values), Palette.TEXT))
        actions(primary = if (r.values == tuning.knobs) null else Action("Use these live") { useLive(r.values) })
    }

    private fun useLive(values: Map<String, Float>) {
        tuning.knobs = Knobs.DEFAULTS + values
        app.run.log("tuning: live settings now ${Knobs.describe(tuning.knobs)}")
        finish() // the test picks them up on resume
    }

    // --- widgets --------------------------------------------------------------------------------

    private fun actions(primary: Action?, secondary: Action? = null, tertiary: Action? = null) {
        bind(this.primary, primary); bind(this.secondary, secondary); bind(this.tertiary, tertiary)
    }

    private fun bind(button: TextView, action: Action?) {
        if (action == null) { button.visibility = View.GONE; return }
        button.visibility = View.VISIBLE
        button.text = action.label
        Widgets.enable(button, action.enabled)
        button.setOnClickListener { action.onClick() }
    }

    private fun card(build: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Widgets.rounded(Palette.SURFACE, dp(12f))
        setPadding(dp(14), dp(12), dp(14), dp(8))
        build()
    }

    private fun heading(t: String) = Widgets.text(this, 13f, Palette.GOLD).apply {
        text = t.uppercase(Locale.ROOT)
        letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun line(t: String, color: Int = Palette.MUTED) = Widgets.text(this, 13f, color).apply {
        text = t
        setLineSpacing(0f, 1.1f)
    }

    private fun choice(t: String, selected: Boolean, onPick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = if (selected) Widgets.rounded(Palette.SURFACE_HI, dp(10f)) else null
        minimumHeight = dp(48)
        isClickable = true
        setOnClickListener { onPick() }
        addView(Widgets.text(this@TuningActivity, 16f, if (selected) Palette.GOLD else Palette.MUTED).apply {
            text = if (selected) "●" else "○"
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), WRAP_CONTENT))
        addView(Widgets.text(this@TuningActivity, 14f, Palette.TEXT).apply { text = t }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stamp(iso: String): String = runCatching { STAMP.format(Instant.parse(iso)) }.getOrDefault(iso)

    private fun dp(v: Int) = (this as android.content.Context).dp(v)
    private fun dp(v: Float) = (this as android.content.Context).dp(v)

    private companion object {
        const val MAX_AXES = 3
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(ZoneId.systemDefault())
    }
}

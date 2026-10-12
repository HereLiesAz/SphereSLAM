package com.hereliesaz.sphereslam.testapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/** The app's colours: the logo's charcoal, gold and indigo, plus one red for failures. */
object Palette {
    val BG = Color.rgb(0x16, 0x17, 0x1C)
    val SURFACE = Color.rgb(0x1F, 0x20, 0x25)
    val SURFACE_HI = Color.rgb(0x2A, 0x2C, 0x34)
    val OUTLINE = Color.rgb(0x3A, 0x3C, 0x46)
    val GOLD = Color.rgb(0xD9, 0xB4, 0x6A)
    val INDIGO = Color.rgb(0x6E, 0x6B, 0xB8)
    val TEXT = Color.rgb(0xEC, 0xEC, 0xEE)
    val MUTED = Color.rgb(0x9A, 0x9C, 0xA6)
    val FAIL = Color.rgb(0xE5, 0x73, 0x73)
    val ON_GOLD = Color.rgb(0x1A, 0x16, 0x0C)
    /** An unfinished progress segment: 3.4:1 against SURFACE (non-text contrast minimum is 3:1). */
    val SEGMENT_TODO = Color.rgb(0x70, 0x73, 0x80)

    fun segment(status: Status): Int = if (status == Status.UNTESTED) SEGMENT_TODO else of(status)

    fun of(status: Status): Int = when (status) {
        Status.PASS -> GOLD
        Status.FAIL -> FAIL
        Status.SKIPPED -> MUTED
        Status.UNTESTED -> OUTLINE
    }

    /** For text marks: an untested mark has to stay readable, unlike the empty progress segment. */
    fun text(status: Status): Int = if (status == Status.UNTESTED) MUTED else of(status)

    fun symbol(status: Status): String = when (status) {
        Status.PASS -> "✓"
        Status.FAIL -> "✕"
        Status.SKIPPED -> "–"
        Status.UNTESTED -> "○"
    }
}

/**
 * Pads [view] so nothing it lays out sits under the status bar, navigation bar, display cutout or
 * keyboard. The padding is measured, not assumed: each band's height from the window's insets, less
 * however much of that band the view already clears where it sits in the window, so it is right
 * whether the window is edge-to-edge or an ancestor already fits the system windows. [insets]
 * supplies the window's insets (tests inject them).
 */
class SystemBarPadding(
    private val view: View,
    private val insets: (View) -> WindowInsetsCompat? = { ViewCompat.getRootWindowInsets(it) },
) {
    private val location = IntArray(2)

    fun attach() {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, i -> update(); i }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = ViewCompat.requestApplyInsets(v)
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    /** The bands in window pixels: left, top, right, bottom. */
    fun bands(): Rect? {
        val i = insets(view) ?: return null
        val bars = i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = i.getInsets(WindowInsetsCompat.Type.ime())
        return Rect(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
    }

    fun update() {
        val b = bands() ?: return
        val window = view.rootView
        if (window.width == 0 || view.width == 0) return
        view.getLocationInWindow(location)
        val left = max(0, b.left - location[0])
        val top = max(0, b.top - location[1])
        val right = max(0, b.right - (window.width - location[0] - view.width))
        val bottom = max(0, b.bottom - (window.height - location[1] - view.height))
        if (view.paddingLeft != left || view.paddingTop != top || view.paddingRight != right || view.paddingBottom != bottom) {
            view.setPadding(left, top, right, bottom)
        }
    }
}

/** One segment per step: done steps in their status colour, the current one outlined. */
class StepProgressView(context: Context) : View(context) {
    private var statuses: List<Status> = emptyList()
    private var current = -1
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(1.5f); color = Palette.TEXT }
    private val r = RectF()

    fun set(statuses: List<Status>, current: Int) {
        if (statuses == this.statuses && current == this.current) return
        this.statuses = statuses
        this.current = current
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = statuses.size
        if (n == 0) return
        val gap = context.dp(3f)
        val w = (width - gap * (n - 1)) / n
        val h = height.toFloat()
        val radius = h / 2
        statuses.forEachIndexed { i, s ->
            val x = i * (w + gap)
            r.set(x, 0f, x + w, h)
            fill.color = Palette.segment(s)
            canvas.drawRoundRect(r, radius, radius, fill)
            if (i == current) {
                r.inset(ring.strokeWidth / 2, ring.strokeWidth / 2)
                canvas.drawRoundRect(r, radius, radius, ring)
            }
        }
    }
}

/** A button's label and action; null hides the button. */
class Action(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The whole screen, top to bottom with nothing layered except the target outline on the camera image
 * it marks: app bar, step progress, camera, status line, the current step's card, and its actions.
 * The overview (every step and verdict) takes the card's place while it is open; the camera stays.
 */
@SuppressLint("SetTextI18n")
class TestScreen(
    private val context: Context,
    cameraView: View,
    insets: ((View) -> WindowInsetsCompat?)? = null,
) {
    // SURFACE behind the system bars continues the app bar above and the action bar below.
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Palette.SURFACE)
    }
    val padding = if (insets != null) SystemBarPadding(root, insets) else SystemBarPadding(root)

    private val stepCounter = text(13f, Palette.MUTED)
    private val reportLine = text(11f, Palette.MUTED)
    private val overviewButton = flatButton("All steps")
    private val progress = StepProgressView(context)

    private val camera = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
    private val statusLabel = text(13f, Palette.MUTED).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isSingleLine = true
    }
    private val telemetry = text(12f, Palette.MUTED).apply {
        typeface = Typeface.MONOSPACE
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val elevationLabel = text(12f, Palette.TEXT).apply {
        typeface = Typeface.MONOSPACE
        isSingleLine = true
    }

    private val cardTitle = text(20f, Palette.TEXT).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val cardBody = text(15f, Palette.TEXT).apply { setLineSpacing(0f, 1.15f) }
    private val cardCoach = text(14f, Palette.TEXT).apply {
        setLineSpacing(0f, 1.1f)
        // Read out when it appears or changes: it is the "what's wrong, what to do" line.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val p = context.dp(12)
        setPadding(p, context.dp(10), p, context.dp(10))
    }
    private val cardProgress = text(14f, Palette.GOLD)
    private val cardResult = text(14f, Palette.TEXT)
    private val notes = EditText(context).apply {
        hint = "Notes for this run (sent with the report)"
        setTextColor(Palette.TEXT)
        setHintTextColor(Palette.MUTED)
        textSize = 14f
        background = rounded(Palette.SURFACE_HI, context.dp(10f))
        val p = context.dp(12)
        setPadding(p, p, p, p)
        visibility = View.GONE
    }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = context.dp(20)
        setPadding(p, context.dp(16), p, context.dp(8))
        addView(cardTitle)
        addView(cardBody, lp(top = 8))
        addView(cardCoach, lp(top = 12))
        addView(cardProgress, lp(top = 12))
        addView(cardResult, lp(top = 6))
        addView(notes, lp(top = 12))
    }
    private val cardScroll = ScrollView(context).apply { addView(card); isFillViewport = true; setBackgroundColor(Palette.BG) }

    private val tertiary = flatButton("")
    private val secondary = outlinedButton("")
    private val primary = filledButton("")
    private val actions = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        val p = context.dp(16)
        setPadding(p, context.dp(8), p, context.dp(12))
        addView(tertiary, LinearLayout.LayoutParams(WRAP_CONTENT, context.dp(48)))
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(secondary, LinearLayout.LayoutParams(WRAP_CONTENT, context.dp(48)).apply { marginEnd = context.dp(10) })
        addView(primary, LinearLayout.LayoutParams(WRAP_CONTENT, context.dp(48)))
    }

    private val overviewList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = context.dp(16)
        setPadding(p, p, p, p)
    }
    private val overview = ScrollView(context).apply { addView(overviewList); visibility = View.GONE; setBackgroundColor(Palette.BG) }

    /** Called with the step index when the tester taps a step in the overview. */
    var onJump: (Int) -> Unit = {}
    var onNotes: (String) -> Unit = {}

    init {
        val appBar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.SURFACE)
            setPadding(context.dp(12), context.dp(8), context.dp(4), context.dp(8))
            addView(ImageView(context).apply {
                setImageResource(R.mipmap.ic_launcher_foreground)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = null
            }, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(10), 0, 0, 0)
                addView(text(17f, Palette.TEXT).apply {
                    text = "SphereSLAM device test"
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                addView(stepCounter)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(overviewButton, LinearLayout.LayoutParams(WRAP_CONTENT, context.dp(48)))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.SURFACE)
            setPadding(context.dp(16), 0, context.dp(16), context.dp(10))
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(6)))
            addView(reportLine, lp(top = 6))
        }
        camera.addView(cameraView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val telemetryBar = LinearLayout(context).apply {
            setBackgroundColor(Palette.SURFACE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
            addView(statusLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = context.dp(10) })
            addView(telemetry, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(elevationLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(12) })
        }
        root.addView(appBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(camera, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.15f))
        root.addView(telemetryBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(cardScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(overview, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(View(context).apply { setBackgroundColor(Palette.OUTLINE) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
        root.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        padding.attach()
        notes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onNotes(s?.toString().orEmpty())
        })
        overviewButton.setOnClickListener { showOverview(overview.visibility != View.VISIBLE) }
    }

    val overviewOpen: Boolean get() = overview.visibility == View.VISIBLE

    /** One check: its verdict mark and [text], in the verdict's colour. */
    fun mark(text: String, status: Status): CharSequence = verdict("${Palette.symbol(status)} $text", status, emptyList())

    /** A headline in [headStatus]'s colour, then one line per check in its own verdict's colour. */
    fun verdict(head: String, headStatus: Status, lines: List<Pair<String, Status>>): CharSequence {
        val out = SpannableStringBuilder()
        fun line(text: String, status: Status) {
            if (out.isNotEmpty()) out.append('\n')
            val start = out.length
            out.append(text)
            out.setSpan(ForegroundColorSpan(Palette.text(status)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        line(head, headStatus)
        for ((text, status) in lines) line("${Palette.symbol(status)} $text", status)
        return out
    }

    /** The overview takes the step card's place; the camera stays up (and keeps running). */
    fun showOverview(open: Boolean) {
        overview.visibility = if (open) View.VISIBLE else View.GONE
        cardScroll.visibility = if (open) View.GONE else View.VISIBLE
        // While open, the action bar's "Back to step" is the way out; one button for it, not two.
        overviewButton.visibility = if (open) View.GONE else View.VISIBLE
        overviewButton.text = if (open) "Back to step" else "All steps"
    }

    // --- rendering ------------------------------------------------------------------------------

    fun header(stepLabel: String, statuses: List<Status>, current: Int, report: String) {
        setIfChanged(stepCounter, stepLabel)
        progress.set(statuses, current)
        progress.contentDescription = "Progress: ${statuses.count { it == Status.PASS }} passed, " +
            "${statuses.count { it == Status.FAIL }} failed, ${statuses.count { it == Status.SKIPPED }} skipped, " +
            "${statuses.count { it == Status.UNTESTED }} to do"
        reportLine.text = report
    }

    /**
     * The status line under the camera: the tracking state in words ([label], coloured by [tone]), the
     * pose details, and the camera's elevation kept whole at the right.
     */
    fun status(label: String, tone: Status, detail: String, elevationText: String = "") {
        setIfChanged(statusLabel, "● $label")
        statusLabel.setTextColor(if (tone == Status.UNTESTED) Palette.TEXT else Palette.of(tone))
        setIfChanged(telemetry, detail)
        setIfChanged(elevationLabel, elevationText)
    }

    /**
     * [coach]: what is in the way and what to do, boxed under the instruction ([coachBlocking] colours
     * it as a stop). [resultTone]: the colour of [result] where it has no colour spans of its own.
     */
    fun card(
        title: String,
        body: String,
        progressLine: String?,
        result: CharSequence?,
        resultTone: Int,
        showNotes: Boolean = false,
        coach: String? = null,
        coachBlocking: Boolean = false,
    ) {
        setIfChanged(cardTitle, title)
        setIfChanged(cardBody, body)
        cardCoach.visibility = if (coach.isNullOrEmpty()) View.GONE else View.VISIBLE
        setIfChanged(cardCoach, coach.orEmpty())
        val accent = if (coachBlocking) Palette.FAIL else Palette.GOLD
        if (cardCoach.tag != accent) {
            cardCoach.tag = accent
            cardCoach.background = GradientDrawable().apply {
                setColor(Palette.SURFACE_HI)
                cornerRadius = context.dp(10f)
                setStroke(context.dp(1), accent)
            }
        }
        cardProgress.visibility = if (progressLine.isNullOrEmpty()) View.GONE else View.VISIBLE
        setIfChanged(cardProgress, progressLine.orEmpty())
        cardResult.visibility = if (result.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (cardResult.text.toString() != result?.toString().orEmpty()) cardResult.text = result ?: ""
        cardResult.setTextColor(resultTone)
        notes.visibility = if (showNotes) View.VISIBLE else View.GONE
    }

    fun actions(primary: Action?, secondary: Action? = null, tertiary: Action? = null) {
        bind(this.primary, primary)
        bind(this.secondary, secondary)
        bind(this.tertiary, tertiary)
    }

    /** One row per step: verdict, title, and each check's evidence. */
    private var overviewKey: String? = null

    fun overview(rows: List<Triple<String, Status, List<CharSequence>>>, current: Int) {
        // Rebuilt only when something in it changed: the render loop calls this five times a second.
        val key = "$current|" + rows.joinToString("|") { (t, st, d) -> "$t$st${d.joinToString()}" }
        if (key == overviewKey) return
        overviewKey = key
        overviewList.removeAllViews()
        overviewList.addView(text(13f, Palette.MUTED).apply { text = "Tap a step to go to it." }, lp(bottom = 8))
        rows.forEachIndexed { i, (title, status, details) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = if (i == current) rounded(Palette.SURFACE_HI, context.dp(10f)) else null
                val p = context.dp(10)
                setPadding(p, p, p, p)
                minimumHeight = context.dp(48)
                isClickable = true
                setOnClickListener { onJump(i) }
                addView(text(16f, Palette.text(status)).apply {
                    text = Palette.symbol(status)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(context.dp(28), WRAP_CONTENT))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(15f, Palette.TEXT).apply { text = "${i + 1}. $title" })
                    for (d in details) addView(text(12f, Palette.MUTED).apply { text = d })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            overviewList.addView(row, lp(bottom = 4))
        }
    }

    /** Every visible view that draws or takes input, for the layout check. */
    fun controls(): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE || v.width == 0 || v.height == 0) return
            if (v is ViewGroup) {
                if (v === camera) { out += v; return }
                for (k in 0 until v.childCount) walk(v.getChildAt(k))
            } else out += v
        }
        walk(root)
        return out
    }

    /**
     * The system-bar, cutout and keyboard bands as the platform reports them (left, top, right,
     * bottom px), read straight from the window rather than through [SystemBarPadding], so the layout
     * check is an independent measurement of what the padding was computed from. Null before attach.
     */
    fun platformBands(): Rect? {
        val wi = root.rootWindowInsets ?: return null
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val t = android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            val bars = wi.getInsets(t)
            val ime = wi.getInsets(android.view.WindowInsets.Type.ime())
            Rect(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
        } else {
            // API 28-29 reports the cutout separately; 26-27 has none to report.
            val cut = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                wi.displayCutout?.let { Rect(it.safeInsetLeft, it.safeInsetTop, it.safeInsetRight, it.safeInsetBottom) }
            } else null
            @Suppress("DEPRECATION")
            Rect(
                max(wi.systemWindowInsetLeft, cut?.left ?: 0),
                max(wi.systemWindowInsetTop, cut?.top ?: 0),
                max(wi.systemWindowInsetRight, cut?.right ?: 0),
                max(wi.systemWindowInsetBottom, cut?.bottom ?: 0),
            )
        }
    }

    /**
     * Whether every control clears [bands] (left, top, right, bottom px: the system bars, cutout and
     * keyboard), with the measurements. Null until laid out or when no bands are known yet.
     */
    fun layoutCheck(bands: Rect?): Pair<Boolean, String>? {
        if (root.width == 0) return null
        // Undecided until the window has reported its insets, so the first frame can't fail it.
        val b = bands ?: return null
        val window = root.rootView
        val w = window.width; val h = window.height
        val density = context.resources.displayMetrics.density
        fun dp(px: Int) = "${(px / density).toInt()} dp"
        val bands = listOf(Rect(0, 0, w, b.top), Rect(0, h - b.bottom, w, h), Rect(0, 0, b.left, h), Rect(w - b.right, 0, w, h))
            .filter { !it.isEmpty }
        // The part of each control actually on screen: clipped by its scrolling parents.
        val controls = controls()
        val offenders = controls.filter { v ->
            val r = Rect()
            v.getGlobalVisibleRect(r) && bands.any { Rect.intersects(it, r) }
        }
        val measured = "window ${dp(w)}×${dp(h)}, status/top ${dp(b.top)}, navigation/bottom ${dp(b.bottom)}, " +
            "sides ${dp(b.left)}/${dp(b.right)}; padding ${dp(root.paddingTop)} top, ${dp(root.paddingBottom)} bottom; " +
            "${controls.size} controls checked"
        return if (offenders.isEmpty()) true to measured
        else false to "$measured; ${offenders.size} overlap: " + offenders.joinToString { it.javaClass.simpleName }
    }

    // --- widgets --------------------------------------------------------------------------------

    private fun bind(button: TextView, action: Action?) {
        if (action == null) { button.visibility = View.GONE; return }
        button.visibility = View.VISIBLE
        setIfChanged(button, action.label)
        button.isEnabled = action.enabled
        button.alpha = if (action.enabled) 1f else 0.4f
        button.setOnClickListener { action.onClick() }
    }

    private fun setIfChanged(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }

    private fun text(sp: Float, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
    }

    private fun lp(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = context.dp(top); bottomMargin = context.dp(bottom)
    }

    private fun baseButton(label: String, fg: Int, bg: GradientDrawable?) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(fg)
        setPadding(context.dp(20), 0, context.dp(20), 0)
        minWidth = context.dp(64)
        isClickable = true
        isFocusable = true
        val mask = rounded(Color.WHITE, context.dp(24f))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), bg, mask)
    }

    private fun filledButton(label: String) = baseButton(label, Palette.ON_GOLD, rounded(Palette.GOLD, context.dp(24f)))
    private fun outlinedButton(label: String) = baseButton(label, Palette.TEXT, rounded(Color.TRANSPARENT, context.dp(24f)).apply {
        setStroke(context.dp(1), Palette.OUTLINE)
    })
    private fun flatButton(label: String) = baseButton(label, Palette.GOLD, null).apply { setPadding(context.dp(12), 0, context.dp(12), 0) }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

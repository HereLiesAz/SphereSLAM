package com.hereliesaz.sphereslam.testapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Locked % per viewing-angle band ([RunMetrics.BANDS]) for one configuration against the baseline:
 * paired bars, the configuration in gold, the baseline in grey, 0–100 % with quarter gridlines. A
 * band with too little time in it to judge shows no bar and a dash.
 */
class BandChartView(context: Context) : View(context) {
    private var shown: RunMetrics.Summary? = null
    private var baseline: RunMetrics.Summary? = null
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.OUTLINE; strokeWidth = context.dp(1f) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; textSize = context.dp(11f); textAlign = Paint.Align.CENTER }
    private val axis = Paint(label).apply { textAlign = Paint.Align.RIGHT }
    private val r = RectF()

    fun set(shown: RunMetrics.Summary, baseline: RunMetrics.Summary?) {
        this.shown = shown
        this.baseline = baseline
        contentDescription = "Locked by viewing angle: " + RunMetrics.BANDS.indices.joinToString("; ") { i ->
            "${RunMetrics.bandName(i)} ${RunMetrics.f0(shown.bandLockedPct[i])}%" +
                (baseline?.let { b -> ", baseline ${RunMetrics.f0(b.bandLockedPct[i])}%" } ?: "")
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(180))
    }

    override fun onDraw(canvas: Canvas) {
        val s = shown ?: return
        val left = context.dp(36f)
        val bottom = height - context.dp(22f)
        val top = context.dp(8f)
        val plotH = bottom - top
        for (q in 0..4) {
            val y = bottom - plotH * q / 4f
            canvas.drawLine(left, y, width.toFloat(), y, grid)
            canvas.drawText("${q * 25}", left - context.dp(6f), y + context.dp(4f), axis)
        }
        val n = RunMetrics.BANDS.size
        val slot = (width - left) / n
        val bw = slot * 0.3f
        for (i in 0 until n) {
            val cx = left + slot * (i + 0.5f)
            fun draw(v: Float?, x: Float, color: Int) {
                if (v == null) {
                    canvas.drawText("–", x + bw / 2, bottom - context.dp(4f), label)
                    return
                }
                bar.color = color
                r.set(x, bottom - plotH * v / 100f, x + bw, bottom)
                canvas.drawRoundRect(r, context.dp(2f), context.dp(2f), bar)
            }
            val b = baseline
            if (b != null) {
                draw(b.bandLockedPct[i], cx - bw - context.dp(2f), Palette.SEGMENT_TODO)
                draw(s.bandLockedPct[i], cx + context.dp(2f), Palette.GOLD)
            } else {
                draw(s.bandLockedPct[i], cx - bw / 2, Palette.GOLD)
            }
            canvas.drawText(RunMetrics.bandName(i), cx, height - context.dp(6f), label)
        }
    }
}

/**
 * A run over time: the viewing angle (0–90°, up) at each moment, gold where the pose was a visual
 * lock and grey where it was an estimate; gaps where nothing was placed. Shows where in the recording
 * a configuration holds the target, and at what angles it lets go.
 */
class TimelineView(context: Context) : View(context) {
    private var points: List<RunMetrics.TimelinePoint> = emptyList()
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.OUTLINE; strokeWidth = context.dp(1f) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dp(3f); strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; textSize = context.dp(11f); textAlign = Paint.Align.RIGHT }

    fun set(points: List<RunMetrics.TimelinePoint>, title: String) {
        this.points = points
        val locked = points.count { it.visual }
        contentDescription = "$title: viewing angle over ${points.size / 10} s, locked ${if (points.isEmpty()) 0 else locked * 100 / points.size}% of it"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(120))
    }

    override fun onDraw(canvas: Canvas) {
        if (points.size < 2) return
        val left = context.dp(36f)
        val top = context.dp(6f)
        val bottom = height - context.dp(8f)
        val plotH = bottom - top
        for (deg in listOf(0, 45, 90)) {
            val y = bottom - plotH * deg / 90f
            canvas.drawLine(left, y, width.toFloat(), y, grid)
            canvas.drawText("$deg°", left - context.dp(6f), y + context.dp(4f), label)
        }
        val t0 = points.first().timeMs
        val span = (points.last().timeMs - t0).coerceAtLeast(1L).toFloat()
        val w = width - left
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val aa = a.angleDeg ?: continue
            val bb = b.angleDeg ?: continue
            if (b.timeMs - a.timeMs > 4 * RunMetrics.TIMELINE_STEP_MS) continue
            line.color = if (b.visual) Palette.GOLD else Palette.SEGMENT_TODO
            canvas.drawLine(
                left + w * (a.timeMs - t0) / span, bottom - plotH * aa.coerceIn(0f, 90f) / 90f,
                left + w * (b.timeMs - t0) / span, bottom - plotH * bb.coerceIn(0f, 90f) / 90f,
                line,
            )
        }
    }
}

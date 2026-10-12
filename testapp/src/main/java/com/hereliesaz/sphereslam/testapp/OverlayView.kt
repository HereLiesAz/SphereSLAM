package com.hereliesaz.sphereslam.testapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.hereliesaz.sphereslam.camera.CameraFillCenter

/**
 * Draws the captured target's outline where SphereSLAM says it is: the page's corners (centred
 * fingerprint frame, 1 unit wide) through camera-from-fingerprint and the frame projection, then
 * from frame pixels onto the FILL_CENTER preview. On capture steps a square marks the capture region.
 */
class OverlayView(context: Context) : View(context) {
    @Volatile private var view: TrackedView? = null

    /** Draw the capture square: only while a capture step is waiting for Capture. */
    var showCaptureGuide = false
        set(value) {
            if (field != value) { field = value; postInvalidateOnAnimation() }
        }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(110, 255, 255, 255)
    }
    private val path = Path()

    fun show(v: TrackedView) {
        view = v
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val v = view ?: return
        if (showCaptureGuide) drawCaptureGuide(canvas, v)
        val m = v.cameraFromFingerprint ?: return
        val p = v.projection ?: return
        val h = v.pageAspect / 2f
        val corners = arrayOf(floatArrayOf(-0.5f, h), floatArrayOf(0.5f, h), floatArrayOf(0.5f, -h), floatArrayOf(-0.5f, -h))
        path.reset()
        corners.forEachIndexed { i, c ->
            val pt = project(m, p, c[0], c[1], v) ?: return
            if (i == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1])
        }
        path.close()
        canvas.drawPath(path, outline)
    }

    /** The square [Tracker.capture] takes, in view pixels. */
    private fun drawCaptureGuide(canvas: Canvas, v: TrackedView) {
        val side = minOf(v.frameWidth, v.frameHeight) * 0.6f
        val l = (v.frameWidth - side) / 2f / v.frameWidth
        val t = (v.frameHeight - side) / 2f / v.frameHeight
        val a = CameraFillCenter.frameToViewNormalized(l, t, v.frameWidth, v.frameHeight, width, height) ?: return
        val b = CameraFillCenter.frameToViewNormalized(1f - l, 1f - t, v.frameWidth, v.frameHeight, width, height) ?: return
        canvas.drawRect(a[0] * width, a[1] * height, b[0] * width, b[1] * height, guide)
    }

    /** Fingerprint point (x, y, 0) → view pixels, or null behind the camera. */
    private fun project(m: FloatArray, p: FloatArray, x: Float, y: Float, v: TrackedView): FloatArray? {
        val cx = m[0] * x + m[4] * y + m[12]
        val cy = m[1] * x + m[5] * y + m[13]
        val cz = m[2] * x + m[6] * y + m[14]
        val clipX = p[0] * cx + p[4] * cy + p[8] * cz + p[12]
        val clipY = p[1] * cx + p[5] * cy + p[9] * cz + p[13]
        val clipW = p[3] * cx + p[7] * cy + p[11] * cz + p[15]
        if (clipW <= 1e-6f) return null
        val u = (clipX / clipW + 1f) / 2f
        val vv = (1f - clipY / clipW) / 2f
        val n = CameraFillCenter.frameToViewNormalized(u, vv, v.frameWidth, v.frameHeight, width, height) ?: return null
        return floatArrayOf(n[0] * width, n[1] * height)
    }
}

package com.hereliesaz.sphereslam.overlay

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.hereliesaz.sphereslam.CoverageGlowProjection
import com.hereliesaz.sphereslam.SphereCoverage

/**
 * Drop-in transparent [GLSurfaceView] that renders the coverage glow above a camera preview. Add it
 * over your `PreviewView` in the same parent (it requests an alpha channel and draws on top), then
 * push coverage updates with [update].
 *
 * ```
 * val glow = CoverageGlowView(context)
 * parent.addView(glow) // above the camera preview
 * // per keyframe, from CameraAttitudeProvider + SphereCoverage:
 * glow.update(coverage.thinDirections(), headingDeg, elevationDeg, hFovDeg, vFovDeg)
 * ```
 *
 * For full control, host [CoverageGlowRenderer] on your own surface instead.
 */
class CoverageGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    /** The renderer, exposed so callers can tune [CoverageGlowRenderer.glowColor] etc. */
    val renderer = CoverageGlowRenderer()

    init {
        setEGLContextClientVersion(2)
        // Alpha channel + translucent holder so the camera preview shows through the gaps.
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /**
     * Project [directions] for the current view and redraw. Call per keyframe (or whenever coverage
     * or attitude changes); an empty [directions] clears the glow.
     *
     * @param directions unscanned directions, typically [SphereCoverage.thinDirections].
     * @param cameraHeadingDeg camera-axis compass heading, degrees.
     * @param cameraElevationDeg camera-axis elevation, degrees.
     * @param horizontalFovDeg preview horizontal field of view, degrees.
     * @param verticalFovDeg preview vertical field of view, degrees.
     */
    fun update(
        directions: List<SphereCoverage.Direction>,
        cameraHeadingDeg: Float,
        cameraElevationDeg: Float,
        horizontalFovDeg: Float,
        verticalFovDeg: Float,
    ) {
        val marks = CoverageGlowProjection.project(
            directions = directions,
            cameraHeadingDeg = cameraHeadingDeg,
            cameraElevationDeg = cameraElevationDeg,
            horizontalFovDeg = horizontalFovDeg,
            verticalFovDeg = verticalFovDeg,
        )
        renderer.setMarks(marks)
        requestRender()
    }
}

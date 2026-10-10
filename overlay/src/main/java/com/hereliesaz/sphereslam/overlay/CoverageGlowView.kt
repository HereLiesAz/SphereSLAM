package com.hereliesaz.sphereslam.overlay

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.hereliesaz.sphereslam.CoverageGlowProjection
import com.hereliesaz.sphereslam.PhotosphereMap

/**
 * Drop-in transparent [GLSurfaceView] that hazes the photosphere's unscanned tiles above a camera
 * preview. Add it over your `PreviewView` in the same parent (it requests an alpha channel and draws
 * on top), then push the map with [update]. Tiles the [PhotosphereMap] has accepted are left clear,
 * so the edge of the pink is the edge of what has been scanned.
 *
 * ~~~
 * val haze = CoverageGlowView(context)
 * parent.addView(haze) // above the camera preview
 * // per keyframe, from CameraAttitudeProvider + the session's PhotosphereMap:
 * haze.update(photosphereMap, headingDeg, elevationDeg, hFovDeg, vFovDeg)
 * ~~~
 *
 * For full control, host [CoverageGlowRenderer] on your own surface instead.
 */
class CoverageGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    /** The renderer, exposed so callers can tune [CoverageGlowRenderer.hazeColor] and [CoverageGlowRenderer.hazeAlpha]. */
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
     * Haze [map]'s tiles that still need an update, for the current view, and redraw. Call per
     * keyframe (or whenever the map or attitude changes). Nothing is drawn until the map is anchored
     * or once every tile is accepted.
     *
     * @param map the photosphere whose tiles are drawn ([PhotosphereMap.regionsNeedingUpdate]).
     * @param cameraHeadingDeg camera-axis compass heading, degrees.
     * @param cameraElevationDeg camera-axis elevation, degrees.
     * @param horizontalFovDeg preview horizontal field of view, degrees, inside `(0, 180)`.
     * @param verticalFovDeg preview vertical field of view, degrees, inside `(0, 180)`.
     */
    fun update(
        map: PhotosphereMap,
        cameraHeadingDeg: Float,
        cameraElevationDeg: Float,
        horizontalFovDeg: Float,
        verticalFovDeg: Float,
    ) {
        renderer.setTriangles(
            CoverageGlowProjection.projectRegions(
                regions = map.regionsNeedingUpdate(),
                cameraHeadingDeg = cameraHeadingDeg,
                cameraElevationDeg = cameraElevationDeg,
                horizontalFovDeg = horizontalFovDeg,
                verticalFovDeg = verticalFovDeg,
            ),
        )
        requestRender()
    }
}

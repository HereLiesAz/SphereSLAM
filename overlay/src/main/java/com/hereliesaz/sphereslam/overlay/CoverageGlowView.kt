package com.hereliesaz.sphereslam.overlay

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.hereliesaz.sphereslam.CoverageGlowProjection
import com.hereliesaz.sphereslam.PhotosphereMap

/**
 * Drop-in transparent [GLSurfaceView] that hazes the photosphere's unscanned tiles above a camera
 * preview. Add it over your `PreviewView` in the same parent (it requests an alpha channel and draws
 * on top), then push the map with [updateRegions]. Tiles the [PhotosphereMap] has accepted are left clear,
 * so the edge of the pink is the edge of what has been scanned.
 *
 * ~~~
 * val haze = CoverageGlowView(context)
 * parent.addView(haze) // above the camera preview
 * haze.renderer.setLens(CoverageGlowProjection.Lens.fromIntrinsics(shownIntrinsics))
 * // display-rate tracking from the rotation-vector sensor (see CameraAttitudeProvider):
 * haze.trackAtDisplayRate { t, out ->
 *     attitude.attitudeExtrapolator.worldFromCameraAt(t, cameraFromDisplay, out)
 * }
 * // per keyframe (whenever the map changes):
 * haze.updateRegions(photosphereMap)
 * ~~~
 *
 * For full control, host [CoverageGlowRenderer] on your own surface instead.
 */
class CoverageGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    /**
     * The renderer, exposed so callers can set its lens and tune [CoverageGlowRenderer.hazeColor],
     * [CoverageGlowRenderer.hazeAlpha] and [CoverageGlowRenderer.fillMode].
     */
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
     * Haze [map]'s tiles that still need an update ([PhotosphereMap.regionsNeedingUpdate]) through the
     * renderer's live projection, and redraw. Call per keyframe (whenever the map changes); the view
     * re-projects every frame from [trackAtDisplayRate]'s source, or from the last
     * [update]`(map, worldFromCamera, lens)` rotation. Nothing is drawn until the map is anchored,
     * a lens is set ([CoverageGlowRenderer.setLens]) and a rotation is known.
     */
    fun updateRegions(map: PhotosphereMap) {
        renderer.setRegions(map.regionsNeedingUpdate())
        requestRender()
    }

    /**
     * Haze [map]'s tiles that still need an update for a full camera rotation and redraw.
     *
     * @param worldFromCamera row-major 3×3, see [CoverageGlowProjection.worldFromCamera].
     * @param lens the view's projection ([CoverageGlowProjection.Lens]).
     */
    fun update(map: PhotosphereMap, worldFromCamera: FloatArray, lens: CoverageGlowProjection.Lens) {
        renderer.setRegions(map.regionsNeedingUpdate())
        renderer.setLens(lens)
        renderer.setWorldFromCamera(worldFromCamera)
        requestRender()
    }

    /**
     * Re-project every display frame from [source] (switches to continuous rendering), or stop with
     * null (back to render-on-update).
     */
    fun trackAtDisplayRate(source: CoverageGlowRenderer.RotationSource?) {
        renderer.rotationSource = source
        renderMode = if (source != null) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
        requestRender()
    }

    /**
     * Haze [map]'s tiles that still need an update, for the current view, and redraw — the
     * heading/elevation path (no roll, no straight up/down).
     *
     * @param map the photosphere whose tiles are drawn ([PhotosphereMap.regionsNeedingUpdate]).
     * @param cameraHeadingDeg camera-axis compass heading, degrees.
     * @param cameraElevationDeg camera-axis elevation, degrees.
     * @param horizontalFovDeg preview horizontal field of view, degrees, inside `(0, 180)`.
     * @param verticalFovDeg preview vertical field of view, degrees, inside `(0, 180)`.
     */
    @Deprecated("Ignores roll; use update(map, worldFromCamera, lens) or updateRegions + trackAtDisplayRate.")
    fun update(
        map: PhotosphereMap,
        cameraHeadingDeg: Float,
        cameraElevationDeg: Float,
        horizontalFovDeg: Float,
        verticalFovDeg: Float,
    ) {
        @Suppress("DEPRECATION")
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

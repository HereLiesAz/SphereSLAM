package com.hereliesaz.sphereslam.overlay

import com.hereliesaz.sphereslam.CoverageGlowProjection

/**
 * Turns projected coverage marks into the interleaved vertex data the glow renderer uploads. Kept
 * pure and framework-free so the packing is unit-testable without an EGL context.
 *
 * Each mark becomes one point-sprite vertex: `[ndcX, ndcY, intensity]`. Intensity is an alpha weight
 * the shader multiplies the glow by, so an off-screen direction (an edge nudge) can glow more faintly
 * than one actually in view.
 */
object CoverageGlowGeometry {

    /** Floats per vertex: x, y, intensity. */
    const val FLOATS_PER_VERTEX = 3

    /**
     * Pack [marks] into interleaved `[x, y, intensity, …]` vertex data.
     *
     * @param marks projected unscanned-direction marks from [CoverageGlowProjection.project].
     * @param onScreenIntensity glow weight for a mark within the frustum, typically `1`.
     * @param offScreenIntensity glow weight for a clamped edge mark, typically lower (a fainter nudge).
     * @return a float array of length `marks.size * FLOATS_PER_VERTEX`; empty when [marks] is empty.
     */
    fun buildVertexData(
        marks: List<CoverageGlowProjection.GlowMark>,
        onScreenIntensity: Float = 1f,
        offScreenIntensity: Float = 0.45f,
    ): FloatArray {
        if (marks.isEmpty()) return FloatArray(0)
        val out = FloatArray(marks.size * FLOATS_PER_VERTEX)
        var i = 0
        for (m in marks) {
            out[i++] = m.ndcX
            out[i++] = m.ndcY
            out[i++] = if (m.onScreen) onScreenIntensity else offScreenIntensity
        }
        return out
    }
}

package com.hereliesaz.sphereslam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPlacementTest {

    private fun pose(viewMatrix: FloatArray): SphereSlamStandaloneSession.Pose =
        SphereSlamStandaloneSession.Pose(
            timestampNs = 0L,
            pageNo = 0,
            cameraFromCanonical = viewMatrix,
            reprojectionError = 0f,
            inlierCount = 20,
            reference = SphereSlamStandaloneSession.Reference(
                pageNo = 0,
                imageNo = 0,
                geometry = SphereSlamPoseMath.pageGeometry(100, 50, 72f),
                canonicalFromPage = SphereSlamPoseMath.identity4(),
                featureCount = 64,
                physicallyMetric = true,
            ),
        )

    @Test
    fun `anchor places translation in the matrix and size in the extents`() {
        val a = OverlayPlacement.anchor(
            panXMeters = 0.3f,
            panYMeters = -0.2f,
            rotationZDeg = 0f,
            halfWidthMeters = 0.5f,
            halfHeightMeters = 0.25f,
        )
        // Column-major translation lives in indices 12,13,14; size is NOT in the matrix.
        assertEquals(0.3f, a.canonicalFromContent[12], 1e-6f)
        assertEquals(-0.2f, a.canonicalFromContent[13], 1e-6f)
        assertEquals(0f, a.canonicalFromContent[14], 1e-6f)
        assertEquals(0.5f, a.halfWidthMeters, 0f)
        assertEquals(0.25f, a.halfHeightMeters, 0f)
    }

    @Test
    fun `a 90 degree z rotation maps content +x to canonical +y`() {
        val a = OverlayPlacement.anchor(0f, 0f, 90f, 1f, 1f)
        // Column-major rotation: first column is the image of content +x.
        assertEquals(0f, a.canonicalFromContent[0], 1e-6f)
        assertEquals(1f, a.canonicalFromContent[1], 1e-6f)
    }

    @Test
    fun `identity view returns the anchor transform unchanged`() {
        val a = OverlayPlacement.anchor(0.1f, 0.2f, 30f, 0.4f, 0.3f)
        val vfc = OverlayPlacement.viewFromContent(pose(SphereSlamPoseMath.identity4()), a)
        assertArrayEquals(a.canonicalFromContent, vfc, 1e-6f)
    }

    @Test
    fun `view translation composes with the anchor translation`() {
        val a = OverlayPlacement.anchor(0.3f, 0f, 0f, 0.5f, 0.5f)
        // View that translates the canonical frame -1 m along z (camera 1 m in front).
        val view = SphereSlamPoseMath.identity4().also { it[14] = -1f }
        val vfc = OverlayPlacement.viewFromContent(pose(view), a)
        assertEquals(0.3f, vfc[12], 1e-6f) // x translation carried through
        assertEquals(-1f, vfc[14], 1e-6f) // z from the view
    }

    @Test
    fun `multiply matches a known column-major product`() {
        val scale2 = floatArrayOf(
            2f, 0f, 0f, 0f,
            0f, 2f, 0f, 0f,
            0f, 0f, 2f, 0f,
            0f, 0f, 0f, 1f,
        )
        val translate = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            5f, 6f, 7f, 1f,
        )
        // scale2 * translate: the translation column is scaled by 2.
        val p = OverlayPlacement.multiplyColumnMajor(scale2, translate)
        assertEquals(10f, p[12], 1e-6f)
        assertEquals(12f, p[13], 1e-6f)
        assertEquals(14f, p[14], 1e-6f)
    }
}

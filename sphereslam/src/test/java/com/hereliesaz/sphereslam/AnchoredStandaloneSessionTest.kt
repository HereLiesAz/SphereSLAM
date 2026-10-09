package com.hereliesaz.sphereslam

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchoredStandaloneSessionTest {

    private fun newSession(engine: FakeEngine) = SphereSlamStandaloneSession(
        frameWidth = 4,
        frameHeight = 2,
        calibration = SphereSlamCalibration(4f, 4f, 2f, 1f),
        engineFactory = SphereSlamStandaloneSession.EngineFactory { _, _, _ -> engine },
    )

    private fun addReference(session: SphereSlamStandaloneSession) {
        session.addReference(
            luma = ByteBuffer.allocateDirect(8),
            width = 4,
            height = 2,
            referenceWidthMeters = 1f,
            physicallyMetric = true,
        )
    }

    private val identityMatch = PlanarMatch(
        pageNo = 0,
        cameraFromPage3x4 = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 1000f,
        ),
        reprojectionError = 0.2f,
        inlierCount = 25,
    )

    /** KPM camera-from-page rotated +90 degrees about the page normal, page 1 m in front. */
    private val rotatedMatch = PlanarMatch(
        pageNo = 0,
        cameraFromPage3x4 = floatArrayOf(
            0f, -1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 1000f,
        ),
        reprojectionError = 0.2f,
        inlierCount = 25,
    )

    /**
     * Hand-computed camera-from-content for [rotatedMatch] with the anchor (pan 0.2, -0.1; 90 deg),
     * derived independently of OverlayPlacement / SphereSlamPoseMath:
     * - page is 1 m x 0.5 m, so the KPM translation re-centred by R*(500,250,0) is (-250, 500, 1000) mm;
     * - the OpenGL flip negates rows 1 and 2: camera-from-canonical rotation columns are
     *   (0,-1,0), (-1,0,0), (0,0,-1), translation (-0.25, -0.5, -1) m;
     * - composing the anchor (columns (0,1,0), (-1,0,0), (0,0,1); translation (0.2,-0.1,0)) gives
     *   rotation columns (-1,0,0), (0,1,0), (0,0,-1) and translation (0.1,-0.2,0) + (-0.25,-0.5,-1).
     */
    private val expectedRotatedCameraFromContent = floatArrayOf(
        -1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, -1f, 0f,
        -0.15f, -0.7f, -1f, 1f,
    )

    private fun rotatedAnchor() = OverlayPlacement.anchor(0.2f, -0.1f, 90f, 0.6f, 0.4f)

    @Test
    fun `no match without a placement`() {
        val engine = FakeEngine().apply { nextMatch = identityMatch }
        val anchored = AnchoredStandaloneSession(newSession(engine))
        addReference(anchored.base)
        assertFalse(anchored.hasPlacement)
        assertNull(anchored.match(ByteBuffer.allocateDirect(8), 1L))
    }

    @Test
    fun `placed content returns a model-view at its real-world size`() {
        val engine = FakeEngine().apply { nextMatch = rotatedMatch }
        val anchored = AnchoredStandaloneSession(newSession(engine))
        addReference(anchored.base)
        anchored.place(rotatedAnchor())

        val ap = anchored.match(ByteBuffer.allocateDirect(8), 1L)
        assertNotNull(ap)
        assertEquals(0.6f, ap!!.halfWidthMeters, 0f)
        assertEquals(0.4f, ap.halfHeightMeters, 0f)
        assertArrayEquals(expectedRotatedCameraFromContent, ap.cameraFromContent, 1e-5f)
    }

    @Test
    fun `a missed frame keeps the placement and the next match restores it identically`() {
        val engine = FakeEngine().apply { nextMatch = rotatedMatch }
        val anchored = AnchoredStandaloneSession(newSession(engine))
        addReference(anchored.base)
        anchored.place(rotatedAnchor())

        val before = anchored.match(ByteBuffer.allocateDirect(8), 1L)
        assertNotNull(before)
        assertArrayEquals(expectedRotatedCameraFromContent, before!!.cameraFromContent, 1e-5f)

        // Tracking lost: no page matches this frame.
        engine.nextMatch = null
        assertNull("a missed frame yields no pose", anchored.match(ByteBuffer.allocateDirect(8), 2L))
        assertTrue("but the placement is retained across the gap", anchored.hasPlacement)

        // Reacquired: the identical anchor snaps the content back to the same place and size.
        engine.nextMatch = rotatedMatch
        val after = anchored.match(ByteBuffer.allocateDirect(8), 3L)
        assertNotNull(after)
        assertArrayEquals(expectedRotatedCameraFromContent, after!!.cameraFromContent, 1e-5f)
        assertEquals(0.6f, after.halfWidthMeters, 0f)
        assertEquals(0.4f, after.halfHeightMeters, 0f)
    }

    @Test
    fun `a persisted placement restored into a fresh session reproduces the same pose`() {
        val original = rotatedAnchor()
        val persisted = original.canonicalFromContent

        val engine = FakeEngine().apply { nextMatch = rotatedMatch }
        val anchored = AnchoredStandaloneSession(newSession(engine))
        addReference(anchored.base)
        val restored = OverlayPlacement.fromCanonicalTransform(
            persisted,
            original.halfWidthMeters,
            original.halfHeightMeters,
        )
        // Mutating the persisted array after restore must not move the restored content.
        persisted[12] = 99f
        anchored.place(restored)

        assertEquals(original, anchored.placement)
        val ap = requireNotNull(anchored.match(ByteBuffer.allocateDirect(8), 4L))
        assertArrayEquals(expectedRotatedCameraFromContent, ap.cameraFromContent, 1e-5f)
        assertEquals(0.6f, ap.halfWidthMeters, 0f)
        assertEquals(0.4f, ap.halfHeightMeters, 0f)
    }

    @Test
    fun `clearPlacement forgets content but leaves the track`() {
        val engine = FakeEngine().apply { nextMatch = identityMatch }
        val anchored = AnchoredStandaloneSession(newSession(engine))
        addReference(anchored.base)
        anchored.place(OverlayPlacement.anchor(0f, 0f, 0f, 0.5f, 0.5f))
        anchored.clearPlacement()
        assertFalse(anchored.hasPlacement)
        assertNull(anchored.match(ByteBuffer.allocateDirect(8), 1L))
        assertTrue("the reference/track is unaffected", anchored.base.hasReference)
    }

    @Test
    fun `close clears the placement`() {
        val engine = FakeEngine().apply { nextMatch = identityMatch }
        val anchored = AnchoredStandaloneSession(newSession(engine))
        addReference(anchored.base)
        anchored.place(OverlayPlacement.anchor(0f, 0f, 0f, 0.5f, 0.5f))
        anchored.close()
        assertFalse(anchored.hasPlacement)
    }

    private class FakeEngine : SphereSlamEngine {
        override val frameWidth: Int = 4
        override val frameHeight: Int = 2
        override val calibration = SphereSlamCalibration(4f, 4f, 2f, 1f)
        override var isReady: Boolean = true
            private set

        var nextMatch: PlanarMatch? = null

        override fun addPage(luma: ByteBuffer, width: Int, height: Int, page: PlanarPage): Int = 64
        override fun match(luma: ByteBuffer): PlanarMatch? = nextMatch
        override fun close() {
            isReady = false
        }
    }
}

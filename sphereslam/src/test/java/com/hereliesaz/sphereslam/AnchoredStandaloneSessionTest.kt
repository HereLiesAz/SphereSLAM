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
        val engine = FakeEngine().apply { nextMatch = identityMatch }
        val anchored = AnchoredStandaloneSession(newSession(engine))
        addReference(anchored.base)
        anchored.place(OverlayPlacement.anchor(0f, 0f, 0f, 0.5f, 0.25f))

        val ap = anchored.match(ByteBuffer.allocateDirect(8), 1L)
        assertNotNull(ap)
        assertEquals(0.5f, ap!!.halfWidthMeters, 0f)
        assertEquals(0.25f, ap.halfHeightMeters, 0f)
        // With an in-plane anchor, viewFromContent equals the session's view composed with it.
        val expected = OverlayPlacement.cameraFromContent(ap.pose, anchored.placement!!)
        assertArrayEquals(expected, ap.cameraFromContent, 1e-6f)
    }

    @Test
    fun `a missed frame keeps the placement and the next match restores it identically`() {
        val engine = FakeEngine().apply { nextMatch = identityMatch }
        val anchored = AnchoredStandaloneSession(newSession(engine))
        addReference(anchored.base)
        anchored.place(OverlayPlacement.anchor(0.2f, -0.1f, 15f, 0.6f, 0.4f))

        val before = anchored.match(ByteBuffer.allocateDirect(8), 1L)
        assertNotNull(before)

        // Tracking lost: no page matches this frame.
        engine.nextMatch = null
        assertNull("a missed frame yields no pose", anchored.match(ByteBuffer.allocateDirect(8), 2L))
        assertTrue("but the placement is retained across the gap", anchored.hasPlacement)

        // Reacquired: the identical anchor snaps the content back to the same place and size.
        engine.nextMatch = identityMatch
        val after = anchored.match(ByteBuffer.allocateDirect(8), 3L)
        assertNotNull(after)
        assertArrayEquals(before!!.cameraFromContent, after!!.cameraFromContent, 1e-6f)
        assertEquals(before.halfWidthMeters, after.halfWidthMeters, 0f)
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

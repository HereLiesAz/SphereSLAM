package com.hereliesaz.sphereslam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class SphereSlamOperationalTest {

    private class ProbeEngine(
        override val frameWidth: Int,
        override val frameHeight: Int,
        override val calibration: SphereSlamCalibration,
        override val isReady: Boolean,
    ) : SphereSlamEngine {
        var closed = false
        override fun addPage(luma: ByteBuffer, width: Int, height: Int, page: PlanarPage): Int = -1
        override fun match(luma: ByteBuffer): PlanarMatch? = null
        override fun close() { closed = true }
    }

    @Test
    fun `unavailable native code is not operational and creates nothing`() {
        var created = false
        val ok = SphereSlam.probe(640, 480, { false }) { w, h, c -> created = true; ProbeEngine(w, h, c, true) }
        assertFalse(ok)
        assertFalse(created)
    }

    @Test
    fun `a ready engine is operational and is always closed`() {
        var engine: ProbeEngine? = null
        assertTrue(SphereSlam.probe(640, 480, { true }) { w, h, c -> ProbeEngine(w, h, c, true).also { engine = it } })
        assertTrue(engine!!.closed)
        assertEquals(320f, engine!!.calibration.cx, 0f)
        assertEquals(240f, engine!!.calibration.cy, 0f)
    }

    @Test
    fun `an engine without a native session is not operational and is still closed`() {
        var engine: ProbeEngine? = null
        assertFalse(SphereSlam.probe(640, 480, { true }) { w, h, c -> ProbeEngine(w, h, c, false).also { engine = it } })
        assertTrue(engine!!.closed)
    }

    @Test
    fun `isOperational never throws on a JVM without the native library`() {
        assertFalse(SphereSlam.isOperational())
        assertFalse(SphereSlam.isOperational(-1, 0))
    }

    @Test
    fun `Observation forTesting copies its pose and validates length`() {
        val pose = FloatArray(12) { it.toFloat() }
        val o = SphereSlamTracker.Observation.forTesting(timestampNs = 5L, error = 1.5f, inliers = 20, cameraFromPage3x4 = pose)
        pose[0] = 99f
        assertArrayEquals(FloatArray(12) { it.toFloat() }, o.cameraFromPage3x4, 0f)
        assertEquals(0, o.pageNo)
        assertEquals(20, o.inliers)
        assertThrows(IllegalArgumentException::class.java) {
            SphereSlamTracker.Observation.forTesting(timestampNs = 0L, error = 0f, inliers = 0, cameraFromPage3x4 = FloatArray(9))
        }
    }
}

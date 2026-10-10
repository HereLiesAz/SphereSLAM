package com.hereliesaz.sphereslam.attitude

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttitudeSampleBufferTest {

    @Test
    fun `a four-element vector is normalized and timestamped`() {
        val b = AttitudeSampleBuffer()
        b.accuracy = 3
        b.ingestRotationVector(floatArrayOf(0f, 0f, 0f, 2f, 0.5f), 123L)
        val s = b.latest()!!
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), s.quaternion, 0f)
        assertEquals(123L, s.timestampNs)
        assertEquals(3, s.accuracy)
        assertEquals(7L, s.ageNs(130L))
    }

    @Test
    fun `a three-element vector recovers w`() {
        val b = AttitudeSampleBuffer()
        b.ingestRotationVector(floatArrayOf(0.6f, 0f, 0f), 1L)
        assertArrayEquals(floatArrayOf(0.6f, 0f, 0f, 0.8f), b.latest()!!.quaternion, 1e-6f)
    }

    @Test
    fun `non-finite input is dropped and clear forgets the sample`() {
        val b = AttitudeSampleBuffer()
        b.ingestRotationVector(floatArrayOf(Float.NaN, 0f, 0f, 1f), 1L)
        assertNull(b.latest())
        b.ingestRotationVector(floatArrayOf(0f, 0f, 0f, 1f), 1L)
        b.clear()
        assertNull(b.latest())
        assertEquals(AttitudeSample.ACCURACY_UNKNOWN, b.accuracy)
    }

    @Test
    fun `sample quaternion is defensively copied`() {
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        val s = AttitudeSample(q, 0L)
        q[3] = 5f
        s.quaternion[3] = 7f
        assertEquals(1f, s.quaternion[3], 0f)
    }
}

package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertEquals
import org.junit.Test

/** [KpmMatchScale.referenceSize] and [KpmMatchScale.resampleArea]. */
class KpmReferenceScaleTest {

    @Test
    fun `the long side is capped and the aspect kept`() {
        assertEquals(960 to 720, KpmMatchScale.referenceSize(4000, 3000, 960))
        assertEquals(540 to 960, KpmMatchScale.referenceSize(2250, 4000, 960))
        assertEquals(800 to 600, KpmMatchScale.referenceSize(800, 600, 960))
        assertEquals(4000 to 3000, KpmMatchScale.referenceSize(4000, 3000, 0))
    }

    @Test
    fun `area resampling keeps a ramp's values and spans the whole image`() {
        val w = 1000
        val h = 10
        val src = ByteArray(w * h) { i -> ((i % w) * 255 / (w - 1)).toByte() }
        val dst = KpmMatchScale.resampleArea(src, w, h, 300, 3)
        assertEquals(900, dst.remaining())
        // Output pixel x covers source [x·10/3, (x+1)·10/3): its mean is the ramp at its centre.
        for (x in listOf(0, 150, 299)) {
            val expected = ((x + 0.5) * w / 300 - 0.5) * 255 / (w - 1)
            assertEquals(expected, (dst.get(x).toInt() and 0xff).toDouble(), 1.6)
        }
    }

    @Test
    fun `a uniform image stays uniform`() {
        val src = ByteArray(997 * 613) { 77 }
        val dst = KpmMatchScale.resampleArea(src, 997, 613, 412, 253)
        for (i in 0 until dst.remaining()) assertEquals(77, dst.get(i).toInt())
    }
}

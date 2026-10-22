package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LumaContrastTest {

    private fun frame(values: IntArray) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `a dim canvas spanning a narrow band is stretched to the full range`() {
        // A black board in a dim bar: luma 10..50, evenly spread.
        val luma = frame(IntArray(4100) { 10 + it % 41 })
        val lut = LumaContrast.stretchLut(luma)
        assertNotNull(lut)
        val out = LumaContrast.apply(luma, lut!!)
        val min = out.minOf { it.toInt() and 0xFF }
        val max = out.maxOf { it.toInt() and 0xFF }
        assertEquals(0, min)
        assertEquals(255, max)
    }

    @Test
    fun `the stretch keeps order, so pixel-pair descriptors are unchanged`() {
        val luma = frame(IntArray(4100) { 10 + it % 41 })
        val lut = LumaContrast.stretchLut(luma)!!
        for (v in 11..50) {
            val prev = lut[v - 1].toInt() and 0xFF
            val cur = lut[v].toInt() and 0xFF
            assert(cur >= prev) { "lut not monotonic at $v" }
        }
    }

    @Test
    fun `a well exposed frame is left alone`() {
        val luma = frame(IntArray(2560) { it % 256 })
        assertNull(LumaContrast.stretchLut(luma))
        assertSame(luma, LumaContrast.stretchedOrSame(luma))
    }

    @Test
    fun `a blank frame is not amplified into noise`() {
        val luma = frame(IntArray(1000) { 30 + it % 3 })
        assertNull(LumaContrast.stretchLut(luma))
    }

    @Test
    fun `a dim canvas that failed the contrast gate passes it once stretched`() {
        // 128x512 frame whose luma alternates 20/30: standard deviation 5, under the 12 floor.
        val w = 128; val h = 512
        val luma = frame(IntArray(w * h) { i -> if ((i % w + i / w) % 2 == 0) 20 else 30 })
        val before = TargetQuality.analyze(luma, w, h, TargetQualityConfig())
        val after = TargetQuality.analyze(LumaContrast.stretchedOrSame(luma), w, h, TargetQualityConfig())
        assert(TargetQualityIssue.LOW_CONTRAST in before.blockingIssues)
        assert(TargetQualityIssue.LOW_CONTRAST !in after.blockingIssues)
    }
}

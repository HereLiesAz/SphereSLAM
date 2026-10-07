package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileMatcherTest {

    private fun match(key: String, inliers: Int) =
        TileMatcher.Match(key, floatArrayOf(0f), inliers)

    @Test
    fun `bestOf picks the highest-inlier match`() {
        val best = TileMatcher.bestOf(
            listOf(match("a", 12), match("b", 40), match("c", 25)),
        )
        assertEquals("b", best?.key)
        assertEquals(40, best?.inliers)
    }

    @Test
    fun `bestOf of an empty list is null`() {
        assertNull(TileMatcher.bestOf<String>(emptyList()))
    }
}

package com.hereliesaz.sphereslam.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelHelpersTest {
    @Test
    fun `network input size comes from the graph with a dynamic-dim fallback`() {
        assertEquals(400 to 600, LowLightEnhancer.networkInputSize(longArrayOf(1, 3, 400, 600), 256))
        assertEquals(256 to 256, LowLightEnhancer.networkInputSize(longArrayOf(1, 3, -1, -1), 256))
        assertEquals(256 to 256, LowLightEnhancer.networkInputSize(null, 256))
    }

    @Test
    fun `image output is picked by name, then by shape`() {
        val shape = longArrayOf(1, 3, 400, 600)
        assertEquals(1, LowLightEnhancer.selectImageOutput(listOf("curves", "enhanced_image"), listOf(longArrayOf(1, 24, 400, 600), shape), 400, 600))
        assertEquals(0, LowLightEnhancer.selectImageOutput(listOf("out", "curves"), listOf(shape, longArrayOf(1, 24, 400, 600)), 400, 600))
        assertEquals(-1, LowLightEnhancer.selectImageOutput(listOf("curves"), listOf(longArrayOf(1, 24, 400, 600)), 400, 600))
    }

    @Test
    fun `depth downscale keeps the long side within the limit`() {
        val (stride, dims) = DepthEstimator.downscale(256, 256, 100)
        assertEquals(3, stride)
        assertTrue(dims.first <= 100 && dims.second <= 100)
        assertEquals(1 to (256 to 256), DepthEstimator.downscale(256, 256, 256))
        val (_, wide) = DepthEstimator.downscale(200, 383, 128)
        assertTrue(wide.second <= 128)
    }

    @Test
    fun `extracted copy is reused only when it matches the asset size`() {
        assertTrue(ModelAssets.isUpToDate(true, 100L, 100L))
        assertFalse(ModelAssets.isUpToDate(true, 90L, 100L))
        assertTrue(ModelAssets.isUpToDate(true, 90L, -1L))
        assertFalse(ModelAssets.isUpToDate(false, 0L, 100L))
    }
}

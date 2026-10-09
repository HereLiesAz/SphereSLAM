package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthScaleFitTest {

    @Test
    fun `recovers a known affine inverse-depth relation`() {
        // invDepth = 3*(1/Z) + 0.5, sampled at several depths.
        val a = 3f
        val b = 0.5f
        val zs = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f, 5f, 8f)
        val oneOverZ = FloatArray(zs.size) { 1f / zs[it] }
        val invDepth = FloatArray(zs.size) { a * oneOverZ[it] + b }

        val fit = DepthScaleFit.fit(oneOverZ, invDepth)
        assertNotNull(fit)
        assertEquals(a, fit!!.a, 1e-3f)
        assertEquals(b, fit.b, 1e-3f)
        // And it inverts: an off-plane sample's invDepth returns its Z.
        val z = fit.depthFor(a * (1f / 4f) + b)
        assertNotNull(z)
        assertEquals(4f, z!!, 1e-2f)
    }

    @Test
    fun `too few samples returns null`() {
        val fit = DepthScaleFit.fit(floatArrayOf(1f, 0.5f), floatArrayOf(1f, 0.5f))
        assertNull(fit)
    }

    @Test
    fun `degenerate (all same depth) returns null`() {
        val oneOverZ = FloatArray(8) { 0.5f }
        val invDepth = FloatArray(8) { 2f }
        assertNull(DepthScaleFit.fit(oneOverZ, invDepth))
    }

    @Test
    fun `inverted sign (non-positive a) is rejected`() {
        // invDepth DEcreasing with 1/Z -> a < 0, physically wrong, must be refused.
        val oneOverZ = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1.0f, 1.2f, 1.4f)
        val invDepth = FloatArray(oneOverZ.size) { -2f * oneOverZ[it] + 5f }
        assertNull(DepthScaleFit.fit(oneOverZ, invDepth))
    }

    @Test
    fun `depthFor rejects at-infinity and behind-camera samples`() {
        val fit = DepthScaleFit.Fit(a = 3f, b = 0.5f)
        // invDepth == b -> invZ 0 -> null; below b -> negative -> null.
        assertNull(fit.depthFor(0.5f))
        assertNull(fit.depthFor(0.1f))
        assertTrue((fit.depthFor(2f) ?: -1f) > 0f)
    }
}

class DepthScaleFitRobustTest {
    private val a = 3f
    private val b = 0.5f

    @Test
    fun `gross outliers do not drag the fit`() {
        val zs = FloatArray(20) { 0.5f + it * 0.4f }
        val x = FloatArray(zs.size) { 1f / zs[it] }
        val y = FloatArray(zs.size) { a * x[it] + b }
        // Corrupt 30% of samples badly.
        for (i in intArrayOf(1, 4, 7, 10, 13, 16)) y[i] += if (i % 2 == 0) 5f else -3f
        val fit = DepthScaleFit.fit(x, y)!!
        assertEquals(a, fit.a, 1e-3f)
        assertEquals(b, fit.b, 1e-3f)
    }

    @Test
    fun `degeneracy check is scale invariant`() {
        // Tiny absolute spread but a healthy relative one: plain |denominator| thresholds would refuse.
        val x = FloatArray(8) { 1e-4f * (1f + it) }
        val y = FloatArray(8) { 2000f * x[it] + 0.1f }
        val fit = DepthScaleFit.fit(x, y)
        assertNotNull(fit)
        assertEquals(2000f, fit!!.a, 1f)
        // Negligible relative spread at a large magnitude is refused.
        val flat = FloatArray(8) { 1000f + 1e-4f * it }
        assertNull(DepthScaleFit.fit(flat, FloatArray(8) { 2f * flat[it] }))
    }
}

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

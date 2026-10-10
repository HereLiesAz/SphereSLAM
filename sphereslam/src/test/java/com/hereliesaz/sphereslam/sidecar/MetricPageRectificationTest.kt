package com.hereliesaz.sphereslam.sidecar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricPageRectificationTest {
    @Test
    fun `fronto-parallel wall derives a metric page with physical aspect`() {
        val geometry = MetricPageRectification.deriveGeometry(
            imageWidth = 1000,
            imageHeight = 800,
            fx = 800f,
            fy = 800f,
            cx = 500f,
            cy = 400f,
            glView = identity(),
            wallPlane = floatArrayOf(
                0f, 0f, -2f,
                0f, 0f, 1f,
            ),
        )
        assertNotNull(geometry)
        geometry!!
        assertTrue(geometry.widthMeters > 1f)
        assertTrue(geometry.heightMeters > 0.7f)
        assertEquals(
            geometry.widthMeters / geometry.heightMeters,
            geometry.outputWidth.toFloat() / geometry.outputHeight.toFloat(),
            0.02f,
        )
        assertEquals(geometry.widthMeters, geometry.pageGeometry.widthMeters, 0.01f)
        // Identity camera at the origin looking -Z, wall at z=-2: the centered KPM page is an
        // identity-oriented GL frame two metres in front of the camera.
        assertEquals(1f, geometry.cameraFromPageGl[0], 1e-4f)
        assertEquals(1f, geometry.cameraFromPageGl[5], 1e-4f)
        assertEquals(1f, geometry.cameraFromPageGl[10], 1e-4f)
        assertEquals(-2f, geometry.cameraFromPageGl[14], 1e-3f)
    }

    @Test
    fun `oblique wall still produces an in-frame metric rectangle`() {
        val angle = Math.toRadians(30.0)
        val normal = floatArrayOf(
            kotlin.math.sin(angle).toFloat(),
            0f,
            kotlin.math.cos(angle).toFloat(),
        )
        val geometry = MetricPageRectification.deriveGeometry(
            imageWidth = 1280,
            imageHeight = 720,
            fx = 900f,
            fy = 900f,
            cx = 640f,
            cy = 360f,
            glView = identity(),
            wallPlane = floatArrayOf(
                0f, 0f, -2.2f,
                normal[0], normal[1], normal[2],
            ),
        )
        assertNotNull(geometry)
        geometry!!
        val c = geometry.sourceCorners
        assertTrue((0 until 4).all { c[2 * it] in 1f..1278f && c[2 * it + 1] in 1f..718f })
        assertTrue(geometry.referenceDpi > 0f)
    }

    @Test
    fun `degenerate wall geometry is refused rather than assigned fake scale`() {
        val geometry = MetricPageRectification.deriveGeometry(
            imageWidth = 1000,
            imageHeight = 800,
            fx = 800f,
            fy = 800f,
            cx = 500f,
            cy = 400f,
            glView = identity(),
            wallPlane = floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f),
        )
        assertEquals(null, geometry)
    }

    @Test
    fun `rectify maps the source quadrilateral onto the page corners`() {
        // A 200x100 gradient image; the quad is an axis-aligned sub-rectangle, so rectification is a
        // pure scale and the corners must land on the output corners exactly.
        val w = 200; val h = 100
        val luma = ByteArray(w * h) { i -> ((i % w) + (i / w)).coerceAtMost(255).toByte() }
        val corners = floatArrayOf(20f, 10f, 180f, 10f, 180f, 90f, 20f, 90f)
        val out = MetricPageRectification.warpQuadToRect(luma, w, h, w, corners, 161, 81)!!
        fun src(x: Int, y: Int) = luma[y * w + x].toInt() and 0xff
        fun dst(x: Int, y: Int) = out[y * 161 + x].toInt() and 0xff
        assertEquals(src(20, 10), dst(0, 0))
        assertEquals(src(180, 10), dst(160, 0))
        assertEquals(src(180, 90), dst(160, 80))
        assertEquals(src(20, 90), dst(0, 80))
        assertEquals(src(100, 50), dst(80, 40))
    }

    @Test
    fun `rectify honours row stride and refuses malformed input`() {
        val w = 64; val h = 48; val stride = 80
        val luma = ByteArray(stride * h) { i -> if (i % stride < w) 200.toByte() else 7 }
        val geometry = MetricPageRectification.deriveGeometry(
            imageWidth = w, imageHeight = h, fx = 60f, fy = 60f, cx = 32f, cy = 24f,
            glView = identity(), wallPlane = floatArrayOf(0f, 0f, -2f, 0f, 0f, 1f),
        )
        // Too small an image for a 160-px page: refused, never a fake reference.
        assertEquals(null, geometry)
        val out = MetricPageRectification.warpQuadToRect(
            luma, w, h, stride, floatArrayOf(1f, 1f, 62f, 1f, 62f, 46f, 1f, 46f), 20, 20,
        )!!
        assertTrue(out.all { (it.toInt() and 0xff) == 200 })
        assertEquals(null, MetricPageRectification.warpQuadToRect(luma, w, h, w - 1, FloatArray(8), 20, 20))
    }

    @Test
    fun `lumaFromArgb uses the BT601 integer weights`() {
        val l = MetricPageRectification.lumaFromArgb(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt()))
        assertEquals(255, l[0].toInt() and 0xff)
        assertEquals(0, l[1].toInt() and 0xff)
        assertEquals((77 * 255 + 128) shr 8, l[2].toInt() and 0xff)
    }

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
}

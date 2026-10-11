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
    fun `the warp, the DPI and the page geometry agree on the pixel pitch`() {
        val geometry = MetricPageRectification.deriveGeometry(
            1000, 800, 800f, 800f, 500f, 400f, identity(), floatArrayOf(0f, 0f, -2f, 0f, 0f, 1f),
        )!!
        val pitch = geometry.widthMeters / geometry.outputWidth
        assertEquals(pitch, geometry.heightMeters / geometry.outputHeight, 1e-6f) // square pixels
        assertEquals(geometry.widthMeters, geometry.pageGeometry.widthMeters, 1e-5f)
        assertEquals(geometry.heightMeters, geometry.pageGeometry.heightMeters, 1e-5f)
        // The source corners are the physical corners: their image span is exactly widthMeters.
        val c = geometry.sourceCorners
        assertEquals(geometry.widthMeters * 800f / 2f, c[2] - c[0], 1e-2f)

        // Output pixel x samples the quad at (x + ½)/W: W pixels cover the corners edge to edge
        // (not W − 1 intervals, which would stretch the page by W/(W − 1)).
        val w = 256
        val gradient = ByteArray(w * 4) { (it % w).toByte() }
        val out = MetricPageRectification.warpQuadToRect(
            gradient, w, 4, w, floatArrayOf(10.25f, 0f, 110.25f, 0f, 110.25f, 3f, 10.25f, 3f), 100, 3,
        )!!
        for (x in 0 until 100) assertEquals(11 + x, out[100 + x].toInt() and 0xff)
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
    fun `rectify maps the source quadrilateral onto the page's outer pixel edges`() {
        // A 200x100 gradient image (value u + v); the quad is an axis-aligned 160x80 sub-rectangle and
        // the page is 160x80 pixels, so each output pixel is exactly one source pixel: output pixel
        // (x, y) samples (20 + x + ½, 10 + y + ½), i.e. the corners are the outer pixel edges.
        val w = 200; val h = 100
        val luma = ByteArray(w * h) { i -> ((i % w) + (i / w)).coerceAtMost(255).toByte() }
        val corners = floatArrayOf(20f, 10f, 180f, 10f, 180f, 90f, 20f, 90f)
        val out = MetricPageRectification.warpQuadToRect(luma, w, h, w, corners, 160, 80)!!
        fun dst(x: Int, y: Int) = out[y * 160 + x].toInt() and 0xff
        assertEquals(31, dst(0, 0)) // 20.5 + 10.5
        assertEquals(31 + 80 + 40, dst(80, 40))
        assertEquals(31 + 100 + 20, dst(100, 20))
        assertEquals(31 + 159, dst(159, 0))
        assertEquals(31 + 79, dst(0, 79))
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

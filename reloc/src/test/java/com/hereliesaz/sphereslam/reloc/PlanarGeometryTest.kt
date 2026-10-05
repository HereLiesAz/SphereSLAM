package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlanarGeometryTest {

    @Test
    fun `image center maps to the plane origin`() {
        val p = PlanarGeometry.pixelToPlane(50f, 25f, 100, 50, 2f)
        assertEquals(0f, p[0], 1e-6f)
        assertEquals(0f, p[1], 1e-6f)
        assertEquals(0f, p[2], 0f)
    }

    @Test
    fun `right edge is +half width, top edge is +half height`() {
        // width 100px -> widthMeters 2 (half = 1); height 50px -> heightMeters 1 (half = 0.5).
        val right = PlanarGeometry.pixelToPlane(100f, 25f, 100, 50, 2f)
        assertEquals(1f, right[0], 1e-6f)
        val top = PlanarGeometry.pixelToPlane(50f, 0f, 100, 50, 2f)
        assertEquals(0.5f, top[1], 1e-6f) // +v is down in image, so top row is +y on the plane
    }

    @Test
    fun `aspect ratio is preserved via derived height`() {
        // Bottom edge should be -half height.
        val bottom = PlanarGeometry.pixelToPlane(50f, 50f, 100, 50, 2f)
        assertEquals(-0.5f, bottom[1], 1e-6f)
    }

    @Test
    fun `rejects non-positive dimensions and width`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanarGeometry.pixelToPlane(0f, 0f, 0, 10, 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanarGeometry.pixelToPlane(0f, 0f, 10, 10, 0f)
        }
    }
}

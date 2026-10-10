package com.hereliesaz.sphereslam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageGlowProjectionTest {

    private fun dir(az: Float, el: Float) = SphereCoverage.Direction(az, el)

    @Test
    fun `a direction straight ahead lands at screen center`() {
        val marks = CoverageGlowProjection.project(
            directions = listOf(dir(90f, 0f)), // east, horizon
            cameraHeadingDeg = 90f,             // camera also looks east
            cameraElevationDeg = 0f,
            horizontalFovDeg = 60f,
            verticalFovDeg = 45f,
        )
        assertEquals(1, marks.size)
        assertEquals(0f, marks[0].ndcX, 1e-4f)
        assertEquals(0f, marks[0].ndcY, 1e-4f)
        assertTrue(marks[0].onScreen)
    }

    @Test
    fun `a direction to the camera's right has positive ndcX`() {
        // Camera looks north; target is to the east (to the viewer's right), within FOV.
        val marks = CoverageGlowProjection.project(
            directions = listOf(dir(20f, 0f)),
            cameraHeadingDeg = 0f,
            cameraElevationDeg = 0f,
            horizontalFovDeg = 60f,
            verticalFovDeg = 45f,
        )
        assertEquals(1, marks.size)
        assertTrue("east of north is screen-right", marks[0].ndcX > 0f)
        assertTrue(marks[0].onScreen)
    }

    @Test
    fun `a direction above the camera has positive ndcY`() {
        val marks = CoverageGlowProjection.project(
            directions = listOf(dir(0f, 15f)),
            cameraHeadingDeg = 0f,
            cameraElevationDeg = 0f,
            horizontalFovDeg = 60f,
            verticalFovDeg = 45f,
        )
        assertTrue(marks[0].ndcY > 0f)
    }

    @Test
    fun `a direction outside the fov is marked off-screen and clamped`() {
        // 60° to the right with a 60° horizontal FOV (±30°) is well outside the frustum.
        val marks = CoverageGlowProjection.project(
            directions = listOf(dir(60f, 0f)),
            cameraHeadingDeg = 0f,
            cameraElevationDeg = 0f,
            horizontalFovDeg = 60f,
            verticalFovDeg = 45f,
        )
        assertEquals(1, marks.size)
        assertFalse(marks[0].onScreen)
        assertTrue("clamped to the edge", marks[0].ndcX in -1f..1f)
        assertEquals("nudges toward the right edge", 1f, marks[0].ndcX, 1e-4f)
    }

    @Test
    fun `a direction behind the camera is dropped`() {
        val marks = CoverageGlowProjection.project(
            directions = listOf(dir(180f, 0f)), // south, camera looks north
            cameraHeadingDeg = 0f,
            cameraElevationDeg = 0f,
            horizontalFovDeg = 60f,
            verticalFovDeg = 45f,
        )
        assertTrue(marks.isEmpty())
    }

    @Test
    fun `empty input yields no marks`() {
        assertTrue(
            CoverageGlowProjection.project(emptyList(), 0f, 0f, 60f, 45f).isEmpty(),
        )
    }

    @Test
    fun `field of view outside the open interval 0 to 180 is rejected`() {
        val bad = listOf(0f, -10f, 180f, 200f, Float.NaN, Float.POSITIVE_INFINITY)
        for (fov in bad) {
            for ((h, v) in listOf(fov to 45f, 60f to fov)) {
                try {
                    CoverageGlowProjection.project(listOf(dir(0f, 0f)), 0f, 0f, h, v)
                    org.junit.Assert.fail("expected rejection of fov h=$h v=$v")
                } catch (_: IllegalArgumentException) {
                }
            }
        }
        // Validation happens even with no directions to place.
        try {
            CoverageGlowProjection.project(emptyList(), 0f, 0f, 180f, 45f)
            org.junit.Assert.fail("expected rejection of fov 180")
        } catch (_: IllegalArgumentException) {
        }
        // Just inside the bounds is accepted.
        assertEquals(1, CoverageGlowProjection.project(listOf(dir(0f, 0f)), 0f, 0f, 179.9f, 0.1f).size)
    }

    private fun region(az0: Float, az1: Float, el0: Float, el1: Float) =
        SphereCoverage.TileRegion(az0, az1, el0, el1)

    @Test
    fun `a tile around the view axis fills a centered quad of whole triangles`() {
        val n = 4
        val v = CoverageGlowProjection.projectRegions(
            listOf(region(350f, 370f, -10f, 10f)), 0f, 0f, 90f, 90f, subdivisions = n,
        )
        assertEquals(n * n * 6 * CoverageGlowProjection.FLOATS_PER_REGION_VERTEX, v.size)
        val edge = Math.tan(Math.toRadians(10.0)).toFloat() // tan(10°) / tan(45°)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        for (i in v.indices step 2) {
            minX = minOf(minX, v[i]); maxX = maxOf(maxX, v[i])
            assertTrue(Math.abs(v[i + 1]) <= edge * 1.02f + 1e-4f)
        }
        assertEquals(-edge, minX, 1e-4f)
        assertEquals(edge, maxX, 1e-4f)
    }

    @Test
    fun `adjacent tiles share their edge vertices exactly`() {
        val left = CoverageGlowProjection.projectRegions(listOf(region(0f, 10f, -5f, 5f)), 5f, 0f, 60f, 60f, 2)
        val right = CoverageGlowProjection.projectRegions(listOf(region(10f, 20f, -5f, 5f)), 5f, 0f, 60f, 60f, 2)
        // The shared az = 10 edge: the vertices with the largest x in `left` must reappear bit-for-bit
        // in `right` (same az/el inputs => same projection). Compare vertex sets, not max vs min:
        // different elevations on the edge differ by float noise even though the meridian is straight.
        fun vertices(v: FloatArray) = (v.indices step 2).map { v[it] to v[it + 1] }.toSet()
        val leftVerts = vertices(left)
        val rightVerts = vertices(right)
        val leftMaxX = leftVerts.maxOf { it.first }
        val sharedEdge = leftVerts.filter { Math.abs(it.first - leftMaxX) < 1e-4f }
        assertEquals(3, sharedEdge.size) // subdivisions + 1 vertices along the edge
        assertTrue(rightVerts.containsAll(sharedEdge))
    }

    @Test
    fun `a tile behind the camera produces nothing`() {
        assertEquals(0, CoverageGlowProjection.projectRegions(listOf(region(170f, 190f, -10f, 10f)), 0f, 0f, 60f, 45f).size)
    }

    @Test
    fun `projectRegions validates subdivisions and fov`() {
        for (call in listOf<() -> Unit>(
            { CoverageGlowProjection.projectRegions(emptyList(), 0f, 0f, 60f, 45f, subdivisions = 0) },
            { CoverageGlowProjection.projectRegions(emptyList(), 0f, 0f, 180f, 45f) },
        )) {
            try {
                call(); throw AssertionError("expected IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
            }
        }
        assertEquals(0, CoverageGlowProjection.projectRegions(emptyList(), 0f, 0f, 60f, 45f).size)
    }
}

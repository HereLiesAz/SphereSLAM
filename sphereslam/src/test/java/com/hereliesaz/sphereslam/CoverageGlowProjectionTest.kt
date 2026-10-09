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
}

package com.hereliesaz.sphereslam

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Projects still-unscanned coverage onto the screen, given where the camera currently looks and its
 * field of view: whole [PhotosphereMap] tiles ([SphereCoverage.TileRegion]) via [projectRegions] for the
 * coverage haze, or tile centers ([SphereCoverage.Direction]) via [project] for point marks.
 *
 * This is the pure geometry behind the glow: a renderer draws a soft mark at each returned
 * [GlowMark] position. It is deliberately independent of any track lock — it runs off live device
 * attitude ([CameraAttitudeProvider]) so the glow keeps pointing at the gaps even while the tracker
 * is reacquiring, which is exactly when the artist needs the nudge.
 *
 * Frames: directions are absolute (azimuth from north clockwise, elevation above the horizon). Each
 * is turned into an East-North-Up unit vector and expressed in the camera's basis (forward = where
 * the camera looks). A pinhole model then gives normalized device coordinates in `[-1, 1]`, with a
 * flag for whether the direction is actually within the frustum or only off-screen (so the renderer
 * can draw an in-scene glow vs. an edge arrow).
 */
object CoverageGlowProjection {

    /**
     * One unscanned direction placed for drawing.
     *
     * @property direction the unscanned coverage direction this mark represents.
     * @property ndcX normalized device X in `[-1, 1]` (right positive).
     * @property ndcY normalized device Y in `[-1, 1]` (up positive).
     * @property onScreen true when the direction is in front of the camera and within the frustum
     *   (`|ndcX| ≤ 1` and `|ndcY| ≤ 1`); when false, [ndcX]/[ndcY] are clamped to the edge and
     *   indicate the side to nudge toward for an off-screen arrow hint.
     */
    data class GlowMark(
        val direction: SphereCoverage.Direction,
        val ndcX: Float,
        val ndcY: Float,
        val onScreen: Boolean,
    )

    /**
     * Place each of [directions] for the current view.
     *
     * @param directions unscanned directions, typically [SphereCoverage.thinDirections].
     * @param cameraHeadingDeg camera axis compass heading, degrees (as [CameraAttitudeProvider]
     *   reports; 0 = north, clockwise).
     * @param cameraElevationDeg camera axis elevation, degrees (above the horizon positive).
     * @param horizontalFovDeg the preview's horizontal field of view, degrees, finite and strictly
     *   inside `(0, 180)`.
     * @param verticalFovDeg the preview's vertical field of view, degrees, finite and strictly inside
     *   `(0, 180)`.
     * @throws IllegalArgumentException if either field of view is outside `(0, 180)` (a pinhole
     *   projection is undefined at 0 and 180 degrees and beyond).
     * @return one [GlowMark] per placeable direction; directions at or behind the view plane are
     *   dropped, and the list is empty when [directions] is empty or the camera looks straight
     *   up/down (no stable screen basis).
     */
    fun project(
        directions: List<SphereCoverage.Direction>,
        cameraHeadingDeg: Float,
        cameraElevationDeg: Float,
        horizontalFovDeg: Float,
        verticalFovDeg: Float,
    ): List<GlowMark> {
        requireFov(horizontalFovDeg, verticalFovDeg)
        if (directions.isEmpty()) return emptyList()
        val forward = enu(cameraHeadingDeg, cameraElevationDeg)
        val worldUp = floatArrayOf(0f, 0f, 1f)
        // Camera right = forward × worldUp (handles any non-vertical forward); up = right × forward.
        var right = cross(forward, worldUp)
        if (norm(right) < 1e-5f) return emptyList() // looking straight up/down: no stable basis
        right = normalize(right)
        val up = normalize(cross(right, forward))
        val tanH = tan(Math.toRadians(horizontalFovDeg / 2.0)).toFloat()
        val tanV = tan(Math.toRadians(verticalFovDeg / 2.0)).toFloat()

        val out = ArrayList<GlowMark>(directions.size)
        for (d in directions) {
            val v = enu(d.azimuthDeg, d.elevationDeg)
            val z = dot(v, forward) // component along the view axis
            if (z <= 1e-4f) continue // at/behind the camera plane — skip
            val x = dot(v, right)
            val y = dot(v, up)
            val ndcX = (x / z) / tanH
            val ndcY = (y / z) / tanV
            val onScreen = abs(ndcX) <= 1f && abs(ndcY) <= 1f
            out.add(
                GlowMark(
                    direction = d,
                    ndcX = ndcX.coerceIn(-1f, 1f),
                    ndcY = ndcY.coerceIn(-1f, 1f),
                    onScreen = onScreen,
                ),
            )
        }
        return out
    }

    /** Floats per haze vertex in [projectRegions] output: NDC x, y. */
    const val FLOATS_PER_REGION_VERTEX = 2

    /**
     * Triangulate each of [regions] into screen space for the coverage haze: a flat fill over every
     * unscanned tile, so the boundary of the fill is the edge of what has been scanned.
     *
     * Each tile is split into [subdivisions]² cells (a tile's edges are curves on screen, not straight
     * lines) and each cell into two triangles. Neighboring tiles share their edge vertices exactly,
     * and no two triangles overlap, so a uniform-alpha fill has no seams or doubled-up bands.
     * Coordinates are NOT clamped — the GPU clips to the viewport — so off-screen tiles cost a few
     * vertices and draw nothing. A cell with any corner at or behind the camera plane is dropped;
     * with the default subdivision such a cell lies far outside any field of view below 180°.
     *
     * @param regions unscanned tiles, typically [PhotosphereMap.regionsNeedingUpdate].
     * @param cameraHeadingDeg camera axis compass heading, degrees (0 = north, clockwise).
     * @param cameraElevationDeg camera axis elevation, degrees.
     * @param horizontalFovDeg horizontal field of view, degrees, strictly inside `(0, 180)`.
     * @param verticalFovDeg vertical field of view, degrees, strictly inside `(0, 180)`.
     * @param subdivisions cells per tile side, `>= 1`.
     * @throws IllegalArgumentException for a field of view outside `(0, 180)` or [subdivisions] `< 1`.
     * @return `GL_TRIANGLES` vertex data, [FLOATS_PER_REGION_VERTEX] floats per vertex; empty when
     *   [regions] is empty or the camera looks straight up/down (no stable screen basis).
     */
    fun projectRegions(
        regions: List<SphereCoverage.TileRegion>,
        cameraHeadingDeg: Float,
        cameraElevationDeg: Float,
        horizontalFovDeg: Float,
        verticalFovDeg: Float,
        subdivisions: Int = 4,
    ): FloatArray {
        requireFov(horizontalFovDeg, verticalFovDeg)
        require(subdivisions >= 1) { "subdivisions must be >= 1, was $subdivisions" }
        if (regions.isEmpty()) return FloatArray(0)
        val forward = enu(cameraHeadingDeg, cameraElevationDeg)
        var right = cross(forward, floatArrayOf(0f, 0f, 1f))
        if (norm(right) < 1e-5f) return FloatArray(0)
        right = normalize(right)
        val up = normalize(cross(right, forward))
        val tanH = tan(Math.toRadians(horizontalFovDeg / 2.0)).toFloat()
        val tanV = tan(Math.toRadians(verticalFovDeg / 2.0)).toFloat()

        val n = subdivisions
        val grid = FloatArray((n + 1) * (n + 1) * 2)
        val valid = BooleanArray((n + 1) * (n + 1))
        val out = FloatArrayBuilder(regions.size * n * n * 6 * FLOATS_PER_REGION_VERTEX)
        for (r in regions) {
            for (i in 0..n) {
                val az = r.azimuthStartDeg + (r.azimuthEndDeg - r.azimuthStartDeg) * i / n
                for (j in 0..n) {
                    val el = r.elevationBottomDeg + (r.elevationTopDeg - r.elevationBottomDeg) * j / n
                    val v = enu(az, el)
                    val z = dot(v, forward)
                    val k = i * (n + 1) + j
                    valid[k] = z > 1e-4f
                    if (valid[k]) {
                        grid[2 * k] = (dot(v, right) / z) / tanH
                        grid[2 * k + 1] = (dot(v, up) / z) / tanV
                    }
                }
            }
            for (i in 0 until n) {
                for (j in 0 until n) {
                    val a = i * (n + 1) + j
                    val b = a + (n + 1)
                    if (!(valid[a] && valid[a + 1] && valid[b] && valid[b + 1])) continue
                    out.vertex(grid, a); out.vertex(grid, b); out.vertex(grid, b + 1)
                    out.vertex(grid, a); out.vertex(grid, b + 1); out.vertex(grid, a + 1)
                }
            }
        }
        return out.toArray()
    }

    private fun requireFov(horizontalFovDeg: Float, verticalFovDeg: Float) {
        require(horizontalFovDeg.isFinite() && horizontalFovDeg > 0f && horizontalFovDeg < 180f) {
            "horizontalFovDeg must be in (0, 180), was $horizontalFovDeg"
        }
        require(verticalFovDeg.isFinite() && verticalFovDeg > 0f && verticalFovDeg < 180f) {
            "verticalFovDeg must be in (0, 180), was $verticalFovDeg"
        }
    }

    private class FloatArrayBuilder(capacity: Int) {
        private var data = FloatArray(capacity)
        private var size = 0
        fun vertex(src: FloatArray, k: Int) {
            data[size++] = src[2 * k]
            data[size++] = src[2 * k + 1]
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    /** East-North-Up unit vector for an absolute (azimuth from north cw, elevation above horizon). */
    private fun enu(azimuthDeg: Float, elevationDeg: Float): FloatArray {
        val a = Math.toRadians(azimuthDeg.toDouble())
        val e = Math.toRadians(elevationDeg.toDouble())
        val cosE = cos(e)
        return floatArrayOf(
            (cosE * sin(a)).toFloat(), // east
            (cosE * cos(a)).toFloat(), // north
            sin(e).toFloat(),          // up
        )
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )
    private fun norm(a: FloatArray) = hypot(hypot(a[0], a[1]), a[2])
    private fun normalize(a: FloatArray): FloatArray {
        val n = norm(a)
        return floatArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }
}

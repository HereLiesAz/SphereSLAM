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
 *
 * Prefer the **full-rotation** API — [worldFromCamera] + [Lens] + [projectRegions] /
 * [prepareRegions] — over the heading/elevation overloads: it honours camera roll, works looking
 * straight up or down, supports an off-centre principal point, and (prepared once) re-projects every
 * display frame without allocation, which is what makes the glow feel attached to the scene.
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
    @Deprecated(
        "Heading/elevation only: ignores camera roll, vanishes when the camera looks straight up or " +
            "down, and assumes a centred principal point. Use projectRegions(regions, worldFromCamera, " +
            "lens) — or prepareRegions + PreparedRegions.projectInto for per-frame use — with " +
            "worldFromCamera(heading, elevation, roll) or a sensor rotation.",
    )
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

    // ---------------------------------------------------------------------------------------------
    // Full-rotation projection
    // ---------------------------------------------------------------------------------------------

    /** Default longest tile-edge segment, degrees, for [prepareRegions] / [projectRegions]. */
    const val DEFAULT_MAX_SEGMENT_DEG: Float = 5f

    /** Upper bound on segments per tile side, whatever the tile size. */
    const val MAX_SEGMENTS_PER_SIDE: Int = 64

    /**
     * How camera-frame directions land on the drawn surface: a pinhole whose normalized device
     * coordinates are `ndcX = scaleX · x/d + offsetX` and `ndcY = scaleY · y/d + offsetY`, where
     * `(x, y)` are GL camera coordinates (x right, y up) and `d` the depth along the view axis.
     *
     * Describe **what the surface shows** — for a `FILL_CENTER` preview, the cropped view
     * ([fromIntrinsics] of `CameraFillCenter.intrinsics(frame, viewW, viewH)`), not the full frame.
     *
     * @property scaleX `1 / tan(hFov/2)` for a centred lens; finite and > 0.
     * @property scaleY `1 / tan(vFov/2)` for a centred lens; finite and > 0.
     * @property offsetX NDC shift of the principal point (0 when centred); finite.
     * @property offsetY NDC shift of the principal point (0 when centred); finite.
     */
    data class Lens(
        val scaleX: Float,
        val scaleY: Float,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
    ) {
        init {
            require(scaleX.isFinite() && scaleX > 0f && scaleY.isFinite() && scaleY > 0f) {
                "Lens scales must be finite and > 0, were ($scaleX, $scaleY)"
            }
            require(offsetX.isFinite() && offsetY.isFinite()) { "Lens offsets must be finite" }
        }

        companion object {
            /**
             * A centred lens with the given full fields of view, degrees, each strictly inside `(0, 180)`.
             * @throws IllegalArgumentException for a field of view outside `(0, 180)`.
             */
            fun fromFieldOfView(horizontalFovDeg: Float, verticalFovDeg: Float): Lens {
                requireFov(horizontalFovDeg, verticalFovDeg)
                return Lens(
                    scaleX = (1.0 / tan(Math.toRadians(horizontalFovDeg / 2.0))).toFloat(),
                    scaleY = (1.0 / tan(Math.toRadians(verticalFovDeg / 2.0))).toFloat(),
                )
            }

            /**
             * Pixel intrinsics of the drawn surface (OpenCV convention: u right, v down, origin at the
             * top-left corner) → [Lens]; honours an off-centre principal point.
             * @throws IllegalArgumentException for non-positive focal lengths or size, or non-finite input.
             */
            fun fromIntrinsics(fx: Float, fy: Float, cx: Float, cy: Float, width: Int, height: Int): Lens {
                require(fx.isFinite() && fy.isFinite() && fx > 0f && fy > 0f) { "focal lengths must be finite and > 0" }
                require(cx.isFinite() && cy.isFinite()) { "principal point must be finite" }
                require(width > 0 && height > 0) { "size must be positive" }
                return Lens(
                    scaleX = 2f * fx / width,
                    scaleY = 2f * fy / height,
                    offsetX = 2f * cx / width - 1f,
                    offsetY = 1f - 2f * cy / height,
                )
            }

            /** [fromIntrinsics] for a [com.hereliesaz.sphereslam.camera.CameraIntrinsics]. */
            fun fromIntrinsics(intrinsics: com.hereliesaz.sphereslam.camera.CameraIntrinsics): Lens =
                fromIntrinsics(intrinsics.fx, intrinsics.fy, intrinsics.cx, intrinsics.cy, intrinsics.width, intrinsics.height)
        }
    }

    /**
     * World-from-camera rotation for an absolute camera attitude, in the convention of
     * [PhotosphereMap.directionOfPixel] and `CameraAttitudeProvider`: [cameraHeadingDeg] is the
     * view axis's compass heading (0 = north, clockwise), [cameraElevationDeg] its angle above the
     * horizon, and [cameraRollDeg] the image-right axis's angle above the horizon (positive = the
     * image's right edge points up).
     *
     * The result is row-major 3×3 whose **columns** are the GL camera axes — x right, y up, z backward
     * (the camera looks down −z) — in East-North-Up world coordinates, so `v_world = M · v_camera`.
     *
     * Defined at every elevation ([AttitudeBasis]); straight up or down it needs the heading and roll
     * of one sample, as `CameraAttitudeProvider.latestAttitude` gives them.
     *
     * @return the rotation, or null for non-finite input.
     */
    fun worldFromCamera(cameraHeadingDeg: Float, cameraElevationDeg: Float, cameraRollDeg: Float = 0f): FloatArray? {
        val basis = AttitudeBasis.basis(cameraHeadingDeg, cameraElevationDeg, cameraRollDeg) ?: return null
        val r = basis.right; val u = basis.up; val f = basis.forward
        return floatArrayOf(
            r[0].toFloat(), u[0].toFloat(), (-f[0]).toFloat(),
            r[1].toFloat(), u[1].toFloat(), (-f[1]).toFloat(),
            r[2].toFloat(), u[2].toFloat(), (-f[2]).toFloat(),
        )
    }

    /**
     * World-from-camera rotation from a sensor attitude: `deviceToWorld · cameraFromDeviceᵀ`.
     *
     * @param deviceToWorld row-major 3×3 device→world East-North-Up rotation
     *   (`SensorManager.getRotationMatrixFromVector` of `TYPE_ROTATION_VECTOR`,
     *   `CameraAttitudeProvider.latestDeviceToWorldMatrix`).
     * @param cameraFromDevice row-major 3×3 GL-camera-from-device of the **drawn** image
     *   (`DeviceCameraRotation`): for an overlay over a display-upright preview,
     *   `DeviceCameraRotation.cameraFromDeviceForDisplayFrame(displayRotationDeg)`.
     * @throws IllegalArgumentException when either matrix is not a finite length-9 array.
     */
    @JvmOverloads
    fun worldFromCamera(deviceToWorld: FloatArray, cameraFromDevice: FloatArray = IDENTITY_3X3): FloatArray {
        requireMat3(deviceToWorld, "deviceToWorld")
        requireMat3(cameraFromDevice, "cameraFromDevice")
        val out = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            out[i * 3 + j] = deviceToWorld[i * 3] * cameraFromDevice[j * 3] +
                deviceToWorld[i * 3 + 1] * cameraFromDevice[j * 3 + 1] +
                deviceToWorld[i * 3 + 2] * cameraFromDevice[j * 3 + 2]
        }
        return out
    }

    /**
     * [project] for a full camera rotation: place each of [directions] on the surface described by
     * [lens], camera attitude [worldFromCamera] (see [worldFromCamera] for the convention). Works at
     * any roll and when looking straight up or down.
     *
     * @return one [GlowMark] per direction in front of the camera (at or behind the view plane are
     *   dropped); off-screen marks are clamped to the edge with `onScreen = false`.
     * @throws IllegalArgumentException when [worldFromCamera] is not a finite length-9 array.
     */
    fun project(
        directions: List<SphereCoverage.Direction>,
        worldFromCamera: FloatArray,
        lens: Lens,
    ): List<GlowMark> {
        requireMat3(worldFromCamera, "worldFromCamera")
        if (directions.isEmpty()) return emptyList()
        val m = worldFromCamera
        val out = ArrayList<GlowMark>(directions.size)
        for (idx in directions.indices) {
            val d = directions[idx]
            val v = enu(d.azimuthDeg, d.elevationDeg)
            val x = m[0] * v[0] + m[3] * v[1] + m[6] * v[2]
            val y = m[1] * v[0] + m[4] * v[1] + m[7] * v[2]
            val depth = -(m[2] * v[0] + m[5] * v[1] + m[8] * v[2])
            if (depth <= 1e-4f) continue
            val ndcX = lens.scaleX * x / depth + lens.offsetX
            val ndcY = lens.scaleY * y / depth + lens.offsetY
            out.add(GlowMark(d, ndcX.coerceIn(-1f, 1f), ndcY.coerceIn(-1f, 1f), abs(ndcX) <= 1f && abs(ndcY) <= 1f))
        }
        return out
    }

    /**
     * Tile regions pre-sampled on the sphere, ready to be re-projected every display frame without
     * trigonometry or allocation ([projectInto]). Immutable and safe to share between threads.
     *
     * Each tile is cut into cells no wider than the `maxSegmentDeg` it was prepared with along either
     * side, and every cell becomes two triangles. Meridian edges project to straight lines under a
     * pinhole, but parallels (constant elevation) are curves, so the per-cell sampling is what keeps a
     * tile's top and bottom edges on their true curve at any roll and field of view. Adjacent tiles of
     * one [PhotosphereMap] grid share their edge samples exactly, so fills have no seams or overlaps.
     */
    class PreparedRegions internal constructor(
        /** Number of regions. */
        val regionCount: Int,
        private val dirs: FloatArray,
        private val dims: IntArray,
        private val dirOffsets: IntArray,
    ) {
        /** Floats [projectInto] may write at most (every cell in front of the camera). */
        val maxVertexFloats: Int = run {
            var total = 0
            for (r in 0 until regionCount) total += dims[2 * r] * dims[2 * r + 1] * 6 * FLOATS_PER_REGION_VERTEX
            total
        }

        /**
         * Project into [out] as `GL_TRIANGLES` NDC data ([FLOATS_PER_REGION_VERTEX] floats per vertex),
         * for camera attitude [worldFromCamera] (row-major 3×3, columns = GL camera axes in ENU world)
         * and [lens]. Allocation-free.
         *
         * A cell with any corner at or behind the camera plane is dropped; with the default
         * [DEFAULT_MAX_SEGMENT_DEG] such a cell lies more than 85° off the view axis. Coordinates are
         * not clamped — the GPU clips — so off-screen cells cost a few floats and draw nothing.
         *
         * @param out destination, at least [maxVertexFloats] long.
         * @return the number of floats written (a multiple of 6).
         * @throws IllegalArgumentException when [out] is too short or [worldFromCamera] is not a
         *   length-9 array.
         */
        fun projectInto(worldFromCamera: FloatArray, lens: Lens, out: FloatArray): Int {
            require(worldFromCamera.size == 9) { "worldFromCamera must be a row-major 3x3" }
            require(out.size >= maxVertexFloats) { "out must hold $maxVertexFloats floats, was ${out.size}" }
            val m = worldFromCamera
            var p = 0
            for (r in 0 until regionCount) {
                val nAz = dims[2 * r]
                val nEl = dims[2 * r + 1]
                val base = dirOffsets[r]
                for (i in 0 until nAz) {
                    for (j in 0 until nEl) {
                        val a = base + i * (nEl + 1) + j      // (i, j)
                        val b = a + (nEl + 1)                 // (i + 1, j)
                        // Triangles (a, b, b+1) and (a, b+1, a+1): slots 0,1,2 and 3,4,5.
                        if (!vertex(a, m, lens, out, p) ||
                            !vertex(b, m, lens, out, p + 2) ||
                            !vertex(b + 1, m, lens, out, p + 4) ||
                            !vertex(a + 1, m, lens, out, p + 10)
                        ) {
                            continue
                        }
                        out[p + 6] = out[p]; out[p + 7] = out[p + 1]
                        out[p + 8] = out[p + 4]; out[p + 9] = out[p + 5]
                        p += 12
                    }
                }
            }
            return p
        }

        private fun vertex(k: Int, m: FloatArray, lens: Lens, out: FloatArray, at: Int): Boolean {
            val o = 3 * k
            val vx = dirs[o]; val vy = dirs[o + 1]; val vz = dirs[o + 2]
            val depth = -(m[2] * vx + m[5] * vy + m[8] * vz)
            if (depth <= 1e-4f) return false
            out[at] = lens.scaleX * (m[0] * vx + m[3] * vy + m[6] * vz) / depth + lens.offsetX
            out[at + 1] = lens.scaleY * (m[1] * vx + m[4] * vy + m[7] * vz) / depth + lens.offsetY
            return true
        }

        companion object {
            /** No regions. */
            @JvmField
            val EMPTY: PreparedRegions = PreparedRegions(0, FloatArray(0), IntArray(0), IntArray(0))
        }
    }

    /**
     * Pre-sample [regions] for repeated projection ([PreparedRegions.projectInto]). Call when the
     * regions change (per keyframe), not per frame.
     *
     * @param maxSegmentDeg longest cell side, degrees, finite and > 0; each tile side gets
     *   `ceil(span / maxSegmentDeg)` segments, capped at [MAX_SEGMENTS_PER_SIDE].
     * @throws IllegalArgumentException for a non-positive [maxSegmentDeg] or a region with
     *   non-finite bounds.
     */
    @JvmOverloads
    fun prepareRegions(
        regions: List<SphereCoverage.TileRegion>,
        maxSegmentDeg: Float = DEFAULT_MAX_SEGMENT_DEG,
    ): PreparedRegions {
        require(maxSegmentDeg.isFinite() && maxSegmentDeg > 0f) { "maxSegmentDeg must be finite and > 0, was $maxSegmentDeg" }
        if (regions.isEmpty()) return PreparedRegions.EMPTY
        val dims = IntArray(regions.size * 2)
        val offsets = IntArray(regions.size)
        var vertices = 0
        for ((r, region) in regions.withIndex()) {
            require(
                region.azimuthStartDeg.isFinite() && region.azimuthEndDeg.isFinite() &&
                    region.elevationBottomDeg.isFinite() && region.elevationTopDeg.isFinite(),
            ) { "region bounds must be finite: $region" }
            dims[2 * r] = segments(region.azimuthEndDeg - region.azimuthStartDeg, maxSegmentDeg)
            dims[2 * r + 1] = segments(region.elevationTopDeg - region.elevationBottomDeg, maxSegmentDeg)
            offsets[r] = vertices
            vertices += (dims[2 * r] + 1) * (dims[2 * r + 1] + 1)
        }
        val dirs = FloatArray(vertices * 3)
        for ((r, region) in regions.withIndex()) {
            val nAz = dims[2 * r]
            val nEl = dims[2 * r + 1]
            for (i in 0..nAz) {
                // Normalized so a shared edge given as 370° by one tile and 10° by its neighbour
                // samples bit-identical directions.
                val az = norm360(region.azimuthStartDeg + (region.azimuthEndDeg - region.azimuthStartDeg) * i / nAz)
                for (j in 0..nEl) {
                    val el = region.elevationBottomDeg + (region.elevationTopDeg - region.elevationBottomDeg) * j / nEl
                    val v = enu(az, el.coerceIn(-90f, 90f))
                    val k = 3 * (offsets[r] + i * (nEl + 1) + j)
                    dirs[k] = v[0]; dirs[k + 1] = v[1]; dirs[k + 2] = v[2]
                }
            }
        }
        return PreparedRegions(regions.size, dirs, dims, offsets)
    }

    /**
     * Triangulate [regions] for a full camera rotation — the replacement for the heading/elevation
     * [projectRegions]: correct at any roll, straight up/down, and for an off-centre principal point.
     * One-shot convenience over [prepareRegions] + [PreparedRegions.projectInto]; for per-frame use,
     * prepare once and project into a reused buffer (as `CoverageGlowRenderer.setRegions` does).
     *
     * @param worldFromCamera row-major 3×3, columns = GL camera axes (x right, y up, z backward) in
     *   East-North-Up world ([worldFromCamera] builds it from heading/elevation/roll or a sensor matrix).
     * @param lens the drawn surface's projection.
     * @return `GL_TRIANGLES` NDC vertex data, [FLOATS_PER_REGION_VERTEX] floats per vertex.
     */
    @JvmOverloads
    fun projectRegions(
        regions: List<SphereCoverage.TileRegion>,
        worldFromCamera: FloatArray,
        lens: Lens,
        maxSegmentDeg: Float = DEFAULT_MAX_SEGMENT_DEG,
    ): FloatArray {
        requireMat3(worldFromCamera, "worldFromCamera")
        val prepared = prepareRegions(regions, maxSegmentDeg)
        val out = FloatArray(prepared.maxVertexFloats)
        val n = prepared.projectInto(worldFromCamera, lens, out)
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun segments(spanDeg: Float, maxSegmentDeg: Float): Int =
        kotlin.math.ceil(abs(spanDeg) / maxSegmentDeg).toInt().coerceIn(1, MAX_SEGMENTS_PER_SIDE)

    private fun norm360(deg: Float): Float {
        val r = deg % 360f
        return if (r < 0f) r + 360f else r
    }

    private val IDENTITY_3X3: FloatArray get() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun requireMat3(m: FloatArray, name: String) {
        require(m.size == 9 && m.all { it.isFinite() }) { "$name must be a finite row-major 3x3" }
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

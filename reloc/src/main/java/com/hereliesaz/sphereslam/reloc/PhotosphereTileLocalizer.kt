package com.hereliesaz.sphereslam.reloc

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Localizes the camera from what it sees of the photosphere — not only from the wall.
 *
 * **Stations.** A station is one standpoint the artist turned around at, with a photosphere of tiles
 * (keyframes, one per [Config.tileSpacingDeg] of view direction) captured there. The first station is
 * the session's base map origin; when the artist steps to a new spot the session opens another
 * station at the new camera centre and **keeps the earlier ones**. All stations share one world frame
 * (the session's map frame); a tile's keyframe pose is `rotation` (world→camera) plus its own
 * `centre` — the camera centre estimate when it was captured, not merely its station's.
 *
 * **From the same standpoint** (live centre within [standpointRadius] of the tile's) a tile
 * recognised in the live frame gives the camera's rotation directly: the camera has only turned since
 * the keyframe, so matched features are related by a pure rotation
 * ([PhotosphereGeometry.ransacRotation]). That fix is visual — it corrects gyro/compass drift — and
 * needs no wall in view.
 *
 * **Across standpoints** the same view direction seen from two centres with a known baseline is a
 * stereo pair: features matched between the two stations' tiles are triangulated into world
 * landmarks and a plane is fitted per tile ([compareAcrossStations]). Those landmarks give a full
 * 6-DoF camera pose from any position ([PhotosphereGeometry.ransacPose]), and the plane predicts how a
 * tile looks from anywhere ([predictTileHomography]) — how the photosphere changes as the device moves.
 *
 * **Monocular depth** ([TileDepthSource], e.g. MiDaS) gives the same from a single station: a tile's
 * relative inverse depth is calibrated ([DepthScaleFit]) against depths that are known — its
 * triangulated landmarks, then calibrated same-station tiles it overlaps (same centre, so a matched
 * feature is the same 3D point), then the wall plane ([setWallPlane], only where a ray meets the wall
 * within its extent) — and every feature with a
 * depth becomes a pseudo-landmark. Triangulated landmarks always win over pseudo ones.
 *
 * **Anchor tiles.** Tiles within [Config.anchorRadiusDeg] of the direction the wall was first locked
 * from at a station ([anchor]) — those viewing the wall and the ring bordering them — are never
 * dropped by [Config.maxTilesPerStation]. They are among the first tiles a station captures, so plain
 * oldest-first eviction would discard exactly the ones that re-find the wall when it leaves view.
 *
 * **Conventions.** Rotations are row-major 3×3 world→camera in the OpenCV camera frame (x right,
 * y down, z forward); centres are world-frame; intrinsics are `fx, fy, cx, cy` of the image the
 * features came from.
 *
 * Not thread-safe: the session drives it from its tile worker only. Owns every tile's features
 * ([close] releases them); a live frame's features are the caller's.
 */
@ExperimentalSphereSlamRelocApi
internal class PhotosphereTileLocalizer(
    private val backend: TileFeatureBackend,
    private val config: Config = Config(),
) : AutoCloseable {

    /**
     * @property tileSpacingDeg a new keyframe becomes a tile only when its view axis is at least this
     *   far from every tile of its station.
     * @property maxTilesPerStation cap per station (oldest beyond it are dropped, anchor tiles never).
     * @property anchorRadiusDeg tiles whose axis is within this of a station's [anchor] direction are
     *   anchor tiles. 2 × [tileSpacingDeg]: the spacing gate leaves neighbouring tiles up to about that
     *   far apart, so the ring bordering the wall-facing tile is inside it (a dozen or so tiles).
     * @property candidateConeDeg tiles whose axis is within this of the predicted camera axis are tried.
     * @property maxCandidates tiles tried per [localize] call, nearest axis first.
     * @property minRotationInliers / [rotationInlierDeg] the within-station rotation fix's RANSAC gate.
     * @property minPoseInliers / [poseInlierPx] the landmark (6-DoF) fix's gate.
     * @property compareAxisDeg two stations' tiles are compared when their axes are within this.
     * @property minParallaxDeg a triangulated landmark needs at least this ray parallax.
     * @property maxLandmarkReprojPx and must reproject into both keyframes within this.
     * @property minPlanePoints landmarks needed before a tile's plane is fitted.
     * @property minDepthSamples known depths needed to calibrate a tile's monocular depth.
     * @property standpointRadius centres within this (map units) count as the same standpoint: a
     *   rotation-only fix and depth sharing need it; triangulation needs more than it.
     * @property turnLiveFrameDeg turn the live frame by the sensors' rotation into the nearest
     *   candidate tile's view before extracting its features, when the two views differ by at least
     *   this ([turnTowardTile]); 0 never turns.
     */
    data class Config(
        val tileSpacingDeg: Float = 12f,
        val maxTilesPerStation: Int = 64,
        val anchorRadiusDeg: Float = 24f,
        val candidateConeDeg: Float = 40f,
        val maxCandidates: Int = 3,
        val minRotationInliers: Int = 20,
        val rotationInlierDeg: Float = 0.6f,
        val minPoseInliers: Int = 12,
        val poseInlierPx: Float = 3f,
        val compareAxisDeg: Float = 30f,
        val minParallaxDeg: Float = 1f,
        val maxLandmarkReprojPx: Float = 3f,
        val minPlanePoints: Int = 8,
        val minDepthSamples: Int = 12,
        val standpointRadius: Double = 0.15,
        val turnLiveFrameDeg: Float = 5f,
    )

    /** One standpoint. [centre] is world-frame, in the session's map units. */
    class Station internal constructor(val id: Int, centre: DoubleArray) {
        val centre: DoubleArray = centre.copyOf()
        internal val tiles = ArrayList<Tile>()
        val tileCount: Int get() = tiles.size

        /** The camera axis (world) the wall was first locked from here, or null ([anchor]). */
        internal var anchorAxis: DoubleArray? = null

        val anchorTileCount: Int get() = tiles.count { it.anchored }
    }

    /** One keyframe of a station: its pose, its features and what other stations taught us about it. */
    class Tile internal constructor(
        val id: Int,
        val station: Station,
        rotation: DoubleArray,
        centre: DoubleArray,
        intrinsics: FloatArray,
        val width: Int,
        val height: Int,
        internal val features: TileFeatures,
        internal val depth: TileDepthMap? = null,
    ) {
        val rotation: DoubleArray = rotation.copyOf()
        val centre: DoubleArray = centre.copyOf()
        val intrinsics: FloatArray = intrinsics.copyOf()

        /** The camera's forward axis in world (third row of [rotation]). */
        internal val axis: DoubleArray = doubleArrayOf(rotation[6], rotation[7], rotation[8])

        /** Near its station's anchor direction: never evicted. */
        var anchored: Boolean = false
            internal set

        /** Feature index → world landmark, from cross-station triangulation. */
        internal val landmarks = HashMap<Int, DoubleArray>()

        /** Feature index → world point from calibrated monocular depth ([TileDepthSource]). */
        internal val pseudo = HashMap<Int, DoubleArray>()

        /** The calibration of [depth] to this tile's camera depth, once known. */
        var depthFit: DepthScaleFit.Fit? = null
            internal set

        /** The 3D point for feature [index]: triangulated first, else from monocular depth. */
        internal fun pointOf(index: Int): DoubleArray? = landmarks[index] ?: pseudo[index]

        /** Features with a 3D point (triangulated or depth). */
        internal val pointCount: Int get() = (landmarks.keys + pseudo.keys).size

        /** The tile's dominant surface, once enough landmarks exist. */
        var plane: PhotosphereGeometry.Plane? = null
            internal set

        val landmarkCount: Int get() = landmarks.size
        val pseudoLandmarkCount: Int get() = pseudo.size
    }

    /** A localization result. */
    sealed class Fix {
        abstract val rotation: DoubleArray
        abstract val inliers: Int
        abstract val tile: Tile

        /** Rotation only, from a tile captured at the camera's standpoint. */
        class Rotation(
            override val rotation: DoubleArray,
            override val inliers: Int,
            override val tile: Tile,
        ) : Fix()

        /** Full pose from the tile's landmarks: valid wherever the camera is. */
        class Pose(
            override val rotation: DoubleArray,
            val centre: DoubleArray,
            override val inliers: Int,
            override val tile: Tile,
            val rmsPx: Double,
        ) : Fix()
    }

    private val stationList = ArrayList<Station>()

    /** Same-standpoint radius in map units ([Config.standpointRadius] until the session sets it). */
    var standpointRadius: Double = config.standpointRadius

    /** The wall plane in world (`n·x = offset`), a depth reference for tiles that see it. */
    var wallPlane: PhotosphereGeometry.Plane? = null
        private set

    // Whether a world point on the wall plane lies on the actual wall (its pages' extent).
    private var onWall: ((DoubleArray) -> Boolean)? = null
    private var nextTileId = 0

    val stations: List<Station> get() = stationList

    /** Open a station at world [centre]. */
    fun addStation(centre: DoubleArray): Station {
        require(centre.size == 3 && centre.all { it.isFinite() }) { "centre must be a finite 3-vector" }
        return Station(stationList.size, centre).also { stationList += it }
    }

    /** The station nearest world [centre], with its distance; null when there is none. */
    fun nearestStation(centre: DoubleArray): Pair<Station, Double>? =
        stationList.map { it to distance(it.centre, centre) }.minByOrNull { it.second }

    /**
     * The wall was locked at [station] with camera [rotation]: on the station's first lock, its tiles
     * near that view direction (and those captured near it later) become anchor tiles. Later locks
     * keep the first direction, so the anchored set stays bounded.
     */
    fun anchor(station: Station, rotation: DoubleArray) {
        require(rotation.size == 9)
        if (station.anchorAxis != null) return
        val axis = doubleArrayOf(rotation[6], rotation[7], rotation[8])
        station.anchorAxis = axis
        station.tiles.forEach { it.anchored = nearAnchor(station, it.axis) }
    }

    private fun nearAnchor(station: Station, axis: DoubleArray): Boolean =
        station.anchorAxis?.let { angleDeg(it, axis) <= config.anchorRadiusDeg } ?: false

    /** Whether a keyframe with [rotation] would become a tile of [station] (the spacing gate). */
    fun wouldAccept(station: Station, rotation: DoubleArray): Boolean {
        val axis = doubleArrayOf(rotation[6], rotation[7], rotation[8])
        return station.tiles.none { angleDeg(it.axis, axis) < config.tileSpacingDeg }
    }

    /**
     * Offer a keyframe captured at camera [centre] to [station]. It becomes a tile unless a tile of
     * the station already looks within [Config.tileSpacingDeg] of the same direction, then it is
     * compared against tiles from other standpoints that look the same way.
     *
     * @return the new tile, or null (too close to an existing tile, or no features).
     */
    fun addTile(
        station: Station,
        luma: ByteArray,
        width: Int,
        height: Int,
        intrinsics: FloatArray,
        rotation: DoubleArray,
        centre: DoubleArray = station.centre,
        depth: TileDepthMap? = null,
    ): Tile? {
        require(intrinsics.size == 4 && rotation.size == 9 && centre.size == 3)
        if (!wouldAccept(station, rotation)) return null
        val features = backend.extract(luma, width, height) ?: return null
        val tile = Tile(nextTileId++, station, rotation, centre, intrinsics, width, height, features, depth)
        tile.anchored = nearAnchor(station, tile.axis)
        station.tiles += tile
        while (station.tiles.size > config.maxTilesPerStation) {
            // Oldest first, anchor tiles never (they are bounded: a cone of anchorRadiusDeg at
            // tileSpacingDeg spacing holds a dozen or so).
            val victim = station.tiles.indexOfFirst { !it.anchored }
            if (victim < 0 || station.tiles[victim] === tile) {
                // Only anchor tiles older than it: the new tile is the one that does not fit.
                station.tiles.remove(tile)
                tile.features.close()
                return null
            }
            station.tiles.removeAt(victim).features.close()
        }
        compareAcrossStations(tile)
        calibrateDepths(onlyUncalibrated = true)
        return tile
    }

    /**
     * Set (or clear) the wall plane and recalibrate every tile's monocular depth. [onWall] says
     * whether a world point on the plane lies on the actual wall (null: the whole plane); rays that
     * meet the plane's extension off the wall are no depth reference.
     */
    fun setWallPlane(plane: PhotosphereGeometry.Plane?, onWall: ((DoubleArray) -> Boolean)? = null) {
        wallPlane = plane
        this.onWall = onWall
        calibrateDepths(onlyUncalibrated = false)
    }

    /**
     * Calibrate tiles' monocular depth, propagating outward: a pass calibrates every tile whose known
     * depths suffice, which can make its neighbours calibratable in the next pass.
     */
    internal fun calibrateDepths(onlyUncalibrated: Boolean) {
        val pending = stationList.flatMap { it.tiles }
            .filter { it.depth != null && (!onlyUncalibrated || it.depthFit == null) }
            .toMutableList()
        repeat(MAX_CALIBRATION_PASSES) {
            val done = pending.filter { calibrate(it) }
            if (done.isEmpty()) return
            pending -= done.toSet()
        }
    }

    /** Fit [tile]'s depth map to known depths and rebuild its pseudo-landmarks; true when it fitted. */
    private fun calibrate(tile: Tile): Boolean {
        val depth = tile.depth ?: return false
        val oneOverZ = ArrayList<Float>()
        val inv = ArrayList<Float>()
        fun sample(index: Int, z: Double) {
            if (!(z > 1e-6) || !z.isFinite()) return
            val d = depth.sample(tile.features.points[2 * index], tile.features.points[2 * index + 1], tile.width, tile.height) ?: return
            oneOverZ += (1.0 / z).toFloat()
            inv += d
        }
        // Triangulated landmarks of this tile.
        for ((i, p) in tile.landmarks) sample(i, cameraZ(tile, p))
        // Calibrated neighbours from the same standpoint: a matched feature is the same point.
        for (u in allTiles()) {
            if (u === tile || u.pointCount == 0 || angleDeg(u.axis, tile.axis) > config.compareAxisDeg) continue
            if (distance(u.centre, tile.centre) > standpointRadius) continue
            val pairs = backend.match(tile.features, u.features)
            var i = 0
            while (i + 1 < pairs.size) {
                u.pointOf(pairs[i + 1])?.let { sample(pairs[i], cameraZ(tile, it)) }
                i += 2
            }
        }
        // The wall plane, only when the better references are too few (off-wall features are
        // outliers here, which the robust fit tolerates only up to about half).
        val wallFrom = oneOverZ.size
        val wallIndex = ArrayList<Int>()
        val wallZ = ArrayList<Double>()
        if (oneOverZ.size < config.minDepthSamples) {
            wallPlane?.let { plane ->
                for (i in 0 until tile.features.size) {
                    val z = wallDepth(tile, i, plane) ?: continue
                    val before = oneOverZ.size
                    sample(i, z)
                    if (oneOverZ.size > before) { wallIndex += i; wallZ += z }
                }
            }
        }
        val fit = DepthScaleFit.fit(oneOverZ.toFloatArray(), inv.toFloatArray(), minSamples = config.minDepthSamples)
        if (fit == null) {
            // A wall seen head-on has one depth: MiDaS's scale and shift are not separable. Its
            // features are still placed — those whose MiDaS value agrees with the wall's lie on it.
            return wallIndex.size >= config.minDepthSamples && placeOnWall(tile, inv.subList(wallFrom, inv.size), wallIndex, wallZ)
        }
        tile.depthFit = fit
        tile.pseudo.clear()
        val k = tile.intrinsics
        val rt = transpose(tile.rotation)
        for (i in 0 until tile.features.size) {
            val u = tile.features.points[2 * i]
            val v = tile.features.points[2 * i + 1]
            val z = depth.sample(u, v, tile.width, tile.height)?.let(fit::depthFor) ?: continue
            val b = PhotosphereGeometry.bearing(u, v, k[0], k[1], k[2], k[3])
            val s = z / b[2]
            val cam = doubleArrayOf(b[0] * s, b[1] * s, b[2] * s)
            val c = tile.centre
            tile.pseudo[i] = DoubleArray(3) { r -> c[r] + rt[r * 3] * cam[0] + rt[r * 3 + 1] * cam[1] + rt[r * 3 + 2] * cam[2] }
        }
        if (tile.landmarks.size < config.minPlanePoints) refitPlane(tile)
        return true
    }

    /**
     * Pseudo-landmarks for the features of [tile] that MiDaS puts at the wall's depth (within a robust
     * band of the median), placed where their rays meet the wall. True when enough were placed.
     */
    private fun placeOnWall(tile: Tile, inv: List<Float>, index: List<Int>, z: List<Double>): Boolean {
        val sorted = inv.sorted()
        val median = sorted[sorted.size / 2]
        val mad = inv.map { kotlin.math.abs(it - median) }.sorted()[inv.size / 2]
        val band = maxOf(3f * 1.4826f * mad, 0.02f * kotlin.math.abs(median))
        val k = tile.intrinsics
        val rt = transpose(tile.rotation)
        val c = tile.centre
        tile.pseudo.clear()
        for (j in index.indices) {
            if (kotlin.math.abs(inv[j] - median) > band) continue
            val i = index[j]
            val b = PhotosphereGeometry.bearing(tile.features.points[2 * i], tile.features.points[2 * i + 1], k[0], k[1], k[2], k[3])
            val s = z[j] / b[2]
            tile.pseudo[i] = DoubleArray(3) { r -> c[r] + (rt[r * 3] * b[0] + rt[r * 3 + 1] * b[1] + rt[r * 3 + 2] * b[2]) * s }
        }
        if (tile.pseudo.size < config.minDepthSamples) {
            tile.pseudo.clear()
            return false
        }
        if (tile.landmarks.size < config.minPlanePoints) refitPlane(tile)
        return true
    }

    /** Camera-axis depth of world point [p] in [tile]'s keyframe. */
    private fun cameraZ(tile: Tile, p: DoubleArray): Double {
        val r = tile.rotation
        val c = tile.centre
        return r[6] * (p[0] - c[0]) + r[7] * (p[1] - c[1]) + r[8] * (p[2] - c[2])
    }

    /** Camera-axis depth at which feature [index]'s ray meets [plane], or null (parallel / behind). */
    private fun wallDepth(tile: Tile, index: Int, plane: PhotosphereGeometry.Plane): Double? {
        val k = tile.intrinsics
        val b = PhotosphereGeometry.bearing(tile.features.points[2 * index], tile.features.points[2 * index + 1], k[0], k[1], k[2], k[3])
        val d = PhotosphereGeometry.rayToWorld(tile.rotation, b)
        val n = plane.normal
        val denom = n[0] * d[0] + n[1] * d[1] + n[2] * d[2]
        if (kotlin.math.abs(denom) < 1e-6) return null
        val c = tile.centre
        val t = (plane.offset - (n[0] * c[0] + n[1] * c[1] + n[2] * c[2])) / denom
        if (t <= 0) return null
        // Only where the ray meets the actual wall, not the plane's extension past it.
        val hit = DoubleArray(3) { c[it] + t * d[it] }
        if (onWall?.invoke(hit) == false) return null
        return t * b[2]
    }

    /**
     * Triangulate [tile] against every other station's tile looking the same way: the two keyframes
     * are a stereo pair with a known baseline (the tiles' centres). Accepted landmarks are attached
     * to both tiles and each tile's plane is refitted.
     *
     * @return landmarks added.
     */
    internal fun compareAcrossStations(tile: Tile): Int {
        var added = 0
        for (u in allTiles()) {
            if (u === tile || angleDeg(u.axis, tile.axis) > config.compareAxisDeg) continue
            // A stereo pair needs a baseline; tiles from the same standpoint have none.
            if (distance(u.centre, tile.centre) <= standpointRadius) continue
            added += triangulatePair(tile, u)
        }
        return added
    }

    private fun allTiles(): List<Tile> = stationList.flatMap { it.tiles }

    private fun triangulatePair(a: Tile, b: Tile): Int {
        val pairs = backend.match(a.features, b.features)
        val ka = a.intrinsics
        val kb = b.intrinsics
        val minParallax = Math.toRadians(config.minParallaxDeg.toDouble())
        var added = 0
        var i = 0
        while (i + 1 < pairs.size) {
            val ia = pairs[i]
            val ib = pairs[i + 1]
            i += 2
            val ua = a.features.points[2 * ia]; val va = a.features.points[2 * ia + 1]
            val ub = b.features.points[2 * ib]; val vb = b.features.points[2 * ib + 1]
            val da = PhotosphereGeometry.rayToWorld(a.rotation, PhotosphereGeometry.bearing(ua, va, ka[0], ka[1], ka[2], ka[3]))
            val db = PhotosphereGeometry.rayToWorld(b.rotation, PhotosphereGeometry.bearing(ub, vb, kb[0], kb[1], kb[2], kb[3]))
            val t = PhotosphereGeometry.triangulateMidpoint(a.centre, da, b.centre, db) ?: continue
            if (t.parallaxRad < minParallax) continue
            if (!reprojects(t.point, a, ua, va) || !reprojects(t.point, b, ub, vb)) continue
            if (a.landmarks.putIfAbsent(ia, t.point) == null) added++
            b.landmarks.putIfAbsent(ib, t.point)
        }
        if (added > 0) {
            refitPlane(a)
            refitPlane(b)
            // Real landmarks are the best depth reference: recalibrate both tiles' depth.
            calibrate(a)
            calibrate(b)
        }
        return added
    }

    private fun reprojects(point: DoubleArray, tile: Tile, u: Float, v: Float): Boolean {
        val k = tile.intrinsics
        val p = PhotosphereGeometry.project(tile.rotation, tile.centre, point, k[0], k[1], k[2], k[3]) ?: return false
        val du = p[0] - u
        val dv = p[1] - v
        return du * du + dv * dv <= config.maxLandmarkReprojPx * config.maxLandmarkReprojPx
    }

    private fun refitPlane(tile: Tile) {
        val points = when {
            tile.landmarks.size >= config.minPlanePoints -> tile.landmarks.values.toList()
            tile.pseudo.size >= config.minPlanePoints -> tile.pseudo.values.toList()
            else -> return
        }
        // Inlier distance relative to the scene depth: 2 % of the median landmark distance.
        val depths = points.map { distance(it, tile.centre) }.sorted()
        val gate = 0.02 * depths[depths.size / 2]
        tile.plane = PhotosphereGeometry.ransacPlane(points, gate, minInliers = config.minPlanePoints)?.first
    }

    /**
     * Localize [live] (features of the current frame, intrinsics [intrinsics]) given a prior pose
     * (gyro rotation + last known centre). Tiles whose axis is near the prior's are tried, nearest
     * first. A tile with enough matched landmarks yields a full [Fix.Pose] (any position); otherwise a
     * tile captured within [standpointRadius] of [priorCentre] yields a [Fix.Rotation]. A pose
     * fix beats a rotation fix; more inliers break ties.
     */
    fun localize(
        live: TileFeatures,
        intrinsics: FloatArray,
        priorRotation: DoubleArray,
        priorCentre: DoubleArray,
    ): Fix? {
        var best: Fix? = null
        for (tile in candidates(priorRotation, priorCentre)) {
            val fix = localizeAgainst(live, intrinsics, priorRotation, priorCentre, tile) ?: continue
            if (best == null || better(fix, best)) best = fix
        }
        return best
    }

    /** The tiles a camera at the prior is tried against: axis within the cone, nearest first. */
    private fun candidates(priorRotation: DoubleArray, priorCentre: DoubleArray): List<Tile> {
        val priorAxis = doubleArrayOf(priorRotation[6], priorRotation[7], priorRotation[8])
        return stationList.asSequence()
            .flatMap { it.tiles.asSequence() }
            .map { it to angleDeg(it.axis, priorAxis) }
            .filter { it.second <= config.candidateConeDeg }
            // Tiles that can give a full pose, or belong to the camera's station, are the useful ones.
            .filter { (t, _) -> sameStandpoint(t, priorCentre) || t.pointCount >= config.minPoseInliers }
            .sortedBy { it.second }
            .take(config.maxCandidates)
            .map { it.first }
            .toList()
    }

    /**
     * The turn of a live frame toward the tile it will most likely match: the pure-rotation
     * homography (row-major) from live pixels ([intrinsics]) to those of the same camera turned to the
     * nearest candidate tile's orientation. A pure turn needs no position, and the sensors' rotation
     * holds while lost, so the turned frame looks as the tile did (its perspective and roll undone),
     * whatever the camera's distance. Null with no candidate, when the views already differ by less
     * than [Config.turnLiveFrameDeg], or with turning off.
     */
    fun turnTowardTile(priorRotation: DoubleArray, priorCentre: DoubleArray, intrinsics: FloatArray): DoubleArray? {
        if (config.turnLiveFrameDeg <= 0f) return null
        val tile = candidates(priorRotation, priorCentre).firstOrNull() ?: return null
        // live camera → world → tile orientation.
        val turn = mul3(tile.rotation, transpose(priorRotation))
        val trace = turn[0] + turn[4] + turn[8]
        val angle = Math.toDegrees(acos(((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
        if (angle < config.turnLiveFrameDeg) return null
        val k = doubleArrayOf(intrinsics[0].toDouble(), 0.0, intrinsics[2].toDouble(), 0.0, intrinsics[1].toDouble(), intrinsics[3].toDouble(), 0.0, 0.0, 1.0)
        val kInv = doubleArrayOf(1.0 / k[0], 0.0, -k[2] / k[0], 0.0, 1.0 / k[4], -k[5] / k[4], 0.0, 0.0, 1.0)
        return mul3(mul3(k, turn), kInv)
    }

    /**
     * Features of [luma] ([width] × [height]) extracted from it turned by [turnedFromLive]
     * ([turnTowardTile]), their positions mapped back to the live frame's pixels, so everything
     * downstream is unchanged. Turned pixels outside the frame are mid-grey (no edge to detect).
     */
    fun extractTurned(luma: ByteArray, width: Int, height: Int, turnedFromLive: DoubleArray): TileFeatures? {
        val back = ObliqueRectifier.invert3(turnedFromLive) ?: return extract(luma, width, height)
        val turned = ByteArray(width * height)
        for (v in 0 until height) for (u in 0 until width) {
            val w = back[6] * u + back[7] * v + back[8]
            var value = 128
            if (w > 1e-9) {
                val x = (back[0] * u + back[1] * v + back[2]) / w
                val y = (back[3] * u + back[4] * v + back[5]) / w
                if (x >= 0 && y >= 0 && x <= width - 1.0 && y <= height - 1.0) {
                    val x0 = minOf(x.toInt(), width - 2).coerceAtLeast(0); val y0 = minOf(y.toInt(), height - 2).coerceAtLeast(0)
                    val fx = x - x0; val fy = y - y0
                    val i = y0 * width + x0
                    fun p(k: Int) = (luma[k].toInt() and 0xff).toDouble()
                    val dx = if (width > 1) 1 else 0; val dy = if (height > 1) width else 0
                    value = ((p(i) * (1 - fx) + p(i + dx) * fx) * (1 - fy) + (p(i + dy) * (1 - fx) + p(i + dy + dx) * fx) * fy + 0.5).toInt()
                }
            }
            turned[v * width + u] = value.coerceIn(0, 255).toByte()
        }
        val features = backend.extract(turned, width, height) ?: return null
        val points = features.points.copyOf()
        var i = 0
        while (i + 1 < points.size) {
            val u = points[i].toDouble(); val v = points[i + 1].toDouble()
            val w = back[6] * u + back[7] * v + back[8]
            points[i] = ((back[0] * u + back[1] * v + back[2]) / w).toFloat()
            points[i + 1] = ((back[3] * u + back[4] * v + back[5]) / w).toFloat()
            i += 2
        }
        return TileFeatures(points, features.descriptors) { features.close() }
    }

    private fun better(a: Fix, b: Fix): Boolean = when {
        a is Fix.Pose && b !is Fix.Pose -> true
        a !is Fix.Pose && b is Fix.Pose -> false
        else -> a.inliers > b.inliers
    }

    private fun localizeAgainst(
        live: TileFeatures,
        k: FloatArray,
        priorRotation: DoubleArray,
        priorCentre: DoubleArray,
        tile: Tile,
    ): Fix? {
        val pairs = backend.match(live, tile.features)
        if (pairs.size / 2 < minOf(config.minPoseInliers, config.minRotationInliers)) return null
        if (tile.pointCount >= config.minPoseInliers) {
            val points = ArrayList<DoubleArray>()
            val pixels = ArrayList<FloatArray>()
            var i = 0
            while (i + 1 < pairs.size) {
                val landmark = tile.pointOf(pairs[i + 1])
                if (landmark != null) {
                    points += landmark
                    pixels += floatArrayOf(live.points[2 * pairs[i]], live.points[2 * pairs[i] + 1])
                }
                i += 2
            }
            if (points.size >= config.minPoseInliers) {
                PhotosphereGeometry.ransacPose(
                    points, pixels, k, priorRotation, priorCentre,
                    inlierPx = config.poseInlierPx.toDouble(), minInliers = config.minPoseInliers,
                )?.let { return Fix.Pose(it.rotation, it.centre, it.inliers.size, tile, it.rmsPx) }
            }
        }
        if (!sameStandpoint(tile, priorCentre)) return null
        val tk = tile.intrinsics
        val a = ArrayList<DoubleArray>(pairs.size / 2)
        val b = ArrayList<DoubleArray>(pairs.size / 2)
        var i = 0
        while (i + 1 < pairs.size) {
            val q = pairs[i]
            val t = pairs[i + 1]
            a += PhotosphereGeometry.bearing(tile.features.points[2 * t], tile.features.points[2 * t + 1], tk[0], tk[1], tk[2], tk[3])
            b += PhotosphereGeometry.bearing(live.points[2 * q], live.points[2 * q + 1], k[0], k[1], k[2], k[3])
            i += 2
        }
        val fit = PhotosphereGeometry.ransacRotation(
            a, b,
            inlierAngleRad = Math.toRadians(config.rotationInlierDeg.toDouble()),
            minInliers = config.minRotationInliers,
        ) ?: return null
        // live_cam = R_rel · tile_cam  ⇒  R_live = R_rel · R_tile.
        return Fix.Rotation(mul3(fit.rotation, tile.rotation), fit.inliers.size, tile)
    }

    private fun sameStandpoint(tile: Tile, centre: DoubleArray) = distance(tile.centre, centre) <= standpointRadius

    /**
     * How [tile] should look from a camera at ([rotation], [centre]) with [intrinsics]: the
     * plane-induced homography from the tile's keyframe pixels to that camera's pixels; null until the
     * tile has a plane.
     */
    fun predictTileHomography(tile: Tile, rotation: DoubleArray, centre: DoubleArray, intrinsics: FloatArray): DoubleArray? {
        val plane = tile.plane ?: return null
        return PhotosphereGeometry.planeHomography(
            tile.rotation, tile.centre, rotation, centre, plane, tile.intrinsics, intrinsics,
        )
    }

    /** Extract a live frame's features (the caller closes them). */
    fun extract(luma: ByteArray, width: Int, height: Int): TileFeatures? = backend.extract(luma, width, height)

    /** Forget every station and tile. */
    fun clear() {
        stationList.forEach { s -> s.tiles.forEach { it.features.close() } }
        stationList.clear()
    }

    override fun close() = clear()

    internal companion object {
        const val MAX_CALIBRATION_PASSES = 4

        fun transpose(a: DoubleArray) = DoubleArray(9) { i -> a[(i % 3) * 3 + i / 3] }

        fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
            val d = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) /
                (sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) * sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]))
            return Math.toDegrees(acos(d.coerceIn(-1.0, 1.0)))
        }

        fun distance(a: DoubleArray, b: DoubleArray): Double {
            val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
            return sqrt(x * x + y * y + z * z)
        }

        fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
            val r = i / 3; val c = i % 3
            a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
    }
}

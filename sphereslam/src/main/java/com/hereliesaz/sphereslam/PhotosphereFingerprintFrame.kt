package com.hereliesaz.sphereslam

import com.hereliesaz.sphereslam.math.RigidMath

/**
 * Coordinate contract for a planar (KPM) fingerprint placed inside a [PhotosphereMap], ported from
 * GraffitiXR's `StandaloneFingerprintFrame`.
 *
 * Fingerprint (wall) frame: origin at the centre of the rectified reference page, +X image-right
 * along the wall, +Y image-up, +Z the wall normal toward the camera; units as
 * [SphereSlamPoseMath.PageGeometry] (metres when physically metric).
 *
 * Map frame (GL style, [AttitudeBasis]): gravity-level and north-referenced — +X east, +Y up, −Z
 * north — the same frame the reloc tracking session's base map uses, so a provisional placement and
 * the base photosphere agree at every elevation, floors and ceilings included. The fingerprint never
 * becomes the world origin: precision poses are rebased into the map with [cameraFromMap].
 */
object PhotosphereFingerprintFrame {
    private const val MILLIMETERS_PER_INCH = 25.4f

    /** A point in the fingerprint's centred wall frame. */
    data class Point3(val x: Float, val y: Float, val z: Float = 0f) {
        init {
            require(x.isFinite() && y.isFinite() && z.isFinite())
        }

        fun toFloatArray(): FloatArray = floatArrayOf(x, y, z)
    }

    /**
     * A fingerprint placement in the map.
     *
     * @property referenceWidthUnits the reference's width in map units at its range.
     * @property physicallyMetric true when the range (and therefore the units) is metric.
     */
    class Placement(
        mapFromFingerprint: FloatArray,
        val referenceWidthUnits: Float,
        val physicallyMetric: Boolean,
        val tileId: TileId,
        val direction: SphereCoverage.Direction,
    ) {
        private val value = mapFromFingerprint.copyOf()

        /** Column-major map-from-fingerprint (a fresh copy). */
        val mapFromFingerprint: FloatArray get() = value.copyOf()
    }

    /**
     * Rectified reference pixel → centred wall frame, reproducing artoolkitX `kpmGenRefDataSet`'s
     * half-pixel mapping (`x_mm = (u + 0.5)/dpi·25.4`, `y_mm = ((h − 0.5) − v)/dpi·25.4`) before
     * subtracting the KPM page centre.
     */
    fun referencePixelToWall(
        u: Float,
        v: Float,
        widthPixels: Int,
        heightPixels: Int,
        geometry: SphereSlamPoseMath.PageGeometry,
    ): Point3 {
        require(widthPixels > 0 && heightPixels > 0)
        require(u.isFinite() && v.isFinite())
        require(u >= -0.5f && u <= widthPixels - 0.5f) { "u lies outside the reference image" }
        require(v >= -0.5f && v <= heightPixels - 0.5f) { "v lies outside the reference image" }
        val dpi = geometry.referenceDpi
        val xMm = (u + 0.5f) / dpi * MILLIMETERS_PER_INCH
        val yMm = ((heightPixels - 0.5f) - v) / dpi * MILLIMETERS_PER_INCH
        return kpmPageMillimetersToWall(xMm, yMm, geometry)
    }

    /** KPM lower-left page millimetres → centred wall frame. */
    fun kpmPageMillimetersToWall(xMm: Float, yMm: Float, geometry: SphereSlamPoseMath.PageGeometry): Point3 {
        require(xMm.isFinite() && yMm.isFinite())
        return Point3(
            x = (xMm - geometry.centerXmm) * SphereSlamPoseMath.MILLIMETERS_TO_METERS,
            y = (yMm - geometry.centerYmm) * SphereSlamPoseMath.MILLIMETERS_TO_METERS,
            z = 0f,
        )
    }

    /**
     * Column-major map-from-fingerprint for a patch seen along [rayEnu] (East-North-Up, any length)
     * at [rangeUnits] (metres only when the photosphere has metric range, else a normalized radius).
     * Fingerprint +Z faces back along the ray; +Y is the camera's image-up [imageUpEnu] made
     * perpendicular to it (the rectified reference's up IS the camera image's up); +X = Y × Z.
     *
     * @return null when [rayEnu] is degenerate or parallel to [imageUpEnu].
     */
    fun mapFromFingerprint(rayEnu: DoubleArray, imageUpEnu: DoubleArray, rangeUnits: Float): FloatArray? {
        require(rayEnu.size == 3 && imageUpEnu.size == 3)
        require(rangeUnits.isFinite() && rangeUnits > 0f)
        val forward = AttitudeBasis.normalized(AttitudeBasis.enuToMap(rayEnu)) ?: return null
        val z = doubleArrayOf(-forward[0], -forward[1], -forward[2])
        val upMap = AttitudeBasis.enuToMap(imageUpEnu)
        val along = AttitudeBasis.dot(upMap, z)
        val y = AttitudeBasis.normalized(DoubleArray(3) { upMap[it] - along * z[it] }) ?: return null
        val x = AttitudeBasis.cross(y, z)
        return floatArrayOf(
            x[0].toFloat(), x[1].toFloat(), x[2].toFloat(), 0f,
            y[0].toFloat(), y[1].toFloat(), y[2].toFloat(), 0f,
            z[0].toFloat(), z[1].toFloat(), z[2].toFloat(), 0f,
            (forward[0] * rangeUnits).toFloat(), (forward[1] * rangeUnits).toFloat(), (forward[2] * rangeUnits).toFloat(), 1f,
        )
    }

    /**
     * [mapFromFingerprint] for an unrolled camera looking straight at the patch: at [azimuthDeg]
     * (compass bearing, 0 = north, clockwise) and [elevationDeg] (up positive), +Y along the tangent of
     * increasing elevation (at the zenith/nadir: the image-up of a camera facing [azimuthDeg]).
     */
    fun mapFromFingerprint(azimuthDeg: Float, elevationDeg: Float, rangeUnits: Float): FloatArray {
        require(azimuthDeg.isFinite() && elevationDeg.isFinite())
        val basis = AttitudeBasis.basis(azimuthDeg, elevationDeg, 0f)!!
        return mapFromFingerprint(basis.forward, basis.up, rangeUnits)!!
    }

    /** `camera_from_map = camera_from_fingerprint · inverse(map_from_fingerprint)` (column-major, rigid). */
    fun cameraFromMap(cameraFromFingerprint: FloatArray, mapFromFingerprint: FloatArray): FloatArray {
        require(cameraFromFingerprint.size == 16 && cameraFromFingerprint.all { it.isFinite() })
        require(mapFromFingerprint.size == 16 && mapFromFingerprint.all { it.isFinite() })
        return RigidMath.multiply(cameraFromFingerprint, RigidMath.rigidInverse(mapFromFingerprint))
    }

    /**
     * Place a fingerprint captured at normalized image point ([nx], [ny]) (top-left origin, `[0, 1]`;
     * the image centre by default) of [view] into [map].
     *
     * The tapped pixel's ray and the reference's orientation both come from the view's full camera
     * rotation ([AttitudeBasis]): the ray through heading, elevation AND roll
     * ([PhotosphereMap.directionOfPixel]), the page's +Y from the camera's image-up. Nothing depends on
     * the compass heading alone, so a fingerprint captured looking straight down at a floor is placed
     * as repeatably as one on a wall. The tile's metric range is used when known; otherwise the
     * placement is at unit radius and non-metric.
     *
     * @return null when the map has no wall heading or the direction is outside its lattice.
     */
    fun placement(map: PhotosphereMap, view: PhotosphereView, nx: Float = 0.5f, ny: Float = 0.5f): Placement? {
        if (!map.hasWallHeading()) return null
        val u = nx.coerceIn(0f, 1f) * view.width
        val v = ny.coerceIn(0f, 1f) * view.height
        val ray = PhotosphereMap.rayOfPixel(
            u, v, view.fx, view.fy, view.cx, view.cy,
            view.headingDeg, view.elevationDeg, view.rollDeg,
        ) ?: return null
        val basis = AttitudeBasis.basis(view.headingDeg, view.elevationDeg, view.rollDeg) ?: return null
        val dir = AttitudeBasis.direction(ray)
        val tileId = map.tileAt(dir.azimuthDeg, dir.elevationDeg) ?: return null
        val tile = map.tile(tileId) ?: return null
        val metricRange = tile.rangeMeters?.takeIf { it.isFinite() && it > 0f }
        val range = metricRange ?: 1f
        return Placement(
            mapFromFingerprint = mapFromFingerprint(ray, basis.up, range) ?: return null,
            referenceWidthUnits = (range * view.width / view.fx).coerceAtLeast(1e-4f),
            physicallyMetric = metricRange != null,
            tileId = tileId,
            direction = dir,
        )
    }
}

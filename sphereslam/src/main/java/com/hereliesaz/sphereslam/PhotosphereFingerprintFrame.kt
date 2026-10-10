package com.hereliesaz.sphereslam

import com.hereliesaz.sphereslam.math.RigidMath
import kotlin.math.cos
import kotlin.math.sin

/**
 * Coordinate contract for a planar (KPM) fingerprint placed inside a [PhotosphereMap], ported from
 * GraffitiXR's `StandaloneFingerprintFrame`.
 *
 * Fingerprint (wall) frame: origin at the centre of the rectified reference page, +X image-right
 * along the wall, +Y image-up, +Z the wall normal toward the camera; units as
 * [SphereSlamPoseMath.PageGeometry] (metres when physically metric).
 *
 * Map frame (GL style): +X right across the wall-facing arc, +Y up, and the wall heading's centre
 * direction points toward −Z. The fingerprint never becomes the world origin: precision poses are
 * rebased into the map with [cameraFromMap].
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
     * Column-major map-from-fingerprint for a wall patch at [azimuthDeltaDeg] (relative to the map's
     * wall heading, clockwise positive) and [elevationDeg] (up positive), at [rangeUnits] (metres only
     * when the photosphere has metric range, else a normalized radius). Fingerprint +Z faces the
     * camera; +Y is the tangent of increasing elevation; +X = Y × Z.
     */
    fun mapFromFingerprint(azimuthDeltaDeg: Float, elevationDeg: Float, rangeUnits: Float): FloatArray {
        require(azimuthDeltaDeg.isFinite() && elevationDeg.isFinite())
        require(rangeUnits.isFinite() && rangeUnits > 0f)
        val az = Math.toRadians(azimuthDeltaDeg.toDouble())
        val el = Math.toRadians(elevationDeg.toDouble())
        val sinAz = sin(az).toFloat(); val cosAz = cos(az).toFloat()
        val sinEl = sin(el).toFloat(); val cosEl = cos(el).toFloat()
        val forward = floatArrayOf(sinAz * cosEl, sinEl, -cosAz * cosEl)
        val z = floatArrayOf(-forward[0], -forward[1], -forward[2])
        val y = floatArrayOf(-sinAz * sinEl, cosEl, cosAz * sinEl)
        val x = floatArrayOf(
            y[1] * z[2] - y[2] * z[1],
            y[2] * z[0] - y[0] * z[2],
            y[0] * z[1] - y[1] * z[0],
        )
        return floatArrayOf(
            x[0], x[1], x[2], 0f,
            y[0], y[1], y[2], 0f,
            z[0], z[1], z[2], 0f,
            forward[0] * rangeUnits, forward[1] * rangeUnits, forward[2] * rangeUnits, 1f,
        )
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
     * The tapped pixel's absolute direction comes from [PhotosphereMap.directionOfPixel], which
     * accounts for camera roll (GraffitiXR added `atan` pixel offsets straight onto heading and
     * elevation, which is wrong for any rolled camera and approximate off-axis). The tile's metric
     * range is used when known; otherwise the placement is at unit radius and non-metric.
     *
     * @return null when the map has no wall heading or the direction is outside its lattice.
     */
    fun placement(map: PhotosphereMap, view: PhotosphereView, nx: Float = 0.5f, ny: Float = 0.5f): Placement? {
        val wallHeading = map.snapshot().wallHeadingDeg ?: return null
        val u = nx.coerceIn(0f, 1f) * view.width
        val v = ny.coerceIn(0f, 1f) * view.height
        val dir = PhotosphereMap.directionOfPixel(
            u, v, view.fx, view.fy, view.cx, view.cy,
            view.headingDeg, view.elevationDeg, view.rollDeg,
        ) ?: return null
        val elevation = dir.elevationDeg.coerceIn(-89f, 89f)
        val tileId = map.tileAt(dir.azimuthDeg, elevation) ?: return null
        val tile = map.tile(tileId) ?: return null
        val metricRange = tile.rangeMeters?.takeIf { it.isFinite() && it > 0f }
        val range = metricRange ?: 1f
        return Placement(
            mapFromFingerprint = mapFromFingerprint(SphereGrid.signedDelta(dir.azimuthDeg, wallHeading), elevation, range),
            referenceWidthUnits = (range * view.width / view.fx).coerceAtLeast(1e-4f),
            physicallyMetric = metricRange != null,
            tileId = tileId,
            direction = SphereCoverage.Direction(dir.azimuthDeg, elevation),
        )
    }
}

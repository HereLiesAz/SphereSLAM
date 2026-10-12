package com.hereliesaz.sphereslam

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The camera basis behind every (heading, elevation, roll) attitude in the library, defined at every
 * elevation including straight up and down.
 *
 * Heading is the compass bearing of the camera axis (0 = north, clockwise); near the zenith/nadir it
 * is dominated by sensor noise, but a (heading, elevation, roll) triple decoded from ONE rotation
 * still describes that rotation exactly: roll is measured from the horizontal right of that same
 * heading, `(cos h, −sin h, 0)` in East-North-Up, so heading noise and roll noise cancel. Code that
 * needs a pole-stable direction uses the full basis, or [facingAzimuthDeg] — never heading alone.
 *
 * World frame: East-North-Up. Map frame (GL, shared by [PhotosphereFingerprintFrame] and the reloc
 * tracking session's base map): +X east, +Y up, −Z north ([enuToMap]).
 */
object AttitudeBasis {

    /** Camera forward (optical axis), image-right and image-up as East-North-Up unit vectors. */
    class Basis(val forward: DoubleArray, val right: DoubleArray, val up: DoubleArray)

    /**
     * The camera basis for ([headingDeg], [elevationDeg], [rollDeg]); null only for non-finite input.
     * Roll turns image-right above the horizontal right (`CameraAttitudeProvider.cameraRollDegrees`).
     */
    fun basis(headingDeg: Float, elevationDeg: Float, rollDeg: Float = 0f): Basis? {
        if (!headingDeg.isFinite() || !elevationDeg.isFinite() || !rollDeg.isFinite()) return null
        val h = Math.toRadians(headingDeg.toDouble())
        val e = Math.toRadians(elevationDeg.toDouble())
        val f = doubleArrayOf(cos(e) * sin(h), cos(e) * cos(h), sin(e))
        val r0 = doubleArrayOf(cos(h), -sin(h), 0.0)
        // u0 = r0 × f: the elevation tangent, image-up at zero roll.
        val u0 = cross(r0, f)
        val roll = Math.toRadians(rollDeg.toDouble())
        val cr = cos(roll); val sr = sin(roll)
        val right = DoubleArray(3) { cr * r0[it] + sr * u0[it] }
        val up = DoubleArray(3) { -sr * r0[it] + cr * u0[it] }
        return Basis(f, right, up)
    }

    /**
     * The compass bearing the camera is FACING, stable at every elevation: the camera-axis heading
     * near the horizon, blending into the bearing the top of the image points toward when looking
     * straight down (and away from it when looking straight up) — the direction the artist faces
     * while holding the phone over a floor or under a ceiling. Equals [headingDeg] at zero roll.
     *
     * It is the horizontal part of `cos(e)·forward − sin(e)·up`, which is exactly the heading
     * direction for an unrolled camera and keeps a well-defined length near the poles.
     *
     * @return degrees in `[0, 360)`, or null for non-finite input (or the measure-zero attitudes
     *   where that vector vanishes: rolled upside down at ±45°).
     */
    fun facingAzimuthDeg(headingDeg: Float, elevationDeg: Float, rollDeg: Float = 0f): Float? {
        val b = basis(headingDeg, elevationDeg, rollDeg) ?: return null
        val e = Math.toRadians(elevationDeg.toDouble())
        val x = cos(e) * b.forward[0] - sin(e) * b.up[0]
        val y = cos(e) * b.forward[1] - sin(e) * b.up[1]
        if (x * x + y * y < 1e-12) return null
        return SphereGrid.norm360(Math.toDegrees(atan2(x, y)).toFloat())
    }

    /** East-North-Up → map frame (+X east, +Y up, −Z north). */
    fun enuToMap(v: DoubleArray): DoubleArray = doubleArrayOf(v[0], v[2], -v[1])

    /** Row-major 3×3 world(ENU)-from-map rotation: the map axes as East-North-Up columns. */
    val WORLD_FROM_MAP: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 0f, -1f,
        0f, 1f, 0f,
    )

    /** Azimuth (0 = north, clockwise) and elevation, degrees, of an East-North-Up vector. */
    fun direction(v: DoubleArray): SphereCoverage.Direction {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        val az = Math.toDegrees(atan2(v[0], v[1])).toFloat()
        val el = Math.toDegrees(asin((v[2] / n).coerceIn(-1.0, 1.0))).toFloat()
        return SphereCoverage.Direction(SphereGrid.norm360(az), el)
    }

    internal fun cross(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    internal fun dot(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    internal fun normalized(a: DoubleArray): DoubleArray? {
        val n = sqrt(dot(a, a))
        if (n < 1e-12 || !n.isFinite()) return null
        return doubleArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }
}

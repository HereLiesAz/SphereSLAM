package com.hereliesaz.sphereslam.reloc

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Synthetic oblique views of a page plane (z = 0, millimetres) for the rectification tests. */
internal object ObliqueGeometry {

    /**
     * Image-convention world→camera (R row-major, t) of a camera at [distanceMm] from page point
     * [target], on the +z side, tilted [tiltDeg] away from the normal about the page's y axis and
     * rolled [rollDeg].
     */
    fun pose(tiltDeg: Double, target: DoubleArray, distanceMm: Double, rollDeg: Double = 4.0): Pair<DoubleArray, DoubleArray> {
        val t = Math.toRadians(tiltDeg)
        // Camera centre: from the target, back along the tilted normal.
        val c = doubleArrayOf(target[0] + distanceMm * sin(t), target[1], distanceMm * cos(t))
        // Forward axis points at the target.
        val f = normalized(doubleArrayOf(target[0] - c[0], target[1] - c[1], -c[2]))
        val upHint = doubleArrayOf(0.0, -1.0, 0.0) // image y down ↔ page −y
        val x0 = normalized(cross(upHint, f)).map { -it }.toDoubleArray()
        val y0 = cross(f, x0)
        val rr = Math.toRadians(rollDeg)
        val x = DoubleArray(3) { x0[it] * cos(rr) + y0[it] * sin(rr) }
        val y = DoubleArray(3) { -x0[it] * sin(rr) + y0[it] * cos(rr) }
        val r = doubleArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], f[0], f[1], f[2])
        val tr = DoubleArray(3) { i -> -(r[i * 3] * c[0] + r[i * 3 + 1] * c[1] + r[i * 3 + 2] * c[2]) }
        return r to tr
    }

    /** Page point (x, y, 0) mm → pixel, for intrinsics (fx, fy, cx, cy). */
    fun project(k: FloatArray, r: DoubleArray, t: DoubleArray, x: Double, y: Double): DoubleArray {
        val cx = r[0] * x + r[1] * y + t[0]
        val cy = r[3] * x + r[4] * y + t[1]
        val cz = r[6] * x + r[7] * y + t[2]
        return doubleArrayOf(k[0] * cx / cz + k[2], k[1] * cy / cz + k[3])
    }

    /** Row-major 3x4 [R | t] as floats. */
    fun pose3x4(r: DoubleArray, t: DoubleArray) =
        FloatArray(12) { i -> if (i % 4 == 3) t[i / 4].toFloat() else r[(i / 4) * 3 + i % 4].toFloat() }

    private fun normalized(v: DoubleArray): DoubleArray {
        val n = hypot(hypot(v[0], v[1]), v[2])
        return DoubleArray(3) { v[it] / n }
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )
}

package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.math.RigidMath
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Metric 3D marks for a fingerprint without a depth sensor, pure math:
 *
 * - **single view on a known plane** ([backProjectToPlane]): intersect each feature pixel's ray with a
 *   wall plane the tracker has already solved (e.g. an ARCore plane) — the depth is already paid for;
 * - **two views** ([triangulateTwoView]): DLT-triangulate matched pixels across a step-in/step-back
 *   baseline through [TileTriangulator], gated by baseline, cheirality and per-view reprojection.
 *
 * Output points are in the (first) capture camera's **OpenCV** frame (+z forward, depth positive) —
 * the frame depth/plane-built fingerprints store their object points in, so they feed PnP directly.
 *
 * Conventions: views here are **column-major** world→camera transforms in the OpenCV convention
 * (convert OpenGL/ARCore views with [RigidMath.glViewToCv], or [glViewToCvDisplay] when the pixels and
 * intrinsics were rotated into display orientation). The view must be in the same frame as the
 * pixels and intrinsics; this object faithfully intersects whatever frame it is handed.
 *
 * Ported from GraffitiXR `anchor.PlaneMarks` / `anchor.MetricMarks`.
 */
@ExperimentalSphereSlamRelocApi
object MetricMarks {

    /** A matched mark: pixel ([u0], [v0]) in view 0 and ([u1], [v1]) in view 1 (same intrinsics). */
    data class Corr(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    /**
     * [kept] indexes the input; [points] is flat `[x, y, z, …]` (one triplet per kept entry) in the
     * capture camera's OpenCV frame.
     */
    class Result(val kept: IntArray, val points: FloatArray) {
        val count: Int get() = kept.size
    }

    /**
     * Back-project feature pixels (flat `[u0, v0, u1, v1, …]`) onto the plane through
     * [planePointWorld] with normal [planeNormalWorld] (world frame; any length), seen through the
     * OpenCV world→camera [cvView]. Rays parallel to the plane, or hitting it outside
     * [minDepth]..[maxDepth] (camera-axis depth), are dropped.
     */
    fun backProjectToPlane(
        pixels: FloatArray,
        cvView: FloatArray,
        planePointWorld: FloatArray,
        planeNormalWorld: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        minDepth: Float = 0.1f,
        maxDepth: Float = 10f,
    ): Result {
        require(cvView.size == 16 && planePointWorld.size == 3 && planeNormalWorld.size == 3)
        val m = cvView
        val p = floatArrayOf(
            m[0] * planePointWorld[0] + m[4] * planePointWorld[1] + m[8] * planePointWorld[2] + m[12],
            m[1] * planePointWorld[0] + m[5] * planePointWorld[1] + m[9] * planePointWorld[2] + m[13],
            m[2] * planePointWorld[0] + m[6] * planePointWorld[1] + m[10] * planePointWorld[2] + m[14],
        )
        val n = floatArrayOf(
            m[0] * planeNormalWorld[0] + m[4] * planeNormalWorld[1] + m[8] * planeNormalWorld[2],
            m[1] * planeNormalWorld[0] + m[5] * planeNormalWorld[1] + m[9] * planeNormalWorld[2],
            m[2] * planeNormalWorld[0] + m[6] * planeNormalWorld[1] + m[10] * planeNormalWorld[2],
        )
        return backProjectToCameraPlane(pixels, p, n, fx, fy, cx, cy, minDepth, maxDepth)
    }

    /**
     * [backProjectToPlane] for a plane already in the camera's OpenCV frame (point [planePoint],
     * normal [planeNormal]). The camera-axis depth of a hit is its ray parameter `t` (rays are
     * `t·((u−cx)/fx, (v−cy)/fy, 1)`).
     */
    fun backProjectToCameraPlane(
        pixels: FloatArray,
        planePoint: FloatArray,
        planeNormal: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        minDepth: Float = 0.1f,
        maxDepth: Float = 10f,
        eps: Float = 1e-6f,
    ): Result {
        require(pixels.size % 2 == 0) { "pixels must be (u, v) pairs" }
        require(fx > 0f && fy > 0f)
        val n = planeNormal
        val nDotP = n[0] * planePoint[0] + n[1] * planePoint[1] + n[2] * planePoint[2]
        val count = pixels.size / 2
        val kept = IntArray(count)
        val pts = FloatArray(count * 3)
        var k = 0
        for (i in 0 until count) {
            val dx = (pixels[2 * i] - cx) / fx
            val dy = (pixels[2 * i + 1] - cy) / fy
            val nDotD = n[0] * dx + n[1] * dy + n[2]
            if (abs(nDotD) < eps) continue
            val t = nDotP / nDotD
            if (!t.isFinite() || t < minDepth || t > maxDepth) continue
            kept[k] = i
            pts[3 * k] = t * dx; pts[3 * k + 1] = t * dy; pts[3 * k + 2] = t
            k++
        }
        return Result(kept.copyOf(k), pts.copyOf(k * 3))
    }

    /**
     * Triangulate matched marks into view 0's OpenCV camera frame. Keeps a mark only when it
     * triangulates in front of both cameras and reprojects within [maxReprojPx] in **each** view;
     * returns nothing when the camera baseline is below [minBaseline] (geometry too weak).
     *
     * @param cvView0 / [cvView1] column-major OpenCV world→camera views.
     */
    fun triangulateTwoView(
        corrs: List<Corr>,
        cvView0: FloatArray,
        cvView1: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        maxReprojPx: Float = 2f,
        minBaseline: Float = 0.03f,
    ): Result {
        require(cvView0.size == 16 && cvView1.size == 16)
        if (baseline(cvView0, cvView1) < minBaseline) return Result(IntArray(0), FloatArray(0))
        val rm0 = RigidMath.columnMajorToRowMajor(cvView0)
        val rm1 = RigidMath.columnMajorToRowMajor(cvView1)
        val kept = ArrayList<Int>(corrs.size)
        val pts = ArrayList<Float>(corrs.size * 3)
        for ((i, c) in corrs.withIndex()) {
            val tri = TileTriangulator.triangulate(
                listOf(TileTriangulator.View(rm0, c.u0, c.v0), TileTriangulator.View(rm1, c.u1, c.v1)),
                fx.toDouble(), fy.toDouble(), cx.toDouble(), cy.toDouble(),
            ) ?: continue
            val w = tri.point
            val cam0 = transform(cvView0, w)
            val cam1 = transform(cvView1, w)
            if (cam0[2] <= 0f || cam1[2] <= 0f) continue
            if (reprojection(cam0, c.u0, c.v0, fx, fy, cx, cy) > maxReprojPx) continue
            if (reprojection(cam1, c.u1, c.v1, fx, fy, cx, cy) > maxReprojPx) continue
            kept.add(i)
            pts.add(cam0[0]); pts.add(cam0[1]); pts.add(cam0[2])
        }
        return Result(kept.toIntArray(), pts.toFloatArray())
    }

    /** Distance between two column-major views' camera centres. */
    fun baseline(view0: FloatArray, view1: FloatArray): Float {
        val c0 = RigidMath.cameraCentre(view0)
        val c1 = RigidMath.cameraCentre(view1)
        val dx = c0[0] - c1[0]; val dy = c0[1] - c1[1]; val dz = c0[2] - c1[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * [RigidMath.glViewToCv], then rotated into the **display** camera frame for a capture whose
     * pixels and intrinsics were rotated by [rotationDeg] (a multiple of 90): display = `R_z(+θ)` ·
     * sensor, pre-multiplying the view.
     */
    fun glViewToCvDisplay(view: FloatArray, rotationDeg: Int): FloatArray {
        val r = quarterTurn(rotationDeg)
        val cv = RigidMath.glViewToCv(view)
        return if (r == 0) cv else rotateZ(cv, r)
    }

    /**
     * The same display rotation on an OpenGL view: in GL the physical `+θ` about the optical axis is
     * `R_z(−θ)` (`D·R_z(θ)·D = R_z(−θ)`, `D = diag(1, −1, −1)`), so this agrees with
     * [glViewToCvDisplay] through [RigidMath.glViewToCv].
     */
    fun glViewDisplayOriented(view: FloatArray, rotationDeg: Int): FloatArray {
        require(view.size == 16)
        val r = quarterTurn(rotationDeg)
        return if (r == 0) view.copyOf() else rotateZ(view, (360 - r) % 360)
    }

    private fun quarterTurn(rotationDeg: Int): Int {
        val r = ((rotationDeg % 360) + 360) % 360
        require(r % 90 == 0) { "rotationDeg must be a multiple of 90, was $rotationDeg" }
        return r
    }

    /** Pre-multiply a column-major view by `R_z(deg)` (exact quarter turns). */
    private fun rotateZ(m: FloatArray, deg: Int): FloatArray {
        val (c, s) = when (deg) {
            90 -> 0f to 1f
            180 -> -1f to 0f
            270 -> 0f to -1f
            else -> 1f to 0f
        }
        val out = m.copyOf()
        for (col in 0 until 4) {
            val x = m[col * 4]
            val y = m[col * 4 + 1]
            out[col * 4] = c * x - s * y
            out[col * 4 + 1] = s * x + c * y
        }
        return out
    }

    private fun transform(m: FloatArray, p: FloatArray) = floatArrayOf(
        m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
        m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
        m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14],
    )

    private fun reprojection(cam: FloatArray, u: Float, v: Float, fx: Float, fy: Float, cx: Float, cy: Float): Float =
        hypot(fx * cam[0] / cam[2] + cx - u, fy * cam[1] / cam[2] + cy - v)
}

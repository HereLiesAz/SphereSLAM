package com.hereliesaz.sphereslam.reloc

import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin geometry core of the photosphere tracker: no OpenCV, no Android, so every routine is
 * unit-testable on the JVM and usable as a fallback when the native library is missing.
 *
 * What it covers, in the order the tracker needs it:
 *
 * 1. **Rotation from bearings** — a phone turning in place ("station") sees a stored keyframe tile
 *    under a pure rotation, so matched pixels reduce to matched unit bearings and the rotation has a
 *    closed form ([rotationFromBearings], Horn's quaternion method) and a robust wrapper
 *    ([ransacRotation], 2-point minimal samples).
 * 2. **Triangulation** — when the artist moves to a second station with a known position (from wall
 *    tracking), the same tile's features seen from both stations become 3-D landmarks
 *    ([triangulateMidpoint]) and each tile gets a plane ([fitPlane], [ransacPlane]).
 * 3. **View prediction** — a tile's look from any other pose is the plane-induced homography
 *    ([planeHomography], [applyHomography]).
 * 4. **Pose from 2-D–3-D matches** — a prior-seeded Levenberg–Marquardt PnP ([refinePose],
 *    [ransacPose]); the tracker always has a prior from the gyro and the last position, so no
 *    minimal P3P solver is needed.
 *
 * ### Conventions
 * - **Camera frame** is OpenCV's: x right, y down, z forward (out of the lens).
 * - **Pose** is a rotation `R` (row-major 3×3, `DoubleArray(9)`, world→camera) and a camera centre
 *   `c` (`DoubleArray(3)`, world): `x_cam = R · (x_world − c)`.
 * - **Intrinsics** `k` are `FloatArray(4) = [fx, fy, cx, cy]` (pixels; no distortion).
 * - **Bearing** of pixel `(u, v)` is `normalize(((u − cx)/fx, (v − cy)/fy, 1))`, camera frame.
 * - **Plane** is `n · x = offset` in world, `n` unit.
 * - **Quaternions** (helpers only) are `[x, y, z, w]`, matching [RotationDeltaMath].
 *
 * Numerics are `Double` throughout; `Float` appears only at the pixel/intrinsics boundary.
 */
@ExperimentalSphereSlamRelocApi
object PhotosphereGeometry {

    // ---------------------------------------------------------------------------------------------
    // Result types
    // ---------------------------------------------------------------------------------------------

    /**
     * A robustly estimated rotation between two sets of bearings.
     *
     * @property rotation row-major 3×3 with `b ≈ R · a` (see [ransacRotation]).
     * @property inliers indices (into the input lists) whose angular residual is within the gate.
     * @property rmsAngleRad RMS angle between `b_i` and `R · a_i` over [inliers], radians.
     */
    data class RotationFit(
        val rotation: DoubleArray,
        val inliers: IntArray,
        val rmsAngleRad: Double,
    ) {
        override fun equals(other: Any?): Boolean =
            other is RotationFit &&
                rotation.contentEquals(other.rotation) &&
                inliers.contentEquals(other.inliers) &&
                rmsAngleRad == other.rmsAngleRad

        override fun hashCode(): Int =
            (rotation.contentHashCode() * 31 + inliers.contentHashCode()) * 31 + rmsAngleRad.hashCode()
    }

    /**
     * A triangulated point.
     *
     * @property point midpoint of the closest points of the two rays, world.
     * @property parallaxRad angle between the two rays, radians (small = poorly conditioned depth).
     * @property gap distance between the two closest points (0 for rays that truly intersect),
     *   world units; a large gap relative to depth flags a bad match.
     */
    data class Triangulated(
        val point: DoubleArray,
        val parallaxRad: Double,
        val gap: Double,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Triangulated &&
                point.contentEquals(other.point) &&
                parallaxRad == other.parallaxRad &&
                gap == other.gap

        override fun hashCode(): Int =
            (point.contentHashCode() * 31 + parallaxRad.hashCode()) * 31 + gap.hashCode()
    }

    /**
     * A world plane `normal · x = offset`.
     *
     * The fitters return the orientation with `offset ≥ 0` (the normal points away from the world
     * origin); [planeHomography] does not depend on the sign.
     *
     * @property normal unit normal, world.
     * @property offset signed distance of the plane from the world origin along [normal].
     */
    data class Plane(
        val normal: DoubleArray,
        val offset: Double,
    ) {
        /** Signed distance of world point [p] from the plane (positive on the [normal] side). */
        fun distance(p: DoubleArray): Double = dot(normal, p) - offset

        override fun equals(other: Any?): Boolean =
            other is Plane && normal.contentEquals(other.normal) && offset == other.offset

        override fun hashCode(): Int = normal.contentHashCode() * 31 + offset.hashCode()
    }

    /**
     * A camera pose solved from 2-D–3-D matches.
     *
     * @property rotation row-major 3×3 world→camera.
     * @property centre camera centre, world.
     * @property inliers indices (into the input lists) supporting the pose.
     * @property rmsPx RMS reprojection error over [inliers], pixels.
     */
    data class PoseFit(
        val rotation: DoubleArray,
        val centre: DoubleArray,
        val inliers: IntArray,
        val rmsPx: Double,
    ) {
        override fun equals(other: Any?): Boolean =
            other is PoseFit &&
                rotation.contentEquals(other.rotation) &&
                centre.contentEquals(other.centre) &&
                inliers.contentEquals(other.inliers) &&
                rmsPx == other.rmsPx

        override fun hashCode(): Int =
            ((rotation.contentHashCode() * 31 + centre.contentHashCode()) * 31 +
                inliers.contentHashCode()) * 31 + rmsPx.hashCode()
    }

    // ---------------------------------------------------------------------------------------------
    // Camera model
    // ---------------------------------------------------------------------------------------------

    /** Unit camera-frame bearing of pixel `(u, v)` under intrinsics `fx, fy, cx, cy`. */
    fun bearing(u: Float, v: Float, fx: Float, fy: Float, cx: Float, cy: Float): DoubleArray =
        normalize(doubleArrayOf((u.toDouble() - cx) / fx, (v.toDouble() - cy) / fy, 1.0))

    /** World-frame direction of camera-frame bearing [bearingCam] under world→camera [rotation]: `Rᵀ · b`. */
    fun rayToWorld(rotation: DoubleArray, bearingCam: DoubleArray): DoubleArray =
        mulTransposeVec(rotation, bearingCam)

    /**
     * Pixel `(u, v)` of world [point] seen by the camera at ([rotation], [centre]); null when the
     * point is not in front of the camera (`z ≤ 0`).
     */
    fun project(
        rotation: DoubleArray,
        centre: DoubleArray,
        point: DoubleArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
    ): FloatArray? {
        val x = toCamera(rotation, centre, point)
        if (x[2] <= MIN_DEPTH) return null
        return floatArrayOf((fx * x[0] / x[2] + cx).toFloat(), (fy * x[1] / x[2] + cy).toFloat())
    }

    // ---------------------------------------------------------------------------------------------
    // Rotation from bearings
    // ---------------------------------------------------------------------------------------------

    /**
     * The rotation `R` minimising `Σ w_i ‖b_i − R · a_i‖²` (so `b ≈ R a`), by Horn's closed-form
     * quaternion method: the optimal unit quaternion is the eigenvector of the largest eigenvalue of
     * a 4×4 symmetric matrix built from the weighted cross-covariance `Σ w a bᵀ`.
     *
     * Inputs need not be unit vectors, but bearings are expected. Always returns a proper rotation
     * (det +1), row-major.
     *
     * @param weights optional non-negative per-pair weights (default all 1).
     * @return null for fewer than 2 pairs, mismatched sizes, a non-positive total weight, or
     *   degenerate input (all `a` or all `b` parallel, which leaves the roll about that axis free).
     */
    fun rotationFromBearings(
        a: List<DoubleArray>,
        b: List<DoubleArray>,
        weights: DoubleArray? = null,
    ): DoubleArray? {
        val n = a.size
        if (n < 2 || b.size != n || (weights != null && weights.size != n)) return null
        if (weights != null && weights.any { it < 0.0 || it.isNaN() }) return null
        if (isCollinear(a, weights) || isCollinear(b, weights)) return null

        // S[i][j] = Σ w a_i b_j
        val s = DoubleArray(9)
        var total = 0.0
        for (k in 0 until n) {
            val w = weights?.get(k) ?: 1.0
            if (w == 0.0) continue
            total += w
            val ak = a[k]
            val bk = b[k]
            for (i in 0..2) for (j in 0..2) s[i * 3 + j] += w * ak[i] * bk[j]
        }
        if (total <= 0.0) return null
        val sxx = s[0]; val sxy = s[1]; val sxz = s[2]
        val syx = s[3]; val syy = s[4]; val syz = s[5]
        val szx = s[6]; val szy = s[7]; val szz = s[8]
        // Horn's N, quaternion order (w, x, y, z).
        val nMat = doubleArrayOf(
            sxx + syy + szz, syz - szy, szx - sxz, sxy - syx,
            syz - szy, sxx - syy - szz, sxy + syx, szx + sxz,
            szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy,
            sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz,
        )
        val (values, vectors) = symmetricEigen(nMat, 4)
        val best = argMax(values)
        val q = DoubleArray(4) { vectors[it * 4 + best] }
        // (w, x, y, z) → [x, y, z, w]
        return quaternionToMatrix(doubleArrayOf(q[1], q[2], q[3], q[0]))
    }

    /**
     * Robust rotation between matched bearings (`b ≈ R a`) by RANSAC over 2-point minimal samples.
     *
     * Each sample solves [rotationFromBearings] on two pairs (samples whose `a` or `b` bearings are
     * within ~2° of parallel, or whose inter-bearing angles disagree by more than 4× the gate, are
     * skipped); hypotheses are scored by how many pairs have `∠(b_i, R a_i) ≤ inlierAngleRad`. The
     * best one is refined on all its inliers and the inlier set recomputed once.
     *
     * @return null when the best hypothesis has fewer than [minInliers] inliers (or no valid sample).
     */
    fun ransacRotation(
        a: List<DoubleArray>,
        b: List<DoubleArray>,
        inlierAngleRad: Double = Math.toRadians(1.0),
        iterations: Int = 200,
        minInliers: Int = 12,
        random: Random = Random(7),
    ): RotationFit? {
        val n = a.size
        if (n < 2 || b.size != n) return null
        val an = a.map { normalize(it) }
        val bn = b.map { normalize(it) }
        var bestR: DoubleArray? = null
        var bestCount = -1
        for (it in 0 until iterations) {
            val i = random.nextInt(n)
            var j = random.nextInt(n - 1)
            if (j >= i) j++
            if (norm(cross(an[i], an[j])) < MIN_SAMPLE_SIN || norm(cross(bn[i], bn[j])) < MIN_SAMPLE_SIN) continue
            // A rotation preserves angles: a sample whose pair angles disagree cannot be all-inlier.
            if (abs(angleBetween(an[i], an[j]) - angleBetween(bn[i], bn[j])) > 4.0 * inlierAngleRad) continue
            val r = rotationFromBearings(listOf(an[i], an[j]), listOf(bn[i], bn[j])) ?: continue
            val count = countRotationInliers(r, an, bn, inlierAngleRad)
            if (count > bestCount) {
                bestCount = count
                bestR = r
            }
        }
        var r = bestR ?: return null
        if (bestCount < minInliers) return null
        var inliers = rotationInliers(r, an, bn, inlierAngleRad)
        if (inliers.size >= 2) {
            rotationFromBearings(inliers.map { an[it] }, inliers.map { bn[it] })?.let { refined ->
                val refinedInliers = rotationInliers(refined, an, bn, inlierAngleRad)
                if (refinedInliers.size >= inliers.size) {
                    r = refined
                    inliers = refinedInliers
                }
            }
        }
        if (inliers.size < minInliers) return null
        var sum = 0.0
        for (k in inliers) {
            val e = angleBetween(bn[k], mulVec(r, an[k]))
            sum += e * e
        }
        return RotationFit(r, inliers, sqrt(sum / inliers.size))
    }

    // ---------------------------------------------------------------------------------------------
    // Triangulation and planes
    // ---------------------------------------------------------------------------------------------

    /**
     * Midpoint triangulation of two rays `c1 + t1·d1` and `c2 + t2·d2` (world frame; [d1], [d2] are
     * world directions, e.g. from [rayToWorld], normalised here).
     *
     * @return null when the rays are (near) parallel (parallax < 1e-4 rad) or the closest point lies
     *   behind either camera (`t ≤ 0`).
     */
    fun triangulateMidpoint(
        c1: DoubleArray,
        d1: DoubleArray,
        c2: DoubleArray,
        d2: DoubleArray,
    ): Triangulated? {
        val u = normalize(d1)
        val v = normalize(d2)
        val parallax = angleBetween(u, v)
        if (parallax < MIN_PARALLAX_RAD) return null
        val w0 = sub(c1, c2)
        val b = dot(u, v)
        val d = dot(u, w0)
        val e = dot(v, w0)
        val denom = 1.0 - b * b // |u| = |v| = 1
        if (denom <= 0.0) return null
        val t1 = (b * e - d) / denom
        val t2 = (e - b * d) / denom
        if (t1 <= 0.0 || t2 <= 0.0) return null
        val p1 = DoubleArray(3) { c1[it] + t1 * u[it] }
        val p2 = DoubleArray(3) { c2[it] + t2 * v[it] }
        val mid = DoubleArray(3) { 0.5 * (p1[it] + p2[it]) }
        return Triangulated(mid, parallax, norm(sub(p1, p2)))
    }

    /**
     * Least-squares plane through [points]: through their centroid, normal = eigenvector of the
     * smallest eigenvalue of their scatter matrix.
     *
     * @return null for fewer than 3 points or (near) collinear points.
     */
    fun fitPlane(points: List<DoubleArray>): Plane? {
        if (points.size < 3) return null
        val centroid = DoubleArray(3)
        for (p in points) for (i in 0..2) centroid[i] += p[i]
        for (i in 0..2) centroid[i] /= points.size.toDouble()
        val cov = DoubleArray(9)
        for (p in points) {
            val d = sub(p, centroid)
            for (i in 0..2) for (j in 0..2) cov[i * 3 + j] += d[i] * d[j]
        }
        val (values, vectors) = symmetricEigen(cov, 3)
        val order = values.indices.sortedBy { values[it] }
        val largest = values[order[2]]
        // Collinear: the two smallest spreads are both ~0, leaving the normal undetermined.
        if (largest <= 0.0 || values[order[1]] <= DEGENERATE_EIGEN_RATIO * largest) return null
        val normal = normalize(DoubleArray(3) { vectors[it * 3 + order[0]] })
        return canonicalPlane(normal, dot(normal, centroid))
    }

    /**
     * Robust plane by RANSAC over 3-point samples, scored by `|distance| ≤ inlierDistance`; the
     * best hypothesis is refit on its inliers ([fitPlane]) and the inlier set recomputed once.
     *
     * @return the plane and the indices of its inliers, or null when fewer than [minInliers] support it.
     */
    fun ransacPlane(
        points: List<DoubleArray>,
        inlierDistance: Double,
        iterations: Int = 100,
        minInliers: Int = 8,
        random: Random = Random(11),
    ): Pair<Plane, IntArray>? {
        val n = points.size
        if (n < 3) return null
        var best: Plane? = null
        var bestCount = -1
        for (it in 0 until iterations) {
            val i = random.nextInt(n)
            val j = random.nextInt(n)
            val k = random.nextInt(n)
            if (i == j || j == k || i == k) continue
            val cr = cross(sub(points[j], points[i]), sub(points[k], points[i]))
            val len = norm(cr)
            if (len < 1e-12) continue
            val normal = DoubleArray(3) { cr[it] / len }
            val plane = Plane(normal, dot(normal, points[i]))
            val count = points.count { abs(plane.distance(it)) <= inlierDistance }
            if (count > bestCount) {
                bestCount = count
                best = plane
            }
        }
        var plane = best ?: return null
        var inliers = planeInliers(plane, points, inlierDistance)
        fitPlane(inliers.map { points[it] })?.let { refit ->
            val refitInliers = planeInliers(refit, points, inlierDistance)
            if (refitInliers.size >= inliers.size) {
                plane = refit
                inliers = refitInliers
            }
        }
        if (inliers.size < minInliers) return null
        return canonicalPlane(plane.normal, plane.offset) to inliers
    }

    // ---------------------------------------------------------------------------------------------
    // Homography
    // ---------------------------------------------------------------------------------------------

    /**
     * Row-major 3×3 homography taking pixels of camera A to pixels of camera B for points on the
     * world [plane] (the tile's predicted appearance from pose B).
     *
     * With `R_AB = R_B R_Aᵀ`, `t_AB = R_B (c_A − c_B)` (so `x_B = R_AB x_A + t_AB`) and the plane in
     * camera A as `n_A · x_A = d_A` (`n_A = R_A n`, `d_A = offset − n · c_A`):
     * `H = K_B (R_AB + t_AB n_Aᵀ / d_A) K_A⁻¹`. (This is the textbook `R − t nᵀ/d` with the plane
     * written `n·x + d = 0`; flipping the sign of both `n` and `offset` gives the same `H`.)
     * Normalised so `H[8] = 1` when `|H[8]|` is not tiny. With `c_A = c_B` it reduces to the pure
     * rotation `K_B R_AB K_A⁻¹`, independent of the plane.
     *
     * @param kA intrinsics `[fx, fy, cx, cy]` of camera A.
     * @param kB intrinsics `[fx, fy, cx, cy]` of camera B.
     * @return null when the plane passes through camera A's centre (`|d_A|` tiny).
     */
    fun planeHomography(
        rotationA: DoubleArray,
        centreA: DoubleArray,
        rotationB: DoubleArray,
        centreB: DoubleArray,
        plane: Plane,
        kA: FloatArray,
        kB: FloatArray,
    ): DoubleArray? {
        val dA = plane.offset - dot(plane.normal, centreA)
        if (abs(dA) < MIN_PLANE_DISTANCE) return null
        val nA = mulVec(rotationA, plane.normal)
        val rAB = multiply3(rotationB, transpose3(rotationA))
        val tAB = mulVec(rotationB, sub(centreA, centreB))
        val m = DoubleArray(9) { rAB[it] + tAB[it / 3] * nA[it % 3] / dA }
        val h = multiply3(multiply3(intrinsicsMatrix(kB), m), inverseIntrinsicsMatrix(kA))
        if (abs(h[8]) > 1e-12) {
            val s = h[8]
            for (i in 0..8) h[i] /= s
        }
        return h
    }

    /** Apply row-major homography [h] to pixel `(u, v)`; null when the point maps to infinity. */
    fun applyHomography(h: DoubleArray, u: Double, v: Double): DoubleArray? {
        val w = h[6] * u + h[7] * v + h[8]
        if (abs(w) < 1e-12) return null
        return doubleArrayOf((h[0] * u + h[1] * v + h[2]) / w, (h[3] * u + h[4] * v + h[5]) / w)
    }

    // ---------------------------------------------------------------------------------------------
    // PnP (prior-seeded)
    // ---------------------------------------------------------------------------------------------

    /**
     * Levenberg–Marquardt refinement of a camera pose on reprojection error, seeded with a prior.
     *
     * Six parameters: a rotation increment `ω` left-multiplied (`R ← exp([ω]×) R`, i.e. in the camera
     * frame) and the centre `c`. Analytic Jacobian; 6×6 damped normal equations solved by Gaussian
     * elimination. Points behind the initial camera are ignored; a step that puts a used point
     * behind the camera is rejected.
     *
     * @param points world points.
     * @param pixels observed pixels `(u, v)`, same order as [points].
     * @param k intrinsics `[fx, fy, cx, cy]`.
     * @param huberPx Huber threshold on the 2-D residual, px (infinite = plain least squares).
     * @return the pose, with `inliers` = the points used (in front of the camera) and `rmsPx` over
     *   them; null with fewer than 3 usable points or a singular system.
     */
    fun refinePose(
        points: List<DoubleArray>,
        pixels: List<FloatArray>,
        k: FloatArray,
        initialRotation: DoubleArray,
        initialCentre: DoubleArray,
        iterations: Int = 10,
        huberPx: Double = Double.POSITIVE_INFINITY,
    ): PoseFit? {
        if (points.size != pixels.size) return null
        val used = points.indices.filter { toCamera(initialRotation, initialCentre, points[it])[2] > MIN_DEPTH }
        if (used.size < 3) return null
        val (r, c) = levenbergMarquardt(points, pixels, used, k, initialRotation, initialCentre, iterations, huberPx)
            ?: return null
        val res = residuals(points, pixels, k, r, c)
        val front = used.filter { res[it].isFinite() }
        if (front.size < 3) return null
        return PoseFit(r, c, front.toIntArray(), rms(res, front))
    }

    /**
     * Robust pose from 2-D–3-D matches given a prior pose (gyro attitude + last position), for when
     * no minimal solver is available.
     *
     * Three gating rounds: residuals under the current pose, keep those within
     * `max(8 · inlierPx, 3 · median)` (generous enough to survive a prior that is several degrees
     * off), Huber-refine on them. Then the final inliers are those within [inlierPx]; the pose is
     * refined on them by plain least squares and the inliers recomputed.
     *
     * @return null when fewer than [minInliers] matches end up within [inlierPx].
     */
    fun ransacPose(
        points: List<DoubleArray>,
        pixels: List<FloatArray>,
        k: FloatArray,
        initialRotation: DoubleArray,
        initialCentre: DoubleArray,
        inlierPx: Double = 3.0,
        minInliers: Int = 10,
    ): PoseFit? {
        if (points.size != pixels.size || points.size < max(3, minInliers)) return null
        var r = initialRotation.copyOf()
        var c = initialCentre.copyOf()
        repeat(GATING_ROUNDS) {
            val res = residuals(points, pixels, k, r, c)
            val finite = res.filter { it.isFinite() }
            if (finite.size < 3) return null
            val gate = max(8.0 * inlierPx, 3.0 * median(finite))
            val sel = points.indices.filter { res[it] <= gate }
            if (sel.size < 3) return null
            val step = levenbergMarquardt(points, pixels, sel, k, r, c, 10, 2.0 * inlierPx) ?: return null
            r = step.first
            c = step.second
        }
        var res = residuals(points, pixels, k, r, c)
        var inliers = points.indices.filter { res[it] <= inlierPx }
        if (inliers.size < max(3, minInliers)) return null
        levenbergMarquardt(points, pixels, inliers, k, r, c, 10, Double.POSITIVE_INFINITY)?.let { (r2, c2) ->
            val res2 = residuals(points, pixels, k, r2, c2)
            val inl2 = points.indices.filter { res2[it] <= inlierPx }
            if (inl2.size >= inliers.size) {
                r = r2; c = c2; res = res2; inliers = inl2
            }
        }
        if (inliers.size < minInliers) return null
        return PoseFit(r, c, inliers.toIntArray(), rms(res, inliers))
    }

    // ---------------------------------------------------------------------------------------------
    // Rotation helpers
    // ---------------------------------------------------------------------------------------------

    /** Row-major 3×3 product `a · b`. */
    fun multiply3(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) {
        val i = it / 3
        val j = it % 3
        a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
    }

    /** Row-major 3×3 transpose. */
    fun transpose3(a: DoubleArray): DoubleArray = DoubleArray(9) { a[(it % 3) * 3 + it / 3] }

    /** Angle of the relative rotation `a · bᵀ`, radians in `[0, π]` (accurate for small angles). */
    fun rotationAngleRad(a: DoubleArray, b: DoubleArray): Double {
        val m = multiply3(a, transpose3(b))
        val sx = m[7] - m[5]
        val sy = m[2] - m[6]
        val sz = m[3] - m[1]
        val sinTheta = 0.5 * sqrt(sx * sx + sy * sy + sz * sz)
        val cosTheta = 0.5 * (m[0] + m[4] + m[8] - 1.0)
        return atan2(sinTheta, cosTheta)
    }

    /** Row-major rotation of unit quaternion `[x, y, z, w]` (normalised here). */
    fun quaternionToMatrix(q: DoubleArray): DoubleArray {
        val len = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (len == 0.0) return identity3()
        val x = q[0] / len
        val y = q[1] / len
        val z = q[2] / len
        val w = q[3] / len
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
            2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
            2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
        )
    }

    /** Unit quaternion `[x, y, z, w]` (with `w ≥ 0`) of a row-major rotation matrix (Shepperd). */
    fun matrixToQuaternion(r: DoubleArray): DoubleArray {
        val tr = r[0] + r[4] + r[8]
        val q = when {
            tr > 0 -> {
                val s = sqrt(tr + 1.0) * 2
                doubleArrayOf((r[7] - r[5]) / s, (r[2] - r[6]) / s, (r[3] - r[1]) / s, 0.25 * s)
            }
            r[0] > r[4] && r[0] > r[8] -> {
                val s = sqrt(1.0 + r[0] - r[4] - r[8]) * 2
                doubleArrayOf(0.25 * s, (r[1] + r[3]) / s, (r[2] + r[6]) / s, (r[7] - r[5]) / s)
            }
            r[4] > r[8] -> {
                val s = sqrt(1.0 + r[4] - r[0] - r[8]) * 2
                doubleArrayOf((r[1] + r[3]) / s, 0.25 * s, (r[5] + r[7]) / s, (r[2] - r[6]) / s)
            }
            else -> {
                val s = sqrt(1.0 + r[8] - r[0] - r[4]) * 2
                doubleArrayOf((r[2] + r[6]) / s, (r[5] + r[7]) / s, 0.25 * s, (r[3] - r[1]) / s)
            }
        }
        if (q[3] < 0) for (i in 0..3) q[i] = -q[i]
        val len = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        for (i in 0..3) q[i] /= len
        return q
    }

    /** Row-major rotation `exp([ω]×)` of rotation vector [omega] (axis × angle, radians; Rodrigues). */
    fun axisAngleToMatrix(omega: DoubleArray): DoubleArray {
        val theta = norm(omega)
        if (theta < 1e-12) {
            // First order: I + [ω]×
            return doubleArrayOf(
                1.0, -omega[2], omega[1],
                omega[2], 1.0, -omega[0],
                -omega[1], omega[0], 1.0,
            )
        }
        val kx = omega[0] / theta
        val ky = omega[1] / theta
        val kz = omega[2] / theta
        val s = kotlin.math.sin(theta)
        val c = kotlin.math.cos(theta)
        val v = 1 - c
        return doubleArrayOf(
            c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s,
            ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s,
            kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private const val MIN_DEPTH = 1e-9
    private const val MIN_PARALLAX_RAD = 1e-4
    private const val MIN_PLANE_DISTANCE = 1e-9
    private const val DEGENERATE_EIGEN_RATIO = 1e-12

    /** sin(2°): 2-point rotation samples closer to parallel than this are skipped. */
    private val MIN_SAMPLE_SIN = kotlin.math.sin(Math.toRadians(2.0))

    /** Bearings closer to parallel than this (sine of the angle) count as collinear. */
    private const val COLLINEAR_SIN = 1e-9

    private const val GATING_ROUNDS = 3

    private fun identity3() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun norm(a: DoubleArray) = sqrt(dot(a, a))

    private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun normalize(a: DoubleArray): DoubleArray {
        val n = norm(a)
        return if (n == 0.0) a.copyOf() else doubleArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }

    /** Angle between two vectors, radians (atan2 form: accurate near 0 and π). */
    private fun angleBetween(a: DoubleArray, b: DoubleArray): Double = atan2(norm(cross(a, b)), dot(a, b))

    private fun mulVec(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    private fun mulTransposeVec(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[3] * v[1] + m[6] * v[2],
        m[1] * v[0] + m[4] * v[1] + m[7] * v[2],
        m[2] * v[0] + m[5] * v[1] + m[8] * v[2],
    )

    private fun toCamera(r: DoubleArray, c: DoubleArray, p: DoubleArray) = mulVec(r, sub(p, c))

    private fun intrinsicsMatrix(k: FloatArray) = doubleArrayOf(
        k[0].toDouble(), 0.0, k[2].toDouble(),
        0.0, k[1].toDouble(), k[3].toDouble(),
        0.0, 0.0, 1.0,
    )

    private fun inverseIntrinsicsMatrix(k: FloatArray): DoubleArray {
        val fx = k[0].toDouble()
        val fy = k[1].toDouble()
        return doubleArrayOf(1 / fx, 0.0, -k[2] / fx, 0.0, 1 / fy, -k[3] / fy, 0.0, 0.0, 1.0)
    }

    private fun argMax(v: DoubleArray): Int {
        var best = 0
        for (i in 1 until v.size) if (v[i] > v[best]) best = i
        return best
    }

    /** True when every (non-zero-weight) vector is parallel or anti-parallel to the first one. */
    private fun isCollinear(v: List<DoubleArray>, weights: DoubleArray?): Boolean {
        var ref: DoubleArray? = null
        for (k in v.indices) {
            if (weights != null && weights[k] == 0.0) continue
            val u = normalize(v[k])
            if (norm(u) == 0.0) continue
            val r = ref
            if (r == null) {
                ref = u
            } else if (norm(cross(r, u)) > COLLINEAR_SIN) {
                return false
            }
        }
        return true
    }

    private fun countRotationInliers(r: DoubleArray, a: List<DoubleArray>, b: List<DoubleArray>, gate: Double): Int {
        var count = 0
        for (k in a.indices) if (angleBetween(b[k], mulVec(r, a[k])) <= gate) count++
        return count
    }

    private fun rotationInliers(r: DoubleArray, a: List<DoubleArray>, b: List<DoubleArray>, gate: Double): IntArray =
        a.indices.filter { angleBetween(b[it], mulVec(r, a[it])) <= gate }.toIntArray()

    private fun planeInliers(plane: Plane, points: List<DoubleArray>, gate: Double): IntArray =
        points.indices.filter { abs(plane.distance(points[it])) <= gate }.toIntArray()

    private fun canonicalPlane(normal: DoubleArray, offset: Double): Plane =
        if (offset < 0) Plane(DoubleArray(3) { -normal[it] }, -offset) else Plane(normal.copyOf(), offset)

    /**
     * Eigen-decomposition of a symmetric `n×n` row-major matrix by cyclic Jacobi rotations.
     *
     * @return eigenvalues (unsorted) and the row-major matrix whose column `j` is the unit
     *   eigenvector of eigenvalue `j`.
     */
    private fun symmetricEigen(input: DoubleArray, n: Int): Pair<DoubleArray, DoubleArray> {
        val a = input.copyOf()
        val v = DoubleArray(n * n).also { for (i in 0 until n) it[i * n + i] = 1.0 }
        var scale = 0.0
        for (x in a) scale += x * x
        if (scale == 0.0) return DoubleArray(n) to v
        for (sweep in 0 until 64) {
            var off = 0.0
            for (p in 0 until n) for (q in p + 1 until n) off += a[p * n + q] * a[p * n + q]
            if (off <= 1e-30 * scale) break
            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    val apq = a[p * n + q]
                    if (apq == 0.0) continue
                    val theta = (a[q * n + q] - a[p * n + p]) / (2.0 * apq)
                    val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1.0))
                    val c = 1.0 / sqrt(t * t + 1.0)
                    val s = t * c
                    // A ← Jᵀ A J with J = I except J[p][p] = J[q][q] = c, J[p][q] = s, J[q][p] = −s.
                    for (kk in 0 until n) {
                        val akp = a[kk * n + p]
                        val akq = a[kk * n + q]
                        a[kk * n + p] = c * akp - s * akq
                        a[kk * n + q] = s * akp + c * akq
                    }
                    for (kk in 0 until n) {
                        val apk = a[p * n + kk]
                        val aqk = a[q * n + kk]
                        a[p * n + kk] = c * apk - s * aqk
                        a[q * n + kk] = s * apk + c * aqk
                    }
                    for (kk in 0 until n) {
                        val vkp = v[kk * n + p]
                        val vkq = v[kk * n + q]
                        v[kk * n + p] = c * vkp - s * vkq
                        v[kk * n + q] = s * vkp + c * vkq
                    }
                }
            }
        }
        return DoubleArray(n) { a[it * n + it] } to v
    }

    /** Solve the dense `n×n` row-major system `a · x = b` by Gaussian elimination with partial pivoting. */
    private fun solveLinear(aIn: DoubleArray, bIn: DoubleArray, n: Int): DoubleArray? {
        val a = aIn.copyOf()
        val b = bIn.copyOf()
        var maxAbs = 0.0
        for (x in a) maxAbs = max(maxAbs, abs(x))
        if (maxAbs == 0.0) return null
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(a[r * n + col]) > abs(a[piv * n + col])) piv = r
            if (abs(a[piv * n + col]) <= 1e-14 * maxAbs) return null
            if (piv != col) {
                for (j in 0 until n) {
                    val t = a[col * n + j]; a[col * n + j] = a[piv * n + j]; a[piv * n + j] = t
                }
                val t = b[col]; b[col] = b[piv]; b[piv] = t
            }
            for (r in col + 1 until n) {
                val f = a[r * n + col] / a[col * n + col]
                if (f == 0.0) continue
                for (j in col until n) a[r * n + j] -= f * a[col * n + j]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (j in r + 1 until n) s -= a[r * n + j] * x[j]
            x[r] = s / a[r * n + r]
        }
        return x
    }

    /** 2-D reprojection residual per point, px; +∞ for points not in front of the camera. */
    private fun residuals(
        points: List<DoubleArray>,
        pixels: List<FloatArray>,
        k: FloatArray,
        r: DoubleArray,
        c: DoubleArray,
    ): DoubleArray = DoubleArray(points.size) { i ->
        val x = toCamera(r, c, points[i])
        if (x[2] <= MIN_DEPTH) {
            Double.POSITIVE_INFINITY
        } else {
            val du = k[0] * x[0] / x[2] + k[2] - pixels[i][0]
            val dv = k[1] * x[1] / x[2] + k[3] - pixels[i][1]
            sqrt(du * du + dv * dv)
        }
    }

    private fun rms(res: DoubleArray, idx: List<Int>): Double {
        if (idx.isEmpty()) return 0.0
        var s = 0.0
        for (i in idx) s += res[i] * res[i]
        return sqrt(s / idx.size)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val m = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[m] else 0.5 * (sorted[m - 1] + sorted[m])
    }

    /** Huber cost of a residual of norm [e] with threshold [delta]. */
    private fun huberCost(e: Double, delta: Double): Double =
        if (e <= delta) 0.5 * e * e else delta * (e - 0.5 * delta)

    /** Robust cost of the pose over [idx]; +∞ when any of them is not in front of the camera. */
    private fun poseCost(
        points: List<DoubleArray>,
        pixels: List<FloatArray>,
        idx: List<Int>,
        k: FloatArray,
        r: DoubleArray,
        c: DoubleArray,
        huber: Double,
    ): Double {
        var cost = 0.0
        for (i in idx) {
            val x = toCamera(r, c, points[i])
            if (x[2] <= MIN_DEPTH) return Double.POSITIVE_INFINITY
            val du = k[0] * x[0] / x[2] + k[2] - pixels[i][0]
            val dv = k[1] * x[1] / x[2] + k[3] - pixels[i][1]
            cost += huberCost(sqrt(du * du + dv * dv), huber)
        }
        return cost
    }

    /**
     * Levenberg–Marquardt (IRLS-Huber) on the reprojection error of points [idx].
     *
     * @return the refined `(rotation, centre)`, or null when the normal equations are singular on the
     *   first iteration or a point is behind the initial camera.
     */
    private fun levenbergMarquardt(
        points: List<DoubleArray>,
        pixels: List<FloatArray>,
        idx: List<Int>,
        k: FloatArray,
        r0: DoubleArray,
        c0: DoubleArray,
        iterations: Int,
        huber: Double,
    ): Pair<DoubleArray, DoubleArray>? {
        val fx = k[0].toDouble()
        val fy = k[1].toDouble()
        var r = r0.copyOf()
        var c = c0.copyOf()
        var cost = poseCost(points, pixels, idx, k, r, c, huber)
        if (!cost.isFinite()) return null
        var lambda = 1e-3
        for (iter in 0 until iterations) {
            val h = DoubleArray(36)
            val g = DoubleArray(6)
            val jRow = DoubleArray(6)
            for (i in idx) {
                val x = toCamera(r, c, points[i])
                val z = x[2]
                val iz = 1.0 / z
                val ru = fx * x[0] * iz + k[2] - pixels[i][0]
                val rv = fy * x[1] * iz + k[3] - pixels[i][1]
                val e = sqrt(ru * ru + rv * rv)
                val w = if (e <= huber) 1.0 else huber / e
                // d(proj)/dx: u-row (fx/z, 0, −fx·x/z²), v-row (0, fy/z, −fy·y/z²).
                val pu0 = fx * iz
                val pu2 = -fx * x[0] * iz * iz
                val pv1 = fy * iz
                val pv2 = -fy * x[1] * iz * iz
                // dx/dω = −[x]×, dx/dc = −R.
                for (row in 0..1) {
                    val p0 = if (row == 0) pu0 else 0.0
                    val p1 = if (row == 0) 0.0 else pv1
                    val p2 = if (row == 0) pu2 else pv2
                    // −[x]× = [[0, z, −y], [−z, 0, x], [y, −x, 0]]
                    jRow[0] = p1 * (-x[2]) + p2 * x[1]
                    jRow[1] = p0 * x[2] + p2 * (-x[0])
                    jRow[2] = p0 * (-x[1]) + p1 * x[0]
                    for (col in 0..2) jRow[3 + col] = -(p0 * r[col] + p1 * r[3 + col] + p2 * r[6 + col])
                    val res = if (row == 0) ru else rv
                    for (a in 0..5) {
                        g[a] += w * jRow[a] * res
                        for (b in a..5) h[a * 6 + b] += w * jRow[a] * jRow[b]
                    }
                }
            }
            for (a in 0..5) for (b in 0 until a) h[a * 6 + b] = h[b * 6 + a]
            var improved = false
            var tries = 0
            while (tries++ < 8) {
                val damped = h.copyOf()
                for (a in 0..5) damped[a * 6 + a] += lambda * max(h[a * 6 + a], 1e-12)
                val delta = solveLinear(damped, DoubleArray(6) { -g[it] }, 6)
                    ?: return if (iter == 0) null else r to c
                val rNew = multiply3(axisAngleToMatrix(doubleArrayOf(delta[0], delta[1], delta[2])), r)
                val cNew = doubleArrayOf(c[0] + delta[3], c[1] + delta[4], c[2] + delta[5])
                val newCost = poseCost(points, pixels, idx, k, rNew, cNew, huber)
                if (newCost < cost) {
                    val gain = cost - newCost
                    r = orthonormalize(rNew)
                    c = cNew
                    cost = newCost
                    lambda = max(lambda / 10.0, 1e-9)
                    improved = true
                    if (gain <= 1e-12 * max(cost, 1e-12)) return r to c
                    break
                }
                lambda = min(lambda * 10.0, 1e9)
            }
            if (!improved) break
        }
        return r to c
    }

    /** Snap a near-rotation back onto SO(3) (via its quaternion) to stop drift over many updates. */
    private fun orthonormalize(r: DoubleArray): DoubleArray = quaternionToMatrix(matrixToQuaternion(r))
}

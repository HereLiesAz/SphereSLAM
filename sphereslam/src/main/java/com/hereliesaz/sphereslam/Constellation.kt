package com.hereliesaz.sphereslam

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Constellation matching: a target of a few isolated marks (five X's and an O on black paper) matched
 * by the arrangement of the marks, not the look of each one.
 *
 * KPM describes and matches each feature on its own, so identical marks are ambiguous to it and a
 * smooth one (an O) yields almost no features. Here each mark is one blob with a centre
 * ([extractMarks]); the reference's centres are matched to the frame's by a similarity hypothesised
 * from every pair of points and scored by how many other centres it lands on ([match]); the pose comes
 * from the homography of the matched centres ([pose]). Pure Kotlin, no OpenCV.
 */
@ExperimentalSphereSlamApi
object Constellation {

    /** One mark: its centre (pixels of the image it was found in) and area (pixels). */
    class Mark(val x: Float, val y: Float, val area: Int)

    /** Fewest marks a constellation needs: a homography takes four. */
    const val MIN_MARKS = 4

    /**
     * Fewest matched points a pose is taken from. Four fit a homography exactly (zero error whatever
     * the pairing), so a fifth is what lets the reprojection error reject a wrong one.
     */
    const val MIN_POSE_POINTS = 5

    /**
     * How near (fraction of the hypothesis pair's frame length) a mapped mark must land. Tunable, not
     * measured: wide enough for moderate perspective (see ConstellationTest's tilted case).
     */
    const val MATCH_TOLERANCE = 0.12f

    /** Fraction of the reference's marks a match must pair. Tunable, not measured. */
    const val MIN_MATCHED_FRACTION = 0.6f

    /** Nearest neighbours each point hypothesises with once the search is no longer exhaustive. */
    const val NEIGHBOURS = 4

    /** Homography refits of a hypothesis' pairing before it is scored. */
    const val REFINE_ROUNDS = 2

    /** Up to this n·m every point pair is hypothesised; beyond, nearest neighbours only. */
    const val FULL_SEARCH_LIMIT = 96

    /** More blobs than this is texture, not a handful of marks: left to KPM. */
    const val MAX_MARKS = 24

    /** Long side (px) marks are found at; larger images are block-averaged down first. */
    const val WORK_LONG_SIDE = 320

    /** A mark differs from the surface median by at least this much luma (0–255)... Tunable, not measured. */
    const val MIN_CONTRAST = 48

    /** ...and by at least this many median absolute deviations of the surface. Tunable, not measured. */
    const val MAD_FACTOR = 6f

    /** A blob smaller than this fraction of the image is noise. Tunable, not measured. */
    const val MIN_AREA_FRACTION = 0.0002f

    /** A blob larger than this fraction of the image is not a mark. Tunable, not measured. */
    const val MAX_AREA_FRACTION = 0.08f

    /**
     * The marks of a [width] × [height] luma image: blobs of either polarity well clear of the surface
     * median, not touching the border (a cut-off mark's centre is wrong), between
     * [MIN_AREA_FRACTION] and [MAX_AREA_FRACTION] of the image. Empty unless there are
     * [MIN_MARKS]..[MAX_MARKS] of them: a busy surface is KPM's.
     */
    /** Scratch arrays [extractMarks] reuses across calls (one per thread; not thread-safe). */
    class Workspace {
        internal var img = IntArray(0)
        internal var dev = IntArray(0)
        internal var mark = BooleanArray(0)
        internal var seen = BooleanArray(0)
        internal var stack = IntArray(0)

        internal fun ensure(n: Int) {
            if (img.size >= n) return
            img = IntArray(n); dev = IntArray(n); mark = BooleanArray(n); seen = BooleanArray(n); stack = IntArray(n)
        }
    }

    fun extractMarks(luma: ByteArray, width: Int, height: Int, workspace: Workspace = Workspace()): List<Mark> {
        require(width > 0 && height > 0 && luma.size >= width * height)
        val step = max(1, (max(width, height) + WORK_LONG_SIDE - 1) / WORK_LONG_SIDE)
        val w = width / step
        val h = height / step
        if (w < 8 || h < 8) return emptyList()
        val n = w * h
        workspace.ensure(n)
        val img = workspace.img
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (dy in 0 until step) {
                val row = (y * step + dy) * width + x * step
                for (dx in 0 until step) s += luma[row + dx].toInt() and 0xFF
            }
            img[y * w + x] = s / (step * step)
        }
        val median = medianOf(img, n)
        val dev = workspace.dev
        for (i in 0 until n) dev[i] = abs(img[i] - median)
        val mad = medianOf(dev, n)
        val threshold = max(MIN_CONTRAST.toFloat(), MAD_FACTOR * mad)
        val mark = workspace.mark
        for (i in 0 until n) mark[i] = dev[i] > threshold

        val minArea = max(4, (MIN_AREA_FRACTION * w * h).toInt())
        val maxArea = (MAX_AREA_FRACTION * w * h).toInt()
        val seen = workspace.seen
        seen.fill(false, 0, n)
        val stack = workspace.stack
        val out = ArrayList<Mark>()
        for (start in 0 until n) {
            if (!mark[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var area = 0
            var sx = 0L
            var sy = 0L
            var border = false
            while (top > 0) {
                val p = stack[--top]
                val px = p % w
                val py = p / w
                area++
                sx += px
                sy += py
                if (px == 0 || py == 0 || px == w - 1 || py == h - 1) border = true
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx
                    val ny = py + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (mark[q] && !seen[q]) {
                        seen[q] = true
                        stack[top++] = q
                    }
                }
            }
            if (border || area < minArea || area > maxArea) continue
            // Back to the input image's pixels (centre of the averaged block).
            out += Mark(
                ((sx.toFloat() / area) + 0.5f) * step - 0.5f,
                ((sy.toFloat() / area) + 0.5f) * step - 0.5f,
                area * step * step,
            )
            if (out.size > MAX_MARKS) return emptyList()
        }
        return if (out.size >= MIN_MARKS) out else emptyList()
    }

    /** A match: frame point [frame] index ↔ reference point [reference] index. */
    class Pairing(val reference: IntArray, val frame: IntArray)

    /**
     * Matches [reference] centres to [frame] centres (each as x, y pairs, both image-convention: y
     * down; the reference's in its own pixels). Every ordered pair of reference points with every
     * ordered pair of frame points fixes a similarity (scale, turn, shift; never a mirror, which no
     * view of a page produces); each is scored by the reference points it lands within [tolerance] (a
     * fraction of the frame span of the pair) of an unclaimed frame point. Moderate perspective bends
     * the arrangement less than the tolerance; [pose] then fits the true homography.
     *
     * @return null unless the best hypothesis pairs at least [MIN_MARKS] points and
     *   [minFraction] of the reference, and no other hypothesis pairs as many points differently
     *   (a symmetric arrangement is ambiguous).
     */
    fun match(
        reference: List<FloatArray>,
        frame: List<FloatArray>,
        tolerance: Float = MATCH_TOLERANCE,
        minFraction: Float = MIN_MATCHED_FRACTION,
    ): Pairing? {
        val n = reference.size
        val m = frame.size
        if (n < MIN_MARKS || m < MIN_MARKS) return null
        // Hypothesis pairs: every pair while the sets are small, else each point with its
        // [NEIGHBOURS] nearest (an arrangement keeps its local neighbourhoods), bounding the work at
        // O((n·k)·(m·k)·n·m) instead of O(n²·m²·n·m).
        val refPairs = hypothesisPairs(reference, full = n * m <= FULL_SEARCH_LIMIT)
        val framePairs = hypothesisPairs(frame, full = n * m <= FULL_SEARCH_LIMIT)
        var bestRef = IntArray(0)
        var bestFrame = IntArray(0)
        var bestCount = 0
        var tie = false
        val assigned = IntArray(n)
        val claimed = BooleanArray(m)
        val frameSpan = span(frame)
        for (rp in refPairs) {
            val i = rp ushr 16
            val j = rp and 0xFFFF
            val rdx = reference[j][0] - reference[i][0]
            val rdy = reference[j][1] - reference[i][1]
            val rLen = hypot(rdx, rdy)
            if (rLen <= 0f) continue
            for (fp in framePairs) {
                val a = fp ushr 16
                val b = fp and 0xFFFF
                val fdx = frame[b][0] - frame[a][0]
                val fdy = frame[b][1] - frame[a][1]
                val fLen = hypot(fdx, fdy)
                if (fLen <= 0f) continue
                // Similarity taking reference i→a, j→b (never a mirror: no view of a page makes one).
                val s = fLen / rLen
                val cos = (rdx * fdx + rdy * fdy) / (rLen * fLen)
                val sin = (rdx * fdy - rdy * fdx) / (rLen * fLen)
                val tol2 = (tolerance * fLen) * (tolerance * fLen)
                claimed.fill(false)
                var count = 0
                for (k in 0 until n) {
                    val px = reference[k][0] - reference[i][0]
                    val py = reference[k][1] - reference[i][1]
                    val qx = frame[a][0] + s * (cos * px - sin * py)
                    val qy = frame[a][1] + s * (sin * px + cos * py)
                    var nearest = -1
                    var nearest2 = tol2
                    for (f in 0 until m) {
                        if (claimed[f]) continue
                        val dx = frame[f][0] - qx
                        val dy = frame[f][1] - qy
                        val d2 = dx * dx + dy * dy
                        if (d2 <= nearest2) {
                            nearest2 = d2
                            nearest = f
                        }
                    }
                    assigned[k] = nearest
                    if (nearest >= 0) {
                        claimed[nearest] = true
                        count++
                    }
                }
                // Perspective bends an arrangement away from any similarity: refit the pairing with a
                // homography and re-pair under it before scoring.
                if (count >= MIN_MARKS) count = refine(reference, frame, assigned, claimed, tolerance * frameSpan)
                if (count < bestCount || count == 0) continue
                if (count > bestCount) {
                    bestCount = count
                    bestRef = IntArray(count)
                    bestFrame = IntArray(count)
                    var c = 0
                    for (k in 0 until n) if (assigned[k] >= 0) { bestRef[c] = k; bestFrame[c] = assigned[k]; c++ }
                    tie = false
                } else if (!tie && !sameAssignment(bestRef, bestFrame, assigned)) {
                    tie = true
                }
            }
        }
        if (bestCount == 0 || tie) return null
        if (bestCount < MIN_MARKS || bestCount < minFraction * n) return null
        return Pairing(bestRef, bestFrame)
    }

    // Re-pairs [assigned] under the homography of its current pairs ([REFINE_ROUNDS] times, a match
    // within [tolerance] px); returns the count. Keeps the similarity's pairing when a fit fails.
    private fun refine(
        reference: List<FloatArray>,
        frame: List<FloatArray>,
        assigned: IntArray,
        claimed: BooleanArray,
        tolerance: Float,
    ): Int {
        var count = assigned.count { it >= 0 }
        repeat(REFINE_ROUNDS) {
            val src = ArrayList<FloatArray>()
            val dst = ArrayList<FloatArray>()
            for (k in assigned.indices) if (assigned[k] >= 0) { src += reference[k]; dst += frame[assigned[k]] }
            val h = homography(src, dst) ?: return count
            val trial = IntArray(assigned.size) { -1 }
            claimed.fill(false)
            var c = 0
            val tol2 = tolerance * tolerance
            for (k in reference.indices) {
                val x = reference[k][0].toDouble(); val y = reference[k][1].toDouble()
                val w = h[6] * x + h[7] * y + h[8]
                if (w <= 1e-9) continue
                val qx = ((h[0] * x + h[1] * y + h[2]) / w).toFloat()
                val qy = ((h[3] * x + h[4] * y + h[5]) / w).toFloat()
                var nearest = -1
                var nearest2 = tol2
                for (f in frame.indices) {
                    if (claimed[f]) continue
                    val dx = frame[f][0] - qx; val dy = frame[f][1] - qy
                    val d2 = dx * dx + dy * dy
                    if (d2 <= nearest2) { nearest2 = d2; nearest = f }
                }
                trial[k] = nearest
                if (nearest >= 0) { claimed[nearest] = true; c++ }
            }
            if (c < count) return count
            trial.copyInto(assigned)
            count = c
        }
        return count
    }

    private fun span(points: List<FloatArray>): Float {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in points) { minX = min(minX, p[0]); maxX = max(maxX, p[0]); minY = min(minY, p[1]); maxY = max(maxY, p[1]) }
        return hypot(maxX - minX, maxY - minY)
    }

    // Ordered pairs (i shl 16 or j) to hypothesise from: all of them, or each point with its nearest.
    private fun hypothesisPairs(points: List<FloatArray>, full: Boolean): IntArray {
        val n = points.size
        val out = ArrayList<Int>()
        for (i in 0 until n) {
            val others = (0 until n).filter { it != i }
            val chosen = if (full) {
                others
            } else {
                others.sortedBy { j -> hypot(points[j][0] - points[i][0], points[j][1] - points[i][1]) }.take(NEIGHBOURS)
            }
            for (j in chosen) out += (i shl 16) or j
        }
        return out.toIntArray()
    }

    private fun sameAssignment(ref: IntArray, frame: IntArray, assigned: IntArray): Boolean {
        var count = 0
        for (k in assigned.indices) if (assigned[k] >= 0) count++
        if (count != ref.size) return false
        for (c in ref.indices) if (assigned[ref[c]] != frame[c]) return false
        return true
    }

    /** A camera-from-page pose and its fit. */
    class Pose(
        /** Row-major 3×4, KPM convention (page millimetres, image camera: x right, y down, z forward). */
        val cameraFromPage3x4: FloatArray,
        /** Mean squared reprojection error, pixels². */
        val meanSquaredErrorPx: Float,
    )

    /**
     * The camera-from-page pose of a planar target from ≥ 4 matched points: page (x, y) millimetres
     * on `z = 0` ↔ image pixels, intrinsics `k = (fx, fy, cx, cy)`. Homography by least squares
     * (h₃₃ = 1, coordinates normalised), then the planar decomposition `K⁻¹H = λ[r₁ r₂ t]` with the
     * rotation re-orthonormalised. Null when degenerate or behind the camera.
     */
    fun pose(page: List<FloatArray>, image: List<FloatArray>, k: FloatArray): Pose? {
        val n = page.size
        if (n < MIN_MARKS || image.size != n) return null
        val h = homography(page, image) ?: return null
        val fx = k[0].toDouble(); val fy = k[1].toDouble(); val cx = k[2].toDouble(); val cy = k[3].toDouble()
        // B = K⁻¹ H, by columns.
        fun col(c: Int) = doubleArrayOf((h[c] - cx * h[6 + c]) / fx, (h[3 + c] - cy * h[6 + c]) / fy, h[6 + c])
        val b1 = col(0); val b2 = col(1); val b3 = col(2)
        var lambda = 2.0 / (norm(b1) + norm(b2))
        if (lambda * b3[2] < 0) lambda = -lambda // the page in front of the camera
        val r1 = normalize(DoubleArray(3) { b1[it] * lambda })
        val r2raw = DoubleArray(3) { b2[it] * lambda }
        val d = dot(r1, r2raw)
        val r2 = normalize(DoubleArray(3) { r2raw[it] - d * r1[it] })
        val r3 = cross(r1, r2)
        val t = DoubleArray(3) { b3[it] * lambda }
        if (t[2] <= 0.0) return null
        val m = FloatArray(12)
        for (row in 0..2) {
            m[row * 4] = r1[row].toFloat()
            m[row * 4 + 1] = r2[row].toFloat()
            m[row * 4 + 2] = r3[row].toFloat()
            m[row * 4 + 3] = t[row].toFloat()
        }
        var sum = 0.0
        for (i in 0 until n) {
            val x = page[i][0].toDouble(); val y = page[i][1].toDouble()
            val zc = r1[2] * x + r2[2] * y + t[2]
            if (zc <= 0.0) return null
            val u = fx * (r1[0] * x + r2[0] * y + t[0]) / zc + cx
            val v = fy * (r1[1] * x + r2[1] * y + t[1]) / zc + cy
            val du = u - image[i][0]; val dv = v - image[i][1]
            sum += du * du + dv * dv
        }
        return Pose(m, (sum / n).toFloat())
    }

    /** Row-major 3×3 homography taking [src] to [dst] (≥ 4 points), or null when degenerate. */
    internal fun homography(src: List<FloatArray>, dst: List<FloatArray>): DoubleArray? {
        val (ts, s) = normalizer(src)
        val (td, d) = normalizer(dst)
        // A h = b with h = (h0..h7), h8 = 1: normal equations AᵀA h = Aᵀb.
        val ata = Array(8) { DoubleArray(8) }
        val atb = DoubleArray(8)
        for (i in s.indices) {
            val x = s[i][0]; val y = s[i][1]; val u = d[i][0]; val v = d[i][1]
            val r1 = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y)
            val r2 = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y)
            for (p in 0..7) {
                for (q in 0..7) ata[p][q] += r1[p] * r1[q] + r2[p] * r2[q]
                atb[p] += r1[p] * u + r2[p] * v
            }
        }
        val hn = solve(ata, atb) ?: return null
        val hNorm = doubleArrayOf(hn[0], hn[1], hn[2], hn[3], hn[4], hn[5], hn[6], hn[7], 1.0)
        // H = Td⁻¹ Hn Ts
        val out = mul3(inverseSimilarity(td), mul3(hNorm, ts))
        val scale = out[8]
        if (abs(scale) < 1e-12) return null
        return DoubleArray(9) { out[it] / scale }
    }

    // Hartley normalisation: centroid to origin, mean distance √2.
    private fun normalizer(points: List<FloatArray>): Pair<DoubleArray, List<DoubleArray>> {
        val mx = points.sumOf { it[0].toDouble() } / points.size
        val my = points.sumOf { it[1].toDouble() } / points.size
        val mean = points.sumOf { hypot(it[0] - mx, it[1] - my) } / points.size
        val s = if (mean > 1e-12) sqrt(2.0) / mean else 1.0
        val t = doubleArrayOf(s, 0.0, -s * mx, 0.0, s, -s * my, 0.0, 0.0, 1.0)
        return t to points.map { doubleArrayOf(s * (it[0] - mx), s * (it[1] - my)) }
    }

    private fun inverseSimilarity(t: DoubleArray): DoubleArray {
        val s = t[0]
        return doubleArrayOf(1 / s, 0.0, -t[2] / s, 0.0, 1 / s, -t[5] / s, 0.0, 0.0, 1.0)
    }

    private fun mul3(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    // Gaussian elimination with partial pivoting; null when singular.
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
            if (abs(m[p][c]) < 1e-12) return null
            val tmp = m[c]; m[c] = m[p]; m[p] = tmp
            for (r in 0 until n) {
                if (r == c) continue
                val f = m[r][c] / m[c][c]
                if (f == 0.0) continue
                for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    private fun medianOf(v: IntArray, count: Int): Int {
        val hist = IntArray(256)
        for (i in 0 until count) hist[min(255, max(0, v[i]))]++
        var acc = 0
        val half = (count + 1) / 2
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= half) return i
        }
        return 255
    }

    private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    private fun normalize(v: DoubleArray): DoubleArray { val l = norm(v); return DoubleArray(3) { v[it] / l } }
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun hypot(x: Double, y: Double) = sqrt(x * x + y * y)
}

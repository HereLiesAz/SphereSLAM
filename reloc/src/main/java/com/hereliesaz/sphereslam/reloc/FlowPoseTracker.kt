package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.geometry.Geometry
import org.opencv.video.Video

/**
 * Frame-to-frame planar tracking between full KPM solves (ARToolKit AR2-style): the KPM inlier
 * correspondences — frame pixel ↔ page millimetre on the `z = 0` page plane — are carried to the next
 * frame with pyramidal Lucas–Kanade (forward–backward checked), and the camera-from-page pose is
 * re-solved by iterative PnP seeded with the previous pose, then re-solved once without residual
 * outliers.
 *
 * Every solve is against the page's own reference coordinates (not chained relative motion), so pose
 * error does not accumulate frame to frame; only the tracked pixel positions can drift, and the full
 * KPM solve re-seeds them periodically. The pose is the KPM convention: row-major camera-from-page
 * 3×4 in millimetres with an image-convention camera (x right, y down, z forward), the frame
 * `SphereSlamStandaloneSession.cameraFromCanonical` converts.
 *
 * Requires the OpenCV Java native library; construction and use throw [LinkageError] without it.
 * The point-selection and acceptance rules are the pure [FlowPoseMath] (unit-tested on the JVM);
 * this class only adds the OpenCV Lucas–Kanade and PnP calls.
 */
internal class FlowPoseTracker(intrinsics: CameraIntrinsics, private val config: KpmMatchSpeedConfig) : FlowTracker {

    private val width = intrinsics.width
    private val height = intrinsics.height
    private val cameraMatrix = Mat(3, 3, CvType.CV_64F).apply {
        put(0, 0, intrinsics.fx.toDouble(), 0.0, intrinsics.cx.toDouble(), 0.0, intrinsics.fy.toDouble(), intrinsics.cy.toDouble(), 0.0, 0.0, 1.0)
    }
    private val distCoeffs = MatOfDouble()
    private var prev = Mat(height, width, CvType.CV_8UC1)
    private var cur = Mat(height, width, CvType.CV_8UC1)
    private val rvec = Mat(3, 1, CvType.CV_64F)
    private val tvec = Mat(3, 1, CvType.CV_64F)
    private val winSize = Size(21.0, 21.0)
    private val criteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 20, 0.03)

    private var imagePoints: List<Point> = emptyList()
    private var objectPoints: List<Point3> = emptyList()
    private var seededCount = 0
    private var pageNo = -1

    override val trackedPage: Int get() = if (seededCount > 0) pageNo else -1

    override fun seed(luma: ByteArray, pageNo: Int, correspondences: FloatArray, cameraFromPage3x4: FloatArray): Boolean {
        reset()
        require(luma.size >= width * height && cameraFromPage3x4.size == 12)
        if (correspondences.size / 4 < config.minFlowInliers) return false
        val rows = FlowPoseMath.usableSeedRows(correspondences, width, height)
        if (rows.size < config.minFlowInliers) return false
        val img = rows.map { i -> Point(correspondences[i * 4].toDouble(), correspondences[i * 4 + 1].toDouble()) }
        val obj = rows.map { i -> Point3(correspondences[i * 4 + 2].toDouble(), correspondences[i * 4 + 3].toDouble(), 0.0) }
        prev.put(0, 0, luma, 0, width * height)
        setPose(cameraFromPage3x4)
        imagePoints = img
        objectPoints = obj
        seededCount = img.size
        this.pageNo = pageNo
        return true
    }

    override fun track(luma: ByteArray): FlowTracker.Result? {
        if (seededCount == 0) return null
        cur.put(0, 0, luma, 0, width * height)
        val prevPts = MatOfPoint2f(*imagePoints.toTypedArray())
        val nextPts = MatOfPoint2f()
        val backPts = MatOfPoint2f()
        val status = MatOfByte()
        val backStatus = MatOfByte()
        val err = MatOfFloat()
        try {
            Video.calcOpticalFlowPyrLK(prev, cur, prevPts, nextPts, status, err, winSize, PYRAMID_LEVELS, criteria)
            Video.calcOpticalFlowPyrLK(cur, prev, nextPts, backPts, backStatus, err, winSize, PYRAMID_LEVELS, criteria)
            val next = nextPts.toArray()
            val back = backPts.toArray()
            val survivors = FlowPoseMath.forwardBackwardSurvivors(
                previousXY = xy(imagePoints),
                nextXY = xy(next.asList()),
                backXY = xy(back.asList()),
                forwardOk = status.toArray(),
                backwardOk = backStatus.toArray(),
                width = width,
                height = height,
                maxForwardBackwardPx = FB_MAX_PX,
            )
            if (!FlowPoseMath.enoughPoints(survivors.size, seededCount, config)) return lost()
            var img = survivors.map { next[it] }
            var obj = survivors.map { objectPoints[it] }

            var solved = solve(obj, img) ?: return lost()
            // One outlier-rejection pass on the reprojection residuals.
            val keep = FlowPoseMath.inlierIndices(solved.residualsSq, OUTLIER_PX)
            if (keep.size < img.size) {
                if (!FlowPoseMath.enoughPoints(keep.size, seededCount, config)) return lost()
                img = keep.map { img[it] }
                obj = keep.map { obj[it] }
                solved = solve(obj, img) ?: return lost()
            }
            val mse = FlowPoseMath.acceptedMeanSquaredError(solved.residualsSq, config) ?: return lost()

            // Accept: the current frame becomes the reference for the next LK step.
            val t = prev
            prev = cur
            cur = t
            imagePoints = img
            objectPoints = obj
            return FlowTracker.Result(currentPose(), mse, img.size)
        } finally {
            prevPts.release(); nextPts.release(); backPts.release()
            status.release(); backStatus.release(); err.release()
        }
    }

    override fun reset() {
        imagePoints = emptyList()
        objectPoints = emptyList()
        seededCount = 0
        pageNo = -1
    }

    override fun close() {
        reset()
        prev.release(); cur.release(); rvec.release(); tvec.release()
        cameraMatrix.release(); distCoeffs.release()
    }

    private class Solve(val residualsSq: DoubleArray)

    private fun xy(points: List<Point>): DoubleArray {
        val out = DoubleArray(points.size * 2)
        for ((i, p) in points.withIndex()) {
            out[i * 2] = p.x
            out[i * 2 + 1] = p.y
        }
        return out
    }

    private fun lost(): FlowTracker.Result? {
        reset()
        return null
    }

    private fun solve(obj: List<Point3>, img: List<Point>): Solve? {
        val objMat = MatOfPoint3f(*obj.toTypedArray())
        val imgMat = MatOfPoint2f(*img.toTypedArray())
        val projected = MatOfPoint2f()
        try {
            if (!Geometry.solvePnP(objMat, imgMat, cameraMatrix, distCoeffs, rvec, tvec, true, Geometry.SOLVEPNP_ITERATIVE)) {
                return null
            }
            if (tvec.get(2, 0)[0] <= 0.0) return null
            Geometry.projectPoints(objMat, rvec, tvec, cameraMatrix, distCoeffs, projected)
            val p = projected.toArray()
            return Solve(DoubleArray(p.size) { i ->
                val dx = p[i].x - img[i].x
                val dy = p[i].y - img[i].y
                dx * dx + dy * dy
            })
        } finally {
            objMat.release(); imgMat.release(); projected.release()
        }
    }

    private fun setPose(cameraFromPage3x4: FloatArray) {
        val r = Mat(3, 3, CvType.CV_64F)
        try {
            for (row in 0 until 3) {
                for (col in 0 until 3) r.put(row, col, cameraFromPage3x4[row * 4 + col].toDouble())
                tvec.put(row, 0, cameraFromPage3x4[row * 4 + 3].toDouble())
            }
            Geometry.Rodrigues(r, rvec)
        } finally {
            r.release()
        }
    }

    private fun currentPose(): FloatArray {
        val r = Mat()
        try {
            Geometry.Rodrigues(rvec, r)
            val out = FloatArray(12)
            for (row in 0 until 3) {
                for (col in 0 until 3) out[row * 4 + col] = r.get(row, col)[0].toFloat()
                out[row * 4 + 3] = tvec.get(row, 0)[0].toFloat()
            }
            return out
        } finally {
            r.release()
        }
    }

    private companion object {
        const val PYRAMID_LEVELS = 3
        const val FB_MAX_PX = 1.0
        const val OUTLIER_PX = 3.0
    }
}

/** The frame-to-frame tracker [KpmPlanarFrontEnd] uses between full solves ([FlowPoseTracker]). */
internal interface FlowTracker : AutoCloseable {

    /** A tracked pose: KPM-convention row-major camera-from-page 3×4 (mm), mean squared error (px²). */
    class Result(val cameraFromPage3x4: FloatArray, val meanSquaredErrorPx: Float, val inliers: Int)

    /** The page being tracked, or -1. */
    val trackedPage: Int

    /**
     * Seed from a full solve on [luma] (this tracker's frame size): [correspondences] rows of
     * (frame x, frame y, page x mm, page y mm) in this frame's pixels, and the solve's
     * [cameraFromPage3x4]. Returns false (and stays unseeded) with fewer than
     * [KpmMatchSpeedConfig.minFlowInliers] usable rows.
     */
    fun seed(luma: ByteArray, pageNo: Int, correspondences: FloatArray, cameraFromPage3x4: FloatArray): Boolean

    /** Track into [luma]; null (and unseeded) on loss. */
    fun track(luma: ByteArray): Result?

    fun reset()
}

/** The pure point-selection and acceptance rules of [FlowPoseTracker]. */
internal object FlowPoseMath {

    /** Indices of correspondence rows whose frame point is finite and inside a `width × height` frame. */
    fun usableSeedRows(correspondences: FloatArray, width: Int, height: Int): List<Int> =
        (0 until correspondences.size / 4).filter { i ->
            val x = correspondences[i * 4]
            val y = correspondences[i * 4 + 1]
            x.isFinite() && y.isFinite() && x >= 0f && y >= 0f && x <= width - 1 && y <= height - 1
        }

    /**
     * Indices of points that survived Lucas–Kanade both ways: tracked forward and back, inside the
     * frame, and returned within [maxForwardBackwardPx] of where they started. Point arrays are
     * interleaved `x, y`.
     */
    fun forwardBackwardSurvivors(
        previousXY: DoubleArray,
        nextXY: DoubleArray,
        backXY: DoubleArray,
        forwardOk: ByteArray,
        backwardOk: ByteArray,
        width: Int,
        height: Int,
        maxForwardBackwardPx: Double,
    ): List<Int> {
        val n = minOf(previousXY.size, nextXY.size, backXY.size) / 2
        val out = ArrayList<Int>(n)
        for (i in 0 until minOf(n, forwardOk.size, backwardOk.size)) {
            if (forwardOk[i].toInt() == 0 || backwardOk[i].toInt() == 0) continue
            val x = nextXY[i * 2]
            val y = nextXY[i * 2 + 1]
            if (!(x >= 0.0 && y >= 0.0 && x <= width - 1.0 && y <= height - 1.0)) continue
            val dx = backXY[i * 2] - previousXY[i * 2]
            val dy = backXY[i * 2 + 1] - previousXY[i * 2 + 1]
            if (!(dx * dx + dy * dy <= maxForwardBackwardPx * maxForwardBackwardPx)) continue
            out += i
        }
        return out
    }

    /** Whether [n] surviving points still meet both the absolute and the fractional floor. */
    fun enoughPoints(n: Int, seededCount: Int, config: KpmMatchSpeedConfig): Boolean =
        n >= config.minFlowInliers && n >= config.minFlowInlierFraction * seededCount

    /** Indices whose squared residual is within [outlierPx]. */
    fun inlierIndices(residualsSq: DoubleArray, outlierPx: Double): List<Int> =
        residualsSq.indices.filter { residualsSq[it] <= outlierPx * outlierPx }

    /** The mean squared residual, or null when it is not finite or its RMS exceeds the config limit. */
    fun acceptedMeanSquaredError(residualsSq: DoubleArray, config: KpmMatchSpeedConfig): Float? {
        if (residualsSq.isEmpty()) return null
        val mse = residualsSq.average().toFloat()
        if (!mse.isFinite() || kotlin.math.sqrt(mse) > config.maxFlowRmsErrorPx) return null
        return mse
    }
}

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
import kotlin.math.sqrt

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
 */
internal class FlowPoseTracker(intrinsics: CameraIntrinsics, private val config: KpmMatchSpeedConfig) : AutoCloseable {

    class Result(val cameraFromPage3x4: FloatArray, val meanSquaredErrorPx: Float, val inliers: Int)

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

    /** The page being tracked, or -1. */
    val trackedPage: Int get() = if (seededCount > 0) pageNo else -1

    /**
     * Seed from a full solve on [luma] (this tracker's frame size): [correspondences] rows of
     * (frame x, frame y, page x mm, page y mm) in this frame's pixels, and the solve's
     * [cameraFromPage3x4]. Returns false (and stays unseeded) with fewer than
     * [KpmMatchSpeedConfig.minFlowInliers] usable rows.
     */
    fun seed(luma: ByteArray, pageNo: Int, correspondences: FloatArray, cameraFromPage3x4: FloatArray): Boolean {
        reset()
        require(luma.size >= width * height && cameraFromPage3x4.size == 12)
        val n = correspondences.size / 4
        if (n < config.minFlowInliers) return false
        val img = ArrayList<Point>(n)
        val obj = ArrayList<Point3>(n)
        for (i in 0 until n) {
            val x = correspondences[i * 4]
            val y = correspondences[i * 4 + 1]
            if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f || x > width - 1 || y > height - 1) continue
            img += Point(x.toDouble(), y.toDouble())
            obj += Point3(correspondences[i * 4 + 2].toDouble(), correspondences[i * 4 + 3].toDouble(), 0.0)
        }
        if (img.size < config.minFlowInliers) return false
        prev.put(0, 0, luma, 0, width * height)
        setPose(cameraFromPage3x4)
        imagePoints = img
        objectPoints = obj
        seededCount = img.size
        this.pageNo = pageNo
        return true
    }

    /** Track into [luma]; null (and unseeded) on loss. */
    fun track(luma: ByteArray): Result? {
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
            val ok = status.toArray()
            val backOk = backStatus.toArray()
            val img = ArrayList<Point>(next.size)
            val obj = ArrayList<Point3>(next.size)
            for (i in next.indices) {
                if (ok[i].toInt() == 0 || backOk[i].toInt() == 0) continue
                val p = next[i]
                if (p.x < 0 || p.y < 0 || p.x > width - 1 || p.y > height - 1) continue
                val dx = back[i].x - imagePoints[i].x
                val dy = back[i].y - imagePoints[i].y
                if (dx * dx + dy * dy > FB_MAX_PX * FB_MAX_PX) continue
                img += p
                obj += objectPoints[i]
            }
            if (!enoughPoints(img.size)) return lost()

            var solved = solve(obj, img) ?: return lost()
            // One outlier-rejection pass on the reprojection residuals.
            val keep = solved.residualsSq.indices.filter { solved.residualsSq[it] <= OUTLIER_PX * OUTLIER_PX }
            if (keep.size < img.size) {
                if (!enoughPoints(keep.size)) return lost()
                val img2 = keep.map { img[it] }
                val obj2 = keep.map { obj[it] }
                solved = solve(obj2, img2) ?: return lost()
                img.clear(); img.addAll(img2)
                obj.clear(); obj.addAll(obj2)
            }
            val mse = solved.residualsSq.average().toFloat()
            if (!mse.isFinite() || sqrt(mse) > config.maxFlowRmsErrorPx) return lost()

            // Accept: the current frame becomes the reference for the next LK step.
            val t = prev
            prev = cur
            cur = t
            imagePoints = img
            objectPoints = obj
            return Result(currentPose(), mse, img.size)
        } finally {
            prevPts.release(); nextPts.release(); backPts.release()
            status.release(); backStatus.release(); err.release()
        }
    }

    fun reset() {
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

    private fun enoughPoints(n: Int): Boolean =
        n >= config.minFlowInliers && n >= config.minFlowInlierFraction * seededCount

    private fun lost(): Result? {
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

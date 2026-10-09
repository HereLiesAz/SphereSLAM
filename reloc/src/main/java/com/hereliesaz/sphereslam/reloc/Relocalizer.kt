package com.hereliesaz.sphereslam.reloc

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.features.DescriptorMatcher
import org.opencv.features.ORB
import org.opencv.geometry.Geometry

/**
 * Result of a relocalization attempt: the camera pose relative to the fingerprint's object frame,
 * how many PnP inliers supported it (confidence), and the inliers' mean reprojection residual.
 */
@ExperimentalSphereSlamRelocApi
data class RelocResult(
    /**
     * `camera_from_object` as a row-major 4×4 (rotation in the upper-left 3×3, translation in the
     * last column). Multiply an object-frame point `[x,y,z,1]` by this to get it in camera space —
     * exactly what an overlay renderer needs to place content on the target.
     */
    val cameraFromObject: FloatArray,
    val inliers: Int,
    /**
     * Mean reprojection error (pixels) of the PnP inliers under [cameraFromObject] — the residual a
     * [PoseAcceptancePolicy] gates on. Defaults to 0 ("unknown, do not reject") for hand-built results.
     */
    val reprojectionErrorPx: Float = 0f,
)

/**
 * Basic markerless relocalization: match the live frame against a [PlanarFingerprint] and solve for
 * the camera pose with PnP+RANSAC. This is the whole "where is the camera relative to my target"
 * primitive for simple overlay AR — no tracking loop, no map, no IMU required.
 *
 * Deliberately plain: ORB + Lowe-ratio matching + `solvePnPRansac`, nothing more. Swap ORB for a
 * learned front-end by building the fingerprint and frame features yourself and calling
 * [relocalizeWith].
 *
 * **Native prerequisite.** This class allocates OpenCV objects in its constructor, so the OpenCV
 * Java native library (`libopencv_java5.so`, e.g. via `System.loadLibrary("opencv_java5")` or
 * `OpenCVLoader.initLocal()`) must be loaded by the host first. SphereSLAM's own
 * `com.hereliesaz.sphereslam.common.util.NativeLibLoader` loads only `libsphereslam` — it does
 * **not** load OpenCV.
 *
 * **Lifecycle.** Holds native ORB and matrix handles; call [close] when done. Per-frame temporaries
 * are released before each call returns. Not thread-safe.
 *
 * @param fx focal length x (px, `> 0`).
 * @param fy focal length y (px, `> 0`).
 * @param cx principal point x (px).
 * @param cy principal point y (px).
 * @param loweRatio Lowe ratio-test threshold (best / second-best descriptor distance).
 * @param minInliers minimum ratio-test matches to attempt a solve, and PnP inliers to accept one.
 * @param maxFeatures ORB feature cap per frame.
 * @param ransacIterations `solvePnPRansac` iteration count.
 * @param ransacReprojectionThresholdPx `solvePnPRansac` inlier threshold (px).
 * @param ransacConfidence `solvePnPRansac` confidence in `(0, 1)`.
 */
@ExperimentalSphereSlamRelocApi
class Relocalizer(
    private val fx: Double,
    private val fy: Double,
    private val cx: Double,
    private val cy: Double,
    private val loweRatio: Float = DEFAULT_LOWE_RATIO,
    private val minInliers: Int = DEFAULT_MIN_INLIERS,
    private val maxFeatures: Int = DEFAULT_MAX_FEATURES,
    private val ransacIterations: Int = DEFAULT_RANSAC_ITERATIONS,
    private val ransacReprojectionThresholdPx: Float = DEFAULT_RANSAC_REPROJECTION_THRESHOLD_PX,
    private val ransacConfidence: Double = DEFAULT_RANSAC_CONFIDENCE,
) : AutoCloseable {
    init {
        // Validate before any native allocation below, so a bad argument never leaks a handle.
        require(fx > 0 && fy > 0) { "focal lengths must be positive" }
        require(loweRatio > 0f && loweRatio <= 1f) { "loweRatio must be in (0, 1]" }
        require(minInliers >= 4) { "minInliers must be >= 4" }
        require(maxFeatures > 0) { "maxFeatures must be positive" }
        require(ransacIterations > 0) { "ransacIterations must be positive" }
        require(ransacReprojectionThresholdPx > 0f) { "ransacReprojectionThresholdPx must be positive" }
        require(ransacConfidence > 0.0 && ransacConfidence < 1.0) { "ransacConfidence must be in (0, 1)" }
    }

    var fingerprint: Fingerprint? = null

    private val orb = ORB.create(maxFeatures)
    private val cameraMatrix: Mat = Mat(3, 3, CvType.CV_64F).apply {
        put(0, 0, fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0)
    }
    private val distCoeffs = MatOfDouble()

    /**
     * ORB keypoints + descriptors for one frame, detected once so they can be matched against many
     * fingerprints (e.g. a photosphere's in-view candidate tiles) without re-detecting per candidate.
     */
    data class FrameFeatures(val keypoints: MatOfKeyPoint, val descriptors: Mat) {
        /** Release both native buffers. */
        fun release() {
            keypoints.release()
            descriptors.release()
        }
    }

    /**
     * Detect ORB features on [gray], or null when the frame yields none. The returned Mats are owned
     * by the caller (release them, or let [relocalize] / [TileMatcher.evaluate] do so).
     */
    fun detect(gray: Mat): FrameFeatures? {
        val kps = MatOfKeyPoint()
        val descriptors = Mat()
        val mask = Mat()
        try {
            orb.detectAndCompute(gray, mask, kps, descriptors)
        } catch (t: Throwable) {
            kps.release(); descriptors.release()
            throw t
        } finally {
            mask.release()
        }
        if (descriptors.empty()) {
            kps.release(); descriptors.release()
            return null
        }
        return FrameFeatures(kps, descriptors)
    }

    /** Detect ORB on [gray] and relocalize against the current [fingerprint]; null if none / too weak. */
    fun relocalize(gray: Mat): RelocResult? {
        val fp = fingerprint ?: return null
        val f = detect(gray) ?: return null
        return try {
            relocalizeWith(f.keypoints, f.descriptors, fp)
        } finally {
            f.release()
        }
    }

    /**
     * Relocalize from already-computed frame features (e.g. a learned detector). [frameDescriptors]
     * must match the fingerprint's descriptor type.
     */
    fun relocalizeWith(
        frameKeypoints: MatOfKeyPoint,
        frameDescriptors: Mat,
        fingerprintOverride: Fingerprint? = null,
    ): RelocResult? {
        val fp = fingerprintOverride ?: fingerprint ?: return null
        if (fp.descriptors.empty() || frameDescriptors.empty()) return null
        if (fp.descriptors.type() != frameDescriptors.type()) return null

        val matcher = DescriptorMatcher.create(
            if (fp.binaryDescriptors) DescriptorMatcher.BRUTEFORCE_HAMMING
            else DescriptorMatcher.BRUTEFORCE_SL2,
        )
        val knn = mutableListOf<MatOfDMatch>()
        val objectMat = MatOfPoint3f()
        val imageMat = MatOfPoint2f()
        val rvec = Mat()
        val tvec = Mat()
        val inliersMat = Mat()
        try {
            matcher.knnMatch(frameDescriptors, fp.descriptors, knn, 2)

            val fpPoints = fp.points3d.toArray()
            val frameKps = frameKeypoints.toArray()
            val objPts = ArrayList<Point3>()
            val imgPts = ArrayList<Point>()
            for (m in knn) {
                val arr = m.toArray()
                if (arr.size < 2) continue
                if (arr[0].distance < loweRatio * arr[1].distance) {
                    val ti = arr[0].trainIdx
                    val qi = arr[0].queryIdx
                    if (ti in fpPoints.indices && qi in frameKps.indices) {
                        objPts.add(fpPoints[ti])
                        imgPts.add(frameKps[qi].pt)
                    }
                }
            }
            if (objPts.size < minInliers) return null

            objectMat.fromList(objPts)
            imageMat.fromList(imgPts)
            val ok = Geometry.solvePnPRansac(
                objectMat, imageMat, cameraMatrix, distCoeffs, rvec, tvec,
                false, ransacIterations, ransacReprojectionThresholdPx, ransacConfidence, inliersMat,
                Geometry.SOLVEPNP_ITERATIVE,
            )
            val inlierCount = if (inliersMat.empty()) 0 else inliersMat.rows()
            if (!ok || inlierCount < minInliers) return null

            val pose = composePose(rvec, tvec)
            val inlierIdx = IntArray(inlierCount) { inliersMat.get(it, 0)[0].toInt() }
            val residual = meanReprojectionErrorPx(
                pose, fx, fy, cx, cy,
                objPts.map { doubleArrayOf(it.x, it.y, it.z) },
                imgPts.map { doubleArrayOf(it.x, it.y) },
                inlierIdx,
            )
            return RelocResult(cameraFromObject = pose, inliers = inlierCount, reprojectionErrorPx = residual)
        } finally {
            for (m in knn) m.release()
            objectMat.release()
            imageMat.release()
            rvec.release()
            tvec.release()
            inliersMat.release()
        }
    }

    /** rvec/tvec (object→camera) → row-major 4×4 camera_from_object. */
    private fun composePose(rvec: Mat, tvec: Mat): FloatArray {
        val r = Mat()
        try {
            Geometry.Rodrigues(rvec, r)
            val m = FloatArray(16)
            m[15] = 1f
            for (row in 0 until 3) {
                for (col in 0 until 3) m[row * 4 + col] = r.get(row, col)[0].toFloat()
                m[row * 4 + 3] = tvec.get(row, 0)[0].toFloat()
            }
            return m
        } finally {
            r.release()
        }
    }

    /**
     * Release the native intrinsics buffers and clear the ORB detector's state. OpenCV's Java binding
     * exposes no explicit delete for an `Algorithm`, so the ORB handle itself is reclaimed by its
     * finalizer once this instance is unreachable. The instance must not be used afterwards.
     */
    override fun close() {
        orb.clear()
        cameraMatrix.release()
        distCoeffs.release()
    }

    companion object {
        /** Default Lowe ratio-test threshold. */
        const val DEFAULT_LOWE_RATIO = 0.75f

        /** Default minimum matches to attempt a solve and inliers to accept it. */
        const val DEFAULT_MIN_INLIERS = 12

        /** Default ORB feature cap per frame. */
        const val DEFAULT_MAX_FEATURES = 1000

        /** Default `solvePnPRansac` iteration count. */
        const val DEFAULT_RANSAC_ITERATIONS = 100

        /** Default `solvePnPRansac` inlier reprojection threshold, pixels. */
        const val DEFAULT_RANSAC_REPROJECTION_THRESHOLD_PX = 8.0f

        /** Default `solvePnPRansac` confidence. */
        const val DEFAULT_RANSAC_CONFIDENCE = 0.99

        /**
         * Mean pinhole reprojection error (px) of the [inlierIndices] subset of parallel
         * [objectPoints] (`[x, y, z]`) / [imagePoints] (`[u, v]`) under the row-major 4×4
         * [cameraFromObject]. Points at or behind the camera count as +∞ (returned as such), so a
         * degenerate solve is rejected by a residual gate. 0 for an empty inlier set. Pure; unit-tested.
         */
        internal fun meanReprojectionErrorPx(
            cameraFromObject: FloatArray,
            fx: Double,
            fy: Double,
            cx: Double,
            cy: Double,
            objectPoints: List<DoubleArray>,
            imagePoints: List<DoubleArray>,
            inlierIndices: IntArray,
        ): Float {
            if (inlierIndices.isEmpty()) return 0f
            val m = cameraFromObject
            var sum = 0.0
            var n = 0
            for (i in inlierIndices) {
                if (i !in objectPoints.indices || i !in imagePoints.indices) continue
                val p = objectPoints[i]
                val xc = m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3]
                val yc = m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7]
                val zc = m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11]
                if (zc <= 1e-9) return Float.POSITIVE_INFINITY
                val du = fx * xc / zc + cx - imagePoints[i][0]
                val dv = fy * yc / zc + cy - imagePoints[i][1]
                sum += kotlin.math.sqrt(du * du + dv * dv)
                n++
            }
            return if (n == 0) 0f else (sum / n).toFloat()
        }
    }
}

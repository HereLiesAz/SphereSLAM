package com.hereliesaz.sphereslam.reloc

import org.opencv.core.Core
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
 * and how many PnP inliers supported it (confidence).
 */
data class RelocResult(
    /**
     * `camera_from_object` as a row-major 4×4 (rotation in the upper-left 3×3, translation in the
     * last column). Multiply an object-frame point `[x,y,z,1]` by this to get it in camera space —
     * exactly what an overlay renderer needs to place content on the target.
     */
    val cameraFromObject: FloatArray,
    val inliers: Int,
)

/**
 * Basic markerless relocalization: match the live frame against a [PlanarFingerprint] and solve for
 * the camera pose with PnP+RANSAC. This is the whole "where is the camera relative to my target"
 * primitive for simple overlay AR — no tracking loop, no map, no IMU required.
 *
 * Deliberately plain: ORB + Lowe-ratio matching + `solvePnPRansac`. None of GraffitiXR's
 * corroboration gating or teleological self-grow is here — that stays proprietary. Swap ORB for a
 * learned front-end by building the fingerprint and frame features yourself and calling
 * [relocalizeWith].
 */
class Relocalizer(
    private val fx: Double,
    private val fy: Double,
    private val cx: Double,
    private val cy: Double,
    private val loweRatio: Float = 0.75f,
    private val minInliers: Int = 12,
    private val maxFeatures: Int = 1000,
) {
    var fingerprint: PlanarFingerprint? = null

    private val orb = ORB.create(maxFeatures)
    private val cameraMatrix: Mat = Mat(3, 3, CvType.CV_64F).apply {
        put(0, 0, fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0)
    }
    private val distCoeffs = MatOfDouble()

    /** Detect ORB on [gray] and relocalize against the current [fingerprint]; null if none / too weak. */
    fun relocalize(gray: Mat): RelocResult? {
        val fp = fingerprint ?: return null
        val kps = MatOfKeyPoint()
        val descriptors = Mat()
        orb.detectAndCompute(gray, Mat(), kps, descriptors)
        if (descriptors.empty()) return null
        return relocalizeWith(kps, descriptors, fp)
    }

    /**
     * Relocalize from already-computed frame features (e.g. a learned detector). [frameDescriptors]
     * must match the fingerprint's descriptor type.
     */
    fun relocalizeWith(
        frameKeypoints: MatOfKeyPoint,
        frameDescriptors: Mat,
        fingerprintOverride: PlanarFingerprint? = null,
    ): RelocResult? {
        val fp = fingerprintOverride ?: fingerprint ?: return null
        if (fp.descriptors.empty() || frameDescriptors.empty()) return null
        if (fp.descriptors.type() != frameDescriptors.type()) return null

        val matcher = DescriptorMatcher.create(
            if (fp.binaryDescriptors) DescriptorMatcher.BRUTEFORCE_HAMMING
            else DescriptorMatcher.BRUTEFORCE_SL2,
        )
        val knn = mutableListOf<MatOfDMatch>()
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

        val objectMat = MatOfPoint3f().apply { fromList(objPts) }
        val imageMat = MatOfPoint2f().apply { fromList(imgPts) }
        val rvec = Mat()
        val tvec = Mat()
        val inliersMat = Mat()
        val ok = Geometry.solvePnPRansac(
            objectMat, imageMat, cameraMatrix, distCoeffs, rvec, tvec,
            false, 100, 8.0f, 0.99, inliersMat, Geometry.SOLVEPNP_ITERATIVE,
        )
        val inlierCount = if (inliersMat.empty()) 0 else inliersMat.rows()
        if (!ok || inlierCount < minInliers) return null

        return RelocResult(cameraFromObject = composePose(rvec, tvec), inliers = inlierCount)
    }

    /** rvec/tvec (object→camera) → row-major 4×4 camera_from_object. */
    private fun composePose(rvec: Mat, tvec: Mat): FloatArray {
        val r = Mat()
        Geometry.Rodrigues(rvec, r)
        val m = FloatArray(16)
        m[15] = 1f
        for (row in 0 until 3) {
            for (col in 0 until 3) m[row * 4 + col] = r.get(row, col)[0].toFloat()
            m[row * 4 + 3] = tvec.get(row, 0)[0].toFloat()
        }
        return m
    }

    init {
        require(fx > 0 && fy > 0) { "focal lengths must be positive" }
        // Core referenced so the OpenCV dependency is unambiguous to the build even if only PnP is hit.
        @Suppress("UNUSED_EXPRESSION") Core.VERSION
    }
}

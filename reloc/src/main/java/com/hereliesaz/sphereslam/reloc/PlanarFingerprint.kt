package com.hereliesaz.sphereslam.reloc

import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.features.ORB

/**
 * A planar reference target distilled to what relocalization needs: ORB [descriptors] and the 3D
 * [points3d] each was detected at, on the centered `z = 0` plane (see [PlanarGeometry]). Build one
 * from a reference image with [fromReference]; hand it to a [Relocalizer].
 *
 * ORB by default — on-device, license-clean, no model. For denser features in hard texture a
 * consumer can build the descriptor/point set from the `:models` SuperPoint wrapper instead and
 * construct this directly; the relocalizer only needs matching descriptor types.
 */
class PlanarFingerprint(
    val descriptors: Mat,
    val points3d: MatOfPoint3f,
    val keypoints: MatOfKeyPoint,
    /** Descriptor matcher norm: ORB is binary (Hamming); a float descriptor set would use L2. */
    val binaryDescriptors: Boolean = true,
) {
    val size: Int get() = keypoints.toArray().size

    companion object {
        /**
         * Detect ORB on a grayscale reference [gray] and map each keypoint to its 3D point on the
         * centered target plane, metric by [widthMeters] (pass 1f for up-to-scale). [maxFeatures]
         * caps the fingerprint size.
         */
        fun fromReference(gray: Mat, widthMeters: Float, maxFeatures: Int = 1000): PlanarFingerprint {
            val orb = ORB.create(maxFeatures)
            val kps = MatOfKeyPoint()
            val descriptors = Mat()
            orb.detectAndCompute(gray, Mat(), kps, descriptors)

            val w = gray.cols()
            val h = gray.rows()
            val pts = kps.toArray().map { kp ->
                val p = PlanarGeometry.pixelToPlane(kp.pt.x.toFloat(), kp.pt.y.toFloat(), w, h, widthMeters)
                Point3(p[0].toDouble(), p[1].toDouble(), p[2].toDouble())
            }
            val points3d = MatOfPoint3f().apply { fromList(pts) }
            return PlanarFingerprint(descriptors, points3d, kps, binaryDescriptors = true)
        }
    }
}

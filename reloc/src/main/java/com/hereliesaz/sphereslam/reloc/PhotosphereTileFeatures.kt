package com.hereliesaz.sphereslam.reloc

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features.DescriptorMatcher
import org.opencv.features.ORB

/**
 * Keypoints of one image (a photosphere tile's keyframe, or a live frame) for
 * [PhotosphereTileLocalizer]: pixel positions plus whatever descriptor storage the
 * [TileFeatureBackend] that made them needs. Owned by whoever holds it; [close] releases native
 * descriptor memory.
 *
 * @property points `u0, v0, u1, v1, …` in the image's pixels (u right, v down).
 */
@ExperimentalSphereSlamRelocApi
internal class TileFeatures(
    val points: FloatArray,
    internal val descriptors: Any?,
    private val release: () -> Unit = {},
) : AutoCloseable {
    val size: Int get() = points.size / 2

    override fun close() = release()
}

/**
 * The OpenCV edge of tile localization, behind a seam so everything downstream (which tile, which
 * rotation, which landmarks, which pose) is plain Kotlin and JVM-tested with a fake.
 */
@ExperimentalSphereSlamRelocApi
internal interface TileFeatureBackend {
    /** Detect and describe [luma] (tightly packed, `width * height`); null when nothing usable. */
    fun extract(luma: ByteArray, width: Int, height: Int): TileFeatures?

    /**
     * Correspondences from [query] to [train], flattened `q0, t0, q1, t1, …` (feature indices). Each
     * query index appears at most once.
     */
    fun match(query: TileFeatures, train: TileFeatures): IntArray
}

/**
 * ORB + brute-force Hamming kNN with a Lowe ratio test — the same plain recipe as [Relocalizer],
 * on-device and model-free. Needs the OpenCV Java native library loaded by the host.
 *
 * @param maxFeatures ORB feature cap per image.
 * @param loweRatio best / second-best Hamming distance ratio a match must beat.
 */
@ExperimentalSphereSlamRelocApi
internal class OrbTileFeatureBackend(
    private val maxFeatures: Int = DEFAULT_MAX_FEATURES,
    private val loweRatio: Float = DEFAULT_LOWE_RATIO,
) : TileFeatureBackend {

    override fun extract(luma: ByteArray, width: Int, height: Int): TileFeatures? {
        require(luma.size == width * height) { "luma must be width*height bytes" }
        val gray = Mat(height, width, CvType.CV_8UC1)
        val kps = MatOfKeyPoint()
        val descriptors = Mat()
        val mask = Mat()
        try {
            gray.put(0, 0, luma)
            ORB.create(maxFeatures).detectAndCompute(gray, mask, kps, descriptors)
        } finally {
            gray.release()
            mask.release()
        }
        val keypoints = kps.toArray()
        kps.release()
        if (keypoints.isEmpty() || descriptors.empty()) {
            descriptors.release()
            return null
        }
        val points = FloatArray(keypoints.size * 2)
        keypoints.forEachIndexed { i, kp ->
            points[2 * i] = kp.pt.x.toFloat()
            points[2 * i + 1] = kp.pt.y.toFloat()
        }
        return TileFeatures(points, descriptors) { descriptors.release() }
    }

    override fun match(query: TileFeatures, train: TileFeatures): IntArray {
        val q = query.descriptors as? Mat ?: return IntArray(0)
        val t = train.descriptors as? Mat ?: return IntArray(0)
        if (q.empty() || t.empty() || t.rows() < 2) return IntArray(0)
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val knn = ArrayList<MatOfDMatch>()
        try {
            matcher.knnMatch(q, t, knn, 2)
            val out = ArrayList<Int>(knn.size * 2)
            val usedTrain = HashSet<Int>()
            for (m in knn) {
                val pair = m.toArray()
                if (pair.size < 2) continue
                if (pair[0].distance >= loweRatio * pair[1].distance) continue
                // One train feature per query match: a repeated texture must not vote twice.
                if (!usedTrain.add(pair[0].trainIdx)) continue
                out += pair[0].queryIdx
                out += pair[0].trainIdx
            }
            return out.toIntArray()
        } finally {
            knn.forEach { it.release() }
        }
    }

    companion object {
        const val DEFAULT_MAX_FEATURES = 600
        const val DEFAULT_LOWE_RATIO = 0.8f
    }
}

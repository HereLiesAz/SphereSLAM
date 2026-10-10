package com.hereliesaz.sphereslam.reloc

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.features.DescriptorMatcher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * A persistent, confidence-weighted feature map of the wall around a fingerprint — the self-growing
 * backbone for wide-area relocalization. Every successful relocalization can [grow] it: features that
 * match an existing map point re-observe it (confidence + 0.1, capped at 1); unmatched features are
 * back-projected onto the fingerprint's wall plane and added (confidence 0.1); with a calibrated
 * [InverseDepthMap], features off the wall are placed radially instead of discarded. At [capacity]
 * the map drops points that never earned a re-observation (confidence < 0.2) before growing.
 *
 * All points live in the **fingerprint (object) frame**, so matching any patch of the map yields the
 * same camera-from-fingerprint pose as the fingerprint itself: [toFingerprint] (optionally gated to the
 * points in view, [visibleIndices], and merged with the base fingerprint) feeds [Relocalizer].
 *
 * Descriptors are stored as raw row-major bytes tagged with their OpenCV type ([CvType.CV_8U] for ORB,
 * [CvType.CV_32F] for SuperPoint), so either kind round-trips; [snapshot] / [fromSnapshot] mirror
 * GraffitiXR's `WallFeatureMap` model field-for-field and [toBytes] / [fromBytes] its native export
 * layout. The geometry ([grow], [visibleIndices], snapshots) is pure Kotlin; only [matchDescriptors],
 * [growFromReloc] and [toFingerprint] touch OpenCV.
 *
 * Ported from GraffitiXR's native `MobileGS::growMapFromReloc` / `exportWallFeatureMap` and its
 * frustum-gated map matching. Not thread-safe.
 */
@ExperimentalSphereSlamRelocApi
class WallFeatureMap(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0)
    }

    /** Field-for-field mirror of GraffitiXR's persisted `WallFeatureMap` model (arrays are copies). */
    class Snapshot(
        val points3d: FloatArray,
        val descriptorsData: ByteArray,
        val descriptorsRows: Int,
        val descriptorsCols: Int,
        val descriptorsType: Int,
        val confidence: FloatArray,
        val obsCount: IntArray,
        val anchor: FloatArray,
        val intrinsics: FloatArray,
    )

    /** What one [grow] changed. */
    data class Growth(val added: Int, val reobserved: Int, val pruned: Int, val depthCalibrated: Boolean)

    private val points = ArrayList<FloatArray>()
    private val confidence = ArrayList<Float>()
    private val observations = ArrayList<Int>()
    private val descriptors = ArrayList<ByteArray>()
    private var descriptorCols = 0
    private var descriptorType = -1
    private var anchorValue: FloatArray? = null
    private var intrinsicsValue: FloatArray? = null

    /** Incremented whenever the map's contents change (for persistence throttling). */
    var revision: Long = 0L
        private set

    /** Number of map points. */
    val size: Int get() = points.size

    /** OpenCV type of the stored descriptors, or -1 while empty. */
    val descriptorsType: Int get() = descriptorType

    /** The fingerprint anchor the map is co-registered to (column-major 16), or null. */
    val anchor: FloatArray? get() = anchorValue?.copyOf()

    /** `[fx, fy, cx, cy]` the map was last grown with, or null. */
    val intrinsics: FloatArray? get() = intrinsicsValue?.copyOf()

    /** Point [i] in the fingerprint frame. */
    fun point(i: Int): FloatArray = points[i].copyOf()

    /** Confidence of point [i]. */
    fun confidenceOf(i: Int): Float = confidence[i]

    /** Observation count of point [i]. */
    fun observationsOf(i: Int): Int = observations[i]

    /** Drop every point and descriptor (keeps [capacity]). */
    fun clear() {
        if (points.isEmpty() && anchorValue == null) return
        points.clear(); confidence.clear(); observations.clear(); descriptors.clear()
        descriptorCols = 0; descriptorType = -1; anchorValue = null; intrinsicsValue = null
        revision++
    }

    /**
     * At [capacity], drop points with confidence below [PRUNE_CONFIDENCE].
     *
     * @return the old index → new index map (-1 for a dropped point), or null when nothing was pruned.
     */
    fun pruneIfFull(): IntArray? {
        if (points.size < capacity) return null
        val remap = IntArray(points.size) { -1 }
        var next = 0
        for (i in points.indices) if (confidence[i] >= PRUNE_CONFIDENCE) remap[i] = next++
        if (next == points.size) return null
        for (i in points.indices.reversed()) {
            if (remap[i] < 0) {
                points.removeAt(i); confidence.removeAt(i); observations.removeAt(i); descriptors.removeAt(i)
            }
        }
        if (points.isEmpty()) { descriptorCols = 0; descriptorType = -1 }
        revision++
        return remap
    }

    /**
     * Grow the map from one relocalized frame (pure geometry; matching is the caller's).
     *
     * @param cameraFromFingerprint row-major 4×4 OpenCV camera-from-fingerprint
     *   ([RelocResult.cameraFromObject]).
     * @param keypoints flat `[u0, v0, …]` frame features.
     * @param frameDescriptors row-major descriptor bytes, one row per keypoint.
     * @param frameDescriptorCols / [frameDescriptorType] the descriptors' OpenCV `cols()` / `type()`.
     * @param matchedMapIndex per keypoint, the map point it matched (index into the map before any
     *   [pruneIfFull] this call performs), or -1.
     * @param wallPlane the fingerprint's wall plane in the fingerprint frame ([WallPlane.fit] over its
     *   marks).
     * @param anchor fingerprint anchor to co-register the map to, or null to keep the current one.
     * @param depth optional relative depth of this frame for radial placement of off-wall features.
     * @return null (map untouched) when the inputs disagree or the descriptor type differs from the map's.
     */
    fun grow(
        cameraFromFingerprint: FloatArray,
        keypoints: FloatArray,
        frameDescriptors: ByteArray,
        frameDescriptorCols: Int,
        frameDescriptorType: Int,
        matchedMapIndex: IntArray,
        wallPlane: WallPlane,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        anchor: FloatArray? = null,
        depth: InverseDepthMap? = null,
    ): Growth? {
        val n = keypoints.size / 2
        if (n == 0 || keypoints.size % 2 != 0 || matchedMapIndex.size != n) return null
        if (frameDescriptors.isEmpty() || frameDescriptors.size % n != 0 || frameDescriptorCols <= 0) return null
        if (cameraFromFingerprint.size != 16 || cameraFromFingerprint.any { !it.isFinite() }) return null
        if (!(fx > 0f) || !(fy > 0f)) return null
        require(anchor == null || anchor.size == 16)
        val rowBytes = frameDescriptors.size / n
        if (points.isNotEmpty() && (frameDescriptorType != descriptorType || rowBytes != descriptors[0].size)) return null

        val sizeBefore = points.size
        val remap = pruneIfFull()
        val pruned = sizeBefore - points.size
        var mutated = pruned > 0

        // Re-observe matched points.
        val matched = BooleanArray(n)
        var reobserved = 0
        for (q in 0 until n) {
            var t = matchedMapIndex[q]
            if (t < 0) continue
            if (remap != null) t = if (t < remap.size) remap[t] else -1
            if (t < 0 || t >= points.size) continue
            confidence[t] = minOf(1f, confidence[t] + 0.1f)
            observations[t] = observations[t] + 1
            matched[q] = true
            reobserved++
            mutated = true
        }

        // Plane in the camera frame; every feature's plane depth (camera-axis, -1 when no hit ahead).
        val camFromFp = columnMajor(cameraFromFingerprint)
        val planeCam = wallPlane.transformedBy(camFromFp)
        val p = planeCam.point
        val nrm = planeCam.normal
        val nDotP = nrm[0] * p[0] + nrm[1] * p[1] + nrm[2] * p[2]
        val planeDepth = FloatArray(n) { i ->
            val dx = (keypoints[2 * i] - cx) / fx
            val dy = (keypoints[2 * i + 1] - cy) / fy
            val nDotD = nrm[0] * dx + nrm[1] * dy + nrm[2]
            if (kotlin.math.abs(nDotD) < 1e-6f) -1f else (nDotP / nDotD).takeIf { it.isFinite() } ?: -1f
        }
        val wallDistance = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
        val fit = depth?.let { RadialDepthPlacement.calibrate(keypoints, planeDepth, it) }

        val fpFromCam = invertRigid(camFromFp)
        var added = 0
        for (i in 0 until n) {
            if (matched[i]) continue
            if (points.size >= capacity) break
            var t = planeDepth[i]
            if (t <= 0f) {
                if (fit == null || depth == null) continue
                t = RadialDepthPlacement.radialDepth(fit, depth, keypoints[2 * i], keypoints[2 * i + 1], wallDistance)
                    ?: continue
            }
            val xc = t * (keypoints[2 * i] - cx) / fx
            val yc = t * (keypoints[2 * i + 1] - cy) / fy
            val m = fpFromCam
            points += floatArrayOf(
                m[0] * xc + m[4] * yc + m[8] * t + m[12],
                m[1] * xc + m[5] * yc + m[9] * t + m[13],
                m[2] * xc + m[6] * yc + m[10] * t + m[14],
            )
            confidence += NEW_POINT_CONFIDENCE
            observations += 1
            descriptors += frameDescriptors.copyOfRange(i * rowBytes, (i + 1) * rowBytes)
            added++
        }
        if (added > 0) {
            descriptorType = frameDescriptorType
            descriptorCols = frameDescriptorCols
            mutated = true
        }
        if (anchor != null) anchorValue = anchor.copyOf()
        intrinsicsValue = floatArrayOf(fx, fy, cx, cy)
        if (mutated) revision++
        return Growth(added = added, reobserved = reobserved, pruned = pruned, depthCalibrated = fit != null)
    }

    /**
     * Indices of map points that project inside a [width] × [height] image under the row-major OpenCV
     * [cameraFromFingerprint] with depth above [minDepth] — the frustum gate that keeps map matching
     * to the subset in view instead of the whole map.
     */
    fun visibleIndices(
        cameraFromFingerprint: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        width: Int,
        height: Int,
        minDepth: Float = 0.05f,
    ): IntArray {
        require(cameraFromFingerprint.size == 16)
        val m = cameraFromFingerprint
        val out = ArrayList<Int>()
        for ((i, pt) in points.withIndex()) {
            val z = m[8] * pt[0] + m[9] * pt[1] + m[10] * pt[2] + m[11]
            if (z <= minDepth) continue
            val u = fx * (m[0] * pt[0] + m[1] * pt[1] + m[2] * pt[2] + m[3]) / z + cx
            val v = fy * (m[4] * pt[0] + m[5] * pt[1] + m[6] * pt[2] + m[7]) / z + cy
            if (u >= 0f && u < width && v >= 0f && v < height) out += i
        }
        return out.toIntArray()
    }

    /** A copy in GraffitiXR's persisted-model shape. */
    fun snapshot(): Snapshot {
        val rowBytes = descriptors.firstOrNull()?.size ?: 0
        val data = ByteArray(rowBytes * descriptors.size)
        descriptors.forEachIndexed { i, d -> System.arraycopy(d, 0, data, i * rowBytes, rowBytes) }
        return Snapshot(
            points3d = FloatArray(points.size * 3) { points[it / 3][it % 3] },
            descriptorsData = data,
            descriptorsRows = descriptors.size,
            descriptorsCols = if (descriptors.isEmpty()) 0 else descriptorCols,
            descriptorsType = if (descriptors.isEmpty()) 0 else descriptorType,
            confidence = confidence.toFloatArray(),
            obsCount = observations.toIntArray(),
            anchor = anchorValue?.copyOf() ?: FloatArray(0),
            intrinsics = intrinsicsValue?.copyOf() ?: FloatArray(0),
        )
    }

    /**
     * The native `exportWallFeatureMap` blob, little-endian: `n, descRows, descCols, descType` (int32),
     * points (`n·3` float32), confidence (`n` float32), observations (`n` int32), anchor (16 float32,
     * zeros when unset), intrinsics (4 float32), descriptor bytes. Empty for an empty map.
     */
    fun toBytes(): ByteArray {
        if (points.isEmpty()) return ByteArray(0)
        val s = snapshot()
        val n = points.size
        val buf = ByteBuffer.allocate(16 + n * 12 + n * 4 + n * 4 + 64 + 16 + s.descriptorsData.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(n).putInt(s.descriptorsRows).putInt(s.descriptorsCols).putInt(s.descriptorsType)
        s.points3d.forEach { buf.putFloat(it) }
        s.confidence.forEach { buf.putFloat(it) }
        s.obsCount.forEach { buf.putInt(it) }
        val a = if (s.anchor.size == 16) s.anchor else FloatArray(16)
        a.forEach { buf.putFloat(it) }
        val k = if (s.intrinsics.size == 4) s.intrinsics else FloatArray(4)
        k.forEach { buf.putFloat(it) }
        buf.put(s.descriptorsData)
        return buf.array()
    }

    /**
     * Native-compatible descriptors as an OpenCV [Mat] ([CvType.CV_8U] or [CvType.CV_32F] rows), or
     * null for an empty map / an unsupported type. Requires OpenCV's native library.
     */
    fun descriptorMat(indices: IntArray? = null): Mat? {
        if (descriptors.isEmpty()) return null
        val rows = indices ?: IntArray(descriptors.size) { it }
        if (rows.isEmpty()) return null
        return when (CvType.depth(descriptorType)) {
            CvType.CV_8U -> Mat(rows.size, descriptorCols, descriptorType).also { mat ->
                val data = ByteArray(rows.size * descriptors[0].size)
                rows.forEachIndexed { r, i -> System.arraycopy(descriptors[i], 0, data, r * descriptors[0].size, descriptors[0].size) }
                mat.put(0, 0, data)
            }
            CvType.CV_32F -> Mat(rows.size, descriptorCols, descriptorType).also { mat ->
                val perRow = descriptors[0].size / 4
                val data = FloatArray(rows.size * perRow)
                rows.forEachIndexed { r, i ->
                    ByteBuffer.wrap(descriptors[i]).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(data, r * perRow, perRow)
                }
                mat.put(0, 0, data)
            }
            else -> null
        }
    }

    /**
     * Match frame descriptors against the map (k = 2, Lowe ratio): per frame row, the matched map
     * index or -1. Hamming for binary descriptors, L2 otherwise. Requires OpenCV's native library.
     */
    fun matchDescriptors(frameDescriptors: Mat, loweRatio: Float = Relocalizer.DEFAULT_LOWE_RATIO): IntArray {
        val out = IntArray(frameDescriptors.rows()) { -1 }
        if (points.size < 2 || frameDescriptors.empty() || frameDescriptors.type() != descriptorType) return out
        val mapDescriptors = descriptorMat() ?: return out
        val matcher = DescriptorMatcher.create(
            if (CvType.depth(descriptorType) == CvType.CV_8U) DescriptorMatcher.BRUTEFORCE_HAMMING else DescriptorMatcher.BRUTEFORCE,
        )
        val knn = mutableListOf<MatOfDMatch>()
        try {
            matcher.knnMatch(frameDescriptors, mapDescriptors, knn, 2)
            for (m in knn) {
                val arr = m.toArray()
                if (arr.size < 2) continue
                if (arr[0].distance < loweRatio * arr[1].distance && arr[0].queryIdx in out.indices) {
                    out[arr[0].queryIdx] = arr[0].trainIdx
                }
            }
        } finally {
            knn.forEach { it.release() }
            mapDescriptors.release()
        }
        return out
    }

    /**
     * [grow] from a [RelocResult] and the frame's features, pruning and matching first (native
     * order). Requires OpenCV's native library.
     */
    fun growFromReloc(
        result: RelocResult,
        features: Relocalizer.FrameFeatures,
        wallPlane: WallPlane,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        anchor: FloatArray? = null,
        depth: InverseDepthMap? = null,
        loweRatio: Float = Relocalizer.DEFAULT_LOWE_RATIO,
    ): Growth? {
        val kps = features.keypoints.toArray()
        val desc = features.descriptors
        if (kps.isEmpty() || desc.rows() != kps.size) return null
        pruneIfFull()
        val matches = matchDescriptors(desc, loweRatio)
        val uv = FloatArray(kps.size * 2) { if (it % 2 == 0) kps[it / 2].pt.x.toFloat() else kps[it / 2].pt.y.toFloat() }
        val bytes = when (CvType.depth(desc.type())) {
            CvType.CV_8U -> ByteArray((desc.total() * desc.elemSize()).toInt()).also { desc.get(0, 0, it) }
            CvType.CV_32F -> FloatArray((desc.total() * desc.channels()).toInt()).also { desc.get(0, 0, it) }.let { f ->
                ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> f.forEach { b.putFloat(it) } }.array()
            }
            else -> return null
        }
        return grow(result.cameraFromObject, uv, bytes, desc.cols(), desc.type(), matches, wallPlane, fx, fy, cx, cy, anchor, depth)
    }

    /**
     * The map (or the [indices] subset, e.g. [visibleIndices]) as a [Fingerprint] for [Relocalizer],
     * optionally appended to [base] (same descriptor type) so one PnP solve uses marks and map
     * together. Null when there is nothing to match. Requires OpenCV's native library; release the
     * returned Mats when done.
     */
    fun toFingerprint(indices: IntArray? = null, base: Fingerprint? = null): Fingerprint? {
        val rows = indices ?: IntArray(points.size) { it }
        val mapDesc = if (rows.isEmpty()) null else descriptorMat(rows)
        val mapPts = rows.map { i -> points[i].let { Point3(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) } }
        val binary = CvType.depth(descriptorType) == CvType.CV_8U
        if (base == null || base.descriptors.empty()) {
            mapDesc ?: return null
            return SimpleFingerprint(mapDesc, MatOfPoint3f().apply { fromList(mapPts) }, binary)
        }
        if (mapDesc == null || base.descriptors.type() != descriptorType) {
            mapDesc?.release()
            return SimpleFingerprint(base.descriptors.clone(), MatOfPoint3f(*base.points3d.toArray()), base.binaryDescriptors)
        }
        val merged = Mat()
        org.opencv.core.Core.vconcat(listOf(base.descriptors, mapDesc), merged)
        mapDesc.release()
        val allPts = base.points3d.toList() + mapPts
        return SimpleFingerprint(merged, MatOfPoint3f().apply { fromList(allPts) }, base.binaryDescriptors)
    }

    private class SimpleFingerprint(
        override val descriptors: Mat,
        override val points3d: MatOfPoint3f,
        override val binaryDescriptors: Boolean,
    ) : Fingerprint

    companion object {
        /** Native cap on map points. */
        const val DEFAULT_CAPACITY = 5000

        /** Points below this confidence are pruned at capacity. */
        const val PRUNE_CONFIDENCE = 0.2f

        /** Confidence of a newly added point. */
        const val NEW_POINT_CONFIDENCE = 0.1f

        /** Rebuild a map from a [Snapshot] (validates the parallel arrays like the app model does). */
        fun fromSnapshot(snapshot: Snapshot, capacity: Int = DEFAULT_CAPACITY): WallFeatureMap {
            val rows = snapshot.descriptorsRows
            require(rows >= 0 && snapshot.descriptorsCols >= 0)
            require(snapshot.points3d.size.toLong() == rows * 3L) { "points3d must be 3 * descriptorsRows" }
            require(rows == 0 || snapshot.descriptorsData.size % rows == 0) { "descriptorsData must divide into rows" }
            require(snapshot.confidence.isEmpty() || snapshot.confidence.size == rows)
            require(snapshot.obsCount.isEmpty() || snapshot.obsCount.size == rows)
            require(snapshot.anchor.isEmpty() || snapshot.anchor.size == 16)
            require(snapshot.intrinsics.isEmpty() || snapshot.intrinsics.size == 4)
            val map = WallFeatureMap(capacity)
            val rowBytes = if (rows == 0) 0 else snapshot.descriptorsData.size / rows
            for (i in 0 until rows) {
                map.points += floatArrayOf(snapshot.points3d[3 * i], snapshot.points3d[3 * i + 1], snapshot.points3d[3 * i + 2])
                // Optional arrays absent in older maps: align them rather than desync (native behaviour).
                map.confidence += if (snapshot.confidence.isEmpty()) 1f else snapshot.confidence[i]
                map.observations += if (snapshot.obsCount.isEmpty()) 1 else snapshot.obsCount[i]
                map.descriptors += snapshot.descriptorsData.copyOfRange(i * rowBytes, (i + 1) * rowBytes)
            }
            if (rows > 0) {
                map.descriptorCols = snapshot.descriptorsCols
                map.descriptorType = snapshot.descriptorsType
            }
            map.anchorValue = snapshot.anchor.takeIf { it.size == 16 }?.copyOf()
            map.intrinsicsValue = snapshot.intrinsics.takeIf { it.size == 4 }?.copyOf()
            return map
        }

        /** Parse a [toBytes] / native `exportWallFeatureMap` blob; null when malformed. */
        fun fromBytes(bytes: ByteArray, capacity: Int = DEFAULT_CAPACITY): WallFeatureMap? {
            if (bytes.isEmpty()) return WallFeatureMap(capacity)
            if (bytes.size < 16) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = buf.int; val rows = buf.int; val cols = buf.int; val type = buf.int
            if (n < 0 || rows != n || cols < 0) return null
            val fixed = 16L + n * 20L + 80L
            if (bytes.size < fixed) return null
            val descBytes = bytes.size - fixed
            if (n > 0 && descBytes % n != 0L) return null
            val pts = FloatArray(n * 3) { buf.float }
            val conf = FloatArray(n) { buf.float }
            val obs = IntArray(n) { buf.int }
            val anchor = FloatArray(16) { buf.float }
            val intr = FloatArray(4) { buf.float }
            val desc = ByteArray(descBytes.toInt()).also { buf.get(it) }
            return runCatching {
                fromSnapshot(
                    Snapshot(pts, desc, rows, cols, type, conf, obs, anchor, intr),
                    capacity,
                )
            }.getOrNull()
        }

        private fun columnMajor(rowMajor: FloatArray): FloatArray =
            FloatArray(16) { i -> rowMajor[(i % 4) * 4 + i / 4] }

        private fun invertRigid(m: FloatArray): FloatArray {
            val out = FloatArray(16)
            for (r in 0..2) for (c in 0..2) out[c * 4 + r] = m[r * 4 + c]
            for (r in 0..2) out[12 + r] = -(out[r] * m[12] + out[4 + r] * m[13] + out[8 + r] * m[14])
            out[15] = 1f
            return out
        }
    }
}

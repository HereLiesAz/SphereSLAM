package com.hereliesaz.sphereslam.reloc

import android.os.SystemClock
import com.hereliesaz.sphereslam.attitude.AttitudeRotationBridge
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.math.RigidMath
import com.hereliesaz.sphereslam.math.RotationMath
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Live planar tracking of one captured reference image, with a short gyro bridge: frame in, OpenGL
 * view matrix out. The drop-in replacement for GraffitiXR's native `HomographyTracker` +
 * `HomographyArTracker` + `BridgedHomographyTracker` stack.
 *
 * Vision is the same algorithm the native tracker ran, on SphereSLAM's [Relocalizer]: ORB, Lowe ratio
 * matching, `solvePnPRansac`, then the IPPE planar-flip refine, against a [PlanarFingerprint] whose
 * object points span exactly the caller's half-extents ([PlanarGeometry.pixelToExtents]) — so the pose
 * is in the units the design quad is drawn in. A solve is accepted only when it has at least
 * [Config.minMatches] matches, [Config.minInliers] inliers, an inlier ratio of
 * [Config.minInlierRatio] and inliers spanning [Config.minInlierSpreadFraction] of the frame on each
 * axis (a tight cluster can pass every count gate with an unconstrained tilt).
 *
 * After a vision lock, a miss is bridged for at most [Config.maxBridgeMs] by rotating the held pose
 * about its camera centre with the attitude change ([AttitudeRotationBridge]); its confidence decays
 * linearly to zero across the window. Translation is never extrapolated.
 *
 * Output: [Pose.viewMatrix] is column-major OpenGL camera-from-plane (the plane's +X right, +Y up,
 * `z = 0`), usable directly as a renderer's view matrix with a quad of the same half-extents.
 *
 * **Native prerequisite:** OpenCV's Java native library must be loaded (as for [Relocalizer]).
 * Not thread-safe; drive from one camera worker and [close] when done.
 *
 * @param nowMs monotonic millisecond clock for the bridge window.
 */
@ExperimentalSphereSlamRelocApi
class PlanarTracker internal constructor(
    private val config: Config,
    private val nowMs: () -> Long,
    private val solver: PlanarSolver,
) : AutoCloseable {

    constructor(config: Config = Config(), nowMs: () -> Long = { SystemClock.elapsedRealtime() }) :
        this(config, nowMs, OpenCvPlanarSolver(config))

    /** Gates and parameters; defaults are GraffitiXR's native `HomographyTracker` constants. */
    data class Config(
        val maxFeatures: Int = 1500,
        val minReferenceFeatures: Int = 30,
        val minMatches: Int = 12,
        val minInliers: Int = 8,
        val minInlierRatio: Float = 0.35f,
        val minInlierSpreadFraction: Float = 0.25f,
        val loweRatio: Float = 0.75f,
        val ransacReprojectionThresholdPx: Float = 8f,
        val planarRefine: Boolean = true,
        val maxBridgeMs: Long = 400L,
    ) {
        init {
            require(maxFeatures > 0 && minReferenceFeatures >= 4)
            require(minInliers >= 4 && minMatches >= minInliers)
            require(minInlierRatio in 0f..1f && minInlierSpreadFraction in 0f..1f)
            require(loweRatio > 0f && loweRatio <= 1f && ransacReprojectionThresholdPx > 0f)
            require(maxBridgeMs >= 0L)
        }
    }

    /** Where a [Pose] came from. */
    enum class Source { VISION, GYRO_BRIDGE }

    /**
     * One tracked pose.
     *
     * @property confidence inlier ratio in `[0, 1]` for [Source.VISION]; decayed across the bridge
     *   window for [Source.GYRO_BRIDGE].
     */
    class Pose internal constructor(
        viewMatrix: FloatArray,
        val confidence: Float,
        val source: Source,
        val inliers: Int,
        val matches: Int,
        val reprojectionErrorPx: Float,
    ) {
        private val view = viewMatrix.copyOf()

        /** Column-major OpenGL camera-from-plane (a fresh copy). */
        val viewMatrix: FloatArray get() = view.copyOf()
    }

    private var lastGood: Pose? = null
    private var bridge: AttitudeRotationBridge? = null
    private var bridgeAtMs = -1L
    private var hasReferenceValue = false
    private var closed = false

    /** True once [setReference] succeeded and neither [reset] nor a failed [setReference] followed. */
    val hasReference: Boolean get() = hasReferenceValue

    /**
     * Replace the reference with a tightly packed luma image spanning `[-halfWidth, halfWidth] ×
     * [-halfHeight, halfHeight]`. Returns false (no reference) when it yields fewer than
     * [Config.minReferenceFeatures] features.
     */
    fun setReference(luma: ByteArray, width: Int, height: Int, halfWidth: Float, halfHeight: Float): Boolean {
        check(!closed) { "PlanarTracker is closed" }
        require(width > 0 && height > 0 && luma.size >= width * height)
        require(halfWidth > 0f && halfHeight > 0f && halfWidth.isFinite() && halfHeight.isFinite())
        clearBridge()
        val features = solver.setReference(luma, width, height, halfWidth, halfHeight)
        hasReferenceValue = features >= config.minReferenceFeatures
        if (!hasReferenceValue) solver.clearReference()
        return hasReferenceValue
    }

    /** Drop the reference and bridge state. */
    fun reset() {
        clearBridge()
        hasReferenceValue = false
        if (!closed) solver.clearReference()
    }

    /**
     * Track one tightly packed luma frame.
     *
     * @param attitude device attitude sampled just before this frame is solved, or null (no bridge).
     * @param cameraFromDevice row-major 3×3 GL-camera-from-device of the tracked image
     *   ([com.hereliesaz.sphereslam.attitude.DeviceCameraRotation.cameraFromDevice]).
     * @return a vision pose, a gyro-bridged hold of the last one, or null.
     */
    fun track(
        luma: ByteArray,
        width: Int,
        height: Int,
        intrinsics: CameraIntrinsics,
        attitude: AttitudeSample? = null,
        cameraFromDevice: FloatArray = RotationMath.IDENTITY_3X3,
    ): Pose? {
        check(!closed) { "PlanarTracker is closed" }
        require(width > 0 && height > 0 && luma.size >= width * height)
        require(intrinsics.isValid) { "intrinsics must be valid" }
        require(cameraFromDevice.size == 9)
        if (!hasReferenceValue) return null
        val atMs = nowMs()
        val solve = solver.solve(luma, width, height, intrinsics)
        val vision = solve?.let { accept(it, width, height) }
        if (vision != null) {
            lastGood = vision
            if (attitude != null) {
                bridge = AttitudeRotationBridge(cameraFromDevice).also { it.markReference(attitude) }
                bridgeAtMs = atMs
            } else {
                // No attitude for this lock: an older bridge reference would not match the new pose.
                bridge = null
                bridgeAtMs = -1L
            }
            return vision
        }

        val held = lastGood ?: return null
        val active = bridge
        val elapsed = if (active == null || bridgeAtMs < 0L) -1L else nowMs() - bridgeAtMs
        if (elapsed < 0L || elapsed > config.maxBridgeMs) {
            lastGood = null
            return null
        }
        val delta = active?.cameraRotationDelta(attitude?.quaternion) ?: return null
        val decay = if (config.maxBridgeMs == 0L) 0f else 1f - elapsed.toFloat() / config.maxBridgeMs.toFloat()
        return Pose(
            viewMatrix = RotationMath.rotateAboutCameraCentre(held.viewMatrix, delta),
            confidence = (held.confidence * decay).coerceAtLeast(0f),
            source = Source.GYRO_BRIDGE,
            inliers = held.inliers,
            matches = held.matches,
            reprojectionErrorPx = held.reprojectionErrorPx,
        )
    }

    private fun accept(s: PlanarSolve, width: Int, height: Int): Pose? {
        if (s.matches < config.minMatches || s.inliers < config.minInliers) return null
        val ratio = s.inliers.toFloat() / s.matches.toFloat()
        if (ratio < config.minInlierRatio) return null
        val b = s.inlierBounds
        if ((b[2] - b[0]) / width < config.minInlierSpreadFraction || (b[3] - b[1]) / height < config.minInlierSpreadFraction) {
            return null
        }
        if (s.cameraFromObjectCv.size != 16 || s.cameraFromObjectCv.any { !it.isFinite() }) return null
        return Pose(
            viewMatrix = RigidMath.cvRowMajorToGlColumnMajor(s.cameraFromObjectCv),
            confidence = ratio,
            source = Source.VISION,
            inliers = s.inliers,
            matches = s.matches,
            reprojectionErrorPx = s.reprojectionErrorPx,
        )
    }

    private fun clearBridge() {
        lastGood = null
        bridge = null
        bridgeAtMs = -1L
    }

    override fun close() {
        if (closed) return
        closed = true
        clearBridge()
        hasReferenceValue = false
        solver.close()
    }
}

/** One raw planar solve: row-major OpenCV camera-from-object plus its evidence. */
@ExperimentalSphereSlamRelocApi
internal class PlanarSolve(
    val cameraFromObjectCv: FloatArray,
    val matches: Int,
    val inliers: Int,
    val reprojectionErrorPx: Float,
    /** `[minU, minV, maxU, maxV]` of the inliers. */
    val inlierBounds: FloatArray,
)

/** The vision front end of [PlanarTracker] (OpenCV in production, a fake in tests). */
@ExperimentalSphereSlamRelocApi
internal interface PlanarSolver : AutoCloseable {
    /** Replace the reference; returns its feature count. */
    fun setReference(luma: ByteArray, width: Int, height: Int, halfWidth: Float, halfHeight: Float): Int
    fun clearReference()
    fun solve(luma: ByteArray, width: Int, height: Int, intrinsics: CameraIntrinsics): PlanarSolve?
}

/** [Relocalizer] + [PlanarFingerprint.fromReferenceExtents]; rebuilds the relocalizer when intrinsics change. */
@ExperimentalSphereSlamRelocApi
internal class OpenCvPlanarSolver(private val config: PlanarTracker.Config) : PlanarSolver {
    private var fingerprint: PlanarFingerprint? = null
    private var relocalizer: Relocalizer? = null
    private var relocalizerKey: CameraIntrinsics? = null

    override fun setReference(luma: ByteArray, width: Int, height: Int, halfWidth: Float, halfHeight: Float): Int {
        clearReference()
        val gray = grayMat(luma, width, height)
        try {
            val fp = PlanarFingerprint.fromReferenceExtents(gray, halfWidth, halfHeight, config.maxFeatures)
            fingerprint = fp
            return if (fp.descriptors.empty()) 0 else fp.descriptors.rows()
        } finally {
            gray.release()
        }
    }

    override fun clearReference() {
        fingerprint?.let {
            it.descriptors.release(); it.points3d.release(); it.keypoints.release()
        }
        fingerprint = null
    }

    override fun solve(luma: ByteArray, width: Int, height: Int, intrinsics: CameraIntrinsics): PlanarSolve? {
        val fp = fingerprint ?: return null
        val reloc = relocalizerFor(intrinsics)
        val gray = grayMat(luma, width, height)
        try {
            val features = reloc.detect(gray) ?: return null
            try {
                val detail = reloc.relocalizeDetailed(features.keypoints, features.descriptors, fp) ?: return null
                return PlanarSolve(
                    cameraFromObjectCv = detail.result.cameraFromObject,
                    matches = detail.matches,
                    inliers = detail.result.inliers,
                    reprojectionErrorPx = detail.result.reprojectionErrorPx,
                    inlierBounds = detail.inlierBounds,
                )
            } finally {
                features.release()
            }
        } finally {
            gray.release()
        }
    }

    private fun relocalizerFor(k: CameraIntrinsics): Relocalizer {
        val existing = relocalizer
        if (existing != null && k == relocalizerKey) return existing
        existing?.close()
        return Relocalizer(
            fx = k.fx.toDouble(),
            fy = k.fy.toDouble(),
            cx = k.cx.toDouble(),
            cy = k.cy.toDouble(),
            loweRatio = config.loweRatio,
            minInliers = config.minInliers,
            maxFeatures = config.maxFeatures,
            ransacReprojectionThresholdPx = config.ransacReprojectionThresholdPx,
            planarRefine = config.planarRefine,
        ).also {
            relocalizer = it
            relocalizerKey = k
        }
    }

    private fun grayMat(luma: ByteArray, width: Int, height: Int): Mat =
        Mat(height, width, CvType.CV_8UC1).also { it.put(0, 0, luma.copyOf(width * height)) }

    override fun close() {
        clearReference()
        relocalizer?.close()
        relocalizer = null
        relocalizerKey = null
    }
}

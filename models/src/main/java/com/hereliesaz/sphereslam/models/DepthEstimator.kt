package com.hereliesaz.sphereslam.models

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/** A single monocular depth map, relative (unitless) unless the model is a metric variant. */
@ExperimentalSphereSlamModelsApi
data class DepthMap(
    val width: Int,
    val height: Int,
    /** Row-major `height * width` values. MiDaS outputs INVERSE depth: larger = nearer. */
    val data: FloatArray,
) {
    init {
        require(data.size == width * height) { "depth data ${data.size} != $width*$height" }
    }
}

/**
 * Monocular depth via ONNX Runtime (CPU). Ships wired to **MiDaS v2.1 Small (256×256, int8)**, the
 * default asset [MODEL_ASSET]. Its inverse-depth output is a **building block** for placing off-wall
 * map points radially (calibrated by `:reloc`'s `DepthScaleFit`); that depth-backed placement is
 * not yet wired into the relocalizer or session.
 *
 * ## Swapping the model
 * This wrapper is model-agnostic about everything but the ImageNet NCHW preprocessing and the
 * single depth output. To use your own:
 * 1. Drop your `.onnx` in `models/src/main/assets/` (or load from a file path — see [loadFrom]).
 * 2. Construct `DepthEstimator(context, assetName = "your_model.onnx", inputSize = N)`.
 * 3. If your model expects a different normalization, override [MEAN]/[STD] via the constructor.
 * A metric-depth model works too — the output is then absolute, and callers can skip the affine
 * scale fit.
 *
 * Fails soft: a missing asset or load error leaves [isLoaded] false and [estimate] returning null.
 * All public methods are synchronized; ORT sessions are not re-entrant.
 */
@ExperimentalSphereSlamModelsApi
class DepthEstimator(
    private val appContext: Context,
    private val assetName: String = MODEL_ASSET,
    private val inputSize: Int = DEFAULT_INPUT,
    private val mean: FloatArray = MEAN,
    private val std: FloatArray = STD,
) : AutoCloseable {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var loadedPath: String? = null

    @Volatile
    var isLoaded: Boolean = false
        private set

    /** Reason the last [load] failed, for a diagnostic overlay. Null once loaded. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Extract the bundled asset to filesDir (refreshing a stale copy) and open the ORT session.
     * Idempotent while the asset model is open; replaces a model opened via [loadFrom]. Returns [isLoaded].
     */
    @Synchronized
    fun load(): Boolean {
        return try {
            val dir = File(appContext.filesDir, MODEL_DIR).apply { mkdirs() }
            val graph = File(dir, assetName)
            if (isLoaded && loadedPath == graph.absolutePath) return true
            if (isLoaded) close() // a different model (e.g. via loadFrom) is open; switch to the asset
            if (!ModelAssets.copyAssetIfNeeded(appContext, assetName, graph, TAG)) {
                lastError = "asset '$assetName' absent (copy failed)"
                return false
            }
            openSession(graph.absolutePath)
        } catch (t: Throwable) {
            failSoft(t)
            false
        }
    }

    /**
     * Open the session from an arbitrary on-disk `.onnx` (for a user-supplied model outside assets).
     * Idempotent for the same path; a different path closes the current session and loads the new one.
     */
    @Synchronized
    fun loadFrom(onnxPath: String): Boolean {
        if (isLoaded && loadedPath == onnxPath) return true
        if (isLoaded) close()
        return try {
            openSession(onnxPath)
        } catch (t: Throwable) {
            failSoft(t)
            false
        }
    }

    private fun openSession(path: String): Boolean {
        val environment = OrtEnvironment.getEnvironment()
        session = environment.createSession(path, OrtSession.SessionOptions())
        env = environment
        loadedPath = path
        isLoaded = true
        lastError = null
        Log.i(TAG, "loaded $path (inputs=${session?.inputNames} outputs=${session?.outputNames})")
        return true
    }

    private fun failSoft(t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message?.take(160) ?: "no message"}"
        Log.w(TAG, "load failed; depth disabled", t)
        close()
    }

    /**
     * Estimate a depth map for [bitmap], downscaled to at most [outMaxDim] on its long side. Null if
     * not loaded or inference fails.
     */
    @Synchronized
    fun estimate(bitmap: Bitmap, outMaxDim: Int = DEFAULT_OUT_MAX_DIM): DepthMap? {
        require(outMaxDim > 0) { "outMaxDim must be positive" }
        val s = session ?: return null
        val environment = env ?: return null
        return try {
            val inputName = s.inputNames.first()
            preprocess(bitmap, environment).use { input ->
                s.run(mapOf(inputName to input)).use { results ->
                    readDepth(results[0] as OnnxTensor, outMaxDim)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "inference failed", t)
            null
        }
    }

    /**
     * Build the `[1,3,N,N]` ImageNet-normalized NCHW tensor. The frame is resized to `N×N` without
     * letterboxing, so a non-square frame's aspect is squashed; the depth map comes back in that
     * squashed `N×N` grid (callers map it to frame pixels per axis: `u·W/N`, `v·H/N`).
     */
    private fun preprocess(bitmap: Bitmap, environment: OrtEnvironment): OnnxTensor {
        val n = inputSize
        val scaled = Bitmap.createScaledBitmap(bitmap, n, n, true)
        val pixels = IntArray(n * n)
        try {
            scaled.getPixels(pixels, 0, n, 0, 0, n, n)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        val plane = n * n
        val chw = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            chw[i] = (r - mean[0]) / std[0]
            chw[plane + i] = (g - mean[1]) / std[1]
            chw[2 * plane + i] = (b - mean[2]) / std[2]
        }
        return OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(chw),
            longArrayOf(1, 3, n.toLong(), n.toLong()),
        )
    }

    /** Read `[1,H,W]` or `[1,1,H,W]` and nearest-neighbour downscale so the long side is `<= outMaxDim`. */
    private fun readDepth(out: OnnxTensor, outMaxDim: Int): DepthMap {
        val shape = out.info.shape
        val h: Int
        val w: Int
        when (shape.size) {
            4 -> { h = shape[2].toInt(); w = shape[3].toInt() }
            3 -> { h = shape[1].toInt(); w = shape[2].toInt() }
            else -> error("unexpected depth output rank ${shape.size}")
        }
        val src = out.floatBuffer
        val (scale, dims) = downscale(h, w, outMaxDim)
        val (oh, ow) = dims
        val dst = FloatArray(ow * oh)
        for (y in 0 until oh) {
            val sy = y * scale
            for (x in 0 until ow) dst[y * ow + x] = src.get(sy * w + x * scale)
        }
        return DepthMap(ow, oh, dst)
    }

    @Synchronized
    override fun close() {
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
        loadedPath = null
        env = null // OrtEnvironment is a process-global singleton; do not close it here.
        isLoaded = false
    }

    companion object {
        private const val TAG = "DepthEstimator"
        const val MODEL_ASSET = "midas_v21_small_256_int8.onnx"
        const val DEFAULT_INPUT = 256
        const val DEFAULT_OUT_MAX_DIM = 128
        private const val MODEL_DIR = "sphereslam-models"
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /**
         * Integer nearest-neighbour stride and resulting `(height, width)` for an `h×w` map so that
         * the long side is at most [outMaxDim]: stride = `ceil(max(h, w) / outMaxDim)` (>= 1). Pure.
         */
        internal fun downscale(h: Int, w: Int, outMaxDim: Int): Pair<Int, Pair<Int, Int>> {
            val longSide = maxOf(h, w)
            val stride = maxOf(1, (longSide + outMaxDim - 1) / outMaxDim)
            return stride to ((h / stride) to (w / stride))
        }
    }
}

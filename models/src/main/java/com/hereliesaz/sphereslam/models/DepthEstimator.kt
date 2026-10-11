package com.hereliesaz.sphereslam.models

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * ONNX Runtime session settings for [DepthEstimator].
 *
 * @property intraOpThreads threads ONNX Runtime may use inside one operator. Default 2, so depth
 *   inference does not compete with the camera-analysis thread for every core; 0 keeps ONNX
 *   Runtime's own default (one per core). With [ExecutionProvider.XNNPACK] the count goes to
 *   XNNPACK's pool and ORT's own pool is pinned to 1, as ONNX Runtime recommends.
 * @property executionProvider provider to try first. If it cannot be added (not in this ORT build,
 *   unsupported device) or the session fails to open with it, the estimator falls back to plain CPU
 *   and reports what it got in [DepthEstimator.activeExecutionProvider].
 * @property allowSpinning whether idle ORT worker threads busy-wait for work. Off by default: spinning
 *   buys a little latency at the cost of CPU the tracker needs.
 */
@ExperimentalSphereSlamModelsApi
data class DepthEstimatorOptions(
    val intraOpThreads: Int = DEFAULT_INTRA_OP_THREADS,
    val executionProvider: ExecutionProvider = ExecutionProvider.CPU,
    val allowSpinning: Boolean = false,
) {
    init {
        require(intraOpThreads >= 0) { "intraOpThreads must be >= 0, was $intraOpThreads" }
    }

    /** ONNX Runtime execution providers [DepthEstimator] can request. */
    enum class ExecutionProvider {
        /** ONNX Runtime's default CPU kernels. Always available. */
        CPU,

        /** XNNPACK's optimized CPU kernels (float and int8 mobile models). */
        XNNPACK,

        /** Android NNAPI (GPU/DSP/NPU where the vendor driver supports the ops; deprecated on API 35+). */
        NNAPI,
    }

    /** The session-option calls ONNX Runtime receives, abstracted so the mapping is unit-testable. */
    internal interface Target {
        fun setIntraOpNumThreads(threads: Int)
        fun addConfigEntry(key: String, value: String)
        fun addXnnpack(options: Map<String, String>)
        fun addNnapi()
    }

    /**
     * Apply these options for [provider] (the requested one, or [ExecutionProvider.CPU] on
     * fallback). Throws whatever the target throws when the provider cannot be added.
     */
    internal fun applyTo(target: Target, provider: ExecutionProvider) {
        target.addConfigEntry(ALLOW_SPINNING_KEY, if (allowSpinning) "1" else "0")
        when (provider) {
            ExecutionProvider.CPU -> if (intraOpThreads > 0) target.setIntraOpNumThreads(intraOpThreads)
            ExecutionProvider.XNNPACK -> {
                target.setIntraOpNumThreads(1)
                target.addXnnpack(mapOf(XNNPACK_THREADS_KEY to intraOpThreads.toString()))
            }
            ExecutionProvider.NNAPI -> {
                // Ops NNAPI does not take still run on ORT's CPU pool.
                if (intraOpThreads > 0) target.setIntraOpNumThreads(intraOpThreads)
                target.addNnapi()
            }
        }
    }

    /** Providers to try, in order: the requested one, then plain CPU. */
    internal fun attempts(): List<ExecutionProvider> = listOf(executionProvider, ExecutionProvider.CPU).distinct()

    companion object {
        const val DEFAULT_INTRA_OP_THREADS = 2
        internal const val ALLOW_SPINNING_KEY = "session.intra_op.allow_spinning"
        internal const val XNNPACK_THREADS_KEY = "intra_op_num_threads"

        /**
         * Open with each of [attempts] in turn: the first provider whose [open] succeeds wins; when
         * every attempt fails the last failure is thrown. [onFallback] sees each failed attempt.
         */
        internal fun <S> openWithFallback(
            attempts: List<ExecutionProvider>,
            open: (ExecutionProvider) -> S,
            onFallback: (ExecutionProvider, Throwable) -> Unit = { _, _ -> },
        ): Pair<S, ExecutionProvider> {
            var last: Throwable? = null
            for (provider in attempts) {
                try {
                    return open(provider) to provider
                } catch (t: Throwable) {
                    if (t is VirtualMachineError) throw t
                    last = t
                    onFallback(provider, t)
                }
            }
            throw last ?: IllegalStateException("no execution provider to try")
        }
    }
}

/**
 * Monocular depth via ONNX Runtime (CPU by default, two intra-op threads; see
 * [DepthEstimatorOptions] for XNNPACK / NNAPI with automatic CPU fallback). Ships wired to **MiDaS v2.1 Small (256×256, int8)**, the
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
 * Input: an ARGB [Bitmap] ([estimate]) or, with no bitmap round trip, a grey luma plane such as the
 * tracking frame itself ([estimateLuma]; the grey value is fed to all three channels).
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
    private val options: DepthEstimatorOptions = DepthEstimatorOptions(),
) : AutoCloseable {

    /** The pre-options constructor (binary compatibility); uses default [DepthEstimatorOptions]. */
    constructor(
        appContext: Context,
        assetName: String,
        inputSize: Int,
        mean: FloatArray,
        std: FloatArray,
    ) : this(appContext, assetName, inputSize, mean, std, DepthEstimatorOptions())

    /** The execution provider the open session actually uses, or null when not loaded. */
    @Volatile
    var activeExecutionProvider: DepthEstimatorOptions.ExecutionProvider? = null
        private set

    // Reused input tensor storage (direct, so ORT reads it without a copy) for the luma path.
    private var inputBuffer: FloatBuffer? = null
    private var inputScratch: FloatArray? = null

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
        val (opened, provider) = DepthEstimatorOptions.openWithFallback(
            options.attempts(),
            open = { p -> sessionOptions(p).use { environment.createSession(path, it) } },
            onFallback = { p, t -> Log.w(TAG, "session with $p unavailable; falling back", t) },
        )
        session = opened
        activeExecutionProvider = provider
        env = environment
        loadedPath = path
        isLoaded = true
        lastError = null
        Log.i(TAG, "loaded $path provider=$provider threads=${options.intraOpThreads} (inputs=${session?.inputNames} outputs=${session?.outputNames})")
        return true
    }

    private fun sessionOptions(provider: DepthEstimatorOptions.ExecutionProvider): OrtSession.SessionOptions {
        val o = OrtSession.SessionOptions()
        try {
            options.applyTo(
                object : DepthEstimatorOptions.Target {
                    override fun setIntraOpNumThreads(threads: Int) = o.setIntraOpNumThreads(threads)
                    override fun addConfigEntry(key: String, value: String) = o.addConfigEntry(key, value)
                    override fun addXnnpack(options: Map<String, String>) = o.addXnnpack(options)
                    override fun addNnapi() = o.addNnapi()
                },
                provider,
            )
        } catch (t: Throwable) {
            o.close()
            throw t
        }
        return o
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
     * Estimate a depth map straight from a grey luma plane ([width] × [height], row stride
     * [rowStride]) — e.g. the display-upright tracking frame — with no ARGB bitmap round trip: the
     * plane is bilinearly resampled to the model's `N×N` input and the grey value fed to all three
     * channels. Same output grid and semantics as [estimate]. Null if not loaded or inference fails.
     * [luma] is only read during the call.
     */
    @JvmOverloads
    @Synchronized
    fun estimateLuma(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        outMaxDim: Int = DEFAULT_OUT_MAX_DIM,
    ): DepthMap? {
        require(outMaxDim > 0) { "outMaxDim must be positive" }
        require(width > 0 && height > 0 && rowStride >= width) { "bad luma geometry ${width}x$height stride $rowStride" }
        require(luma.size >= (height - 1) * rowStride + width) { "luma too small for ${width}x$height stride $rowStride" }
        val s = session ?: return null
        val environment = env ?: return null
        return try {
            val n = inputSize
            val count = 3 * n * n
            val chw = inputScratch?.takeIf { it.size == count } ?: FloatArray(count).also { inputScratch = it }
            lumaToNchw(luma, width, height, rowStride, n, mean, std, chw)
            val buffer = inputBuffer?.takeIf { it.capacity() == count }
                ?: ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { inputBuffer = it }
            buffer.clear()
            buffer.put(chw)
            buffer.flip()
            val inputName = s.inputNames.first()
            OnnxTensor.createTensor(environment, buffer, longArrayOf(1, 3, n.toLong(), n.toLong())).use { input ->
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
        activeExecutionProvider = null
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
         * Bilinearly resample a grey plane to `n×n` (pixel-centre aligned, like a filtered
         * `Bitmap.createScaledBitmap`) and write it as a normalized `[3, n, n]` NCHW block into [out]
         * (length >= `3·n·n`), the grey value `v/255` in every channel as `(v − mean[c]) / std[c]`.
         * Pure and allocation-free.
         */
        internal fun lumaToNchw(
            luma: ByteArray,
            width: Int,
            height: Int,
            rowStride: Int,
            n: Int,
            mean: FloatArray,
            std: FloatArray,
            out: FloatArray,
        ) {
            require(n > 0 && out.size >= 3 * n * n)
            val plane = n * n
            val sx = width.toFloat() / n
            val sy = height.toFloat() / n
            val inv0 = 1f / std[0]
            val inv1 = 1f / std[1]
            val inv2 = 1f / std[2]
            val maxX = (width - 1).toFloat()
            val maxY = (height - 1).toFloat()
            for (y in 0 until n) {
                val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, maxY)
                val y0 = fy.toInt()
                val y1 = minOf(y0 + 1, height - 1)
                val wy = fy - y0
                val r0 = y0 * rowStride
                val r1 = y1 * rowStride
                for (x in 0 until n) {
                    val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, maxX)
                    val x0 = fx.toInt()
                    val x1 = minOf(x0 + 1, width - 1)
                    val wx = fx - x0
                    val a = (luma[r0 + x0].toInt() and 0xFF).toFloat()
                    val b = (luma[r0 + x1].toInt() and 0xFF).toFloat()
                    val c = (luma[r1 + x0].toInt() and 0xFF).toFloat()
                    val d = (luma[r1 + x1].toInt() and 0xFF).toFloat()
                    val top = a + (b - a) * wx
                    val v = (top + (c + (d - c) * wx - top) * wy) / 255f
                    val i = y * n + x
                    out[i] = (v - mean[0]) * inv0
                    out[plane + i] = (v - mean[1]) * inv1
                    out[2 * plane + i] = (v - mean[2]) * inv2
                }
            }
        }

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

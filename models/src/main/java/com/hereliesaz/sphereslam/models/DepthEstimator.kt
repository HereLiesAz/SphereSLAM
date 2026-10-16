package com.hereliesaz.sphereslam.models

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File

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
 * Runtime settings for [DepthEstimator] (LiteRT / TensorFlow Lite).
 *
 * @property backend backend to try first. [Backend.GPU] (the default, and the expected path on
 *   every supported phone) uses the LiteRT GPU delegate; if it cannot be initialised (device on the
 *   GPU compatibility block list, driver failure, the first inference failing) the estimator logs an
 *   error and falls back to [Backend.CPU]. [DepthEstimator.activeBackend] reports what it got.
 * @property cpuThreads threads for the CPU (XNNPACK) path. Default 2, so a CPU fallback does not
 *   compete with the camera-analysis thread for every core; 0 keeps LiteRT's own default.
 * @property allowFp16Precision let the GPU compute in fp16 (default; the bundled weights are fp16
 *   already and MiDaS output is relative). False forces fp32 arithmetic on the GPU.
 * @property gpuProgramCache serialize the GPU delegate's compiled programs under the app's
 *   `codeCacheDir` (cleared by the system on app update) so later loads skip shader compilation.
 */
@ExperimentalSphereSlamModelsApi
data class DepthEstimatorOptions(
    val backend: Backend = Backend.GPU,
    val cpuThreads: Int = DEFAULT_CPU_THREADS,
    val allowFp16Precision: Boolean = true,
    val gpuProgramCache: Boolean = true,
) {
    init {
        require(cpuThreads >= 0) { "cpuThreads must be >= 0, was $cpuThreads" }
    }

    /** Where [DepthEstimator] runs the model. */
    enum class Backend {
        /** LiteRT GPU delegate (OpenCL, or OpenGL ES where OpenCL is absent). */
        GPU,

        /** LiteRT CPU kernels via XNNPACK. Always available. */
        CPU,
    }

    /** Backends to try, in order: the requested one, then CPU. */
    internal fun attempts(): List<Backend> = listOf(backend, Backend.CPU).distinct()

    /** GPU delegate settings: fp16 per [allowFp16Precision], sustained speed, optional program cache. */
    internal fun gpuDelegateConfig(cache: DepthRuntime.GpuCache?): GpuDelegateConfig {
        val useCache = gpuProgramCache && cache != null
        return GpuDelegateConfig(
            precisionLossAllowed = allowFp16Precision,
            sustainedSpeed = true,
            serializationDir = if (useCache) cache!!.directory.absolutePath else null,
            modelToken = if (useCache) cache!!.modelToken else null,
        )
    }

    /** CPU interpreter settings: XNNPACK with [cpuThreads]. */
    internal fun cpuConfig(): CpuConfig = CpuConfig(threads = cpuThreads, useXnnpack = true)

    companion object {
        const val DEFAULT_CPU_THREADS = 2

        /**
         * Open with each of [attempts] in turn: the first backend whose [open] succeeds wins; when
         * every attempt fails the last failure is thrown. [onFallback] sees each failed attempt.
         */
        internal fun <S> openWithFallback(
            attempts: List<Backend>,
            open: (Backend) -> S,
            onFallback: (Backend, Throwable) -> Unit = { _, _ -> },
        ): Pair<S, Backend> {
            var last: Throwable? = null
            for (backend in attempts) {
                try {
                    return open(backend) to backend
                } catch (t: Throwable) {
                    if (t is VirtualMachineError) throw t
                    last = t
                    onFallback(backend, t)
                }
            }
            throw last ?: IllegalStateException("no backend to try")
        }
    }
}

/**
 * Monocular depth on **LiteRT (TensorFlow Lite) with the GPU delegate** (fp16, sustained-speed,
 * compiled-program cache; see [DepthEstimatorOptions]), falling back loudly to LiteRT's XNNPACK CPU
 * path only if the GPU cannot be initialised. Ships wired to **MiDaS v2.1 Small (256×256, fp16
 * weights)**, the default asset [MODEL_ASSET] (from the official isl-org/MiDaS `v2_1` release; see
 * `docs/MODELS.md`). Its inverse-depth output gives photosphere tiles depth: passed to
 * `PhotosphereTrackingSession` as a `TileDepthSource`, it is calibrated per tile by `:reloc`'s
 * `DepthScaleFit` against known (wall-plane / triangulated) depths. `RadialDepthPlacement` — placing
 * off-wall points of a `WallFeatureMap` radially — is a building block not yet on the session path.
 *
 * ## Threading
 * The GPU delegate must be created, run and closed on one thread. The estimator owns that thread
 * (`SphereSLAM-depth`): [load], [loadFrom], [estimate], [estimateLuma] and [close] may be called from
 * **any** thread(s) — they hand the model work to the depth thread and block until it is done (input
 * preprocessing runs on the caller). Calls are serialized; do not call from a thread that must not
 * block (e.g. the UI thread). [close] ends the depth thread; a later [load] starts a new one.
 *
 * ## Swapping the model
 * This wrapper is model-agnostic about everything but the ImageNet normalization and the single
 * float input/output. To use your own:
 * 1. Drop your `.tflite` in `models/src/main/assets/` (or load from a file path — see [loadFrom]).
 * 2. Construct `DepthEstimator(context, assetName = "your_model.tflite", inputSize = N)`.
 * 3. If your model expects a different normalization, override [MEAN]/[STD] via the constructor.
 * The input may be NHWC `[1,N,N,3]` (as MiDaS's TFLite export) or NCHW `[1,3,N,N]`; the output
 * `[1,H,W,1]`, `[1,1,H,W]`, `[1,H,W]` or `[H,W]`. A metric-depth model works too — the output is then
 * absolute, and callers can skip the affine scale fit.
 *
 * Input: an ARGB [Bitmap] ([estimate]) or, with no bitmap round trip, a grey luma plane such as the
 * tracking frame itself ([estimateLuma]; the grey value is fed to all three channels).
 *
 * Fails soft: a missing asset or load error leaves [isLoaded] false and [estimate] returning null.
 */
@ExperimentalSphereSlamModelsApi
class DepthEstimator internal constructor(
    private val appContext: Context,
    private val assetName: String,
    private val inputSize: Int,
    private val mean: FloatArray,
    private val std: FloatArray,
    private val options: DepthEstimatorOptions,
    private val runtimeFactory: DepthRuntime.Factory,
) : AutoCloseable {

    constructor(
        appContext: Context,
        assetName: String = MODEL_ASSET,
        inputSize: Int = DEFAULT_INPUT,
        mean: FloatArray = MEAN,
        std: FloatArray = STD,
        options: DepthEstimatorOptions = DepthEstimatorOptions(),
    ) : this(appContext, assetName, inputSize, mean, std, options, LiteRtDepthRuntime.Factory)

    /** The pre-options constructor (binary compatibility); uses default [DepthEstimatorOptions]. */
    constructor(
        appContext: Context,
        assetName: String,
        inputSize: Int,
        mean: FloatArray,
        std: FloatArray,
    ) : this(appContext, assetName, inputSize, mean, std, DepthEstimatorOptions())

    /** The backend the loaded model actually runs on ([DepthEstimatorOptions.Backend.GPU] expected), or null when not loaded. */
    @Volatile
    var activeBackend: DepthEstimatorOptions.Backend? = null
        private set

    private val depthThread = DepthThread()

    // Touched only on the depth thread.
    private var runtime: DepthRuntime? = null

    // Caller-side state, guarded by this.
    private var inputLayout = InputLayout.NHWC
    private var outH = 0
    private var outW = 0
    private var inputScratch: FloatArray? = null
    private var outputScratch: FloatArray? = null
    private var loadedPath: String? = null

    @Volatile
    var isLoaded: Boolean = false
        private set

    /** Reason the last [load] failed, for a diagnostic overlay. Null once loaded. */
    @Volatile
    var lastError: String? = null
        private set

    /** `System.nanoTime()` before which [load] will not retry after a failure; 0 = no cool-down. */
    private var retryNotBeforeNanos = 0L

    /**
     * Extract the bundled asset to filesDir (refreshing a stale copy) and open the model.
     * Idempotent while the asset model is open; replaces a model opened via [loadFrom]. Returns [isLoaded].
     */
    @Synchronized
    fun load(): Boolean {
        // A failed load is not retried on every call: each retry re-checks the asset, re-hashes the
        // model, re-opens the runtime and runs a warm-up inference, and callers poll this per tile.
        if (!isLoaded && System.nanoTime() < retryNotBeforeNanos) return false
        return try {
            val dir = File(appContext.filesDir, MODEL_DIR).apply { mkdirs() }
            val graph = File(dir, assetName)
            if (isLoaded && loadedPath == graph.absolutePath) return true
            if (isLoaded) close() // a different model (e.g. via loadFrom) is open; switch to the asset
            if (!ModelAssets.copyAssetIfNeeded(appContext, assetName, graph, TAG)) {
                lastError = "asset '$assetName' absent (copy failed)"
                return false
            }
            open(graph)
        } catch (t: Throwable) {
            failSoft(t)
            false
        }
    }

    /**
     * Open the model from an arbitrary on-disk `.tflite` file (for a user-supplied model outside
     * assets; the parameter keeps its historical name for source compatibility). Idempotent for the
     * same path; a different path closes the current model and loads the new one.
     */
    @Synchronized
    fun loadFrom(onnxPath: String): Boolean {
        retryNotBeforeNanos = 0L
        if (isLoaded && loadedPath == onnxPath) return true
        if (isLoaded) close()
        return try {
            open(File(onnxPath))
        } catch (t: Throwable) {
            failSoft(t)
            false
        }
    }

    private fun open(model: File): Boolean {
        require(model.isFile) { "model file ${model.absolutePath} not found" }
        val cache = gpuCache(model)
        val n = inputSize
        val opened = depthThread.call {
            val (rt, backend) = DepthEstimatorOptions.openWithFallback(
                options.attempts(),
                open = { b -> openValidated(model, b, cache, n) },
                onFallback = ::logFallback,
            )
            runtime = rt
            Triple(rt, backend, layoutOf(rt.inputShape, n)!!)
        }
        val (rt, backend, layout) = opened
        val (h, w) = outputGrid(rt.outputShape)
        inputLayout = layout
        outH = h
        outW = w
        activeBackend = backend
        loadedPath = model.absolutePath
        isLoaded = true
        lastError = null
        Log.i(TAG, "loaded ${model.absolutePath} backend=$backend input=${rt.inputShape.contentToString()} " +
            "output=${rt.outputShape.contentToString()}")
        if (backend != options.backend) Log.e(TAG, "depth is running on $backend, not the requested ${options.backend}")
        return true
    }

    /**
     * Open on [backend], check the tensors fit this wrapper, and run one warm-up inference — GPU
     * delegates can fail on the first run (kernel compile) rather than at creation, and the fallback
     * must happen here, not mid-session. Depth thread only.
     */
    private fun openValidated(model: File, backend: DepthEstimatorOptions.Backend, cache: DepthRuntime.GpuCache?, n: Int): DepthRuntime {
        val rt = runtimeFactory.open(model, backend, options, cache)
        try {
            checkNotNull(layoutOf(rt.inputShape, n)) {
                "model input ${rt.inputShape.contentToString()} is not [1,$n,$n,3] or [1,3,$n,$n] (inputSize=$n)"
            }
            val (h, w) = outputGrid(rt.outputShape)
            rt.run(FloatArray(3 * n * n), FloatArray(h * w))
            return rt
        } catch (t: Throwable) {
            try {
                rt.close()
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    private fun gpuCache(model: File): DepthRuntime.GpuCache? {
        if (!options.gpuProgramCache || !options.attempts().contains(DepthEstimatorOptions.Backend.GPU)) return null
        return try {
            val base = appContext.codeCacheDir ?: return null
            val dir = File(base, GPU_CACHE_DIR).apply { mkdirs() }
            DepthRuntime.GpuCache(dir, gpuModelToken(model.name, model.length(), ModelAssets.sha256Hex(model)))
        } catch (t: Throwable) {
            Log.w(TAG, "GPU program cache unavailable", t)
            null
        }
    }

    private fun logFallback(backend: DepthEstimatorOptions.Backend, t: Throwable) {
        if (backend == DepthEstimatorOptions.Backend.GPU) {
            Log.e(TAG, "!!! LiteRT GPU delegate FAILED to initialise — monocular depth falls back to the CPU " +
                "(several times slower). GPU is expected on every supported device; please report this one.", t)
        } else {
            Log.w(TAG, "depth backend $backend unavailable", t)
        }
    }

    private fun failSoft(t: Throwable) {
        retryNotBeforeNanos = System.nanoTime() + LOAD_RETRY_COOLDOWN_NANOS
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
        if (!isLoaded) return null
        return try {
            val n = inputSize
            val scaled = Bitmap.createScaledBitmap(bitmap, n, n, true)
            val pixels = IntArray(n * n)
            try {
                scaled.getPixels(pixels, 0, n, 0, 0, n, n)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
            val input = inputBuffer()
            argbToInput(pixels, n, mean, std, inputLayout, input)
            infer(input, outMaxDim)
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
        if (!isLoaded) return null
        return try {
            val input = inputBuffer()
            lumaToInput(luma, width, height, rowStride, inputSize, mean, std, inputLayout, input)
            infer(input, outMaxDim)
        } catch (t: Throwable) {
            Log.w(TAG, "inference failed", t)
            null
        }
    }

    private fun inputBuffer(): FloatArray {
        val count = 3 * inputSize * inputSize
        return inputScratch?.takeIf { it.size == count } ?: FloatArray(count).also { inputScratch = it }
    }

    private fun infer(input: FloatArray, outMaxDim: Int): DepthMap? {
        val count = outH * outW
        val output = outputScratch?.takeIf { it.size == count } ?: FloatArray(count).also { outputScratch = it }
        val ran = depthThread.call {
            val rt = runtime ?: return@call false
            rt.run(input, output)
            true
        }
        return if (ran) downsample(output, outH, outW, outMaxDim) else null
    }

    @Synchronized
    override fun close() {
        try {
            depthThread.call {
                val rt = runtime
                runtime = null
                rt?.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        }
        depthThread.shutdown()
        activeBackend = null
        loadedPath = null
        isLoaded = false
    }

    /** Memory layout of the model's image input. */
    internal enum class InputLayout { NHWC, NCHW }

    companion object {
        private const val TAG = "DepthEstimator"

        /** After a failed [load], [load] returns false for this long instead of retrying. */
        private const val LOAD_RETRY_COOLDOWN_NANOS = 30_000_000_000L

        /** MiDaS v2.1 Small 256×256, fp16 weights (isl-org/MiDaS `v2_1` `model_opt.tflite`). */
        const val MODEL_ASSET = "midas_v21_small_256_fp16.tflite"
        const val DEFAULT_INPUT = 256
        const val DEFAULT_OUT_MAX_DIM = 128
        private const val MODEL_DIR = "sphereslam-models"
        private const val GPU_CACHE_DIR = "sphereslam-depth-gpu"
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /** NHWC `[1,n,n,3]` or NCHW `[1,3,n,n]`, else null (unsupported). NHWC wins when ambiguous. */
        internal fun layoutOf(shape: IntArray, n: Int): InputLayout? = when {
            shape.size != 4 || shape[0] != 1 -> null
            shape[1] == n && shape[2] == n && shape[3] == 3 -> InputLayout.NHWC
            shape[1] == 3 && shape[2] == n && shape[3] == n -> InputLayout.NCHW
            else -> null
        }

        /** `(height, width)` of a single-channel depth output: `[1,H,W,1]`, `[1,1,H,W]`, `[1,H,W]` or `[H,W]`. */
        internal fun outputGrid(shape: IntArray): Pair<Int, Int> {
            val dims = when {
                shape.size == 4 && shape[0] == 1 && shape[3] == 1 -> shape[1] to shape[2]
                shape.size == 4 && shape[0] == 1 && shape[1] == 1 -> shape[2] to shape[3]
                shape.size == 3 && shape[0] == 1 -> shape[1] to shape[2]
                shape.size == 2 -> shape[0] to shape[1]
                else -> error("unexpected depth output shape ${shape.contentToString()}")
            }
            require(dims.first > 0 && dims.second > 0) { "dynamic depth output shape ${shape.contentToString()}" }
            return dims
        }

        /**
         * Bilinearly resample a grey plane to `n×n` (pixel-centre aligned, like a filtered
         * `Bitmap.createScaledBitmap`) and write it normalized into [out] (length >= `3·n·n`) in
         * [layout], the grey value `v/255` in every channel as `(v − mean[c]) / std[c]`.
         * Pure and allocation-free.
         */
        internal fun lumaToInput(
            luma: ByteArray,
            width: Int,
            height: Int,
            rowStride: Int,
            n: Int,
            mean: FloatArray,
            std: FloatArray,
            layout: InputLayout,
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
                    write(out, layout, plane, y * n + x, (v - mean[0]) * inv0, (v - mean[1]) * inv1, (v - mean[2]) * inv2)
                }
            }
        }

        /** Normalize `n×n` ARGB [pixels] into [out] (length >= `3·n·n`) in [layout], RGB channel order. Pure. */
        internal fun argbToInput(pixels: IntArray, n: Int, mean: FloatArray, std: FloatArray, layout: InputLayout, out: FloatArray) {
            val plane = n * n
            require(pixels.size >= plane && out.size >= 3 * plane)
            for (i in 0 until plane) {
                val p = pixels[i]
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                write(out, layout, plane, i, (r - mean[0]) / std[0], (g - mean[1]) / std[1], (b - mean[2]) / std[2])
            }
        }

        @Suppress("NOTHING_TO_INLINE")
        private inline fun write(out: FloatArray, layout: InputLayout, plane: Int, i: Int, c0: Float, c1: Float, c2: Float) {
            if (layout == InputLayout.NHWC) {
                val o = 3 * i
                out[o] = c0
                out[o + 1] = c1
                out[o + 2] = c2
            } else {
                out[i] = c0
                out[plane + i] = c1
                out[2 * plane + i] = c2
            }
        }

        /** Nearest-neighbour downscale a row-major `h×w` map so its long side is `<= outMaxDim` ([downscale]). Pure. */
        internal fun downsample(src: FloatArray, h: Int, w: Int, outMaxDim: Int): DepthMap {
            require(src.size >= h * w)
            val (stride, dims) = downscale(h, w, outMaxDim)
            val (oh, ow) = dims
            val dst = FloatArray(ow * oh)
            for (y in 0 until oh) {
                val row = y * stride * w
                for (x in 0 until ow) dst[y * ow + x] = src[row + x * stride]
            }
            return DepthMap(ow, oh, dst)
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

        /**
         * GPU program-cache token: unique per model content (the same SHA-256 that decides whether an
         * extracted asset is current, first 16 hex digits), filename-safe (the delegate uses it as a
         * file name), so a replaced model never reuses a stale compiled program. Pure.
         */
        internal fun gpuModelToken(name: String, length: Long, sha256Hex: String): String =
            "${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}-$length-${sha256Hex.take(16).lowercase()}"
    }
}

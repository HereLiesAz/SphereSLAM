package com.hereliesaz.sphereslam.models

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * Optional low-light image enhancement via ONNX Runtime (CPU), wired to **Zero-DCE** ([MODEL_ASSET]).
 * Zero-DCE is image→image: it maps a dark frame to a brightened one, which can lift feature counts
 * for KPM/relocalization in poor light. Offered, not required — the tracker runs fine without it.
 *
 * Swap the model by passing a different [assetName] (expects NCHW `[1,3,H,W]` in 0..1, image output
 * of the same layout). The network input size is **read from the loaded graph** (the bundled Zero-DCE
 * export is fixed at `[1,3,400,600]`); [inputSize] is only the fallback for graphs with dynamic
 * spatial dims. The frame is resized (aspect squashed) to the network size and the enhanced result is
 * resized back, so the returned bitmap always has the input bitmap's dimensions.
 *
 * Fails soft: unavailable model ⇒ [isLoaded] false. [enhanceOrNull] returns null on any failure;
 * [enhance] returns the input bitmap unchanged instead and sets [lastEnhanceFailed] so the fallback
 * is observable.
 */
@ExperimentalSphereSlamModelsApi
class LowLightEnhancer(
    private val appContext: Context,
    private val assetName: String = MODEL_ASSET,
    private val inputSize: Int = DEFAULT_INPUT,
) : AutoCloseable {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null
    private var netWidth: Int = inputSize
    private var netHeight: Int = inputSize

    @Volatile var isLoaded: Boolean = false
        private set

    /** True when the most recent [enhance]/[enhanceOrNull] call failed (or ran unloaded). */
    @Volatile var lastEnhanceFailed: Boolean = false
        private set

    @Synchronized
    fun load(): Boolean {
        if (isLoaded) return true
        return try {
            val dir = File(appContext.filesDir, MODEL_DIR).apply { mkdirs() }
            val graph = File(dir, assetName)
            if (!ModelAssets.copyAssetIfNeeded(appContext, assetName, graph, TAG)) return false
            val environment = OrtEnvironment.getEnvironment()
            val s = environment.createSession(graph.absolutePath, OrtSession.SessionOptions())
            session = s
            env = environment
            val name = s.inputNames.first()
            inputName = name
            val shape = (s.inputInfo[name]?.info as? TensorInfo)?.shape
            val (h, w) = networkInputSize(shape, inputSize)
            netHeight = h
            netWidth = w
            isLoaded = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "load failed; low-light enhancement disabled", t)
            close()
            false
        }
    }

    /**
     * Enhance [bitmap]; returns a new brightened bitmap with [bitmap]'s dimensions, or [bitmap]
     * itself when not loaded or inference fails (check [lastEnhanceFailed], or use [enhanceOrNull]).
     */
    @Synchronized
    fun enhance(bitmap: Bitmap): Bitmap = enhanceOrNull(bitmap) ?: bitmap

    /** Enhance [bitmap]; a new bitmap with [bitmap]'s dimensions, or null if not loaded / failed. */
    @Synchronized
    fun enhanceOrNull(bitmap: Bitmap): Bitmap? {
        lastEnhanceFailed = true
        val s = session ?: return null
        val environment = env ?: return null
        val name = inputName ?: return null
        val w = netWidth
        val h = netHeight
        var scaled: Bitmap? = null
        var netOut: Bitmap? = null
        return try {
            scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val plane = w * h
            val pixels = IntArray(plane)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            val chw = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = pixels[i]
                chw[i] = ((p shr 16) and 0xFF) / 255f
                chw[plane + i] = ((p shr 8) and 0xFF) / 255f
                chw[2 * plane + i] = (p and 0xFF) / 255f
            }
            val input = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(chw), longArrayOf(1, 3, h.toLong(), w.toLong()),
            )
            val enhanced = input.use {
                s.run(mapOf(name to it)).use { results ->
                    val tensors = results.map { it.key to (it.value as? OnnxTensor) }
                    val index = selectImageOutput(
                        tensors.map { it.first },
                        tensors.map { it.second?.info?.shape },
                        h,
                        w,
                    )
                    require(index >= 0) { "no [1,3,$h,$w] image output in ${tensors.map { it.first }}" }
                    val out = tensors[index].second!!.floatBuffer
                    val outPixels = IntArray(plane)
                    for (i in 0 until plane) {
                        val r = (out.get(i) * 255f).toInt().coerceIn(0, 255)
                        val g = (out.get(plane + i) * 255f).toInt().coerceIn(0, 255)
                        val b = (out.get(2 * plane + i) * 255f).toInt().coerceIn(0, 255)
                        outPixels[i] = Color.rgb(r, g, b)
                    }
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.setPixels(outPixels, 0, w, 0, 0, w, h)
                    }
                }
            }
            netOut = enhanced
            val result = if (enhanced.width == bitmap.width && enhanced.height == bitmap.height) {
                enhanced
            } else {
                Bitmap.createScaledBitmap(enhanced, bitmap.width, bitmap.height, true)
            }
            if (result === enhanced) netOut = null // handed to the caller; do not recycle
            lastEnhanceFailed = false
            result
        } catch (t: Throwable) {
            Log.w(TAG, "enhance failed", t)
            null
        } finally {
            scaled?.let { if (it !== bitmap) it.recycle() }
            netOut?.recycle()
        }
    }

    @Synchronized
    override fun close() {
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
        env = null
        inputName = null
        isLoaded = false
    }

    companion object {
        private const val TAG = "LowLightEnhancer"
        const val MODEL_ASSET = "zerodce.onnx"
        const val DEFAULT_INPUT = 256
        private const val MODEL_DIR = "sphereslam-models"

        /** Name the bundled Zero-DCE export gives its image output. */
        internal const val PREFERRED_OUTPUT = "enhanced_image"

        /**
         * `(height, width)` the network expects, from the graph's NCHW input [shape]; a missing or
         * dynamic (`<= 0`) spatial dim falls back to [fallback]. Pure; unit-tested.
         */
        internal fun networkInputSize(shape: LongArray?, fallback: Int): Pair<Int, Int> {
            val h = shape?.getOrNull(2)?.takeIf { it > 0 }?.toInt() ?: fallback
            val w = shape?.getOrNull(3)?.takeIf { it > 0 }?.toInt() ?: fallback
            return h to w
        }

        /**
         * Index of the image output to read: [PREFERRED_OUTPUT] if present with a usable shape, else
         * the first output shaped `[1,3,h,w]` (dynamic dims accepted). -1 if none qualifies. Pure.
         */
        internal fun selectImageOutput(names: List<String>, shapes: List<LongArray?>, h: Int, w: Int): Int {
            fun fits(shape: LongArray?): Boolean {
                if (shape == null || shape.size != 4) return false
                fun ok(dim: Long, want: Int) = dim <= 0L || dim == want.toLong()
                return ok(shape[0], 1) && shape[1] == 3L && ok(shape[2], h) && ok(shape[3], w)
            }
            val preferred = names.indexOf(PREFERRED_OUTPUT)
            if (preferred >= 0 && fits(shapes.getOrNull(preferred))) return preferred
            return shapes.indexOfFirst { fits(it) }
        }
    }
}

package com.hereliesaz.sphereslam.models

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/** Raw SuperPoint outputs: the dense score map and the descriptor field, each with its shape. */
@ExperimentalSphereSlamModelsApi
data class SuperPointOutput(
    val scores: FloatArray,
    val scoresShape: LongArray,
    val descriptors: FloatArray,
    val descriptorsShape: LongArray,
)

/**
 * Optional learned keypoint/descriptor front-end (**SuperPoint**, [MODEL_ASSET]) via ONNX Runtime
 * (CPU) — a denser, more repeatable alternative to ORB for building the feature map in hard texture.
 * Offered, not required.
 *
 * This wrapper is deliberately thin: it runs the forward pass over a grayscale frame and returns the
 * **raw** score/descriptor tensors ([SuperPointOutput]). Decoding those into keypoints — thresholding,
 * non-max suppression, and bilinear descriptor sampling — is specific to the exact SuperPoint export
 * (output names, score layout, descriptor stride) and is left to the consumer so swapping in a
 * different variant doesn't fight a hard-coded decoder. Input is normalized grayscale NCHW
 * `[1,1,N,N]` in 0..1.
 */
@ExperimentalSphereSlamModelsApi
class SuperPointDetector(
    private val appContext: Context,
    private val assetName: String = MODEL_ASSET,
    private val inputSize: Int = DEFAULT_INPUT,
) : AutoCloseable {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Volatile var isLoaded: Boolean = false
        private set

    /** Output tensor names the loaded graph exposes — use these to interpret [detect]'s raw result. */
    val outputNames: List<String> get() = session?.outputNames?.toList() ?: emptyList()

    @Synchronized
    fun load(): Boolean {
        if (isLoaded) return true
        return try {
            val dir = File(appContext.filesDir, MODEL_DIR).apply { mkdirs() }
            val graph = File(dir, assetName)
            if (!copyAssetIfNeeded(assetName, graph)) return false
            val environment = OrtEnvironment.getEnvironment()
            session = environment.createSession(graph.absolutePath, OrtSession.SessionOptions())
            env = environment
            isLoaded = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "load failed; SuperPoint disabled", t)
            close()
            false
        }
    }

    /** Run the forward pass over [bitmap]; null if not loaded or inference fails. */
    @Synchronized
    fun detect(bitmap: Bitmap): SuperPointOutput? {
        val s = session ?: return null
        val environment = env ?: return null
        return try {
            val n = inputSize
            val scaled = Bitmap.createScaledBitmap(bitmap, n, n, true)
            val pixels = IntArray(n * n)
            scaled.getPixels(pixels, 0, n, 0, 0, n, n)
            if (scaled !== bitmap) scaled.recycle()
            val gray = FloatArray(n * n)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                gray[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            }
            val input = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(gray), longArrayOf(1, 1, n.toLong(), n.toLong()),
            )
            input.use {
                s.run(mapOf(s.inputNames.first() to it)).use { results ->
                    val a = results[0] as OnnxTensor
                    val b = results[if (results.size() > 1) 1 else 0] as OnnxTensor
                    SuperPointOutput(
                        scores = a.floatBuffer.toArray(),
                        scoresShape = a.info.shape,
                        descriptors = b.floatBuffer.toArray(),
                        descriptorsShape = b.info.shape,
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "inference failed", t)
            null
        }
    }

    private fun FloatBuffer.toArray(): FloatArray {
        val a = FloatArray(remaining())
        get(a)
        return a
    }

    private fun copyAssetIfNeeded(name: String, dest: File): Boolean {
        return try {
            if (dest.exists() && dest.length() > 0L) return true
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            appContext.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) { tmp.delete(); return false }
            dest.exists() && dest.length() > 0L
        } catch (t: Throwable) {
            Log.w(TAG, "asset $name unavailable", t); false
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
        isLoaded = false
    }

    companion object {
        private const val TAG = "SuperPointDetector"
        const val MODEL_ASSET = "superpoint.onnx"
        const val DEFAULT_INPUT = 256
        private const val MODEL_DIR = "sphereslam-models"
    }
}

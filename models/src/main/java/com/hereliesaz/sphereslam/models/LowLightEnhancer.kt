package com.hereliesaz.sphereslam.models

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
 * Swap the model by passing a different [assetName] (expects NCHW `[1,3,H,W]` in 0..1, same-shape
 * output). Fails soft: unavailable model ⇒ [isLoaded] false and [enhance] returns the input bitmap.
 */
class LowLightEnhancer(
    private val appContext: Context,
    private val assetName: String = MODEL_ASSET,
    private val inputSize: Int = DEFAULT_INPUT,
) : AutoCloseable {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Volatile var isLoaded: Boolean = false
        private set

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
            Log.w(TAG, "load failed; low-light enhancement disabled", t)
            close()
            false
        }
    }

    /** Enhance [bitmap]; returns a new brightened bitmap, or [bitmap] unchanged if not loaded/failed. */
    @Synchronized
    fun enhance(bitmap: Bitmap): Bitmap {
        val s = session ?: return bitmap
        val environment = env ?: return bitmap
        return try {
            val n = inputSize
            val scaled = Bitmap.createScaledBitmap(bitmap, n, n, true)
            val pixels = IntArray(n * n)
            scaled.getPixels(pixels, 0, n, 0, 0, n, n)
            val plane = n * n
            val chw = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = pixels[i]
                chw[i] = ((p shr 16) and 0xFF) / 255f
                chw[plane + i] = ((p shr 8) and 0xFF) / 255f
                chw[2 * plane + i] = (p and 0xFF) / 255f
            }
            val input = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(chw), longArrayOf(1, 3, n.toLong(), n.toLong()),
            )
            input.use {
                s.run(mapOf(s.inputNames.first() to it)).use { results ->
                    val out = (results[results.size() - 1] as OnnxTensor).floatBuffer
                    val outPixels = IntArray(plane)
                    for (i in 0 until plane) {
                        val r = (out.get(i) * 255f).toInt().coerceIn(0, 255)
                        val g = (out.get(plane + i) * 255f).toInt().coerceIn(0, 255)
                        val b = (out.get(2 * plane + i) * 255f).toInt().coerceIn(0, 255)
                        outPixels[i] = Color.rgb(r, g, b)
                    }
                    val result = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
                    result.setPixels(outPixels, 0, n, 0, 0, n, n)
                    if (scaled !== bitmap) scaled.recycle()
                    result
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enhance failed; returning input", t)
            bitmap
        }
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
        private const val TAG = "LowLightEnhancer"
        const val MODEL_ASSET = "zerodce.onnx"
        const val DEFAULT_INPUT = 256
        private const val MODEL_DIR = "sphereslam-models"
    }
}

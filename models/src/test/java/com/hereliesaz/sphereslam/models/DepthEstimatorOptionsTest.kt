package com.hereliesaz.sphereslam.models

import com.hereliesaz.sphereslam.models.DepthEstimator.InputLayout
import com.hereliesaz.sphereslam.models.DepthEstimatorOptions.Backend
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DepthEstimatorOptionsTest {

    @Test
    fun `default is gpu first with cpu fallback`() {
        val defaults = DepthEstimatorOptions()
        assertEquals(Backend.GPU, defaults.backend)
        assertEquals(listOf(Backend.GPU, Backend.CPU), defaults.attempts())
        assertEquals(listOf(Backend.CPU), DepthEstimatorOptions(backend = Backend.CPU).attempts())
    }

    @Test
    fun `gpu config is fp16, sustained speed, with the program cache`() {
        val cache = DepthRuntime.GpuCache(File("/cache/gpu"), "tok")
        assertEquals(
            GpuDelegateConfig(precisionLossAllowed = true, sustainedSpeed = true, serializationDir = "/cache/gpu", modelToken = "tok"),
            DepthEstimatorOptions().gpuDelegateConfig(cache),
        )
        assertEquals(
            GpuDelegateConfig(precisionLossAllowed = false, sustainedSpeed = true, serializationDir = null, modelToken = null),
            DepthEstimatorOptions(allowFp16Precision = false, gpuProgramCache = false).gpuDelegateConfig(cache),
        )
        // No cache directory available: no serialization even when requested.
        assertNull(DepthEstimatorOptions().gpuDelegateConfig(null).serializationDir)
    }

    @Test
    fun `cpu config is xnnpack with the requested threads`() {
        assertEquals(CpuConfig(threads = 2, useXnnpack = true), DepthEstimatorOptions().cpuConfig())
        assertEquals(CpuConfig(threads = 0, useXnnpack = true), DepthEstimatorOptions(cpuThreads = 0).cpuConfig())
        assertThrows(IllegalArgumentException::class.java) { DepthEstimatorOptions(cpuThreads = -1) }
    }

    @Test
    fun `a failing gpu falls back to cpu and the last failure surfaces`() {
        val fallbacks = mutableListOf<Backend>()
        val (opened, backend) = DepthEstimatorOptions.openWithFallback(
            DepthEstimatorOptions().attempts(),
            open = { b -> if (b == Backend.GPU) throw IllegalStateException("no gpu") else "cpu-runtime" },
            onFallback = { b, _ -> fallbacks += b },
        )
        assertEquals("cpu-runtime", opened)
        assertEquals(Backend.CPU, backend)
        assertEquals(listOf(Backend.GPU), fallbacks)

        val error = assertThrows(IllegalStateException::class.java) {
            DepthEstimatorOptions.openWithFallback(listOf(Backend.GPU, Backend.CPU), open = { b -> error("$b failed") })
        }
        assertEquals("CPU failed", error.message)
    }

    @Test
    fun `gpu cache token is filename safe and content specific`() {
        assertEquals("midas_v21_small_256_fp16.tflite-33248432-ff", DepthEstimator.gpuModelToken("midas_v21_small_256_fp16.tflite", 33248432L, 255L))
        assertEquals("a_b_c.tflite-1-0", DepthEstimator.gpuModelToken("a b/c.tflite", 1L, 0L))
        assertTrue(DepthEstimator.gpuModelToken("m", 1L, 1L) != DepthEstimator.gpuModelToken("m", 1L, 2L))
    }

    @Test
    fun `input layout and output grid follow the tensor shapes`() {
        assertEquals(InputLayout.NHWC, DepthEstimator.layoutOf(intArrayOf(1, 256, 256, 3), 256))
        assertEquals(InputLayout.NCHW, DepthEstimator.layoutOf(intArrayOf(1, 3, 256, 256), 256))
        assertNull(DepthEstimator.layoutOf(intArrayOf(1, 384, 384, 3), 256))
        assertNull(DepthEstimator.layoutOf(intArrayOf(256, 256, 3), 256))

        assertEquals(256 to 256, DepthEstimator.outputGrid(intArrayOf(1, 256, 256, 1)))
        assertEquals(120 to 160, DepthEstimator.outputGrid(intArrayOf(1, 1, 120, 160)))
        assertEquals(120 to 160, DepthEstimator.outputGrid(intArrayOf(1, 120, 160)))
        assertEquals(120 to 160, DepthEstimator.outputGrid(intArrayOf(120, 160)))
        assertThrows(IllegalStateException::class.java) { DepthEstimator.outputGrid(intArrayOf(1, 2, 3, 4)) }
    }

    @Test
    fun `luma is resampled bilinearly into normalized grey nhwc`() {
        val mean = floatArrayOf(0.5f, 0.25f, 0f)
        val std = floatArrayOf(0.5f, 0.25f, 1f)
        // Uniform white plane: every pixel is (1, 3, 1), interleaved.
        val out = FloatArray(3 * 4)
        DepthEstimator.lumaToInput(ByteArray(9 * 7) { 255.toByte() }, 9, 7, 9, 2, mean, std, InputLayout.NHWC, out)
        assertArrayEquals(floatArrayOf(1f, 3f, 1f, 1f, 3f, 1f, 1f, 3f, 1f, 1f, 3f, 1f), out, 1e-6f)

        // 4x1 ramp 0, 100, 200, 250 (row stride 6, padding ignored) halved to 2x2: centre-aligned
        // samples fall at x = 0.5 and 2.5 -> 50 and 225.
        val ramp = byteArrayOf(0, 100, 200.toByte(), 250.toByte(), 9, 9)
        val zero = floatArrayOf(0f, 0f, 0f)
        val one = floatArrayOf(1f, 1f, 1f)
        val a = 50f / 255f
        val b = 225f / 255f
        val nhwc = FloatArray(3 * 4)
        DepthEstimator.lumaToInput(ramp, 4, 1, 6, 2, zero, one, InputLayout.NHWC, nhwc)
        assertArrayEquals(floatArrayOf(a, a, a, b, b, b, a, a, a, b, b, b), nhwc, 1e-6f)
        val nchw = FloatArray(3 * 4)
        DepthEstimator.lumaToInput(ramp, 4, 1, 6, 2, zero, one, InputLayout.NCHW, nchw)
        assertArrayEquals(floatArrayOf(a, b, a, b, a, b, a, b, a, b, a, b), nchw, 1e-6f)
    }

    @Test
    fun `argb pixels are normalized per channel in rgb order`() {
        val pixels = intArrayOf(0xFF336699.toInt(), 0xFF000000.toInt())
        val mean = floatArrayOf(0.1f, 0.2f, 0.3f)
        val std = floatArrayOf(0.5f, 0.25f, 2f)
        val r = (0x33 / 255f - 0.1f) / 0.5f
        val g = (0x66 / 255f - 0.2f) / 0.25f
        val bl = (0x99 / 255f - 0.3f) / 2f
        val nhwc = FloatArray(3)
        DepthEstimator.argbToInput(pixels, 1, mean, std, InputLayout.NHWC, nhwc)
        assertArrayEquals(floatArrayOf(r, g, bl), nhwc, 1e-6f)
        val nchw = FloatArray(3)
        DepthEstimator.argbToInput(pixels, 1, mean, std, InputLayout.NCHW, nchw)
        assertArrayEquals(floatArrayOf(r, g, bl), nchw, 1e-6f)
    }

    @Test
    fun `output is downsampled row-major by integer stride`() {
        // 4x6 map with value = 10*y + x.
        val src = FloatArray(24) { (10 * (it / 6) + it % 6).toFloat() }
        val full = DepthEstimator.downsample(src, 4, 6, 6)
        assertEquals(6, full.width)
        assertEquals(4, full.height)
        assertArrayEquals(src, full.data, 0f)
        val half = DepthEstimator.downsample(src, 4, 6, 3)
        assertEquals(3, half.width)
        assertEquals(2, half.height)
        assertArrayEquals(floatArrayOf(0f, 2f, 4f, 20f, 22f, 24f), half.data, 0f)
    }
}

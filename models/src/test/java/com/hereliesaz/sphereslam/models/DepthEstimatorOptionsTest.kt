package com.hereliesaz.sphereslam.models

import com.hereliesaz.sphereslam.models.DepthEstimatorOptions.ExecutionProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DepthEstimatorOptionsTest {

    private class Recorder(private val failXnnpack: Boolean = false, private val failNnapi: Boolean = false) :
        DepthEstimatorOptions.Target {
        val calls = mutableListOf<String>()
        override fun setIntraOpNumThreads(threads: Int) {
            calls += "intra=$threads"
        }

        override fun addConfigEntry(key: String, value: String) {
            calls += "$key=$value"
        }

        override fun addXnnpack(options: Map<String, String>) {
            if (failXnnpack) throw UnsupportedOperationException("no xnnpack")
            calls += "xnnpack$options"
        }

        override fun addNnapi() {
            if (failNnapi) throw UnsatisfiedLinkError("no nnapi")
            calls += "nnapi"
        }
    }

    private fun applied(options: DepthEstimatorOptions, provider: ExecutionProvider) =
        Recorder().also { options.applyTo(it, provider) }.calls

    @Test
    fun `default is two cpu threads without spinning`() {
        val defaults = DepthEstimatorOptions()
        assertEquals(2, defaults.intraOpThreads)
        assertEquals(ExecutionProvider.CPU, defaults.executionProvider)
        assertEquals(listOf("session.intra_op.allow_spinning=0", "intra=2"), applied(defaults, ExecutionProvider.CPU))
    }

    @Test
    fun `zero threads keeps the runtime default and spinning can be enabled`() {
        assertEquals(
            listOf("session.intra_op.allow_spinning=1"),
            applied(DepthEstimatorOptions(intraOpThreads = 0, allowSpinning = true), ExecutionProvider.CPU),
        )
    }

    @Test
    fun `xnnpack gets the threads and ort's own pool is pinned to one`() {
        assertEquals(
            listOf("session.intra_op.allow_spinning=0", "intra=1", "xnnpack{intra_op_num_threads=3}"),
            applied(DepthEstimatorOptions(intraOpThreads = 3, executionProvider = ExecutionProvider.XNNPACK), ExecutionProvider.XNNPACK),
        )
    }

    @Test
    fun `nnapi keeps cpu threads for unsupported ops`() {
        assertEquals(
            listOf("session.intra_op.allow_spinning=0", "intra=2", "nnapi"),
            applied(DepthEstimatorOptions(executionProvider = ExecutionProvider.NNAPI), ExecutionProvider.NNAPI),
        )
    }

    @Test
    fun `a requested provider falls back to cpu`() {
        val options = DepthEstimatorOptions(executionProvider = ExecutionProvider.NNAPI)
        assertEquals(listOf(ExecutionProvider.NNAPI, ExecutionProvider.CPU), options.attempts())
        assertEquals(listOf(ExecutionProvider.CPU), DepthEstimatorOptions().attempts())

        val fallbacks = mutableListOf<ExecutionProvider>()
        val (session, provider) = DepthEstimatorOptions.openWithFallback(
            options.attempts(),
            open = { p -> Recorder(failNnapi = true).also { options.applyTo(it, p) }.calls },
            onFallback = { p, _ -> fallbacks += p },
        )
        assertEquals(ExecutionProvider.CPU, provider)
        assertEquals(listOf(ExecutionProvider.NNAPI), fallbacks)
        assertEquals(listOf("session.intra_op.allow_spinning=0", "intra=2"), session)
    }

    @Test
    fun `when every attempt fails the last failure surfaces`() {
        val error = assertThrows(IllegalStateException::class.java) {
            DepthEstimatorOptions.openWithFallback(
                listOf(ExecutionProvider.XNNPACK, ExecutionProvider.CPU),
                open = { p -> error("session $p failed") },
            )
        }
        assertEquals("session CPU failed", error.message)
    }

    @Test
    fun `negative thread counts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { DepthEstimatorOptions(intraOpThreads = -1) }
    }

    @Test
    fun `luma is resampled bilinearly into normalized grey channels`() {
        val mean = floatArrayOf(0.5f, 0.25f, 0f)
        val std = floatArrayOf(0.5f, 0.25f, 1f)
        // Uniform plane: every sample is the same grey.
        val out = FloatArray(3 * 4)
        DepthEstimator.lumaToNchw(ByteArray(9 * 7) { 255.toByte() }, 9, 7, 9, 2, mean, std, out)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f, 3f, 3f, 3f, 3f, 1f, 1f, 1f, 1f), out, 1e-6f)

        // 4x1 ramp 0, 100, 200, 250 (row stride 6, padding ignored) halved to 2x2: centre-aligned
        // samples fall at x = 0.5 and 2.5 -> 50 and 225.
        val ramp = byteArrayOf(0, 100, 200.toByte(), 250.toByte(), 9, 9)
        val half = FloatArray(3 * 4)
        DepthEstimator.lumaToNchw(ramp, 4, 1, 6, 2, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), half)
        val expected = floatArrayOf(50f / 255f, 225f / 255f, 50f / 255f, 225f / 255f)
        assertArrayEquals(expected, half.copyOfRange(0, 4), 1e-6f)
        assertArrayEquals(expected, half.copyOfRange(4, 8), 1e-6f)
        assertArrayEquals(expected, half.copyOfRange(8, 12), 1e-6f)
    }
}

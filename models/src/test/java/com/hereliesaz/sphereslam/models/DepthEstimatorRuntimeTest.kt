package com.hereliesaz.sphereslam.models

import android.content.ContextWrapper
import com.hereliesaz.sphereslam.models.DepthEstimatorOptions.Backend
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** [DepthEstimator] orchestration against a fake [DepthRuntime] (no GPU or LiteRT natives on the JVM). */
class DepthEstimatorRuntimeTest {

    private val model: File = File.createTempFile("depth", ".tflite").apply { writeBytes(ByteArray(64) { it.toByte() }) }

    @After
    fun cleanUp() {
        model.delete()
    }

    /** Records every call and the thread it ran on; output = 1000·backendOrdinal + index. */
    private class FakeFactory(
        private val failing: Set<Backend> = emptySet(),
        private val failFirstRun: Set<Backend> = emptySet(),
        private val inShape: IntArray = intArrayOf(1, 4, 4, 3),
        private val outShape: IntArray = intArrayOf(1, 4, 4, 1),
    ) : DepthRuntime.Factory {
        val events = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<Thread>()
        val inputs = CopyOnWriteArrayList<FloatArray>()

        override fun open(model: File, backend: Backend, options: DepthEstimatorOptions, gpuCache: DepthRuntime.GpuCache?): DepthRuntime {
            threads += Thread.currentThread()
            events += "open $backend"
            if (backend in failing) throw IllegalStateException("$backend unavailable")
            return object : DepthRuntime {
                private var runs = 0
                override val backend = backend
                override val inputShape = inShape
                override val outputShape = outShape
                override fun run(input: FloatArray, output: FloatArray) {
                    threads += Thread.currentThread()
                    events += "run $backend"
                    if (runs++ == 0 && backend in failFirstRun) throw IllegalStateException("$backend first run failed")
                    inputs += input.copyOf()
                    for (i in output.indices) output[i] = (1000 * backend.ordinal + i).toFloat()
                }

                override fun close() {
                    threads += Thread.currentThread()
                    events += "close $backend"
                }
            }
        }
    }

    private fun estimator(factory: DepthRuntime.Factory, options: DepthEstimatorOptions = DepthEstimatorOptions()) =
        DepthEstimator(ContextWrapper(null), DepthEstimator.MODEL_ASSET, 4, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), options, factory)

    @Test
    fun `gpu is the default backend and is reported`() {
        val factory = FakeFactory()
        val depth = estimator(factory)
        assertTrue(depth.loadFrom(model.absolutePath))
        assertEquals(Backend.GPU, depth.activeBackend)
        assertEquals(listOf("open GPU", "run GPU"), factory.events) // warm-up run validates the backend
        depth.close()
        assertNull(depth.activeBackend)
        assertFalse(depth.isLoaded)
        assertEquals("close GPU", factory.events.last())
    }

    @Test
    fun `gpu init failure falls back to cpu truthfully`() {
        val factory = FakeFactory(failing = setOf(Backend.GPU))
        val depth = estimator(factory)
        assertTrue(depth.loadFrom(model.absolutePath))
        assertEquals(Backend.CPU, depth.activeBackend)
        assertEquals(listOf("open GPU", "open CPU", "run CPU"), factory.events)
        depth.close()
    }

    @Test
    fun `a gpu that fails its first inference is closed and replaced by cpu`() {
        val factory = FakeFactory(failFirstRun = setOf(Backend.GPU))
        val depth = estimator(factory)
        assertTrue(depth.loadFrom(model.absolutePath))
        assertEquals(Backend.CPU, depth.activeBackend)
        assertEquals(listOf("open GPU", "run GPU", "close GPU", "open CPU", "run CPU"), factory.events)
        depth.close()
    }

    @Test
    fun `unsupported model shapes and total failure fail soft`() {
        val wrongInput = estimator(FakeFactory(inShape = intArrayOf(1, 8, 8, 3)))
        assertFalse(wrongInput.loadFrom(model.absolutePath))
        assertNotNull(wrongInput.lastError)
        assertNull(wrongInput.estimateLuma(ByteArray(16), 4, 4))

        val nothing = estimator(FakeFactory(failing = setOf(Backend.GPU, Backend.CPU)))
        assertFalse(nothing.loadFrom(model.absolutePath))
        assertEquals("IllegalStateException: CPU unavailable", nothing.lastError)
        assertNull(nothing.activeBackend)

        assertFalse(estimator(FakeFactory()).loadFrom(File(model.parentFile, "missing.tflite").absolutePath))
    }

    @Test
    fun `luma input reaches the runtime as nhwc and the output maps row-major`() {
        val factory = FakeFactory()
        val depth = estimator(factory)
        assertTrue(depth.loadFrom(model.absolutePath))
        // 4x4 plane, every pixel 51 (= 0.2 after /255), mean 0 / std 1.
        val map = depth.estimateLuma(ByteArray(16) { 51 }, 4, 4, outMaxDim = 2)!!
        val input = factory.inputs.last()
        assertEquals(48, input.size)
        assertTrue(input.all { kotlin.math.abs(it - 0.2f) < 1e-6f })
        // GPU output values 0..15 on a 4x4 grid, stride 2 -> (0,0),(0,2),(2,0),(2,2).
        assertEquals(2, map.width)
        assertEquals(2, map.height)
        assertArrayEquals(floatArrayOf(0f, 2f, 8f, 10f), map.data, 0f)
        depth.close()
    }

    @Test
    fun `every runtime call runs on one depth thread whichever thread calls`() {
        val factory = FakeFactory()
        val depth = estimator(factory)
        val callers = Executors.newFixedThreadPool(3)
        try {
            callers.submit { depth.loadFrom(model.absolutePath) }.get()
            (1..6).map { callers.submit { depth.estimateLuma(ByteArray(16), 4, 4) } }.forEach { it.get() }
            depth.close()
        } finally {
            callers.shutdown()
        }
        assertEquals(8, factory.events.count { it.startsWith("run") } + factory.events.count { it.startsWith("open") })
        assertEquals(1, factory.threads.toSet().size)
        val depthThread = factory.threads.first()
        assertEquals("SphereSLAM-depth", depthThread.name)
        assertTrue(Thread.currentThread() !== depthThread)

        // close() ended that thread; a reload gets a fresh one, used consistently from then on.
        factory.threads.clear()
        assertTrue(depth.loadFrom(model.absolutePath))
        depth.estimateLuma(ByteArray(16), 4, 4)
        depth.close()
        assertEquals(1, factory.threads.toSet().size)
        assertTrue(factory.threads.first() !== depthThread)
    }
}

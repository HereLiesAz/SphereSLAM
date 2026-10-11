package com.hereliesaz.sphereslam.models

import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * One opened depth model on one backend. Every call — [open][Factory.open], [run] and [close] — is
 * made on the same thread ([DepthThread]); implementations may rely on that (the LiteRT GPU delegate
 * requires it). Kept behind this interface so [DepthEstimator]'s orchestration, fallback and
 * pre/post-processing are testable on the JVM, where no GPU (or LiteRT native library) exists.
 */
internal interface DepthRuntime : AutoCloseable {
    /** The backend this runtime actually executes on. */
    val backend: DepthEstimatorOptions.Backend

    /** Shape of the single float32 input tensor, e.g. `[1, 256, 256, 3]` (NHWC). */
    val inputShape: IntArray

    /** Shape of the single float32 output tensor, e.g. `[1, 256, 256, 1]`. */
    val outputShape: IntArray

    /** Run one inference: [input] holds `product(inputShape)` floats, [output] receives `product(outputShape)`. */
    fun run(input: FloatArray, output: FloatArray)

    fun interface Factory {
        /**
         * Open [model] on [backend]. Throws when that backend cannot be initialised (the estimator
         * then tries the next backend from [DepthEstimatorOptions.attempts]).
         */
        fun open(model: File, backend: DepthEstimatorOptions.Backend, options: DepthEstimatorOptions, gpuCache: GpuCache?): DepthRuntime
    }

    /** Where the GPU delegate may serialize its compiled programs, and the token naming this model's entry. */
    data class GpuCache(val directory: File, val modelToken: String)
}

/** GPU delegate settings derived from [DepthEstimatorOptions]; plain data so the mapping is unit-testable. */
internal data class GpuDelegateConfig(
    /** Run in fp16 (the weights are fp16 already); false forces fp32 arithmetic on the GPU. */
    val precisionLossAllowed: Boolean,
    /** `INFERENCE_PREFERENCE_SUSTAINED_SPEED` (repeated inference) rather than a fast single answer. */
    val sustainedSpeed: Boolean,
    /** Serialization directory for the compiled-program cache, or null for none. */
    val serializationDir: String?,
    val modelToken: String?,
)

/** CPU interpreter settings derived from [DepthEstimatorOptions]. */
internal data class CpuConfig(val threads: Int, val useXnnpack: Boolean)

/** [DepthRuntime] on LiteRT (TensorFlow Lite): GPU delegate, or the XNNPACK CPU path. */
internal class LiteRtDepthRuntime private constructor(
    private val interpreter: Interpreter,
    private val delegate: GpuDelegate?,
    override val backend: DepthEstimatorOptions.Backend,
) : DepthRuntime {

    override val inputShape: IntArray = interpreter.getInputTensor(0).shape()
    override val outputShape: IntArray = interpreter.getOutputTensor(0).shape()

    private val inputBytes = ByteBuffer.allocateDirect(4 * inputShape.fold(1) { a, b -> a * b }).order(ByteOrder.nativeOrder())
    private val outputBytes = ByteBuffer.allocateDirect(4 * outputShape.fold(1) { a, b -> a * b }).order(ByteOrder.nativeOrder())

    override fun run(input: FloatArray, output: FloatArray) {
        inputBytes.clear()
        inputBytes.asFloatBuffer().put(input, 0, inputBytes.capacity() / 4)
        outputBytes.clear()
        interpreter.run(inputBytes, outputBytes)
        outputBytes.rewind()
        outputBytes.asFloatBuffer().get(output, 0, outputBytes.capacity() / 4)
    }

    override fun close() {
        try {
            interpreter.close()
        } finally {
            delegate?.close() // after the interpreter that uses it
        }
    }

    object Factory : DepthRuntime.Factory {
        override fun open(
            model: File,
            backend: DepthEstimatorOptions.Backend,
            options: DepthEstimatorOptions,
            gpuCache: DepthRuntime.GpuCache?,
        ): DepthRuntime = when (backend) {
            DepthEstimatorOptions.Backend.GPU -> openGpu(model, options, gpuCache)
            DepthEstimatorOptions.Backend.CPU -> openCpu(model, options)
        }

        private fun openGpu(model: File, options: DepthEstimatorOptions, gpuCache: DepthRuntime.GpuCache?): DepthRuntime {
            val supported = CompatibilityList().use { it.isDelegateSupportedOnThisDevice }
            check(supported) { "LiteRT GPU delegate reports this device/driver as unsupported (compatibility list)" }
            val config = options.gpuDelegateConfig(gpuCache)
            val gpuOptions = GpuDelegateFactory.Options()
                .setPrecisionLossAllowed(config.precisionLossAllowed)
                .setInferencePreference(
                    if (config.sustainedSpeed) GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED
                    else GpuDelegateFactory.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER,
                )
            if (config.serializationDir != null && config.modelToken != null) {
                gpuOptions.setSerializationParams(config.serializationDir, config.modelToken)
            }
            val delegate = GpuDelegate(gpuOptions)
            val interpreter = try {
                // Only the delegate's fallback ops (none for MiDaS Small) would use these threads.
                Interpreter(model, Interpreter.Options().addDelegate(delegate).setNumThreads(1))
            } catch (t: Throwable) {
                delegate.close()
                throw t
            }
            return LiteRtDepthRuntime(interpreter, delegate, DepthEstimatorOptions.Backend.GPU)
        }

        private fun openCpu(model: File, options: DepthEstimatorOptions): DepthRuntime {
            val config = options.cpuConfig()
            val interpreterOptions = Interpreter.Options().setUseXNNPACK(config.useXnnpack)
            if (config.threads > 0) interpreterOptions.setNumThreads(config.threads)
            return LiteRtDepthRuntime(Interpreter(model, interpreterOptions), null, DepthEstimatorOptions.Backend.CPU)
        }
    }
}

/**
 * The single thread every [DepthRuntime] call runs on. GPU delegates are bound to the thread that
 * created them (an OpenGL context in the GL backend), so [DepthEstimator] never touches its runtime
 * from the caller's thread: work is handed to this thread and the caller blocks for the result,
 * whichever (and however many different) threads call in. Created on first use; [shutdown] ends the
 * thread, and a later [call] starts a fresh one (together with a freshly opened runtime).
 */
internal class DepthThread(private val name: String = "SphereSLAM-depth") {
    @Volatile
    private var thread: Thread? = null
    private var executor: ExecutorService? = null

    /** Whether the current thread is the depth thread (for assertions/tests). */
    fun isCurrent(): Boolean = Thread.currentThread() === thread

    /**
     * Run [block] on the depth thread and return its result (rethrowing what it threw). Waits
     * uninterruptibly — the native call cannot be abandoned half way — and restores the caller's
     * interrupt flag afterwards. Runs inline when already on the depth thread.
     */
    fun <T> call(block: () -> T): T {
        if (isCurrent()) return block()
        val future: Future<T> = submit(block)
        var interrupted = false
        try {
            while (true) {
                try {
                    return future.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** Stop the thread once queued work has run. Idempotent. */
    @Synchronized
    fun shutdown() {
        executor?.shutdown()
        executor = null
        thread = null
    }

    @Synchronized
    private fun <T> submit(block: () -> T): Future<T> = executor().submit(Callable { block() })

    private fun executor(): ExecutorService = executor ?: Executors.newSingleThreadExecutor { r ->
        Thread(r, name).also {
            it.isDaemon = true
            thread = it
        }
    }.also { executor = it }
}

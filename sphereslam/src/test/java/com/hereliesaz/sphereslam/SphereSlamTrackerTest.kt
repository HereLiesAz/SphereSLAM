package com.hereliesaz.sphereslam

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereSlamTrackerTest {
    @Test
    fun `packLuma removes row padding without moving source position`() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 99, 99, 4, 5, 6, 99, 99))
        val packed = SphereSlamTracker.packLuma(source, width = 3, height = 2, rowStride = 5)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), packed)
        assertEquals(0, source.position())
    }

    @Test
    fun `matching is asynchronous and publishes sidecar observation`() {
        val matched = CountDownLatch(1)
        val fake = object : SphereSlamTracker.Native {
            override val available = true
            override fun create(camera: SphereSlamTracker.CameraModel) = 7L
            override fun destroy(handle: Long) = Unit
            override fun setReference(
                handle: Long, luma: ByteArray, width: Int, height: Int, dpi: Float,
                pageNo: Int, imageNo: Int, maxFeatures: Int,
            ) = true
            override fun match(
                handle: Long, luma: ByteArray, timestampNs: Long,
            ): SphereSlamTracker.Observation {
                matched.countDown()
                return SphereSlamTracker.Observation(
                    timestampNs, 4, 0.25f, 31, FloatArray(12) { it.toFloat() }
                )
            }
            override fun clear(handle: Long) = true
        }

        SphereSlamTracker(fake).use { tracker ->
            tracker.configure(SphereSlamTracker.CameraModel(2, 2, 500f, 500f, 1f, 1f))

            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!tracker.isReferenceReady && System.nanoTime() < readyDeadline) {
                tracker.setReference(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 2, 2, 2)
                Thread.sleep(5)
            }
            assertTrue(tracker.isReferenceReady)

            tracker.submitFrame(
                ByteBuffer.wrap(byteArrayOf(4, 3, 2, 1)), 2, 2, 2, timestampNs = 123L
            )
            assertTrue(matched.await(2, TimeUnit.SECONDS))

            val observationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var observation = tracker.latestObservation()
            while (observation == null && System.nanoTime() < observationDeadline) {
                Thread.sleep(5)
                observation = tracker.latestObservation()
            }

            assertNotNull(observation)
            assertEquals(123L, observation!!.timestampNs)
            assertEquals(4, observation.pageNo)
            assertEquals(31, observation.inliers)
        }
    }
    @Test
    fun `rectified reference dimensions are independent from live camera dimensions`() {
        val referenceAdded = CountDownLatch(1)
        val fake = object : SphereSlamTracker.Native {
            override val available = true
            override fun create(camera: SphereSlamTracker.CameraModel) = 9L
            override fun destroy(handle: Long) = Unit
            override fun setReference(
                handle: Long, luma: ByteArray, width: Int, height: Int, dpi: Float,
                pageNo: Int, imageNo: Int, maxFeatures: Int,
            ): Boolean {
                assertEquals(640, width)
                assertEquals(320, height)
                referenceAdded.countDown()
                return true
            }
            override fun match(
                handle: Long, luma: ByteArray, timestampNs: Long,
            ): SphereSlamTracker.Observation? = null
            override fun clear(handle: Long) = true
        }

        SphereSlamTracker(fake).use { tracker ->
            tracker.configure(SphereSlamTracker.CameraModel(1280, 720, 900f, 900f, 640f, 360f))
            val configureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!tracker.isNativeAvailable && System.nanoTime() < configureDeadline) {
                Thread.sleep(1)
            }
            assertTrue(tracker.isNativeAvailable)
            tracker.setReference(
                ByteBuffer.wrap(ByteArray(640 * 320)),
                width = 640,
                height = 320,
                rowStride = 640,
                dpi = 80f,
            )
            assertTrue(referenceAdded.await(2, TimeUnit.SECONDS))
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!tracker.isReferenceReady && System.nanoTime() < readyDeadline) Thread.sleep(5)
            assertTrue(tracker.isReferenceReady)
            assertEquals(0.2032f, tracker.currentReferenceGeometry()!!.widthMeters, 0.0001f)
        }
    }

    /** Records native calls in order so atlas replacement can be asserted. */
    private class RecordingNative(override val available: Boolean = true) : SphereSlamTracker.Native {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        var creates = 0
        override fun create(camera: SphereSlamTracker.CameraModel): Long {
            creates++
            calls += "create"
            return 11L
        }
        override fun destroy(handle: Long) { calls += "destroy" }
        override fun setReference(
            handle: Long, luma: ByteArray, width: Int, height: Int, dpi: Float,
            pageNo: Int, imageNo: Int, maxFeatures: Int,
        ): Boolean {
            calls += "add:$pageNo"
            return true
        }
        override fun match(
            handle: Long, luma: ByteArray, timestampNs: Long,
        ): SphereSlamTracker.Observation? = null
        override fun clear(handle: Long): Boolean {
            calls += "clear"
            return true
        }
    }

    /** Runs a no-op through the single worker and waits, so every earlier task has finished. */
    private fun drain(worker: java.util.concurrent.ExecutorService) {
        worker.submit(Runnable {}).get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `clearReference then setReference replaces the native atlas instead of appending`() {
        val native = RecordingNative()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        SphereSlamTracker(native, worker).use { tracker ->
            tracker.configure(SphereSlamTracker.CameraModel(2, 2, 500f, 500f, 1f, 1f))
            tracker.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2, pageNo = 1)
            drain(worker)
            assertTrue(tracker.isReferenceReady)

            tracker.clearReference()
            assertFalse(tracker.isReferenceReady)
            drain(worker)
            assertFalse(tracker.isReferenceReady)

            tracker.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2, pageNo = 2)
            drain(worker)
            assertTrue(tracker.isReferenceReady)
            assertEquals(
                listOf("create", "clear", "add:1", "clear", "clear", "add:2"),
                native.calls.toList(),
            )
        }
    }

    @Test
    fun `unavailable native degrades configure and setReference to no-ops`() {
        val native = RecordingNative(available = false)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        SphereSlamTracker(native, worker).use { tracker ->
            tracker.configure(SphereSlamTracker.CameraModel(2, 2, 500f, 500f, 1f, 1f))
            tracker.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2)
            drain(worker)
            assertFalse(tracker.isNativeAvailable)
            assertFalse(tracker.isReferenceReady)
            assertEquals(0, native.creates)
        }
    }

    @Test
    fun `calls racing a shut-down worker are ignored instead of throwing`() {
        val native = RecordingNative()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val tracker = SphereSlamTracker(native, worker)
        // Simulate close() winning the race after the caller's closed-check: the executor rejects.
        worker.shutdownNow()
        val camera = SphereSlamTracker.CameraModel(2, 2, 500f, 500f, 1f, 1f)
        tracker.configure(camera)
        tracker.reset(camera)
        tracker.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2)
        tracker.clearReference()
        tracker.submitFrame(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2, timestampNs = 1L)
        tracker.close()
        assertEquals(0, native.creates)
        assertFalse(tracker.isReferenceReady)
    }
}

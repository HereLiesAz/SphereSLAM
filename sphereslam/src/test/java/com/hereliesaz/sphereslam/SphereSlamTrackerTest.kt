package com.hereliesaz.sphereslam

import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SphereSlamTracker] over a recording [KpmApi]: the tracker's own logic and the real
 * [KpmSphereSlamEngine] it now drives, down to the arguments each native call receives.
 */
class SphereSlamTrackerTest {

    /** Records native calls in order; answers matches with [matchPage] / [matchOut]. */
    private class RecordingApi(
        private val matchPage: Int = -1,
        private val matchOut: FloatArray = FloatArray(14),
        private val createHandle: Long = 11L,
        private val addResult: Int = 1,
    ) : KpmApi {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val matchedFrames: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val added: MutableList<List<Any>> = Collections.synchronizedList(mutableListOf())
        var created: Triple<Int, Int, SphereSlamCalibration>? = null
        var creates = 0

        override fun create(width: Int, height: Int, calibration: SphereSlamCalibration): Long {
            creates++
            created = Triple(width, height, calibration)
            calls += "create"
            return createHandle
        }

        override fun addPage(
            session: Long, luma: ByteBuffer, width: Int, height: Int, referenceDpi: Float,
            pageNo: Int, imageNo: Int, maxFeatures: Int,
        ): Int {
            check(luma.isDirect)
            val bytes = ByteArray(luma.remaining()).also { luma.duplicate().get(it) }
            added += listOf(bytes.toList(), width, height, referenceDpi, pageNo, imageNo, maxFeatures)
            calls += "add:$pageNo"
            return addResult
        }

        override fun match(session: Long, luma: ByteBuffer, out: FloatArray): Int {
            check(luma.isDirect)
            val bytes = ByteArray(luma.remaining()).also { luma.duplicate().get(it) }
            matchedFrames += bytes
            calls += "match:${bytes[0]}"
            matchOut.copyInto(out)
            return matchPage
        }

        override fun clear(session: Long): Boolean {
            calls += "clear"
            return true
        }

        override fun destroy(session: Long) {
            calls += "destroy"
        }
    }

    private fun tracker(api: KpmApi, worker: ExecutorService, available: Boolean = true) =
        SphereSlamTracker(SphereSlamTracker.Engines.kpm(api) { available }, worker)

    /** Runs a no-op through the single worker and waits, so every earlier task has finished. */
    private fun drain(worker: ExecutorService) {
        worker.submit(Runnable {}).get(2, TimeUnit.SECONDS)
    }

    private val camera = SphereSlamTracker.CameraModel(2, 2, 500f, 510f, 1f, 1.5f)

    @Test
    fun `packLuma removes row padding without moving source position`() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 99, 99, 4, 5, 6, 99, 99))
        val packed = SphereSlamTracker.packLuma(source, width = 3, height = 2, rowStride = 5)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), packed)
        assertEquals(0, source.position())
    }

    @Test
    fun `the engine is created with the camera's calibration`() {
        val api = RecordingApi()
        val worker = Executors.newSingleThreadExecutor()
        tracker(api, worker).use { t ->
            t.configure(camera)
            drain(worker)
            assertEquals(Triple(2, 2, SphereSlamCalibration(500f, 510f, 1f, 1.5f)), api.created)
        }
    }

    @Test
    fun `a match is published with the frame's timestamp and KPM's output mapped as before`() {
        val out = FloatArray(14) { it + 0.5f }.also { it[12] = 0.25f; it[13] = 31f }
        val api = RecordingApi(matchPage = 4, matchOut = out)
        val worker = Executors.newSingleThreadExecutor()
        tracker(api, worker).use { t ->
            t.configure(camera)
            t.setReference(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 2, 2, 2)
            drain(worker)
            assertTrue(t.isReferenceReady)
            t.submitFrame(ByteBuffer.wrap(byteArrayOf(4, 3, 2, 1)), 2, 2, 2, timestampNs = 123L)
            drain(worker)
            drain(worker)
            val o = t.latestObservation()
            assertNotNull(o)
            assertEquals(123L, o!!.timestampNs)
            assertEquals(4, o.pageNo)
            assertEquals(0.25f, o.error, 0f)
            assertEquals(31, o.inliers)
            assertArrayEquals(out.copyOfRange(0, 12), o.cameraFromPage3x4, 0f)
            assertEquals(listOf<Byte>(4, 3, 2, 1), api.matchedFrames.single().toList())
        }
    }

    @Test
    fun `a miss publishes nothing`() {
        val api = RecordingApi(matchPage = -1)
        val worker = Executors.newSingleThreadExecutor()
        tracker(api, worker).use { t ->
            t.configure(camera)
            t.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2)
            drain(worker)
            t.submitFrame(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2, timestampNs = 1L)
            drain(worker)
            drain(worker)
            assertNull(t.latestObservation())
        }
    }

    @Test
    fun `rectified reference dimensions and page settings reach KPM unchanged`() {
        val api = RecordingApi()
        val worker = Executors.newSingleThreadExecutor()
        tracker(api, worker).use { t ->
            t.configure(SphereSlamTracker.CameraModel(1280, 720, 900f, 900f, 640f, 360f))
            drain(worker)
            assertTrue(t.isNativeAvailable)
            t.setReference(
                ByteBuffer.wrap(ByteArray(640 * 320) { (it % 7).toByte() }),
                width = 640, height = 320, rowStride = 640, dpi = 80f, pageNo = 3, imageNo = 2, maxFeatures = 400,
            )
            drain(worker)
            assertTrue(t.isReferenceReady)
            val a = api.added.single()
            assertEquals(List(640 * 320) { (it % 7).toByte() }, a[0])
            assertEquals(listOf<Any>(640, 320, 80f, 3, 2, 400), a.drop(1))
            assertEquals(0.2032f, t.currentReferenceGeometry()!!.widthMeters, 0.0001f)
        }
    }

    @Test
    fun `a page KPM finds no features in is not ready`() {
        val api = RecordingApi(addResult = 0)
        val worker = Executors.newSingleThreadExecutor()
        tracker(api, worker).use { t ->
            t.configure(camera)
            t.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2)
            drain(worker)
            assertFalse(t.isReferenceReady)
            assertNull(t.currentReferenceGeometry())
        }
    }

    @Test
    fun `a native add failure is not ready instead of throwing on the worker`() {
        val api = RecordingApi(addResult = -3)
        val worker = Executors.newSingleThreadExecutor()
        tracker(api, worker).use { t ->
            t.configure(camera)
            t.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2)
            drain(worker)
            assertFalse(t.isReferenceReady)
        }
    }

    @Test
    fun `clearReference then setReference replaces the native atlas instead of appending`() {
        val api = RecordingApi()
        val worker = Executors.newSingleThreadExecutor()
        tracker(api, worker).use { t ->
            t.configure(camera)
            t.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2, pageNo = 1)
            drain(worker)
            assertTrue(t.isReferenceReady)

            t.clearReference()
            assertFalse(t.isReferenceReady)
            drain(worker)
            assertFalse(t.isReferenceReady)

            t.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2, pageNo = 2)
            drain(worker)
            assertTrue(t.isReferenceReady)
            assertEquals(listOf("create", "clear", "add:1", "clear", "clear", "add:2"), api.calls.toList())
        }
    }

    @Test
    fun `reset destroys the old native session and creates a new one`() {
        val api = RecordingApi()
        val worker = Executors.newSingleThreadExecutor()
        tracker(api, worker).use { t ->
            t.configure(camera)
            t.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2)
            drain(worker)
            t.reset(camera)
            drain(worker)
            assertFalse(t.isReferenceReady)
            assertEquals(listOf("create", "clear", "add:0", "destroy", "create"), api.calls.toList())
        }
        // close() destroys the session on the worker, then shuts it down.
        assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals("destroy", api.calls.last())
    }

    @Test
    fun `submitted frames are packed and matched in order`() {
        val api = RecordingApi()
        val worker = Executors.newSingleThreadExecutor()
        tracker(api, worker).use { t ->
            t.configure(camera)
            t.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2)
            drain(worker)
            for (i in 1..6) {
                // Row stride 3: the pack must still produce the tight 2x2 frame.
                t.submitFrame(ByteBuffer.wrap(byteArrayOf(i.toByte(), 0, 9, 0, 0, 9)), 2, 2, 3, timestampNs = i.toLong())
                drain(worker)
            }
            assertEquals((1..6).map { "match:$it" }, api.calls.filter { it.startsWith("match") })
            assertEquals(listOf<Byte>(6, 0, 0, 0), api.matchedFrames.last().toList())
        }
    }

    @Test
    fun `unavailable native degrades configure and setReference to no-ops`() {
        val api = RecordingApi()
        val worker = Executors.newSingleThreadExecutor()
        tracker(api, worker, available = false).use { t ->
            t.configure(camera)
            t.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2)
            drain(worker)
            assertFalse(t.isNativeAvailable)
            assertFalse(t.isReferenceReady)
            assertEquals(0, api.creates)
        }
    }

    @Test
    fun `a session the native layer cannot create leaves the tracker unready`() {
        val api = RecordingApi(createHandle = 0L)
        val worker = Executors.newSingleThreadExecutor()
        tracker(api, worker).use { t ->
            t.configure(camera)
            t.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2)
            drain(worker)
            assertFalse(t.isReferenceReady)
            assertEquals(listOf("create"), api.calls.toList())
        }
    }

    @Test
    fun `calls racing a shut-down worker are ignored instead of throwing`() {
        val api = RecordingApi()
        val worker = Executors.newSingleThreadExecutor()
        val t = tracker(api, worker)
        // Simulate close() winning the race after the caller's closed-check: the executor rejects.
        worker.shutdownNow()
        t.configure(camera)
        t.reset(camera)
        t.setReference(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2)
        t.clearReference()
        t.submitFrame(ByteBuffer.wrap(ByteArray(4)), 2, 2, 2, timestampNs = 1L)
        t.close()
        assertEquals(0, api.creates)
        assertFalse(t.isReferenceReady)
    }
}

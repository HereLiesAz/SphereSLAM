package com.hereliesaz.sphereslam.nativebridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hereliesaz.sphereslam.common.InternalSphereSlamApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The native session lifecycle on a real device (`./gradlew :core:nativebridge:connectedAndroidTest`
 * on an arm64-v8a / armeabi-v7a device or emulator image; skipped where libsphereslam has no ABI).
 * These are the only tests that load libsphereslam: the JVM unit tests run against fakes.
 */
@OptIn(InternalSphereSlamApi::class)
@RunWith(AndroidJUnit4::class)
class KpmBridgeInstrumentedTest {

    private val w = 320
    private val h = 240

    @Before
    fun requireNative() {
        assumeTrue("libsphereslam is not available on this ABI", KpmBridge.isAvailable())
    }

    /** Blocky random texture: plenty of FREAK corners at several scales. */
    private fun texture(width: Int, height: Int, seed: Int): ByteBuffer {
        val r = Random(seed)
        val bytes = ByteArray(width * height)
        val block = 8
        for (by in 0 until height / block) for (bx in 0 until width / block) {
            val v = r.nextInt(256).toByte()
            for (y in 0 until block) for (x in 0 until block) bytes[(by * block + y) * width + bx * block + x] = v
        }
        return ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
    }

    private fun session(): Long = KpmBridge.createCalibratedSession(w, h, 300f, 300f, w / 2f, h / 2f).also {
        assertNotEquals(0L, it)
    }

    @Test
    fun aPageMatchesItselfAndTheFeatureCapHolds() {
        val s = session()
        try {
            val page = texture(w, h, 1)
            val features = KpmBridge.addPlanarPage(s, page, w, h, 72f, pageNo = 0, imageNo = 0, maxFeatures = 50)
            assertTrue("features=$features", features in 1..50) // maxFeatures caps the FREAK path too
            val out = FloatArray(KpmBridge.MATCH_OUTPUT_FLOATS)
            assertEquals(0, KpmBridge.matchPlanar(s, page.duplicate(), out))
        } finally {
            KpmBridge.destroySession(s)
        }
    }

    @Test
    fun aFeaturelessPageIsRefusedAndNotMerged() {
        val s = session()
        try {
            val flat = ByteBuffer.allocateDirect(w * h).apply { put(ByteArray(w * h) { 90 }); flip() }
            assertTrue(KpmBridge.addPlanarPage(s, flat, w, h, 72f, 0, 0, 500) < 0)
            // The refused page left nothing behind: the same ids can be used for a real page.
            assertTrue(KpmBridge.addPlanarPage(s, texture(w, h, 2), w, h, 72f, 0, 0, 500) > 0)
            assertEquals(0, KpmBridge.matchPlanar(s, texture(w, h, 2), FloatArray(KpmBridge.MATCH_OUTPUT_FLOATS)))
        } finally {
            KpmBridge.destroySession(s)
        }
    }

    @Test
    fun aCloneMatchesNothingUntilItsFirstAddThenEveryPage() {
        val s = session()
        val page0 = texture(w, h, 3)
        val page1 = texture(w, h, 4)
        try {
            assertTrue(KpmBridge.addPlanarPage(s, page0, w, h, 72f, 0, 0, 500) > 0)
            val clone = KpmBridge.cloneSession(s)
            assertNotEquals(0L, clone)
            try {
                val out = FloatArray(KpmBridge.MATCH_OUTPUT_FLOATS)
                assertEquals(-1, KpmBridge.matchPlanar(clone, page0.duplicate(), out))
                assertTrue(KpmBridge.addPlanarPage(clone, page1, w, h, 72f, 1, 1, 500) > 0)
                assertEquals(0, KpmBridge.matchPlanar(clone, page0.duplicate(), out))
                assertEquals(1, KpmBridge.matchPlanar(clone, page1.duplicate(), out))
                // The source never saw page 1.
                assertEquals(-1, KpmBridge.matchPlanar(s, page1.duplicate(), out))
            } finally {
                KpmBridge.destroySession(clone)
            }
            // Destroying the clone leaves the source intact.
            assertEquals(0, KpmBridge.matchPlanar(s, page0.duplicate(), FloatArray(KpmBridge.MATCH_OUTPUT_FLOATS)))
        } finally {
            KpmBridge.destroySession(s)
        }
    }

    @Test
    fun aPreviewJoinsItsPageAndAFrameLikeItMatchesThatPage() {
        val s = session()
        val page = texture(w, h, 7)
        try {
            assertTrue(KpmBridge.addPlanarPage(s, page, w, h, 72f, 0, 0, 500) > 0)
            // The page turned 90°, h × w: preview pixel (u, v) shows page pixel (v, h − 1 − u), so every
            // feature's millimetres come back through pageFromView.
            val bytes = ByteArray(w * h).also { page.duplicate().get(it) }
            val turned = ByteArray(w * h)
            for (v in 0 until w) for (u in 0 until h) turned[v * h + u] = bytes[(h - 1 - u) * w + v]
            val view = ByteBuffer.allocateDirect(turned.size).apply { put(turned); flip() }
            val pageFromView = floatArrayOf(0f, 1f, 0f, -1f, 0f, (h - 1).toFloat(), 0f, 0f, 1f)
            val added = KpmBridge.addPlanarPageView(s, view, h, w, 0, 1, 300, pageFromView, h, 72f)
            assertTrue("added=$added", added > 0)
            // The page itself still matches as page 0, and so does a frame showing the preview's view.
            val out = FloatArray(KpmBridge.MATCH_OUTPUT_FLOATS)
            assertEquals(0, KpmBridge.matchPlanar(s, page.duplicate(), out))
            val frame = ByteBuffer.allocateDirect(w * h).apply {
                // The turned page cropped into the frame's top-left (it is h × w).
                val f = ByteArray(w * h) { 90 }
                for (v in 0 until minOf(w, h)) for (u in 0 until minOf(w, h)) f[v * w + u] = turned[v * h + u]
                put(f); flip()
            }
            assertEquals(0, KpmBridge.matchPlanar(s, frame, out))
        } finally {
            KpmBridge.destroySession(s)
        }
    }

    @Test
    fun theSinglePageHandlePathMatchesOnlyThatPage() {
        val s = session()
        val page0 = texture(w, h, 5)
        val page1 = texture(w, h, 6)
        try {
            assertTrue(KpmBridge.addPlanarPage(s, page0, w, h, 72f, 0, 0, 500) > 0)
            assertTrue(KpmBridge.addPlanarPage(s, page1, w, h, 72f, 1, 1, 500) > 0)
            val out = FloatArray(KpmBridge.MATCH_EX_OUTPUT_FLOATS)
            val corr = FloatArray(4 * 50)
            assertEquals(1, KpmBridge.matchPlanarEx(s, page1.duplicate(), 1, out, corr))
            assertTrue(out[14] > 0f)
            assertEquals(-1, KpmBridge.matchPlanarEx(s, page1.duplicate(), 0, out, null))
            assertEquals(-1, KpmBridge.matchPlanarEx(s, page1.duplicate(), 7, out, null)) // unknown page
        } finally {
            KpmBridge.destroySession(s)
        }
    }

    @Test
    fun destroyWhileMatchingOnAnotherThreadIsSafe() {
        repeat(20) { round ->
            val s = session()
            val page = texture(w, h, 10 + round)
            assertTrue(KpmBridge.addPlanarPage(s, page, w, h, 72f, 0, 0, 500) > 0)
            val pool = Executors.newFixedThreadPool(2)
            val started = CountDownLatch(1)
            val calls = AtomicInteger()
            val matcher = pool.submit {
                started.countDown()
                val out = FloatArray(KpmBridge.MATCH_OUTPUT_FLOATS)
                repeat(10) {
                    KpmBridge.matchPlanar(s, page.duplicate(), out) // -1 once destroyed, never a crash
                    calls.incrementAndGet()
                }
            }
            started.await(5, TimeUnit.SECONDS)
            KpmBridge.destroySession(s)
            KpmBridge.destroySession(s) // a second destroy is a no-op
            matcher.get(30, TimeUnit.SECONDS)
            pool.shutdown()
            assertEquals(10, calls.get())
            assertEquals(-1, KpmBridge.matchPlanar(s, page.duplicate(), FloatArray(KpmBridge.MATCH_OUTPUT_FLOATS)))
            assertFalse(KpmBridge.clearPages(s))
        }
    }
}

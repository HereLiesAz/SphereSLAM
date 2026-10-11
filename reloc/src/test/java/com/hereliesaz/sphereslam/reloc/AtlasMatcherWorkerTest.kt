package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.SphereSlamPoseMath
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.math.RigidMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class AtlasMatcherWorkerTest {

    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() = tasks.removeFirst().run()
        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }

    private class Fake(val features: Int = 100) : PlanarFrontEnd {
        val pages = mutableListOf<Int>()
        var closed = false
        override fun addPage(
            luma: ByteBuffer, width: Int, height: Int, referenceWidthUnits: Float,
            physicallyMetric: Boolean, pageNo: Int, canonicalFromPage: FloatArray,
        ): PhotosphereTrackingSession.RegisteredPage {
            pages += pageNo
            val dpi = SphereSlamPoseMath.dpiForReferenceWidth(width, referenceWidthUnits)
            return PhotosphereTrackingSession.RegisteredPage(pageNo, features, SphereSlamPoseMath.pageGeometry(width, height, dpi), physicallyMetric)
        }

        override fun match(luma: ByteBuffer, timestampNs: Long): PlanarMatchResult? = null
        override fun close() {
            closed = true
        }
    }

    private val intrinsics = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)

    private fun built(fake: Fake) = AtlasMatcherWorker.Outcome.Built(
        fake, intrinsics, fake.addPage(ByteBuffer.allocate(0), 4, 3, 1f, false, 0, RigidMath.identity()), emptyList(),
    )

    @Test
    fun `growth runs only when idle and its outcome is polled once`() {
        val q = Queue()
        val w = AtlasMatcherWorker(q)
        assertEquals(AtlasMatcherWorker.State.IDLE, w.state)
        assertTrue(w.submitGrowth { AtlasMatcherWorker.Outcome.GrowthSkipped("a") })
        assertEquals(AtlasMatcherWorker.State.GROWING, w.state)
        assertFalse(w.isIdle)
        assertFalse(w.submitGrowth { AtlasMatcherWorker.Outcome.GrowthSkipped("b") })
        assertNull(w.poll())
        q.runAll()
        assertEquals(AtlasMatcherWorker.State.READY, w.state)
        assertFalse(w.submitGrowth { AtlasMatcherWorker.Outcome.GrowthSkipped("c") }) // not before poll
        assertEquals("a", (w.poll() as AtlasMatcherWorker.Outcome.GrowthSkipped).diagnostic)
        assertNull(w.poll())
        assertEquals(AtlasMatcherWorker.State.IDLE, w.state)
    }

    @Test
    fun `a build supersedes an in-flight growth whose matcher is then released`() {
        val q = Queue()
        val w = AtlasMatcherWorker(q)
        val grownMatcher = Fake()
        val page = PhotosphereTrackingSession.AtlasPage(1, ByteArray(12), 4, 3, 1f, false, RigidMath.identity())
        w.submitGrowth { AtlasMatcherWorker.Outcome.Grown(grownMatcher, page, emptyList(), null, "grew") }
        val buildMatcher = Fake()
        assertTrue(w.submitBuild { built(buildMatcher) })
        assertEquals(AtlasMatcherWorker.State.BUILDING, w.state)
        assertTrue(w.isBuilding)
        q.runNext() // the stale growth finishes
        assertTrue(grownMatcher.closed)
        assertEquals(AtlasMatcherWorker.State.BUILDING, w.state)
        q.runNext()
        val outcome = w.poll() as AtlasMatcherWorker.Outcome.Built
        assertSame(buildMatcher, outcome.frontEnd)
        assertFalse(buildMatcher.closed) // the caller owns it now
    }

    @Test
    fun `invalidate releases a ready outcome on the worker`() {
        val q = Queue()
        val w = AtlasMatcherWorker(q)
        val fake = Fake()
        w.submitBuild { built(fake) }
        q.runAll()
        assertEquals(AtlasMatcherWorker.State.READY, w.state)
        w.invalidate()
        assertEquals(AtlasMatcherWorker.State.IDLE, w.state)
        assertNull(w.poll())
        assertFalse(fake.closed)
        q.runAll()
        assertTrue(fake.closed)
    }

    @Test
    fun `job failures become outcomes`() {
        val w = AtlasMatcherWorker(Executor(Runnable::run))
        w.submitBuild { throw ReferenceTooWeakException(3, 30) }
        assertTrue((w.poll() as AtlasMatcherWorker.Outcome.BuildFailed).error is ReferenceTooWeakException)
        w.submitGrowth { error("boom") }
        val skipped = w.poll() as AtlasMatcherWorker.Outcome.GrowthSkipped
        assertTrue(skipped.diagnostic, skipped.diagnostic.contains("boom"))
    }

    @Test
    fun `retire runs after queued jobs and close refuses new work`() {
        val q = Queue()
        val w = AtlasMatcherWorker(q)
        val order = mutableListOf<String>()
        val live = object : PlanarFrontEnd by Fake() {
            override fun close() {
                order += "close"
            }
        }
        w.submitGrowth { order += "fork"; AtlasMatcherWorker.Outcome.GrowthSkipped("x") }
        w.retire(live)
        w.close()
        assertFalse(w.submitBuild { built(Fake()) })
        assertFalse(w.submitGrowth { AtlasMatcherWorker.Outcome.GrowthSkipped("y") })
        q.runAll()
        assertEquals(listOf("fork", "close"), order)
        assertNull(w.poll()) // closed: the finished growth was dropped
    }

    @Test
    fun `growth job keeps a strong page and drops a weak one without touching the live matcher`() {
        val live = Fake()
        val luma = Random(4).let { r -> ByteArray(640 * 480) { r.nextInt(256).toByte() } }
        val view = RigidMath.identity().also { it[12] = -0.6f; it[14] = -1f }
        val rootWindow = PhotosphereAtlasGrowth.PageWindow(0, 0f, 0f, 1f, 0.75f)
        val geometry = PhotosphereAtlasGrowth.propose(view, intrinsics, 640, 480, 1f, 0.75f, listOf(rootWindow))!!
        val reference = PhotosphereTrackingSession.ReferenceImage(luma.copyOf(400 * 300), 400, 300)
        val created = mutableListOf<Fake>()
        fun factory(features: Int) = PlanarFrontEndFactory { Fake(features).also { created += it } }

        val grown = AtlasMatcherWorker.growth(
            live, factory(100), intrinsics, reference, emptyList(), 1, luma, 640, 480, geometry, TargetQualityConfig(), null,
        ) as AtlasMatcherWorker.Outcome.Grown
        assertEquals(listOf(0, 1), (grown.frontEnd as Fake).pages)
        assertTrue(grown.diagnostic, grown.diagnostic.contains("rebuilt=true")) // Fake cannot fork

        val weak = AtlasMatcherWorker.growth(
            live, factory(5), intrinsics, reference, emptyList(), 1, luma, 640, 480, geometry, TargetQualityConfig(), null,
        )
        assertTrue(weak is AtlasMatcherWorker.Outcome.GrowthSkipped)
        assertTrue(created.last().closed)
        assertTrue(live.pages.isEmpty())
        assertFalse(live.closed)
    }

    @Test
    fun `a real background thread publishes outcomes safely`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val w = AtlasMatcherWorker(pool)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val ran = AtomicBoolean(false)
            w.submitGrowth {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                ran.set(true)
                AtlasMatcherWorker.Outcome.GrowthSkipped("done")
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertNull(w.poll()) // the "analyzer" is never blocked by the job
            release.countDown()
            val polls = AtomicInteger()
            var outcome: AtlasMatcherWorker.Outcome? = null
            while (outcome == null && polls.incrementAndGet() < 5_000) {
                outcome = w.poll()
                if (outcome == null) Thread.sleep(1)
            }
            assertTrue(ran.get())
            assertEquals("done", (outcome as AtlasMatcherWorker.Outcome.GrowthSkipped).diagnostic)
        } finally {
            pool.shutdownNow()
        }
    }
}

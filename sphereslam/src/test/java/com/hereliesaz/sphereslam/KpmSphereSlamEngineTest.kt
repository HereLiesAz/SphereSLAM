package com.hereliesaz.sphereslam

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KpmSphereSlamEngineTest {
    private val calibration = SphereSlamCalibration(800f, 810f, 320f, 240f)

    @Test
    fun addPage_andMatch_useIndependentCalibratedNativeSession() {
        val api = FakeKpmApi()
        val engine = KpmSphereSlamEngine(4, 4, calibration, api)
        assertTrue(engine.isReady)
        assertEquals(calibration, api.createdWith)

        val reference = ByteBuffer.allocateDirect(16)
        val generated = engine.addPage(reference, 4, 4, PlanarPage(pageNo = 7))
        assertEquals(42, generated)

        val live = ByteBuffer.allocateDirect(16)
        val match = engine.match(live)
        assertNotNull(match)
        assertEquals(7, match!!.pageNo)
        assertEquals(9, match.inlierCount)
        assertEquals(0.25f, match.reprojectionError, 0f)
        assertEquals(12, match.cameraFromPage3x4.size)

        engine.close()
        assertFalse(engine.isReady)
        assertEquals(1, api.destroyCalls)
    }

    @Test
    fun noMatch_returnsNull_withoutAffectingSession() {
        val api = FakeKpmApi(matchPage = -1)
        val engine = KpmSphereSlamEngine(2, 2, calibration, api)

        assertNull(engine.match(ByteBuffer.allocateDirect(4)))
        assertTrue(engine.isReady)

        engine.close()
    }

    @Test
    fun fork_clonesTheNativeSessionIntoAnIndependentEngine() {
        val api = FakeKpmApi(cloneTo = 456L)
        val engine = KpmSphereSlamEngine(4, 4, calibration, api)
        engine.addPage(ByteBuffer.allocateDirect(16), 4, 4, PlanarPage(pageNo = 0))
        val fork = engine.fork()!!
        assertEquals(listOf(123L), api.clonedFrom)
        fork.addPage(ByteBuffer.allocateDirect(16), 4, 4, PlanarPage(pageNo = 1))
        assertEquals(listOf(123L, 456L), api.addedTo)
        fork.close()
        assertEquals(listOf(456L), api.destroyed)
        assertTrue(engine.isReady)
        engine.close()
        assertEquals(listOf(456L, 123L), api.destroyed)
        assertNull(engine.fork()) // closed
    }

    @Test
    fun fork_isNullWhenCloningFails() {
        val engine = KpmSphereSlamEngine(4, 4, calibration, FakeKpmApi(cloneTo = 0L))
        assertNull(engine.fork())
    }

    @Test
    fun standaloneSessionFork_carriesItsPagesAndMatchesThem() {
        val api = FakeKpmApi(cloneTo = 456L)
        val session = SphereSlamStandaloneSession(
            4, 4, calibration,
            SphereSlamStandaloneSession.EngineFactory { w, h, c -> KpmSphereSlamEngine(w, h, c, api) },
        )
        session.addReference(ByteBuffer.allocateDirect(16), 4, 4, 1f, false, pageNo = 7)
        val fork = session.fork()!!
        assertTrue(fork.hasReference)
        assertNotNull(fork.match(ByteBuffer.allocateDirect(16), 1L)) // page 7 is known to the fork
        fork.addReference(ByteBuffer.allocateDirect(16), 4, 4, 1f, false, pageNo = 8)
        assertEquals(456L, api.addedTo.last())
        fork.close()
        assertTrue(session.isReady)
        assertNotNull(session.match(ByteBuffer.allocateDirect(16), 2L))
        session.close()
        assertNull(session.fork())
    }

    @Test
    fun matchRequest_usesExtendedNativeMatch_andTrimsCorrespondences() {
        val api = FakeKpmApi()
        val engine = KpmSphereSlamEngine(4, 4, calibration, api)
        engine.addPage(ByteBuffer.allocateDirect(16), 4, 4, PlanarPage(pageNo = 7))

        val match = requireNotNull(engine.match(ByteBuffer.allocateDirect(16), PlanarMatchRequest(pageNo = 7, maxCorrespondences = 5)))
        assertEquals(7, api.exPage)
        assertEquals(20, api.exCorrCapacity)
        assertEquals(2, match.correspondenceCount)
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f), match.correspondences.toList())

        // The default request takes the original path (no correspondences).
        api.exPage = Int.MIN_VALUE
        assertEquals(0, engine.match(ByteBuffer.allocateDirect(16), PlanarMatchRequest())!!.correspondenceCount)
        assertEquals(Int.MIN_VALUE, api.exPage)
        engine.close()
    }

    private class FakeKpmApi(
        private val matchPage: Int = 7,
        private val cloneTo: Long = 0L,
    ) : KpmApi {
        val clonedFrom = mutableListOf<Long>()
        val addedTo = mutableListOf<Long>()
        val destroyed = mutableListOf<Long>()
        var destroyCalls = 0
        var createdWith: SphereSlamCalibration? = null

        override fun create(
            width: Int,
            height: Int,
            calibration: SphereSlamCalibration,
        ): Long {
            createdWith = calibration
            return 123L
        }

        override fun addPage(
            session: Long,
            luma: ByteBuffer,
            width: Int,
            height: Int,
            referenceDpi: Float,
            pageNo: Int,
            imageNo: Int,
            maxFeatures: Int,
        ): Int {
            addedTo += session
            return 42
        }

        override fun clone(session: Long): Long {
            clonedFrom += session
            return cloneTo
        }

        override fun match(session: Long, luma: ByteBuffer, out: FloatArray): Int {
            if (matchPage < 0) return -1
            for (i in 0 until 12) out[i] = i.toFloat()
            out[12] = 0.25f
            out[13] = 9f
            return matchPage
        }

        var exPage = Int.MIN_VALUE
        var exCorrCapacity = -1

        override fun matchEx(session: Long, luma: ByteBuffer, pageNo: Int, out: FloatArray, correspondences: FloatArray?): Int {
            exPage = pageNo
            exCorrCapacity = correspondences?.size ?: 0
            val page = match(session, luma, out)
            if (correspondences != null) for (i in 0 until 8) correspondences[i] = (i + 1).toFloat()
            out[14] = 2f
            return page
        }

        override fun destroy(session: Long) {
            destroyed += session
            destroyCalls++
        }
    }
}

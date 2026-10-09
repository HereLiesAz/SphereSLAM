package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session facade's pure pieces. The full [SphereSlamSession.onFrame] path needs native OpenCV
 * (the matcher) and a device; these cover the logic the wiring is built from.
 */
class SphereSlamSessionTest {

    @Test
    fun `reacquiring is true only once the lock is lost`() {
        assertTrue(SphereSlamSession.reacquiring(TrackingState.LOST))
        assertTrue(SphereSlamSession.reacquiring(TrackingState.REACQUIRING))
        assertFalse(SphereSlamSession.reacquiring(TrackingState.INITIALIZING))
        assertFalse(SphereSlamSession.reacquiring(TrackingState.LOCKED))
        assertFalse(SphereSlamSession.reacquiring(TrackingState.IMU_BRIDGE))
    }

    @Test
    fun `row-major to column-major transposes storage for the same transform`() {
        // A transform with a distinct value in every cell so a mis-indexed transpose is caught.
        val rowMajor = FloatArray(16) { it.toFloat() } // r[row*4+col] = row*4+col
        val col = SphereSlamSession.rowMajorToColumnMajor(rowMajor)
        // col[col*4+row] must equal r[row*4+col].
        for (row in 0 until 4) for (c in 0 until 4) {
            org.junit.Assert.assertEquals(rowMajor[row * 4 + c], col[c * 4 + row], 0f)
        }
    }

    @Test
    fun `identity round-trips and translation lands in the column-major translation slots`() {
        val identityRow = floatArrayOf(
            1f, 0f, 0f, 2f, // tx in row-major slot 3
            0f, 1f, 0f, 3f, // ty in slot 7
            0f, 0f, 1f, 4f, // tz in slot 11
            0f, 0f, 0f, 1f,
        )
        val col = SphereSlamSession.rowMajorToColumnMajor(identityRow)
        // Column-major translation lives at indices 12,13,14.
        assertArrayEquals(floatArrayOf(2f, 3f, 4f), floatArrayOf(col[12], col[13], col[14]), 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), floatArrayOf(col[0], col[5], col[10]), 0f)
    }
}

/**
 * The per-frame wiring through the internal matcher seam ([SphereSlamSession.processFrame]), so the
 * loop/predictor/photosphere orchestration and [SphereSlamSession.reset] run without native OpenCV.
 */
class SphereSlamSessionWiringTest {
    /** Never touched by the session's wiring; any access would need native OpenCV. */
    private object FakeFingerprint : Fingerprint {
        override val descriptors: org.opencv.core.Mat get() = throw UnsupportedOperationException()
        override val points3d: org.opencv.core.MatOfPoint3f get() = throw UnsupportedOperationException()
        override val binaryDescriptors: Boolean = true
    }

    private val identityQuat = floatArrayOf(0f, 0f, 0f, 1f)

    /** Row-major OpenCV camera-from-map: identity rotation, camera 2 m in front of the map origin. */
    private val cvPose = floatArrayOf(
        1f, 0f, 0f, 0.1f,
        0f, 1f, 0f, 0.2f,
        0f, 0f, 1f, 2f,
        0f, 0f, 0f, 1f,
    )

    private class Rig(maxBridgeMs: Long = 1_000L) {
        val map = com.hereliesaz.sphereslam.PhotosphereMap()
        val tile: com.hereliesaz.sphereslam.TileId
        val session: SphereSlamSession

        init {
            map.markUpdated(0f, 0f, 0L)
            tile = map.tileAt(0f, 0f)!!
            session = SphereSlamSession(
                evaluateFrame = { _, _ -> error("tests inject evaluation") },
                photosphere = map,
                loop = RobustTrackingLoop(
                    stateMachine = TrackingStateMachine(
                        TrackingStateConfig(confirmationFrames = 2, lostAfterMs = 500L, maxBridgeMs = maxBridgeMs),
                    ),
                ),
                reviewConfig = com.hereliesaz.sphereslam.PhotosphereReviewLoop.ReviewConfig(),
                relockSeedLimit = 8,
                predictor = AttitudePosePredictor(),
            )
            session.supplyTile(tile, FakeFingerprint)
        }
    }

    private fun Rig.frame(nowMs: Long, hit: Boolean, residual: Float = 1f): FloatArray? =
        session.processFrame(
            evaluate = { candidates ->
                if (hit && tile in candidates) {
                    TileMatcher.Evaluation(TileMatcher.Match(tile, cvPose, 40, residual), setOf(tile), emptySet())
                } else {
                    TileMatcher.Evaluation(null, emptySet(), candidates.keys)
                }
            },
            attitudeQuat = identityQuat,
            headingDeg = 0f,
            elevationDeg = 0f,
            frameTimestampNs = 0L,
            nowElapsedRealtimeNs = 0L,
            timestampSource = CameraTimestampSource.UNKNOWN,
            nowMs = nowMs,
        )

    @Test
    fun `chooseCandidates uses the view cone when tracking and relock seeds when lost`() {
        val rig = Rig()
        assertTrue(rig.tile in rig.session.chooseCandidates(TrackingState.LOCKED, 0f, 0f))
        assertEquals(rig.map.relockSeeds(8), rig.session.chooseCandidates(TrackingState.LOST, 180f, 0f))
    }

    @Test
    fun `two matches lock and return the OpenGL column-major pose`() {
        val rig = Rig()
        rig.frame(0L, hit = true)
        val pose = rig.frame(16L, hit = true)
        assertEquals(TrackingState.LOCKED, rig.session.trackingState)
        assertArrayEquals(SphereSlamSession.openCvToOpenGlColumnMajor(cvPose), pose, 1e-5f)
    }

    @Test
    fun `measured residual is gated by the acceptance policy`() {
        val rig = Rig()
        assertNull(rig.frame(0L, hit = true, residual = 500f))
        assertEquals(TrackingState.INITIALIZING, rig.session.trackingState)
    }

    @Test
    fun `reset also resets the robustness loop`() {
        val rig = Rig()
        rig.frame(0L, hit = true); rig.frame(16L, hit = true)
        rig.session.reset()
        assertEquals(TrackingState.INITIALIZING, rig.session.trackingState)
        rig.frame(32L, hit = true)
        // Without loop.reset() the loop would still be LOCKED and report LOCKED here.
        assertEquals(TrackingState.INITIALIZING, rig.session.trackingState)
    }

    @Test
    fun `bridge predicts during misses then stops drawing after the timeout`() {
        val rig = Rig(maxBridgeMs = 100L)
        rig.frame(0L, hit = true); rig.frame(16L, hit = true)
        assertNotNull(rig.frame(50L, hit = false))
        assertEquals(TrackingState.IMU_BRIDGE, rig.session.trackingState)
        assertNull(rig.frame(200L, hit = false))
        assertEquals(TrackingState.REACQUIRING, rig.session.trackingState)
    }

    @Test
    fun `OpenCV to OpenGL flips the y and z rows`() {
        val gl = SphereSlamSession.openCvToOpenGlColumnMajor(cvPose)
        // Column-major translation: x kept, y and z negated (OpenCV forward +z -> GL -z).
        assertArrayEquals(floatArrayOf(0.1f, -0.2f, -2f), floatArrayOf(gl[12], gl[13], gl[14]), 0f)
        assertArrayEquals(floatArrayOf(1f, -1f, -1f), floatArrayOf(gl[0], gl[5], gl[10]), 0f)
        // Camera centre is convention-independent.
        assertArrayEquals(floatArrayOf(-0.1f, -0.2f, -2f), PoseBlend.cameraCenter(gl), 1e-6f)
    }
}

package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertEquals
import org.junit.Test

/** A still phone holds its last lock past the bridge limit; a turned one doesn't. */
class TrackingStateMachineStillHoldTest {

    private fun locked(): TrackingStateMachine {
        val sm = TrackingStateMachine(TrackingStateConfig(confirmationFrames = 1, lostAfterMs = 2_000L, maxBridgeMs = 400L))
        sm.onAcceptedVisual(0L)
        assertEquals(TrackingState.LOCKED, sm.state)
        return sm
    }

    @Test
    fun `a still phone stays bridged long past the limit`() {
        val sm = locked()
        for (t in 100L..10_000L step 100L) {
            assertEquals("at $t ms", TrackingState.IMU_BRIDGE, sm.onVisualMiss(bridgeAvailable = true, nowMs = t, holdStill = true))
        }
    }

    @Test
    fun `without the hold the bridge still expires at the limit`() {
        val sm = locked()
        assertEquals(TrackingState.IMU_BRIDGE, sm.onVisualMiss(true, 100L))
        assertEquals(TrackingState.REACQUIRING, sm.onVisualMiss(true, 600L))
        assertEquals(TrackingState.LOST, sm.onVisualMiss(true, 2_500L))
    }

    @Test
    fun `turning after a long still hold reacquires from then, not from the old expiry`() {
        val sm = locked()
        sm.onVisualMiss(true, 100L, holdStill = true)
        sm.onVisualMiss(true, 9_000L, holdStill = true)
        // The phone turns: no more hold. Lost only lostAfterMs after this, not at once.
        assertEquals(TrackingState.REACQUIRING, sm.onVisualMiss(true, 9_100L, holdStill = false))
        assertEquals(TrackingState.REACQUIRING, sm.onVisualMiss(true, 10_500L, holdStill = false))
        assertEquals(TrackingState.LOST, sm.onVisualMiss(true, 11_200L, holdStill = false))
    }

    @Test
    fun `a match after a still hold relocks`() {
        val sm = locked()
        sm.onVisualMiss(true, 5_000L, holdStill = true)
        assertEquals(TrackingState.LOCKED, sm.onAcceptedVisual(5_100L))
    }
}

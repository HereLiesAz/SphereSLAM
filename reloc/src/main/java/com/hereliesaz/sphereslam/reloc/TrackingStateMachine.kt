package com.hereliesaz.sphereslam.reloc

/** Tracking lifecycle state. */
@ExperimentalSphereSlamRelocApi
enum class TrackingState {
    /** Acquiring the first lock; not yet confirmed. */
    INITIALIZING,

    /** Confirmed lock on the surface. */
    LOCKED,

    /** A brief visual miss from a lock, held by an IMU rotation bridge. */
    IMU_BRIDGE,

    /** Lost the lock recently; trying to reacquire. */
    REACQUIRING,

    /** Reacquisition has exceeded the patience window. */
    LOST,

    /** Unrecoverable error; terminal until [TrackingStateMachine.reset]. */
    FATAL,
}

/**
 * @property confirmationFrames consecutive accepted visual poses required to (re)lock (`>= 1`).
 * @property lostAfterMs how long to stay [TrackingState.REACQUIRING] before declaring
 *   [TrackingState.LOST] (`>= 0`).
 */
@ExperimentalSphereSlamRelocApi
data class TrackingStateConfig(
    val confirmationFrames: Int = 2,
    val lostAfterMs: Long = 2_000L,
) {
    init {
        require(confirmationFrames >= 1)
        require(lostAfterMs >= 0L)
    }
}

/**
 * Pure tracking-state hysteresis for a relocalization loop.
 *
 * Initial acquisition and post-loss reacquisition require [TrackingStateConfig.confirmationFrames]
 * consecutive good poses. A brief miss from a locked state becomes [TrackingState.IMU_BRIDGE] when a
 * bridge is available, rather than immediately lost; otherwise it enters [TrackingState.REACQUIRING]
 * and is only called [TrackingState.LOST] after [TrackingStateConfig.lostAfterMs]. Not thread-safe.
 */
@ExperimentalSphereSlamRelocApi
class TrackingStateMachine(
    private val config: TrackingStateConfig = TrackingStateConfig(),
) {
    /** The current state. */
    var state: TrackingState = TrackingState.INITIALIZING
        private set

    private var everLocked = false
    private var consecutiveAccepted = 0
    private var reacquireStartedMs: Long? = null

    /**
     * Record an accepted visual pose at [nowMs] (monotonic millis).
     *
     * @return the new [state].
     */
    fun onAcceptedVisual(nowMs: Long): TrackingState {
        if (state == TrackingState.FATAL) return state
        when (state) {
            TrackingState.LOCKED,
            TrackingState.IMU_BRIDGE -> {
                consecutiveAccepted = config.confirmationFrames
                everLocked = true
                reacquireStartedMs = null
                state = TrackingState.LOCKED
            }

            TrackingState.INITIALIZING,
            TrackingState.REACQUIRING,
            TrackingState.LOST -> {
                consecutiveAccepted++
                if (consecutiveAccepted >= config.confirmationFrames) {
                    everLocked = true
                    reacquireStartedMs = null
                    state = TrackingState.LOCKED
                } else if (everLocked) {
                    if (reacquireStartedMs == null) reacquireStartedMs = nowMs
                    state = TrackingState.REACQUIRING
                } else {
                    state = TrackingState.INITIALIZING
                }
            }

            TrackingState.FATAL -> Unit
        }
        return state
    }

    /**
     * Record a visual miss at [nowMs].
     *
     * @param bridgeAvailable whether an IMU rotation bridge can hold the pose through this miss.
     * @return the new [state].
     */
    fun onVisualMiss(bridgeAvailable: Boolean, nowMs: Long): TrackingState {
        if (state == TrackingState.FATAL) return state
        consecutiveAccepted = 0
        if (!everLocked) {
            state = TrackingState.INITIALIZING
            return state
        }
        if (bridgeAvailable) {
            state = TrackingState.IMU_BRIDGE
            return state
        }
        val started = reacquireStartedMs ?: nowMs.also { reacquireStartedMs = it }
        state = if (nowMs - started >= config.lostAfterMs) TrackingState.LOST else TrackingState.REACQUIRING
        return state
    }

    /** Enter the terminal [TrackingState.FATAL] state. */
    fun onFatal(): TrackingState {
        state = TrackingState.FATAL
        consecutiveAccepted = 0
        return state
    }

    /** Reset to [TrackingState.INITIALIZING], forgetting all history. */
    fun reset(): TrackingState {
        state = TrackingState.INITIALIZING
        everLocked = false
        consecutiveAccepted = 0
        reacquireStartedMs = null
        return state
    }
}

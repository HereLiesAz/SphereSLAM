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
 * @property maxBridgeMs longest an uninterrupted [TrackingState.IMU_BRIDGE] may hold a lock
 *   (`>= 0`). A rotation-only bridge drifts with any translation, so once a miss streak has been
 *   bridged this long further misses are treated as having no bridge: the state enters
 *   [TrackingState.REACQUIRING] (timed from the bridge expiry) and then [TrackingState.LOST] after
 *   [lostAfterMs].
 */
@ExperimentalSphereSlamRelocApi
data class TrackingStateConfig(
    val confirmationFrames: Int = 2,
    val lostAfterMs: Long = 2_000L,
    val maxBridgeMs: Long = 1_000L,
) {
    init {
        require(confirmationFrames >= 1)
        require(lostAfterMs >= 0L)
        require(maxBridgeMs >= 0L)
    }
}

/**
 * Pure tracking-state hysteresis for a relocalization loop.
 *
 * Initial acquisition and post-loss reacquisition require [TrackingStateConfig.confirmationFrames]
 * consecutive good poses. A brief miss from a locked state becomes [TrackingState.IMU_BRIDGE] when a
 * bridge is available, rather than immediately lost — but only for up to
 * [TrackingStateConfig.maxBridgeMs] of consecutive misses; without a bridge, or once it times out, it
 * enters [TrackingState.REACQUIRING] and is only called [TrackingState.LOST] after
 * [TrackingStateConfig.lostAfterMs]. Not thread-safe.
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
    private var bridgeStartedMs: Long? = null
    // The current bridge was carried past maxBridgeMs by a still hold (see onVisualMiss).
    private var heldStillPastLimit = false

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
                bridgeStartedMs = null
                heldStillPastLimit = false
                state = TrackingState.LOCKED
            }

            TrackingState.INITIALIZING,
            TrackingState.REACQUIRING,
            TrackingState.LOST -> {
                consecutiveAccepted++
                if (consecutiveAccepted >= config.confirmationFrames) {
                    everLocked = true
                    reacquireStartedMs = null
                    bridgeStartedMs = null
                    heldStillPastLimit = false
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
     * @param holdStill the phone has barely turned since the last lock: the bridge holds past
     *   [TrackingStateConfig.maxBridgeMs] (the last good pose is still where the target is). When the
     *   hold ends, the reacquire clock starts then, not at the long-past bridge expiry.
     * @return the new [state].
     */
    fun onVisualMiss(bridgeAvailable: Boolean, nowMs: Long, holdStill: Boolean = false): TrackingState {
        if (state == TrackingState.FATAL) return state
        consecutiveAccepted = 0
        if (!everLocked) {
            state = TrackingState.INITIALIZING
            return state
        }
        // A bridge only ever holds a miss streak that began from a lock (LOCKED or a running bridge);
        // once REACQUIRING/LOST, the stale reference must not be bridged again.
        val canBridge = state == TrackingState.LOCKED || state == TrackingState.IMU_BRIDGE
        if (bridgeAvailable && canBridge) {
            val bridgeStart = bridgeStartedMs ?: nowMs.also { bridgeStartedMs = it }
            if (nowMs - bridgeStart < config.maxBridgeMs) {
                state = TrackingState.IMU_BRIDGE
                return state
            }
            if (holdStill) {
                heldStillPastLimit = true
                state = TrackingState.IMU_BRIDGE
                return state
            }
            // Bridge expired: start the reacquire clock at the expiry, not at the original miss — or
            // now, when a still hold carried the bridge past its expiry.
            if (reacquireStartedMs == null) {
                reacquireStartedMs = if (heldStillPastLimit) nowMs else bridgeStart + config.maxBridgeMs
            }
        }
        bridgeStartedMs = null
        heldStillPastLimit = false
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
        bridgeStartedMs = null
        heldStillPastLimit = false
        return state
    }
}

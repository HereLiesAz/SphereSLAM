package com.hereliesaz.sphereslam.sidecar

import com.hereliesaz.sphereslam.math.RigidMath
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Fuses timestamp-aligned KPM sidecar observations ([HybridKpmCorrection.Accepted]) into the primary
 * tracker's (ARCore's) artwork backbone **without replacing the primary pose**.
 *
 * The fix is stored as a persistent **anchor-local** correction `L = backbone⁻¹ ∘ corrected` and
 * re-applied every frame as `fused = backbone ∘ L`. Under a global world rebase `G` both poses
 * become `G ∘ …` and `L` is unchanged, so — unlike a world-space correction — it never fights the
 * primary tracker renumbering its world.
 *
 * A **large** move (beyond [COLD_SNAP_DIST_M] / [COLD_SNAP_ANGLE_DEG] from what is drawn now) must
 * be confirmed by [HYBRID_REQUIRED_AGREEMENT] mutually [agrees]-ing observations within
 * [HYBRID_AGREEMENT_WINDOW_NS] before it applies: a single observation that clears every quality gate
 * can still be a wrong wall or a repeated pattern. Small corrections apply on one observation. A
 * confident cold relock hard-snaps; otherwise `L` is blended toward the new fix.
 *
 * Ported from GraffitiXR `PoseFusion` (its hybrid path). Matrices are column-major 4×4, rigid. Not
 * thread-safe; drive it from the render thread.
 */
class HybridAnchorFusion {

    /** What the last [currentAnchor] call did. */
    enum class State {
        /** No correction yet. */
        WAITING_FOR_LOCK,

        /** A confident cold relock replaced the correction outright. */
        COLD_SNAP,

        /** The correction was blended toward a new fix. */
        BLENDING,

        /** A correction stands and nothing new arrived. */
        HOLDING,

        /** A new observation was refused (quality below [MIN_INLIER_RATIO]); the standing correction holds. */
        RELOCK_REFUSED,

        /** A large move is pending until enough observations agree. */
        AWAITING_AGREEMENT,
    }

    /**
     * A snapshot of the last decision. Magnitudes are of the anchor-local correction (invariant under
     * world rebasing); `-1` when not applicable.
     */
    data class Diagnostics(
        val state: State,
        val lastAlpha: Float,
        val lastQuality: Float,
        val correctionMm: Float,
        val correctionDeg: Float,
        val accepted: Int,
        val rejected: Int,
        val agreeingObservations: Int,
        val requiredAgreement: Int,
    )

    private var correction: FloatArray? = null
    private var coldStart = true
    private var lastTimestampNs = Long.MIN_VALUE
    private val pending = ArrayList<FloatArray>(HYBRID_REQUIRED_AGREEMENT)
    private var pendingFirstNs = Long.MIN_VALUE
    private var pendingLastNs = Long.MIN_VALUE
    private var lastAgreement = -1
    private var lastState = State.WAITING_FOR_LOCK
    private var lastAlpha = -1f
    private var lastQuality = -1f
    private var accepted = 0
    private var rejected = 0

    /**
     * The standing anchor-local correction (a fresh copy), or null. Settable so a host that fuses a
     * second correction source into the same anchor can share one correction; setting clears any
     * pending agreement.
     */
    var localCorrection: FloatArray?
        get() = correction?.copyOf()
        set(value) {
            require(value == null || (value.size == 16 && value.all { it.isFinite() })) {
                "correction must be a finite column-major 4x4"
            }
            correction = value?.copyOf()
            clearPending()
        }

    /** Re-arm the cold (hard) snap — on session resume / tracking loss — so the next confident relock is instant. */
    fun markRelocalizing() {
        coldStart = true
    }

    /**
     * Clear the correction and re-arm the cold snap, as if new — for a new anchor/reference whose
     * local frame differs. Session-lifetime accepted/rejected counters are kept.
     */
    fun reset() {
        correction = null
        coldStart = true
        lastTimestampNs = Long.MIN_VALUE
        lastState = State.WAITING_FOR_LOCK
        lastAlpha = -1f
        lastQuality = -1f
        clearPending()
    }

    /** [currentAnchor] for an [HybridKpmCorrection.Accepted] decision. */
    fun currentAnchor(currentBackbone: FloatArray, observation: HybridKpmCorrection.Accepted): FloatArray =
        currentAnchor(
            currentBackbone = currentBackbone,
            backboneAtObservation = observation.backboneAtObservation,
            correctedAnchorAtObservation = observation.correctedAnchorWorld,
            observationTimestampNs = observation.timestampNs,
            confidence = observation.confidence,
            inliers = observation.inliers,
        )

    /**
     * Apply one timestamp-aligned KPM observation and return the fused anchor in the CURRENT world
     * frame. [backboneAtObservation] and [correctedAnchorAtObservation] are in the same world frame
     * at the observation instant; only their relative transform is kept. Re-presenting the same
     * [observationTimestampNs] on a later frame holds.
     */
    fun currentAnchor(
        currentBackbone: FloatArray,
        backboneAtObservation: FloatArray,
        correctedAnchorAtObservation: FloatArray,
        observationTimestampNs: Long,
        confidence: Float,
        inliers: Int,
    ): FloatArray {
        val isNew = observationTimestampNs > 0L && observationTimestampNs != lastTimestampNs
        if (isNew) {
            val quality = confidence.coerceIn(0f, 1f)
            lastQuality = quality
            if (quality < MIN_INLIER_RATIO) rejected++

            if (quality >= MIN_INLIER_RATIO) {
                val newLocal = RigidMath.multiply(RigidMath.rigidInverse(backboneAtObservation), correctedAnchorAtObservation)
                val correctedCurrent = RigidMath.multiply(currentBackbone, newLocal)
                val applied = correction?.let { RigidMath.multiply(currentBackbone, it) }
                val drawn = applied ?: currentBackbone
                if (diverged(drawn, correctedCurrent)) {
                    val anchorLocal = pending.firstOrNull()
                    // A timestamp before the last pending one is a clock discontinuity: old candidates
                    // belong to another clock and must not count toward agreement.
                    val expired = pendingFirstNs != Long.MIN_VALUE &&
                        (observationTimestampNs < pendingLastNs ||
                            observationTimestampNs - pendingFirstNs > HYBRID_AGREEMENT_WINDOW_NS)
                    if (anchorLocal != null && (expired || !agrees(anchorLocal, newLocal))) clearPending()
                    if (pending.isEmpty()) pendingFirstNs = observationTimestampNs
                    pending.add(newLocal)
                    pendingLastNs = observationTimestampNs
                    if (pending.size < HYBRID_REQUIRED_AGREEMENT) {
                        lastAgreement = pending.size
                        lastState = State.AWAITING_AGREEMENT
                        lastTimestampNs = observationTimestampNs
                        return RigidMath.multiply(currentBackbone, correction ?: RigidMath.identity())
                    }
                }
                clearPending()
                val cold = coldStart || applied == null || diverged(applied, correctedCurrent)
                val highConf = quality >= COLD_SNAP_INLIER_RATIO && inliers >= COLD_SNAP_MIN_INLIERS
                correction = if (cold && highConf) {
                    coldStart = false
                    lastState = State.COLD_SNAP
                    lastAlpha = -1f
                    newLocal
                } else {
                    val effConf = CONF_FLOOR + (1f - CONF_FLOOR) * quality
                    val alpha = (BASE_ALPHA * quality * effConf).coerceIn(0f, 1f)
                    lastState = State.BLENDING
                    lastAlpha = alpha
                    blend(correction ?: RigidMath.identity(), newLocal, alpha)
                }
                accepted++
            } else if (correction != null) {
                lastState = State.RELOCK_REFUSED
            } else if (lastState == State.AWAITING_AGREEMENT) {
                lastState = State.WAITING_FOR_LOCK
            }
            lastTimestampNs = observationTimestampNs
        } else if (correction != null && pending.isEmpty()) {
            lastState = State.HOLDING
        }
        return RigidMath.multiply(currentBackbone, correction ?: RigidMath.identity())
    }

    /** Re-apply the standing correction to [backbone] without consuming an observation. */
    fun holdCurrentAnchor(backbone: FloatArray): FloatArray {
        if (correction != null && pending.isEmpty()) lastState = State.HOLDING
        return RigidMath.multiply(backbone, correction ?: RigidMath.identity())
    }

    /** Agreeing large-correction observations collected so far, or -1 when none is pending. */
    fun agreementCount(): Int = lastAgreement

    /** True only when the most recent decision held a large move for agreement. */
    fun isAwaitingAgreement(): Boolean = lastState == State.AWAITING_AGREEMENT && pending.isNotEmpty()

    /** A snapshot of the last decision. */
    fun diagnostics(): Diagnostics {
        val d = correction
        return Diagnostics(
            state = lastState,
            lastAlpha = lastAlpha,
            lastQuality = lastQuality,
            correctionMm = d?.let { RigidMath.translationNorm(it) * 1000f } ?: -1f,
            correctionDeg = d?.let { RigidMath.rotationAngleDeg(it) } ?: -1f,
            accepted = accepted,
            rejected = rejected,
            agreeingObservations = lastAgreement,
            requiredAgreement = if (lastAgreement >= 0) HYBRID_REQUIRED_AGREEMENT else -1,
        )
    }

    private fun clearPending() {
        pending.clear()
        pendingFirstNs = Long.MIN_VALUE
        pendingLastNs = Long.MIN_VALUE
        lastAgreement = -1
    }

    companion object {
        /** Minimum observation quality for a fix to be trusted at all. */
        const val MIN_INLIER_RATIO = 0.5f

        /** Base smoothing rate for a (non-cold) correction update. */
        const val BASE_ALPHA = 0.25f

        /** Floor of the effective confidence that scales blending: `CONF_FLOOR + (1 − CONF_FLOOR)·quality`. */
        const val CONF_FLOOR = 0.5f

        /** A cold (hard) snap requires at least this quality … */
        const val COLD_SNAP_INLIER_RATIO = 0.7f

        /** … and at least this many absolute inliers. */
        const val COLD_SNAP_MIN_INLIERS = 20f

        /** Translation gap (m) between what is drawn and the new fix that counts as a relock / large move. */
        const val COLD_SNAP_DIST_M = 0.20f

        /** Rotation gap (deg) that counts as a relock / large move. */
        const val COLD_SNAP_ANGLE_DEG = 15f

        /** Observations that must agree before a large correction applies (~0.3 s at a 10 Hz sidecar). */
        const val HYBRID_REQUIRED_AGREEMENT = 3

        /** Two candidate local corrections agree when within this translation (m) … */
        const val HYBRID_AGREEMENT_DIST_M = 0.05f

        /** … and this rotation (deg) of the first pending candidate. */
        const val HYBRID_AGREEMENT_ANGLE_DEG = 3f

        /** Pending evidence older than this (observation-clock ns) no longer counts. */
        const val HYBRID_AGREEMENT_WINDOW_NS = 1_500_000_000L

        /** True when two anchor-local corrections are within the agreement tolerances. */
        fun agrees(a: FloatArray, b: FloatArray): Boolean {
            val delta = RigidMath.multiply(RigidMath.rigidInverse(a), b)
            return RigidMath.translationNorm(delta) <= HYBRID_AGREEMENT_DIST_M &&
                RigidMath.rotationAngleDeg(delta) <= HYBRID_AGREEMENT_ANGLE_DEG
        }

        /** Smoothed interpolation between two rigid poses (translation lerp + quaternion nlerp). */
        fun blend(current: FloatArray, target: FloatArray, alpha: Float): FloatArray {
            val t = RigidMath.lerp(RigidMath.translationOf(current), RigidMath.translationOf(target), alpha)
            val q = RigidMath.nlerpQuat(RigidMath.matrixToQuaternion(current), RigidMath.matrixToQuaternion(target), alpha)
            return RigidMath.fromQuaternionTranslation(q, t)
        }

        /**
         * True if two rigid model matrices differ in translation or rotation beyond the cold-snap
         * thresholds. Compares translation columns, which for model (world-from-anchor) matrices are
         * the anchor positions.
         */
        fun diverged(a: FloatArray, b: FloatArray): Boolean {
            val ta = RigidMath.translationOf(a); val tb = RigidMath.translationOf(b)
            val dx = ta[0] - tb[0]; val dy = ta[1] - tb[1]; val dz = ta[2] - tb[2]
            if (sqrt(dx * dx + dy * dy + dz * dz) >= COLD_SNAP_DIST_M) return true
            val qa = RigidMath.matrixToQuaternion(a); val qb = RigidMath.matrixToQuaternion(b)
            val dot = abs(qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3]).coerceIn(0f, 1f)
            return Math.toDegrees(2.0 * acos(dot.toDouble())).toFloat() >= COLD_SNAP_ANGLE_DEG
        }
    }
}

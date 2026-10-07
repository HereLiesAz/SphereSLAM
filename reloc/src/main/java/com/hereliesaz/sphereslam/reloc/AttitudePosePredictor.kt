package com.hereliesaz.sphereslam.reloc

/**
 * A cheap, low-latency pose predictor that hides the lag of a slower, higher-quality pose source
 * (KPM / relocalization).
 *
 * The problem: a visual solve takes tens of milliseconds and arrives in bursts, so content anchored
 * straight off it lags the camera and pops when a solve lands. The fix: between solves, extrapolate
 * the last good pose by the device's **drift-free, magnetometer-fused attitude**, which is available
 * every frame for almost nothing. [predict] returns an immediate pose each frame; [correct] folds in
 * a real solve when it arrives, re-basing future predictions on truth.
 *
 * Because the reference attitude is absolute (not dead-reckoned), this holds for as long as you like —
 * it is the long-lived successor to a short gyro bridge, and doubles as the off-page orientation
 * bridge (keep predicting while no page matches). Honest limit: it extrapolates **rotation** only;
 * translation is carried from the last solve, so a sideways step is not recovered until the next
 * [correct]. Reconcile visibly-large corrections with a smoother ([PoseStabilizer]) if a hard snap
 * is undesirable.
 *
 * Pure math (quaternions + the [RotationDeltaMath] view rotation); no sensor, no OpenCV, testable.
 * Not thread-safe; drive it from one worker. [reset] clears the reference on a new session.
 */
class AttitudePosePredictor {

    private var referenceView: FloatArray? = null
    private var referenceAttitude: FloatArray? = null

    /** Whether a reference pose has been installed (via [correct]) and prediction is possible. */
    val hasReference: Boolean get() = referenceView != null

    /**
     * Install a high-quality pose as the new prediction reference, paired with the device attitude at
     * the frame it was solved for. Future [predict] calls extrapolate from here.
     *
     * @param view the solved camera-from-world view, column-major length 16.
     * @param attitudeQuat the device attitude `[x, y, z, w]` at that frame.
     */
    fun correct(view: FloatArray, attitudeQuat: FloatArray) {
        require(view.size == 16) { "view must be length 16" }
        require(attitudeQuat.size == 4) { "attitude must be a quaternion length 4" }
        referenceView = view.copyOf()
        referenceAttitude = RotationDeltaMath.normalize(attitudeQuat)
    }

    /**
     * The predicted current view for [attitudeQuat], the reference pose rotated by the attitude delta
     * since [correct]. Null until the first [correct].
     *
     * @param attitudeQuat the device attitude `[x, y, z, w]` now.
     */
    fun predict(attitudeQuat: FloatArray): FloatArray? {
        require(attitudeQuat.size == 4) { "attitude must be a quaternion length 4" }
        val view = referenceView ?: return null
        val ref = referenceAttitude ?: return null
        val delta = RotationDeltaMath.cameraRotationDelta(ref, attitudeQuat)
        return RotationDeltaMath.rotateViewKeepingCameraCenter(view, delta)
    }

    /** Forget the reference (new session / reference change), so [predict] returns null until re-corrected. */
    fun reset() {
        referenceView = null
        referenceAttitude = null
    }
}

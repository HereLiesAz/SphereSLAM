package com.hereliesaz.sphereslam.gyro

import com.hereliesaz.sphereslam.camera.ScreenIntrinsics
import com.hereliesaz.sphereslam.math.RotationMath
import kotlin.math.abs

/**
 * Gyro stabilisation of a screen-space overlay: a gyro orientation delta in, the screen-space
 * homography that keeps a drawn design pinned to the wall out. Pure math, unit-tested on the JVM.
 * Ported from GraffitiXR's `OverlayGyroCompensationMath`.
 *
 * **Model.** The phone sits on a tripod and only jitters by fractions of a degree. For a camera
 * that only ROTATES, every scene point — whatever its depth — moves on the image by the infinite
 * homography `H = K · R · K⁻¹` (K the pinhole intrinsics in SCREEN pixels, R the camera rotation
 * since the reference). That is exact for pure rotation, so it needs no depth at all. A small
 * camera TRANSLATION `t` would add the plane-induced parallax term: `H = K · (R + t·nᵀ/d) · K⁻¹`
 * for a wall with normal `n` at distance `d`. Translation is not measured (bridging it from the
 * accelerometer means double integration, drift growing with time²), so the caller passes
 * `translationOverDepth = null` and the term is off; it is kept here, tested, so a future
 * translation source plugs in without touching the projection.
 *
 * **Axes.** Input rotations are in the phone's BODY axes as Android's rotation-vector sensors define
 * them (x right, y up, z out of the screen, in the device's natural orientation) — i.e. the
 * `conj(qNow)·qRef` delta [RotationMath.cameraRotationDelta] returns for two
 * [com.hereliesaz.sphereslam.attitude.AttitudeSample]s. [bodyToDisplay] turns that into the axes
 * of the CURRENT display rotation (the same remap `SensorManager.remapCoordinateSystem` documents per
 * `Surface.ROTATION_*`), and [homography] flips y/z into the computer-vision camera frame (x right,
 * y down, z into the scene — the rear camera looks out of the BACK, along body −z) before applying K.
 *
 * Matrices are `FloatArray(9)`, row-major, matching [RotationMath]. A homography's output is
 * normalised so `h[8] == 1` and is ready for `android.graphics.Matrix.setValues`.
 */
object GyroCompensationMath {

    /**
     * Beyond this much rotation since the reference the phone was bumped or picked up, not
     * vibrating: tripod jitter is well under a degree. The caller releases stabilisation rather than drag
     * the design several degrees across the wall on a sensor reading it can't sanity-check.
     */
    const val DEFAULT_RELEASE_DEGREES = 3f

    /**
     * Horizontal field of view (across the sensor's long side) assumed when Camera2 reports no
     * usable intrinsics: a typical phone main camera sits around 65–75°. Only the magnitude of the
     * compensation depends on it (a 10 % focal error leaves a 10 % residual of an already-tiny
     * shift), never its direction.
     */
    const val FALLBACK_LONG_SIDE_FOV_DEGREES = ScreenIntrinsics.FALLBACK_LONG_SIDE_FOV_DEGREES

    /** Outcome of [compensate]. */
    sealed class Result {
        /** Within the tripod-jitter envelope: draw the design through [homography] (row-major 3×3, `h[8] == 1`). */
        class Within(homography: FloatArray, val angleDegrees: Float) : Result() {
            private val h = homography.copyOf()

            /** A fresh copy of the homography. */
            val homography: FloatArray get() = h.copyOf()
        }

        /** Moved more than the release threshold: the caller should release stabilisation. */
        class Exceeded(val angleDegrees: Float) : Result()
    }

    /**
     * Body-to-display axis remap for a `Surface.ROTATION_*` expressed in degrees (0/90/180/270):
     * `v_display = M · v_body`, i.e. a rotation about z by +rotationDeg.
     *
     * For 90 that is `x' = −y, y' = x`. `ROTATION_90` means the device was turned 90° COUNTER-
     * clockwise (`Display.getRotation`'s documented example), so its top edge points left — screen
     * right is body −y — and its right edge points up — screen up is body +x. That is exactly what
     * `remapCoordinateSystem(inR, AXIS_Y, AXIS_MINUS_X, outR)` computes (its arguments name where
     * each DEVICE axis lands: device x → new +y, device y → new −x).
     *
     * This used to negate the angle, reading those arguments the other way round (`x' = y, y' = −x`),
     * which reversed every pan/tilt compensation in landscape.
     */
    fun bodyToDisplay(displayRotationDeg: Int): FloatArray =
        RotationMath.rotationAboutZ(normalizeQuarterTurn(displayRotationDeg))

    /** Re-express a body-axes rotation delta in the display's axes: `M · ΔR · Mᵀ`. */
    fun displayDelta(deltaBody: FloatArray, displayRotationDeg: Int): FloatArray {
        val m = bodyToDisplay(displayRotationDeg)
        return RotationMath.multiplyMat3(
            RotationMath.multiplyMat3(m, deltaBody),
            RotationMath.transposeMat3(m),
        )
    }

    /** Total rotation angle of a 3x3 rotation matrix, in degrees, from its trace. */
    fun rotationAngleDegrees(r: FloatArray): Float = RotationMath.rotationAngleDegrees(r)

    /** [ScreenIntrinsics.fitCenter]; kept here so the gyro step reads as one API. */
    fun screenIntrinsics(
        imageFx: Float,
        imageFy: Float,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): FloatArray = ScreenIntrinsics.fitCenter(imageFx, imageFy, imageWidth, imageHeight, viewWidth, viewHeight)

    /** [ScreenIntrinsics.fallback]. */
    fun fallbackScreenIntrinsics(viewWidth: Int, viewHeight: Int): FloatArray =
        ScreenIntrinsics.fallback(viewWidth, viewHeight)

    /**
     * `H = K · (R_cv + t·nᵀ) · K⁻¹` with `n = (0, 0, 1)` (a wall facing the camera — the tripod
     * case), mapping where a scene point was on screen at the reference to where it is now.
     *
     * @param deltaDisplay camera rotation since the reference in DISPLAY axes ([displayDelta]):
     *   `v_now = ΔR · v_ref` for a fixed world direction.
     * @param translationOverDepth camera-frame (CV axes) translation divided by the wall distance,
     *   in the `X_now = R·X_ref + t` convention, or null for none. Unitless, so a RELATIVE depth
     *   (like MiDaS's) can only feed it once translation is known in the same units. Off by default.
     */
    fun homography(
        deltaDisplay: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        translationOverDepth: FloatArray? = null,
    ): FloatArray {
        // Display/GL camera axes (y up, z toward the viewer) → CV axes (y down, z into the scene):
        // conjugate by C = diag(1, −1, −1), i.e. flip the sign of every entry whose row and column
        // signs differ.
        val sign = floatArrayOf(1f, -1f, -1f)
        val m = FloatArray(9) { i -> sign[i / 3] * sign[i % 3] * deltaDisplay[i] }
        if (translationOverDepth != null) {
            // + t · nᵀ with n = e3 touches only the third column.
            m[2] += translationOverDepth[0]
            m[5] += translationOverDepth[1]
            m[8] += translationOverDepth[2]
        }
        val k = floatArrayOf(fx, 0f, cx, 0f, fy, cy, 0f, 0f, 1f)
        val kInv = floatArrayOf(1f / fx, 0f, -cx / fx, 0f, 1f / fy, -cy / fy, 0f, 0f, 1f)
        val h = RotationMath.multiplyMat3(RotationMath.multiplyMat3(k, m), kInv)
        val w = h[8]
        if (abs(w) < 1e-9f) return h
        return FloatArray(9) { h[it] / w }
    }

    /**
     * The whole per-frame step: body delta → display delta → angle check → homography.
     * Returns [Result.Exceeded] (and no transform) past [releaseDegrees].
     */
    fun compensate(
        deltaBody: FloatArray,
        displayRotationDeg: Int,
        screenIntrinsics: FloatArray,
        releaseDegrees: Float = DEFAULT_RELEASE_DEGREES,
        translationOverDepth: FloatArray? = null,
    ): Result {
        val angle = rotationAngleDegrees(deltaBody)
        if (angle > releaseDegrees) return Result.Exceeded(angle)
        val delta = displayDelta(deltaBody, displayRotationDeg)
        val (fx, fy, cx, cy) = screenIntrinsics
        return Result.Within(homography(delta, fx, fy, cx, cy, translationOverDepth), angle)
    }

    /**
     * Median MiDaS value in a small window around a normalised point of a row-major depth map (MiDaS
     * returns INVERSE relative depth: larger = nearer), divided by the whole map's median — so 1 is
     * "as far as the typical scene point", above 1 nearer. Null for an empty or non-finite map.
     * Relative, unitless: it can scale a relative translation but is never a distance in metres.
     */
    fun relativeInverseDepthAt(
        data: FloatArray,
        width: Int,
        height: Int,
        nx: Float,
        ny: Float,
        radius: Int = 4,
    ): Float? {
        if (width <= 0 || height <= 0 || data.size != width * height) return null
        val px = (nx.coerceIn(0f, 1f) * (width - 1)).toInt()
        val py = (ny.coerceIn(0f, 1f) * (height - 1)).toInt()
        val patch = ArrayList<Float>()
        for (y in (py - radius)..(py + radius)) {
            if (y !in 0 until height) continue
            for (x in (px - radius)..(px + radius)) {
                if (x !in 0 until width) continue
                val v = data[y * width + x]
                if (v.isFinite()) patch += v
            }
        }
        val all = data.filter { it.isFinite() }
        if (patch.isEmpty() || all.isEmpty()) return null
        val sceneMedian = median(all)
        if (sceneMedian <= 0f) return null
        return median(patch) / sceneMedian
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    private fun normalizeQuarterTurn(deg: Int): Int = ((deg % 360) + 360) % 360
}

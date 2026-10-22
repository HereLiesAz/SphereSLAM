package com.hereliesaz.sphereslam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Estimates [CameraIntrinsics] from Camera2 `CameraCharacteristics` — what ARCore's
 * `Camera.getImageIntrinsics()` supplies for free and CameraX does not.
 *
 * **Precision, in descending order:**
 * 1. `LENS_INTRINSIC_CALIBRATION` — a per-device `[fx, fy, cx, cy, skew]` (skew dropped), defined
 *    against `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`. Optional; many devices omit it.
 * 2. Otherwise the pinhole approximation `fx = focalLengthMm · pixelArrayWidth / sensorWidthMm`
 *    (and the y equivalent) with a centred principal point, defined against the full pixel array.
 *    A real approximation — not photogrammetry-grade calibration.
 *
 * **Stream geometry.** The requested frame size is rarely the sensor array size. Per the Camera2
 * documentation, output streams come from the crop region (`SCALER_CROP_REGION`), which is defined
 * relative to — and by default equal to — the **active array** (`SENSOR_INFO_ACTIVE_ARRAY_SIZE`); a
 * stream is the largest **centred** region of it with the stream's aspect ratio, scaled uniformly.
 * [computeIntrinsics] models exactly that: the principal point is first moved into active-array
 * coordinates (the calibration is relative to the pre-correction array, the pinhole fallback to the
 * full pixel array; both rectangles are given in pixel-array coordinates), then an aspect-changing
 * request (a 16:9 stream from a 4:3 sensor) crops the active array, so `fx`/`fy` scale uniformly and
 * the principal point shifts by the crop offset. (Earlier GraffitiXR code scaled x and y
 * independently, which squashed the focal lengths on any aspect change.) A non-default
 * `SCALER_CROP_REGION` / digital zoom and lens distortion (or its correction) are not modelled.
 *
 * **Caching.** `CameraManager.getCameraCharacteristics` is a binder call; [estimate] reads it once
 * per camera id and caches the raw numbers ([RawCalibration]), so calling it per frame costs only
 * the arithmetic. [clearCache] drops the cache (e.g. after a camera-service reconnect).
 *
 * Results are in the raw sensor-buffer orientation; use [CaptureRotation] /
 * [CameraIntrinsicsTransforms] to follow a crop or rotation of the image.
 */
object CameraIntrinsicsEstimator {

    private const val TAG = "CameraIntrinsics"

    /**
     * The Camera2 numbers [computeIntrinsics] needs, read once per camera. Absent values are null
     * (or `0` for array sizes).
     */
    data class RawCalibration(
        val calibration: FloatArray?,
        val pixelArrayWidth: Int,
        val pixelArrayHeight: Int,
        val focalLengthMm: Float?,
        val sensorWidthMm: Float?,
        val sensorHeightMm: Float?,
        val calibrationArrayWidth: Int,
        val calibrationArrayHeight: Int,
        /** Pre-correction array origin in pixel-array coordinates ([calibration]'s origin). */
        val calibrationArrayLeft: Int = 0,
        val calibrationArrayTop: Int = 0,
        /** `SENSOR_INFO_ACTIVE_ARRAY_SIZE` in pixel-array coordinates; width/height `0` = unknown. */
        val activeArrayLeft: Int = 0,
        val activeArrayTop: Int = 0,
        val activeArrayWidth: Int = 0,
        val activeArrayHeight: Int = 0,
    ) {
        override fun equals(other: Any?): Boolean =
            other is RawCalibration &&
                (calibration?.contentEquals(other.calibration) ?: (other.calibration == null)) &&
                pixelArrayWidth == other.pixelArrayWidth && pixelArrayHeight == other.pixelArrayHeight &&
                focalLengthMm == other.focalLengthMm && sensorWidthMm == other.sensorWidthMm &&
                sensorHeightMm == other.sensorHeightMm &&
                calibrationArrayWidth == other.calibrationArrayWidth &&
                calibrationArrayHeight == other.calibrationArrayHeight &&
                calibrationArrayLeft == other.calibrationArrayLeft && calibrationArrayTop == other.calibrationArrayTop &&
                activeArrayLeft == other.activeArrayLeft && activeArrayTop == other.activeArrayTop &&
                activeArrayWidth == other.activeArrayWidth && activeArrayHeight == other.activeArrayHeight

        override fun hashCode(): Int {
            var h = calibration?.contentHashCode() ?: 0
            h = 31 * h + pixelArrayWidth
            h = 31 * h + pixelArrayHeight
            h = 31 * h + (focalLengthMm?.hashCode() ?: 0)
            h = 31 * h + (sensorWidthMm?.hashCode() ?: 0)
            h = 31 * h + (sensorHeightMm?.hashCode() ?: 0)
            h = 31 * h + calibrationArrayWidth
            h = 31 * h + calibrationArrayHeight
            h = 31 * h + calibrationArrayLeft
            h = 31 * h + calibrationArrayTop
            h = 31 * h + activeArrayLeft
            h = 31 * h + activeArrayTop
            h = 31 * h + activeArrayWidth
            h = 31 * h + activeArrayHeight
            return h
        }

        /** [computeIntrinsics] for a stream of [targetWidth] × [targetHeight]. */
        fun intrinsicsFor(targetWidth: Int, targetHeight: Int): CameraIntrinsics? = computeIntrinsics(
            calibration = calibration,
            pixelArrayWidth = pixelArrayWidth,
            pixelArrayHeight = pixelArrayHeight,
            focalLengthMm = focalLengthMm,
            sensorWidthMm = sensorWidthMm,
            sensorHeightMm = sensorHeightMm,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            calibrationArrayWidth = calibrationArrayWidth,
            calibrationArrayHeight = calibrationArrayHeight,
            calibrationArrayLeft = calibrationArrayLeft,
            calibrationArrayTop = calibrationArrayTop,
            activeArrayLeft = activeArrayLeft,
            activeArrayTop = activeArrayTop,
            activeArrayWidth = activeArrayWidth,
            activeArrayHeight = activeArrayHeight,
        )
    }

    private val cache = ConcurrentHashMap<String, RawCalibration>()

    /**
     * Intrinsics for camera [cameraId] at the frame size actual frames arrive at.
     *
     * @param cameraId a Camera2 id (for CameraX: `Camera2CameraInfo.from(cameraInfo).cameraId`).
     * @return null when the camera service or id cannot be reached or the result would be
     *   non-finite / non-positive — never a plausible-looking guess.
     */
    fun estimate(context: Context, cameraId: String, targetWidth: Int, targetHeight: Int): CameraIntrinsics? =
        rawCalibration(context, cameraId)?.intrinsicsFor(targetWidth, targetHeight)

    /** The cached [RawCalibration] for [cameraId], reading `CameraCharacteristics` on first use. */
    fun rawCalibration(context: Context, cameraId: String): RawCalibration? {
        cache[cameraId]?.let { return it }
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
            readRaw(manager.getCameraCharacteristics(cameraId)).also { cache[cameraId] = it }
        } catch (e: Exception) {
            Log.w(TAG, "failed to read characteristics for camera $cameraId", e)
            null
        }
    }

    /** Forget every cached camera. */
    fun clearCache() = cache.clear()

    /** Read the raw Camera2 fields (no caching). */
    fun readRaw(characteristics: CameraCharacteristics): RawCalibration {
        val pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val preCorrection = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return RawCalibration(
            calibration = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION),
            pixelArrayWidth = pixelArray?.width ?: 0,
            pixelArrayHeight = pixelArray?.height ?: 0,
            focalLengthMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
            sensorWidthMm = sensorSize?.width,
            sensorHeightMm = sensorSize?.height,
            calibrationArrayWidth = preCorrection?.width() ?: (pixelArray?.width ?: 0),
            calibrationArrayHeight = preCorrection?.height() ?: (pixelArray?.height ?: 0),
            calibrationArrayLeft = preCorrection?.left ?: 0,
            calibrationArrayTop = preCorrection?.top ?: 0,
            activeArrayLeft = active?.left ?: 0,
            activeArrayTop = active?.top ?: 0,
            activeArrayWidth = active?.width() ?: 0,
            activeArrayHeight = active?.height() ?: 0,
        )
    }

    /** True when ([cx], [cy]) lies in the middle half of a [width] × [height] array. */
    internal fun plausiblePrincipalPoint(cx: Float, cy: Float, width: Int, height: Int): Boolean =
        cx.isFinite() && cy.isFinite() &&
            cx in width * 0.25f..width * 0.75f && cy in height * 0.25f..height * 0.75f

    /**
     * The pinhole math as a pure function. `pixelArrayWidth`/`Height <= 0` means "not available".
     *
     * @param calibrationArrayWidth / [calibrationArrayHeight] the array [calibration] is defined
     *   against (`SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`); defaults to the pixel array.
     * @param calibrationArrayLeft / [calibrationArrayTop] that array's origin in pixel-array
     *   coordinates.
     * @param activeArrayLeft / [activeArrayTop] / [activeArrayWidth] / [activeArrayHeight]
     *   `SENSOR_INFO_ACTIVE_ARRAY_SIZE` in pixel-array coordinates — the region streams are cropped
     *   from. A width or height `<= 0` means unknown: the calibration (or pixel) array is used.
     * @return intrinsics for the [targetWidth] × [targetHeight] stream (centred aspect crop of the
     *   active array, then uniform scale), or null when inputs are missing or the result is not finite
     *   and positive.
     */
    fun computeIntrinsics(
        calibration: FloatArray?,
        pixelArrayWidth: Int,
        pixelArrayHeight: Int,
        focalLengthMm: Float?,
        sensorWidthMm: Float?,
        sensorHeightMm: Float?,
        targetWidth: Int,
        targetHeight: Int,
        calibrationArrayWidth: Int = pixelArrayWidth,
        calibrationArrayHeight: Int = pixelArrayHeight,
        calibrationArrayLeft: Int = 0,
        calibrationArrayTop: Int = 0,
        activeArrayLeft: Int = 0,
        activeArrayTop: Int = 0,
        activeArrayWidth: Int = 0,
        activeArrayHeight: Int = 0,
    ): CameraIntrinsics? {
        if (targetWidth <= 0 || targetHeight <= 0) return null
        if (pixelArrayWidth <= 0 || pixelArrayHeight <= 0) return null

        val raw: FloatArray
        var arrayW: Int
        var arrayH: Int
        // Origin of raw's principal point in pixel-array coordinates.
        val originX: Int
        val originY: Int
        if (calibration != null && calibration.size >= 4) {
            raw = floatArrayOf(calibration[0], calibration[1], calibration[2], calibration[3])
            val known = calibrationArrayWidth > 0 && calibrationArrayHeight > 0
            arrayW = if (known) calibrationArrayWidth else pixelArrayWidth
            arrayH = if (known) calibrationArrayHeight else pixelArrayHeight
            originX = if (known) calibrationArrayLeft else 0
            originY = if (known) calibrationArrayTop else 0
            // Some devices report a usable focal length with a principal point of (0, 0) (or
            // otherwise far off-centre). Trusted, every pose is solved about the image corner and
            // nothing matches. A real lens's principal point sits near the array centre; outside the
            // middle half it is a bad value, so keep the focal lengths and centre it.
            if (!plausiblePrincipalPoint(raw[2], raw[3], arrayW, arrayH)) {
                raw[2] = arrayW / 2f
                raw[3] = arrayH / 2f
            }
        } else {
            if (focalLengthMm == null || sensorWidthMm == null || sensorHeightMm == null) return null
            if (sensorWidthMm <= 0f || sensorHeightMm <= 0f) return null
            raw = floatArrayOf(
                focalLengthMm * pixelArrayWidth / sensorWidthMm,
                focalLengthMm * pixelArrayHeight / sensorHeightMm,
                pixelArrayWidth / 2f,
                pixelArrayHeight / 2f,
            )
            arrayW = pixelArrayWidth
            arrayH = pixelArrayHeight
            originX = 0
            originY = 0
        }
        if (raw.any { !it.isFinite() } || raw[0] <= 0f || raw[1] <= 0f) return null
        if (activeArrayWidth > 0 && activeArrayHeight > 0) {
            // Streams are cropped from the active array: re-express the principal point relative to it.
            raw[2] += (originX - activeArrayLeft).toFloat()
            raw[3] += (originY - activeArrayTop).toFloat()
            arrayW = activeArrayWidth
            arrayH = activeArrayHeight
        }

        // Largest centred region of the array with the stream's aspect ratio, then a uniform scale.
        val arrayAspect = arrayW.toDouble() / arrayH
        val targetAspect = targetWidth.toDouble() / targetHeight
        val cropW: Double
        val cropH: Double
        if (targetAspect > arrayAspect) {
            cropW = arrayW.toDouble(); cropH = arrayW / targetAspect
        } else {
            cropH = arrayH.toDouble(); cropW = arrayH * targetAspect
        }
        val offX = (arrayW - cropW) / 2.0
        val offY = (arrayH - cropH) / 2.0
        val scale = targetWidth / cropW
        val out = CameraIntrinsics(
            fx = (raw[0] * scale).toFloat(),
            fy = (raw[1] * scale).toFloat(),
            cx = ((raw[2] - offX) * scale).toFloat(),
            cy = ((raw[3] - offY) * scale).toFloat(),
            width = targetWidth,
            height = targetHeight,
        )
        return if (out.isValid) out else null
    }
}

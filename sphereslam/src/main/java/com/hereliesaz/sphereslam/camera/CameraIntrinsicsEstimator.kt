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
 * **Stream geometry.** The requested frame size is rarely the sensor array size. Camera2 produces a
 * stream by taking the largest **centred** region of the array with the stream's aspect ratio and
 * scaling it uniformly. [computeIntrinsics] models exactly that: an aspect-changing request (a 16:9
 * stream from a 4:3 sensor) crops the array first, so `fx`/`fy` scale uniformly and the principal
 * point shifts by the crop offset. (Earlier GraffitiXR code scaled x and y independently, which
 * squashed the focal lengths on any aspect change.) A non-default `SCALER_CROP_REGION` / digital
 * zoom is not modelled.
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
    ) {
        override fun equals(other: Any?): Boolean =
            other is RawCalibration &&
                (calibration?.contentEquals(other.calibration) ?: (other.calibration == null)) &&
                pixelArrayWidth == other.pixelArrayWidth && pixelArrayHeight == other.pixelArrayHeight &&
                focalLengthMm == other.focalLengthMm && sensorWidthMm == other.sensorWidthMm &&
                sensorHeightMm == other.sensorHeightMm &&
                calibrationArrayWidth == other.calibrationArrayWidth &&
                calibrationArrayHeight == other.calibrationArrayHeight

        override fun hashCode(): Int {
            var h = calibration?.contentHashCode() ?: 0
            h = 31 * h + pixelArrayWidth
            h = 31 * h + pixelArrayHeight
            h = 31 * h + (focalLengthMm?.hashCode() ?: 0)
            h = 31 * h + (sensorWidthMm?.hashCode() ?: 0)
            h = 31 * h + (sensorHeightMm?.hashCode() ?: 0)
            h = 31 * h + calibrationArrayWidth
            h = 31 * h + calibrationArrayHeight
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
        )
    }

    /**
     * The pinhole math as a pure function. `pixelArrayWidth`/`Height <= 0` means "not available".
     *
     * @param calibrationArrayWidth / [calibrationArrayHeight] the array [calibration] is defined
     *   against (`SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`); defaults to the pixel array.
     * @return intrinsics for the [targetWidth] × [targetHeight] stream (centred aspect crop, then
     *   uniform scale), or null when inputs are missing or the result is not finite and positive.
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
    ): CameraIntrinsics? {
        if (targetWidth <= 0 || targetHeight <= 0) return null
        if (pixelArrayWidth <= 0 || pixelArrayHeight <= 0) return null

        val raw: FloatArray
        val arrayW: Int
        val arrayH: Int
        if (calibration != null && calibration.size >= 4) {
            raw = floatArrayOf(calibration[0], calibration[1], calibration[2], calibration[3])
            arrayW = if (calibrationArrayWidth > 0) calibrationArrayWidth else pixelArrayWidth
            arrayH = if (calibrationArrayHeight > 0) calibrationArrayHeight else pixelArrayHeight
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
        }
        if (raw.any { !it.isFinite() } || raw[0] <= 0f || raw[1] <= 0f) return null

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

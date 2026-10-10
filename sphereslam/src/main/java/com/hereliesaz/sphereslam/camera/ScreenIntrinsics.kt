package com.hereliesaz.sphereslam.camera

import kotlin.math.tan

/**
 * Pinhole intrinsics in **screen (view) pixels** for a camera image shown `FIT_CENTER` in a view —
 * what screen-space overlays (e.g. [com.hereliesaz.sphereslam.gyro.GyroCompensationMath]) need.
 */
object ScreenIntrinsics {

    /**
     * Horizontal field of view across the sensor's long side assumed when the camera reports no
     * usable intrinsics (a typical phone main camera is ~65–75°).
     */
    const val FALLBACK_LONG_SIDE_FOV_DEGREES = 68f

    /**
     * `[fx, fy, cx, cy]` in view pixels for an [imageWidth] × [imageHeight] frame (focal lengths
     * [imageFx]/[imageFy] in the sensor's own orientation, as [CameraIntrinsicsEstimator] returns)
     * shown `FIT_CENTER` in a [viewWidth] × [viewHeight] view. When the view's orientation differs
     * from the frame's, the preview shows the frame rotated a quarter turn, so the axes swap. The
     * principal point is taken as the view centre (a decentred lens adds a constant offset that
     * cancels out of a small-rotation delta to first order).
     */
    fun fitCenter(
        imageFx: Float,
        imageFy: Float,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): FloatArray {
        require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0) { "sizes must be positive" }
        val swap = (imageWidth >= imageHeight) != (viewWidth >= viewHeight)
        val shownW = if (swap) imageHeight else imageWidth
        val shownH = if (swap) imageWidth else imageHeight
        val shownFx = if (swap) imageFy else imageFx
        val shownFy = if (swap) imageFx else imageFy
        val scale = minOf(viewWidth.toFloat() / shownW, viewHeight.toFloat() / shownH)
        return floatArrayOf(shownFx * scale, shownFy * scale, viewWidth / 2f, viewHeight / 2f)
    }

    /** [fitCenter] for a [CameraIntrinsics]. */
    fun fitCenter(intrinsics: CameraIntrinsics, viewWidth: Int, viewHeight: Int): FloatArray =
        fitCenter(intrinsics.fx, intrinsics.fy, intrinsics.width, intrinsics.height, viewWidth, viewHeight)

    /**
     * [fitCenter] when the camera reports nothing usable: a 4:3 frame (CameraX's default preview
     * aspect) with [FALLBACK_LONG_SIDE_FOV_DEGREES] across its long side.
     */
    fun fallback(viewWidth: Int, viewHeight: Int): FloatArray {
        val w = 4000
        val h = 3000
        val f = (w / 2f) / tan(Math.toRadians(FALLBACK_LONG_SIDE_FOV_DEGREES / 2.0)).toFloat()
        return fitCenter(f, f, w, h, viewWidth, viewHeight)
    }
}

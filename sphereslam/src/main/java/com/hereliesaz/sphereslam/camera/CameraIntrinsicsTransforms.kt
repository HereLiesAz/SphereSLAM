package com.hereliesaz.sphereslam.camera

/**
 * Intrinsics bookkeeping that must follow the image exactly: crop, uniform rescale, and (via
 * [CaptureRotation]) quarter-turn rotation — applied in the same order as the pixels.
 */
object CameraIntrinsicsTransforms {

    /**
     * Intrinsics for a crop rectangle of the image (e.g. CameraX `ImageProxy.cropRect`, which is in
     * buffer coordinates). Cropping keeps the focal length and moves the principal point by the crop
     * origin. Apply before rotating, matching [LumaFrameTransform.packCropAndRotate].
     *
     * @throws IllegalArgumentException when the crop is empty or outside the image.
     */
    fun crop(intrinsics: CameraIntrinsics, cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int): CameraIntrinsics {
        require(cropLeft >= 0 && cropTop >= 0)
        require(cropWidth > 0 && cropHeight > 0)
        require(cropLeft + cropWidth <= intrinsics.width)
        require(cropTop + cropHeight <= intrinsics.height)
        return CameraIntrinsics(
            fx = intrinsics.fx,
            fy = intrinsics.fy,
            cx = intrinsics.cx - cropLeft,
            cy = intrinsics.cy - cropTop,
            width = cropWidth,
            height = cropHeight,
        )
    }

    /**
     * Intrinsics for the same image resampled to [targetWidth] × [targetHeight] (independent x/y
     * scale — only correct for a resample of the whole image, not an aspect-changing crop).
     */
    fun rescale(intrinsics: CameraIntrinsics, targetWidth: Int, targetHeight: Int): CameraIntrinsics {
        require(intrinsics.width > 0 && intrinsics.height > 0)
        require(targetWidth > 0 && targetHeight > 0)
        val sx = targetWidth.toFloat() / intrinsics.width
        val sy = targetHeight.toFloat() / intrinsics.height
        return CameraIntrinsics(
            fx = intrinsics.fx * sx,
            fy = intrinsics.fy * sy,
            cx = intrinsics.cx * sx,
            cy = intrinsics.cy * sy,
            width = targetWidth,
            height = targetHeight,
        )
    }

    /** [CaptureRotation.rotateIntrinsics] on a [CameraIntrinsics], swapping the image size on quarter turns. */
    fun rotate(intrinsics: CameraIntrinsics, rotationDeg: Int): CameraIntrinsics {
        val r = CaptureRotation.normalize(rotationDeg)
        val k = CaptureRotation.rotateIntrinsics(
            intrinsics.fx, intrinsics.fy, intrinsics.cx, intrinsics.cy,
            intrinsics.width.toFloat(), intrinsics.height.toFloat(), r,
        )
        val swap = r == 90 || r == 270
        return CameraIntrinsics(
            k[0], k[1], k[2], k[3],
            if (swap) intrinsics.height else intrinsics.width,
            if (swap) intrinsics.width else intrinsics.height,
        )
    }
}

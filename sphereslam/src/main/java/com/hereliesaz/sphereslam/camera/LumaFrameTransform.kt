package com.hereliesaz.sphereslam.camera

import java.nio.ByteBuffer

/**
 * A tightly packed (row stride == [width]) luminance frame, as produced by [LumaFrameTransform].
 * [bytes] is owned by the caller once returned (the transform keeps no reference).
 */
class RotatedLuma(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun toString(): String = "RotatedLuma(${width}x$height)"
}

/**
 * Packs a CameraX / Camera2 Y plane (honouring row and pixel stride), optionally crops it, and
 * rotates it clockwise by a quarter turn — the pixel half of the transform
 * [CaptureRotation.rotateIntrinsics] / [CameraIntrinsicsTransforms] apply to calibration, so KPM
 * pixels and intrinsics describe the exact same image.
 *
 * Pass CameraX `ImageInfo.rotationDegrees` to obtain the display-upright image; pass 0 to keep the
 * raw sensor orientation. The source buffer's position is honoured and not modified.
 */
object LumaFrameTransform {

    /** [packCropAndRotate] over the whole image. */
    fun packAndRotate(
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        rotationDegrees: Int,
    ): RotatedLuma = packCropAndRotate(
        source = source,
        sourceWidth = width,
        sourceHeight = height,
        rowStride = rowStride,
        pixelStride = pixelStride,
        cropLeft = 0,
        cropTop = 0,
        cropWidth = width,
        cropHeight = height,
        rotationDegrees = rotationDegrees,
    )

    /**
     * Packs only the crop rectangle, then rotates the cropped image. CameraX defines
     * `ImageProxy.cropRect` in the buffer's coordinate system, so crop offsets apply before rotation
     * (pair with [CameraIntrinsicsTransforms.crop] then [CameraIntrinsicsTransforms.rotate]).
     *
     * @throws IllegalArgumentException for an empty/out-of-range crop, a non-quarter-turn rotation,
     *   or a buffer too small for the strided crop.
     */
    fun packCropAndRotate(
        source: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
    ): RotatedLuma {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(rowStride > 0 && pixelStride > 0)
        require(cropLeft >= 0 && cropTop >= 0)
        require(cropWidth > 0 && cropHeight > 0)
        require(cropLeft + cropWidth <= sourceWidth)
        require(cropTop + cropHeight <= sourceHeight)

        val rotation = ((rotationDegrees % 360) + 360) % 360
        require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
            "rotation must be a multiple of 90 degrees"
        }

        val base = source.position() + cropTop * rowStride + cropLeft * pixelStride
        val lastIndex = base + (cropHeight - 1) * rowStride + (cropWidth - 1) * pixelStride
        require(lastIndex < source.limit()) {
            "Y plane does not contain the requested cropped strided image"
        }

        val outWidth = if (rotation == 90 || rotation == 270) cropHeight else cropWidth
        val outHeight = if (rotation == 90 || rotation == 270) cropWidth else cropHeight
        val out = ByteArray(cropWidth * cropHeight)
        val src = source.duplicate()

        for (y in 0 until cropHeight) {
            val row = base + y * rowStride
            for (x in 0 until cropWidth) {
                val value = src.get(row + x * pixelStride)
                val dx: Int
                val dy: Int
                when (rotation) {
                    90 -> {
                        dx = cropHeight - 1 - y
                        dy = x
                    }
                    180 -> {
                        dx = cropWidth - 1 - x
                        dy = cropHeight - 1 - y
                    }
                    270 -> {
                        dx = y
                        dy = cropWidth - 1 - x
                    }
                    else -> {
                        dx = x
                        dy = y
                    }
                }
                out[dy * outWidth + dx] = value
            }
        }

        return RotatedLuma(out, outWidth, outHeight)
    }
}

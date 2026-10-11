package com.hereliesaz.sphereslam.camera

import java.nio.ByteBuffer

/**
 * A tightly packed (row stride == [width]) luminance frame, as produced by [LumaFrameTransform].
 * From [LumaFrameTransform.packAndRotate] / [LumaFrameTransform.packCropAndRotate], [bytes] is a fresh
 * array owned by the caller (the transform keeps no reference). From the `…Into` variants it is the
 * caller's destination, and from a [LumaFrameBufferPool] it is a pooled array that a later call
 * overwrites (see the pool's retention rule).
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
 *
 * Per frame, the allocating functions create one output array; for a camera loop prefer
 * [LumaFrameBufferPool] (pooled output arrays) or the `…Into` variants (caller-owned output). All
 * variants read the plane a row at a time and rotate in cache-sized bands; the band / row scratch is
 * kept per calling thread (or per pool) and reused, so it is allocated once, not per frame.
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
    ): RotatedLuma = transform(
        source, sourceWidth, sourceHeight, rowStride, pixelStride,
        cropLeft, cropTop, cropWidth, cropHeight, rotationDegrees, null, null,
    )

    internal fun transform(
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
        destination: ByteArray?,
        scratch: Scratch?,
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
        val out = destination?.also {
            require(it.size == cropWidth * cropHeight) {
                "destination must be exactly ${cropWidth * cropHeight} bytes, was ${it.size}"
            }
        } ?: ByteArray(cropWidth * cropHeight)
        val work = scratch ?: threadScratch.get()!!
        // The band is only needed to rotate; an unrotated pack reads rows straight into the output.
        val band = if (rotation == 0) EMPTY else {
            val bandBytes = BAND_ROWS * cropWidth
            work.band?.takeIf { it.size >= bandBytes } ?: ByteArray(bandBytes).also { work.band = it }
        }
        val span = (cropWidth - 1) * pixelStride + 1
        val rowSpan = if (pixelStride == 1) null else {
            work.rowSpan?.takeIf { it.size >= span } ?: ByteArray(span).also { work.rowSpan = it }
        }

        packRotate(source.duplicate(), base, rowStride, pixelStride, cropWidth, cropHeight, rotation, out, band, rowSpan)
        return RotatedLuma(out, outWidth, outHeight)
    }

    /**
     * [packCropAndRotate] into a caller-owned [destination] of exactly `cropWidth * cropHeight` bytes.
     * Allocates nothing frame-sized: per call only a buffer view and the small [RotatedLuma] wrapper
     * (the band / row scratch is reused per calling thread). The returned [RotatedLuma.bytes] **is**
     * [destination]: reuse it for the next frame only once nothing still reads the previous one.
     */
    fun packCropAndRotateInto(
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
        destination: ByteArray,
    ): RotatedLuma = transform(
        source, sourceWidth, sourceHeight, rowStride, pixelStride,
        cropLeft, cropTop, cropWidth, cropHeight, rotationDegrees, destination, null,
    )

    /** [packCropAndRotateInto] over the whole image. */
    fun packAndRotateInto(
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        rotationDegrees: Int,
        destination: ByteArray,
    ): RotatedLuma = packCropAndRotateInto(
        source, width, height, rowStride, pixelStride, 0, 0, width, height, rotationDegrees, destination,
    )

    /** Rows packed per band: big enough to amortise the strided writes of a quarter turn, small enough for L1. */
    internal const val BAND_ROWS = 16

    /** Reusable band / row scratch (per [LumaFrameBufferPool], or per thread for the other calls). */
    internal class Scratch {
        var band: ByteArray? = null
        var rowSpan: ByteArray? = null
    }

    private val EMPTY = ByteArray(0)

    /** Scratch for calls without their own, one per thread so concurrent callers never share it. */
    private val threadScratch: ThreadLocal<Scratch> = ThreadLocal.withInitial { Scratch() }

    /** The calling thread's scratch (tests). */
    internal fun threadScratchForTest(): Scratch = threadScratch.get()!!

    /**
     * Band-tiled pack + rotate. Up to [BAND_ROWS] source rows are bulk-read (one `ByteBuffer.get` per
     * row instead of one per pixel) into [band]; a quarter turn then writes each source column of the
     * band as a run of contiguous output bytes, so output cache lines are filled while hot.
     */
    private fun packRotate(
        src: ByteBuffer,
        base: Int,
        rowStride: Int,
        pixelStride: Int,
        w: Int,
        h: Int,
        rotation: Int,
        out: ByteArray,
        band: ByteArray,
        rowSpan: ByteArray?,
    ) {
        var y0 = 0
        while (y0 < h) {
            val rows = minOf(BAND_ROWS, h - y0)
            if (rotation == 0) {
                for (r in 0 until rows) readRow(src, base + (y0 + r) * rowStride, pixelStride, w, out, (y0 + r) * w, rowSpan)
                y0 += rows
                continue
            }
            for (r in 0 until rows) readRow(src, base + (y0 + r) * rowStride, pixelStride, w, band, r * w, rowSpan)
            when (rotation) {
                90 -> {
                    // (x, y) -> (h - 1 - y, x); output width h.
                    for (x in 0 until w) {
                        val d = x * h + (h - 1 - y0)
                        var bi = x
                        for (r in 0 until rows) {
                            out[d - r] = band[bi]
                            bi += w
                        }
                    }
                }
                270 -> {
                    // (x, y) -> (y, w - 1 - x); output width h.
                    for (x in 0 until w) {
                        val d = (w - 1 - x) * h + y0
                        var bi = x
                        for (r in 0 until rows) {
                            out[d + r] = band[bi]
                            bi += w
                        }
                    }
                }
                else -> {
                    // 180: (x, y) -> (w - 1 - x, h - 1 - y).
                    for (r in 0 until rows) {
                        val bRow = r * w
                        val oRowEnd = (h - 1 - (y0 + r)) * w + (w - 1)
                        for (x in 0 until w) out[oRowEnd - x] = band[bRow + x]
                    }
                }
            }
            y0 += rows
        }
    }

    private fun readRow(
        src: ByteBuffer,
        start: Int,
        pixelStride: Int,
        w: Int,
        dst: ByteArray,
        offset: Int,
        rowSpan: ByteArray?,
    ) {
        src.position(start)
        if (pixelStride == 1) {
            src.get(dst, offset, w)
            return
        }
        val span = (w - 1) * pixelStride + 1
        val tmp = rowSpan!!
        src.get(tmp, 0, span)
        var si = 0
        for (x in 0 until w) {
            dst[offset + x] = tmp[si]
            si += pixelStride
        }
    }
}

/**
 * Reusable output buffers for [LumaFrameTransform], for a camera-analysis loop that would otherwise
 * allocate a new frame-sized array (≈300 KB at 640×480) every frame.
 *
 * The pool owns [slots] frame arrays and hands them out round-robin: the array in a returned
 * [RotatedLuma] stays untouched until [slots] more frames have been packed, after which it is
 * overwritten. So a caller may keep the last `slots - 1` frames, but anything retained longer — a
 * keyframe list, a queue to another thread — must copy it (or use the allocating
 * [LumaFrameTransform.packCropAndRotate], whose arrays are never reused). Library consumers of a
 * frame that copy what they keep (e.g. `PhotosphereTrackingSession`, `PhotosphereKeyframe`) are safe
 * with a pool of 1. A size change simply reallocates. Synchronized; one pool per analysis stream.
 */
class LumaFrameBufferPool(val slots: Int = 2) {
    init {
        require(slots >= 1) { "slots must be >= 1" }
    }

    private val buffers = arrayOfNulls<ByteArray>(slots)
    private var next = 0
    private val scratch = LumaFrameTransform.Scratch()

    /** Pooled [LumaFrameTransform.packCropAndRotate]. */
    @Synchronized
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
        require(cropWidth > 0 && cropHeight > 0)
        val size = cropWidth * cropHeight
        val slot = next
        val out = buffers[slot]?.takeIf { it.size == size } ?: ByteArray(size).also { buffers[slot] = it }
        val result = LumaFrameTransform.transform(
            source, sourceWidth, sourceHeight, rowStride, pixelStride,
            cropLeft, cropTop, cropWidth, cropHeight, rotationDegrees, out, scratch,
        )
        next = (slot + 1) % slots
        return result
    }

    /** Pooled [LumaFrameTransform.packAndRotate]. */
    fun packAndRotate(
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        rotationDegrees: Int,
    ): RotatedLuma = packCropAndRotate(source, width, height, rowStride, pixelStride, 0, 0, width, height, rotationDegrees)
}

package com.hereliesaz.sphereslam.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class LumaFrameTransformTest {

    private fun stridedSource(): ByteBuffer {
        val bytes = ByteArray(13)
        bytes[0] = 1
        bytes[2] = 2
        bytes[4] = 3
        bytes[8] = 4
        bytes[10] = 5
        bytes[12] = 6
        return ByteBuffer.wrap(bytes)
    }

    @Test
    fun packsPixelAndRowStride_withoutRotating() {
        val out = LumaFrameTransform.packAndRotate(stridedSource(), 3, 2, 8, 2, 0)
        assertEquals(3, out.width)
        assertEquals(2, out.height)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), out.bytes)
    }

    @Test
    fun rotatesClockwise90_intoDisplayOrientation() {
        val out = LumaFrameTransform.packAndRotate(stridedSource(), 3, 2, 8, 2, 90)
        assertEquals(2, out.width)
        assertEquals(3, out.height)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), out.bytes)
    }

    @Test
    fun cropsStridedSource_beforeRotating() {
        // Logical 4x3 image with pixelStride=1, rowStride=4:
        //  1  2  3  4
        //  5  6  7  8
        //  9 10 11 12
        val source = ByteBuffer.wrap(byteArrayOf(
            1, 2, 3, 4,
            5, 6, 7, 8,
            9, 10, 11, 12,
        ))
        val out = LumaFrameTransform.packCropAndRotate(
            source = source,
            sourceWidth = 4,
            sourceHeight = 3,
            rowStride = 4,
            pixelStride = 1,
            cropLeft = 1,
            cropTop = 1,
            cropWidth = 2,
            cropHeight = 2,
            rotationDegrees = 90,
        )
        assertEquals(2, out.width)
        assertEquals(2, out.height)
        // Cropped 6,7 / 10,11, then clockwise 90°.
        assertArrayEquals(byteArrayOf(10, 6, 11, 7), out.bytes)
    }

    @Test
    fun rotates180_and270() {
        val r180 = LumaFrameTransform.packAndRotate(stridedSource(), 3, 2, 8, 2, 180)
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), r180.bytes)

        val r270 = LumaFrameTransform.packAndRotate(stridedSource(), 3, 2, 8, 2, 270)
        assertEquals(2, r270.width)
        assertEquals(3, r270.height)
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), r270.bytes)
    }

    /** The original scalar per-pixel implementation, kept as the oracle. */
    private fun reference(
        source: ByteBuffer, rowStride: Int, pixelStride: Int,
        cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int, rotation: Int,
    ): ByteArray {
        val base = source.position() + cropTop * rowStride + cropLeft * pixelStride
        val outWidth = if (rotation == 90 || rotation == 270) cropHeight else cropWidth
        val out = ByteArray(cropWidth * cropHeight)
        for (y in 0 until cropHeight) for (x in 0 until cropWidth) {
            val v = source.get(base + y * rowStride + x * pixelStride)
            val (dx, dy) = when (rotation) {
                90 -> (cropHeight - 1 - y) to x
                180 -> (cropWidth - 1 - x) to (cropHeight - 1 - y)
                270 -> y to (cropWidth - 1 - x)
                else -> x to y
            }
            out[dy * outWidth + dx] = v
        }
        return out
    }

    @Test
    fun bandedRotation_matchesTheScalarOracle_acrossShapesStridesAndCrops() {
        val r = Random(7)
        // Heights straddle the 16-row band (partial last band, single band, several bands).
        val shapes = listOf(1 to 1, 3 to 2, 5 to 17, 17 to 5, 33 to 40, 64 to 48, 31 to 16)
        for ((w, h) in shapes) for (pixelStride in listOf(1, 2)) for (rotation in listOf(0, 90, 180, 270, -90, 450)) {
            val rowStride = w * pixelStride + r.nextInt(0, 9)
            val offset = r.nextInt(0, 5)
            val bytes = ByteArray(offset + rowStride * h + 3).also { r.nextBytes(it) }
            val buffer = ByteBuffer.wrap(bytes).apply { position(offset) }
            val cropLeft = if (w > 2) 1 else 0
            val cropTop = if (h > 2) 1 else 0
            val cw = w - cropLeft - (if (w > 3) 1 else 0)
            val ch = h - cropTop
            val norm = ((rotation % 360) + 360) % 360
            val expected = reference(buffer, rowStride, pixelStride, cropLeft, cropTop, cw, ch, norm)
            val fresh = LumaFrameTransform.packCropAndRotate(buffer, w, h, rowStride, pixelStride, cropLeft, cropTop, cw, ch, rotation)
            assertArrayEquals("w=$w h=$h ps=$pixelStride rot=$rotation", expected, fresh.bytes)
            assertEquals(if (norm % 180 == 0) cw else ch, fresh.width)
            val into = ByteArray(cw * ch)
            val viaInto = LumaFrameTransform.packCropAndRotateInto(buffer, w, h, rowStride, pixelStride, cropLeft, cropTop, cw, ch, rotation, into)
            assertSame(into, viaInto.bytes)
            assertArrayEquals(expected, into)
            assertEquals(offset, buffer.position()) // source position untouched
        }
    }

    @Test
    fun directSourceBuffers_work() {
        val heap = ByteArray(64 * 48).also { Random(3).nextBytes(it) }
        val direct = ByteBuffer.allocateDirect(heap.size).apply { put(heap); flip() }
        val a = LumaFrameTransform.packAndRotate(ByteBuffer.wrap(heap), 64, 48, 64, 1, 90)
        val b = LumaFrameTransform.packAndRotate(direct, 64, 48, 64, 1, 90)
        assertArrayEquals(a.bytes, b.bytes)
    }

    @Test
    fun intoRejectsAWronglySizedDestination() {
        assertThrows(IllegalArgumentException::class.java) {
            LumaFrameTransform.packAndRotateInto(stridedSource(), 3, 2, 8, 2, 90, ByteArray(7))
        }
    }

    @Test
    fun pool_reusesArraysRoundRobin_andLeavesRecentFramesIntact() {
        val pool = LumaFrameBufferPool(slots = 2)
        val first = pool.packAndRotate(stridedSource(), 3, 2, 8, 2, 90)
        val firstCopy = first.bytes.copyOf()
        val second = pool.packAndRotate(stridedSource(), 3, 2, 8, 2, 180)
        assertNotSame(first.bytes, second.bytes)
        assertArrayEquals(firstCopy, first.bytes) // the previous frame survives one more call
        val third = pool.packAndRotate(stridedSource(), 3, 2, 8, 2, 0)
        assertSame(first.bytes, third.bytes) // ...and is reused after `slots` calls
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), third.bytes)
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), second.bytes)
    }

    @Test
    fun pool_reallocatesOnSizeChange_andAllocatingCallsNeverAlias() {
        val pool = LumaFrameBufferPool(slots = 1)
        val a = pool.packAndRotate(stridedSource(), 3, 2, 8, 2, 0)
        val b = pool.packCropAndRotate(stridedSource(), 3, 2, 8, 2, 0, 0, 2, 2, 90)
        assertEquals(4, b.bytes.size)
        assertNotSame(a.bytes, b.bytes)
        val c = pool.packCropAndRotate(stridedSource(), 3, 2, 8, 2, 0, 0, 2, 2, 0)
        assertSame(b.bytes, c.bytes)
        val x = LumaFrameTransform.packAndRotate(stridedSource(), 3, 2, 8, 2, 0)
        val y = LumaFrameTransform.packAndRotate(stridedSource(), 3, 2, 8, 2, 0)
        assertNotSame(x.bytes, y.bytes)
        assertThrows(IllegalArgumentException::class.java) { LumaFrameBufferPool(0) }
    }
}

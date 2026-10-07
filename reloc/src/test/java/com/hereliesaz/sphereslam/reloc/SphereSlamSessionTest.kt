package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session facade's pure pieces. The full [SphereSlamSession.onFrame] path needs native OpenCV
 * (the matcher) and a device; these cover the logic the wiring is built from.
 */
class SphereSlamSessionTest {

    @Test
    fun `reacquiring is true only once the lock is lost`() {
        assertTrue(SphereSlamSession.reacquiring(TrackingState.LOST))
        assertTrue(SphereSlamSession.reacquiring(TrackingState.REACQUIRING))
        assertFalse(SphereSlamSession.reacquiring(TrackingState.INITIALIZING))
        assertFalse(SphereSlamSession.reacquiring(TrackingState.LOCKED))
        assertFalse(SphereSlamSession.reacquiring(TrackingState.IMU_BRIDGE))
    }

    @Test
    fun `row-major to column-major transposes storage for the same transform`() {
        // A transform with a distinct value in every cell so a mis-indexed transpose is caught.
        val rowMajor = FloatArray(16) { it.toFloat() } // r[row*4+col] = row*4+col
        val col = SphereSlamSession.rowMajorToColumnMajor(rowMajor)
        // col[col*4+row] must equal r[row*4+col].
        for (row in 0 until 4) for (c in 0 until 4) {
            org.junit.Assert.assertEquals(rowMajor[row * 4 + c], col[c * 4 + row], 0f)
        }
    }

    @Test
    fun `identity round-trips and translation lands in the column-major translation slots`() {
        val identityRow = floatArrayOf(
            1f, 0f, 0f, 2f, // tx in row-major slot 3
            0f, 1f, 0f, 3f, // ty in slot 7
            0f, 0f, 1f, 4f, // tz in slot 11
            0f, 0f, 0f, 1f,
        )
        val col = SphereSlamSession.rowMajorToColumnMajor(identityRow)
        // Column-major translation lives at indices 12,13,14.
        assertArrayEquals(floatArrayOf(2f, 3f, 4f), floatArrayOf(col[12], col[13], col[14]), 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), floatArrayOf(col[0], col[5], col[10]), 0f)
    }
}

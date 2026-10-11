package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallFeatureMapTest {

    private val fx = 100f
    private val fy = 100f
    private val cx = 32f
    private val cy = 24f

    /** OpenCV camera-from-fingerprint (row-major) for a camera 2 units in front of the z = 0 wall. */
    private val facingWall = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, -1f, 2f,
        0f, 0f, 0f, 1f,
    )
    private val wall = WallPlane(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    private fun descriptors(n: Int, seed: Int) = ByteArray(n * 32) { (it * 31 + seed).toByte() }

    private fun grow(
        map: WallFeatureMap,
        pixels: FloatArray,
        matched: IntArray = IntArray(pixels.size / 2) { -1 },
        pose: FloatArray = facingWall,
        plane: WallPlane = wall,
        depth: InverseDepthMap? = null,
        seed: Int = 0,
    ) = map.grow(pose, pixels, descriptors(pixels.size / 2, seed), 32, 0, matched, plane, fx, fy, cx, cy, identity, depth)

    @Test
    fun `unmatched features are back-projected onto the wall`() {
        val map = WallFeatureMap()
        val g = grow(map, floatArrayOf(cx, cy, cx + 50f, cy, cx, cy + 25f))!!
        assertEquals(WallFeatureMap.Growth(3, 0, 0, false), g)
        assertEquals(3, map.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), map.point(0), 1e-5f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), map.point(1), 1e-5f)
        assertArrayEquals(floatArrayOf(0f, -0.5f, 0f), map.point(2), 1e-5f) // image down = wall down
        assertEquals(WallFeatureMap.NEW_POINT_CONFIDENCE, map.confidenceOf(0), 0f)
        assertArrayEquals(floatArrayOf(fx, fy, cx, cy), map.intrinsics!!, 0f)
        assertEquals(1L, map.revision)
    }

    @Test
    fun `matched features re-observe instead of adding`() {
        val map = WallFeatureMap()
        grow(map, floatArrayOf(cx, cy, cx + 50f, cy))
        val g = grow(map, floatArrayOf(cx, cy, cx - 50f, cy), matched = intArrayOf(1, -1), seed = 5)!!
        assertEquals(1, g.added)
        assertEquals(1, g.reobserved)
        assertEquals(3, map.size)
        assertEquals(0.2f, map.confidenceOf(1), 1e-6f)
        assertEquals(2, map.observationsOf(1))
        repeat(12) { grow(map, floatArrayOf(cx, cy), matched = intArrayOf(1)) }
        assertEquals(1f, map.confidenceOf(1), 0f)
    }

    @Test
    fun `a full map prunes unconfirmed points first and remaps matches`() {
        val map = WallFeatureMap(capacity = 4)
        grow(map, floatArrayOf(cx, cy, cx + 10f, cy, cx + 20f, cy))
        grow(map, floatArrayOf(cx, cy), matched = intArrayOf(2)) // point 2 earns 0.2
        grow(map, floatArrayOf(cx, cy + 5f)) // now at capacity
        assertEquals(4, map.size)
        val g = grow(map, floatArrayOf(cx, cy, cx, cy + 10f), matched = intArrayOf(2, 0), seed = 9)!!
        assertEquals(3, g.pruned)
        assertEquals(1, g.reobserved) // the match to old point 2 (now 0) survives
        assertEquals(1, g.added) // the match to pruned point 0 becomes a new point
        assertEquals(2, map.size)
        assertEquals(0.3f, map.confidenceOf(0), 1e-6f)
    }

    @Test
    fun `capacity bounds additions and mismatched descriptors are refused`() {
        val map = WallFeatureMap(capacity = 2)
        assertEquals(2, grow(map, floatArrayOf(cx, cy, cx + 1f, cy, cx + 2f, cy))!!.added)
        val other = map.grow(facingWall, floatArrayOf(cx, cy), ByteArray(16), 4, 5, intArrayOf(-1), wall, fx, fy, cx, cy)
        assertNull(other)
        assertNull(map.grow(facingWall, floatArrayOf(cx, cy), ByteArray(32), 32, 0, intArrayOf(-1, -1), wall, fx, fy, cx, cy))
    }

    @Test
    fun `off-wall features are placed radially from calibrated depth`() {
        // Camera frame == fingerprint frame; the "wall" is a floor 1 unit below (OpenCV y down), so
        // pixels above the principal row never hit it.
        val floor = WallPlane(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f))
        val w = 64; val h = 48
        val a = 2f; val b = 0.5f
        fun trueDepth(v: Int): Float = if (v > cy) fy / (v - cy) else 3f
        val dm = InverseDepthMap(FloatArray(w * h) { a / trueDepth(it / w) + b }, w, h, w, h)
        val pixels = ArrayList<Float>()
        for (v in listOf(30, 33, 36, 40, 44, 47)) for (u in listOf(10, 30, 50)) { pixels += u.toFloat(); pixels += v.toFloat() }
        val offWall = pixels.size / 2
        for (u in listOf(5, 25, 45)) { pixels += u.toFloat(); pixels += 10f }
        val map = WallFeatureMap()
        val g = grow(map, pixels.toFloatArray(), pose = identity, plane = floor, depth = dm)!!
        assertTrue(g.depthCalibrated)
        assertEquals(pixels.size / 2, g.added)
        for (i in offWall until map.size) assertEquals(3f, map.point(i)[2], 1e-2f)
        for (i in 0 until offWall) assertEquals(1f, map.point(i)[1], 1e-4f)
        // Without depth the off-wall features are discarded.
        val plain = WallFeatureMap()
        assertEquals(offWall, grow(plain, pixels.toFloatArray(), pose = identity, plane = floor)!!.added)
    }

    @Test
    fun `frustum gate keeps points in view`() {
        val map = WallFeatureMap()
        grow(map, floatArrayOf(cx, cy, cx + 30f, cy, 1f, 1f))
        assertArrayEquals(intArrayOf(0, 1, 2), map.visibleIndices(facingWall, fx, fy, cx, cy, 64, 48))
        // Shift the camera 0.5 to the right: point 2 (top-left) leaves the image.
        val shifted = facingWall.copyOf().also { it[3] = -0.5f }
        assertArrayEquals(intArrayOf(0, 1), map.visibleIndices(shifted, fx, fy, cx, cy, 64, 48))
        val behind = facingWall.copyOf().also { it[11] = -2f }
        assertEquals(0, map.visibleIndices(behind, fx, fy, cx, cy, 64, 48).size)
    }

    @Test
    fun `snapshot and native blob round-trip`() {
        val map = WallFeatureMap()
        grow(map, floatArrayOf(cx, cy, cx + 50f, cy, cx, cy + 25f))
        grow(map, floatArrayOf(cx, cy), matched = intArrayOf(1))
        val s = map.snapshot()
        assertEquals(3, s.descriptorsRows)
        assertEquals(32, s.descriptorsCols)
        assertEquals(96, s.descriptorsData.size)
        val back = WallFeatureMap.fromSnapshot(s)
        assertArrayEquals(s.points3d, back.snapshot().points3d, 0f)
        assertArrayEquals(s.descriptorsData, back.snapshot().descriptorsData)
        assertArrayEquals(s.obsCount, back.snapshot().obsCount)

        val blob = map.toBytes()
        assertEquals(16 + 3 * 20 + 80 + 96, blob.size)
        val parsed = WallFeatureMap.fromBytes(blob)!!
        assertArrayEquals(s.points3d, parsed.snapshot().points3d, 0f)
        assertArrayEquals(s.confidence, parsed.snapshot().confidence, 0f)
        assertArrayEquals(identity, parsed.anchor!!, 0f)
        assertArrayEquals(blob, parsed.toBytes())
        assertNull(WallFeatureMap.fromBytes(blob.copyOf(20)))
        assertEquals(0, WallFeatureMap.fromBytes(ByteArray(0))!!.size)
        assertEquals(0, WallFeatureMap().toBytes().size)
    }

    @Test
    fun `older snapshots without confidence align instead of desyncing`() {
        val s = WallFeatureMap.Snapshot(
            points3d = floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f),
            descriptorsData = ByteArray(64),
            descriptorsRows = 2,
            descriptorsCols = 32,
            descriptorsType = 0,
            confidence = FloatArray(0),
            obsCount = IntArray(0),
            anchor = FloatArray(0),
            intrinsics = FloatArray(0),
        )
        val map = WallFeatureMap.fromSnapshot(s)
        assertEquals(2, map.size)
        assertEquals(1f, map.confidenceOf(1), 0f)
        assertEquals(1, map.observationsOf(1))
        assertNull(map.anchor)
        assertNotNull(grow(map, floatArrayOf(cx, cy)))
    }
}

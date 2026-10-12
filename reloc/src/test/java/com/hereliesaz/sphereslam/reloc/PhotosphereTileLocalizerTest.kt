package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [PhotosphereTileLocalizer] against a synthetic world: the fake backend "detects" the projections of
 * known 3D points (ids as descriptors), so rotation, triangulation, plane and pose results are checked
 * against ground truth without OpenCV.
 */
class PhotosphereTileLocalizerTest {

    private val w = 640
    private val h = 480
    private val k = floatArrayOf(500f, 500f, 320f, 240f)

    /** Points on a wall plane z = 4 (world), spread wide enough for every view below. */
    private var wall: List<DoubleArray> = Random(3).let { rnd ->
        List(600) { doubleArrayOf(rnd.nextDouble() * 12 - 6, rnd.nextDouble() * 8 - 4, 4.0) }
    }

    /** The scene's surface `z = 4 + slope·x` (depth tests vary it). */
    private var slope = 0.0

    /** Points on a wall z = 4 + 0.3·x in front of a recessed panel — depth varies across the view. */
    private fun useObliqueScene() {
        slope = 0.3
        wall = Random(4).let { rnd -> List(800) { val x = rnd.nextDouble() * 10 - 5; doubleArrayOf(x, rnd.nextDouble() * 8 - 4, 4.0 + slope * x) } }
    }

    private class Pose(val r: DoubleArray, val c: DoubleArray)

    private val frames = HashMap<Int, Pose>()
    private var nextFrame = 1
    private var pixelNoise = 0.0
    private val noise = Random(5)

    private val backend = object : TileFeatureBackend {
        override fun extract(luma: ByteArray, width: Int, height: Int): TileFeatures? {
            val id = (luma[0].toInt() and 0xff) or ((luma[1].toInt() and 0xff) shl 8)
            val pose = frames[id] ?: return null
            val pts = ArrayList<Float>()
            val ids = ArrayList<Int>()
            wall.forEachIndexed { i, p ->
                val px = PhotosphereGeometry.project(pose.r, pose.c, p, k[0], k[1], k[2], k[3]) ?: return@forEachIndexed
                if (px[0] < 0 || px[0] >= width || px[1] < 0 || px[1] >= height) return@forEachIndexed
                pts += (px[0] + noise.nextGaussian() * pixelNoise).toFloat()
                pts += (px[1] + noise.nextGaussian() * pixelNoise).toFloat()
                ids += i
            }
            if (ids.isEmpty()) return null
            return TileFeatures(pts.toFloatArray(), ids.toIntArray())
        }

        override fun match(query: TileFeatures, train: TileFeatures): IntArray {
            val q = query.descriptors as IntArray
            val t = train.descriptors as IntArray
            val index = HashMap<Int, Int>().apply { t.forEachIndexed { i, id -> put(id, i) } }
            val out = ArrayList<Int>()
            q.forEachIndexed { i, id -> index[id]?.let { out += i; out += it } }
            return out.toIntArray()
        }
    }

    private fun lumaFor(pose: Pose): ByteArray {
        val id = nextFrame++
        frames[id] = pose
        return ByteArray(w * h).also { it[0] = (id and 0xff).toByte(); it[1] = (id shr 8).toByte() }
    }

    /** World→camera (OpenCV) for a camera looking along +z, turned [yawDeg] about world y, [pitchDeg] about x. */
    private fun rot(yawDeg: Double, pitchDeg: Double = 0.0): DoubleArray {
        val y = Math.toRadians(yawDeg); val p = Math.toRadians(pitchDeg)
        // Camera axes in world: x right, y down, z forward; world y is down for this synthetic scene.
        val ry = doubleArrayOf(cos(y), 0.0, -sin(y), 0.0, 1.0, 0.0, sin(y), 0.0, cos(y))
        val rx = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(p), sin(p), 0.0, -sin(p), cos(p))
        return PhotosphereTileLocalizer.mul3(rx, ry)
    }

    private fun angleBetween(a: DoubleArray, b: DoubleArray): Double {
        // angle of a·bᵀ
        val m = PhotosphereTileLocalizer.mul3(a, DoubleArray(9) { i -> b[(i % 3) * 3 + i / 3] })
        val tr = (m[0] + m[4] + m[8] - 1) / 2
        return Math.toDegrees(kotlin.math.acos(tr.coerceIn(-1.0, 1.0)))
    }

    private fun dist(a: DoubleArray, b: DoubleArray) =
        sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

    @Test
    fun `a tile of the camera's station fixes its rotation`() {
        val loc = PhotosphereTileLocalizer(backend)
        val s0 = loc.addStation(DoubleArray(3))
        val origin = DoubleArray(3)
        assertNotNull(loc.addTile(s0, lumaFor(Pose(rot(0.0), origin)), w, h, k, rot(0.0)))
        pixelNoise = 0.3
        val truth = rot(9.0, 4.0)
        val live = backend.extract(lumaFor(Pose(truth, origin)), w, h)!!
        // The prior is off by several degrees (gyro drift); the fix is visual.
        val fix = loc.localize(live, k, rot(4.0), origin)
        assertTrue(fix is PhotosphereTileLocalizer.Fix.Rotation)
        assertEquals(0.0, angleBetween(fix!!.rotation, truth), 0.1)
        // Not the camera's standpoint: no rotation-only fix (it would carry the parallax).
        assertNull(loc.localize(live, k, rot(4.0), doubleArrayOf(1.0, 0.0, 0.0)))
    }

    @Test
    fun `the live frame is turned by the sensors into the nearest tile's view`() {
        val loc = PhotosphereTileLocalizer(backend)
        val s0 = loc.addStation(DoubleArray(3))
        val origin = DoubleArray(3)
        loc.addTile(s0, lumaFor(Pose(rot(0.0), origin)), w, h, k, rot(0.0))!!
        // 20° off the tile: the turn maps every live pixel onto where the tile saw the same point.
        val prior = rot(20.0, 6.0)
        val turn = loc.turnTowardTile(prior, origin, k)!!
        for (p in wall.take(80)) {
            val live = PhotosphereGeometry.project(prior, origin, p, k[0], k[1], k[2], k[3]) ?: continue
            val tile = PhotosphereGeometry.project(rot(0.0), origin, p, k[0], k[1], k[2], k[3]) ?: continue
            val wgt = turn[6] * live[0] + turn[7] * live[1] + turn[8]
            assertEquals(tile[0].toDouble(), (turn[0] * live[0] + turn[1] * live[1] + turn[2]) / wgt, 1e-2)
            assertEquals(tile[1].toDouble(), (turn[3] * live[0] + turn[4] * live[1] + turn[5]) / wgt, 1e-2)
        }
        // Nearly the tile's view already, or turning off: no turn.
        assertNull(loc.turnTowardTile(rot(3.0), origin, k))
        assertNull(PhotosphereTileLocalizer(backend, PhotosphereTileLocalizer.Config(turnLiveFrameDeg = 0f)).also { l ->
            l.addTile(l.addStation(DoubleArray(3)), lumaFor(Pose(rot(0.0), origin)), w, h, k, rot(0.0))
        }.turnTowardTile(prior, origin, k))
    }

    @Test
    fun `features of the turned frame come back in the live frame's pixels`() {
        // A backend that finds the bright pixels of what it is given.
        var seen: ByteArray? = null
        val dots = object : TileFeatureBackend {
            override fun extract(luma: ByteArray, width: Int, height: Int): TileFeatures? {
                seen = luma
                val pts = ArrayList<Float>()
                for (i in luma.indices) if ((luma[i].toInt() and 0xff) > 200) { pts += (i % width).toFloat(); pts += (i / width).toFloat() }
                return TileFeatures(pts.toFloatArray(), null)
            }
            override fun match(query: TileFeatures, train: TileFeatures) = IntArray(0)
        }
        val loc = PhotosphereTileLocalizer(dots)
        val ww = 64; val hh = 48
        val luma = ByteArray(ww * hh) { 20 }
        // Turned frame = live shifted by (+5, −3): pixel (u, v) of the turned image is live (u − 5, v + 3).
        val shift = doubleArrayOf(1.0, 0.0, 5.0, 0.0, 1.0, -3.0, 0.0, 0.0, 1.0)
        luma[20 * ww + 30] = 250.toByte()
        val f = loc.extractTurned(luma, ww, hh, shift)!!
        // The detector saw the dot moved by the turn …
        assertEquals(250, seen!![17 * ww + 35].toInt() and 0xff)
        // … and reports it where the live frame has it.
        assertEquals(listOf(30f, 20f), f.points.toList())
        // Turned pixels from outside the live frame are mid-grey.
        assertEquals(128, seen!![0].toInt() and 0xff)
    }

    @Test
    fun `the tiles around the lock direction outlive the tile cap`() {
        val loc = PhotosphereTileLocalizer(backend, PhotosphereTileLocalizer.Config(maxTilesPerStation = 4))
        val s0 = loc.addStation(DoubleArray(3))
        val origin = DoubleArray(3)
        fun add(yaw: Double) = loc.addTile(s0, lumaFor(Pose(rot(yaw), origin)), w, h, k, rot(yaw))!!
        val lockTile = add(0.0)
        loc.anchor(s0, rot(2.0)) // the wall locked from (near) the first tile's direction
        assertTrue(lockTile.anchored)
        // Neighbours captured after the lock (within anchorRadiusDeg = 24) are anchored too.
        val right = add(20.0)
        val left = add(-20.0)
        assertTrue(right.anchored && left.anchored)
        // Farther tiles fill the cap and push each other out; the anchor tiles stay.
        val far = listOf(33.0, -33.0, 46.0, -46.0).map { add(it) }
        assertTrue(far.none { it.anchored })
        assertEquals(4, s0.tileCount)
        assertEquals(listOf(lockTile, right, left, far.last()), s0.tiles)
        assertEquals(3, s0.anchorTileCount)
        // A later lock elsewhere does not move the anchor.
        loc.anchor(s0, rot(45.0))
        assertEquals(3, s0.anchorTileCount)
    }

    @Test
    fun `a new tile that only anchor tiles could make room for is not kept`() {
        val loc = PhotosphereTileLocalizer(backend, PhotosphereTileLocalizer.Config(maxTilesPerStation = 2))
        val s0 = loc.addStation(DoubleArray(3))
        val origin = DoubleArray(3)
        loc.anchor(s0, rot(0.0))
        val anchored = listOf(0.0, 20.0).map { loc.addTile(s0, lumaFor(Pose(rot(it), origin)), w, h, k, rot(it))!! }
        assertNull(loc.addTile(s0, lumaFor(Pose(rot(-20.0), origin)), w, h, k, rot(-20.0)))
        assertEquals(anchored, s0.tiles)
    }

    @Test
    fun `keyframes closer than the tile spacing do not add tiles`() {
        val loc = PhotosphereTileLocalizer(backend)
        val s0 = loc.addStation(DoubleArray(3))
        assertNotNull(loc.addTile(s0, lumaFor(Pose(rot(0.0), DoubleArray(3))), w, h, k, rot(0.0)))
        assertNull(loc.addTile(s0, lumaFor(Pose(rot(5.0), DoubleArray(3))), w, h, k, rot(5.0)))
        assertNotNull(loc.addTile(s0, lumaFor(Pose(rot(15.0), DoubleArray(3))), w, h, k, rot(15.0)))
        assertEquals(2, s0.tileCount)
    }

    @Test
    fun `a second station triangulates landmarks and a plane, which localize the camera anywhere`() {
        val loc = PhotosphereTileLocalizer(backend)
        val c0 = DoubleArray(3)
        val c1 = doubleArrayOf(0.5, 0.0, 0.0)
        val s0 = loc.addStation(c0)
        val s1 = loc.addStation(c1)
        pixelNoise = 0.2
        val t0 = loc.addTile(s0, lumaFor(Pose(rot(0.0), c0)), w, h, k, rot(0.0))!!
        val t1 = loc.addTile(s1, lumaFor(Pose(rot(-3.0), c1)), w, h, k, rot(-3.0))!!
        assertTrue("landmarks ${t0.landmarkCount}", t0.landmarkCount > 100)
        assertTrue(t1.landmarkCount > 100)
        // Landmarks sit on the wall.
        val err = t0.landmarks.values.map { kotlin.math.abs(it[2] - 4.0) }.sorted()
        assertTrue("median depth error ${err[err.size / 2]}", err[err.size / 2] < 0.05)
        val plane = t0.plane!!
        assertEquals(1.0, kotlin.math.abs(plane.normal[2]), 1e-2)

        // A third standpoint the photosphere never visited, wall off-centre in view.
        val truthR = rot(12.0, -5.0)
        val truthC = doubleArrayOf(0.25, 0.3, -0.6)
        val live = backend.extract(lumaFor(Pose(truthR, truthC)), w, h)!!
        val fix = loc.localize(live, k, rot(8.0), doubleArrayOf(0.1, 0.2, -0.4))
        assertTrue(fix is PhotosphereTileLocalizer.Fix.Pose)
        fix as PhotosphereTileLocalizer.Fix.Pose
        assertEquals(0.0, angleBetween(fix.rotation, truthR), 0.3)
        assertTrue("centre error ${dist(fix.centre, truthC)}", dist(fix.centre, truthC) < 0.03)
    }

    @Test
    fun `a tile's plane predicts how it looks from a new position`() {
        val loc = PhotosphereTileLocalizer(backend)
        val c0 = DoubleArray(3)
        val c1 = doubleArrayOf(0.6, 0.0, 0.0)
        val s0 = loc.addStation(c0)
        val s1 = loc.addStation(c1)
        val t0 = loc.addTile(s0, lumaFor(Pose(rot(0.0), c0)), w, h, k, rot(0.0))!!
        loc.addTile(s1, lumaFor(Pose(rot(0.0), c1)), w, h, k, rot(0.0))
        assertNotNull(t0.plane)
        val r = rot(-10.0, 3.0)
        val c = doubleArrayOf(-0.4, 0.2, -1.0)
        val hm = loc.predictTileHomography(t0, r, c, k)!!
        // Any wall point seen by the tile lands where the new camera actually sees it.
        for (p in wall.take(50)) {
            val inTile = PhotosphereGeometry.project(t0.rotation, c0, p, k[0], k[1], k[2], k[3]) ?: continue
            val actual = PhotosphereGeometry.project(r, c, p, k[0], k[1], k[2], k[3]) ?: continue
            val predicted = PhotosphereGeometry.applyHomography(hm, inTile[0].toDouble(), inTile[1].toDouble())!!
            assertEquals(actual[0].toDouble(), predicted[0], 0.5)
            assertEquals(actual[1].toDouble(), predicted[1], 0.5)
        }
    }

    @Test
    fun `nearest station and clearing`() {
        val loc = PhotosphereTileLocalizer(backend)
        loc.addStation(DoubleArray(3))
        val s1 = loc.addStation(doubleArrayOf(1.0, 0.0, 0.0))
        assertEquals(s1, loc.nearestStation(doubleArrayOf(0.8, 0.0, 0.0))!!.first)
        loc.clear()
        assertNull(loc.nearestStation(DoubleArray(3)))
    }

    /**
     * A MiDaS-like map for a camera at [pose] looking at the wall z = 4: affine in true inverse depth
     * (unknown scale 3 and shift 0.2), at a quarter of the image resolution, with noise.
     */
    private fun midasFor(pose: Pose): TileDepthMap {
        val dw = w / 4; val dh = h / 4
        val rnd = Random(9)
        val rt = PhotosphereTileLocalizer.transpose(pose.r)
        val values = FloatArray(dw * dh) { i ->
            val u = ((i % dw) + 0.5f) * 4 - 0.5f
            val v = ((i / dw) + 0.5f) * 4 - 0.5f
            val b = PhotosphereGeometry.bearing(u, v, k[0], k[1], k[2], k[3])
            val d = DoubleArray(3) { r -> rt[r * 3] * b[0] + rt[r * 3 + 1] * b[1] + rt[r * 3 + 2] * b[2] }
            // Ray hits z = 4 + slope·x.
            val t = (4.0 + slope * pose.c[0] - pose.c[2]) / (d[2] - slope * d[0])
            val z = t * b[2]
            (3.0 / z + 0.2 + rnd.nextGaussian() * 0.002).toFloat()
        }
        return TileDepthMap(dw, dh, values)
    }

    private val wallPlane = PhotosphereGeometry.Plane(doubleArrayOf(0.0, 0.0, 1.0), 4.0)

    @Test
    fun `the wall seen head-on places a moved camera from a single station`() {
        val loc = PhotosphereTileLocalizer(backend)
        val s0 = loc.addStation(DoubleArray(3))
        loc.setWallPlane(wallPlane)
        val p0 = Pose(rot(0.0), DoubleArray(3))
        val t0 = loc.addTile(s0, lumaFor(p0), w, h, k, p0.r, depth = midasFor(p0))!!
        assertTrue("pseudo ${t0.pseudoLandmarkCount}", t0.pseudoLandmarkCount > 100)
        // Head-on wall: one depth, so no MiDaS fit — the wall-consistent features are placed on it.
        assertNull(t0.depthFit)
        val err = t0.pseudo.values.map { kotlin.math.abs(it[2] - 4.0) }.sorted()
        assertTrue("median depth error ${err[err.size / 2]}", err[err.size / 2] < 1e-6)

        // The artist has stepped away; no second station exists yet.
        pixelNoise = 0.3
        val truthR = rot(-7.0, 3.0)
        val truthC = doubleArrayOf(-0.35, 0.1, -0.5)
        val live = backend.extract(lumaFor(Pose(truthR, truthC)), w, h)!!
        val fix = loc.localize(live, k, rot(-3.0), DoubleArray(3))
        assertTrue(fix is PhotosphereTileLocalizer.Fix.Pose)
        fix as PhotosphereTileLocalizer.Fix.Pose
        assertEquals(0.0, angleBetween(fix.rotation, truthR), 0.3)
        assertTrue("centre error ${dist(fix.centre, truthC)}", dist(fix.centre, truthC) < 0.05)
    }

    @Test
    fun `an oblique surface calibrates MiDaS, and that tile calibrates an overlapping one`() {
        useObliqueScene()
        val loc = PhotosphereTileLocalizer(backend)
        val s0 = loc.addStation(DoubleArray(3))
        // n·x = offset for z − 0.3·x = 4.
        val n = doubleArrayOf(-slope, 0.0, 1.0).let { v -> val l = sqrt(v[0] * v[0] + 1.0); doubleArrayOf(v[0] / l, 0.0, 1.0 / l) }
        loc.setWallPlane(PhotosphereGeometry.Plane(n, 4.0 * n[2]))
        val p0 = Pose(rot(0.0), DoubleArray(3))
        val t0 = loc.addTile(s0, lumaFor(p0), w, h, k, p0.r, depth = midasFor(p0))!!
        assertNotNull(t0.depthFit)
        // No wall reference from here on: the next tile can only learn its scale from its neighbour.
        loc.setWallPlane(null)
        val p1 = Pose(rot(15.0), DoubleArray(3))
        val t1 = loc.addTile(s0, lumaFor(p1), w, h, k, p1.r, depth = midasFor(p1))!!
        assertNotNull(t1.depthFit)
        val err = t1.pseudo.values.map { kotlin.math.abs(it[2] - (4.0 + slope * it[0])) }.sorted()
        assertTrue("median depth error ${err[err.size / 2]}", err[err.size / 2] < 0.05)
    }

    @Test
    fun `the wall plane is no depth reference beyond the wall's extent`() {
        val loc = PhotosphereTileLocalizer(backend)
        val s0 = loc.addStation(DoubleArray(3))
        // The real wall spans only |x| <= 0.5: every ray here meets the plane's extension, not the wall.
        loc.setWallPlane(wallPlane) { p -> kotlin.math.abs(p[0]) <= 0.5 && kotlin.math.abs(p[1]) <= 0.5 }
        val p0 = Pose(rot(35.0), DoubleArray(3))
        val t0 = loc.addTile(s0, lumaFor(p0), w, h, k, p0.r, depth = midasFor(p0))!!
        assertNull(t0.depthFit)
        assertEquals(0, t0.pseudoLandmarkCount)
    }

    @Test
    fun `without a depth reference a tile stays uncalibrated`() {
        val loc = PhotosphereTileLocalizer(backend)
        val s0 = loc.addStation(DoubleArray(3))
        val p0 = Pose(rot(0.0), DoubleArray(3))
        val t0 = loc.addTile(s0, lumaFor(p0), w, h, k, p0.r, depth = midasFor(p0))!!
        assertNull(t0.depthFit)
        assertEquals(0, t0.pseudoLandmarkCount)
        // The wall arrives later: placed then.
        loc.setWallPlane(wallPlane)
        assertTrue(t0.pseudoLandmarkCount > 100)
    }
}

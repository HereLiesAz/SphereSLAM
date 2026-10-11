package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * [PhotosphereStationTracker] end to end on a synthetic scene: a fake feature backend "sees" the
 * projections of known world points for the true camera pose encoded in each frame, while the gyro
 * base rotation it is fed drifts away from the truth — so a tile fix must pull the rendered rotation
 * back to the truth, not to the gyro.
 */
class PhotosphereStationTrackerTest {

    private val intrinsics = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)
    private val k = floatArrayOf(500f, 500f, 320f, 240f)

    /** Points on a shell 3–6 units around the origin: something to see in every direction. */
    private val scene: List<DoubleArray> = Random(21).let { rnd ->
        List(3000) {
            val v = DoubleArray(3) { rnd.nextGaussian() }
            val n = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            val r = 3.0 + rnd.nextDouble() * 3.0
            DoubleArray(3) { i -> v[i] / n * r }
        }
    }

    /** True GL world→camera rotation and centre per frame id. */
    private val truth = HashMap<Int, Pair<DoubleArray, DoubleArray>>()
    private var nextId = 1
    private var extractCalls = 0
    private var linkFailure = false

    private val backend = object : TileFeatureBackend {
        override fun extract(luma: ByteArray, width: Int, height: Int): TileFeatures? {
            extractCalls++
            if (linkFailure) throw UnsatisfiedLinkError("no opencv_java")
            val id = (luma[0].toInt() and 0xff) or ((luma[1].toInt() and 0xff) shl 8)
            val (rGl, c) = truth[id] ?: return null
            val r = PhotosphereStationTracker.cv(rGl)
            val pts = ArrayList<Float>()
            val ids = ArrayList<Int>()
            scene.forEachIndexed { i, p ->
                val px = PhotosphereGeometry.project(r, c, p, k[0], k[1], k[2], k[3]) ?: return@forEachIndexed
                if (px[0] < 0 || px[0] >= width || px[1] < 0 || px[1] >= height) return@forEachIndexed
                pts += px[0]; pts += px[1]; ids += i
            }
            return if (ids.isEmpty()) null else TileFeatures(pts.toFloatArray(), ids.toIntArray())
        }

        override fun match(query: TileFeatures, train: TileFeatures): IntArray {
            val t = (train.descriptors as IntArray).withIndex().associate { it.value to it.index }
            val out = ArrayList<Int>()
            (query.descriptors as IntArray).forEachIndexed { i, id -> t[id]?.let { out += i; out += it } }
            return out.toIntArray()
        }
    }

    private fun frameFor(rGl: DoubleArray, c: DoubleArray = DoubleArray(3)): ByteArray {
        val id = nextId++
        truth[id] = rGl to c
        return ByteArray(640 * 480).also { it[0] = (id and 0xff).toByte(); it[1] = (id shr 8).toByte() }
    }

    /** GL world→camera rotation for a camera yawed [yawDeg] about world y (looking down −z at 0). */
    private fun yaw(yawDeg: Double): DoubleArray {
        val a = Math.toRadians(yawDeg)
        return doubleArrayOf(cos(a), 0.0, -sin(a), 0.0, 1.0, 0.0, sin(a), 0.0, cos(a))
    }

    private fun angle(a: DoubleArray, b: DoubleArray) =
        Math.toDegrees(PhotosphereGeometry.rotationAngleRad(a, b))

    private fun tracker() = PhotosphereStationTracker(
        backend, null, java.util.concurrent.Executor(Runnable::run), PhotosphereStationTracker.Config(),
    )

    /** The gyro reports truth · D: a world-side drift D (heading error) about world y. */
    private fun gyro(trueR: DoubleArray, driftDeg: Double) = PhotosphereStationTracker.mul3(trueR, yaw(driftDeg))

    @Test
    fun `nothing is captured, fixed or posed before the first lock`() {
        val t = tracker()
        val r = yaw(0.0)
        assertFalse(t.wantsKeyframe(0))
        t.onKeyframe(r, frameFor(r), intrinsics, 0)
        t.onFrame(r, frameFor(r), intrinsics, 1_000)
        assertNull(t.pose(r))
        assertEquals(0, extractCalls)
    }

    @Test
    fun `a tile fix corrects gyro drift off the wall`() {
        val t = tracker()
        val r0 = yaw(0.0)
        // The lock defines the map relation (gyro already 2° off), then keyframes become tiles.
        t.onLock(PhotosphereStationTracker.viewOf(r0, DoubleArray(3)), gyro(r0, 2.0), 0)
        for ((i, deg) in listOf(0.0, 15.0, 30.0, 45.0).withIndex()) {
            val r = yaw(deg)
            assertTrue(t.wantsKeyframe(100L + i))
            t.onKeyframe(gyro(r, 2.0), frameFor(r), intrinsics, 100L + i)
        }
        // Later, the wall long gone, the gyro has drifted to 9° off.
        val trueR = yaw(37.0)
        val drifted = gyro(trueR, 9.0)
        val carried = PhotosphereStationTracker.rotationOfView(t.pose(drifted)!!)
        assertEquals(7.0, angle(carried, trueR), 0.05) // gyro-carried: off by the new drift
        t.onFrame(drifted, frameFor(trueR), intrinsics, 2_000) // localizes (direct executor)
        t.onFrame(drifted, frameFor(trueR), intrinsics, 2_010) // adopts
        val fixed = PhotosphereStationTracker.rotationOfView(t.pose(drifted)!!)
        assertEquals(0.0, angle(fixed, trueR), 0.05)
    }

    @Test
    fun `a fix measured before a newer lock is dropped`() {
        val t = tracker()
        val r0 = yaw(0.0)
        t.onLock(PhotosphereStationTracker.viewOf(r0, DoubleArray(3)), r0, 0)
        t.onKeyframe(r0, frameFor(r0), intrinsics, 10)
        val trueR = yaw(5.0)
        t.onFrame(gyro(trueR, 4.0), frameFor(trueR), intrinsics, 1_000) // fix measured at 1000, pending
        // A lock lands at 1200 saying the gyro is now exact.
        t.onLock(PhotosphereStationTracker.viewOf(trueR, DoubleArray(3)), trueR, 1_200)
        t.onFrame(trueR, frameFor(trueR), intrinsics, 1_210) // would adopt the stale fix
        assertEquals(0.0, angle(PhotosphereStationTracker.rotationOfView(t.pose(trueR)!!), trueR), 1e-6)
    }

    @Test
    fun `a lock far from every station opens one, and reset forgets everything`() {
        val t = tracker()
        t.setSpawnDistance(0.3)
        val r = yaw(0.0)
        t.onLock(PhotosphereStationTracker.viewOf(r, DoubleArray(3)), r, 0)
        t.onLock(PhotosphereStationTracker.viewOf(r, doubleArrayOf(0.5, 0.0, 0.0)), r, 10)
        assertEquals(2, t.stationCount)
        assertEquals(1, t.activeStationIndex)
        val lines = mutableListOf<String>()
        t.drainDiagnostics { lines += it }
        assertTrue(lines.any { it.startsWith("SphereSLAM station 1 opened") })
        t.reset()
        assertEquals(1, t.stationCount)
        assertNull(t.pose(r))
        assertArrayEquals(DoubleArray(3), t.cameraCentre, 0.0)
    }

    @Test
    fun `a missing OpenCV runtime disables tile localization once`() {
        val t = tracker()
        val r = yaw(0.0)
        t.onLock(PhotosphereStationTracker.viewOf(r, DoubleArray(3)), r, 0)
        linkFailure = true
        t.onKeyframe(r, frameFor(r), intrinsics, 10)
        t.onFrame(r, frameFor(r), intrinsics, 2_000)
        t.onFrame(r, frameFor(r), intrinsics, 3_000)
        val lines = mutableListOf<String>()
        t.drainDiagnostics { lines += it }
        assertEquals(1, lines.count { it.startsWith("SphereSLAM tile localization disabled") })
        assertEquals(1, extractCalls)
        assertFalse(t.hasCorrection)
        assertNull(t.pose(r))
    }

    @Test
    fun `the wall test follows map_from_fingerprint and the pages' extent`() {
        // Wall 2 units in front of the map origin (camera looks down −z), turned 30° about y.
        val a = Math.toRadians(30.0)
        val m = floatArrayOf(
            cos(a).toFloat(), 0f, -sin(a).toFloat(), 0f,
            0f, 1f, 0f, 0f,
            sin(a).toFloat(), 0f, cos(a).toFloat(), 0f,
            0f, 0f, -2f, 1f,
        )
        val plane = PhotosphereStationTracker.wallPlaneOf(m)!!
        assertNotNull(plane)
        assertEquals(0.0, plane.distance(doubleArrayOf(0.0, 0.0, -2.0)), 1e-6)
        val onWall = PhotosphereStationTracker.onWallTest(m, listOf(floatArrayOf(0f, 0f, 1f, 0.75f)))
        assertTrue(onWall(doubleArrayOf(0.0, 0.0, -2.0)))
        // 0.45 along the wall's x axis: inside 0.5 + 10 %; 1.0 along it: off the wall.
        assertTrue(onWall(doubleArrayOf(0.45 * cos(a), 0.0, -2.0 - 0.45 * sin(a))))
        assertFalse(onWall(doubleArrayOf(1.0 * cos(a), 0.0, -2.0 - 1.0 * sin(a))))
    }

    @Test
    fun `view helpers round-trip`() {
        val r = PhotosphereStationTracker.mul3(yaw(23.0), yaw(-4.0))
        val c = doubleArrayOf(0.3, -0.2, 1.1)
        val v = PhotosphereStationTracker.viewOf(r, c)
        assertArrayEquals(r, PhotosphereStationTracker.rotationOfView(v), 1e-6)
        assertArrayEquals(c, PhotosphereStationTracker.centreOfView(v), 1e-5)
        assertArrayEquals(r, PhotosphereStationTracker.glFromCv(PhotosphereStationTracker.cv(r)), 0.0)
    }
}

package com.hereliesaz.sphereslam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Painting on a floor or ceiling puts the camera at the zenith/nadir, where the camera-axis heading
 * is noise. Everything that places, frames or selects there must be as repeatable as at the wall.
 */
class PoleRobustnessTest {

    private val width = 640
    private val height = 480
    private val f = 500f
    private val rng = java.util.Random(7)

    /** Device→ENU row-major matrix for a camera looking along [fwd] with image-up near [upHint]. */
    private fun deviceToWorld(fwd: DoubleArray, upHint: DoubleArray): FloatArray {
        val fw = AttitudeBasis.normalized(fwd)!!
        val along = AttitudeBasis.dot(upHint, fw)
        val up = AttitudeBasis.normalized(DoubleArray(3) { upHint[it] - along * fw[it] })!!
        val back = doubleArrayOf(-fw[0], -fw[1], -fw[2])
        val right = AttitudeBasis.cross(up, back)
        // Columns: device x = right, y = up, z = back.
        return FloatArray(9) { i ->
            val row = i / 3; val col = i % 3
            when (col) { 0 -> right[row]; 1 -> up[row]; else -> back[row] }.toFloat()
        }
    }

    private fun azimuthVector(deg: Double) = doubleArrayOf(sin(Math.toRadians(deg)), cos(Math.toRadians(deg)), 0.0)

    /** Looking straight down, the top of the image toward [facingDeg], tilted off the pole by up to [jitterDeg]. */
    private fun floorPose(facingDeg: Double, jitterDeg: Double): FloatArray {
        val tilt = Math.toRadians(jitterDeg * rng.nextDouble())
        val dir = rng.nextDouble() * 2 * Math.PI
        val fwd = doubleArrayOf(sin(tilt) * cos(dir), sin(tilt) * sin(dir), -cos(tilt))
        return deviceToWorld(fwd, azimuthVector(facingDeg))
    }

    private fun decode(r: FloatArray): Triple<Float, Float, Float> {
        val (h, e) = CameraAttitudeProvider.cameraAxisHeadingElevation(r)!!
        return Triple(h, e, CameraAttitudeProvider.cameraRollDegrees(r)!!)
    }

    private fun view(r: FloatArray): PhotosphereView {
        val (h, e, roll) = decode(r)
        return PhotosphereView(h, e, roll, width, height, f, f, width / 2f, height / 2f)
    }

    private fun anchoredMap() = PhotosphereMap().apply { setWallHeading(0f) }

    private fun angleDeg(a: FloatArray, b: FloatArray): Double {
        var trace = 0.0
        for (c in 0..2) for (r in 0..2) trace += a[c * 4 + r] * b[c * 4 + r]
        return Math.toDegrees(acos(((trace - 1) / 2).coerceIn(-1.0, 1.0)))
    }

    @Test
    fun `heading elevation and roll of one sample rebuild it exactly, straight down included`() {
        val poses = listOf(floorPose(37.0, 0.0), floorPose(200.0, 0.001), floorPose(90.0, 0.5)) +
            List(20) { floorPose(rng.nextDouble() * 360, 3.0) } +
            List(5) { deviceToWorld(doubleArrayOf(0.0, 0.0, 1.0), azimuthVector(rng.nextDouble() * 360)) }
        for (r in poses) {
            val (h, e, roll) = decode(r)
            val m = CoverageGlowProjection.worldFromCamera(h, e, roll)
            assertNotNull(m)
            for (i in 0..8) assertEquals("element $i of ${r.toList()}", r[i], m!![i], 2e-4f)
        }
    }

    @Test
    fun `facing azimuth is where the top of the image points over a floor and the heading at the wall`() {
        repeat(20) {
            val facing = rng.nextDouble() * 360
            val (h, e, roll) = decode(floorPose(facing, 1.0))
            val got = AttitudeBasis.facingAzimuthDeg(h, e, roll)!!
            assertEquals(0f, SphereGrid.signedDelta(got, facing.toFloat()), 1.5f)
        }
        assertEquals(123f, AttitudeBasis.facingAzimuthDeg(123f, 0f, 0f)!!, 1e-3f)
        assertEquals(123f, AttitudeBasis.facingAzimuthDeg(123f, 40f, 0f)!!, 1e-3f)
        // Under a ceiling the top of the image points back toward the artist; facing is still forward.
        val ceiling = deviceToWorld(doubleArrayOf(0.0, 0.0001, 1.0), azimuthVector(250.0).map { -it }.toDoubleArray())
        val (h, e, roll) = decode(ceiling)
        assertEquals(0f, SphereGrid.signedDelta(AttitudeBasis.facingAzimuthDeg(h, e, roll)!!, 250f), 0.5f)
    }

    @Test
    fun `a fingerprint captured over a floor is placed the same way every time`() {
        val map = anchoredMap()
        val placements = List(30) {
            PhotosphereFingerprintFrame.placement(map, view(floorPose(75.0, 0.3)))!!.mapFromFingerprint
        }
        for (p in placements) {
            assertTrue("rotation ${angleDeg(p, placements[0])}°", angleDeg(p, placements[0]) < 1.0)
            for (i in 12..14) assertEquals(placements[0][i], p[i], 0.01f) // within 1 cm per metre
        }
        // The page's +Y is the image's up: toward the facing bearing (75°), in map coordinates.
        val y = doubleArrayOf(placements[0][4].toDouble(), placements[0][5].toDouble(), placements[0][6].toDouble())
        val expected = AttitudeBasis.enuToMap(azimuthVector(75.0))
        assertTrue(AttitudeBasis.dot(y, expected) > 0.99)
    }

    @Test
    fun `a tap off centre over a floor lands on the true ray whichever way the artist faces`() {
        val map = anchoredMap()
        repeat(20) {
            val r = floorPose(rng.nextDouble() * 360, 2.0)
            val u = 100f; val v = 60f
            val xn = (u - width / 2f) / f; val yn = (v - height / 2f) / f
            // Camera ray (x right, y up, looking −z) for pixel (u, v) → ENU through the device matrix.
            val cam = doubleArrayOf(xn.toDouble(), -yn.toDouble(), -1.0)
            val world = DoubleArray(3) { row -> (0..2).sumOf { k -> r[row * 3 + k] * cam[k] } }
            val expected = AttitudeBasis.normalized(AttitudeBasis.enuToMap(world))!!
            val p = PhotosphereFingerprintFrame.placement(map, view(r), u / width, v / height)!!.mapFromFingerprint
            val t = AttitudeBasis.normalized(doubleArrayOf(p[12].toDouble(), p[13].toDouble(), p[14].toDouble()))!!
            assertTrue("tap ray off by ${Math.toDegrees(acos(AttitudeBasis.dot(t, expected).coerceIn(-1.0, 1.0)))}°", AttitudeBasis.dot(t, expected) > 0.99999)
        }
    }

    @Test
    fun `the capture view is always the live attitude, never a stored keyframe's`() {
        val map = PhotosphereMap()
        val store = PhotosphereKeyframeStore(map)
        val luma = ByteArray(width * height) { (it % 251).toByte() }
        store.record(PhotosphereKeyframe(1L, luma, width, height, floatArrayOf(400f, 400f, 320f, 240f), 10f, -85f, rollDeg = 0f), 0L)
        // Same tile as the keyframe (near the nadir its heading says little about where it faced).
        assertEquals(map.tileAt(10f, -85f), map.tileAt(12f, -85.5f))
        val live = store.view(12f, -85.5f, 30f)!!
        assertEquals(12f, live.headingDeg, 0f)
        assertEquals(-85.5f, live.elevationDeg, 0f)
        assertEquals(30f, live.rollDeg, 0f)
        // The stored keyframe still supplies the intrinsics.
        assertEquals(400f, live.fx, 0f)
    }

    @Test
    fun `tiles in view over a floor do not depend on the noisy heading`() {
        val map = anchoredMap()
        val sets = List(20) {
            val (h, e, roll) = decode(floorPose(140.0, 0.05))
            // Narrow enough that the frustum's orientation decides which polar tiles are on screen.
            map.tilesInView(h, e, 40f, 20f, roll).toSet()
        }
        sets.forEach { assertEquals(sets[0], it) }
        assertTrue(sets[0].isNotEmpty())
        assertTrue(sets[0].size < 24) // not simply the whole polar band
    }

    @Test
    fun `a map first seen over a floor anchors at the bearing the artist faces`() {
        val map = PhotosphereMap()
        val (h, e, roll) = decode(floorPose(210.0, 0.2))
        map.recordView(h, e, roll, CoverageGlowProjection.Lens.fromIntrinsics(f, f, width / 2f, height / 2f, width, height))
        val anchor = map.snapshot().wallHeadingDeg!!
        assertEquals(0f, SphereGrid.signedDelta(anchor, 210f), 1f)
    }

    @Test
    fun `the decoded attitude of an exact nadir is still a valid placement`() {
        val r = deviceToWorld(doubleArrayOf(0.0, 0.0, -1.0), azimuthVector(300.0))
        val p = PhotosphereFingerprintFrame.placement(anchoredMap(), view(r))
        assertNotNull(p)
        val m = p!!.mapFromFingerprint
        assertEquals(-1f, m[13], 1e-5f) // straight down: map −Y at unit range
        val n = sqrt(m[12] * m[12] + m[13] * m[13] + m[14] * m[14])
        assertEquals(1f, n, 1e-5f)
    }
}

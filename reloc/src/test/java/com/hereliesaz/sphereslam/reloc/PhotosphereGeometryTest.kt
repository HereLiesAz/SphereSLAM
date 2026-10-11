package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.reloc.PhotosphereGeometry.Plane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

@OptIn(ExperimentalSphereSlamRelocApi::class)
class PhotosphereGeometryTest {

    private val g = PhotosphereGeometry
    private val k = floatArrayOf(500f, 500f, 320f, 240f)

    // ---- helpers ------------------------------------------------------------------------------

    private fun randomUnit(rnd: Random): DoubleArray {
        val v = doubleArrayOf(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian())
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    private fun randomRotation(rnd: Random): DoubleArray =
        g.quaternionToMatrix(doubleArrayOf(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()))

    private fun rotationAbout(axis: DoubleArray, deg: Double): DoubleArray {
        val n = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
        val a = Math.toRadians(deg) / n
        return g.axisAngleToMatrix(doubleArrayOf(axis[0] * a, axis[1] * a, axis[2] * a))
    }

    private fun mul(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    private fun det(m: DoubleArray) =
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    private fun dist(a: DoubleArray, b: DoubleArray) =
        sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

    private fun assertMatEquals(expected: DoubleArray, actual: DoubleArray?, tol: Double) {
        assertNotNull(actual)
        for (i in expected.indices) assertEquals("element $i", expected[i], actual!![i], tol)
    }

    /** Double-precision pixel of world [p] (avoids the Float rounding of [PhotosphereGeometry.project]). */
    private fun projectD(r: DoubleArray, c: DoubleArray, p: DoubleArray, kk: FloatArray): DoubleArray {
        val x = mul(r, doubleArrayOf(p[0] - c[0], p[1] - c[1], p[2] - c[2]))
        return doubleArrayOf(kk[0] * x[0] / x[2] + kk[2], kk[1] * x[1] / x[2] + kk[3])
    }

    private fun degToRad(d: Double) = Math.toRadians(d)

    // ---- camera model -------------------------------------------------------------------------

    @Test
    fun `bearing of principal point is optical axis and round-trips through project`() {
        val b0 = g.bearing(320f, 240f, 500f, 500f, 320f, 240f)
        assertMatEquals(doubleArrayOf(0.0, 0.0, 1.0), b0, 1e-12)

        val r = randomRotation(Random(1))
        val c = doubleArrayOf(0.1, -0.2, 0.3)
        val bCam = g.bearing(100f, 400f, 500f, 500f, 320f, 240f)
        assertEquals(1.0, sqrt(bCam[0] * bCam[0] + bCam[1] * bCam[1] + bCam[2] * bCam[2]), 1e-12)
        val dWorld = g.rayToWorld(r, bCam)
        val p = doubleArrayOf(c[0] + 2.5 * dWorld[0], c[1] + 2.5 * dWorld[1], c[2] + 2.5 * dWorld[2])
        val px = g.project(r, c, p, 500f, 500f, 320f, 240f)!!
        assertEquals(100f, px[0], 1e-3f)
        assertEquals(400f, px[1], 1e-3f)

        val behind = doubleArrayOf(c[0] - dWorld[0], c[1] - dWorld[1], c[2] - dWorld[2])
        assertNull(g.project(r, c, behind, 500f, 500f, 320f, 240f))
    }

    // ---- rotation helpers ---------------------------------------------------------------------

    @Test
    fun `quaternion and matrix helpers round-trip and angle is exact`() {
        val rnd = Random(2)
        repeat(20) {
            val r = randomRotation(rnd)
            assertMatEquals(r, g.quaternionToMatrix(g.matrixToQuaternion(r)), 1e-12)
            assertEquals(1.0, det(r), 1e-12)
        }
        val r = randomRotation(rnd)
        val axis = randomUnit(rnd)
        assertEquals(degToRad(0.01), g.rotationAngleRad(g.multiply3(rotationAbout(axis, 0.01), r), r), 1e-12)
        assertEquals(degToRad(179.0), g.rotationAngleRad(g.multiply3(rotationAbout(axis, 179.0), r), r), 1e-9)
        assertMatEquals(r, g.transpose3(g.transpose3(r)), 0.0)
    }

    // ---- rotationFromBearings -----------------------------------------------------------------

    @Test
    fun `rotationFromBearings recovers random rotations exactly`() {
        val rnd = Random(3)
        repeat(50) {
            val truth = randomRotation(rnd)
            val a = List(20) { randomUnit(rnd) }
            val b = a.map { mul(truth, it) }
            val r = g.rotationFromBearings(a, b)
            assertMatEquals(truth, r, 1e-9)
            assertEquals(1.0, det(r!!), 1e-9)
        }
        // Near-180° rotation and the 2-pair minimum.
        val truth = rotationAbout(doubleArrayOf(0.3, -1.0, 0.2), 179.9)
        val a = listOf(randomUnit(rnd), randomUnit(rnd))
        assertMatEquals(truth, g.rotationFromBearings(a, a.map { mul(truth, it) }), 1e-9)
    }

    @Test
    fun `rotationFromBearings honours weights`() {
        val rnd = Random(4)
        val truth = randomRotation(rnd)
        val a = List(15) { randomUnit(rnd) }
        val b = a.map { mul(truth, it) }.toMutableList()
        val weights = DoubleArray(15) { 0.1 + rnd.nextDouble() * 5 }
        assertMatEquals(truth, g.rotationFromBearings(a, b, weights), 1e-9)

        // Corrupt three pairs and weight them out: still exact.
        for (i in 0..2) {
            b[i] = randomUnit(rnd)
            weights[i] = 0.0
        }
        assertMatEquals(truth, g.rotationFromBearings(a, b, weights), 1e-9)
        // Unweighted, the corruption shows.
        assertTrue(g.rotationAngleRad(truth, g.rotationFromBearings(a, b)!!) > 1e-3)
    }

    @Test
    fun `rotationFromBearings rejects degenerate input`() {
        val rnd = Random(5)
        val truth = randomRotation(rnd)
        val v = randomUnit(rnd)
        assertNull(g.rotationFromBearings(listOf(v), listOf(mul(truth, v))))
        // All a parallel / antiparallel: roll about the axis is free.
        val a = listOf(v, v, doubleArrayOf(-v[0], -v[1], -v[2]), v)
        assertNull(g.rotationFromBearings(a, a.map { mul(truth, it) }))
        // Size mismatch, bad weights.
        val a2 = List(4) { randomUnit(rnd) }
        assertNull(g.rotationFromBearings(a2, a2.take(3)))
        assertNull(g.rotationFromBearings(a2, a2, doubleArrayOf(1.0, 1.0)))
        assertNull(g.rotationFromBearings(a2, a2, doubleArrayOf(1.0, -1.0, 1.0, 1.0)))
        // Only one non-zero-weight pair left.
        assertNull(g.rotationFromBearings(a2, a2, doubleArrayOf(1.0, 0.0, 0.0, 0.0)))
    }

    // ---- ransacRotation -----------------------------------------------------------------------

    @Test
    fun `ransacRotation survives 30 percent outliers and 0_1 degree noise`() {
        val rnd = Random(6)
        val truth = randomRotation(rnd)
        val n = 100
        // Bearings within a ~60° camera field of view, as from a real frame.
        val a = List(n) {
            val v = doubleArrayOf(rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5, 1.0)
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + 1.0)
            doubleArrayOf(v[0] / l, v[1] / l, 1.0 / l)
        }
        val outliers = (0 until n).shuffled(kotlin.random.Random(6)).take(30).toSet()
        val b = List(n) { i ->
            if (i in outliers) {
                randomUnit(rnd)
            } else {
                val noise = rotationAbout(randomUnit(rnd), 0.1 * abs(rnd.nextGaussian()))
                mul(noise, mul(truth, a[i]))
            }
        }
        val fit = g.ransacRotation(a, b)
        assertNotNull(fit)
        fit!!
        assertTrue(
            "rotation error ${Math.toDegrees(g.rotationAngleRad(truth, fit.rotation))}°",
            g.rotationAngleRad(truth, fit.rotation) < degToRad(0.05),
        )
        assertTrue(fit.inliers.none { it in outliers })
        assertTrue("inliers ${fit.inliers.size}", fit.inliers.size >= 65)
        assertTrue(fit.rmsAngleRad < degToRad(0.3))
        assertEquals(1.0, det(fit.rotation), 1e-9)
    }

    @Test
    fun `ransacRotation returns null with too few inliers`() {
        val rnd = Random(8)
        val truth = randomRotation(rnd)
        val a = List(10) { randomUnit(rnd) }
        assertNull(g.ransacRotation(a, a.map { mul(truth, it) }, minInliers = 12))
        // Pure garbage.
        val a2 = List(60) { randomUnit(rnd) }
        assertNull(g.ransacRotation(a2, List(60) { randomUnit(rnd) }))
        // Equal content: equals/hashCode by value.
        val fit = g.ransacRotation(a, a.map { mul(truth, it) }, minInliers = 5)!!
        val copy = fit.copy(rotation = fit.rotation.copyOf(), inliers = fit.inliers.copyOf())
        assertEquals(fit, copy)
        assertEquals(fit.hashCode(), copy.hashCode())
    }

    // ---- triangulation ------------------------------------------------------------------------

    @Test
    fun `triangulateMidpoint recovers a point from two stations`() {
        val rnd = Random(9)
        val c1 = doubleArrayOf(0.0, 0.0, 0.0)
        val c2 = doubleArrayOf(0.5, 0.0, 0.0)
        val r1 = rotationAbout(randomUnit(rnd), 10.0)
        val r2 = rotationAbout(randomUnit(rnd), 10.0)
        repeat(20) {
            val p = doubleArrayOf(rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1, 2.5 + rnd.nextDouble())
            val b1 = mul(r1, doubleArrayOf(p[0] - c1[0], p[1] - c1[1], p[2] - c1[2]))
            val b2 = mul(r2, doubleArrayOf(p[0] - c2[0], p[1] - c2[1], p[2] - c2[2]))
            val n1 = sqrt(b1[0] * b1[0] + b1[1] * b1[1] + b1[2] * b1[2])
            val n2 = sqrt(b2[0] * b2[0] + b2[1] * b2[1] + b2[2] * b2[2])
            val d1 = g.rayToWorld(r1, DoubleArray(3) { b1[it] / n1 })
            val d2 = g.rayToWorld(r2, DoubleArray(3) { b2[it] / n2 })
            val t = g.triangulateMidpoint(c1, d1, c2, d2)
            assertNotNull(t)
            assertTrue(dist(p, t!!.point) < 1e-9)
            assertTrue(t.gap < 1e-9)
            assertTrue(t.parallaxRad > degToRad(5.0))
        }
    }

    @Test
    fun `triangulateMidpoint reports the gap of skew rays`() {
        val t = g.triangulateMidpoint(
            doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(1.0, 0.1, 1.0), doubleArrayOf(-1.0, 0.0, 0.0),
        )!!
        assertEquals(0.1, t.gap, 1e-12)
        assertMatEquals(doubleArrayOf(0.0, 0.05, 1.0), t.point, 1e-12)
        assertEquals(Math.PI / 2, t.parallaxRad, 1e-12)
    }

    @Test
    fun `triangulateMidpoint rejects parallel and behind-camera rays`() {
        val c1 = doubleArrayOf(0.0, 0.0, 0.0)
        val c2 = doubleArrayOf(0.5, 0.0, 0.0)
        val d = doubleArrayOf(0.0, 0.0, 1.0)
        assertNull(g.triangulateMidpoint(c1, d, c2, d))
        assertNull(g.triangulateMidpoint(c1, d, c2, doubleArrayOf(1e-6, 0.0, 1.0)))
        // Rays that meet at (0.25, 0, −3): behind both cameras.
        val p = doubleArrayOf(0.25, 0.0, -3.0)
        val d1 = doubleArrayOf(p[0] - c1[0], p[1] - c1[1], p[2] - c1[2])
        val d2 = doubleArrayOf(p[0] - c2[0], p[1] - c2[1], p[2] - c2[2])
        assertNotNull(g.triangulateMidpoint(c1, d1, c2, d2))
        assertNull(g.triangulateMidpoint(c1, DoubleArray(3) { -d1[it] }, c2, DoubleArray(3) { -d2[it] }))
        // Behind only one of them.
        assertNull(g.triangulateMidpoint(c1, d1, c2, DoubleArray(3) { -d2[it] }))
    }

    // ---- planes -------------------------------------------------------------------------------

    private fun wallPoints(rnd: Random, plane: Plane, count: Int, noise: Double): List<DoubleArray> {
        // Orthonormal basis of the plane.
        val n = plane.normal
        val helper = if (abs(n[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        val u = normalized(cross(n, helper))
        val v = cross(n, u)
        return List(count) {
            val s = rnd.nextDouble() * 4 - 2
            val t = rnd.nextDouble() * 3 - 1.5
            val e = rnd.nextGaussian() * noise
            DoubleArray(3) { plane.offset * n[it] + s * u[it] + t * v[it] + e * n[it] }
        }
    }

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun normalized(a: DoubleArray): DoubleArray {
        val n = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        return DoubleArray(3) { a[it] / n }
    }

    private fun dotP(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    @Test
    fun `fitPlane recovers a wall plane exactly and with noise`() {
        val rnd = Random(10)
        val truth = Plane(normalized(doubleArrayOf(0.2, -0.1, 1.0)), 3.0)
        val exact = g.fitPlane(wallPoints(rnd, truth, 30, 0.0))!!
        assertEquals(1.0, dotP(truth.normal, exact.normal), 1e-12)
        assertEquals(truth.offset, exact.offset, 1e-9)

        val noisy = g.fitPlane(wallPoints(rnd, truth, 200, 0.005))!!
        assertTrue(dotP(truth.normal, noisy.normal) > kotlin.math.cos(degToRad(0.5)))
        assertEquals(truth.offset, noisy.offset, 0.005)
        assertTrue(noisy.offset >= 0)
    }

    @Test
    fun `fitPlane canonicalises sign and rejects degenerate sets`() {
        val rnd = Random(12)
        val truth = Plane(doubleArrayOf(0.0, 0.0, -1.0), 2.0) // the plane z = −2
        val fit = g.fitPlane(wallPoints(rnd, truth, 10, 0.0))!!
        assertMatEquals(doubleArrayOf(0.0, 0.0, -1.0), fit.normal, 1e-12)
        assertEquals(2.0, fit.offset, 1e-12)

        assertNull(g.fitPlane(listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0))))
        val line = List(10) { doubleArrayOf(it.toDouble(), 2.0 * it, -it.toDouble()) }
        assertNull(g.fitPlane(line))
    }

    @Test
    fun `ransacPlane recovers a wall with 25 percent outliers`() {
        val rnd = Random(13)
        val truth = Plane(normalized(doubleArrayOf(-0.3, 0.1, 1.0)), 2.8)
        val inliers = wallPoints(rnd, truth, 60, 0.003)
        val outliers = List(20) {
            doubleArrayOf(rnd.nextDouble() * 4 - 2, rnd.nextDouble() * 4 - 2, 0.5 + rnd.nextDouble() * 1.8)
        }
        val all = inliers + outliers
        val (plane, idx) = g.ransacPlane(all, inlierDistance = 0.015)!!
        assertTrue(dotP(truth.normal, plane.normal) > kotlin.math.cos(degToRad(0.5)))
        assertEquals(truth.offset, plane.offset, 0.005)
        assertTrue("inliers ${idx.size}", idx.size >= 58)
        assertTrue(idx.all { it < 60 || abs(truth.distance(all[it])) <= 0.03 })

        assertNull(g.ransacPlane(inliers.take(5), 0.015))
    }

    // ---- homography ---------------------------------------------------------------------------

    @Test
    fun `planeHomography maps plane points from camera A to camera B`() {
        val rnd = Random(14)
        val plane = Plane(normalized(doubleArrayOf(0.15, -0.05, 1.0)), 3.0)
        val rA = rotationAbout(randomUnit(rnd), 8.0)
        val cA = doubleArrayOf(0.0, 0.0, 0.0)
        val rB = rotationAbout(randomUnit(rnd), 15.0)
        val cB = doubleArrayOf(0.6, -0.2, 0.4)
        val kB = floatArrayOf(620f, 610f, 330f, 250f)
        val h = g.planeHomography(rA, cA, rB, cB, plane, k, kB)!!
        assertEquals(1.0, h[8], 1e-12)
        for (p in wallPoints(rnd, plane, 40, 0.0)) {
            val pa = projectD(rA, cA, p, k)
            val pb = projectD(rB, cB, p, kB)
            val mapped = g.applyHomography(h, pa[0], pa[1])!!
            assertEquals(pb[0], mapped[0], 1e-6)
            assertEquals(pb[1], mapped[1], 1e-6)
        }
        // Same answer with the plane written the other way round.
        val flipped = Plane(DoubleArray(3) { -plane.normal[it] }, -plane.offset)
        assertMatEquals(h, g.planeHomography(rA, cA, rB, cB, flipped, k, kB), 1e-9)
    }

    @Test
    fun `planeHomography with a shared centre is the pure rotation homography`() {
        val rnd = Random(15)
        val rA = randomRotation(rnd)
        val rB = g.multiply3(rotationAbout(randomUnit(rnd), 20.0), rA)
        val c = doubleArrayOf(0.3, 1.2, -0.4)
        val kB = floatArrayOf(450f, 455f, 300f, 230f)
        val rAB = g.multiply3(rB, g.transpose3(rA))
        val kBm = doubleArrayOf(450.0, 0.0, 300.0, 0.0, 455.0, 230.0, 0.0, 0.0, 1.0)
        val kAinv = doubleArrayOf(1 / 500.0, 0.0, -320 / 500.0, 0.0, 1 / 500.0, -240 / 500.0, 0.0, 0.0, 1.0)
        val expected = g.multiply3(g.multiply3(kBm, rAB), kAinv)
        val s = expected[8]
        for (i in 0..8) expected[i] /= s
        val p1 = Plane(normalized(doubleArrayOf(0.1, 0.2, 1.0)), 4.0)
        val p2 = Plane(normalized(doubleArrayOf(-0.7, 0.0, 0.3)), -1.0)
        assertMatEquals(expected, g.planeHomography(rA, c, rB, c, p1, k, kB), 1e-9)
        assertMatEquals(expected, g.planeHomography(rA, c, rB, c, p2, k, kB), 1e-9)
    }

    @Test
    fun `planeHomography rejects a plane through camera A`() {
        val plane = Plane(doubleArrayOf(0.0, 0.0, 1.0), 1.0)
        val r = randomRotation(Random(16))
        assertNull(g.planeHomography(r, doubleArrayOf(5.0, -2.0, 1.0), r, doubleArrayOf(0.0, 0.0, 0.0), plane, k, k))
        assertNull(g.applyHomography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0), 0.0, 5.0))
    }

    // ---- PnP ----------------------------------------------------------------------------------

    private class Scene(
        val rotation: DoubleArray,
        val centre: DoubleArray,
        val points: List<DoubleArray>,
        val pixels: List<FloatArray>,
        val outliers: Set<Int>,
    )

    private fun scene(rnd: Random, count: Int, noisePx: Double, outlierFraction: Double): Scene {
        val r = rotationAbout(randomUnit(rnd), 25.0)
        val c = doubleArrayOf(0.4, -0.3, 0.2)
        val rt = g.transpose3(r)
        val points = List(count) {
            // A ~3 m scene with depth relief (a wall with furniture), filling the frame.
            val z = 2.0 + 2.0 * rnd.nextDouble()
            val xc = doubleArrayOf((rnd.nextDouble() - 0.5) * 1.2 * z, (rnd.nextDouble() - 0.5) * 0.9 * z, z)
            val w = mul(rt, xc)
            doubleArrayOf(w[0] + c[0], w[1] + c[1], w[2] + c[2])
        }
        val nOut = (count * outlierFraction).toInt()
        val outliers = (0 until count).shuffled(kotlin.random.Random(rnd.nextLong())).take(nOut).toSet()
        val pixels = List(count) { i ->
            if (i in outliers) {
                floatArrayOf((rnd.nextDouble() * 640).toFloat(), (rnd.nextDouble() * 480).toFloat())
            } else {
                val p = projectD(r, c, points[i], k)
                floatArrayOf(
                    (p[0] + rnd.nextGaussian() * noisePx).toFloat(),
                    (p[1] + rnd.nextGaussian() * noisePx).toFloat(),
                )
            }
        }
        return Scene(r, c, points, pixels, outliers)
    }

    private fun perturbed(rnd: Random, s: Scene, deg: Double, metres: Double): Pair<DoubleArray, DoubleArray> {
        val r = g.multiply3(rotationAbout(randomUnit(rnd), deg), s.rotation)
        val d = randomUnit(rnd)
        return r to DoubleArray(3) { s.centre[it] + metres * d[it] }
    }

    @Test
    fun `refinePose converges exactly on noise-free data from a perturbed prior`() {
        val rnd = Random(17)
        val s = scene(rnd, 40, 0.0, 0.0)
        val (r0, c0) = perturbed(rnd, s, 5.0, 0.2)
        val fit = g.refinePose(s.points, s.pixels, k, r0, c0, iterations = 20)!!
        assertTrue(g.rotationAngleRad(s.rotation, fit.rotation) < 1e-6)
        assertTrue(dist(s.centre, fit.centre) < 1e-5)
        assertEquals(40, fit.inliers.size)
        assertTrue("rms ${fit.rmsPx}", fit.rmsPx < 1e-3) // Float pixels
        assertEquals(1.0, det(fit.rotation), 1e-9)
    }

    @Test
    fun `ransacPose recovers the pose with noise and 20 percent outliers`() {
        val rnd = Random(18)
        repeat(10) { trial ->
            val s = scene(rnd, 40, 1.0, 0.2)
            val (r0, c0) = perturbed(rnd, s, 5.0, 0.2)
            val fit = g.ransacPose(s.points, s.pixels, k, r0, c0)
            assertNotNull("trial $trial", fit)
            fit!!
            val rotErr = Math.toDegrees(g.rotationAngleRad(s.rotation, fit.rotation))
            val cErr = dist(s.centre, fit.centre)
            // Oracle: least squares on exactly the clean matches, from the true pose. 32 points at
            // 1 px leave ~0.1–0.3° of rotation/translation ambiguity; the robust solve must match it.
            val clean = (0 until 40).filter { it !in s.outliers }
            val oracle = g.refinePose(clean.map { s.points[it] }, clean.map { s.pixels[it] }, k, s.rotation, s.centre, 30)!!
            val oracleRotErr = Math.toDegrees(g.rotationAngleRad(s.rotation, oracle.rotation))
            assertTrue("trial $trial: $rotErr° vs oracle $oracleRotErr°", rotErr < oracleRotErr + 0.05)
            assertTrue("trial $trial rotation error $rotErr°", rotErr < 0.3)
            assertTrue("trial $trial centre error $cErr", cErr < 0.02)
            assertTrue("trial $trial inliers ${fit.inliers.size}", fit.inliers.size >= 28)
            assertTrue(fit.rmsPx < 2.5)
            // An outlier may only survive if its random pixel happens to land on its true projection.
            for (i in fit.inliers) {
                if (i in s.outliers) {
                    val p = projectD(s.rotation, s.centre, s.points[i], k)
                    val e = sqrt(
                        (p[0] - s.pixels[i][0]) * (p[0] - s.pixels[i][0]) +
                            (p[1] - s.pixels[i][1]) * (p[1] - s.pixels[i][1]),
                    )
                    assertTrue(e < 5.0)
                }
            }
        }
    }

    @Test
    fun `ransacPose and refinePose return null when support is missing`() {
        val rnd = Random(19)
        val s = scene(rnd, 40, 1.0, 0.0)
        // Every pixel garbage.
        val garbage = List(40) { floatArrayOf((rnd.nextDouble() * 640).toFloat(), (rnd.nextDouble() * 480).toFloat()) }
        assertNull(g.ransacPose(s.points, garbage, k, s.rotation, s.centre))
        // Too few points.
        assertNull(g.ransacPose(s.points.take(8), s.pixels.take(8), k, s.rotation, s.centre))
        assertNull(g.refinePose(s.points.take(2), s.pixels.take(2), k, s.rotation, s.centre))
        // Points behind the prior camera are not usable.
        val flipped = g.multiply3(rotationAbout(doubleArrayOf(0.0, 1.0, 0.0), 180.0), s.rotation)
        assertNull(g.refinePose(s.points, s.pixels, k, flipped, s.centre))
        assertFalse(s.points.isEmpty())
    }
}

package com.hereliesaz.sphereslam.reloc

import com.hereliesaz.sphereslam.SphereSlamPoseMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.hypot

/**
 * [ObliqueRectifier] on synthetic geometry: a page plane (z = 0, millimetres) seen from grazing
 * angles by a 320×240 matcher camera.
 */
class ObliqueRectifierTest {

    private val k = floatArrayOf(250f, 250f, 160f, 120f)
    private val w = 320
    private val h = 240

    private fun pose(tiltDeg: Double, target: DoubleArray, distanceMm: Double, rollDeg: Double = 4.0) =
        ObliqueGeometry.pose(tiltDeg, target, distanceMm, rollDeg)

    private fun project(r: DoubleArray, t: DoubleArray, x: Double, y: Double) = ObliqueGeometry.project(k, r, t, x, y)

    private fun apply(hm: DoubleArray, u: Double, v: Double): DoubleArray {
        val ww = hm[6] * u + hm[7] * v + hm[8]
        return doubleArrayOf((hm[0] * u + hm[1] * v + hm[2]) / ww, (hm[3] * u + hm[4] * v + hm[5]) / ww)
    }

    private val target = doubleArrayOf(400.0, 300.0, 0.0)

    @Test
    fun `pagePose inverts the session's page-to-canonical conversion`() {
        val (r, t) = pose(50.0, target, 900.0)
        val p3x4 = ObliqueGeometry.pose3x4(r, t)
        val canonicalFromPage = SphereSlamPoseMath.identity4().also { it[12] = 0.7f; it[13] = -0.2f }
        val view = SphereSlamPoseMath.pageViewToCanonicalView(
            SphereSlamPoseMath.pageToOpenGlViewMeters(p3x4, 420f, 297f), canonicalFromPage,
        )
        val (r2, t2) = ObliqueRectifier.pagePose(view, canonicalFromPage, 420f, 297f)!!
        for (i in 0..8) assertEquals(r[i], r2[i], 1e-5)
        for (i in 0..2) assertEquals(t[i], t2[i], 1e-2)
    }

    @Test
    fun `a near-frontal view is not rectified`() {
        val (r, t) = pose(15.0, target, 900.0)
        assertNull(ObliqueRectifier.plan(r, t, k, 30f, target))
    }

    @Test
    fun `the virtual view is frontal and maps exactly back to the real image`() {
        val (r, t) = pose(70.0, target, 1200.0)
        val plan = ObliqueRectifier.plan(r, t, k, 30f, target)!!
        assertEquals(70.0, plan.obliquityDeg, 0.5)
        val virtualFromReal = ObliqueRectifier.invert3(plan.realFromVirtual)!!
        // A 200 mm square around the target: equal, perpendicular sides in the virtual image.
        val corners = listOf(-100.0 to -100.0, 100.0 to -100.0, 100.0 to 100.0, -100.0 to 100.0).map { (dx, dy) ->
            val real = project(r, t, target[0] + dx, target[1] + dy)
            apply(virtualFromReal, real[0], real[1])
        }
        val sides = (0..3).map { i -> hypot(corners[(i + 1) % 4][0] - corners[i][0], corners[(i + 1) % 4][1] - corners[i][1]) }
        sides.forEach { assertEquals(sides[0], it, sides[0] * 0.01) }
        // The real view's foreshortening: in the real image the same square is far from equal-sided.
        val real = listOf(-100.0 to -100.0, 100.0 to -100.0, 100.0 to 100.0, -100.0 to 100.0).map { (dx, dy) -> project(r, t, target[0] + dx, target[1] + dy) }
        val realSides = (0..3).map { i -> hypot(real[(i + 1) % 4][0] - real[i][0], real[(i + 1) % 4][1] - real[i][1]) }
        assertTrue(realSides.toString(), realSides.max() / realSides.min() > 2.0)
    }

    @Test
    fun `correspondences found in the virtual view give the real pose, even from a poor prediction`() {
        val (rTrue, tTrue) = pose(72.0, target, 1100.0)
        // Predicted: 6° less tilt, 80 mm off, different roll — the rectification is built from this.
        val (rPred, tPred) = pose(66.0, doubleArrayOf(target[0] + 80.0, target[1] - 40.0, 0.0), 1000.0, rollDeg = -3.0)
        val plan = ObliqueRectifier.plan(rPred, tPred, k, 30f, target)!!
        val virtualFromReal = ObliqueRectifier.invert3(plan.realFromVirtual)!!
        // What KPM would report on the virtual image: page points at their virtual pixels.
        val corr = ArrayList<Float>()
        for (gx in -6..6) for (gy in -6..6) {
            val px = target[0] + gx * 40.0
            val py = target[1] + gy * 40.0
            val real = project(rTrue, tTrue, px, py)
            if (real[0] < 0 || real[0] >= w || real[1] < 0 || real[1] >= h) continue
            val v = apply(virtualFromReal, real[0], real[1])
            corr += v[0].toFloat(); corr += v[1].toFloat(); corr += px.toFloat(); corr += py.toFloat()
        }
        assertTrue("visible ${corr.size / 4}", corr.size / 4 >= 20)
        val solved = ObliqueRectifier.solve(plan, corr.toFloatArray(), k, plan.priorRotation, plan.priorCentre)
        assertNotNull(solved)
        val p = solved!!.cameraFromPage3x4
        for (row in 0..2) {
            for (col in 0..2) assertEquals(rTrue[row * 3 + col], p[row * 4 + col].toDouble(), 1e-3)
            assertEquals(tTrue[row], p[row * 4 + 3].toDouble(), 1.0)
        }
        assertTrue(solved.meanSquaredErrorPx < 0.01f)
        // Its correspondences are in real pixels, ready for flow seeding.
        val real0 = project(rTrue, tTrue, solved.correspondences[2].toDouble(), solved.correspondences[3].toDouble())
        assertEquals(real0[0], solved.correspondences[0].toDouble(), 0.05)
    }

    @Test
    fun `warp moves a mark from its real pixel to its virtual pixel`() {
        val (r, t) = pose(65.0, target, 1000.0)
        val plan = ObliqueRectifier.plan(r, t, k, 30f, target)!!
        val markReal = project(r, t, target[0] + 60.0, target[1] - 30.0)
        val src = ByteArray(w * h)
        for (y in -2..2) for (x in -2..2) {
            src[(markReal[1].toInt() + y) * w + markReal[0].toInt() + x] = 255.toByte()
        }
        val out = ObliqueRectifier.warp(src, w, h, plan, ByteBuffer.allocateDirect(w * h))
        var best = -1
        var bestAt = 0
        for (i in 0 until w * h) {
            val v = out.get(i).toInt() and 0xff
            if (v > best) { best = v; bestAt = i }
        }
        val expected = apply(ObliqueRectifier.invert3(plan.realFromVirtual)!!, markReal[0], markReal[1])
        assertTrue("peak $best", best > 128)
        assertEquals(expected[0], (bestAt % w).toDouble(), 6.0)
        assertEquals(expected[1], (bestAt / w).toDouble(), 6.0)
    }
}

package com.hereliesaz.sphereslam.reloc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalSphereSlamRelocApi::class)
class ConstellationTest {

    private val w = 640
    private val h = 480

    // Five X's and an O, bright on black, at irregular places.
    private val centres = listOf(200f to 140f, 330f to 170f, 250f to 260f, 420f to 300f, 180f to 340f)
    private val o = 380f to 110f

    private fun paper(): ByteArray {
        val img = ByteArray(w * h) { 12 }
        fun dot(x: Int, y: Int) { if (x in 0 until w && y in 0 until h) img[y * w + x] = 240.toByte() }
        for ((cx, cy) in centres) for (t in -12..12) for (k in -1..1) {
            dot(cx.toInt() + t, cy.toInt() + t + k)
            dot(cx.toInt() + t, cy.toInt() - t + k)
        }
        for (a in 0 until 360) for (r in 9..11) {
            val rad = Math.toRadians(a.toDouble())
            dot((o.first + r * cos(rad)).toInt(), (o.second + r * sin(rad)).toInt())
        }
        return img
    }

    @Test
    fun findsEveryMarkOfASparseTarget() {
        val marks = Constellation.extractMarks(paper(), w, h)
        assertEquals(6, marks.size)
        for ((cx, cy) in centres + o) {
            assertTrue("no mark near $cx,$cy", marks.any { kotlin.math.hypot(it.x - cx, it.y - cy) < 3f })
        }
    }

    @Test
    fun aBusySurfaceIsLeftToKpm() {
        val rnd = java.util.Random(1)
        val img = ByteArray(w * h) { if (rnd.nextInt(20) == 0) 250.toByte() else 10 }
        assertTrue(Constellation.extractMarks(img, w, h).isEmpty())
    }

    @Test
    fun matchesTheArrangementOfLookalikesUnderTurnScaleAndShift() {
        val ref = (centres + o).map { floatArrayOf(it.first, it.second) }
        val a = Math.toRadians(25.0)
        val s = 0.7
        // The frame: turned, scaled, shifted, with one mark missing and two strays.
        val frame = ref.drop(1).map { p ->
            floatArrayOf(
                (s * (cos(a) * p[0] - sin(a) * p[1]) + 90).toFloat(),
                (s * (sin(a) * p[0] + cos(a) * p[1]) + 40).toFloat(),
            )
        }.shuffled(java.util.Random(3)) + listOf(floatArrayOf(20f, 20f), floatArrayOf(600f, 450f))
        val pairing = assertNotNull(Constellation.match(ref, frame)).let { Constellation.match(ref, frame)!! }
        assertEquals(5, pairing.reference.size)
        for (k in pairing.reference.indices) {
            val p = ref[pairing.reference[k]]
            val q = frame[pairing.frame[k]]
            val ex = s * (cos(a) * p[0] - sin(a) * p[1]) + 90
            val ey = s * (sin(a) * p[0] + cos(a) * p[1]) + 40
            assertEquals(ex, q[0].toDouble(), 0.01)
            assertEquals(ey, q[1].toDouble(), 0.01)
        }
    }

    @Test
    fun aSymmetricArrangementIsAmbiguous() {
        val square = listOf(floatArrayOf(0f, 0f), floatArrayOf(100f, 0f), floatArrayOf(100f, 100f), floatArrayOf(0f, 100f))
        assertNull(Constellation.match(square, square.map { floatArrayOf(it[0] + 5f, it[1] + 5f) }))
    }

    @Test
    fun recoversThePoseOfATiltedPage() {
        val k = floatArrayOf(500f, 500f, 320f, 240f)
        // Camera 300 mm from the page, turned 20° about the page's vertical axis.
        val b = Math.toRadians(20.0)
        val r = doubleArrayOf(cos(b), 0.0, sin(b), 0.0, -1.0, 0.0, sin(b), 0.0, -cos(b))
        val t = doubleArrayOf(-40.0, 30.0, 300.0)
        val page = listOf(floatArrayOf(0f, 0f), floatArrayOf(80f, 10f), floatArrayOf(30f, 60f), floatArrayOf(90f, 70f), floatArrayOf(10f, 90f))
        val image = page.map { p ->
            val x = r[0] * p[0] + r[1] * p[1] + t[0]
            val y = r[3] * p[0] + r[4] * p[1] + t[1]
            val z = r[6] * p[0] + r[7] * p[1] + t[2]
            floatArrayOf((k[0] * x / z + k[2]).toFloat(), (k[1] * y / z + k[3]).toFloat())
        }
        val pose = assertNotNull(Constellation.pose(page, image, k)).let { Constellation.pose(page, image, k)!! }
        assertTrue(pose.meanSquaredErrorPx < 1e-3f)
        for (row in 0..2) assertEquals(t[row], pose.cameraFromPage3x4[row * 4 + 3].toDouble(), 0.1)
        for (row in 0..2) for (col in 0..2) {
            assertEquals(r[row * 3 + col], pose.cameraFromPage3x4[row * 4 + col].toDouble(), 1e-3)
        }
    }
}

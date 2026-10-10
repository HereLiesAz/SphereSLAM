package com.hereliesaz.sphereslam

import com.hereliesaz.sphereslam.math.RigidMath

/**
 * Coordinate and scale conversions for calibrated artoolkitX KPM poses.
 *
 * KPM stores planar reference coordinates in millimetres:
 *
 * `x_mm = x_px / dpi * 25.4`
 *
 * and flips image Y so the reference plane is +X right / +Y up. Its 3x4 match pose is therefore a
 * camera-from-page transform in that millimetre coordinate system.
 *
 * The renderer wants a right-handed OpenGL world-to-view matrix (+X right, +Y up, -Z forward).
 * [pageToOpenGlViewMeters] reproduces artoolkitX's own `arglCameraViewRHf()` conversion and converts
 * translation from KPM millimetres to metres.
 */
object SphereSlamPoseMath {
    const val MILLIMETERS_TO_METERS: Float = 0.001f
    private const val MILLIMETERS_PER_INCH: Float = 25.4f

    /**
     * Choose KPM DPI so [referenceWidthPixels] spans exactly [referenceWidthMeters] in KPM space.
     *
     * This can be a real measured wall width, which makes the pose physically metric, or a
     * normalized logical width (for example 1 metre) when only stable visual registration is needed.
     * In the normalized case the word "metre" is merely the renderer's shared unit; distance readouts
     * must not be presented as physical measurements.
     */
    fun dpiForReferenceWidth(
        referenceWidthPixels: Int,
        referenceWidthMeters: Float,
    ): Float {
        require(referenceWidthPixels > 0) { "reference width must be positive" }
        require(referenceWidthMeters.isFinite() && referenceWidthMeters > 0f) {
            "reference width in metres must be finite and positive"
        }
        return referenceWidthPixels.toFloat() * (MILLIMETERS_PER_INCH / 1000f) /
            referenceWidthMeters
    }

    data class PageGeometry(
        val widthMeters: Float,
        val heightMeters: Float,
        val centerXmm: Float,
        val centerYmm: Float,
        val referenceDpi: Float,
    ) {
        init {
            require(widthMeters > 0f && heightMeters > 0f)
            require(centerXmm.isFinite() && centerYmm.isFinite())
            require(referenceDpi > 0f)
        }
    }

    /** Geometry KPM will assign to a reference image for the supplied DPI. */
    fun pageGeometry(
        widthPixels: Int,
        heightPixels: Int,
        referenceDpi: Float,
    ): PageGeometry {
        require(widthPixels > 0 && heightPixels > 0)
        require(referenceDpi.isFinite() && referenceDpi > 0f)
        val widthMm = widthPixels.toFloat() / referenceDpi * MILLIMETERS_PER_INCH
        val heightMm = heightPixels.toFloat() / referenceDpi * MILLIMETERS_PER_INCH
        return PageGeometry(
            widthMeters = widthMm * MILLIMETERS_TO_METERS,
            heightMeters = heightMm * MILLIMETERS_TO_METERS,
            centerXmm = widthMm * 0.5f,
            centerYmm = heightMm * 0.5f,
            referenceDpi = referenceDpi,
        )
    }

    /**
     * Convert artoolkitX's row-major camera-from-page 3x4 transform to a column-major OpenGL
     * world-to-view matrix in metres.
     *
     * KPM's page origin is at the lower-left of the reference image. Host renderers typically draw
     * wall artwork around its local origin, so [pageCenterXmm]/[pageCenterYmm] shift that lower-left KPM
     * frame to a centered wall frame before the handedness conversion.
     *
     * The sign/transpose layout below intentionally mirrors artoolkitX
     * `arglCameraViewRHf(para, modelview, scale)`:
     * - first camera row unchanged;
     * - second and third camera rows negated;
     * - 3x4 row-major input written as a column-major 4x4 matrix;
     * - translation scaled from millimetres to metres.
     */
    fun pageToOpenGlViewMeters(
        cameraFromPage3x4: FloatArray,
        pageCenterXmm: Float = 0f,
        pageCenterYmm: Float = 0f,
    ): FloatArray {
        require(cameraFromPage3x4.size == 12) { "KPM pose must contain 12 floats" }
        require(cameraFromPage3x4.all { it.isFinite() }) { "KPM pose must be finite" }
        require(pageCenterXmm.isFinite() && pageCenterYmm.isFinite())

        val p = cameraFromPage3x4

        // Re-express the same rigid transform around a centered page origin:
        // camera = R * (localCentered + pageCenter) + t.
        val tx = p[3] + p[0] * pageCenterXmm + p[1] * pageCenterYmm
        val ty = p[7] + p[4] * pageCenterXmm + p[5] * pageCenterYmm
        val tz = p[11] + p[8] * pageCenterXmm + p[9] * pageCenterYmm

        val out = FloatArray(16)
        // Row 0.
        out[0] = p[0]
        out[4] = p[1]
        out[8] = p[2]
        out[12] = tx * MILLIMETERS_TO_METERS

        // Rows 1 and 2 are flipped exactly as arglCameraViewRHf does when moving from the
        // camera/image convention to a right-handed OpenGL eye frame.
        out[1] = -p[4]
        out[5] = -p[5]
        out[9] = -p[6]
        out[13] = -ty * MILLIMETERS_TO_METERS

        out[2] = -p[8]
        out[6] = -p[9]
        out[10] = -p[10]
        out[14] = -tz * MILLIMETERS_TO_METERS

        out[15] = 1f
        return out
    }

    /**
     * Rebase a centered KPM page view into the immutable canonical wall/atlas frame.
     *
     * [canonicalFromPage] maps coordinates from the matched page's centered wall frame into the
     * canonical wall frame. A renderer view needs camera-from-canonical, therefore:
     *
     * cameraFromCanonical = cameraFromPage * inverse(canonicalFromPage)
     *
     * Both matrices are column-major rigid 4x4 transforms in the same right-handed wall convention.
     */
    fun pageViewToCanonicalView(
        cameraFromPage: FloatArray,
        canonicalFromPage: FloatArray,
    ): FloatArray {
        requireRigid4(cameraFromPage, "cameraFromPage")
        requireRigid4(canonicalFromPage, "canonicalFromPage")
        return multiply4(cameraFromPage, invertRigid4(canonicalFromPage))
    }

    fun identity4(): FloatArray = RigidMath.identity()

    /**
     * Require a finite column-major 4x4 with an affine bottom row. With [requireOrthonormal], also
     * require the upper 3x3 to be a proper rotation (orthonormal columns, determinant +1) within
     * [ROTATION_TOLERANCE], i.e. a genuinely rigid transform. Throws IllegalArgumentException.
     */
    internal fun requireRigid4(matrix: FloatArray, name: String, requireOrthonormal: Boolean = false) {
        require(matrix.size == 16) { "$name must contain 16 floats" }
        require(matrix.all { it.isFinite() }) { "$name must be finite" }
        require(kotlin.math.abs(matrix[3]) < 1e-5f) { "$name must have an affine bottom row" }
        require(kotlin.math.abs(matrix[7]) < 1e-5f) { "$name must have an affine bottom row" }
        require(kotlin.math.abs(matrix[11]) < 1e-5f) { "$name must have an affine bottom row" }
        require(kotlin.math.abs(matrix[15] - 1f) < 1e-5f) { "$name must have an affine bottom row" }
        if (!requireOrthonormal) return
        for (a in 0..2) {
            for (b in a..2) {
                var dot = 0f
                for (k in 0..2) dot += matrix[a * 4 + k] * matrix[b * 4 + k]
                val expected = if (a == b) 1f else 0f
                require(kotlin.math.abs(dot - expected) < ROTATION_TOLERANCE) {
                    "$name rotation must be orthonormal (no scale or shear)"
                }
            }
        }
        val det =
            matrix[0] * (matrix[5] * matrix[10] - matrix[9] * matrix[6]) -
                matrix[4] * (matrix[1] * matrix[10] - matrix[9] * matrix[2]) +
                matrix[8] * (matrix[1] * matrix[6] - matrix[5] * matrix[2])
        require(kotlin.math.abs(det - 1f) < ROTATION_TOLERANCE) {
            "$name rotation must be proper (determinant +1, no reflection)"
        }
    }

    private const val ROTATION_TOLERANCE = 1e-3f

    private fun invertRigid4(m: FloatArray): FloatArray = RigidMath.rigidInverse(m)

    private fun multiply4(a: FloatArray, b: FloatArray): FloatArray = RigidMath.multiply(a, b)
}

package com.hereliesaz.sphereslam.camera

/**
 * OpenGL projection matrix from pinhole intrinsics — what ARCore's `Camera.getProjectionMatrix()`
 * supplies and CameraX does not — so a CameraX-backed pose renders through the same path.
 *
 * A pinhole camera in OpenCV convention (+X right, +Y down, +Z forward) projects `(Xc, Yc, Zc)` to
 * `u = fx·Xc/Zc + cx`, `v = fy·Yc/Zc + cy`. SphereSLAM poses are in the OpenGL eye frame (+X right,
 * +Y up, −Z forward): `Xc = Xg`, `Yc = −Yg`, `Zc = −Zg`. With `x_ndc = 2u/w − 1`,
 * `y_ndc = 1 − 2v/h` and `Wclip = −Zg`:
 * ```
 * Xclip = (2fx/w)·Xg              + (1 − 2cx/w)·Zg
 * Yclip =          (2fy/h)·Yg     + (2cy/h − 1)·Zg
 * Zclip =                         −((f+n)/(f−n))·Zg − (2fn/(f−n))·Wg
 * Wclip =                         −Zg
 * ```
 * so the intrinsics land only in the third column.
 */
object ProjectionMatrix {

    /**
     * @param intrinsics pixel-space intrinsics and the image size they were measured against; only
     *   ratios matter, so tracked-frame intrinsics work for a GL surface of the same aspect ratio.
     * @param near / [far] clip planes in the pose's units, `0 < near < far`.
     * @return a column-major `FloatArray(16)`.
     */
    fun buildFrom(intrinsics: CameraIntrinsics, near: Float = 0.05f, far: Float = 50f): FloatArray {
        require(intrinsics.width > 0 && intrinsics.height > 0) { "intrinsics must carry a positive image size" }
        require(far > near && near > 0f) { "require 0 < near < far" }
        val w = intrinsics.width.toFloat()
        val h = intrinsics.height.toFloat()
        val m = FloatArray(16)
        m[0] = 2f * intrinsics.fx / w
        m[5] = 2f * intrinsics.fy / h
        m[8] = 1f - 2f * intrinsics.cx / w
        m[9] = 2f * intrinsics.cy / h - 1f
        m[10] = -(far + near) / (far - near)
        m[11] = -1f
        m[14] = -2f * far * near / (far - near)
        return m
    }
}

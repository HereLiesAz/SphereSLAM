package com.hereliesaz.sphereslam

/**
 * A view of a registered page that the host anticipates the camera may see: the page's reference
 * image rendered as some camera would see it (from an angle, nearer, turned), handed to SphereSLAM
 * to match a lost camera against. SphereSLAM does not choose or render these; it learns each as
 * another image of its page (features mapped back to the page, so a match solves the page's pose),
 * keeps them out of the live matcher, and searches them while the target is lost.
 *
 * @property id names the view, unique within its page; [com.hereliesaz.sphereslam.SphereSlamTracker]
 *   and the photosphere session select views to search by id.
 * @property luma the rendered view, [width] × [height], tightly packed.
 * @property pageFromView row-major 3×3 homography: view pixel → pixel of the page's reference image
 *   as the host registered it (pixel centres at integer coordinates).
 * @property cameraFromPage row-major 3×3 rotation from the page frame (x right, y up, z out of the
 *   wall) to the camera's (x right, y down, z forward) the view stands for; square-on and upright is
 *   `diag(1, −1, −1)`. Ranks views against a predicted camera.
 * @property scale view pixels per reference pixel at the page's centre (the view's zoom), for the same.
 */
class AnticipatedView(
    val id: String,
    luma: ByteArray,
    val width: Int,
    val height: Int,
    pageFromView: FloatArray,
    cameraFromPage: FloatArray,
    val scale: Float,
) {
    private val lumaValue = luma.copyOf()
    private val pageFromViewValue = pageFromView.copyOf()
    private val cameraFromPageValue = cameraFromPage.copyOf()

    init {
        require(id.isNotEmpty())
        require(width > 0 && height > 0 && lumaValue.size == width * height)
        require(pageFromViewValue.size == 9 && pageFromViewValue.all { it.isFinite() })
        require(cameraFromPageValue.size == 9 && cameraFromPageValue.all { it.isFinite() })
        require(scale.isFinite() && scale > 0f)
    }

    val luma: ByteArray get() = lumaValue.copyOf()
    val pageFromView: FloatArray get() = pageFromViewValue.copyOf()
    val cameraFromPage: FloatArray get() = cameraFromPageValue.copyOf()

    companion object {
        /** Square-on and upright: page x right is image right, page y up is image up. */
        val SQUARE_ON: FloatArray get() = floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f)
    }
}

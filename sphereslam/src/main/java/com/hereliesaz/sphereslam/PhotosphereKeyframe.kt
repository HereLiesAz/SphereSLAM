package com.hereliesaz.sphereslam

/**
 * One accepted photosphere keyframe: a tightly packed luma image, its pinhole intrinsics, and the
 * absolute camera attitude it was taken at.
 *
 * Array-backed values are defensively copied on input and on every read.
 *
 * @property headingDeg camera-axis compass heading, degrees (0 = north, clockwise).
 * @property elevationDeg camera-axis elevation, degrees (above the horizon positive).
 * @property rollDeg image-right axis angle above the horizon, degrees (see
 *   [CameraAttitudeProvider.cameraRollDegrees]); 0 when unknown.
 */
class PhotosphereKeyframe(
    val timestampNs: Long,
    luma: ByteArray,
    val width: Int,
    val height: Int,
    intrinsics: FloatArray,
    val headingDeg: Float,
    val elevationDeg: Float,
    orientationQuaternion: FloatArray? = null,
    cameraFromMap: FloatArray? = null,
    val rollDeg: Float = 0f,
) {
    private val lumaValue = luma.copyOf()
    private val intrinsicsValue = intrinsics.copyOf()
    private val orientationValue = orientationQuaternion?.copyOf()
    private val cameraFromMapValue = cameraFromMap?.copyOf()

    init {
        require(width > 0 && height > 0 && luma.size == width * height) { "luma must be width*height bytes" }
        require(intrinsics.size == 4 && intrinsics.all { it.isFinite() } && intrinsics[0] > 0f && intrinsics[1] > 0f) {
            "intrinsics must be finite [fx, fy, cx, cy] with positive focal lengths"
        }
        require(headingDeg.isFinite() && elevationDeg.isFinite() && rollDeg.isFinite())
        require(orientationQuaternion == null || orientationQuaternion.size == 4)
        require(cameraFromMap == null || (cameraFromMap.size == 16 && cameraFromMap.all { it.isFinite() }))
    }

    /** Luma bytes, row stride == [width] (a fresh copy). */
    val luma: ByteArray get() = lumaValue.copyOf()

    /** `[fx, fy, cx, cy]` in [width] × [height] pixels (a fresh copy). */
    val intrinsics: FloatArray get() = intrinsicsValue.copyOf()

    /** Device orientation quaternion `[x, y, z, w]` at capture, or null (a fresh copy). */
    val orientationQuaternion: FloatArray? get() = orientationValue?.copyOf()

    /** Column-major GL camera-from-map at capture, or null (a fresh copy). */
    val cameraFromMap: FloatArray? get() = cameraFromMapValue?.copyOf()

    override fun toString(): String =
        "PhotosphereKeyframe(t=$timestampNs, ${width}x$height, heading=$headingDeg, elevation=$elevationDeg, roll=$rollDeg)"
}

/**
 * The camera view a capture is projected through: an attitude plus intrinsics in [width] ×
 * [height] pixels.
 */
data class PhotosphereView(
    val headingDeg: Float,
    val elevationDeg: Float,
    val rollDeg: Float,
    val width: Int,
    val height: Int,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
)

/**
 * Keeps the latest [PhotosphereKeyframe] per [PhotosphereMap] tile, feeding the map as keyframes
 * arrive. Ported from GraffitiXR's standalone photosphere (`onStandalonePhotosphereKeyframe`).
 *
 * The first keyframe anchors the map's wall heading when it has none (the photosphere owns its
 * frame from startup; this is not a fingerprint anchor). Not thread-safe — like [PhotosphereMap],
 * guard both with one lock when keyframes and reads cross threads.
 */
class PhotosphereKeyframeStore(
    /** The map this store feeds. */
    val map: PhotosphereMap,
) {
    private val frames = LinkedHashMap<TileId, PhotosphereKeyframe>()

    /** The most recent accepted keyframe of any tile (supplies session intrinsics for a fresh tile). */
    var latest: PhotosphereKeyframe? = null
        private set

    /** Result of [record]. */
    data class Recorded(
        /** The tile the keyframe updated. */
        val tile: TileId,
        /** True when this was the tile's first ever capture. */
        val firstCaptureForTile: Boolean,
    )

    /**
     * Fold [keyframe] into the map (anchoring the wall heading if unset) and keep it for its tile.
     *
     * @return the updated tile, or null when the keyframe's direction lies outside the map's
     *   viewable lattice (nothing is stored then).
     */
    fun record(keyframe: PhotosphereKeyframe, nowMs: Long): Recorded? {
        if (!map.hasWallHeading()) map.setWallHeading(keyframe.headingDeg)
        val candidate = map.tileAt(keyframe.headingDeg, keyframe.elevationDeg)
        val first = candidate != null && !map.hasBeenScanned(candidate)
        val tile = map.markUpdated(
            headingDeg = keyframe.headingDeg,
            elevationDeg = keyframe.elevationDeg,
            nowMs = nowMs,
            representativeOrientation = keyframe.orientationQuaternion,
        ) ?: return null
        frames[tile] = keyframe
        latest = keyframe
        return Recorded(tile, first)
    }

    /** The keyframe stored for [tile], or null. */
    fun keyframe(tile: TileId): PhotosphereKeyframe? = frames[tile]

    /** Every stored (tile, keyframe), in first-recorded order. */
    fun keyframes(): Map<TileId, PhotosphereKeyframe> = LinkedHashMap(frames)

    /** Forget every keyframe (call alongside [PhotosphereMap.reset]). */
    fun clear() {
        frames.clear()
        latest = null
    }

    /**
     * The capture view for the current camera attitude: the current tile's own keyframe when it has
     * one, otherwise the session intrinsics of [latest] paired with the CURRENT attitude (never
     * another tile's direction).
     *
     * @return null without a usable attitude, any keyframe, or usable intrinsics.
     */
    fun view(headingDeg: Float, elevationDeg: Float, rollDeg: Float = 0f): PhotosphereView? {
        if (!headingDeg.isFinite() || !elevationDeg.isFinite() || !rollDeg.isFinite()) return null
        val tileFrame = map.tileAt(headingDeg, elevationDeg)?.let { frames[it] }
        val source = tileFrame ?: latest ?: return null
        val k = source.intrinsics
        return PhotosphereView(
            headingDeg = tileFrame?.headingDeg ?: headingDeg,
            elevationDeg = tileFrame?.elevationDeg ?: elevationDeg,
            rollDeg = tileFrame?.rollDeg ?: rollDeg,
            width = source.width,
            height = source.height,
            fx = k[0], fy = k[1], cx = k[2], cy = k[3],
        )
    }
}

package com.hereliesaz.sphereslam.reloc

import org.opencv.core.Mat

/**
 * Which known tile is the camera looking at? Given a live frame and a small set of candidate tiles
 * (each with a [Fingerprint] — planar or depth-backed), detect the frame's features once and
 * relocalize against each candidate, returning the best-supported match.
 *
 * Generic over the tile key [K] so it carries no dependency on the photosphere map: the caller
 * supplies whatever identifies a tile (e.g. its grid address) and the fingerprints for the tiles it
 * predicts are in view. Keeping the candidate set small — that is the caller's in-view prediction's
 * job — is what keeps this cheap as the map grows.
 *
 * Acceptance is delegated to the wrapped [Relocalizer] (its inlier floor); a candidate that doesn't
 * clear it simply doesn't score. Pure selection ([bestOf]) is split out so it is testable without
 * OpenCV.
 *
 * @property relocalizer the PnP relocalizer used per candidate; its `fingerprint` field is ignored
 *   (each candidate's fingerprint is passed explicitly).
 */
class TileMatcher<K>(
    private val relocalizer: Relocalizer,
) {
    /**
     * A recognized tile.
     *
     * @property key the caller's identifier for the matched tile.
     * @property cameraFromObject row-major 4×4 camera-from-tile pose (see [RelocResult]).
     * @property inliers PnP inliers supporting the match (confidence).
     */
    data class Match<K>(
        val key: K,
        val cameraFromObject: FloatArray,
        val inliers: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Match<*>) return false
            return key == other.key &&
                cameraFromObject.contentEquals(other.cameraFromObject) &&
                inliers == other.inliers
        }

        override fun hashCode(): Int {
            var r = key?.hashCode() ?: 0
            r = 31 * r + cameraFromObject.contentHashCode()
            r = 31 * r + inliers
            return r
        }
    }

    /**
     * Per-candidate recognition verdict from one shared feature detection pass.
     *
     * [recognized] contains every candidate that cleared relocalization, not only [best].
     * [checkedButUnmatched] contains candidates actually evaluated after successful live-feature
     * detection that failed relocalization. If feature detection itself fails, both sets are empty:
     * the frame did not provide enough evidence to declare any known tile changed.
     */
    data class Evaluation<K>(
        val best: Match<K>?,
        val recognized: Set<K>,
        val checkedButUnmatched: Set<K>,
    )

    /**
     * Detect features once and retain the verdict for every attempted candidate.
     */
    fun evaluate(gray: Mat, candidates: Map<K, Fingerprint>): Evaluation<K> {
        if (candidates.isEmpty()) return Evaluation(null, emptySet(), emptySet())
        val features = relocalizer.detect(gray) ?: return Evaluation(null, emptySet(), emptySet())
        val scored = ArrayList<Match<K>>(candidates.size)
        val recognized = LinkedHashSet<K>()
        val unmatched = LinkedHashSet<K>()
        for ((key, fingerprint) in candidates) {
            val result = relocalizer.relocalizeWith(
                features.keypoints,
                features.descriptors,
                fingerprint,
            )
            if (result == null) {
                unmatched.add(key)
            } else {
                recognized.add(key)
                scored.add(Match(key, result.cameraFromObject, result.inliers))
            }
        }
        return Evaluation(
            best = bestOf(scored),
            recognized = recognized,
            checkedButUnmatched = unmatched,
        )
    }

    /**
     * Backward-compatible best-match API. Use [evaluate] when freshness/change detection also needs
     * truthful per-candidate outcomes.
     */
    fun match(gray: Mat, candidates: Map<K, Fingerprint>): Match<K>? =
        evaluate(gray, candidates).best

    companion object {
        /** The highest-inlier match, or null for an empty list. Pure — the selection rule, unit-testable. */
        fun <K> bestOf(scored: List<Match<K>>): Match<K>? = scored.maxByOrNull { it.inliers }
    }
}

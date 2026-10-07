package com.hereliesaz.sphereslam.reloc

/**
 * A depth-backed tile that has cleared the geometric gate ([TileGate]) and is now offered to the
 * host for the trust decision. The library has already built the [fingerprint]; the parallel
 * per-point geometry stats are carried so a host's corroboration can weigh them without re-deriving.
 *
 * @property fingerprint the geometrically-admitted tile, built by the library.
 * @property perPointParallaxDeg parallax (deg) of each admitted point, parallel to the fingerprint's
 *   points (higher = better-constrained depth).
 * @property perPointReprojectionRmsPx reprojection RMS (px) of each admitted point, parallel to the
 *   fingerprint's points (lower = better agreement).
 */
class TileCandidate(
    val fingerprint: TileFingerprint,
    val perPointParallaxDeg: FloatArray,
    val perPointReprojectionRmsPx: FloatArray,
)

/**
 * The seam between the library's **geometric** admission and the host's **teleological** promotion.
 * After [TileGate] admits a candidate on geometry, the host decides whether it is trustworthy to
 * anchor on — the self-grow / corroboration judgment that is deliberately *not* in this library.
 *
 * The return encodes the whole decision in one type:
 * - `null` — reject; the tile does not enter the map.
 * - the candidate's own `fingerprint` — accept as is.
 * - a different [TileFingerprint] — accept, with whatever the host chose to change or annotate (the
 *   library neither requires nor inspects host trust metadata).
 *
 * The default ([AcceptAll]) returns the candidate's fingerprint unchanged, so the library is fully
 * usable standalone; a host replaces it with MobileGS's judgment. The library ships no non-trivial
 * implementation.
 */
fun interface TileCorroborator {
    fun corroborate(candidate: TileCandidate): TileFingerprint?

    companion object {
        /** Accept every geometrically-admitted candidate unchanged. The standalone default. */
        val AcceptAll: TileCorroborator = TileCorroborator { it.fingerprint }
    }
}

# SphereSLAM — path to 1.0

1.0 means one thing: **a public API we are willing to freeze, backing a capability proven on a real
device, documented.** Every milestone below serves that definition and nothing else.

## The capability 1.0 commits to

Markerless, ARCore-free spatial anchoring for overlay AR:

- 6-DoF pose on a recognized surface (KPM / PnP).
- A robust tracking loop — acceptance, stabilization, state — over that pose.
- An honest rotation bridge and a cheap low-latency predictor across off-page views.
- An orientation-indexed **photosphere** of tiles: per-tile freshness, a coverage glow, a re-check
  loop, relocalization seeding, and persistence across process death.
- **Off-page 6-DoF** by depth-backed tiles: triangulated 3D fingerprints matched with PnP, so a pose
  survives leaving the reference surface — not just rotation.

## The gate splits in two

Depth-backed tiles need a quality gate before the map trusts them. "Gate" is two different things,
and the split is the project's architectural spine:

- **Geometric gate — in the library.** View count, baseline adequacy, reprojection residual, RANSAC
  inliers. Pure math, no judgment. Enough to make a tile *geometrically* consistent and the whole
  capability provable standalone.
- **Teleological gate — stays proprietary.** Self-grow, corroboration, "is this tile trustworthy to
  anchor on." Layered on through a documented `corroborate()` seam; never in the library.

So the library proposes tiles and admits them on geometry; a host's own layer decides what to
*believe*. 1.0 ships the full mechanism without ever containing the proprietary judgment.

## Milestones

In flight, pending merge + tag (`0.13.0`–`0.17.0`): tile matcher, review loop, depth tags +
persistence, attitude predictor, relock seeding. The build below stacks on that.

| Ver | Milestone | Module | Gist |
|-----|-----------|--------|------|
| `0.18` | **TileFingerprint** | `:reloc` | Non-planar 3D fingerprint + tile-local frame + per-point confidence. `Relocalizer` already solves PnP on arbitrary `points3d`, so the solver is unchanged. |
| `0.19` | **Triangulation** | `:reloc` | `TileTriangulator.propose` — multi-view rays to metric 3D with per-point reprojection residuals. Verified against synthetic known geometry. |
| `0.20` | **Map growth + depth-PnP** | `:reloc` / `:sphereslam` | Geometric gate admits a proposed tile; the matcher recognizes promoted 3D tiles; pose is assembled into the map frame. Atlas growth folds in (same propose → gate → promote shape). `corroborate()` documented as the proprietary seam. |
| `0.21` | **Session facade** | `:sphereslam` | One orchestrator tying photosphere + tracking loop + relock + tile-building into a single entry, so a consumer does not hand-wire the parts. Shrinks the surface 1.0 freezes. |
| `0.22` | **API freeze audit** | all | Lock the public surface, make the rest `internal`, pay down naming/shape debt, state a per-module stability promise. The last cheap moment to change shape. |
| `0.23` | **Docs** | all | Per-module README, a top-level "does / does not (vs ARCore)" capability doc, sample usage. |
| — | **On-device dogfood** *(hard gate)* | consumer app | The photosphere pipeline runs end-to-end on hardware. Device-surfaced fixes land as `0.23.x`. |
| **`1.0.0`** | **Tag** | — | Only once the device proves the pipeline composes. |

## Sequencing notes

- `0.18`–`0.20` are the real engineering; triangulation and the geometric gate are where the
  subtlety lives.
- `0.21` is what makes 1.0 *usable* rather than a pile of parts — which is why the freeze audit
  (`0.22`) comes after it. We freeze the facade, not the internals.
- The on-device dogfood is a hard gate, not a formality: nothing here has been proven to compose as
  one pipeline on real hardware, and 1.0 is a claim we will not make unverified.

## What 1.0 is *not*

- Not full VIO / SLAM-in-the-open — that is ARCore's job and stays out of scope.
- Not the proprietary trust layer — self-grow, corroboration, and fingerprint generation live in the
  host, behind the seams this library documents.

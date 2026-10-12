# SphereSLAM test app (`:testapp`)

An on-device test bench for the library: a live standalone tracker, a checklist that grades itself,
and automatic reporting to this repository's issues. It builds against the library source in this
checkout and is never published.

## Build and install

~~~bash
./gradlew :testapp:installDebug
~~~

Debug builds are arm64-only. The report names the tested source as `git describe --tags --always --dirty`
of the checkout it was built from.

## Use

1. Point at a textured surface. Capture takes the square in the middle of the screen as the target
   (page 0, one unit wide).
2. Exercise each check (Checklist button; every row says what to do). The outline should sit on the
   target's edges.
3. Leave the app or keep going. There is nothing to copy: every change is reported.

The HUD shows tracking state, pose source and match method, match time and inliers, and the attitude
snapshot (heading, elevation, roll, facing azimuth).

## Checks

Graded automatically from live telemetry:

| Check | Passes when |
|---|---|
| `attitude_sensor` | the rotation-vector sensor reports reliable samples |
| `lens_correction` | the camera delivers a distortion-corrected stream (skipped when it cannot; evidence gives the uncorrected residual) |
| `lock_wall` / `lock_floor` / `lock_ceiling` | a target captured at \|elevation\| ≤ 30° / ≤ −60° / ≥ 60° locks within 30 s |
| `match_speed` | the median KPM/flow match time over 100 poses fits 33.3 ms |
| `flow_used` | flow tracking runs while locked |
| `rectified` | three `KPM_RECTIFIED` poses (grazing view) |
| `bridge` | LOCKED → IMU_BRIDGE → LOCKED |
| `off_wall` | a `PHOTOSPHERE` or `HELD` pose with the target out of view |
| `relock_after_away` | a lock after 10 s+ without a KPM pose |
| `map_solved` | `map_from_fingerprint` solved on the first lock |
| `atlas_growth` | a page grown beyond the captured one |
| `tile_fixes` | the session's `tiles{…}` diagnostic counts a rotation or pose fix |
| `pole_attitude` | at \|elevation\| ≥ 85°, heading/elevation/roll rebuild the sensor rotation within 2° (30 samples) |
| `pole_facing` | over a floor, held still for 2 s, the facing azimuth spreads ≤ 5° |

The tester's verdict (Pass / Fail buttons): `outline_on_target`, `outline_at_edges`,
`returns_after_minute`. A notes field goes with the report.

## Reporting

The app posts the run to `https://workflows.hereliesaz.workers.dev/test-report/SphereSLAM`
(`HereLiesAz/workflows`, `worker/src/test-report.js`) 3 s after any change and when it leaves the
foreground. The Worker files one issue per tested build (label `device-test-<build>`) and one comment
per run, updated in place as the run progresses, with the result table, notes and the last 200 log
lines. The key in `BuildConfig.REPORT_KEY` ships in the APK: it filters spam, it is not a secret.

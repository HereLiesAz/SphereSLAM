# SphereSLAM test app (`:testapp`)

An on-device test bench for the library: a live standalone tracker, a checklist that grades itself,
and automatic reporting to this repository's issues. It builds against the library source in this
checkout and is never published.

## Build and install

The **Test app** workflow (`.github/workflows/testapp.yml`) builds it on every push and pull request
to `main`, and on demand (Actions ▸ Test app ▸ Run workflow). The APK is the run's artifact,
`sphereslam-testapp-<build>`, kept 30 days. Every build is signed with the same key
(`testapp/testapp.keystore`, a test-bench key with its password in `build.gradle.kts`) and gets a
higher versionCode (the commit count), so a new APK installs over the previous one. The app bar shows
the running build next to the step counter, and the report names it.

Locally:

~~~bash
./gradlew :testapp:installDebug
~~~

Debug builds are arm64-only. The report names the tested source as `git describe --tags --always --dirty`
of the checkout it was built from.

## Use

The app walks you through the run one step at a time (`Guide.kt`, 15 steps, wall first, then the
floor, then an optional ceiling). Each step says exactly what to do and shows its progress live:
the tilt still needed before Capture unlocks, a lock countdown, frames tracked, a look-away timer.
It decides the step itself and moves on (a passed result stays up about 2 s). A step only you can
judge asks a yes/no question, and its buttons unlock only when the step's conditions are met (the
one-minute look-away, for example). A failed step shows its evidence with Retry and Continue. Skip
records the step as skipped.

- When something is in the way, a boxed line under the instruction says what and what to do, with
  a button when there is a fix: **Restart tracking** if the tracker has stopped, **Capture a
  target** if the step needs a lock that doesn't exist (or keeps failing), a nudge to point back at
  the target when the lock drops, and a request for a busier patch when a capture is too plain.
- The segmented bar under the title shows every step's result; **All steps** lists them with their
  evidence in place of the step (the camera keeps running), and tapping one goes to it.
- The status line under the camera says what tracking is doing in words (Locked on target, Target
  lost, Tracking stopped …), then pose source and match method, match time, inliers, and the
  camera's elevation. The capture square shows only on capture steps.
- If the app crashes, the next launch files the stack trace in that run's report.
- When the run is complete, add notes if something looked wrong. There is nothing to copy: every
  change is reported.

The screen is a single column (app bar, progress, camera, telemetry, step, actions), padded by the
measured status bar, navigation bar, display cutout and keyboard. The first step checks that on the
device (`layout_clear`), and `ScreenLayoutTest` renders the screen under Robolectric with those
insets. Its screenshots go to `testapp/build/screenshots/`.

## Checks

Graded automatically from live telemetry:

| Check | Passes when |
|---|---|
| `native_engine` | `SphereSlam.isOperational()`: libsphereslam loads and a KPM engine can be created |
| `layout_clear` | no control overlaps the status bar, navigation bar, display cutout or keyboard (evidence: the measured insets and padding) |
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

The tester's verdict (a yes/no question in the step): `outline_on_target`, `outline_at_edges`,
`returns_after_minute` (unlocks after a minute with the target out of view). A notes field goes with
the report.

## Reporting

The app posts the run to `https://workflows.hereliesaz.workers.dev/test-report/SphereSLAM`
(`HereLiesAz/workflows`, `worker/src/test-report.js`) 3 s after any change and when it leaves the
foreground. The Worker files one issue per tested build (label `device-test-<build>`) and one comment
per run, updated in place as the run progresses, with the result table, notes and the last 200 log
lines. The key in `BuildConfig.REPORT_KEY` ships in the APK: it filters spam, it is not a secret.

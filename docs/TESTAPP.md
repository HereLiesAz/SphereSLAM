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
one-minute look-away, for example; once met it stays met when you look back at the target). A
decided step shows its evidence with **Retry** (failed) or **Run again** (passed or skipped), and
**Continue** / **Next**. Skip records the step's undecided checks as skipped; a step with any skipped
check reads skipped, not passed. Buttons ignore taps for 0.7 s after they change, so a tap meant for
the last step can't land on the next one.

- When something is in the way, a boxed line under the instruction says what and what to do, with
  a button when there is a fix:
  - **Allow camera** / **Open settings** when the camera permission is missing (the run picks up as
    soon as you come back from Settings), **Restart camera** when the camera failed to start or has
    sent no frames for 5 s.
  - **Restart tracking** when the session has stopped.
  - **Capture a target** when a step needs a locked target the tracker doesn't hold (none yet,
    tracking restarted, or it holds another surface's), or when the lock has been gone 20 s.
  - A nudge to point back at the target when the lock drops, and a request for a busier patch when
    a capture is too plain.
- Every automatic step has a time limit (below). The clock runs only while nothing is in the way
  (and, on a capture step, only once its lock is decided), and when it runs out the step's undecided
  checks fail with what was seen, rather than waiting forever.
- The segmented bar under the title shows every step's result; **All steps** lists them with their
  evidence in place of the step (the camera keeps running), and tapping one goes to it (a step you
  jump to doesn't auto-advance).
- The status line under the camera says what tracking is doing in words (Locked on target, Target
  lost, Tracking stopped, Capture rejected …), then pose source and match method, match time,
  inliers, and the camera's elevation. The capture square shows only on capture steps.
- The run belongs to the process, not the screen: rotation, folding, resizing or a theme change
  keeps it, and the camera's frame follows the display's rotation.
- When the run is complete, add notes if something looked wrong. There is nothing to copy: every
  change is reported. **Start over** begins a new run with its own report; the finished one stays as
  it was filed.

The screen is a single column (app bar, progress, camera, telemetry, step, actions), padded by the
measured status bar, navigation bar, display cutout and keyboard. The first step checks that on the
device (`layout_clear`) against the insets the platform reports, measured again whenever the window
or its insets change. `ScreenLayoutTest` renders the screen under Robolectric with those insets; its
screenshots go to `testapp/build/screenshots/`. `MainActivityTest` drives the real activity without
the camera (permission coach, overview, Skip to the end, Start over).

## Checks

Graded automatically from live telemetry:

| Check | Passes when |
|---|---|
| `layout_clear` | no control overlaps the status bar, navigation bar, display cutout or keyboard (evidence: the measured insets and padding) |
| `native_packaged` | the APK carries `lib/<abi>/libsphereslam.so` for the device's ABI (read from the APK, not loaded) |
| `attitude_sensor` | the rotation-vector sensor reports accuracy medium or better (fails with no sensor) |
| `lens_correction` | the camera delivers a distortion-corrected stream (skipped when it cannot; evidence gives the uncorrected residual) |
| `lock_wall` / `lock_floor` / `lock_ceiling` | a target captured at \|elevation\| ≤ 30° / ≤ −60° / ≥ 60° locks within 30 s |
| `native_session` | the first capture runs 2 s with no `NATIVE_LIBRARY_UNAVAILABLE` (fails on one): the session path loads the engine itself |
| `match_speed` | over 100 locked KPM/flow poses, the median match time and the median whole-frame time both fit 33.3 ms |
| `flow_used` | flow tracking runs while locked |
| `rectified` | three `KPM_RECTIFIED` poses (grazing view) |
| `bridge` | LOCKED → IMU_BRIDGE → LOCKED |
| `off_wall` | a `PHOTOSPHERE` or `HELD` pose while REACQUIRING/LOST, 1.5 s+ after the last KPM pose |
| `relock_after_away` | a lock on the same target after 10 s+ without a KPM pose |
| `map_solved` | `map_from_fingerprint` solved on the first lock |
| `atlas_growth` | a page grown beyond the captured one |
| `tile_fixes` | the session's `tiles{…}` diagnostic counts a rotation or pose fix (fails when it reports tiles `disabled`) |
| `pole_attitude` | at \|elevation\| ≥ 85°, heading/elevation/roll rebuild the sensor rotation within 2° (30 samples) |
| `pole_facing` | over a floor, held still (turning < 5°/s) for 2 s, the facing azimuth spreads ≤ 5° |

The tester's verdict (a yes/no question in the step): `outline_on_target`, `outline_at_edges`,
`returns_after_minute` (unlocks after a minute with the target out of view). A notes field goes with
the report.

### Thresholds

| Value | Where | Why |
|---|---|---|
| 33.3 ms | `match_speed` | one frame at 30 fps, the camera's standard rate |
| 30 s | lock checks | a textured target locks in a few seconds; 30 s means it isn't going to |
| 10 s | `relock_after_away` | long enough that the session has dropped the lock, not just blinked |
| 1.5 s | `off_wall` | longer than the relock confirm window, so its fill-in poses don't count |
| 2 s | `native_session` | a missing library fails the first frames after a capture |
| medium | `attitude_sensor` | `SENSOR_STATUS_ACCURACY_MEDIUM`; low or unreliable means the heading drifts |
| 85° / 2° | `pole_attitude` | inside the band where the axis heading is noise; well above sensor noise, well below a misread roll |
| 5°/s, 2 s, 5° | `pole_facing` | a hand-held phone held still turns well under 5°/s; panning is far above |
| 45 s | Device check | sensor calibration with a figure-8 takes seconds |
| 20 s | Wall capture (after the lock) | the session check and map solve follow the lock at once |
| 60–120 s | the other automatic steps | each asks for something a person does in well under a minute |

## Reporting

The app posts the run to `https://workflows.hereliesaz.workers.dev/test-report/SphereSLAM`
(`HereLiesAz/workflows`, `worker/src/test-report.js`) 3 s after any change and when it leaves the
foreground. The Worker files one issue per tested build (label `device-test-<build>`) and one comment
per run, updated in place as the run progresses, with the result table, notes and the last 200 log
lines. The key in `BuildConfig.REPORT_KEY` ships in the APK: it filters spam, it is not a secret.

Nothing is lost to a dead network or a dead process (`Reporter.kt`, `TestApp.kt`):

- Every body is written to the app's files before it is sent and marked delivered only on a 2xx. A
  failed send retries on its own (10 s, doubling, at most every 5 min), and an undelivered body is
  sent at the next launch.
- A Java crash writes the crashed run's report, with `THE APP CRASHED` and the trace's head at the
  top of the notes and the full trace after the log, and tries to send it before the process dies.
- A native crash or an ANR never reaches that handler. At the next launch (Android 11+) the app reads
  the system's record of how the process ended and adds it, with its trace where the system kept one,
  to that run's last report.
- The next launch says so on the card for 20 s.

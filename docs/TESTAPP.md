# SphereSLAM test app (`:testapp`)

An on-device test bench for the library: a live standalone tracker, a checklist that grades itself,
automatic reporting to this repository's issues, and a tuning kit (record a session, replay the library
over it under other settings, compare continuous measures). It builds against the library source in this
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
- The outline is solid on a visual lock and dashed when it is an estimate (gyro bridge, held lock,
  the photosphere's placement): an estimate can sit beside the target once you have walked, since
  the photosphere knows how the phone turned, not where it went.
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

## Tuning

Pass/fail checks say whether something works; tuning needs to know how well, and whether a change
made it better. The kit: live knobs, recording, replay, sweeps and continuous measures.

### Knobs

**Tune** (app bar) opens the tuning panel in the step card's place. Each knob (`Knobs.kt`) is one
field of `PhotosphereTrackingSession.Config`, with its range, step, what it does, and the library's
own default:

| Group | Knobs |
|---|---|
| Steep angles | rectify from (`obliqueRectificationDeg`), guess steep views after N misses (`obliqueSearchAfterMisses`) |
| Previews | search previews after N misses (`previewSearchAfterMisses`, 0 builds none), search nearest first (`previewNearest`), tilt previews, size previews, turn previews every N° (`previewRotationStepDeg`), features per preview, preview atlas pages |
| Matching | match downscale, flow tracking, full solve every N frames / at least every N ms, flow alone for at most N ms, flow inliers at least, flow error at most |
| Acceptance | pose error at most, pose inliers at least, frame age at most (`ObservationAgeConfig.maxRealtimeAgeMs`) |
| Lock | confirm a lock over N frames, searching becomes lost after N ms, gyro bridge up to N ms |
| Photosphere | tile localization, atlas growth |

A change applies once the slider settles (0.6 s): the session restarts with the new values and keeps
its target. The values are kept across launches. The panel's live measures (below) restart with it.

### Recording

**Record** (in the panel's action bar) writes every camera frame and sensor reading the session is
given, exactly as given (`Recording.kt`, `.sslrec`): the upright luma (deflated), intrinsics,
timestamp, attitude sample and angles, and the camera-from-device rotation, plus the events between
frames: target captures (with the reference image), restarts, and knob changes. The camera hands
each frame to the recorder and to tracking separately, so a slow frame never costs the recording a
frame. A recording stops itself at 5 minutes; it takes a few hundred MB a minute. The newest 8 are
kept, in `Android/data/com.hereliesaz.sphereslam.testapp/files/recordings/` (`adb pull` reaches them).
A target already held when recording starts goes in first.

### Replay and sweeps

**Recordings** opens the tuning screen: the recordings, a sweep's setup, its progress and its
results. A sweep varies up to three knobs over one recording: every combination of their values on
top of a base (the recording's own settings, or the live ones), plus the base itself as the
baseline, at most 36 replays.

A replay (`Replay.kt`) runs on the phone, so its timings are real. It keeps the recording's time:
a virtual clock starts at the first frame and advances by what each frame really costs; frames that
arrived while the session was busy are skipped, as the camera skips them live, so a slower setting
sees fewer frames exactly as it would. The session runs on the same clock, so frame-age checks,
bridge windows and cadences hold. Captures replay before the frame they were taken on; a restart for
new knobs keeps the target, as it did live. A replay takes about as long as the recording.

When a sweep finishes its results are saved beside the recording and reported to the build's issue
as tables (by configuration, by viewing angle, failures by reason). The results screen ranks the
configurations by any measure; one tap applies a configuration to live tracking. Its detail shows
locked % by viewing angle against the baseline and both runs over time.

### Measures

`RunMetrics` scores a live session and a replay with the same code, from the first capture on:

| Measure | What it is |
|---|---|
| Locked % | share of time with a visual (KPM) pose; also per viewing-angle band (0–15°, 15–30°, 30–45°, 45–60°, 60–90°), and at 45°+ |
| Steepest lock | largest viewing angle of any visual pose |
| Relocks | losses of the visual pose over 300 ms that ended; median and longest time to relock; a loss still open at the end is counted apart |
| Off at relock | how far the estimate shown just before a relock was from the relocked pose (target widths, degrees) |
| Jitter | camera position spread while the phone is still (under 3°/s for 15+ visual frames), % of the target's width |
| Jumps | consecutive visual poses ≤ 150 ms apart that moved over 0.25 target widths or turned over 8° |
| Error | median reprojection error of the visual poses, px² |
| Frame, match | median and 95th percentile ms (frame: packing plus the session's work) |
| Dropped | frames skipped while the session was busy |
| Failures | the session's failures by reason (`STALE_OBSERVATION`, `NO_CURRENT_PAGE_MATCH` …) |

The viewing angle is the angle between the camera's axis and the target's normal, from the visual
pose or, without one, from the estimate (its rotation is the gyro's, so the angle is right even when
its position is not).

## Reporting

The app posts the run to `https://workflows.hereliesaz.workers.dev/test-report/SphereSLAM`
(`HereLiesAz/workflows`, `worker/src/test-report.js`) 3 s after any change and when it leaves the
foreground. The Worker files one issue per tested build (label `device-test-<build>`) and one comment
per run, updated in place as the run progresses, with the result table, notes and the newest log
lines (the app keeps 400 lines or 60 000 characters, the issue shows the newest 50 000). Transient
misses (`severity=TRANSIENT`, several a second while searching) are counted by reason onto the next
`timing{…}` line as `transient{…}` instead of one line each. The key in `BuildConfig.REPORT_KEY` ships in the APK: it filters spam, it is not a secret.

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

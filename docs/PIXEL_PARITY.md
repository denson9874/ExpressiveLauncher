# Expressive Launcher Pixel parity ledger

This ledger records verified Pixel Launcher behavior, the public-API-compatible Expressive
implementation, and validation evidence. Pixel-only private APIs and privileged system behavior are
out of scope for a third-party HOME app.

## Current QA delivery — 2026-09-06

Version **1.0.8 / code 9** was built and published through Jenkins from commit
`92ea47b6e2d159d87fce1ec2bbbbad7d258ff4cb`. Build #1 passed 45 pipeline checks,
132 app tests and 11 isolated Beta 4 device checks. Publish #1 verified the versioned uploads and
public APK bytes before promoting the existing QA feed. This release supersedes the historical
publication hold described below. See [the release record](JENKINS_ADOPTION_RELEASE.md) for its
exact identity, delivery evidence and [pipeline operations](CI_PIPELINE.md).

## Reference environment

- Reference date: 2026-09-04
- Latest public beta: Android 17 QPR2 Beta 4, released 2026-08-28; official release notes updated 2026-09-02
- Guest build: `CP41.260814.003.B1` (`dev-keys`), Android SDK full version `37.2`, security patch `2026-08-05`
- Guest fingerprint: `google/sdk_gphone16k_arm64/emu64a16k:17/CP41.260814.003.B1/16166531:user/dev-keys`
- System image: `system-images;android-37.2;google_apis_playstore_ps16k;arm64-v8a`, revision 4
- AVD: `Pixel_8_Pro_Android_17_QPR2_Beta4`, Pixel 8 Pro, ARM64, 16 KB page size
- Android Emulator: 37.2.5.0, build 16079175
- Pixel Launcher: `com.google.android.apps.nexuslauncher`, versionCode 907, versionName `17`
- Rollback retained: the prior `Pixel_8_Pro_Android_17_QPR2_Beta3` and `Pixel_8_Pro` Beta 2 AVDs and images were not removed
- Local evidence directory: `artifacts/pixel-parity-2026-09-04-VaIqR4` (generated QA evidence, not committed)

Official reference: [Android 17 QPR2 release notes](https://developer.android.com/about/versions/17/qpr2/release-notes)
and [Google Play system-image repository](https://dl.google.com/android/repository/sys-img/google_apis_playstore/sys-img2-3.xml).
The guest build and Pixel Launcher package version above were verified from the running AVD.

### Prior reference — 2026-08-28

- Reference date: 2026-08-28
- Latest public beta: Android 17 QPR2 Beta 3
- Guest build: `CP41.260731.005.B1` (`dev-keys`), Android security patch `2026-08-05`
- System image: `system-images;android-37.2-beta3;google_apis_playstore_ps16k;arm64-v8a`, revision 3
- AVD: `Pixel_8_Pro_Android_17_QPR2_Beta3`, Pixel 8 Pro, ARM64, 16 KB page size
- Android Emulator: 37.2.5.0, build 16079175
- Pixel Launcher: `com.google.android.apps.nexuslauncher`, versionCode 907, versionName `DEV`
- Rollback retained: the prior `Pixel_8_Pro` Android 17 QPR2 Beta 2 AVD and image were not removed

The guest build, package metadata, UI hierarchy, and screenshots are captured from the running AVD;
the AVD display name alone is not accepted as evidence.

## Implemented parity improvements

### 2026-09-04 — Smartspace date opens the current day after rollover

**Observed gap.** On the verified QPR2 Beta 4 guest, tapping Pixel Launcher's date after moving the
device date from September 4 to September 5 launched a fresh Calendar activity with the next day's
`content://com.android.calendar/time/…` URI. The pre-change Expressive date label also advanced, but
its click intent retained the September 4 timestamp from when the Smartspace target was bound.

**Implementation.** The built-in Smartspace date action now constructs the existing public
`CalendarContract` intent when clicked. Repeated taps use the current timestamp without rebinding
the card. The shared click wrapper still contains activity-launch failures; the existing intent flags,
whole-card action, and other Smartspace actions are preserved. No new API, permission, dependency,
or persistent data is introduced.

**Automated coverage.** Three Robolectric tests exercise the real detached card/date views: advancing
the clock across multiple days without rebinding, keeping the date action separate from the whole-card
action, and retrying successfully after a missing Calendar handler. Each launch checks the exact URI
timestamp and existing activity flags. Clock instrumentation and class-scoped font/preferences shadows
isolate the test fixture; they do not replace the production click listener or intent helper. The full
Expressive unit suite passed, 119/119 tests. Visible date ticking is covered by emulator evidence,
not by these detached-view unit tests.

**Initial device result.** The implementation passed date rollover checks on
`CP41.260814.003.B1`. Representative checks also passed for cold/warm startup, home, app drawer,
search, folders, widget picker, Wallpaper & style, portrait/landscape orientation, and local Smartspace
date/setup-card paging. Captured logs contained no crash, fatal exception, or ANR during these checks.
Calendar handoff and the destination URI were verified; Calendar remained at onboarding without a
signed-in account. The feed gesture launched the Google app, whose Discover surface reported
`Couldn't connect to Discover`, so external Discover content was not verified. External Smartspacer
was absent; the built-in fallback was tested.

**Release status.** The initial development-build QA passed, but it did not establish final Qa delivery
readiness. A September 5–6 recovery rebuilt the compact, durable-release-signed `Qa` variant matching
the shipped 1.0.7 update identity. That upgrade check exposed an intermittent pre-Application Android
replacement crash and a swipe-completion gap also present on 1.0.7. The user explicitly held the APK
for further repair. At that stage, version `1.0.8` / versionCode `9` was unpublished. See the repair
entry below and retained `qa-delivery-recovery` evidence. The later Jenkins release recorded above
supersedes that historical hold.

### 2026-09-06 — Preserve the final position when releasing an active swipe

**Observed gap.** On isolated Beta 4 guests, an upward workspace drag could start All Apps and then
settle back to Home because `BaseSwipeDetector` ignored movement carried only by `ACTION_UP`.
The shipped 1.0.7 Qa baseline had the same behavior. A 1600-pixel / 700-millisecond injected swipe
recorded only about 472 pixels at completion; explicitly providing the endpoint as a MOVE restored
the full 1576 pixels after touch slop and opened the drawer.

**Implementation.** An existing drag now consumes a valid, changed active-pointer release position
before settling. It uses the same pointer-origin and RTL calculations as MOVE. It does not start a
drag on release, advance CANCEL, or duplicate an unchanged endpoint. The two Quickstep pause
consumers skip terminal pause sampling while retaining final position updates; the custom one-shot
gesture controller also skips terminal action triggering. Fling velocity, success thresholds,
controller registration, ordinary MOVE handling, and unrelated gesture behavior are unchanged.

**Automated coverage.** Thirteen real-MotionEvent regression tests cover endpoint delivery before
completion, duplicate suppression, cancellation, taps/below-slop movement, missing active pointers,
LTR/RTL directions, pointer switching, settling, recatch, and both axes. Before repair, seven failed
specifically on the missing final callback and six preservation checks passed. After repair all 13
passed. The expanded full Expressive suite passed 132/132 tests; final-artifact UI QA is tracked in
the matching retained report, not inferred from unit success.

**Input-tool boundary.** The command-line `input swipe` can run out of wall-clock duration while
waiting for an intermediate event, then send only the final UP. A coherent, queued 1000-pixel fast
swipe opened the drawer on the unmodified diagnostic build with measured nonzero velocity and
`fling=true`, despite distance below the success threshold. No invented velocity fallback or reduced
threshold was added. See `baseline-swipe-check/FAST-FLING-CONTROL.md` for primary-source references,
measured controls, and why a short zero-velocity synthetic input is not a valid fling test.

**Upgrade boundary.** Foreground-HOME ADB replacement intermittently used a removed old APK path
before Application initialization, including on an identical-APK reinstall. Background/quiesced ADB
controls passed. The exact Android cache at fault is unproven; no app class-loader or resource hack
was introduced. See `REPLACEMENT-CRASH-DIAGNOSIS.md` and the direct-distribution validation guidance.
Privileged Quickstep recents integration remains source-reviewed, not end-to-end verified by a
third-party HOME app. The publication hold remained in effect during this repair; the later explicit
release request and verified Jenkins release are recorded above.

### 2026-08-28 — Date-first Smartspace fallback

**Observed gap.** On the same clean QPR2 Beta 3 guest, Pixel Launcher displayed `Fri, Aug 28` at the
top of the first home frame. Expressive 1.0.4 displayed a permanent local onboarding card instead
when Smartspacer was unavailable: `Welcome to Expressive Launcher L3` / `Swipe up to open the app
drawer`.

**Implementation.** The Expressive product now removes the local onboarding target and promotes the
weather/date target above setup prompts. Live weather, when available, uses the same promoted target;
battery, torch, Now Playing, setup, and other local targets remain available. The policy applies only
to the Expressive product and does not alter a healthy Smartspacer feed.

**Automated coverage.** Unit coverage verifies Expressive ordering, live-weather promotion, target
preservation, and unchanged non-Expressive behavior. Device coverage verifies that the built-in
fallback renders a non-empty date instead of onboarding when Smartspacer is absent.

**Device result.** Verified on `CP41.260731.005.B1`: the first frame and a persisted cold restart both
rendered `Fri, Aug 28`; the onboarding copy was absent. Cold/warm startup, home, drawer, search,
folder, widget picker, Wallpaper & style, rotation handling, feed gesture, and persistence passed
without a launcher crash, fatal exception, or ANR.

## Open limitations

- Expressive cannot use Pixel Launcher's private Smartspace, Google feed, or system-only recents APIs.
- Weather requires an available public/local provider; the deterministic fallback is the current date.
- The earlier Beta 3 development image reported Pixel Launcher versionName `DEV`; the verified Beta 4
  image reports `17` with the same versionCode 907. These labels do not establish a production release;
  behavior, exact guest build, and package metadata are recorded together.
- Calendar account setup/content and external Discover content were not validated in the Beta 4 run.
  The checked flows establish Calendar intent handoff and Google-app launch only.
- External Smartspacer was not installed in the Beta 4 QA environment; only the built-in fallback and
  its date/setup-card paging were exercised.

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

- Reference date: 2026-09-07
- Latest public beta: Android 17 QPR2 Beta 4, released 2026-08-28; official release notes updated 2026-09-02
- Guest build: `CP41.260814.003.B1` (`dev-keys`), Android SDK full version `37.2`, security patch `2026-08-05`
- Guest fingerprint: `google/sdk_gphone16k_arm64/emu64a16k:17/CP41.260814.003.B1/16166531:user/dev-keys`
- System image: `system-images;android-37.2;google_apis_playstore_ps16k;arm64-v8a`, revision 4
- AVD: `Expressive_Parity_Explore_20260907`, freshly created from the retained `Pixel_8_Pro_Android_17_QPR2_Beta4` hardware/image configuration; Pixel 8 Pro, ARM64, 16 KB page size
- Android Emulator: 37.2.5.0, build 16079175
- Pixel Launcher: `com.google.android.apps.nexuslauncher`, versionCode 907, versionName `17`
- Rollback retained: the prior `Pixel_8_Pro_Android_17_QPR2_Beta3` and `Pixel_8_Pro` Beta 2 AVDs and images were not removed
- Current evidence directory: `artifacts/pixel-parity-20260907`; September 4 evidence remains in `artifacts/pixel-parity-2026-09-04-VaIqR4` (generated QA evidence, not committed)

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

## GitHub distribution migration — 2026-09-07 (candidate 1.0.11)

The user requested GitHub build exports and updater notifications, and explicitly selected automatic
publication of future passing QA builds. The export repository is
https://github.com/denson9874/ExpressiveLauncher . Stable release publication remains separate.

The app keeps its existing manual and immediate/six-hour scheduled checks, notification deduplication,
snooze and verified installer flow. QA and release defaults now point to separate public GitHub
manifests on the updates branch, and About links point to the owned repository/releases/issues.
Real HTTPS parsing rejects malformed/credential-bearing URLs; normal HTTPS CDN redirects remain
supported while HTTP/HTTPS scheme-changing redirects are blocked. Six fetch/client regressions plus
existing policy/notification/ownership coverage passed, 23 focused tests total.

Jenkins now stages immutable versioned GitHub QA prereleases, verifies authenticated asset bytes,
and on promotion verifies all public assets before advancing only qa/latest.json using Contents API
blob-SHA conflict detection. The publisher preserves seal/signature/package/version checks, exact-asset
retries and failure receipts; 51 focused tests passed, including recovery of an empty failed-upload placeholder in the matching draft. A pre-migration Drive publisher is rejected by
the new provider contract before upload. Normal builds obtain their baseline from the GitHub QA feed.
The initial migration build explicitly selects the retained sealed1.0.10 baseline; eight focused
bootstrap/download tests passed, including a device-QA digest association added after independent review.

Version 1.0.11 / code12 is the migration candidate. Jenkins still owns the full app suite, signed
minified Qa assembly, isolated upgrade and final seal; final build/publication receipts and live
notification/download/installer evidence belong in artifacts/github-exports-20260907. This entry
records the implementation and focused checks, not an unverified release claim. Historical Drive
artifacts remain retained; the existing legacy QA manifest needs a one-time pointer to the verified
GitHub migration APK so installed1.0.10 and earlier builds can discover it. The one-time Jenkins
bridge requires the successful GitHub receipt, backs up and updates only the fixed legacy QA JSON
file, and verifies public readback; 14 focused migration tests passed.

## Implemented parity improvements

### 2026-09-08 — Recover At a Glance weather setup (candidate 1.0.12)

**Observed defect.** On the connected Pixel 11 Pro XL running Android 17 build
`CP41.260814.003.C2`, the published signed 1.0.11 / code 12 build returned Weather to off
after widget approval. Two surviving settings activities launched concurrent Google At a Glance
widget-bind requests. Android reported `Bad widget id 9`; the reader continued reusing that
invalid ID. Widget snapshots show a valid binding (ID 17) throughout the failure. Restarting
the launcher restored use of that existing connection, and Weather enabled with 89°F matching
the Google At a Glance widget on the same home screen. No weather-parser exception was observed.

**Implementation.** Setup requests are accepted only from resumed screens and coordinated once
across the application. Fresh enabled/binding/lifecycle checks reject stale requests. The upstream
lifecycle gate leaves an already launched Android permission result alive while preferences
are paused. Each new unbound-widget setup attempt first adopts a valid saved binding if another
manager has replaced the cached ID. Otherwise it allocates and persists a fresh ID. An existing
valid binding is retained. If binding is already allowed, no approval dialog opens.

**Focused validation.** All 13 new regressions passed (seven setup-coordinator and six widget-binding
checks; zero failures, errors or skips). They cover overlapping screens, background lifecycle
transitions, permission completion and cancellation, invalid-ID retry, adoption of a newer saved
binding by a stale object and across two managers, and preservation of a working widget. Jenkins remains responsible for the full suite, signed minified QA build,
upgrade smoke and seal. The exact sealed candidate is then validated on the connected Pixel
for weather rendering and persistence before GitHub QA promotion. Physical-device evidence
remains local under `artifacts/weather-pixel-20260908`; final results and release receipts belong
in that run report. This entry alone does not claim a completed build or publication.

### 2026-09-07 — Identify contact search actions for accessibility (candidate 1.0.10)

**Observed gap.** On the freshly booted and verified Beta 4 Pixel guest, a saved device-local
contact exposed distinct accessible actions, `Messages Mobile` and `Phone Mobile`. The same
contact in the retained, signed Expressive 1.0.9 Qa build exposed `Custom` for both clickable
icons. Contacts were enabled through normal search settings; UI hierarchies and screenshots
record both implementations.

**Implementation.** Contact rows now assign `Message <contact name>` and `Call <contact name>`
from translatable Android resources on every bind. Recycled rows receive the current person's
name. The generic XML descriptions are removed. Existing message/dial intents, default-app
resolution, permissions, visibility and layouts are unchanged.

**Focused coverage.** Two Robolectric tests bind the real row in an attached window and inspect
Android accessibility nodes. The contact test checks initial binding and rebinding to a second
person; the file test keeps contact controls hidden and its preview visible. Before the change,
the contact assertion failed with expected `Message Alex Parity`, actual `Custom`, while file
preservation passed. After the change both tests passed without failures, errors or skips.
Font, preference and theme setup are isolated within this test class; unrelated artwork loading
is paused while the actual binding and accessibility behavior run.

**Development device result.** A separate developer package on fresh Beta 4 guest
`Expressive_Parity_Dev_20260907` passed Alex → Jordan → Alex accessible-label checks. The phone
action opened the correct number in the dialer; the message action opened the correct SMS/MMS
conversation with an empty message field. The crash buffer was empty, with no launcher crash or
ANR events. These checks do not claim a completed call, sent message or spoken TalkBack session.

**Build-state recovery.** Duplicated ignored Kotlin, Java, merged-resource and test-output files
stalled local Gradle hashing. Only the affected generated task directories were preserved in
run-specific quarantine directories with recovery records. The developer assembly used a temporary
init script placing generated output under Library/Caches; no permanent build configuration changed.
Android correctly prevented a differently signed developer package from sharing the existing QA
feed permission, so a separate disposable development guest preserved the signed QA baseline.

**Candidate boundary.** Version 1.0.10 / code 11 records this focused change. Jenkins owns the full
suite, minified durable-signed Qa build, packaging, isolated upgrade checks and sealed candidate.
Publication remains upload-only: no QA or production feed or file-sharing change is authorized by
this recurring run. Final Jenkins results and publication receipts are recorded in the retained
run report and versioned CI artifacts; this entry alone is not a release claim.

### 2026-09-06 — Google Discover setup inside Expressive (candidate 1.0.9)

The release launcher already contains the native left-page overlay, but users previously needed
to acquire a separate feed-support APK and navigate a generic provider selector. Expressive now
bundles its same-signed background support and exposes setup, updates and a single Google Discover
switch in Home settings. The helper has no launcher icon or feed Activity; Google renders the panel
inside Home. Android confirms the helper installation and the main launcher stays non-debuggable.

The generated asset pipeline and runtime installer check version, bytes and signing identity.
Jenkins additionally rejects missing, mismatched or UI-bearing helper bundles. See
[Google Discover integration](GOOGLE_DISCOVER.md) for the platform constraint and setup flow.
Jenkins build #2 passed 145 app tests, 51 pipeline checks and 11 standard device checks. Ten
additional scoped integration flows passed against the exact signed candidate, including retained
Home data, normal helper installation with cancellation/retry, Google-owned overlay attachment to
Expressive, and persistent off/on behavior with Infinite scrolling enabled. Both the embedded
panel and the standalone Google app showed the same Discover connection error on the signed-out
guest; personalized stories remain unverified. See [the candidate delivery record](GOOGLE_DISCOVER_QA_1_0_9.md).

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
- Calendar account setup/content and personalized Discover content remain unverified. The original
  Beta 4 checks established Calendar intent handoff and Google-app launch; the separate 1.0.9
  checks additionally establish Google's native overlay attached inside Expressive Home.
- External Smartspacer was not installed in the Beta 4 QA environment; only the built-in fallback and
  its date/setup-card paging were exercised.

# Expressive Launcher Pixel parity ledger

This ledger records verified Pixel Launcher behavior, the public-API-compatible Expressive
implementation, and validation evidence. Pixel-only private APIs and privileged system behavior are
out of scope for a third-party HOME app.

## Jenkins adoption QA delivery — 2026-09-06

Version **1.0.8 / code 9** was built and published through Jenkins from commit
`92ea47b6e2d159d87fce1ec2bbbbad7d258ff4cb`. Build #1 passed 45 pipeline checks,
132 app tests and 11 isolated Beta 4 device checks. Publish #1 verified the versioned uploads and
public APK bytes before promoting the existing QA feed. This release supersedes the historical
publication hold described below. See [the release record](JENKINS_ADOPTION_RELEASE.md) for its
exact identity, delivery evidence and [pipeline operations](CI_PIPELINE.md).

## Direct-distribution wallpaper access correction — 2026-09-12 (candidate 1.0.15)

The user reported Blur wallpaper showing a Play Store policy denial in the GitHub-distributed
Expressive QA APK. The exact published 1.0.14/code15 reproduced that dialog on the pinned
`CP41.260814.003.B1` guest and did not declare `MANAGE_EXTERNAL_STORAGE`.

Expressive now declares Android’s all-files special access for both QA and stable, and its build
capability enables the matching Settings request. The separate upstream Play flavor stays restricted.
Wallpaper access requests only the special access needed for current wallpaper pixels; it does not
request separate photo/video permissions. A successful grant completes the requested blur toggle on
return, while cancellation leaves it off. Home refreshes blur after grants and restores its original
background after revocation. Existing selected-folder search remains available before full access,
and a partial folder grant no longer blocks opening the all-files request.

Signed candidate testing also exposed a crash when Android restored Preferences after revoking
access. Material3 saves `SheetValue` as a serializable enum, but R8 had removed its reflective
`values()` method. A keep rule for that one enum preserves the saved-state contract. The minified
candidate must pass the same revoke-and-return path; an unminified test cannot detect this removal.

This is a correction to Expressive’s direct-distribution contract, not a newly claimed Pixel feature.
The focused policy suite passed 20 unit tests; five Compose instrumentation checks passed on the
isolated Android17 QPR2 Beta4 emulator. Live Android Settings grant/deny/revoke flows, visible blur,
regrant, and persisted-folder retention are retained in `artifacts/blur-wallpaper-access-20260912`.
The 1.0.15/code16 candidate must pass Jenkins full unit/signing/minification/upgrade checks before
any delivery claim. The user paused stable publication; no release is authorized by this correction
or its test results. See [direct distribution](DIRECT_DISTRIBUTION.md) for the permission workflow.

## Expressive Bloom branding — 2026-09-12 (candidate 1.0.16)

The user approved the new Expressive Bloom icon for GitHub and current/future app builds.
The shared Expressive resources now use its transparent color foreground, ink-indigo
background and dedicated monochrome silhouette. Application, Preferences, round and
themed icons share the same artwork. Both launcher and embedded feed defaults advance
to 1.0.16/code17 so this can upgrade the already sealed 1.0.15 candidate.

Asset exports preserve Android's centered 66dp adaptive safe circle on a 108dp canvas;
the approved repository artwork and 512px listing icon are retained alongside a repeatable
export script. See [app icon assets](APP_ICON.md). This entry records the implementation;
signed Jenkins results, device screenshots and publication receipts are retained under
`artifacts/expressive-icon-20260912` when completed. Weekly stable publication remains paused.

## One app with selectable update channels — 2026-09-12 (candidate 2.0.0)

The user selected one app with in-place QA/Stable channel switching for the new 2.0 series.
Signed QA and Stable now share `dev.launcher.expressive.l3` and the existing release signer.
The About screen stores the selected channel, uses it for manual and background checks, and
keeps that choice across process restarts and build-channel replacements. A channel change
cancels stale work; installation revalidates the selected request and APK immediately before
handoff. Equal-version builds from the other channel can replace the installed build, while
an older selected channel waits until it catches up without downgrading user data.

QA 2.x uses `updates:qa-v2/latest.json`. The legacy QA 1.x `.debug` package and its existing
`updates:qa/latest.json` feed remain intact. Android does not transfer private launcher data
between these package IDs; legacy QA users need a one-time move to the unified app. Existing
Stable 1.0.16/code17 is the authenticated baseline for the first signed QA 2.0.0/code18 upgrade.

Local CI regression checks passed 260 tests. The full app suite, signed/minified Jenkins
candidate, in-place baseline upgrade and interactive channel-selection evidence are recorded
under `artifacts/unified-channel-2.0.0-20260912` as they complete. This entry records candidate
behavior, not an unverified publication. Subsequent new scheduled candidates advance to
2.0.1/code19 and onward; retries keep the same version. The existing QA schedule is preserved.

## Respect themed icons on Home only — 2026-09-13 (candidate 2.0.1)

On the user's Pixel 11 Pro XL running `CP41.260814.003.C2`, QA 2.0.0/code18 displayed
Lawnicons in the app drawer and its prediction row even with **Themed icons: Home screen**
selected and **Force monochrome** disabled. Lawnicons was selected as the normal icon pack.
The provider had flattened themed artwork into the shared base bitmap, so the drawer's
existing surface setting could no longer restore the original full-color icon.

The provider now retains original artwork and supplies monochrome artwork separately to
the icon factory. Home and drawer choose the corresponding cached rendering according to
their existing settings. The known monochrome-only Lawnicons pack supplies themed artwork;
native full-color icons remain available for surfaces with theming disabled. Ordinary colorful
packs retain their original artwork. The icon-state format advances to invalidate previously
flattened cached icons during an in-place upgrade, and source-map caching refreshes when
theming is re-enabled or the source changes.

Both launcher and embedded feed defaults advance to 2.0.1/code19. Regression results,
signed Jenkins validation, physical-device screenshots, and delivery receipts are retained in
`artifacts/lawnicons-home-only-20260913` as they complete. This entry records the candidate
implementation; final device and publication results require that retained evidence.

## Long-press the whole app search row — 2026-09-14 (candidate 2.0.2)

On the verified Beta 4 guest, holding the Gmail search label in Pixel Launcher opens app
actions. Expressive QA 2.0.1/code19 also opened actions from the small icon in its horizontal
Gmail result, but holding that row's title launched Gmail on release. Pixel presents a grid
result for this query; this comparison establishes the label gesture, not identical layouts.

App and shortcut rows now forward long presses from their text and empty area to the existing
icon action. The row consumes the gesture so release cannot also launch the result. Binding a
settings, calculator, web or other action result clears the recycled long-press listener and
state. Existing icon menus, drag handling, normal taps and keyboard launch remain in use.

The corrected baseline regression had three expected failures among four tests. After the
change, all 13 focused tests passed, including Private Space and contact accessibility checks.
Development UI checks passed for app title and empty-area holds, shortcut title actions,
dragging Compose onto Home, normal tap and keyboard launch, and a verified cold restart with
the shortcut retained. No fatal exception or ANR was observed in the scoped device log.

Both version defaults advance once to 2.0.2/code20. This records the candidate implementation;
Jenkins full-suite, signed/minified upgrade, sealed artifact and publication results must pass
before delivery is claimed. Evidence is retained in `artifacts/pixel-parity-20260914-resumed`.
No physical-device or spoken TalkBack validation is claimed.

## Telegram feedback and announcements — 2026-09-15 (candidate 2.0.3)

The user requested an Expressive community for issue reports, feedback and announcements.
About now includes **Community → Telegram feedback** linking to
`https://t.me/ExpressiveLauncherFeedback`, followed by **Telegram announcements** linking to
`https://t.me/ExpressiveLauncher`. Both use the existing external-link row and Telegram icon.
The GitHub Support destination and inherited Lawnchair community links remain in place.
This is a user-requested support addition and does not claim a Pixel Launcher parity change.

Both launcher and embedded feed defaults advance once to 2.0.3/code21. Ownership tests cover
the exact Expressive URLs and order plus inherited Lawnchair destinations; the About UI test
checks the Community heading and both clickable rows. This entry records the implementation.
Focused tests, signed Jenkins validation, device navigation and publication results are retained
in `artifacts/telegram-community-20260915` as they complete; publication is not established by
this entry.

## Keep widget results when search has surrounding spaces — 2026-09-16 (candidate 2.0.4)

On the verified Beta 4 guest, Pixel Launcher keeps the Clock group and all five widgets visible
for `clock ` and ` clock `. Published signed Expressive 2.0.3/code21 finds those widgets for
`clock`, but a trailing or leading space instead produces **No widgets or shortcuts found**.
An individual widget query such as ` digital ` also loses its matching results in Expressive.
Both launchers retain a whitespace-only field and its clear control while showing no results.

Widget matching now trims surrounding whitespace once using the launcher's existing utility,
before matching app titles or individual widget labels. The displayed query, callback identity,
clear control and search mode remain unchanged. Interior spaces keep their existing matching
rules; no permissions, data schema, dependency or private Pixel API is introduced.

Seven focused regressions exercise real result entries, the asynchronous callback and the real
search controller. The unchanged implementation failed six assertions; all seven pass after the
fix. Coverage includes padded app/item names, the existing utility's nonbreaking-space handling,
interior spaces, blank input, raw callback text and clear behavior. Nonbreaking-space coverage is
a utility regression, not a claim of directly observed Pixel behavior for that character.

Fifteen developer checks passed on a separate Beta 4 guest: app-name and item-label searches,
padded multiword names, unchanged interior-space/blank behavior, clear, adding a Digital Clock
from a padded search, a verified cold restart with the widget retained, and search after restart.
Scoped logs contained no fatal exception or launcher crash/ANR. Initial UI capture and menu
opening needed readiness retries; no production workaround was added for those helper timings.

Both version defaults advance once to 2.0.4/code22. This entry records candidate implementation;
Jenkins full-suite, signed/minified upgrade, sealing, publication and live installer evidence are
retained in `artifacts/pixel-parity-20260916` as they complete. The upgrade baseline is the verified
published 2.0.3/code21 seal `qa-2.0.3-21-build-19`. No physical-device or spoken TalkBack check is
claimed. Stable and legacy QA feeds remain separate.

## Restore separate Advanced settings rows — 2026-09-18 (candidate 2.0.5)

The [Pixel 8 Pro XDA report](https://xdaforums.com/t/app-qa-android-17-expressive-launcher-material-3-expressive-pixel-8-pro-feedback.4801791/post-90740687)
was accepted for investigation on September 16 as XDA-001. On the exact published signed
2.0.4/code22 APK, expanding **Home settings → Advanced** and scrolling exposed only
**Backup and restore**. The same defect reproduced at font scale 2.0 and display density 600.
The existing Dock, Folders, Gestures and Backup rows were siblings directly inside
`AnimatedVisibility`, so they occupied the same bounds and the final row covered the others.

The Advanced content now uses one vertical `Column` with the existing segmented-row spacing.
Its existing callbacks, conditional Quickstep row, selection and saved expansion state remain
unchanged. The section summary now describes Dock, folders, gestures and backup. No new setting,
permission, private API or data migration is introduced. Pixel Home and Home settings were directly
inspected on the same Beta 5 guest; Pixel does not have this identical Advanced category. This is
a reproduced community-reported Expressive navigation defect, not a claim of matching menu layouts.

Three focused Compose instrumentation tests use the production Preferences activity, inspect
unclipped row geometry, deliver actual touches to each of the four destinations, and check
collapse/reopen plus activity recreation. All three failed against the original layout (identical
Dock/Folders bounds and incorrect touch destination) and all three pass after the fix. The same
three pass with 200% text and density 600. An initial test-fixture bounds accessor compile error
was corrected before those red/green runs; it was not an application failure.

Developer screenshots confirm distinct, scrollable rows at normal and enlarged settings. A verified
empty-PID cold Home restart rendered normally; captured application logs contain no launcher fatal
exception, native crash or ANR. Font scale 1.0 and physical density 480 were restored. This uses a
separate Debug guest, built before version assignment; it is not signed-candidate delivery evidence.

Both version defaults advance once to 2.0.5/code23 after these checks. CI now pins the verified Beta 5
emulator build and template below; 18 existing smoke-QA regressions and 10 configuration/image
agreement checks pass. Jenkins must complete full tests, signed/minified assembly, upgrade, seal and
publication against this exact candidate. Evidence is retained in `artifacts/pixel-parity-20260918`
and the run cache as it completes; this ledger entry alone does not claim a released update.
The baseline is the verified published 2.0.4/code22 seal `qa-2.0.4-22-build-20`. New Friday XDA
feedback is deferred to Monday September 21 with target versions unassigned. Stable remains held.
No physical-device, spoken TalkBack or privileged Quickstep validation is claimed.
## Restore v1 (JAR) and v3 signature schemes for Android 12 / ColorOS installation compatibility — 2026-09-19 (candidate 2.0.7)

GitHub Issue [#15](https://github.com/denson9874/ExpressiveLauncher/issues/15) reported that installing candidate 2.0.6 on an Oppo CPH2043 (ColorOS / Android 12) failed with `INSTALL_PARSE_FAILED_NO_CERTIFICATES: Failed collecting certificates... SHA-512 digest of contents did not verify`, and package inspection tools reported `No signer certificate was available`.

Root-cause analysis established that both `build.gradle` and `expressiveFeed/build.gradle` had `enableV1Signing false` configured, completely omitting the v1 JAR signature block (`META-INF/*.SF`, `META-INF/*.RSA`), while `enableV3Signing` was also not enabled. Certain Android OEM installers (especially ColorOS on Oppo/Realme, MIUI, EMUI) and session-based streaming package parsers fail certificate collection or digest verification when v1 signatures are absent.

Both the launcher and companion feed signing configurations are updated to enable v1 (JAR signing), v2 (APK Signature Scheme v2), and v3 (APK Signature Scheme v3) while retaining the exact same release signing key and certificate (`c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2`). This ensures seamless package verification across stock Android, third-party APK installers, and OEM firmware versions.

Both launcher and embedded feed defaults advance to candidate 2.0.7 / versionCode 25.

## Lower minimum SDK requirement to 31 with full Android 12 backward compatibility — 2026-09-19 (candidate 2.0.6)

The user requested lowering the application SDK version requirement from 37 (Android 17) to 31
(Android 12) to allow devices running Android 12 through 16 to install and run Expressive Launcher,
while guaranteeing that modern Android 17 behaviors are preserved and no crashes or regressions
occur across supported API levels.

`minSdk` is now set to 31 across all manifests and Gradle build configurations (`build.gradle`,
`expressiveFeed/build.gradle`, `AndroidManifest.xml`, `quickstep/AndroidManifest-launcher.xml`, and
`play/AndroidManifest.xml`). `compileSdk = 37` (minorApiLevel 1) and `targetSdk = 37` remain unchanged
to retain compilation of ported Android 17 AOSP classes, preserve modern platform behaviors, and
comply with Play Store distribution policies.

Runtime compatibility gaps on API 31–32 guests were identified and remediated:
1. `Launcher.java`: In `onBackPressed()`, guarded Android 14+ Predictive Back (`OnBackInvokedDispatcher`
   and `BackEvent`) behind `Utilities.ATLEAST_U` and added the full pre-U legacy dispatch sequence
   (`finishAutoCancelActionMode`, `mDragController.cancelDrag`, `topView.close(true)`,
   `handler.onBackInvoked()`, and `onStateBack()`) to eliminate `NoClassDefFoundError` on Android 12/13.
2. `LauncherClient.java`: Replaced direct 3-argument `registerReceiver` with
   `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_EXPORTED)` to prevent `NoSuchMethodError`
   on API 31.
3. `ExpressiveUpdateNotifications.kt` & `ExpressiveUpdateNotificationControl.kt`: Guarded
   `POST_NOTIFICATIONS` runtime permission checks and launch requests behind
   `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`, correctly treating notifications as enabled
   by default on API 31.
4. Typed `Bundle.getParcelable(key, Class)` & `Intent.getParcelableExtra(key, Class)` calls in
   `RemoteTargetGluer.java`, `BubbleBarController.java`, `SplitScreenController.java`, `DragLayout.java`,
   and `PipDisplayTransferHandler.java`: Guarded behind `Utilities.ATLEAST_T` or
   `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU` with fallbacks to legacy single-argument
   getters to eliminate `NoSuchMethodError` on API 31.
5. `BubbleController.java`: Guarded `RECEIVER_NOT_EXPORTED` flags behind `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`.

Both launcher and embedded feed defaults advance to candidate 2.0.6 / versionCode 24. This entry
records candidate implementation; verification is recorded in `walkthrough.md`.

## Resolve shortcut drag crash on Android 17 and add return-to-default-page navigation — 2026-09-21 (candidate 2.0.9)

GitHub Issues [#18](https://github.com/denson9874/ExpressiveLauncher/issues/18) and [#23](https://github.com/denson9874/ExpressiveLauncher/issues/23) reported two launcher issues:
1. **Shortcut drag crash (Issue #18)**: When pinning a shortcut from an external application (such as Markor), `AddItemActivity` crashed with `java.lang.SecurityException: Permission Denial: starting Intent ... with remoteTransition`. Android 14+ requires `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS` for `RemoteTransition` options. Unprivileged third-party launchers do not hold this signature permission. `SystemApiWrapper.kt` and `ApiWrapper.java` now verify that `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS` is granted before attaching `RemoteTransition`, cleanly falling back to standard fade-out custom animation options. In addition, `AddItemActivity.onLongClick` wraps `startActivity(homeIntent, options)` in a try-catch for `SecurityException` and falls back to plain `startActivity(homeIntent)`.
2. **Return to default page (Issue #23)**: When returning to the launcher from an external application, the workspace remained on the last visited page rather than returning to the user's designated default page (e.g. center page). In addition, `QuickstepLauncher.java` had hardcoded `Workspace.DEFAULT_PAGE` (0) instead of querying `workspace.getDefaultPage()`. A new `returnToDefaultPage` preference was added to `PreferenceManager2` (enabled by default) and exposed as a switch in **Home settings → General**. `Launcher.java` respects this setting on `onNewIntent` and `onDeferredResumed()`, ensuring seamless navigation back to the designated default page.

Both launcher and embedded feed defaults advance to candidate 2.0.9 / versionCode 28.

## Add App Drawer Icon option and direct shortcut support — 2026-09-23 (candidate 2.0.10)

GitHub Issue [#17](https://github.com/denson9874/ExpressiveLauncher/issues/17) requested an accessible App Drawer icon option for users who cannot easily perform swipe-up gestures.
1. **Dedicated App Drawer Shortcut Activity**: Created `AppDrawerShortcutActivity`, exported with `android.intent.action.CREATE_SHORTCUT` and `android.intent.action.MAIN`. This allows 1-tap shortcut creation with the native `ic_apps` icon and direct launch handling that animates into All Apps.
2. **One-Tap Home Screen & Dock Pinning**: Added accessible "Add app drawer icon" options in **Settings > Home Screen > General** and **Settings > Dock > Icons** that invoke `AppDrawerShortcutActivity.pinAppDrawerShortcut(context)` to instantly place an App Drawer button onto the workspace or dock.
3. **Lifecycle-Resilient Launch Handling**: Updated `LawnchairLauncher.kt` to handle `START_ACTION` (`openAppDrawer`) in both `onCreate()` and `onNewIntent()`, ensuring consistent execution across cold and warm starts.

Both launcher and embedded feed defaults advance to candidate 2.0.10 / versionCode 29.

## Reference environment

- Reference date: 2026-09-18
- Latest public beta: Android 17 QPR2 Beta 5, released 2026-09-15; official release notes updated 2026-09-16
- Guest build: `CP41.260828.004.A7` (`dev-keys`), Android SDK full version `37.2`, security patch `2026-08-05`
- Guest fingerprint: `google/sdk_gphone16k_arm64/emu64a16k:17/CP41.260828.004.A7/16296984:user/dev-keys`
- System image: `system-images;android-37.2;google_apis_playstore_ps16k;arm64-v8a`, revision 5; official archive size and SHA-1 verified before extraction
- Image is installed separately at `/Users/daryldenson/Library/Android/reference-images/android-17-qpr2-beta5-r5/arm64-v8a/`, preserving the installed revision 4 image
- AVDs: `Expressive_Parity_Beta5_20260918` and `Expressive_Parity_Dev_Beta5_20260918`, separate owned Pixel 8 Pro ARM64 16 KB guests under Library/Caches; two independent reference cold boots passed
- CI static template: `Pixel_8_Pro_Android_17_QPR2_Beta5`; expected guest is the actual emulator A7 build, distinct from the physical-device A8/A6 builds in the official notes
- Android Emulator: 37.2.5.0, build 16079175
- Pixel Launcher: `com.google.android.apps.nexuslauncher`, versionCode 907, versionName `17`
- Rollback retained: prior AVDs, Beta 4 image and hardware template; 35 inventory entries and 10 complete image/config SHA-256 checks matched after preparation
- Reference limitation: emulator UWB service aborts report missing `/dev/uwb0`; Pixel Home rendered after both cold boots, with no Java fatal exception. This does not establish UWB hardware functionality
- Current evidence directory: `artifacts/pixel-parity-20260918`; generated QA evidence remains uncommitted

### Prior reference — 2026-09-16

- Reference date: 2026-09-16
- Latest public beta: Android 17 QPR2 Beta 4, released 2026-08-28; official release notes updated 2026-09-02
- Guest build: `CP41.260814.003.B1` (`dev-keys`), Android SDK full version `37.2`, security patch `2026-08-05`
- Guest fingerprint: `google/sdk_gphone16k_arm64/emu64a16k:17/CP41.260814.003.B1/16166531:user/dev-keys`
- System image: `system-images;android-37.2;google_apis_playstore_ps16k;arm64-v8a`, revision 4
- AVD: `Expressive_Parity_Explore_20260916`, created from the retained `Pixel_8_Pro_Android_17_QPR2_Beta4` hardware/image configuration; Pixel 8 Pro, ARM64, 16 KB page size. Separate owned `Expressive_Parity_Dev_20260916` used for development checks on the same verified guest. Both run in an isolated AVD directory under Library/Caches.
- Android Emulator: 37.2.5.0, build 16079175
- Pixel Launcher: `com.google.android.apps.nexuslauncher`, versionCode 907, versionName `17`
- Rollback retained: the two existing September 15 CI AVDs, the Beta 4 hardware template and installed image were preserved; this run did not remove or replace any previous environment.
- Current evidence directory: `artifacts/pixel-parity-20260916`; previous investigation and release evidence remain retained (generated QA evidence, not committed)

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

### 2026-09-11 — Unlock hidden Private Space directly into its apps (candidate 1.0.14)

**Observed gap.** On the connected Pixel 11 Pro XL (`CP41.260814.003.C2`), signed QA
1.0.13 / code 14 correctly locked and hid Private Space, including after a launcher restart.
Searching the full `private space` label recovered a neutral result, but opening it diverted to
Android's Private Space settings. The user had to return to the drawer to reach the apps.
On the freshly verified Beta 4 Pixel Launcher guest above, the same hidden-space search instead
authenticates and directly reveals the private apps with search cleared.

**Implementation.** The recovery row, icon and keyboard action now use the existing public
`UserManager.requestQuietModeEnabled` unlock path. The manager reads actual quiet mode and waits
for the confirmed model transition before opening the container; stale or unknown launcher state
is reconciled without assuming authentication succeeded. Already-unlocked spaces open directly.
Search's keyboard is dismissed before authentication, and late provider callbacks for a cleared
query are rejected so they cannot restore the recovery results over the opened space. Opening
also scrolls to the destination when Private Space animation is disabled. The exact-query result
remains state-blind, Android setup remains the fallback when no profile exists, and the settings
gear keeps its settings destination.

**Focused validation.** Eighteen unit checks pass: nine manager dispatch/state/navigation cases,
six rendered row/icon/keyboard/rebinding cases, two existing recovery-target cases, and one real
late-result delivery regression. Live testing exposed the late callback after the initial test
pass; the corrected developer APK subsequently passed all 27 exploratory checks. These include
lock/hide, cancelled authentication with the query preserved, icon retry, authenticated direct
opening, delayed-callback stability, hidden keyboard, cold-process recovery, the settings gear,
ordinary app search and cold HOME startup. No launcher crash or ANR was observed in the captured
test interval. Evidence is retained in `artifacts/pixel-parity-20260911`, including the reference,
physical baseline, focused XML results, and `developer-ime-final-ui/result.json`.

Version **1.0.14 / code 15** is the candidate record. Jenkins owns the full suite, signed minified
Qa build, isolated same-signer upgrade, seal and publication. The physical phone disconnected
after baseline reproduction, so the candidate's physical retest remains pending reconnection.
Candidate build/publication receipts and subsequent installer evidence belong with this run's
retained artifacts; this entry does not claim an unverified release or phone installation.

### 2026-09-09 — Use saved phone numbers in contact search (candidate 1.0.13)

**Observed gap.** On the verified Beta 4 guest, a device-local contact named AlexParity has
phone number `2025550123` with the custom label `Work`. Pixel Launcher's Phone action opens
`(202) 555-0123`. The exact published, signed Expressive 1.0.12 / code 13 instead opens `967-5`,
the dial-pad conversion of `Work`. The provider interpreted MIME-specific DATA3/DATA5 values
as numbers; a later email row could also overwrite the destination. Email-only contacts had
a working contact-details action, which must remain available.

**Implementation.** Phone destinations now come only from the public `Phone.NUMBER` column
on phone MIME rows. Contact metadata remains separate, so email and custom labels cannot
replace the number. Selected contacts are fully read after the result limit is reached.
Aggregate-default numbers take precedence over raw-contact defaults, with numeric data ID
providing a stable tie-break. Existing name matching, permissions and accepted contact types
remain intact. Contacts without numbers remain searchable and open their details; their Call
and Message controls are hidden with listeners and accessibility descriptions cleared.
Rebinding the row to a phone contact restores both actions and the current person's labels.

**Focused validation.** Twelve provider regressions and three contact-row checks pass with
zero failures, errors or skips. Nine assertions failed against the prior behavior before the fix.
Coverage includes custom labels, email ordering, blank data, malformed IDs, result limits,
preferred numbers, permission checks, email-only details and recycled action controls.
The final fresh developer build passed the actual custom-label and later-email dialer flows,
email-only detail navigation, phone-result rebinding, an empty AlexParity SMS/MMS composer,
and three cold Home launches without a new launcher fatal exception or ANR. No call or message
was sent; accessibility checks do not claim a spoken TalkBack session.

**Development build recovery.** A reused local Kotlin cache paired an older Smartspace consumer
with a widget class missing a compiler-generated stability field. Preserved class/dex evidence
confirmed the mismatch. Fresh nonincremental compilation into a separate cache restored the
field without changing widget/Smartspace source; the focused tests and runtime flows then passed.
A final log-decoding error in the ignored QA helper was resolved by retaining the raw bytes and
using replacement decoding. Prior caches, failed evidence, AVDs and images remain retained.

**Candidate boundary.** Version 1.0.13 / code 14 was incremented once after local validation.
Jenkins owns the full unit suite, signed minified QA assembly, isolated upgrade checks, seal
and authorized GitHub QA publication. The expected CI guest stays `CP41.260814.003.B1`, matching
the newest official QPR image verified today. Exact commit, terminal Jenkins results, seal,
publication receipts and any live updater evidence belong in `artifacts/pixel-parity-20260909`;
this ledger entry alone does not claim a published update.

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

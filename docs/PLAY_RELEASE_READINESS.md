# Google Play release readiness

Last verified: August 26, 2026 on the Pixel 8 Pro Android 17 (API 37) emulator. The
August 25 physical-device and bundle checks remain recorded below.

## Verified gates

- `testLawnWithQuickstepExpressiveDebugUnitTest`: 61 tests passed with zero failures, errors, or
  skips. Coverage includes widget activity results, default-workspace source and XML namespace
  policy, Smartspacer, icon-pack parsing, file-access policy, About ownership/easter-egg behavior,
  day-scoped saved state and daylight-saving boundaries, and wallpaper-carousel geometry and
  accessibility state.
- The current Expressive Android instrumentation APK passed all 12 tests directly on the API 37
  emulator. Coverage includes widget result-proxy registration, Smartspacer fallback/reattachment,
  encrypted hidden apps, selected-folder/cloud search, edge-to-edge settings, Play-safe file
  access, the complete About daily-riddle/five-tap celebration flow, and a deliberately emptied
  user-profile cache to reproduce the widget-catalog cold-start race.
- `assembleLawnWithQuickstepExpressiveRelease` produced the minified/shrunk 1.0.0 APK. Direct APK
  inspection confirmed the daily About and wallpaper-carousel resources survived shrinking, the
  HOME activity remains exported, and restricted storage permissions and `debuggable` are absent.
  Its SHA-256 is `49bc364f2aed41666dff8e71ff08077ca06bd2cca48a088750a19e83e79135be`.
- A disposable QA-signed copy of that exact minified APK was installed on the API 37 emulator.
  Clean first launch showed the Pixel-style Google folder, Camera, Play Store, Phone, Messages,
  Contacts, and Chrome defaults. After a forced process restart, the picker still exposed the full
  widget catalog and Clock search returned all five Clock widgets. A real Digital Clock completed
  Android's bind confirmation, rendered on the workspace, and survived another process restart and
  an APK update. Manual checks also verified the selected Home and unselected Lock screen wallpaper
  cards, the system Wallpaper & style handoff, the owned About profile, daily quote/riddle,
  progressive snark, and the modal five-tap thank-you animation. The focused instrumentation and
  release logcat scans contained no launcher fatal exception or ANR.
- `bundleLawnWithQuickstepExpressiveRelease`: a minified, signed QA bundle was built using an
  ephemeral test upload key. The production task correctly refuses to run without an upload key
  and a public HTTPS privacy-policy URL. That August 25 QA bundle predates the final widget fixes
  and is retained only as pipeline evidence, not as an upload candidate.
- Bundletool 1.18.3 validated the AAB. The manifest reports package
  `dev.launcher.expressive.l3`, version `1.0.0` (`versionCode` 1), and min/target SDK 37.
- A universal APK generated from that exact AAB installed and cold-launched in 536 ms. No fatal
  exception, ANR, or activity-not-found event appeared in the focused logcat capture.
- First-run defaults rendered the Google folder, Camera, Play Store, and the Pixel-style Phone,
  Messages, Contacts, and Chrome dock. Settings displayed `Expressive Launcher L3 1.0.0`.
- Store icon, feature graphic, and two phone screenshots were visually inspected. Screenshots are
  1080 by 2160 JPEGs without an alpha channel.

The QA key and bundle-derived device APK are disposable validation credentials/artifacts. They
must not be uploaded to Play. Generate and protect the permanent upload key as described in
`docs/PLAY_RELEASE.md`.

## Open production gates

### Critical: full release lint is not clean

The August 26 `lintLawnWithQuickstepExpressiveRelease` run failed with 1,675 errors and 1,448
warnings. The largest counts are inherited resource/translation findings (including 1,047 missing
translations, 669 unused resources, 324 format mismatches, 142 obsolete SDK checks, 85 invalid
string formats, 74 extra translations, and 12 missing default resources), but the report also
contains runtime-significant findings:

- four legacy `onBackPressed` implementations flagged for predictive-back migration;
- 22 missing constraints in gesture-tutorial layouts;
- two remaining static activity/context leak warnings;
- remaining protected-permission findings from inherited source manifests, even though the final
  Expressive APK omits `BIND_APPWIDGET` and the restricted storage permissions.

The contextual-search and wallpaper call sites are now guarded, all 17 default-workspace files use
the modern `res-auto` namespace, release CI explicitly invokes lint, and the first-launch parser has
tests for both modern and legacy namespace forms. Widget enumeration now bypasses the asynchronously
warmed profile cache, and each widget model owns its application context instead of sharing a static
one. These fixes reduced the release lint total, but the remaining report is still a production
blocker.

Do not create a lint baseline for the whole report. Triage runtime and manifest findings first,
then isolate upstream-only false positives narrowly with comments and issue references. The full
report is generated at `build/reports/lint-results-lawnWithQuickstepExpressiveRelease.html`.

### Critical: production identity and signing are not final

- Select the permanent, brand-owned application ID before the first Play upload. The current
  package has already been installed with a debug certificate and may enter Google's existing
  package-name verification flow.
- Create the permanent upload key, back it up outside this repository, and configure local or CI
  secrets. The build deliberately has no debug-signing fallback.
- Increment `expressiveVersionCode` for every uploaded artifact.

### Policy and account inputs required

- Replace every placeholder in `play/privacy-policy-template.md`, have it reviewed, and publish it
  at the same public HTTPS URL embedded in the app and entered in Play Console.
- Complete Data safety from `play/data-safety.md` against the exact shipping configuration.
- Complete declarations for `QUERY_ALL_PACKAGES`, optional contact/media access, the gesture
  accessibility service, foreground service use, and notification permission.
- Confirm whether the Play developer account is subject to the 12-testers-for-14-days production
  access requirement.
- Rewrite and review translations before uploading localized listings. Only the prepared `en-US`
  listing is product-specific today.

## Re-run before each upload

```sh
./gradlew \
  testLawnWithQuickstepExpressiveDebugUnitTest \
  connectedLawnWithQuickstepExpressiveDebugAndroidTest \
  lintLawnWithQuickstepExpressiveRelease

./gradlew bundleLawnWithQuickstepExpressiveRelease \
  -PexpressiveApplicationId=your.permanent.application.id \
  -PexpressiveVersionCode=1 \
  -PexpressiveVersionName=1.0.0 \
  -PexpressivePrivacyPolicyUrl=https://your-domain.example/privacy
```

After Play processes the AAB, install the Play-delivered build from Internal testing and repeat
Home-role selection, first-run defaults, widget bind/configure, folder, search, Smartspacer
fallback, rotation, process-recreation, and upgrade-retention checks.

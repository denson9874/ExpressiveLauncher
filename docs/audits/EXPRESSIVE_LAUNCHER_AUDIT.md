# Expressive Launcher Android 17 Release Audit

Audit date: 2026-08-26
Branch: `codex/launcher3-rebase`
Baseline commit: `cf9bc51846f`
Devices: Pixel 7 Pro, Android 17/API 37, extension level 23, 120 Hz; Pixel 8 Pro
Android 17/API 37 emulator

## Outcome

The minified Expressive release builds, all 61 JVM/Robolectric tests pass, all 12 API 37 device
tests pass, and clean-install interactive launcher, widget, wallpaper, About, search, scroll, and
configuration-change runs completed without a launcher crash, ANR, out-of-bounds exception, or
missing-service retry loop.

The release APK is intentionally unsigned until the private production upload key is supplied. A
separate Android-debug-certificate copy was used only for emulator QA. Full Android lint also
remains red on the inherited AOSP/Lawnchair baseline (1,675 errors and 1,448 warnings); the dominant
groups and architectural constraints are recorded below.

## Remediated defects

### Critical

- Fixed first-launch `SQLITE_READONLY_DBMOVED`: stale database cleanup now protects the database name actually owned by the open helper instead of a concurrently recalculated grid filename.
- Fixed Smartspacer's zero-height pager and blank host after reattachment/configuration recreation.
- Added a non-crashing built-in Smartspace/setup fallback when Smartspacer is missing or does not respond.
- Removed application-global Smartspacer client teardown from individual launcher activity destruction.

### Major

- Added lifecycle-aware Smartspacer attach/detach behavior and a bounded target count.
- Removed the standard-home flavor's infinite `TouchInteractionService` bind retry. The service is intentionally absent because third-party launchers cannot own the privileged recents component.
- Made prediction lookup tolerate locked Android 17 private profiles and avoid repeated cross-user security stack traces.
- Made all-apps search generation-aware so stale background results cannot overwrite a newer query; added stale-index guards to adapter item access.
- Fixed the cold-start widget catalog race by querying Android's current user profiles instead of
  depending on an asynchronously warmed cache; restored per-instance widget-model context ownership.
- Fixed first-run bundled workspace parsing and prevented Expressive from being replaced by OEM
  partner layouts, yielding the requested Pixel-style defaults.
- Added Android Keystore AES/GCM storage and plaintext migration for hidden-app component names. Corrupt ciphertext fails closed.
- Hardened icon-pack parsing: normalized components, closed XML streams, bounded parallel parsing, cached resource IDs, and removed unstructured stack-trace printing.
- Closed or lifecycle-scoped wallpaper, widget, icon override, database, theme, font, notification, and Smartspace flows/resources that could outlive their owner.
- Repaired API 37 desktop-task layout IDs and several invalid localized format strings.
- Enforced `minSdk=37` and `targetSdk=37` in the app and Quickstep manifests/build configuration.

### Minor / build hygiene

- Added current API 37-capable Robolectric test infrastructure and an unsigned test-only crypto-provider bundle required by AGP's transformed test classpath.
- Kotlin formatting passes.
- The emulator-only QA copy passes APK Signature Scheme v3 verification.

## Added tests

JVM/Robolectric: 61 tests, 0 failures

- Smartspacer host measurement/fallback
- Smartspacer configuration limits
- Lawnchair icon-pack component parsing
- Active launcher database cleanup protection
- Widget cold-start/profile-cache and per-model context ownership
- Default workspace source/namespace parsing, About daily content, file access, and wallpaper cards

API 37 instrumentation: 12 tests, 0 failures

- Smartspacer missing-provider fallback
- Smartspacer detach/reattach/fresh-host lifecycle
- Hidden-app encrypted round trip and corrupt-ciphertext fail-closed behavior
- Compose edge-to-edge/system-bar inset containment
- Widget result-proxy registration and empty-profile-cache provider loading
- Selected-folder/cloud search and Play-safe storage manifest behavior
- About ownership, daily riddle, and five-tap celebration accessibility

Interactive device QA:

- Clean install and first workspace population
- App drawer opening and repeated rapid 120 Hz scrolling
- Search query/result rendering with IME (`chatgpt` -> ChatGPT result)
- Back navigation through IME/search/drawer
- Dark/light mode changes
- Portrait/landscape recreation
- Cold-process widget catalog loading, Clock search, bind confirmation, rendered Digital Clock,
  process-restart persistence, and APK-update persistence
- Pixel-style first-run Google folder, Camera, Play Store, Phone, Messages, Contacts, and Chrome
- Wallpaper preview carousel/system picker handoff and About daily/easter-egg behavior
- Logcat crash/ANR/index/SQLite/TIS retry scan
- Original Pixel Launcher home role, night mode, and rotation settings restored afterward

## Static-analysis findings still open

Full lint: 1,675 errors, 1,448 warnings.

Largest error groups:

- 1,047 `MissingTranslation`
- 324 `StringFormatMatches`
- 85 `StringFormatInvalid`
- 74 `ExtraTranslation`
- 31 `ThreadConstraint`
- 22 `ProtectedPermissions`
- 22 `MissingConstraints`
- 12 `MissingDefaultResource`
- 8 `AppCompatCustomView`
- 7 `WrongConstant`
- 4 `GestureBackNavigation`

Largest warning groups:

- 669 `UnusedResources`
- 142 `ObsoleteSdkInt`
- 113 `VisibleForTests`
- 57 `UseKtx`
- 41 `Typos`
- 31 `UseRequiresApi`
- 30 `ClickableViewAccessibility`
- 29 `ContentDescription`

The protected-permission and Quickstep/recents findings reflect AOSP system-launcher code compiled into a standard-home product. A normal installed APK cannot acquire those signature/system privileges. This build now skips its absent TIS binding, but full system Recents, Quickswitch, taskbar ownership, privileged split-screen initiation, and contextual search require deployment as the platform's signed recents/home component.

The compiler also reports 28 Java warnings, predominantly `DesktopModeStatus` APIs marked for removal, plus fallback `systemUiVisibility` use in `RecentsWindowManager`. These remain migration debt against the next AOSP/SystemUI API revision.

## Validation gaps

- Smartspacer was not installed on the device, so real provider permission handshake, live target sync, and provider-death recovery were not end-to-end validated. Missing/unresponsive-provider fallback and host recreation were validated.
- Privileged Quickstep/Recents behavior cannot be validated with the standard-home APK.
- Folder expansion, cross-page drag/drop, and third-party widget resize were not automated in this pass.
- The release APK is unsigned; provide/configure the private production upload key before distribution.

## Artifacts

- Release APK: `build/outputs/apk/lawnWithQuickstepExpressive/release/ExpressiveLauncherL3.1.0.0.expressive.release.apk`
- Release APK SHA-256: `49bc364f2aed41666dff8e71ff08077ca06bd2cca48a088750a19e83e79135be`
- Lint HTML: `build/reports/lint-results-lawnWithQuickstepExpressiveRelease.html`
- Lint text: `build/reports/lint-results-lawnWithQuickstepExpressiveRelease.txt`
- JVM report: `build/reports/tests/testLawnWithQuickstepExpressiveDebugUnitTest/index.html`
- Device report: `build/reports/androidTests/connected/debug/flavors/lawnWithQuickstepExpressive/index.html`
- Device screenshots/UI trees: `artifacts/expressive-qa/`

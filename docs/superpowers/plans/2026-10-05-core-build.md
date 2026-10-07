# Expressive Core build (Play Protect enhanced fraud protection) — Implementation Plan

**Problem (XDA-021):** Play Protect's enhanced fraud protection blocks apps installed from browsers,
messaging apps or file managers when they declare an accessibility service, a notification listener,
or SMS read/receive. Expressive declares `BIND_ACCESSIBILITY_SERVICE` (`LawnchairAccessibilityService`)
and `BIND_NOTIFICATION_LISTENER_SERVICE` (Launcher3 `NotificationListener`). Google's documented
remedies are Play distribution or removing those capabilities; developer verification (4.0.8) does
not affect this check. The user chose a Core build (2026-10-05).

**Goal:** A second APK of the same package and signer, "Expressive Core", with neither service, that
installs from a browser without the block. Users can move between Core and Full in place.

## What Core gives up (verified in source)
| Feature | Full | Core |
| --- | --- | --- |
| Notification dots | `NotificationListener` | Hidden; Settings explains why and links to Full |
| Open notifications / quick settings gestures | `StatusBarManager` first, a11y fallback | `StatusBarManager` only (`EXPAND_STATUS_BAR` is declared) |
| Double-tap to sleep | a11y (default), device admin, root | Device admin or root; note that device-admin locking requires the PIN for the next unlock |
| Recents gesture | a11y only | Option hidden |

## Design
- **Build switch:** Gradle property `-PexpressiveCore=true`, mirroring how `targetPlayStore` already
  selects the manifest and BuildConfig. No new flavor dimension, so task names and variants stay the same.
- **Manifest overlay** (`expressive/AndroidManifest-core.xml`): `tools:node="remove"` for
  `app.lawnchair.LawnchairAccessibilityService` and `com.android.launcher3.notification.NotificationListener`.
  A CI contract test fails if a Core APK's manifest contains either `BIND_` permission.
- **Runtime gating:** `BuildConfig.EXPRESSIVE_CORE`; Notification dots preference, the a11y sleep mode
  and the Recents gesture handler hidden or replaced in Core, with an About/Settings line: "Expressive Core —
  installs without the Play Protect block; notification dots need Expressive Full."
- **Update feed:** Core reads its own manifest `updates:qa-v2-core/latest.json` (stable:
  `updates:release-core/latest.json`), so the in-app updater never swaps a Core user onto Full.
- **Pipeline:** the QA build job assembles both APKs from the same sealed source; the seal, verification,
  smoke QA and publication cover both. The GitHub release attaches both APKs, clearly named
  (`…-Core.apk`, `…-Full.apk`). Weekly gate and stable follow the same pattern.
- **Docs/notes:** README and release notes explain which APK to pick; XDA/Telegram posts link both.

## Phases
1. App: build property, manifest overlay, BuildConfig gating, settings copy, unit tests, manifest
   contract test; local Core APK verified with `aapt2` (no flagged services) and on the emulator.
   **Done 2026-10-05 (branch `claude/core-build`):** `-PexpressiveCore=true` → `ExpressiveLauncherL3-Core.*.apk`;
   `aapt2` shows no accessibility/notification-listener/SMS declarations (only BIND_DEVICE_ADMIN and
   BIND_JOB_SERVICE); `ExpressiveCore` rules + `ExpressiveCoreTest`; `ci/tests/test_core_manifest.py`
   (fails on Full/Core drift). Emulator: dots section explains Core; double-tap sleep goes to device admin.
   Also fixed a pre-existing bug: the device-admin prompt reused the accessibility dialog text ("To To …
   accessibility service"). Gesture/sleep pickers are Pro-gated, so their filtering is unit-tested only.
2. Pipeline: Jenkins build/publish for both APKs, feeds, publisher and weekly-gate tests (sealed
   evidence for each). Reconfigure Jenkins jobs (user approval each time).
3. Release: first QA with Core plus at least two other improvements; ask an enforcing-region user
   (Georgery) to confirm a browser install of Core has no block.

## Open questions
- Should Full stay the default download and Core be offered for affected regions, or the reverse?
- Should the Obtainium link point to Full or Core? (Obtainium can filter assets by name regex.)

## Decisions (2026-10-05)
- Full stays the default download and the Obtainium target; Core is offered for affected regions.
- Each QA version gets a separate Core prerelease (`qa-core-v<VER>-<CODE>`, Core APK only) and its
  own feed (`updates:qa-v2-core/latest.json`); Full's release, feed, stable gate and Obtainium link
  are unchanged. Core is built on every QA build. Core stable is deferred.

## Status
- Phase 1 (app): done on `claude/core-build` (`5caff5b`).
- Phase 2 (pipeline): implemented on `claude/core-build`; see `docs/CI_PIPELINE.md` → Expressive Core.
  Remaining: reconfigure `expressive-qa-build` and create `expressive-qa-core-publish`
  (`control.py configure`, user approval), then merge via PR.

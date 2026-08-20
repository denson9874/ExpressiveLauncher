# Expressive Launcher L3

Expressive Launcher L3 is an ordinary Android Home application built on the real
Launcher3 architecture through [Lawnchair 16](https://github.com/LawnchairLauncher/lawnchair).
It keeps Launcher3 authoritative for the workspace, favorites database, All Apps,
folders, widgets, drag and drop, restore, profiles, and launcher state.

The product package is `dev.launcher.expressive.l3`. Debug builds add the normal
`.debug` suffix and can coexist with both the archived prototype and upstream
Lawnchair.

## Foundation

- Lawnchair baseline: `eed2baf4efe4cf49540cf4ec474942dc743b83cc`
- AOSP Launcher3 Android 17 r1 source: `android-17.0.0_r1`
- Shipping model: standard Home-role APK; SystemUI remains responsible for Recents
  and system gesture navigation
- UI architecture: Launcher3 Views for launcher-critical surfaces and Lawnchair
  Compose for settings and auxiliary customization surfaces

The green product branch is `codex/launcher3-rebase`. The original bespoke launcher is
preserved on `codex/archive-bespoke-2026-08-20` and is not copied into the shipping
Launcher3 module.

The Android 17 core integration is staged separately on `codex/android17-port` so the
working product baseline remains buildable throughout the upstream port. See
[docs/ANDROID17_PORT.md](docs/ANDROID17_PORT.md) for exact provenance, the verified
vendor bridge, conflict inventory, subsystem gates, and completion criteria. The
presence of that bridge does not mean the Android 17 core merge is complete.

## Product customizations

- Lawnchair icon-pack infrastructure, custom masks, calendar icons, and Minimal icons
- Lawnicons as the first-run default when installed, with System icons as fallback
- Wallpaper-derived Material 3 Expressive colors and Lawnchair's live preview pipeline
- Pixel-first Home settings with customization grouped under Advanced
- Animated settings category glyphs that honor the system animation setting
- Wallpaper & style handoff to the resolvable OEM/AOSP picker with system fallback
- Lawnchair Smartspacer, QSB/search, profile, and Private Space extension points
- A minimal transient All Apps scrollbar that overlays rather than resizing the grid

## Build

The current development artifact is:

```sh
./gradlew assembleLawnWithQuickstepExpressiveDebug
```

Output:

```text
build/outputs/apk/lawnWithQuickstepExpressive/debug/
  ExpressiveLauncherL3.16.Dev.(eed2baf).expressive.debug.apk
```

This source snapshot requires Android SDK 37.1 and Java 21-compatible compilation.
The local verification environment builds with JDK 26 while targeting Java 21.

## Verification policy

Every Launcher3 port checkpoint must keep the branded baseline buildable and must not
replace Launcher3 workspace/model behavior with a second implementation. Acceptance
includes Home, All Apps, folders, drag/drop, widgets, restore, rotation, process death,
profiles, icon packs, wallpaper color response, accessibility, and performance on an
Android 17 Pixel 8 Pro or an equivalent disposable test target.

AOSP Tradefed launcher tests can change global device state and must only run on a
disposable AOSP test image, never on a personal device.

## Upstream and licensing

Expressive Launcher L3 derives from:

- [Lawnchair](https://github.com/LawnchairLauncher/lawnchair), a Launcher3-based open
  source launcher with extensive customization infrastructure
- [AOSP Launcher3](https://android.googlesource.com/platform/packages/apps/Launcher3),
  Android's reference Home implementation

Upstream commit history and source notices are retained. Source files remain under
their existing Apache License 2.0 headers, and the repository-level [LICENSE](LICENSE)
continues to apply.

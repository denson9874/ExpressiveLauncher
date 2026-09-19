![Expressive Launcher: a Pixel-inspired Android Home experience](docs/assets/expressive/hero-bloom.svg)

<p align="center">
  <img src="docs/assets/expressive/expressive-bloom.png" width="192" height="192" alt="Expressive Bloom: interlocking violet, blue, coral, and mint petals on a deep-indigo background" />
</p>

<p align="center">
  <strong><a href="https://github.com/denson9874/ExpressiveLauncher/releases/tag/v2.0.8-26">Download 2.0.8 Stable</a></strong> ·
  <strong><a href="https://github.com/denson9874/ExpressiveLauncher/releases">All releases</a></strong> ·
  <strong><a href="#whats-new-in-version-208-stable">What's new</a></strong> ·
  <strong><a href="docs/BUILDING.md">Build from source</a></strong> ·
  <strong><a href="docs/FEATURE_COMPARISON.md">Compare features</a></strong> ·
  <strong><a href="docs/REUSE_AND_ATTRIBUTION.md">Reuse and attribution</a></strong> ·
  <strong><a href="https://github.com/denson9874/ExpressiveLauncher/issues">Report an issue</a></strong>
</p>

# Expressive Launcher

**A focused, Pixel-inspired Home experience for Android 12 through Android 17.** Expressive builds on the real [Launcher3](https://android.googlesource.com/platform/packages/apps/Launcher3/) foundation through [Lawnchair](https://github.com/LawnchairLauncher/lawnchair), keeping its familiar Home screens, app drawer, folders, widgets, and customization while refining everyday setup, interactions, and updates.

This repository contains the **application source, build instructions, signed release and QA downloads, and release verification files**. The stable package is `dev.launcher.expressive.l3`, compatible with devices from Android 12 (API 31) through Android 17 (API 37). See [source provenance](docs/SOURCE_PROVENANCE.md) for the upstream baseline and the relationship to published builds.

## Get Expressive

**Featured Stable build: [Expressive Launcher 2.0.8 Stable — The Grand Unification](https://github.com/denson9874/ExpressiveLauncher/releases/tag/v2.0.8-26)** · Android 12+ (API 31–37) · Signed with the official Expressive release certificate.

| File | What you get |
| --- | --- |
| **[Download the APK](https://github.com/denson9874/ExpressiveLauncher/releases/download/v2.0.8-26/ExpressiveLauncherL3-2.0.8-Android17-QPR2-Beta5-Jenkins-Release-release-signed.apk)** | Official installable Expressive Launcher 2.0.8 Stable APK, about 22.8 MB (23,943,857 bytes). |
| [Release notes](https://github.com/denson9874/ExpressiveLauncher/releases/tag/v2.0.8-26) | Complete release notes, changes since 1.0.16, humor, and riddle. |
| [Release report](https://github.com/denson9874/ExpressiveLauncher/releases/download/v2.0.8-26/ExpressiveLauncherL3-2.0.8-Android17-QPR2-Beta5-Jenkins-Release-release-signed-Release-report.md) | Build identity, automated test totals, and verification summary. |
| [Package metadata](https://github.com/denson9874/ExpressiveLauncher/releases/download/v2.0.8-26/ExpressiveLauncherL3-2.0.8-Android17-QPR2-Beta5-Jenkins-Release-release-signed-metadata.json) | Package, version, size, SHA-256, and signing-certificate information. |
| [Device test results](https://github.com/denson9874/ExpressiveLauncher/releases/download/v2.0.8-26/ExpressiveLauncherL3-2.0.8-Android17-QPR2-Beta5-Jenkins-Release-release-signed-release-result.json) | Results from the isolated Android upgrade and smoke tests from 1.0.16 to 2.0.8. |
| [Application source](https://github.com/denson9874/ExpressiveLauncher/tree/main) | Browse the project, resources, tests, and build scripts. |

**[All releases and older builds](https://github.com/denson9874/ExpressiveLauncher/releases)** · **[Download/file guide](downloads/README.md)** · **[Stable update manifest](https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/release/latest.json)** · **[QA update manifest](https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/qa-v2/latest.json)**

> **Looking for QA builds?**  
> Check out **[Expressive Launcher 2.0.8 QA](https://github.com/denson9874/ExpressiveLauncher/releases/tag/qa-v2.0.8-26)** (`qa-v2.0.8-26`) with direct download: [Download QA APK](https://github.com/denson9874/ExpressiveLauncher/releases/download/qa-v2.0.8-26/ExpressiveLauncherL3-2.0.8-Android17-QPR2-Beta5-Jenkins-QA-release-signed.apk) · [QA update manifest](https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/qa-v2/latest.json).

### Stable APK Verification
```text
SHA-256: f0368d2af067fd64eec9e688b813999d80e17d6e2ed16979d444671b56bff703
Size:    23,943,857 bytes
Package: dev.launcher.expressive.l3
Version: 2.0.8 (code 26)
```

## What's new in version 2.0.8 Stable

Version 2.0.8 leaps from the 1.0.16 stable series into the unified 2.0 architecture, delivering broader device compatibility, deeply requested customization controls, crucial bug fixes, and rooted gesture support:

- 📱 **Broad Android 12+ device compatibility (Issue #15):**
  - Minimum SDK lowered from 37 to **31** (Android 12), enabling full support for Android 12, 13, 14, 15, 16, and 17 (Baklava).
  - Restored APK Signature Schemes **v1 (JAR signing)** and **v3** alongside v2, ensuring seamless package installation across Android 12–15 without signature verification failures.
- 🎛️ **Dedicated Widget Preferences & custom corner radius (Issue #12):**
  - **Dynamic corner radius slider:** Adjust widget corner radius smoothly between **0 dp** (crisp, sharp corners) and **40 dp** (ultra-rounded), dynamically enforced across all home screen widgets via `RoundedCornerEnforcement`.
  - **Widget padding factor:** Fine-tune spacing and margins between widgets from **0%** to **200%**.
  - **Layout & sizing controls:** Toggle forced rounded corners, allow widget overlap, unlock unlimited sizing, and force-resize stubborn widgets under **Settings > Widgets** and **Home Screen > Widgets**.
- 🔍 **Instant Settings Search (Issue #13):**
  - Fast, responsive search bar directly in the Settings Dashboard header bar.
  - Multi-token keyword filtering indexing titles, summaries, categories, and keywords across all 12 preference sections with direct deep links to any setting.
- ⚡ **Quickstep & QuickSwitch root support (Issue #10):**
  - Full system recents, task switching, and gesture transitions when installed as a privileged component (`/system/priv-app`) via QuickSwitch.
  - **Zero-overhead safety:** Conditioned privileged background binding on `STATUS_BAR_SERVICE` permission checks with capped connection attempts, ensuring unrooted devices experience zero battery drain, zero background loops, and zero crashes.
- 💾 **Reliable Nova backup persistence (Issue #14):**
  - Resolved an issue where imported Nova Launcher backups were overwritten with default Google/AOSP favorites after a device reboot. Restored layouts now permanently persist.
- 🛡️ **Workspace & widget crash prevention (Issue #11):**
  - Fixed an unhandled `NullPointerException` crash in `UiThreadHelper` when changing icon size, grid dimensions, or display settings with active widgets on the workspace.
- 📐 **Dashboard navigation & tablet improvements:**
  - Restored reachable Advanced settings rows in the dashboard and improved two-pane tablet layout responsiveness.

## What you can do

- **Make Home your own.** Choose icon packs, shapes, fonts, colors, grid layouts, and other Lawnchair customization options, with Material 3 Expressive styling that can follow wallpaper and system colors.
- **Find things quickly.** Search apps, contacts, and the web; search inside preferences with instant settings search. Contact actions have clear Call and Message accessibility labels.
- **Keep useful information in view.** Use At a Glance for the date and available weather, with optional Smartspacer integration. Expressive adds a date-first fallback and recovery when the weather connection becomes stale.
- **Set up Google Discover from Home settings.** Install or update the bundled Expressive Feed support through a guided flow, then access Google's feed beside the first Home page.
- **Customize without losing your place.** Everyday controls are grouped in a Pixel-inspired Home settings layout, with additional controls together under Advanced. Category animations respect Android's animation setting.
- **Stay on a verified update path.** Enable update notifications, open a newer matching build, or snooze the reminder. The app validates the downloaded APK before handing installation to Android.

## How Expressive compares

![Comparison of Launcher3, Lawnchair 16, and Expressive, highlighting focused settings, weather recovery, guided Discover setup, and verified updates](docs/assets/expressive/feature-comparison.svg)

**[Read the accessible comparison table and supporting sources](docs/FEATURE_COMPARISON.md).**

Launcher3 supplies the launcher foundation. Lawnchair adds the customization toolkit, including icon packs, Material 3 Expressive theming, global search, and Smartspacer support. **Expressive's contribution is its focused settings and setup experience, dedicated widget corner roundness controls, instant settings search, reliable backup restore, safe Quickstep root support, selected interaction and accessibility refinements, Android 12–17 validation, and its own verified GitHub delivery flow.** Shared features are credited to their upstream projects; this is a comparison of implementations and priorities, not a performance ranking.

## Install and update

1. Use an Android 12 (API 31) or newer device and download the signed release APK from this repository's Releases.
2. Open the APK and approve installation in Android. If upgrading from stable 1.0.16, the update installs directly over the existing app with full data retention.
3. Select **Expressive Launcher L3** as your default Home app when prompted, or choose it in Android's **Default apps → Home app** settings.
4. Open **Home settings → About** to check for updates and enable update notifications. Grant notification permission when requested.

Stable releases use package name `dev.launcher.expressive.l3`. The in-app updater checks periodically when Android schedules it and a network is available. Before opening Android's installer, it checks the APK's **size, SHA-256, package, version, and signing lineage**. You confirm installation in the system interface. [Update and channel details](docs/DIRECT_DISTRIBUTION.md).

## What has been validated

The published **2.0.8 Stable** build passed complete automated unit test suites, all **266** CI pipeline and contract tests, and isolated Android 17 emulator upgrade tests from stable baseline 1.0.16 to 2.0.8. Verification confirmed same-signer upgrade, full preference retention, cold/warm start latency, home transitions, drawer swipes, local search, date handoff, and clean launcher crash logs. The downloadable [Release report](https://github.com/denson9874/ExpressiveLauncher/releases/download/v2.0.8-26/ExpressiveLauncherL3-2.0.8-Android17-QPR2-Beta5-Jenkins-Release-release-signed-Release-report.md) and [device results](https://github.com/denson9874/ExpressiveLauncher/releases/download/v2.0.8-26/ExpressiveLauncherL3-2.0.8-Android17-QPR2-Beta5-Jenkins-Release-release-signed-release-result.json) describe the automated build's scope.

Expressive runs as a standard Home app; root is not required for everyday use. For rooted devices running QuickSwitch in `/system/priv-app`, Quickstep components provide system recents and fluid gesture navigation, while remaining strictly inert with zero overhead on unrooted devices.

## Source and development

```sh
git clone --recurse-submodules https://github.com/denson9874/ExpressiveLauncher.git
cd ExpressiveLauncher
./gradlew --no-scan assembleLawnWithQuickstepExpressiveDebug
```

Use **JDK 21** and the **Android 37.1 SDK** (`minSdk 31`). The SystemUI submodule is required: ordinary GitHub ZIP downloads omit its contents. The [build guide](docs/BUILDING.md) includes dependency setup, output locations, test commands, and signing instructions.

| Project area | Contents |
| --- | --- |
| [`android/`](android/) | Application source, Android libraries, resources, and app tests. |
| [`ci/`](ci/) | Jenkins build, verification, and release tooling. |
| [`docs/`](docs/) | Build guides, architecture, security, and source provenance. |
| [`scripts/`](scripts/) · [`tools/`](tools/) | Signing setup, artwork, and developer utilities. |
| [`fastlane/`](fastlane/) | Store descriptions and artwork. |
| [`downloads/`](downloads/) | Download and release-verification information. |

Run Gradle from the repository root. The wrapper, dependency catalog, local SDK
configuration, signing inputs, and `build/` outputs retain their existing locations.
The [Android source guide](android/README.md) maps the folders inside `android/`.

For contributions, start with [CONTRIBUTING.md](CONTRIBUTING.md). Report Expressive issues in [this repository](https://github.com/denson9874/ExpressiveLauncher/issues).

## Credits and license

**Expressive-specific contributions are by Daryl Denson and the Expressive Launcher contributors.**
Reusing them carries the applicable license and attribution obligations. See the
[Expressive NOTICE](NOTICE), [reuse and attribution guide](docs/REUSE_AND_ATTRIBUTION.md),
and [contribution index](docs/SOURCE_PROVENANCE.md#expressive-contribution-index).

Expressive stands on the work of **AOSP Launcher3**, **Lawnchair**, and their contributors. It is an independent project; it is not an official Google, Pixel, or Lawnchair release.

The retained [Apache License 2.0 and AOSP/Lawnchair notices](LICENSE.txt) apply alongside each component's own notices. See [third-party notices](docs/THIRD_PARTY_NOTICES.md), including the Google Sans Flex font license.

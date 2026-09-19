# Integrated Google Discover — QA 1.0.9

On September 6, 2026, Jenkins built and staged Expressive Launcher **1.0.9 / code 10** with
Google Discover setup and management inside Home settings. The native Google panel attaches to
Expressive Home. Its included background helper has no separate feed screen or launcher activity.
Android confirms the helper installation; users do not need a separate download.

**Delivery status: staged and verified in the existing private QA folder.** This candidate has
not been promoted to the automatic QA update feed, which remains on released 1.0.8 / code 9.
Its personalized Discover content has not been demonstrated on a signed-in device.

[Download the staged QA APK](https://drive.google.com/file/d/1ESKs0X6EcfNEa-qHweB5vIEnRg4qwHu9/view)
using the existing folder access.
[Jenkins QA report](https://drive.google.com/file/d/1036-LTulYXnuW2Yjrp1XkArvx6yh1RiF/view).
[QA Drive folder](https://drive.google.com/drive/folders/1x9QK-ZRgIJUqXXekfxr_KEzGqN_A0TiZ).

## Exact identity

- Source: `e0e8bb2ea30b29d8768bdac6e97e5869e938f9ae` on `codex/pixel-parity`.
- Package: `dev.launcher.expressive.l3.debug`, minified, non-debuggable, durable-signed `Qa`.
- APK: `ExpressiveLauncherL3-1.0.9-Android17-QPR2-Beta4-Jenkins-QA-release-signed.apk`.
- Size: **21,793,376 bytes**.
- SHA-256: `d17d255871603b7e7d8666edb52a464df2016c75033aae7771f565b42e6184fc`.
- Certificate SHA-256: `c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2`.
- Bundled support: `dev.launcher.expressive.feed`, 1.0.9 / code 10, **37,077 bytes**, same certificate.
- Support SHA-256: `c1a037472b0cc496355af543c9f7fb1129498b66c908c38820622778a4b9a442`.

The included service-only helper retains its separate debuggable identity for Google overlay
compatibility. Expressive validates its archive, identity, certificate, byte size and digest before
offering installation. Jenkins validates the nested package as part of the release contract.
See [Google Discover integration](GOOGLE_DISCOVER.md) for behavior and platform constraints.

## Validation and delivery

| Check | Observed result |
| --- | --- |
| [Jenkins build #2](http://127.0.0.1:8091/job/expressive-qa-build/2/) | SUCCESS, 9m 56s. All 145 app tests, 51 pipeline checks and 11 standard isolated device checks passed. |
| Additional Discover integration | Ten scoped flows passed on the exact candidate: upgrade retention, identity, setup cancel/retry, system installation, native overlay, return Home, off/on persistence, direct Google control and clean application logs. |
| [Jenkins publish #2](http://127.0.0.1:8091/job/expressive-qa-publish/2/) | SUCCESS, 44s. APK, report, metadata and device result uploaded as versioned files and downloaded back for SHA-256 verification. Receipt status `staged-verified`; `promoteRequested=false`. |

The integration guest ran Android 17 QPR2 Beta 4 **CP41.260814.003.B1**, SDK 37, and Google app
**17.46.15.sa.arm64 / code 301786727**. Upgrading shipped 1.0.8 retained Home assignment, the
original installation time, the renamed **Feed QA** folder with all four apps and the enabled
Infinite scrolling preference. The launcher upgrade used the documented quiesced ADB path.

Support installation used **Home settings → Home screen → Google Discover → Set up Google
Discover**, followed by Android's source-specific permission and system installer. Cancellation
left support absent and showed a retry message; retry installed the exact bundled bytes. The
installer offered Done, and the installed support exposed no launcher activity. The per-APK
**Install without scanning** option was used for the known helper; global Play Protect, account
and network settings were unchanged.

After restarting the retained guest, the actual alphabetical app drawer had no helper icon or
synthetic app-info entry. Searching **Expressive Feed** and **Feed** returned no local helper
result while the helper remained installed. Those UI trees and screenshots supplement the
package-manager check; no web search suggestions were opened.

Swiping right displayed a Google-owned `GoogleDiscoverWindow` attached to Expressive's
`LawnchairLauncher` window and activity. Expressive remained the resumed Home activity. Turning
Discover off and on survived cold starts, and Infinite scrolling remained enabled. No fatal
exception, native/process crash or ANR was recorded for the launcher, helper or Google app.

Both the embedded panel and the standalone Google app showed **Couldn't connect to Discover**
on this signed-out guest. This supports a shared Google/guest content limitation; the precise
cause is unproven. Signed-in stories, physical devices, other Google-app versions, legacy
provider migration, helper-update recovery and the 1.0.9 live updater delivery were outside this
device run. The observed setup and native overlay results do not establish those cases.

## Retained evidence

The sealed build remains unchanged at:
`/path/to/ExpressiveCI/releases/qa-1.0.9-10-build-2/`.

The publication receipt is:
`/path/to/ExpressiveCI/agent/workspace/expressive-qa-publish/publication-2.json`.
It finished at **2026-09-06 15:18:55 UTC**. The four uploaded objects retain existing private-folder
access. Earlier versioned artifacts remain available.

The detailed integration report, result, UI trees, screenshots, package/window evidence and logs
are retained in `artifacts/embedded-feed-20260906/runtime/candidate/`. A durable copy and the
publication receipt are retained beside the CI release under
`/path/to/ExpressiveCI/delivery/qa-1.0.9-10-build-2/`.
Owned emulator processes were stopped after validation; their AVDs and evidence were preserved.

Jenkins owns the build, test execution and verified artifact delivery. The additional device
exploration establishes the new integration's observed behavior and its content limitation.

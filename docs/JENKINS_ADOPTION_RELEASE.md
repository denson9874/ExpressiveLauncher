# Jenkins adoption and QA 1.0.8 release

On September 6, 2026, Jenkins built and released Expressive Launcher **1.0.8 / versionCode 9**
on the existing QA channel. This release includes the retained Smartspace date and swipe-release
repairs. The user's request to adopt a CI solution and release the next build superseded the earlier
publication hold for this candidate.

## Verified release identity

- Source commit: `92ea47b6e2d159d87fce1ec2bbbbad7d258ff4cb` on `codex/pixel-parity`.
- SystemUI submodule: `e12acf0978875fc4adcebf46207b766106ffc92b`.
- Package: `dev.launcher.expressive.l3.debug`, minified, non-debuggable, durable-release-signed `Qa`.
- APK: `ExpressiveLauncherL3-1.0.8-Android17-QPR2-Beta4-Jenkins-QA-release-signed.apk`.
- Size: **21,748,543 bytes**.
- SHA-256: `f4b730064dc69d503ddaeb6652f3b86225f680b9f520991bfb80f46ba30aea66`.
- Signing certificate SHA-256: `c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2`.
- Baseline: shipped 1.0.7 / code 8, matching signing identity.

[Download the QA APK](https://drive.usercontent.google.com/download?id=1IG12Z7eTZygb2FXyE2fQufz3l8Vk_VZS&export=download&confirm=t).
[QA Drive folder](https://drive.google.com/drive/folders/1x9QK-ZRgIJUqXXekfxr_KEzGqN_A0TiZ).
[Uploaded QA report](https://drive.google.com/file/d/1_BpRv35YbhGpXcdiw6TviCe8l2IVZPTi/view).

## Jenkins results

| Job | Result | Evidence |
| --- | --- | --- |
| [Build #1](http://127.0.0.1:8091/job/expressive-qa-build/1/) | SUCCESS, 13m 22s | 45 pipeline checks; 132 app tests across 31 suites, zero failures/errors/skips; signed package verification; 11 device checks |
| [Publish #1](http://127.0.0.1:8091/job/expressive-qa-publish/1/) | SUCCESS, 59s | Four versioned files uploaded and downloaded for verification; public APK size/SHA-256 verified; existing QA feed updated and read back |

The actual isolated guest was Android 17 QPR2 Beta 4 `CP41.260814.003.B1`, SDK 37. Automated
device checks covered guest identity, baseline install, a seeded preference, matching-signer
upgrade, preference retention, warm/cold startup, drawer swipe, local search, current-date Calendar
handoff, and clean app crash/ANR logs. All 58 listed evidence files were retained.

The sealed candidate is retained under:
`/path/to/ExpressiveCI/releases/qa-1.0.8-9-build-1/`.
The release receipt is retained at:
`/path/to/ExpressiveCI/agent/workspace/expressive-qa-publish/publication-1.json`.
An additional copy and public feed snapshot are in `artifacts/ci-adoption-20260906/`.

The publisher finished at **2026-09-06 13:19:02 UTC** with `status=released`. The existing QA feed
object `1_A529DlPEMzwizq-6j3fpMBElGugY-_J` now points to the APK above. The APK is link-readable;
the accompanying report, metadata and device result retain private folder access. Previous
versioned files were preserved. Production distribution is outside this QA release.

## Actual in-app delivery

A second isolated Beta 4 guest started from the shipped 1.0.7 APK and used the real published feed.
Notification opt-in produced the actual 1.0.8 update notification; tapping it opened the matching QA
prompt. Selecting the one-hour snooze scheduled the expected delayed job, which survived the
subsequent process/package replacement. The hour-later firing itself was not awaited or simulated.

The normal About flow downloaded the released APK, logged successful SHA-256 verification and
opened Android's installer. The test granted this app's install-source permission and selected the
per-APK "Install without scanning" option on Play Protect's first-seen-app prompt. Global Play
Protect and account settings were unchanged; the APK was not submitted for a Google scan.
Android displayed **App updated**.

The installed package is 1.0.8/code 9 and its pulled APK hashes to the exact Jenkins digest above.
The first install timestamp remained `2026-09-06 09:09:09`; HOME assignment, the renamed
`CI-Live-Upgrade` folder, all four item positions and the enabled Infinite scrolling preference
survived. The retained final crash buffer is empty and the launcher log has no fatal exception or ANR.

The detailed report, machine-readable result, screenshots, UI trees, package/hash checks and logs
are in `artifacts/ci-live-updater-20260906/LIVE-UPDATER-QA.md` and
`artifacts/ci-live-updater-20260906/live-updater-result.json`. This delivery check used the normal
download/system-installer path, with no substitute feed and no ADB installation of the candidate.

## Adopted responsibilities

Jenkins LTS 2.568.3 now runs build and publication jobs on a dedicated local worker. Gradle handles
compilation/tests and rclone 1.75.1 handles Drive transport. Codex handles research, implementation,
exploratory testing and diagnosis. Failed publication can retry the sealed candidate without
rebuilding, changing its version, or discarding source work.

The existing Monday/Wednesday/Friday 03:00 Pixel-parity automation is active with its schedule,
project and model settings preserved. Its updated prompt submits candidate commits to Jenkins
and uses the separate publication job. Its future runs retain upload-only scope; promotion of
future candidates requires a specific release instruction.

See [CI_PIPELINE.md](CI_PIPELINE.md) for the platform comparison, service configuration, credential
locations, run/retry commands and maintenance. The Mac must remain awake and logged in for Jenkins
jobs. Jenkins and its worker run as the same macOS user, with separate processes/directories and
authenticated loopback access. Application-specific validation scripts still require maintenance.

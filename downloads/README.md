# Downloads and release files

[Browse every release](https://github.com/denson9874/ExpressiveLauncher/releases) for versioned APKs and verification files. QA builds are marked **Pre-release**. Keep the same channel when updating an existing installation.

## Expressive Launcher 2.0.8 Stable — version code 26

| Download | Purpose |
| --- | --- |
| [Installable APK](https://github.com/denson9874/ExpressiveLauncher/releases/download/v2.0.8-26/ExpressiveLauncherL3-2.0.8-Android17-QPR2-Beta5-Jenkins-Release-release-signed.apk) | Signed Release app for Android 12 (API 31) through Android 17 (API 37). |
| [Release notes](https://github.com/denson9874/ExpressiveLauncher/releases/tag/v2.0.8-26) | Full release notes, changes since 1.0.16, humor, and riddle. |
| [Release report](https://github.com/denson9874/ExpressiveLauncher/releases/download/v2.0.8-26/ExpressiveLauncherL3-2.0.8-Android17-QPR2-Beta5-Jenkins-Release-release-signed-Release-report.md) | Build and verification summary. |
| [Package metadata](https://github.com/denson9874/ExpressiveLauncher/releases/download/v2.0.8-26/ExpressiveLauncherL3-2.0.8-Android17-QPR2-Beta5-Jenkins-Release-release-signed-metadata.json) | Version, package, source identity, size, hash, and certificate. |
| [Device test results](https://github.com/denson9874/ExpressiveLauncher/releases/download/v2.0.8-26/ExpressiveLauncherL3-2.0.8-Android17-QPR2-Beta5-Jenkins-Release-release-signed-release-result.json) | Isolated upgrade and smoke-check evidence. |

APK size: **23,943,857 bytes**. SHA-256:

```text
f0368d2af067fd64eec9e688b813999d80e17d6e2ed16979d444671b56bff703
```

Stable updates are selected by the [Release manifest](https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/release/latest.json), while pre-release QA updates use the [QA manifest](https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/qa-v2/latest.json).

## Application source

The full application source is on [`main`](https://github.com/denson9874/ExpressiveLauncher/tree/main). Use a recursive clone to include the pinned SystemUI dependency; [build instructions](../docs/BUILDING.md) explain the requirements.

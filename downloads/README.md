# Downloads and release files

[Browse every release](https://github.com/denson9874/ExpressiveLauncher/releases) for versioned APKs and verification files. QA builds are marked **Pre-release**. Keep the same channel when updating an existing installation.

## Expressive Launcher 1.0.12 QA — version code 13

| Download | Purpose |
| --- | --- |
| [Installable APK](https://github.com/denson9874/ExpressiveLauncher/releases/download/qa-v1.0.12-13/ExpressiveLauncherL3-1.0.12-Android17-QPR2-Beta4-Jenkins-QA-release-signed.apk) | Signed QA app for Android 17 / API37 or newer. |
| [QA report](https://github.com/denson9874/ExpressiveLauncher/releases/download/qa-v1.0.12-13/ExpressiveLauncherL3-1.0.12-Android17-QPR2-Beta4-Jenkins-QA-release-signed-QA-report.md) | Build and verification summary. |
| [Package metadata](https://github.com/denson9874/ExpressiveLauncher/releases/download/qa-v1.0.12-13/ExpressiveLauncherL3-1.0.12-Android17-QPR2-Beta4-Jenkins-QA-release-signed-metadata.json) | Version, package, source identity, size, hash, and certificate. |
| [Device test results](https://github.com/denson9874/ExpressiveLauncher/releases/download/qa-v1.0.12-13/ExpressiveLauncherL3-1.0.12-Android17-QPR2-Beta4-Jenkins-QA-release-signed-qa-result.json) | Isolated upgrade and smoke-check evidence. |

APK size: **21,794,680 bytes**. SHA-256:

```text
778bb1720151c4b486ddc5d400fb0b69bc2c725cd692f2d246cf17dc6ebf88a1
```

These existing assets are retained unchanged. Future published QA offers are selected by the [QA manifest](https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/qa/latest.json), so use the Releases page or the app's updater to find newer builds.

## Application source

The full application source is on [`main`](https://github.com/denson9874/ExpressiveLauncher/tree/main). Use a recursive clone to include the pinned SystemUI dependency; [build instructions](../docs/BUILDING.md) explain the requirements. The historical `qa-v1.0.11-12` and `qa-v1.0.12-13` release tags predate application-source publication, so their automatically generated **Source code (zip/tar.gz)** files contain the earlier exports-only repository. Use `main` and its source provenance for this application snapshot. Original release tags and assets remain preserved.

# Expressive Launcher

Signed Android build exports and update channels for Expressive Launcher.

[Download builds and read release notes](https://github.com/denson9874/ExpressiveLauncher/releases)

## Choose your channel

| Channel | Android package | Releases |
| --- | --- | --- |
| QA | `dev.launcher.expressive.l3.debug` | Prereleases for testing |
| Stable | `dev.launcher.expressive.l3` | Stable releases, published separately |

Install the channel matching your existing app. QA and stable releases do not update each other.
Use the release-signed QA APK for upgrades; developer Debug APKs use a different signing key.

## Update notifications

GitHub-enabled builds check their own channel from About and, when update notifications are enabled,
periodically while a network is available. Only a newer matching build produces an alert. You can
snooze an update or open its download prompt. The app verifies the complete APK's size, SHA-256,
package, version, and signing lineage before Android asks you to confirm installation.

The `updates` branch holds the published channel manifests. A draft release is not offered to users.
Jenkins advances a channel only after the signed build passes tests and the public download is verified.

## Build verification and provenance

Each release retains its APK, QA report, package metadata, and device results. Metadata records the
application source commit and SHA-256. Assets are versioned and prior builds are retained.
GitHub's generated source archives contain this exports repository; they are not application source archives.

Expressive Launcher is based on [Lawnchair](https://github.com/LawnchairLauncher/lawnchair) and
[Android Launcher3](https://android.googlesource.com/platform/packages/apps/Launcher3/).
See [LICENSE.txt](LICENSE.txt) for the retained AOSP/Lawnchair notices and Apache 2.0 license.
Bundled components retain their respective notices in the application.

# Build Expressive Launcher

This guide covers the public source for **Expressive Launcher 1.0.12 / version code 13**,
exported from product revision `ad7a5c336423df6edb198a700a050117808c96f6`.
Use the [GitHub releases](https://github.com/denson9874/ExpressiveLauncher/releases)
for official signed APKs. A local build is useful for development and testing.

## Requirements

| Dependency | Required configuration |
| --- | --- |
| Java | JDK 21; point `JAVA_HOME` or Android Studio's Gradle JDK to it |
| Android SDK | Compile platform API 37, minor API level 1 (Android SDK 37.1), plus Android SDK Build-Tools and Platform-Tools |
| Gradle | Use the included wrapper, pinned to **9.7.0**; a separate Gradle installation is unnecessary |
| Device or emulator | **Android 17 / API 37 or newer**; the app's minimum and target SDK are both 37 |
| Git | Submodule support and access to GitHub |
| Network | Needed to obtain the wrapper, Android components and Maven dependencies on the first build |

The build inputs are declared in [build.gradle](../build.gradle),
[the version catalog](../gradle/libs.versions.toml), and
[the wrapper configuration](../gradle/wrapper/gradle-wrapper.properties).
Install the requested SDK components through Android Studio's SDK Manager. Configure
the SDK with an ignored `local.properties` file containing `sdk.dir=...`, or with
your normal Android SDK environment configuration.

## Get the source and its submodule

```sh
git clone --recurse-submodules https://github.com/denson9874/ExpressiveLauncher.git
cd ExpressiveLauncher
```

For an existing clone, or after changing revisions:

```sh
git submodule sync --recursive
git submodule update --init --recursive
git submodule status --recursive
```

The required submodule is `platform_frameworks_libs_systemui`, from
[LawnchairLauncher/platform_frameworks_libs_systemui](https://github.com/LawnchairLauncher/platform_frameworks_libs_systemui).
For this source snapshot, its recorded revision is
`e12acf0978875fc4adcebf46207b766106ffc92b`. The checked-in gitlink is authoritative;
use `git submodule update` to restore that revision rather than updating to the
latest upstream branch. Its URL is recorded in [.gitmodules](../.gitmodules).

## Build a developer APK

Confirm the Java runtime, then use the project wrapper:

```sh
java -version
./gradlew --version
./gradlew assembleLawnWithQuickstepExpressiveDebug
```

The developer APK is written to:

```text
build/outputs/apk/lawnWithQuickstepExpressive/debug/*.apk
```

On Windows, run the equivalent commands with `gradlew.bat`.
The task name contains `WithQuickstep` because Lawnchair shares those implementation
classes. Expressive's installable product is a standard Home app: Android SystemUI
continues to provide system gesture navigation and Recents. Root and QuickSwitch
are not required.

The default developer application ID is `dev.launcher.expressive.l3.debug`.
Official QA APKs use that same ID with a different, durable signing certificate.
**A locally debug-signed APK cannot update an official signed QA installation.**
Use a separate development emulator for the default developer build so that your
existing launcher installation and Home data remain available.

## Run the app tests

Run the complete Expressive unit-test suite:

```sh
./gradlew testLawnWithQuickstepExpressiveDebugUnitTest
```

For example, run only the At a Glance setup and widget-binding regression tests:

```sh
./gradlew testLawnWithQuickstepExpressiveDebugUnitTest \
  --tests app.lawnchair.smartspace.provider.SmartspaceSetupCoordinatorTest \
  --tests app.lawnchair.HeadlessWidgetBindingTest
```

Results are written to
`build/reports/tests/testLawnWithQuickstepExpressiveDebugUnitTest/` and
`build/test-results/testLawnWithQuickstepExpressiveDebugUnitTest/`.
The suite includes Android-resource/Robolectric tests under
[tests/expressiveUnit](../tests/expressiveUnit). Unit-test success does not replace
installation, upgrade and visible feature testing on an Android device or emulator.

## Build with your own signing identity

The `Qa` and `Release` variants require a complete local signing configuration;
they do not fall back to Android's debug certificate.

```sh
cp keystore.properties.example keystore.properties
```

Edit the ignored `keystore.properties` with your own keystore path, alias and
passwords, following [the example](../keystore.properties.example). The supported
keys are `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. The build can
also read a properties file selected by `EXPRESSIVE_KEYSTORE_PROPERTIES`.
Signing keys and credentials are not included in this repository and must remain
outside commits and exports.

With your configuration present:

```sh
./gradlew assembleLawnWithQuickstepExpressiveQa
```

The result is a minified, non-debuggable APK under
`build/outputs/apk/lawnWithQuickstepExpressive/qa/`. Your own signer does not confer
the ability to update official Expressive APKs. For independently distributed
forks, configure your own application identity, update endpoints and signing
lineage using the product properties in [build.gradle](../build.gradle).

## Official delivery and port status

Official QA delivery uses the project's Jenkins build and publication pipeline:
full tests, signed assembly, isolated upgrade validation, artifact sealing and
GitHub publication. Building the source locally does not publish a release. The
inherited GitHub workflow files are retained for reference under
[docs/reference/upstream-github](reference/upstream-github); they are not the
active Expressive release service.

Expressive runs on Android 17 while retaining its Lawnchair-derived Launcher3
foundation. The separate full AOSP Android 17 core port is **not complete**.
[The port notes](ANDROID17_PORT.md) record its provenance and remaining gates.
This guide describes the current source and developer build, not a completed
Play Store or stable-production certification.

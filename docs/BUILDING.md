# Build Expressive Launcher

This guide covers building **Expressive Launcher** from source.
For official signed APKs, use the [GitHub Releases](https://github.com/denson9874/ExpressiveLauncher/releases).

## Requirements

| Dependency | Required configuration |
| --- | --- |
| Java | JDK 21; point `JAVA_HOME` or Android Studio's Gradle JDK to it |
| Android SDK | Compile platform API 37, minor API level 1 (Android SDK 37.1), plus Android SDK Build-Tools and Platform-Tools |
| Gradle | Use the included wrapper, pinned to Gradle 9; a separate Gradle installation is unnecessary |
| Device or emulator | **Android 12 (API 31) through Android 17 (API 37)**; `minSdk 31`, `targetSdk 37`, `compileSdk 37` |
| Git | Submodule support and access to GitHub |
| Network | Needed to obtain the wrapper, Android components, and Maven dependencies on the first build |

Build inputs are declared in [build.gradle](../build.gradle),
[gradle/libs.versions.toml](../gradle/libs.versions.toml), and
[gradle/wrapper/gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties).
Install the requested SDK components through Android Studio's SDK Manager or CLI `sdkmanager`. Configure
the SDK path with a `local.properties` file containing `sdk.dir=...`, or through the `ANDROID_HOME` / `ANDROID_SDK_ROOT` environment variable.

## Get the source and submodules

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
Its gitlink is authoritative and tracked in [.gitmodules](../.gitmodules).

## Build a developer APK

```sh
./gradlew assembleLawnWithQuickstepExpressiveDebug
```

The developer APK is written to:

```text
build/outputs/apk/lawnWithQuickstepExpressive/debug/
```

On Windows, run the equivalent commands with `gradlew.bat`.

The default developer application ID is `dev.launcher.expressive.l3.debug`.
Official QA APKs use that same ID signed with the official QA release certificate, while official stable builds use `dev.launcher.expressive.l3`.

## Run the unit tests

Run the complete Expressive unit-test suite:

```sh
./gradlew testLawnWithQuickstepExpressiveDebugUnitTest
```

Results are written to `build/reports/tests/testLawnWithQuickstepExpressiveDebugUnitTest/`.

## CI Pipeline verification tests

Run the CI contract and release gate verification tests:

```sh
python3 -m unittest discover -s ci/tests -v
```

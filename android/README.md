# Android source

This folder contains the Expressive Launcher application, its Android libraries,
resources, and tests. The inherited Launcher3 and Lawnchair source-set names stay
together so upstream changes can still be compared and imported.

| Area | What lives here |
| --- | --- |
| [lawnchair/](lawnchair/) | Launcher customization, settings, search, and Expressive behavior. |
| [src/](src/) · [quickstep/](quickstep/) | Launcher3 core and shared Quickstep implementation. |
| [expressive/](expressive/) · [expressiveFeed/](expressiveFeed/) | Expressive branding, product manifest, and bundled Discover helper. |
| [tests/](tests/) · [baseline-profile/](baseline-profile/) | App regression tests, instrumentation, and benchmark/profile generation. |
| [res/](res/) · [protos/](protos/) · [schemas/](schemas/) | Shared resources, protobuf definitions, and Room database schemas. |
| [systemUI/](systemUI/) · [platform_frameworks_libs_systemui/](platform_frameworks_libs_systemui/) | SystemUI integration and its pinned upstream submodule. |
| [compatLib/](compatLib/) · [hidden-api/](hidden-api/) · [prebuilts/](prebuilts/) | Platform compatibility code and compile-time framework inputs. |
| [build.gradle](build.gradle) | Launcher variants, source sets, dependencies, and shared Android build configuration. |

## Build from the repository root

```sh
./gradlew assembleLawnWithQuickstepExpressiveDebug
./gradlew testLawnWithQuickstepExpressiveDebugUnitTest
```

Open the repository root in Android Studio. The root [settings.gradle](../settings.gradle)
maps existing project names to this folder, so task names stay the same, including
`:baseline-profile:assembleLawnWithQuickstepExpressiveBenchmark`. The wrapper,
version catalog, `local.properties`, and `keystore.properties` remain at the root.
Launcher APKs and test reports are still written under the root `build/` folder;
library and helper outputs live in each module's `build/` folder.

For a clone created before the layout change, commit or stash your work before
updating, then synchronize the moved submodule:

```sh
git submodule sync --recursive
git submodule update --init --recursive
```

Git may leave an untracked copy of the old top-level submodule directory during
the checkout. Check it for local work before removing it. The active submodule is
now `android/platform_frameworks_libs_systemui` at the same pinned revision.

See the [build guide](../docs/BUILDING.md) for SDK setup, signing, outputs, and
validation requirements. The [root NOTICE](../NOTICE) and component license files
continue to apply to the relocated source.

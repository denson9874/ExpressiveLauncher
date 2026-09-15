# Code Scanning review: Expressive source

This review covers the 37 alerts reported against `0bb9673` and rechecks their
source paths after dependency remediation in `e04051a`. The reported production
code is unchanged. Source files have since moved under `android/`; the alert
numbers and reviewed commit references below describe the original occurrences.
Findings were traced through their direct callers and the
Expressive Gradle source sets, including SystemUI submodule commit
`e12acf0978875fc4adcebf46207b766106ffc92b`.

These are applicability decisions for the listed occurrences, not blanket
exceptions for a query or a guarantee about every shipped APK. CodeQL remains
enabled for Java/Kotlin, Swift, Python, and GitHub Actions.

## Archive extraction: alert 1

`NovaBackupConverter.extractFromZip()` accepts only exact filenames supplied by
its two callers: preview requests `nova.xml` and `nova.db`; restore requests
`nova.db`. Both use fresh UUID directories in the private application cache and
clean up in `finally`. The extractor also requires the canonical output path to
start with the canonical destination **plus a separator**, before opening the
output file. It does not restore symlinks from ZIP metadata.

**Classification: false positive; no production change.** Traversal, absolute,
nested, encoded, and alternate-separator names fail the exact allowlist. An
existing symlink targeting outside the destination fails canonical containment.
Unrelated entries are intentionally skipped so ordinary Nova archives remain
compatible.

`NovaBackupExtractionSecurityTest` exercises the actual extraction method with
malicious entries and valid controls, database-only extraction, and an outside
symlink target in a sibling directory sharing the destination prefix.

## PendingIntents: alerts 2–4

The [reported query](https://codeql.github.com/codeql-query-help/java/java-android-implicit-pendingintents/)
describes modification of an implicit **mutable** token. All reported tokens use
`FLAG_IMMUTABLE`; Android ignores a recipient's fill-in intent for such tokens.
`FLAG_UPDATE_CURRENT` permits updates by the creator, not mutation by recipients.
See [Android's contract](https://developer.android.com/reference/android/app/PendingIntent#FLAG_IMMUTABLE).

| Alert | Existing enforcement | Compatibility |
| --- | --- | --- |
| 2 | `BugReportReceiver.notify()` makes every activity/broadcast token immutable; copy/upload broadcasts specify the application package, and the receiver is not exported. | Keep browser/viewer/chooser selection and private copy/upload actions. |
| 3 | `ExpressiveUpdateNotifications.post()` uses an immutable token; `PreferenceActivity.createIntent()` also specifies the activity class explicitly. | Keep the About route and update-prompt extra. |
| 4 | `LawnchairUtils.restartLauncher()` uses an immutable alarm token. Its production callers use the wrapper that locally constructs the HOME or package-launch intent. | Keep restart after launcher process exit. |

**Classification: false positives; no production changes.** The notification
tests inspect the tokens actually produced by the two notification builders and
send hostile fill-in intents to activity tokens. They check the original
destination and legitimate prompt/report behavior. The restart classification
uses source/caller evidence and Android's immutability contract; the test suite
does not execute its process-killing restart path.

## Settings provider: alerts 5–6

`LauncherProvider.query()` accepts SQL projection, selection, bound arguments,
and sorting from provider clients. `ModelDbController.query()` forwards those
parameters to SQLite. This is a real data path, but it is reserved for trusted
clients: the provider declares both READ_SETTINGS and WRITE_SETTINGS permissions,
and the Expressive variant replaces both declarations with `signature`
protection. Android checks the provider read permission before allowing a query.
See [provider permission enforcement](https://developer.android.com/guide/topics/manifest/provider-element#rprmsn).

There are no URI grants or weaker path permissions. The other direct model-query
caller, `LoaderTask`, is internal. Item URI identifiers are parsed with
`ContentUris.parseId()` before entering a selection.

**Classification: false positives for the Expressive trust boundary; no
production changes.** Same-signature clients intentionally retain query
semantics. The merged-manifest guard verifies both permissions, their signature
protection, the authority, and absence of permission overrides for QA and
Release. It also checks the private bug-report receiver. This is manifest and
platform-contract verification, not a new cross-UID device penetration test.

## Debuggable source/test manifests: alerts 7–16

| Alert | Manifest | Why it does not enable debugging in the distributed launcher |
| --- | --- | --- |
| 7 | `android/AndroidManifest.xml` | Gradle main uses `android/AndroidManifest-common.xml`; the Expressive combined source set uses `android/expressive/AndroidManifest-launcher.xml`. The reported AOSP manifest is excluded. |
| 8–9 | Pinned submodule `mechanics/compose/tests` and `mechanics/tests` | Instrumentation manifests; the mechanics module selects its root manifest for main. |
| 10 | Pinned submodule `viewcapturelib/tests` | Assigned to `androidTest`, with a separate main manifest. |
| 11 | `android/systemUI/animation/lib/tests` | Test-only instrumentation manifest; animation main selects its root manifest. |
| 12 | `android/systemUI/viewcapture/tests` | Assigned to `androidTest`; this legacy project is also absent from Gradle settings, which include the pinned submodule's viewcapture library. |
| 13 | `android/tests/AndroidManifest.xml` | Upstream instrumentation manifest; Expressive tests select `android/tests/ExpressiveAndroidTestManifest.xml`. |
| 14–16 | `android/wmshell/multivalentScreenshotTests`, `android/wmshell/multivalentTests`, and `android/wmshell/tests/unittest` | Separate instrumentation manifests; wmshell main selects its root manifest. |

**Classification: alert 7 false positive; alerts 8–16 used in tests.** Preserve
instrumentation debugging. QA explicitly sets `debuggable false`; Release is
nondebuggable. `ci/check_security_manifests.py` checks their merged manifests,
and the existing signed-candidate verifier separately rejects a debuggable
launcher APK. The separate Feed helper has its own documented debugging
requirement and is not the subject of these occurrences.

## Numeric conversions: alerts 17–37

The implicit narrowing operations are present. Their outputs represent view
positions, pixel dimensions, or animation timing. They do not implement an
authorization check, parser length, memory allocation boundary, or database/file
access decision. Fractional pixel/timing truncation is existing UI behavior.

| Alerts | Source and value flow | Review conclusion |
| --- | --- | --- |
| 17 | `RecentsView`: sliding translation for task dismissal | Float translation becomes a pixel offset for an animation. |
| 18–19 | `RecentsView`: task/clear-all offset adjustments | Accumulate visual offsets into integer paged positions. |
| 20–24 | `DeviceProfile`: page indicator, hotseat spacing, icon/folder label sizes | Apply local appearance preferences to resource-derived pixel dimensions. These are presentation settings, not security decisions. |
| 25 | `PagedView.distanceInfluenceForSnapDuration()` | Its caller clamps the distance ratio to at most 1; the float/double multiplication feeds a sine-based scroll interpolation. |
| 26 | `PagedView`: animation scale | Studio-build-only system animation-scale adjustment to scrolling duration. |
| 27–30 | `Workspace`: drop animation coordinates | Scale/offset view and drag-region dimensions into integer destination coordinates. |
| 31 | `DragController`: distance since scroll | Accumulate touch movement for the scroll-hover gesture threshold; fractional truncation cannot grant data or component access. |
| 32–33 | `DragLayer`: drop destination | Convert scaled view geometry to pixel destinations. |
| 34 | `DragLayer`: animation duration | Interpolation reduces the configured maximum when distance is below the resource maximum, then enforces the configured minimum. |
| 35 | `FolderPagedView`: reorder stagger | Delay starts at zero, adds a 30 ms term decaying by 0.9, and stays below the 300 ms geometric-series bound. |
| 36–37 | `ViewGroupFocusHelper`: focus rectangle | Accumulate view x/y positions into the integer focus-drawing rectangle. |

**Classification: intentional conversions retained (`won't fix` for these
occurrences), with no demonstrated security-boundary violation.** This does not
deny that narrowing occurs or claim to fix it. Changing rounding or widening
shared pixel APIs would alter UI behavior without addressing a demonstrated
vulnerability. Reassess if these values acquire a security-sensitive use or a
concrete incorrect-UI reproduction is supplied.

## Reproducible validation

The existing Dependency Submission workflow runs the app unit suite, developer
and benchmark assemblies, and then:

```sh
./gradlew --no-daemon --no-scan --no-configuration-cache --max-workers=2 \
  processLawnWithQuickstepExpressiveQaManifest \
  processLawnWithQuickstepExpressiveReleaseManifest
python3 -m unittest discover -s ci/tests -p test_check_security_manifests.py
python3 ci/check_security_manifests.py \
  build/intermediates/merged_manifests/lawnWithQuickstepExpressiveQa/processLawnWithQuickstepExpressiveQaManifest/AndroidManifest.xml \
  build/intermediates/merged_manifests/lawnWithQuickstepExpressiveRelease/processLawnWithQuickstepExpressiveReleaseManifest/AndroidManifest.xml
```

The workflow retains JUnit XML and merged manifest evidence for seven days.
These checks do not build or publish an official signed QA/Stable release.

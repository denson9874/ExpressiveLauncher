# Weekly QA and stable releases

The user authorized this cadence on September 9, 2026. All times are **America/New_York**:

| Day | Time | Action |
| --- | --- | --- |
| Monday, Wednesday, Friday | 3:00 a.m. | Existing focused Pixel-parity QA build and QA publication |
| Saturday | 3:00 a.m. | Stable Release build and publication, only when the week's QA gate passes |

Codex schedules and coordinates these runs. Jenkins performs all full tests, signing, packaging,
device validation, sealing and publication. The Mac must be awake and logged in with the desktop
automation available. There is no second Jenkins timer or independent publication scheduler.

## What green means

`ci/weekly_release_gate.py` reads the live Jenkins history and immutable candidate evidence for the
current local Monday-through-Friday window. Every actual QA build in that window must have completed
SUCCESS. Monday, Wednesday and Friday must each have build coverage, and the latest candidate on
each of those days must have a matching successful GitHub publication receipt and intact seal.
Queued or running QA, missing days/evidence, failed, aborted or unstable QA builds hold the release.
A no-op is not a passing build. Failed QA build attempts hold the week even if a later build passes.
Transient publication failures can be resolved by a successful retry of the exact sealed candidate.

The winner is Friday's latest verified build. Stable reuses its exact application source SHA,
version name and code; it does not add a new feature or bump a version solely for promotion.
The gate runs again before publication. A candidate from another week or superseded Friday source
cannot be published by the weekly workflow. The production CLI has no clock override.

```sh
python3 ci/weekly_release_gate.py --output /absolute/run-artifacts/weekly-gate.json
python3 ci/jenkins/control.py run --job release-build
python3 ci/jenkins/control.py status --job release-build --number BUILD_NUMBER
python3 ci/jenkins/control.py run --job release-publish --release-id release-VERSION-CODE-build-NUMBER
python3 ci/jenkins/control.py status --job release-publish --number PUBLICATION_NUMBER
```

## Separate stable package and evidence

The stable build uses `assembleLawnWithQuickstepExpressiveRelease`, package
`dev.launcher.expressive.l3`, minification and the existing durable signing identity. QA remains
`dev.launcher.expressive.l3.debug`. These are separate Android installations: this schedule does
not convert a QA installation or copy its private settings into the stable app.

Stable builds run the full Expressive unit suite and isolated device checks for the actual Release
APK. Subsequent stable builds verify their version is newer than the delivered stable baseline,
verify signing continuity, and test upgrade/settings retention, Home launches, drawer, search,
date handoff and launcher crash/ANR logs on the pinned Android guest.

The first stable release has no shipped stable baseline. Only an explicit, verified missing stable
feed permits `first-stable-install` mode. That mode tests a fresh installation, seeds a preference,
reinstalls the same exact stable APK and verifies retention plus the normal smoke flows. Its report
does not claim a prior-version stable upgrade. A QA APK is never used as a stable upgrade baseline.
The selected sealed QA metadata still establishes the exact intended version/source and signer.

Jenkins seals under `releases/release-VERSION-CODE-build-NUMBER`. The publisher checks the seal,
weekly selection and actual stable identity, uploads/reuses exact APK/report/metadata/device-result
bytes, publishes a non-prerelease `vVERSION-CODE`, and updates only `updates:release/latest.json`.
QA tags, assets and `updates:qa/latest.json` remain independent. A retry never replaces signed assets.

The source-selected build must contain the stable pipeline support. Do not silently build newer
application source when a selected older QA commit lacks that support; let a new QA candidate pass
the normal weekday workflow first.

## Infrastructure validation

A manual, private trial can exercise the new pipeline before the first eligible Saturday:

```sh
python3 ci/jenkins/control.py run --job release-build --validation-qa-release-id qa-VERSION-CODE-build-NUMBER
```

This explicit mode builds current clean committed source and checks version/signing against the
given sealed QA candidate. It records that its source may differ from that QA source and seals
`validationOnly=true`. Both publication adapters and the publisher reject such a candidate, even
on an otherwise green Saturday. It does not satisfy weekday QA coverage or become a stable offer.

## Weekly changelog and outcomes

After successful publication, the weekly automation authors a changelog under
[the GitHub standard](GITHUB_RELEASE_CHANGELOG.md), covering changes since the prior stable release,
with a catchy title, relevant humor and a revealable riddle. Preserve the `expressive-release`
identity comment and exact assets/feed while editing prose. A held week reports its concrete reasons;
a publication failure retains the candidate and receipts for a same-candidate retry within the
eligible week. A build pass alone is never a released update.

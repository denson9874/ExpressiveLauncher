# Weekly QA and stable releases

The user authorized this cadence on September 9, 2026. All times are **America/New_York**:

| Day | Time | Action |
| --- | --- | --- |
| Monday, Wednesday, Friday | 3:00 a.m. | Existing focused Pixel-parity QA build and QA publication |
| Saturday | 3:00 a.m. | Stable Release build and publication, only when the week's QA gate passes |

On September 12, 2026, the user additionally authorized automatic publication of every green,
sealed stable build to the literal GitHub [`stable` branch](https://github.com/denson9874/ExpressiveLauncher/tree/stable),
including the already verified 1.0.16 build 7. This publication policy supersedes the earlier
calendar and private-trial publication restriction. Scheduled source selection still uses the
weekly QA gate below; a manual stable build uses an explicitly selected sealed QA version.

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
New publication receipts declare `releaseAssetPolicy=apk-only` and must verify exactly the sealed
APK as the release attachment. The gate also accepts historical receipts without that policy only
when all four original assets verify. Both paths still require the complete internal seal and QA
evidence; unknown policies, extra new attachments or missing expected files hold the release.

The winner is Friday's latest verified build. Stable reuses its exact application source SHA,
version name and code; it does not add a new feature or bump a version solely for promotion.
The production source-selection CLI has no clock override. Publication checks the originating
stable Jenkins build for terminal `SUCCESS`, its immutable seal, passing unit tests and complete
stable device checks. A later calendar change does not invalidate an already green stable build.

```sh
python3 ci/weekly_release_gate.py --output /absolute/run-artifacts/weekly-gate.json
python3 ci/jenkins/control.py run --job release-build
python3 ci/jenkins/control.py status --job release-build --number BUILD_NUMBER
# Publication starts automatically after the build succeeds. For a failed upload, retry:
python3 ci/jenkins/control.py run --job release-publish --release-id release-VERSION-CODE-build-NUMBER
python3 ci/jenkins/control.py status --job release-publish --number PUBLICATION_NUMBER
```

## Separate stable package and evidence

The stable build uses `assembleLawnWithQuickstepExpressiveRelease`, package
`dev.launcher.expressive.l3`, minification and the existing durable signing identity. From 2.0.0,
signed QA uses the same package and signer, as explicitly requested by the user. About provides
a persistent Stable/QA update-channel choice. New QA manifests live at `updates:qa-v2/latest.json`;
Stable continues to use `updates:release/latest.json`. The legacy 1.x QA package `.debug` and
its `updates:qa/latest.json` feed remain separate. Existing stable data survives an upgrade to
QA 2.0.0; legacy QA private data cannot be transferred by an Android package update.

Stable builds run the full Expressive unit suite and isolated device checks for the actual Release
APK. Subsequent stable builds verify their version is newer than the delivered stable baseline,
verify signing continuity, and test upgrade/settings retention, Home launches, drawer, search,
date handoff and launcher crash/ANR logs on the pinned Android guest.

The first stable release has no shipped stable baseline. Only an explicit, verified missing stable
feed, with authenticated confirmation that no stable feed history or prior stable release exists,
permits `first-stable-install` mode. A deleted feed after stable publication holds the release; it
does not reset this rule. That mode tests a fresh installation, seeds a preference,
reinstalls the same exact stable APK and verifies retention plus the normal smoke flows. Its report
does not claim a prior-version stable upgrade. Stable build validation continues to use the exact
previously shipped stable APK as its baseline, even though QA 2.x shares its installation identity.
The selected sealed QA metadata still establishes the exact intended version/source and signer.

Jenkins seals under `releases/release-VERSION-CODE-build-NUMBER`. Its successful build queues
`expressive-release-publish` automatically without holding a worker while waiting for the publisher.
The publisher confirms the upstream job has actually finished successfully, then records a separate
authorization bound to the build, seal, APK and original evidence. Failed, unstable, aborted,
incomplete, unsigned, debuggable or mismatched candidates fail before upload.

Future normal GitHub releases `vVERSION-CODE` attach only the exact signed APK. The report,
metadata, device result, seal and authorization remain validated local/Jenkins evidence and are
not release attachments. The publisher still commits the APK and that evidence under
`stable:releases/RELEASE_ID/`, alongside root `latest.json`, `publication.json` and download instructions.
The branch update preserves prior versions and unrelated files, uses a non-forced atomic commit,
and rejects rollback or same-version conflicts. The original sealed files are never rewritten.
For historical private-trial artifacts, the separate authorization explicitly supersedes their
original publication restriction while retaining the original report as build-time history.

Authenticated and anonymous verification must confirm the exact committed APK and manifest.
Only then does the publisher advance `updates:release/latest.json`, preserving compatibility with
the stable app's existing updater. Success requires verified GitHub assets, the `stable` branch,
and the stable update feed. QA tags, assets and `updates:qa-v2/latest.json` remain independent;
the legacy QA manifest is preserved for 1.x installations.
A retry reuses the retained authorization and never replaces signed assets.

The source-selected build must contain the stable pipeline support. Do not silently build newer
application source when a selected older QA commit lacks that support; let a new QA candidate pass
the normal weekday workflow first.

## Explicit stable build selection

A manually authorized stable build can select a sealed QA version:

```sh
python3 ci/jenkins/control.py run --job release-build --validation-qa-release-id qa-VERSION-CODE-build-NUMBER
```

The parameter retains its legacy name for Jenkins compatibility. This mode builds current clean
committed source and checks version/signing against the given sealed QA candidate. It records the
manual selection and both source revisions. The legacy `validationOnly` marker is retained by
the build scripts to record the source-difference mode; it no longer controls publication when
an exact green-build authorization is present. New reports explain that distinction. All normal
stable build, signature, unit, device and baseline checks still apply, and successful sealed builds
publish automatically. This does not satisfy or replace weekday QA coverage.

`ci/green_stable.py` owns the exact-build authorization policy. Publication runs current clean,
committed infrastructure and records its revision and file hashes separately from the application's
sealed source revision. The authorization is retained under the CI home directory's
`publication-authorizations/` so recovery attempts use identical public evidence.

## Weekly changelog, V3 Free/Pro architecture, and community outcomes

After successful publication, the weekly automation authors a changelog under
[the GitHub standard](GITHUB_RELEASE_CHANGELOG.md), covering changes since the prior stable release,
with a catchy title, relevant humor and a revealable riddle. For the V3 milestone shift, document the
Free Core vs. $4.99 Pro customization architecture, accompanied by the standard developer support
statement:
> *“Expressive Pro is available as a one-time $4.99 activation. Every dollar directly funds our ongoing tooling and infrastructure expenses—including automated CI/CD servers, dedicated Android 17 testing devices/emulators, signing pipelines, and active continuous development—keeping Expressive fast, independent, and completely ad-free.”*

Additionally, each published Stable release triggers official companion release announcements across the
three community feedback threads:
- [Pixel 7 Pro (Thread 4801789)](https://xdaforums.com/t/app-qa-android-17-expressive-launcher-pixel-inspired-customization-pixel-7-pro-feedback.4801789/)
- [Pixel 8 Pro (Thread 4801791)](https://xdaforums.com/t/app-qa-android-17-expressive-launcher-material-3-expressive-pixel-8-pro-feedback.4801791/)
- [Pixel 11 Pro / Pro XL (Thread 4801792)](https://xdaforums.com/t/app-qa-android-17-expressive-launcher-looking-for-pixel-11-pro-pro-xl-feedback.4801792/)

Preserve the `expressive-release` identity comment and exact assets/feed while editing prose. A held week
reports its concrete reasons; a publication failure retains the candidate and receipts for a same-candidate retry.
A build pass alone is never a released update.

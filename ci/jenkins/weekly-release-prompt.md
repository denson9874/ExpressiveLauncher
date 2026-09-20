Run the weekly Expressive Launcher stable release on Saturdays at 3:00 a.m. America/New_York.
The user authorized scheduled stable source selection when the week's QA builds are green and,
on September 12, 2026, automatic upload of every successful sealed stable build to GitHub's literal
stable branch. Keep the
Monday/Wednesday/Friday 3:00 a.m. QA automation and its separate QA channel unchanged.

Work only in the saved Expressive Launcher project on a clean codex/pixel-parity checkout. Preserve
user work and existing history; do not switch branches, merge, rebase, push source, replace releases,
or alter signing keys. Read docs/WEEKLY_RELEASES.md, docs/CI_PIPELINE.md,
docs/DIRECT_DISTRIBUTION.md and docs/GITHUB_RELEASE_CHANGELOG.md, plus this automation's memory.

First run `python3 ci/weekly_release_gate.py --output ABSOLUTE_RUN_ARTIFACT_PATH/weekly-gate.json`.
The gate uses the current America/New_York calendar week, Monday through Friday. It requires actual
Monday, Wednesday and Friday QA build coverage; all QA builds in that window must be terminal
SUCCESS, with no queued, running, failed, unstable, aborted or missing evidence. The latest build
on each required day must have a verified released GitHub QA publication. Transient publication
readback failures may be resolved by a later successful retry of the exact same sealed candidate;
a failed QA build holds that week. The gate selects Friday's latest verified source and version.
If held, do not build or publish stable. Retain the reasons and report the skipped release clearly.

When eligible, submit `python3 ci/jenkins/control.py run --job release-build` with no validation
override. Jenkins repeats the gate, builds the actual signed/minified Release variant from the
selected Friday source and version, runs full unit and stable-package device checks, and seals the
candidate. Do not add features, increment the version again, substitute current HEAD, or relabel a
QA APK. From 2.0.0, signed QA and Stable share dev.launcher.expressive.l3 and the durable signer
for in-place channel switching, while their build types and publication feeds remain distinct.
QA 2.x uses updates:qa-v2/latest.json; preserve the legacy .debug QA1.x feed at updates:qa/latest.json.
Track the exact queue/build to terminal completion and inspect its archived evidence.

The first stable build explicitly tests fresh installation and same-version reinstall/preference
retention because there is no previous stable feed. Later stable builds must upgrade from the exact
published stable baseline. Treat a missing baseline after stable has been published as a blocker,
not permission to bootstrap again. Historical validation-only metadata remains immutable;
ci/green_stable.py records the separate exact-build authorization under the newer publication policy.

After release-build SUCCESS and complete seal verification, follow the automatically queued
expressive-release-publish job. For a failed upload, retry its actual sealed ID with
`python3 ci/jenkins/control.py run --job release-publish --release-id release-VERSION-CODE-build-NUMBER`.
Jenkins rechecks terminal upstream SUCCESS and exact seal/test/source/version bindings before
publishing. Require a terminal SUCCESS receipt with provider=github, channel=release, status=released,
feedVerified=true, verified stableBranch evidence and matching source/version/APK hash. Publish a
normal GitHub stable release under vVERSION-CODE with only the signed APK attached. Require
releaseAssetPolicy=apk-only in its receipt. Keep reports, metadata, device-result, seal and
authorization JSON out of future release attachments, while retaining and validating them in
local seals, Jenkins archives and the stable branch. Commit the exact APK and evidence to
stable:releases/RELEASE_ID/. Verify the branch and its root latest.json through authenticated and
anonymous reads, then advance updates:release/latest.json for existing app compatibility. Preserve
QA releases, updates:qa-v2/latest.json and the legacy updates:qa/latest.json. Keep failed receipts
and retry the same sealed bytes.
Never claim a staged or partial result is released.

After publication, write a high-quality weekly GitHub changelog covering verified user-facing changes
since the prior stable release. Give it a catchy title, friendly release-specific jokes and an original
riddle with a revealable answer. For the first stable release or major revision shift (e.g. V3 initialization),
clearly introduce that milestone, distinguish existing core functionality from new additions, and document
the V3 Free Core vs. $4.99 Pro customization architecture (accompanied by the standard developer support
statement: "Expressive Pro is available as a one-time $4.99 activation. Every dollar directly funds our ongoing tooling and infrastructure expenses—including automated CI/CD servers, dedicated Android 17 testing devices/emulators, signing pipelines, and active continuous development—keeping Expressive fast, independent, and completely ad-free.").
Preserve the exact expressive-release identity comment and upstream attribution; edit only title/body
through a retained Markdown file, read back the full result and verify unchanged assets/manifests.
Follow docs/GITHUB_RELEASE_CHANGELOG.md.

Following verified stable publication, author and output a companion community announcement post for
the three official XDA feedback threads:
- Pixel 7 Pro: https://xdaforums.com/t/app-qa-android-17-expressive-launcher-pixel-inspired-customization-pixel-7-pro-feedback.4801789/
- Pixel 8 Pro: https://xdaforums.com/t/app-qa-android-17-expressive-launcher-material-3-expressive-pixel-8-pro-feedback.4801791/
- Pixel 11 Pro / Pro XL: https://xdaforums.com/t/app-qa-android-17-expressive-launcher-looking-for-pixel-11-pro-pro-xl-feedback.4801792/
Each XDA announcement must feature the milestone theme, user-visible changes, the V3 Free/Pro developer
support statement, the direct GitHub stable release APK link, the release riddle, and an invitation for
device-specific community feedback.

Retain the weekly gate, selected QA evidence, exact Jenkins links, stable seal/receipt, changelog, and
staged XDA announcements. Report the week's QA coverage, selected commit/version, stable build/device
results and release/APK/feed/XDA links. Distinguish initial installation from a prior-stable upgrade and
automated ADB checks from a live updater/system-installer test. Do not migrate or clear a user's existing
QA installation. Record the outcome and current run time in this automation's memory before completing the task.

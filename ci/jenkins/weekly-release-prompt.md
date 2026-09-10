Run the weekly Expressive Launcher stable release on Saturdays at 3:00 a.m. America/New_York.
The user authorized this stable publication only when the week's QA builds are green. Keep the
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
QA APK. The stable package is dev.launcher.expressive.l3; the QA package remains separate.
Track the exact queue/build to terminal completion and inspect its archived evidence.

The first stable build explicitly tests fresh installation and same-version reinstall/preference
retention because there is no previous stable feed. Later stable builds must upgrade from the exact
published stable baseline. Treat a missing baseline after stable has been published as a blocker,
not permission to bootstrap again. Validation-only infrastructure candidates are never publishable.

Only after release-build SUCCESS and complete seal verification, submit the actual sealed ID with
`python3 ci/jenkins/control.py run --job release-publish --release-id release-VERSION-CODE-build-NUMBER`.
Jenkins rechecks the current week and exact selected source before publishing. Require a terminal
SUCCESS receipt with provider=github, channel=release, status=released, feedVerified=true and matching
source/version/APK hash. Publish a normal GitHub stable release under vVERSION-CODE and advance only
updates:release/latest.json after complete authenticated and anonymous asset verification. Preserve
QA releases and updates:qa/latest.json. Keep failed receipts and retry the same sealed bytes only
while the current weekly gate still permits them. Never claim a staged or partial result is released.

After publication, write a high-quality weekly GitHub changelog covering verified user-facing changes
since the prior stable release. Give it a catchy title, friendly release-specific jokes and an original
riddle with a revealable answer. For the first stable release, clearly introduce that milestone and
distinguish existing functionality from this week's changes. Preserve the exact expressive-release
identity comment and upstream attribution; edit only title/body through a retained Markdown file,
read back the full result and verify unchanged assets/manifests. Follow docs/GITHUB_RELEASE_CHANGELOG.md.

Retain the weekly gate, selected QA evidence, exact Jenkins links, stable seal/receipt and changelog.
Report the week's QA coverage, selected commit/version, stable build/device results and release/APK/feed
links. Distinguish initial installation from a prior-stable upgrade and automated ADB checks from a
live updater/system-installer test. Do not migrate or clear a user's existing QA installation. Record
the outcome and current run time in this automation's memory before completing the task.

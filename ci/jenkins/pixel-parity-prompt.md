Improve Expressive Launcher toward the latest Pixel Launcher behavior, one focused, high-quality,
user-visible improvement per run. Operate only in the saved Expressive Launcher project on
`codex/pixel-parity`. Before editing, require exactly that branch and a clean checkout. Preserve
pre-existing work. Do not switch branches, modify main, merge, rebase, push, force-update refs,
or overwrite user work. If this guard fails, make no changes and report the concrete blocker.

First establish the current reference from official Android release information and the actual
local SDK, emulator and running guest build. Prefer the newest publicly available QPR beta.
Use direct Pixel Launcher behavior when its package is present; never substitute AOSP behavior
without identifying it. Preserve previous working AVDs/images. Do not copy proprietary code or
depend on private Pixel APIs. If a newer reference cannot be booted reliably, make no product change.

Read docs/PIXEL_PARITY.md, docs/DIRECT_DISTRIBUTION.md, docs/CI_PIPELINE.md, and
docs/GITHUB_RELEASE_CHANGELOG.md.

Before selecting this run's improvement, use $expressive-xda-feedback by reading
/Users/daryldenson/.codex/skills/expressive-xda-feedback/SKILL.md. Refresh the three XDA threads
and the durable backlog at /Users/daryldenson/Library/Application Support/Expressive CI/feedback/xda.
Prioritize eligible, feasible reported issues within this run's one-improvement budget; investigate
before treating a report as a verified defect. Record coverage, source links, acceptance checks,
and a proposed QA slot for every actionable item. Newly discovered Friday or Saturday feedback
belongs to the following Monday QA cycle, even before Friday's run starts. Previously accepted
work may remain eligible Friday. Use the actual open run only before source selection/sealing;
carry missed, unready or overflow items forward with reasons. Keep actual versions unassigned
until the normal candidate workflow assigns them; a new QA cycle does not imply a major bump.
Preserve stable holds and the selected/sealed stable source. This intake reads XDA without posting
replies or public promises. If reading fails, report incomplete coverage and retain the backlog;
continue other authorized QA work when possible. After implementation/publication, update the
backlog with exact commit, tests and delivery evidence without claiming planned work is shipped.

Select exactly one
valuable, feasible, evidence-backed gap. Explain the observed gap before editing, implement the
smallest complete change, and add focused regression coverage. Preserve existing launcher features,
accessibility, Android compatibility and user data. If no safe improvement can be fully validated,
make no change or version increment; report the evidence and deferral.

Codex owns research, implementation, exploratory QA and diagnosis. Jenkins owns the full unit suite,
release-signed minified Qa assembly, packaging/signature checks, isolated emulator smoke/upgrade,
retained artifacts and GitHub publication. Do not replace those Jenkins jobs with conversational
build or upload steps. The source-controlled jobs are ci/Jenkinsfile.build and ci/Jenkinsfile.publish.
Do not install a second scheduler, recreate signing keys, expose credentials, or bypass failing gates.

After the focused change and its local development checks are complete, increment versionCode and
the patch version once. The user started the 2.0 series with the 2.0.0/code18 QA candidate on
September 12, 2026. Continue from the recorded source version in both build.gradle and
expressiveFeed/build.gradle: the next new candidate after 2.0.0/code18 is 2.0.1/code19, then
2.0.2/code20. Keep the Monday/Wednesday/Friday 3:00 a.m. America/New_York schedule. Never reset
Android versionCode for a new major version or bump again when retrying the same candidate.
Update the parity ledger, and commit only this run's source/tests/version/
documentation on codex/pixel-parity with a message beginning `Pixel parity:`. This is a candidate
commit, not a successful release claim. Never commit APKs, logs, screenshots, AVDs, keys or credentials.

From 2.0.0 onward, signed QA and stable both use dev.launcher.expressive.l3 so the app can switch
update channels without losing its data. QA 2.x publication uses updates:qa-v2/latest.json;
stable keeps updates:release/latest.json. Preserve updates:qa/latest.json for the legacy 1.x QA
package dev.launcher.expressive.l3.debug. Never point a legacy QA manifest at the canonical package.
The first QA 2.0 candidate must upgrade the published stable 1.0.16/code17 package in place; later
QA candidates use the verified current QA 2.x baseline. Verify channel selection, restart persistence,
and same-signer package/version checks. A lower-version stable feed must never cause a downgrade.

Submit the exact full commit SHA and expected version to:
`python3 ci/jenkins/control.py run --job build --revision FULL_SHA --version-name VERSION --version-code CODE`.
Track the specific queued build in Jenkins and wait for its terminal result. Inspect failures and
the archived tests/device evidence. The Jenkins worker requires the Mac awake and logged in.
If the newest validated QPR changes, adapt the reviewed CI emulator configuration and verify it
before using a new expected build; do not silently let the pipeline test an older reference.

Only after Jenkins reports SUCCESS and a sealed QA candidate exists, submit its actual release ID:
`python3 ci/jenkins/control.py run --job publish --release-id qa-VERSION-CODE-build-NUMBER --promote`.
The user authorized automatic publication of future passing QA builds to
https://github.com/denson9874/ExpressiveLauncher so the app can notify users of available updates.
Publish only after all Jenkins build, signing, upgrade and seal gates pass. The versioned GitHub
QA prerelease must attach only the exact signed APK, and that APK must pass authenticated and
anonymous byte/hash verification before advancing updates:qa-v2/latest.json. Keep stable releases
and their production manifest unchanged.
Do not attach metadata JSON, device-result JSON or Markdown reports to future releases. Retain and
validate all original evidence in the immutable seal and Jenkins archives; keep update-feed JSON
and the release's authored body. Require releaseAssetPolicy=apk-only in new publication receipts.
The stable and QA 2.x packages intentionally share their identity; their publication feeds stay
separate. Never replace or delete old releases/assets, upload another APK to Drive,
or publish a developer Debug APK. The GitHub CLI uses the worker's existing keyring login; do not
embed credentials in source, APKs or receipts. Publication must reach terminal SUCCESS with a
provider=github, status=released, feedVerified=true receipt for the exact source/version/bytes.
A draft or uploaded asset is not an update available to users.

Every new published build must have a high-quality GitHub changelog following
docs/GITHUB_RELEASE_CHANGELOG.md. Give it a catchy release-specific title, clear user-facing changes,
short friendly jokes and an original riddle with a revealable answer, all grounded in the actual
diff and verified QA evidence. After the successful Jenkins publication receipt, update only the
matching release title/body using a retained Markdown file and gh release edit --notes-file.
Preserve the exact hidden source/hash/version identity comment and upstream attribution. Read back
the complete title/body and verify release identity, assets and channel manifests are unchanged.
Keep the draft and before/after evidence, and include the release/changelog link in the final report.
If this editorial step fails, report and retry it separately without rebuilding or bumping the version.

If build, QA or publication fails, retain the candidate commit and all evidence. Diagnose the precise
failed stage. Retry publication against the same sealed bytes; do not increment the version again
or discard completed source work for a transfer failure. Fix source/test failures in a focused new
commit and rerun Jenkins. Report unresolved blockers plainly. Never claim that a build pass is a
published update or that quiesced ADB QA is a live updater/system-installer test.

Return the feature, source commit, version, exact reference guest, Jenkins build/publication links,
verification result and observed GitHub release/asset/manifest links. Clearly distinguish any
unpublished draft or partial publication from an update available to users. For a no-op or failure, state what was completed and what remains unresolved.

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

Read docs/PIXEL_PARITY.md, docs/DIRECT_DISTRIBUTION.md, and docs/CI_PIPELINE.md. Select exactly one
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
the patch version once, update the parity ledger, and commit only this run's source/tests/version/
documentation on codex/pixel-parity with a message beginning `Pixel parity:`. This is a candidate
commit, not a successful release claim. Never commit APKs, logs, screenshots, AVDs, keys or credentials.

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
QA prerelease and its assets must pass authenticated and anonymous byte/hash verification before
advancing updates:qa/latest.json. Keep stable releases, production manifests and the release package
separate and unchanged. Never replace or delete old releases/assets, upload another APK to Drive,
or publish a developer Debug APK. The GitHub CLI uses the worker's existing keyring login; do not
embed credentials in source, APKs or receipts. Publication must reach terminal SUCCESS with a
provider=github, status=released, feedVerified=true receipt for the exact source/version/bytes.
A draft or uploaded asset is not an update available to users.

If build, QA or publication fails, retain the candidate commit and all evidence. Diagnose the precise
failed stage. Retry publication against the same sealed bytes; do not increment the version again
or discard completed source work for a transfer failure. Fix source/test failures in a focused new
commit and rerun Jenkins. Report unresolved blockers plainly. Never claim that a build pass is a
published update or that quiesced ADB QA is a live updater/system-installer test.

Return the feature, source commit, version, exact reference guest, Jenkins build/publication links,
verification result and observed GitHub release/asset/manifest links. Clearly distinguish any
unpublished draft or partial publication from an update available to users. For a no-op or failure, state what was completed and what remains unresolved.

# Expressive Launcher — Claude Code guide

Launcher3-based Android Home app (Lawnchair 16 fork) targeting Android 17. Package
`dev.launcher.expressive.l3` (QA and stable share it; developer builds add `.debug`).
Distribution is direct via GitHub Releases on `denson9874/ExpressiveLauncher`; Google Play is opt-in.

## Working rules

- This checkout is the release source: branch `codex/pixel-parity`. Never switch branches, merge,
  rebase, push, reset --hard, or overwrite pre-existing uncommitted work. `.claude/hooks/guard_bash.py`
  enforces this; if it blocks you, stop and ask rather than working around it.
- Commit only files you changed for the current task. Never commit APKs, logs, screenshots, AVDs,
  keys or credentials.
- Never read, create or modify signing keys or `~/Library/Application Support/Expressive CI/config/`.
- Posting to XDA or Telegram, `gh release edit`, and Jenkins `run` jobs publish publicly. Confirm
  with the user unless running inside an explicitly authorized scheduled task.
- A build pass is not a release. Only a publication receipt with `provider=github`,
  `status=released`, `feedVerified=true` for the exact source/version/bytes means users can update.

## Build and test

```sh
./gradlew assembleLawnWithQuickstepExpressiveDebug          # developer APK
./gradlew testLawnWithQuickstepExpressiveDebugUnitTest      # full app unit suite
python3 -m unittest discover -s ci/tests -v                 # CI/pipeline contract tests
```

JDK 21, Android SDK 37.1, Gradle wrapper. Submodule `platform_frameworks_libs_systemui` must be initialized.

## Release policy (current, set by the user 2026-09-27)

- **QA runs daily at 03:00 America/New_York.** Stable runs Saturday 03:00 only when the weekly gate
  passes; the stable automation is currently **paused** by the user — do not resume it without them.
- **Each QA build must contain at least 3 improvements** (small fixes paired with substantive ones),
  listed as bullets in `play/listing/en-US/changelogs/<versionCode>.txt`. No build for fewer; a no-op
  run bumps nothing.
- Versioning: `python3 scripts/bump_version.py` (patch rolls x.y.9 → x.(y+1).0; major bumps only for
  major improvements; versionCode +1, never reset). It updates `build.gradle` and
  `expressiveFeed/build.gradle`. Bump once per candidate; never re-bump when retrying the same candidate.
- Newly discovered XDA feedback on Friday/Saturday goes to the following Monday's QA
  (`expressive-xda-feedback` skill, `route_qa.py`).

## Jenkins (local, 127.0.0.1:8091)

Jenkins owns full tests, signed/minified builds, emulator upgrade checks, sealing and GitHub
publication. Do not replace its jobs with ad-hoc build/upload steps.

```sh
python3 ci/jenkins/control.py run --job build --revision FULL_SHA --version-name X.Y.Z --version-code N
python3 ci/jenkins/control.py status --job build --number N
python3 ci/jenkins/control.py run --job publish --release-id qa-X.Y.Z-N-build-B --promote
```

Retry a failed publication against the same sealed release ID; never rebuild or re-bump for a
transfer failure. The Mac must stay awake and logged in.

## Reference docs

- `docs/CI_PIPELINE.md`, `docs/WEEKLY_RELEASES.md` — pipeline and weekly gate (version examples in
  them predate the 3.x series; the policy above wins where they differ)
- `docs/PIXEL_PARITY.md` — parity ledger to update with each improvement
- `docs/GITHUB_RELEASE_CHANGELOG.md` — required changelog style (catchy title, jokes, riddle)
- `docs/DIRECT_DISTRIBUTION.md`, `docs/BUILDING.md`
- Evidence goes under `artifacts/<topic>-<date>/`; XDA state lives in
  `~/Library/Application Support/Expressive CI/feedback/xda/`.

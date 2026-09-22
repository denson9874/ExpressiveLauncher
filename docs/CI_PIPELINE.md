# Expressive Launcher Jenkins pipeline

Jenkins runs full tests, signed/minified QA builds, isolated upgrade checks, sealing, retention,
and GitHub publication. Codex handles research, implementation, focused development checks,
exploratory device validation and diagnosis. Failed publication preserves the exact tested candidate.

The [weekly release workflow](WEEKLY_RELEASES.md) adds Saturday 3:00 a.m. Eastern stable builds
when the QA week is green. QA runs operate on a daily schedule.
Stable uses separate `expressive-release-build` and `expressive-release-publish` jobs, with a live
weekly gate before scheduled building. Successful sealed stable builds automatically publish to the
GitHub `stable` branch and stable update feed after the publisher rechecks their evidence.

## Infrastructure

- Source checkout: `/Users/daryldenson/Documents/ChatGPT/New project`, `codex/pixel-parity`.
- Export repository: https://github.com/denson9874/ExpressiveLauncher . This repository contains
  build exports and manifests; the application source checkout and upstream remotes remain separate.
- Build job: http://127.0.0.1:8091/job/expressive-qa-build/
- Publish job: http://127.0.0.1:8091/job/expressive-qa-publish/
- Jenkins state, private configuration, logs and retained releases:
  `/Users/daryldenson/Library/Application Support/Expressive CI/`.
- Services: `dev.expressive.jenkins` and `dev.expressive.jenkins-agent` in `~/Library/LaunchAgents`.

Jenkins is bound to loopback, requires authentication, and has zero controller executors. Its one
local worker uses the existing SDK, durable signing identity, JDK 21 and verified Android 17 QPR2
Beta 5 image. The Mac must remain awake and logged in. The controller and worker use the same macOS
user, but separate processes and directories; only trusted recorded source is accepted.

The GitHub CLI is installed at `/opt/homebrew/bin/gh`. The worker uses the authenticated macOS user's
GitHub CLI keyring login to publish to `denson9874/ExpressiveLauncher`. Tokens are never embedded in
APKs, source, arguments or publication receipts. Public clients need no GitHub account or token.
GitHub authentication is separate from the Codex GitHub connector. If it expires, complete
`gh auth login --hostname github.com --git-protocol https --web --skip-ssh-key` and retry the same release.

Jenkins UI credentials remain in private `config/bootstrap.properties`; API credentials are in
`config/jenkins-api-auth`. Preserve these and signing-key backups securely; never include them in exports.

## Build a recorded candidate

Finish the focused change and its local development checks, increment the patch version and version
code once, update the parity ledger, and commit only source/tests/version/docs on `codex/pixel-parity`.
A candidate commit is a source record, not a release claim. Do not commit APKs, logs, keys or AVDs.

The user started the **2.0 series** with the **2.0.0 / code 18** QA candidate on September 12, 2026,
following published QA and stable 1.0.16 / code 17. The launcher and embedded feed companion use
the same version defaults in `build.gradle` and `expressiveFeed/build.gradle`. Continue the
daily schedule with 2.0.1 / code 19, 2.0.2 / code 20, and so on as new candidates
are implemented; a no-op run does not bump either value. The major-version change never resets
Android's monotonically increasing version code. A stable build keeps its selected QA source's
version. GitHub publication retains the established tag formats: `qa-v2.0.0-18` for this QA candidate
and `v2.0.0-18` only if that version later passes and publishes as stable.

The CLI and Jenkins require explicit expected version parameters for every QA build. They have
no baked-in version fallback, so subsequent 2.0.x builds cannot silently reuse an older default.

QA and stable 2.x use the same signed application ID, `dev.launcher.expressive.l3`, allowing an
in-place channel switch with launcher data retained. QA 2.x uses `updates:qa-v2/latest.json`;
stable keeps `updates:release/latest.json`. The legacy `updates:qa/latest.json` continues to describe
1.x QA's separate `.debug` package and must not be overwritten with a 2.x canonical-package APK.
The first 2.0 QA build verifies its upgrade against the published stable 1.0.16 / code 17. After the
QA 2.x feed exists, subsequent QA candidates use that channel's current published baseline.

```sh
python3 ci/jenkins/control.py run --job build --revision FULL_COMMIT_SHA \
  --version-name VERSION --version-code CODE
python3 ci/jenkins/control.py status --job build --number BUILD_NUMBER
```

Jenkins verifies that the revision belongs to the saved branch and prepares detached main/submodule
worktrees at the exact recorded commits. Normal 2.x QA builds download the live GitHub manifest from
`https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/qa-v2/latest.json` and its
versioned GitHub APK. The first QA 2.x bootstrap uses the exact authenticated stable baseline described
above. Other repositories, unrelated channel assets, credential-bearing URLs and HTTP redirect
downgrades are rejected. Bytes and SHA-256 must agree with the manifest.

For the initial GitHub channel bootstrap only, an operator can explicitly select a retained, sealed
QA baseline. This does not silently fall back from a missing or broken feed:

```sh
python3 ci/jenkins/control.py run --job build --revision FULL_COMMIT_SHA \
  --version-name 1.0.11 --version-code 12 --baseline-release-id qa-1.0.10-11-build-3
```

The baseline's complete seal, source provenance, signed QA identity, metadata/report/device-result
hashes and APK bytes must pass before copying it. The selected baseline is recorded in source.json.
After GitHub has a published QA manifest, omit this bootstrap option.

The worker runs pipeline contract tests, the full Expressive unit suite and the durable-signed
minified Qa assembly. Package validation requires the exact expected version, QA package,
non-debuggable output, verified signature and continuity with the previous certificate.
A new isolated `Expressive_CI_*` emulator validates same-signer upgrade, retained preferences/HOME,
warm/cold launch, drawer/search/date flows and clean launcher crash/ANR logs. Existing AVDs remain intact.
The source-controlled template is `Pixel_8_Pro_Android_17_QPR2_Beta5`, with expected emulator build
`CP41.260828.004.A7`, full SDK `37.2` and security patch `2026-08-05`. Google's revision 5 Play Store
ARM64 16 KB image was checksum-verified and passed two independent cold boots with Pixel Launcher
907/version 17. Its emulator build differs from the physical-device Beta 5 builds listed in the
[official release notes](https://developer.android.com/about/versions/17/qpr2/release-notes).
The template references the separate image under
`/Users/daryldenson/Library/Android/reference-images/android-17-qpr2-beta5-r5/arm64-v8a/`;
the retained Beta 4 image and AVDs remain unchanged. Update these pins deliberately after verifying
any newer public QPR reference, before building a new candidate.

A successful build seals `releases/qa-VERSION-CODE-build-NUMBER` with APK, metadata, report, unit
results, mapping and device evidence. Jenkins also archives them. Prior releases are never overwritten.

## GitHub staging and publication

The recurring Pixel-parity automation is authorized to promote every successful, sealed QA build
automatically. Stable releases remain separate. The CLI still supports draft-only staging for manual
review when explicitly requested.

Every newly published build also requires an authored GitHub changelog under
[the release changelog standard](GITHUB_RELEASE_CHANGELOG.md): a catchy title, useful changes,
release-specific humor and a riddle with a revealable answer. After Jenkins verifies publication,
Codex updates only the release title/body, preserves the identity comment and attribution, and
verifies the readback and unchanged assets/manifests. This editorial step does not rebuild an APK
or modify its seal. Release notes are part of completing the recurring release workflow.

```sh
# Retain and verify a GitHub draft without changing the public update manifest.
python3 ci/jenkins/control.py run --job publish --release-id qa-VERSION-CODE-build-NUMBER

# Publish the QA prerelease, verify its public download, then advance only the QA manifest.
python3 ci/jenkins/control.py run --job publish --release-id qa-VERSION-CODE-build-NUMBER --promote
python3 ci/jenkins/control.py status --job publish --number PUBLICATION_NUMBER
```

Use the actual sealed candidate ID from a successful build. The adapter extracts the publisher from
that candidate's exact source commit and records its source revision/hash in the receipt. It requires
the GitHub provider; pre-migration candidates containing the old Drive publisher fail before upload.
Create a new tested migration candidate instead of silently changing the publisher for old bytes.

The publisher uses versioned `qa-vVERSION-CODE` release tags and marks QA releases as prereleases.
Future QA and stable GitHub releases attach only the exact signed APK. Metadata JSON, device-result
JSON and Markdown QA reports remain in the immutable local seal and Jenkins archives; they are
validated before publication but are not release downloads. Stable branch evidence archival and
the JSON update-channel manifests remain required. Release notes remain in the release body.
The publisher stages the APK, downloads it with authentication and verifies its bytes. New receipts
record `releaseAssetPolicy=apk-only`. Existing completed assets are accepted only when byte-identical;
conflicting tags/assets fail without replacement or deletion. An empty failed-upload starter placeholder can be removed only
from the matching candidate draft after rechecking its exact asset ID, name, zero size and source identity. Draft staging does not make an update available to users.

With promotion, Jenkins publishes the prerelease, verifies a complete anonymous APK download, and
updates `updates:qa-v2/latest.json` using the existing Contents API blob SHA. This protects against
concurrent feed writes. The stable manifest `updates:release/latest.json` and legacy
`updates:qa/latest.json` are excluded from a 2.x QA publication.
A published release whose manifest update failed can be retried against the same sealed bytes.

The receipt is the authoritative status record. A build pass, created draft or successful asset upload
alone does not establish an available update. If a job fails after public release publication, preserve
its receipt and retry; the release may be visible even though the channel manifest has not advanced.

## Migration from Drive

Future build exports use GitHub. Historical Drive files are retained. Version 1.0.10 and earlier have
fixed Drive manifest URLs, so the first GitHub migration release also needs a one-time legacy QA
manifest bridge: preserve the existing Drive manifest object's ID and update only its schema-v1
metadata to the already verified GitHub APK. No APK or new report is uploaded to Drive.
After users install the migration APK, its manual and scheduled checks use GitHub exclusively.
The initial publication uses the explicit `--bridge-legacy-qa` option together with `--promote`.
Jenkins runs the migration script from the same sealed candidate commit, backs up the prior manifest,
and requires the verified GitHub receipt before changing that existing QA file. Future automated
publications omit the bridge option. No legacy file permissions or stable-channel files are changed.

```sh
python3 ci/jenkins/control.py run --job publish --release-id qa-VERSION-CODE-build-NUMBER \
  --promote --bridge-legacy-qa
```

That bridge is for legacy 1.x QA's `.debug` package only. Never point the legacy manifest at the
canonical 2.x package. Stable and QA 2.x use their own manifests despite sharing an application ID.

Validate the older installed build's actual notification, snooze, download, integrity checks and Android
installer handoff after publication. Also verify the installed migration build reads GitHub and reports
up to date. Quiesced ADB smoke does not substitute for the live delivery flow; retain the Android
foreground replacement limitation documented in DIRECT_DISTRIBUTION.md.

## V3 Free vs. Pro Architecture & Community Announcements

Starting with the V3 release cycle:
- **Core Free Tier**: All core daily-driver launcher functions—including workspace grid, app drawer, dock, Google Discover feed companion, instant search, and system gestures—remain completely free and open forever.
- **Pro Customization Tier ($4.99 Activation)**: Deep customization controls (custom widget corner radius/padding factor sliders, widget sizing/overlap overrides, third-party icon packs, custom fonts, and Keystore-backed hidden-app locks) are available under an optional $4.99 activation with an initial 14-day unrestricted trial.
- **Tooling & Development Support Statement**: All V3 documentation, release changelogs, and in-app activation prompts must carry the developer support rationale:
  > *“Expressive Pro is available as a one-time $4.99 activation. Every dollar directly funds our ongoing tooling and infrastructure expenses—including automated CI/CD servers, dedicated Android 17 testing devices/emulators, signing pipelines, and active continuous development—keeping Expressive fast, independent, and completely ad-free.”*
- **XDA Community Announcements**:
  Following verified GitHub publication of both QA builds and Stable releases, companion community announcements are authored and posted to the three official XDA feedback threads:
  - [Pixel 7 Pro (Thread 4801789)](https://xdaforums.com/t/app-qa-android-17-expressive-launcher-pixel-inspired-customization-pixel-7-pro-feedback.4801789/)
  - [Pixel 8 Pro (Thread 4801791)](https://xdaforums.com/t/app-qa-android-17-expressive-launcher-material-3-expressive-pixel-8-pro-feedback.4801791/)
  - [Pixel 11 Pro / Pro XL (Thread 4801792)](https://xdaforums.com/t/app-qa-android-17-expressive-launcher-looking-for-pixel-11-pro-pro-xl-feedback.4801792/)
  Each announcement highlights community feedback addressed in the build, links directly to the verified GitHub APK asset, includes the release riddle, and solicits device-specific feedback.

## Maintenance

`ci/jenkins/control.py configure` loads reviewed Jenkinsfiles into the existing jobs. Preserve fields and
history when reconfiguring; do not create another scheduler or rotate the durable signer.
Homebrew supplies Jenkins LTS and GitHub CLI. Keep controller/agent services and their secure backups;
Drive/rclone authentication is no longer needed for normal build publication.

References: [GitHub releases](https://docs.github.com/en/rest/releases/releases),
[release assets](https://docs.github.com/en/rest/releases/assets),
[Contents API](https://docs.github.com/en/rest/repos/contents),
[GitHub CLI authentication](https://cli.github.com/manual/gh_auth_login),
[Jenkins controller isolation](https://www.jenkins.io/doc/book/security/controller-isolation/).

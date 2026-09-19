# Expressive Launcher Jenkins pipeline

Jenkins runs full tests, signed/minified QA builds, isolated upgrade checks, sealing, retention,
and GitHub publication. Codex handles research, implementation, focused development checks,
exploratory device validation and diagnosis. Failed publication preserves the exact tested candidate.

## Infrastructure

- Source checkout: `/path/to/ExpressiveLauncher`, `codex/pixel-parity`.
- Export repository: https://github.com/denson9874/ExpressiveLauncher . This repository contains
  build exports and manifests; the application source checkout and upstream remotes remain separate.
- Build job: http://127.0.0.1:8091/job/expressive-qa-build/
- Publish job: http://127.0.0.1:8091/job/expressive-qa-publish/
- Jenkins state, private configuration, logs and retained releases:
  `/path/to/ExpressiveCI/`.
- Services: `dev.expressive.jenkins` and `dev.expressive.jenkins-agent` in `~/Library/LaunchAgents`.

Jenkins is bound to loopback, requires authentication, and has zero controller executors. Its one
local worker uses the existing SDK, durable signing identity, JDK 21 and verified Android 17 QPR2
Beta 4 image. The Mac must remain awake and logged in. The controller and worker use the same macOS
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

```sh
python3 ci/jenkins/control.py run --job build --revision FULL_COMMIT_SHA \
  --version-name VERSION --version-code CODE
python3 ci/jenkins/control.py status --job build --number BUILD_NUMBER
```

Jenkins verifies that the revision belongs to the saved branch and prepares detached main/submodule
worktrees at the exact recorded commits. Normal builds download the live GitHub QA manifest from
`https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/qa/latest.json` and its
versioned GitHub APK. Other repositories, stable-channel assets, credential-bearing URLs and HTTP
redirect downgrades are rejected. Bytes and SHA-256 must agree with the manifest.

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
The source-controlled expected guest remains `CP41.260814.003.B1`; update it deliberately after verifying
any newer public QPR reference.

A successful build seals `releases/qa-VERSION-CODE-build-NUMBER` with APK, metadata, report, unit
results, mapping and device evidence. Jenkins also archives them. Prior releases are never overwritten.

## GitHub staging and publication

The recurring Pixel-parity automation is authorized to promote every successful, sealed QA build
automatically. Stable releases remain separate. The CLI still supports draft-only staging for manual
review when explicitly requested.

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
It stages the exact sealed APK/report/metadata/device result, downloads each asset with authentication,
and verifies bytes. Existing completed assets are accepted only when byte-identical; conflicting tags/assets
fail without replacement or deletion. An empty failed-upload starter placeholder can be removed only
from the matching candidate draft after rechecking its exact asset ID, name, zero size and source identity. Draft staging does not make an update available to users.

With promotion, Jenkins publishes the prerelease, verifies a complete anonymous APK download, and
updates `updates:qa/latest.json` using the existing Contents API blob SHA. This protects against
concurrent feed writes. The stable manifest `updates:release/latest.json` and stable package are excluded.
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

Production migration is separate; never point the stable manifest at a QA package.

Validate the older installed build's actual notification, snooze, download, integrity checks and Android
installer handoff after publication. Also verify the installed migration build reads GitHub and reports
up to date. Quiesced ADB smoke does not substitute for the live delivery flow; retain the Android
foreground replacement limitation documented in DIRECT_DISTRIBUTION.md.

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

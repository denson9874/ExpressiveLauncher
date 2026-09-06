# Expressive Launcher Jenkins pipeline

Jenkins LTS runs the repeatable QA build and release process. Codex handles feature research,
implementation, exploratory checks, and failure diagnosis. A failed upload preserves the tested
candidate and its recorded source commit so publication can be retried independently.

## Platform and scope

The current integration uses the existing Mac, Android SDK, verified Android 17 QPR2 Beta 4 image,
and durable signing identity. Jenkins is bound to `127.0.0.1:8091`, requires authentication, and has
zero controller executors. One dedicated local agent executes both serialized jobs. It is a local
service: the Mac must be awake and logged in, but a Codex conversation need not be running.
The controller and agent run as the current macOS user; they are separate processes and directories,
not separate operating-system security identities. Only trusted local source is accepted.

- Build job: `http://127.0.0.1:8091/job/expressive-qa-build/`
- Publish job: `http://127.0.0.1:8091/job/expressive-qa-publish/`
- State, private configuration, logs and retained releases:
  `/Users/daryldenson/Library/Application Support/Expressive CI/`
- Services: `dev.expressive.jenkins` and `dev.expressive.jenkins-agent` in `~/Library/LaunchAgents`.
- Jenkins UI credentials: the private `config/bootstrap.properties` file in that state directory.
  The API credential is separately stored in `config/jenkins-api-auth`. Neither belongs in Git.

The pipeline publishes only the QA application `dev.launcher.expressive.l3.debug` to the existing
Drive Debug Builds folder. The production package, release folder and release feed are excluded.
Google Cloud SDK provides the worker's authenticated Drive token; rclone handles transfers and
retry. No connector staging service, agent conversation, or persistent rclone token configuration
is involved in publication. The existing Google account must authorize the SDK's Drive access.

## Run the next candidate

Finish a focused source change, update its version and parity ledger, run its development checks,
and commit the candidate on `codex/pixel-parity`. A candidate commit records work; it does not
mark the version released. Preserve unrelated work and never stage keys, SDK files or artifacts.

```sh
python3 ci/jenkins/control.py run --job build --revision FULL_COMMIT_SHA \
  --version-name 1.0.8 --version-code 9
python3 ci/jenkins/control.py status --job build
```

The build checks that the revision belongs to `codex/pixel-parity`, creates detached worktrees for
the exact main and submodule commits, and downloads the current QA baseline from its existing
public feed. It runs pipeline contract tests, the full Expressive unit suite, and the minified
durable-signed `Qa` build with JDK 21 and Build Tools 37. It explicitly disables build-scan uploads.

Packaging checks require the expected version, exact QA package, non-debuggable output, verified
signature, and the pinned durable certificate matching the previous delivered APK. An isolated
new `Expressive_CI_*` emulator then tests an in-place upgrade, retained settings and HOME, startup,
drawer/search/date flows, and app crash/ANR logs. Existing AVDs remain untouched. Owned CI AVDs and
evidence remain available; the emulator process stops after the checks.

A successful build retains a candidate under `releases/qa-VERSION-CODE-build-NUMBER`, including
the APK, metadata, report, unit results, mapping files and device evidence. Jenkins archives the
results too. There is no automatic history deletion policy. Monitor disk usage and preserve
release history when moving old evidence to backed-up storage.

## Publish or retry publication

```sh
# Retain versioned files privately without changing the feed or file sharing.
python3 ci/jenkins/control.py run --job publish --release-id qa-1.0.8-9-build-1

# Release the verified candidate on the existing QA update feed.
python3 ci/jenkins/control.py run --job publish --release-id qa-1.0.8-9-build-1 --promote
python3 ci/jenkins/control.py status --job publish
```

Use the actual successful build number. Publication reuses the publisher from that candidate's
exact commit. It validates the retained QA result and bytes, uploads immutable versioned files,
and downloads them back for digest verification. Promotion makes only the APK link-readable,
verifies a full unauthenticated download, then updates the existing QA feed object in place and
reads it back. Equal-version retries require identical payloads; older/conflicting versions fail.
Prior artifacts and the private folder listing remain intact.

The publication receipt is the release status record. A build pass or an upload attempt alone
does not establish release. Keep publication serialized and avoid editing the QA feed manually
during a job. Drive/rclone does not provide a transactional compare-and-swap across external writers.

After publication, validate the prior installed build's actual About → download → system-installer
upgrade. The automated quiesced ADB smoke is not a substitute for that delivery check. Preserve the
existing Android foreground-replacement limitation described in DIRECT_DISTRIBUTION.md.

## Maintenance and recovery

Homebrew installs Jenkins LTS and rclone from their established distributions. The official Jenkins
plugin manager installs `ci/jenkins/plugins.lock.txt`; `plugins.txt` records the requested features.
The initial installation used Jenkins 2.568.3 and rclone 1.75.1. Update deliberately, validate a build,
and retain the previous configuration before changing versions.

`ci/jenkins/install_local.py controller` and `agent` provision the local launchd services idempotently.
`ci/jenkins/control.py configure` loads the reviewed Jenkinsfiles into the two jobs. Back up the
private Jenkins state securely, including its secret-encryption material, alongside the existing
offline signing-key backup. Never publish that backup as a build artifact.

If authentication expires, publication stops without advancing the feed. Refresh the existing SDK
login through Google's supported sign-in flow, then rerun the same publication job. If build or QA
fails, retain its commit and evidence, fix the cause in another commit, and start a new build.
Do not erase completed engineering work to compensate for a transport failure.

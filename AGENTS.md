# Expressive Launcher — Agent Guidelines & Operational Rules

## 1. Engineering Rigor & Ground Truth
- **Zero Sycophancy & Brutal Truth**: Never claim a bug, build, test, or signature is "fixed", "passed", or "verified" without concrete verification data and exit codes.
- **Strict Evidence Tracking**: Distinguish strictly between **Direct Evidence** (read directly from logs, command output, or device telemetry), **Inference** (clearly labeled deduction), and **Unknowns** (unmeasured or unverifiable state).
- **No Speculating**: Never list hypothetical causes when device telemetry, Android dumps, logcat, or test outputs can be measured directly.

## 2. Tooling & Environment Contracts
- **Native Automation Mandate**: Always use existing scripts in `scripts/` and `ci/` for versioning, changelog generation, release testing, verification, and announcements. Never manually craft BBCode, changelog txt files, or ad-hoc release flows when dedicated automation exists.
- **Python Runtime**: Always execute repository Python scripts using `/opt/homebrew/bin/python3.14`. Do not use macOS system Python 3.9, which lacks required typing features.
- **Java Runtime**: JDK 21 ARM64 only:
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  ```
- **Android Target**: Android SDK 37.1 (Android 17 API 37). Submodule `platform_frameworks_libs_systemui` must remain initialized.

## 3. Release Governance Policies
- **Minimum Scope Requirement**: Every release candidate requires at least 3 distinct, substantive improvements (feedback, UX flows, Pixel parity, performance). Standalone micro-fixes are never released alone.
- **Versioning Standard**: `MAJOR.MINOR.PATCH` with patch capped at 9 (`x.y.9` rolls over to `x.(y+1).0`). Major overhauls bump `(MAJOR+1).0.0`. `versionCode` increments by +1 every release and never resets. Always use `python3 scripts/bump_version.py`.
- **Distribution Channels**:
  - Primary: GitHub Releases and in-app update feed (`updates/qa-v2/latest.json` or `updates/release/latest.json`).
  - Community: Obtainium (`obtainium://add/https://github.com/denson9874/ExpressiveLauncher`), Telegram (`@ExpressiveLauncher`), XDA Developers thread.
- **Session Confirmation Boundaries**:
  - XDA forum postings and Google Play uploads (`--with-play`) require explicit user confirmation in the session before execution.
  - Telegram announcements may be rendered with `--dry-run` to generate `TELEGRAM_CHANGELOG.txt` without network transmission.
- **Credential & Secret Protection**: Never commit APKs, logs, screenshots, AVD images, private keys, keystores, or API tokens to git.

## 4. Automation Reference
See `.agents/skills/expressive-release-automation/SKILL.md` for complete runbook details.
- Unified release: `python3 scripts/release_unified.py`
- Version bumping: `python3 scripts/bump_version.py`
- Telegram announcement: `python3 scripts/post_telegram.py`
- XDA formatting: `python3 scripts/format_xda.py`
- Play Protect validation: `python3 scripts/verify_developer_play_protect.py`
- CI Contract suite: `python3 -m unittest discover -s ci/tests -v`

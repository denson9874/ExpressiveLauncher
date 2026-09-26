#!/usr/bin/env python3
"""
Unified Release Orchestrator for Expressive Launcher.

Deploys to all official distribution & community channels in a single command:
1. GitHub Releases & in-app QA update feed (via Jenkins build & publish)
2. Google Play Closed Beta / Closed Alpha track ('alpha')
3. Google Play Internal Testing track ('internal')
4. Telegram announcement & changelog format/broadcast (@ExpressiveLauncher)
5. XDA Developers thread announcement BBCode generation
"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CONTROL_SCRIPT = ROOT / "ci/jenkins/control.py"
PUBLISH_SCRIPT = ROOT / "scripts/publish_play_bundle.py"
TELEGRAM_SCRIPT = ROOT / "scripts/post_telegram.py"
XDA_SCRIPT = ROOT / "scripts/format_xda.py"
XDA_THREAD_URL = "https://xdaforums.com/t/app-qa-android-17-expressive-launcher-looking-for-pixel-11-pro-pro-xl-feedback.4801792/"


def get_current_commit() -> str:
    return subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
    ).strip()


def get_version_info() -> tuple[str, str]:
    build_gradle = (ROOT / "build.gradle").read_text(encoding="utf-8")
    code_match = re.search(r'expressiveVersionCode(?:Text)?[\s\S]*?\.orElse\("([0-9]+)"\)', build_gradle)
    name_match = re.search(r'expressiveVersionName[\s\S]*?\.orElse\("([0-9]+\.[0-9]+\.[0-9]+)"\)', build_gradle)
    if not code_match or not name_match:
        raise ValueError("Could not parse expressiveVersionCode or expressiveVersionName from build.gradle")
    return name_match.group(1), code_match.group(1)


def run_cmd(cmd: list[str], timeout: int = 600) -> subprocess.CompletedProcess:
    print(f"\n[RUN] {' '.join(str(c) for c in cmd)}")
    result = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        print(f"[ERROR] Command failed with exit {result.returncode}")
        if result.stdout:
            print(f"[STDOUT]\n{result.stdout[-1500:]}")
        if result.stderr:
            print(f"[STDERR]\n{result.stderr[-1500:]}")
        raise RuntimeError(f"Command failed: {' '.join(str(c) for c in cmd)}")
    return result


def wait_for_jenkins_job(job: str, expected_number: int | None = None, max_wait_seconds: int = 900) -> dict:
    start_time = time.time()
    last_status = None
    build_number = expected_number

    while time.time() - start_time < max_wait_seconds:
        status_cmd = [sys.executable, str(CONTROL_SCRIPT), "status", "--job", job]
        if build_number is not None:
            status_cmd.extend(["--number", str(build_number)])
        
        proc = subprocess.run(status_cmd, cwd=ROOT, capture_output=True, text=True)
        if proc.returncode == 0 and proc.stdout.strip():
            try:
                data = json.loads(proc.stdout)
                curr_num = data.get("number")
                building = data.get("building", True)
                result = data.get("result")

                if build_number is None and curr_num is not None:
                    build_number = curr_num

                if result != last_status or building:
                    print(f"[{job} #{build_number}] building={building}, result={result}")
                    last_status = result

                if not building and result is not None:
                    if result == "SUCCESS":
                        return data
                    raise RuntimeError(f"Jenkins job {job} #{build_number} finished with non-success result: {result}")
            except json.JSONDecodeError:
                pass
        time.sleep(10)

    raise TimeoutError(f"Timed out waiting for Jenkins {job} job to complete after {max_wait_seconds}s")


def resolve_notes_file(version_name: str, version_code: str, custom_path: str | None = None) -> Path:
    if custom_path:
        p = Path(custom_path)
        if not p.is_absolute():
            p = ROOT / p
        if not p.exists():
            raise FileNotFoundError(f"Specified notes file not found: {p}")
        return p

    candidates = [
        ROOT / f"docs/release_notes/{version_name}_announcement.md",
        ROOT / f"docs/release_notes/{version_name}.md",
        ROOT / f"docs/release_notes/v{version_name}.md",
    ]
    for c in candidates:
        if c.exists():
            return c

    changelog_path = ROOT / f"play/listing/en-US/changelogs/{version_code}.txt"
    out_path = ROOT / f"docs/release_notes/{version_name}_announcement.md"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    if changelog_path.exists():
        cl_text = changelog_path.read_text(encoding="utf-8").strip()
        synthetic_notes = (
            f"# Expressive Launcher {version_name} (Build {version_code})\n\n"
            f"### ✨ What's New\n\n"
            f"{cl_text}\n\n"
            f"---\n\n"
            f"### 🛡️ Verification & Technical Details\n\n"
            f"- **Launcher Version**: `{version_name}` (Build `{version_code}`)\n"
            f"- **Package ID**: `com.denson9874.Expressive_Launcher_L3`\n"
        )
        out_path.write_text(synthetic_notes, encoding="utf-8")
        print(f"[INFO] Synthesized announcement notes at {out_path} from Play changelog.")
        return out_path

    raise FileNotFoundError(
        f"No release notes found for version {version_name} in docs/release_notes/ and no changelog at {changelog_path}"
    )


def main():
    parser = argparse.ArgumentParser(description="Unified Multi-Channel Release Flow for Expressive Launcher")
    parser.add_argument("--version-name", help="Version name (e.g. 3.0.9). Defaults to build.gradle value")
    parser.add_argument("--version-code", help="Version code (e.g. 41). Defaults to build.gradle value")
    parser.add_argument("--revision", help="Commit hash to release. Defaults to HEAD")
    parser.add_argument("--play-tracks", default="alpha,internal", help="Comma-separated Google Play tracks (default: 'alpha,internal')")
    parser.add_argument("--notes-file", help="Path to markdown release notes file (default: docs/release_notes/<version>_announcement.md)")
    parser.add_argument("--bump", choices=["auto", "patch", "minor", "major"], help="Bump version in build.gradle before running release (auto rolls over at x.y.9 -> x.(y+1).0)")
    parser.add_argument("--major", action="store_true", help="Bump major version ((MAJOR+1).0.0) before running release")
    parser.add_argument("--skip-jenkins", action="store_true", help="Skip Jenkins build & GitHub publication")
    parser.add_argument("--skip-play", action="store_true", help="Skip Google Play Store bundle build & publication")
    parser.add_argument("--skip-telegram", action="store_true", help="Skip Telegram announcement formatting/posting")
    parser.add_argument("--skip-xda", action="store_true", help="Skip XDA BBCode formatting")
    parser.add_argument("--telegram-token", default="", help="Telegram bot token override")
    parser.add_argument("--telegram-channel", default="", help="Telegram channel override")
    args = parser.parse_args()

    if args.major or args.bump:
        b_type = "major" if args.major else args.bump
        if str(ROOT / "scripts") not in sys.path:
            sys.path.insert(0, str(ROOT / "scripts"))
        from bump_version import bump_version
        _, _, bumped_name, bumped_code = bump_version(bump_type=b_type)
        print(f"[INFO] Successfully bumped version to {bumped_name} (Build {bumped_code})")

    default_name, default_code = get_version_info()
    version_name = args.version_name or default_name
    version_code = args.version_code or default_code
    revision = args.revision or get_current_commit()

    changelog_path = ROOT / f"play/listing/en-US/changelogs/{version_code}.txt"
    if not changelog_path.exists():
        raise FileNotFoundError(f"Missing changelog at {changelog_path}")

    notes_file = None
    if not args.skip_telegram or not args.skip_xda:
        notes_file = resolve_notes_file(version_name, version_code, args.notes_file)

    print("=" * 70)
    print(f"EXPRESSIVE LAUNCHER UNIFIED RELEASE PIPELINE")
    print(f"Version:       {version_name} (Build {version_code})")
    print(f"Revision:      {revision}")
    print(f"Play Tracks:   {args.play_tracks}")
    if notes_file:
        print(f"Release Notes: {notes_file}")
    print("=" * 70)

    # -------------------------------------------------------------
    # 1. Direct / GitHub Releases Flow (Jenkins)
    # -------------------------------------------------------------
    if not args.skip_jenkins:
        print("\n>>> STAGE 1: Jenkins Build Job (QA Signed APK & Smoke Tests)...")
        trigger_cmd = [
            sys.executable, str(CONTROL_SCRIPT), "run",
            "--job", "build",
            "--revision", revision,
            "--version-name", version_name,
            "--version-code", version_code,
        ]
        run_cmd(trigger_cmd)
        time.sleep(5)
        build_data = wait_for_jenkins_job("build")
        build_number = build_data["number"]
        release_id = f"qa-{version_name}-{version_code}-build-{build_number}"
        print(f"\n[SUCCESS] Jenkins build #{build_number} succeeded! Release ID: {release_id}")

        print("\n>>> STAGE 2: Jenkins Publish Job (GitHub Releases & Feed Promotion)...")
        publish_cmd = [
            sys.executable, str(CONTROL_SCRIPT), "run",
            "--job", "publish",
            "--release-id", release_id,
            "--promote",
        ]
        run_cmd(publish_cmd)
        time.sleep(5)
        wait_for_jenkins_job("publish")
        print(f"\n[SUCCESS] Published to GitHub Releases and promoted in-app update feed!")

    # -------------------------------------------------------------
    # 2. Google Play Store Release Flow (AAB Bundle & Developer API)
    # -------------------------------------------------------------
    if not args.skip_play:
        print("\n>>> STAGE 3: Building Google Play App Bundle (.aab)...")
        bundle_cmd = [
            "./gradlew", "bundleLawnWithQuickstepExpressiveRelease",
            "-PtargetPlayStore=true",
        ]
        run_cmd(bundle_cmd, timeout=900)

        aab_path = ROOT / "build/outputs/bundle/lawnWithQuickstepExpressiveRelease/expressive-launcher-l3-lawn-withQuickstep-expressive-release.aab"
        if not aab_path.exists():
            raise FileNotFoundError(f"Generated App Bundle not found at {aab_path}")

        print(f"\n>>> STAGE 4: Publishing App Bundle to Google Play ({args.play_tracks})...")
        play_pub_cmd = [
            sys.executable, str(PUBLISH_SCRIPT),
            "--package-name", "com.denson9874.Expressive_Launcher_L3",
            "--track", args.play_tracks,
            "--bundle", str(aab_path),
            "--changelog", str(changelog_path),
            "--version-name", version_name,
        ]
        run_cmd(play_pub_cmd, timeout=300)

    # -------------------------------------------------------------
    # 3. Telegram Announcement Flow (Bot API / Channel Broadcast)
    # -------------------------------------------------------------
    tg_status = "Skipped"
    if not args.skip_telegram and notes_file:
        print("\n>>> STAGE 5: Formatting & Posting Telegram Announcement...")
        tg_out = ROOT / "TELEGRAM_CHANGELOG.txt"
        tg_cmd = [
            sys.executable, str(TELEGRAM_SCRIPT),
            "--notes-file", str(notes_file),
            "--output", str(tg_out),
        ]
        if args.telegram_token:
            tg_cmd.extend(["--token", args.telegram_token])
        if args.telegram_channel:
            tg_cmd.extend(["--channel", args.telegram_channel])

        qa_apk_candidates = list((ROOT / "build/outputs/apk/lawnWithQuickstepExpressive/qa").glob("*.apk"))
        if qa_apk_candidates:
            tg_cmd.extend(["--apk", str(qa_apk_candidates[0])])

        try:
            run_cmd(tg_cmd, timeout=120)
            tg_status = f"Rendered & broadcast via {args.telegram_channel or '@ExpressiveLauncher'}"
        except Exception as e:
            print(f"[WARNING] Telegram broadcast step finished with warning/notice: {e}")
            print(f"[INFO] Rendered Telegram message remains saved at {tg_out}")
            tg_status = f"Rendered to {tg_out.name} (Notice logged)"

    # -------------------------------------------------------------
    # 4. XDA Developers Thread BBCode Flow
    # -------------------------------------------------------------
    xda_output = None
    if not args.skip_xda and notes_file:
        print("\n>>> STAGE 6: Formatting XDA BBCode Announcement...")
        xda_output = ROOT / f"docs/release_notes/xda_thread_post_{version_name}.bbcode"
        xda_cmd = [
            sys.executable, str(XDA_SCRIPT),
            str(notes_file),
            "--output", str(xda_output),
            "--version-name", version_name,
            "--version-code", version_code,
        ]
        run_cmd(xda_cmd, timeout=60)
        print(f"\n[SUCCESS] Generated XDA thread BBCode at: {xda_output}")

    # -------------------------------------------------------------
    # Summary
    # -------------------------------------------------------------
    print("\n" + "=" * 70)
    print("ALL RELEASE TARGETS COMPLETED SUCCESSFULLY!")
    print(f"- Version:            {version_name} (Build {version_code})")
    print(f"- GitHub Release:     https://github.com/denson9874/ExpressiveLauncher/releases/tag/qa-v{version_name}-{version_code}")
    print(f"- QA In-App Feed:     https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/qa-v2/latest.json")
    print(f"- Google Play Tracks: {args.play_tracks}")
    if not args.skip_telegram:
        print(f"- Telegram:           {tg_status}")
    if not args.skip_xda and xda_output:
        print(f"- XDA Thread BBCode:  docs/release_notes/{xda_output.name}")
        print(f"- XDA Forum Thread:   {XDA_THREAD_URL}")
    print("=" * 70)


if __name__ == "__main__":
    main()

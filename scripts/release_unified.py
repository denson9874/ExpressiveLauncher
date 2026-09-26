#!/usr/bin/env python3
"""
Unified Release Orchestrator for Expressive Launcher.

Deploys to all three official channels in a single command:
1. GitHub Releases & in-app QA update feed (via Jenkins build & publish)
2. Google Play Closed Beta / Closed Alpha track ('alpha')
3. Google Play Internal Testing track ('internal')
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


def get_current_commit() -> str:
    return subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
    ).strip()


def get_version_info() -> tuple[str, str]:
    build_gradle = (ROOT / "build.gradle").read_text(encoding="utf-8")
    code_match = re.search(r'expressiveVersionCode.*"([0-9]+)"', build_gradle)
    name_match = re.search(r'expressiveVersionName.*"([0-9]+\.[0-9]+\.[0-9]+)"', build_gradle)
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


def main():
    parser = argparse.ArgumentParser(description="Unified Multi-Channel Release Flow for Expressive Launcher")
    parser.add_argument("--version-name", help="Version name (e.g. 3.0.9). Defaults to build.gradle value")
    parser.add_argument("--version-code", help="Version code (e.g. 41). Defaults to build.gradle value")
    parser.add_argument("--revision", help="Commit hash to release. Defaults to HEAD")
    parser.add_argument("--play-tracks", default="alpha,internal", help="Comma-separated Google Play tracks (default: 'alpha,internal')")
    parser.add_argument("--skip-jenkins", action="store_true", help="Skip Jenkins build & GitHub publication")
    parser.add_argument("--skip-play", action="store_true", help="Skip Google Play Store bundle build & publication")
    args = parser.parse_args()

    default_name, default_code = get_version_info()
    version_name = args.version_name or default_name
    version_code = args.version_code or default_code
    revision = args.revision or get_current_commit()

    changelog_path = ROOT / f"play/listing/en-US/changelogs/{version_code}.txt"
    if not changelog_path.exists():
        raise FileNotFoundError(f"Missing changelog at {changelog_path}")

    print("=" * 70)
    print(f"EXPRESSIVE LAUNCHER UNIFIED RELEASE PIPELINE")
    print(f"Version:      {version_name} (Build {version_code})")
    print(f"Revision:     {revision}")
    print(f"Play Tracks:  {args.play_tracks}")
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
    # Summary
    # -------------------------------------------------------------
    print("\n" + "=" * 70)
    print("ALL RELEASE TARGETS COMPLETED SUCCESSFULLY!")
    print(f"- Version:           {version_name} (Build {version_code})")
    print(f"- GitHub Release:    https://github.com/denson9874/ExpressiveLauncher/releases/tag/qa-v{version_name}-{version_code}")
    print(f"- QA In-App Feed:    https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/qa-v2/latest.json")
    print(f"- Google Play Tracks: {args.play_tracks}")
    print("=" * 70)


if __name__ == "__main__":
    main()

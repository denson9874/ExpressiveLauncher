#!/usr/bin/env python3
"""
Version Bumping Script for Expressive Launcher.

Enforces release versioning policy:
1. Version format is MAJOR.MINOR.PATCH (e.g. 3.0.9).
2. Patch number must not exceed 9. When rolling over from x.y.9, the next
   standard release moves up to x.(y+1).0 (e.g. 3.0.9 -> 3.1.0 -> 3.1.1).
3. Whenever a major improvement is implemented, version bumps to (MAJOR+1).0.0 (e.g. 4.0.0, 5.0.0).
4. Version code increments monotonically by 1 (e.g. 41 -> 42).
"""

import argparse
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
BUILD_GRADLE = ROOT / "build.gradle"


def get_current_version_info() -> tuple[str, str]:
    build_gradle = BUILD_GRADLE.read_text(encoding="utf-8")
    code_match = re.search(r'expressiveVersionCode(?:Text)?[\s\S]*?\.orElse\("([0-9]+)"\)', build_gradle)
    name_match = re.search(r'expressiveVersionName[\s\S]*?\.orElse\("([0-9]+\.[0-9]+\.[0-9]+)"\)', build_gradle)
    if not code_match or not name_match:
        raise ValueError("Could not parse expressiveVersionCode or expressiveVersionName from build.gradle")
    return name_match.group(1), code_match.group(1)


def compute_next_version(current_version: str, bump_type: str = "auto") -> str:
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)$", current_version)
    if not m:
        raise ValueError(f"Invalid current version format: '{current_version}' (expected MAJOR.MINOR.PATCH)")
    major, minor, patch = int(m.group(1)), int(m.group(2)), int(m.group(3))

    if bump_type == "major":
        return f"{major + 1}.0.0"
    elif bump_type == "minor":
        return f"{major}.{minor + 1}.0"
    elif bump_type in ("patch", "auto"):
        # Rule: Patch must not exceed 9; rollover x.y.9 -> x.(y+1).0
        if patch < 9:
            return f"{major}.{minor}.{patch + 1}"
        else:
            return f"{major}.{minor + 1}.0"
    else:
        raise ValueError(f"Unknown bump type: {bump_type}")


def bump_version(
    bump_type: str = "auto",
    custom_name: str | None = None,
    custom_code: str | None = None,
    changelog_message: str | None = None,
    dry_run: bool = False,
) -> tuple[str, str, str, str]:
    current_name, current_code = get_current_version_info()
    next_name = custom_name or compute_next_version(current_name, bump_type)
    next_code = custom_code or str(int(current_code) + 1)

    if dry_run:
        print(f"[DRY RUN] Current: {current_name} (Build {current_code}) -> Next: {next_name} (Build {next_code})")
        return current_name, current_code, next_name, next_code

    content = BUILD_GRADLE.read_text(encoding="utf-8")

    # Replace versionCode: .orElse("41")
    def replace_code(m):
        full = m.group(0)
        return full.replace(f'"{current_code}"', f'"{next_code}"')

    content, n_code = re.subn(
        r'expressiveVersionCode(?:Text)?[\s\S]*?\.orElse\("[0-9]+"\)',
        replace_code,
        content,
        count=1,
    )
    if n_code != 1:
        raise RuntimeError("Failed to update expressiveVersionCode in build.gradle")

    # Replace versionName: .orElse("3.0.9")
    def replace_name(m):
        full = m.group(0)
        return full.replace(f'"{current_name}"', f'"{next_name}"')

    content, n_name = re.subn(
        r'expressiveVersionName[\s\S]*?\.orElse\("[0-9]+\.[0-9]+\.[0-9]+"\)',
        replace_name,
        content,
        count=1,
    )
    if n_name != 1:
        raise RuntimeError("Failed to update expressiveVersionName in build.gradle")

    BUILD_GRADLE.write_text(content, encoding="utf-8")
    print(f"Updated build.gradle: {current_name} (Build {current_code}) -> {next_name} (Build {next_code})")

    feed_gradle = ROOT / "expressiveFeed/build.gradle"
    if feed_gradle.exists():
        feed_content = feed_gradle.read_text(encoding="utf-8")
        feed_content, _ = re.subn(
            r'expressiveVersionCode(?:Text)?[\s\S]*?\.orElse\("[0-9]+"\)',
            replace_code,
            feed_content,
            count=1,
        )
        feed_content, _ = re.subn(
            r'expressiveVersionName[\s\S]*?\.orElse\("[0-9]+\.[0-9]+\.[0-9]+"\)',
            replace_name,
            feed_content,
            count=1,
        )
        feed_gradle.write_text(feed_content, encoding="utf-8")
        print(f"Updated expressiveFeed/build.gradle: {current_name} (Build {current_code}) -> {next_name} (Build {next_code})")

    # Prepare changelog files (Play Store & Fastlane / IzzyOnDroid)
    changelog_paths = [
        ROOT / f"play/listing/en-US/changelogs/{next_code}.txt",
        ROOT / f"fastlane/metadata/android/en-US/changelogs/{next_code}.txt",
    ]
    msg = changelog_message or f"- Expressive Launcher {next_name} update with performance improvements and bug fixes."
    for cl_path in changelog_paths:
        if not cl_path.exists():
            cl_path.parent.mkdir(parents=True, exist_ok=True)
            cl_path.write_text(msg.strip() + "\n", encoding="utf-8")
            print(f"Created changelog at {cl_path}")

    # Update .fdroid.yml if present
    fdroid_path = ROOT / ".fdroid.yml"
    if fdroid_path.exists():
        fdroid_txt = fdroid_path.read_text(encoding="utf-8")
        fdroid_txt = re.sub(r"CurrentVersion:\s*.+", f"CurrentVersion: {next_name}", fdroid_txt)
        fdroid_txt = re.sub(r"CurrentVersionCode:\s*.+", f"CurrentVersionCode: {next_code}", fdroid_txt)
        fdroid_path.write_text(fdroid_txt, encoding="utf-8")
        print(f"Updated .fdroid.yml: CurrentVersion={next_name}, CurrentVersionCode={next_code}")

    # Prepare draft release announcement markdown if missing
    announcement_path = ROOT / f"docs/release_notes/{next_name}_announcement.md"
    if not announcement_path.exists():
        announcement_content = (
            f"# Expressive Launcher {next_name} (Build {next_code})\n\n"
            f"### ✨ What's New & Improved\n\n"
            f"- Bug fixes, stability improvements, and Android 17 Pixel parity updates.\n\n"
            f"---\n\n"
            f"### 🛡️ Verification & Technical Details\n\n"
            f"- **Launcher Version**: `{next_name}` (Build `{next_code}`)\n"
            f"- **Package ID**: `dev.launcher.expressive.l3`\n"
            f"- **Release Certificate SHA-256**: `C1:41:60:30:6D:5C:05:9B:3D:11:9F:15:FB:74:E0:8C:57:CC:27:23:16:E8:0B:36:E1:92:C7:1D:C9:E4:D0:D2`\n\n"
            f"🔗 **Links & Downloads**:\n"
            f"- **1-Click Add to Obtainium**: [Add to Obtainium](obtainium://add/https://github.com/denson9874/ExpressiveLauncher)\n"
            f"- **GitHub Repository**: [https://github.com/denson9874/ExpressiveLauncher](https://github.com/denson9874/ExpressiveLauncher)\n"
            f"- **Community & Feedback**: [@ExpressiveLauncherFeedback](https://t.me/ExpressiveLauncherFeedback)\n"
        )
        announcement_path.parent.mkdir(parents=True, exist_ok=True)
        announcement_path.write_text(announcement_content, encoding="utf-8")
        print(f"Created release announcement draft at {announcement_path}")

    return current_name, current_code, next_name, next_code


def main():
    parser = argparse.ArgumentParser(description="Bump Expressive Launcher version with SemVer 0-9 patch rollover")
    parser.add_argument(
        "--type",
        choices=["auto", "patch", "minor", "major"],
        default="auto",
        help="Type of bump: 'auto' (default, rolls over at x.y.9 -> x.(y+1).0), 'major' (x.0.0), 'minor', or 'patch'",
    )
    parser.add_argument("--major", action="store_true", help="Shorthand for --type major (bumps to (MAJOR+1).0.0)")
    parser.add_argument("--version-name", help="Explicit version name override (e.g. 3.1.0)")
    parser.add_argument("--version-code", help="Explicit version code override (e.g. 42)")
    parser.add_argument("--changelog", help="Changelog text for Google Play changelog file")
    parser.add_argument("--dry-run", action="store_true", help="Print expected changes without modifying files")

    args = parser.parse_args()
    bump_type = "major" if args.major else args.type

    bump_version(
        bump_type=bump_type,
        custom_name=args.version_name,
        custom_code=args.version_code,
        changelog_message=args.changelog,
        dry_run=args.dry_run,
    )


if __name__ == "__main__":
    main()

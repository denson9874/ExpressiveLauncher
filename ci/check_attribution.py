#!/usr/bin/env python3
# Copyright 2026 Daryl Denson and Expressive Launcher contributors.
# SPDX-License-Identifier: Apache-2.0
# https://github.com/denson9874/ExpressiveLauncher

"""Check that repository attribution and retained license texts stay intact."""

import argparse
import hashlib
from pathlib import Path


REQUIRED_FILES = (
    "NOTICE",
    "LICENSE.txt",
    "docs/REUSE_AND_ATTRIBUTION.md",
    "docs/SOURCE_PROVENANCE.md",
    "docs/THIRD_PARTY_NOTICES.md",
)
NOTICE_MARKERS = (
    "Expressive Launcher",
    "https://github.com/denson9874/ExpressiveLauncher",
    "Copyright 2026 Daryl Denson and Expressive Launcher contributors.",
    "The Android Open Source Project",
    "Lawnchair",
)
PACKAGED_NOTICE = "lawnchair/assets/expressive-NOTICE.txt"

# Baseline license bytes from the public source checkout. An intentional license
# update needs a review of the applicable rights before changing these digests.
LICENSE_SHA256 = {
    "LICENSE.txt": "4bb3ad323fe4c27f06a47b39c0ae6ad35315f15c40e46c81a5211f7f519286ab",
    "lawnchair/src/app/lawnchair/search/algorithms/data/calculator/LICENSE":
        "6f1b3f6fecde974467f78fec9fdad2614c1652ec2bda13206b83c2f9f083a0c9",
    "docs/licenses/GoogleSansFlex-OFL.txt":
        "8e8f5ee54c1431ed9c56a2f80dbce767d20d849ab7314434c1460a06d5e566b7",
}
ATTRIBUTED_SOURCES = (
    "lawnchair/src/app/lawnchair/feed/ExpressiveFeedSetup.kt",
    "lawnchair/src/app/lawnchair/ui/preferences/about/ExpressiveUpdatePolicy.kt",
    "lawnchair/src/app/lawnchair/ui/preferences/about/ExpressiveUpdateNotifications.kt",
    "lawnchair/src/app/lawnchair/ui/preferences/about/ExpressiveUpdateNotificationControl.kt",
)
SOURCE_MARKERS = (
    "Expressive Launcher",
    "Copyright 2026 Daryl Denson and Expressive Launcher contributors.",
    "https://github.com/denson9874/ExpressiveLauncher",
    "SPDX-License-Identifier: Apache-2.0",
)


def check(root: Path) -> list[str]:
    """Return actionable errors without changing the checkout or using a network."""
    root = Path(root)
    errors = []
    contents = {}
    texts = {}
    paths = dict.fromkeys((*REQUIRED_FILES, PACKAGED_NOTICE, *LICENSE_SHA256, *ATTRIBUTED_SOURCES))
    for relative in paths:
        path = root / relative
        if path.is_symlink() or not path.is_file():
            errors.append(f"{relative}: missing required regular file (symlinks are not accepted).")
            continue
        try:
            contents[relative] = path.read_bytes()
            texts[relative] = contents[relative].decode("utf-8")
        except (OSError, UnicodeError) as error:
            errors.append(f"{relative}: cannot read as UTF-8: {error}")
            continue
        if not texts[relative].strip():
            errors.append(f"{relative}: required file is empty.")

    if "NOTICE" in texts:
        for marker in NOTICE_MARKERS:
            if marker not in texts["NOTICE"]:
                errors.append(f"NOTICE: missing attribution {marker!r}.")
    if "NOTICE" in contents and PACKAGED_NOTICE in contents:
        if contents["NOTICE"] != contents[PACKAGED_NOTICE]:
            errors.append(f"{PACKAGED_NOTICE}: must exactly match the root NOTICE; update both together.")

    for relative, expected in LICENSE_SHA256.items():
        if relative in contents and hashlib.sha256(contents[relative]).hexdigest() != expected:
            errors.append(f"{relative}: retained license text changed; intentional updates require rights review "
                          "and a reviewed SHA-256 baseline update in ci/check_attribution.py.")

    for relative in ATTRIBUTED_SOURCES:
        if relative in texts:
            for marker in SOURCE_MARKERS:
                if marker not in texts[relative][:1500]:
                    errors.append(f"{relative}: first 1500 characters must retain {marker!r}.")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1],
                        help="repository root (defaults to this script's checkout)")
    errors = check(parser.parse_args().root)
    if errors:
        print("Attribution checks failed:")
        for error in errors:
            print(f"- {error}")
        return 1
    print("Attribution checks passed: notices, source headers, and retained licenses are intact.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

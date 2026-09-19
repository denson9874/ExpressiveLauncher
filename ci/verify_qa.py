#!/usr/bin/env python3
"""Verify an immutable Expressive QA APK and write release metadata.

Only Android's apksigner/aapt2 executables and Python's standard library are used.
The supplied baseline must be the previously delivered QA APK, not a new build.
No signing key is read and neither APK is modified.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile


QA_PACKAGE = "dev.launcher.expressive.l3.debug"
EXPECTED_CERTIFICATE_SHA256 = (
    "c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2"
)


class VerificationError(ValueError):
    """An APK or tool result does not meet the QA release contract."""


def parse_badging(output):
    """Read only the unambiguous package record and application debug marker."""
    packages = re.findall(r"^package: (.+)$", output, re.MULTILINE)
    if len(packages) != 1:
        raise VerificationError("aapt2 must report exactly one package record")
    fields = re.findall(r"(?:^|\s)([A-Za-z][A-Za-z0-9]*)='([^']*)'", packages[0])
    identity = {}
    for key in ("name", "versionCode", "versionName"):
        values = [value for field, value in fields if field == key]
        if len(values) != 1 or not values[0]:
            raise VerificationError("aapt2 package record has missing or duplicate " + key)
        identity[key] = values[0]
    if not re.fullmatch(r"[0-9]+", identity["versionCode"]):
        raise VerificationError("aapt2 versionCode is not a positive integer")
    code = int(identity["versionCode"])
    if not 1 <= code <= 2_100_000_000:
        raise VerificationError("aapt2 versionCode is outside the Android release range")
    if not re.search(r"^application:\s", output, re.MULTILINE):
        raise VerificationError("aapt2 output is missing the application record")
    return {
        "packageName": identity["name"],
        "versionCode": code,
        "versionName": identity["versionName"],
        "debuggable": bool(re.search(r"^application-debuggable(?:\s|$)", output, re.MULTILINE)),
    }


def parse_signer(output):
    """Handle legacy Signer #1 and Build Tools 37 V2 Signer certificate labels."""
    if not re.search(r"^Verifies\s*$", output, re.MULTILINE):
        raise VerificationError("apksigner did not report a verified signature")
    counts = re.findall(r"^Number of signers:\s*(\d+)\s*$", output, re.MULTILINE)
    if counts != ["1"]:
        raise VerificationError("QA APK must have exactly one signer")
    matches = re.findall(
        r"^(?:Signer #([1-9][0-9]*)|V[1-4](?:\.\d+)? Signer(?: #([1-9][0-9]*))?)"
        r": certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$",
        output,
        re.MULTILINE,
    )
    fingerprints = set()
    for legacy_index, scheme_index, digest in matches:
        if (legacy_index or scheme_index or "1") != "1":
            raise VerificationError("QA APK reports more than one signer")
        normalized = digest.replace(":", "").lower()
        if not re.fullmatch(r"[0-9a-f]{64}", normalized):
            raise VerificationError("apksigner returned an invalid certificate SHA-256")
        fingerprints.add(normalized)
    if len(fingerprints) != 1:
        raise VerificationError("apksigner must report one unambiguous signing certificate")
    return fingerprints.pop()


def file_digest(path):
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            size += len(chunk)
            digest.update(chunk)
    if not size:
        raise VerificationError("APK is empty: " + str(path))
    return size, digest.hexdigest()


def run_tool(executable, *arguments):
    try:
        result = subprocess.run(
            [str(executable), *map(str, arguments)],
            capture_output=True,
            text=True,
            timeout=120,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise VerificationError("Could not run " + executable.name + ": " + str(error)) from error
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()[-2000:]
        raise VerificationError(
            f"{executable.name} failed (exit {result.returncode}): {detail or 'no diagnostic output'}"
        )
    return result.stdout


def inspect_apk(apk, build_tools):
    if not apk.is_file():
        raise VerificationError("APK does not exist or is not a regular file: " + str(apk))
    before = file_digest(apk)
    signer = parse_signer(run_tool(build_tools / "apksigner", "verify", "--verbose", "--print-certs", apk))
    identity = parse_badging(run_tool(build_tools / "aapt2", "dump", "badging", apk))
    if file_digest(apk) != before:
        raise VerificationError("APK changed while being verified: " + str(apk))
    return {
        "fileName": apk.name,
        **identity,
        "sizeBytes": before[0],
        "sha256": before[1],
        "certificateSha256": signer,
        "signatureVerified": True,
    }


def verify_feed_bundle(apk, candidate, build_tools):
    """Verify the installable helper retained inside launcher releases starting with code 10."""
    names = ("assets/expressive-feed/metadata.json", "assets/expressive-feed/ExpressiveFeed.apk")
    try:
        with zipfile.ZipFile(apk) as archive:
            for name, limit in zip(names, (16 * 1024, 64 * 1024 * 1024)):
                entries = [entry for entry in archive.infolist() if entry.filename == name]
                if len(entries) != 1 or not 0 < entries[0].file_size <= limit:
                    raise VerificationError("Missing, duplicate or oversized bundled Discover asset: " + name)
            metadata = json.loads(archive.read(names[0]), object_pairs_hook=unique_json_fields)
            helper_bytes = archive.read(names[1])
    except (OSError, zipfile.BadZipFile, ValueError, KeyError) as error:
        raise VerificationError("Cannot read the bundled Discover support: " + str(error)) from error
    required = {"schemaVersion", "packageName", "versionCode", "versionName", "sizeBytes", "sha256", "fileName"}
    if not isinstance(metadata, dict) or set(metadata) != required:
        raise VerificationError("Bundled Discover metadata has unexpected fields")
    expected = {
        "schemaVersion": 1, "packageName": "dev.launcher.expressive.feed",
        "versionCode": candidate["versionCode"], "versionName": candidate["versionName"],
        "sizeBytes": len(helper_bytes), "sha256": hashlib.sha256(helper_bytes).hexdigest(),
        "fileName": "ExpressiveFeed.apk",
    }
    if metadata != expected or any(type(metadata[key]) is not int for key in ("schemaVersion", "versionCode", "sizeBytes")):
        raise VerificationError("Bundled Discover metadata differs from its bytes or launcher version")
    with tempfile.TemporaryDirectory(prefix="expressive-feed-verify-") as temporary:
        helper = Path(temporary) / "ExpressiveFeed.apk"
        helper.write_bytes(helper_bytes)
        identity = inspect_apk(helper, build_tools)
        if any(identity[key] != expected[key] for key in ("packageName", "versionCode", "versionName", "sizeBytes", "sha256")):
            raise VerificationError("Bundled Discover APK identity differs from its metadata")
        if identity["certificateSha256"] != candidate["certificateSha256"]:
            raise VerificationError("Bundled Discover support is not signed by the launcher signer")
        if not identity["debuggable"]:
            raise VerificationError("Bundled Discover support cannot connect to Google's overlay")
        manifest = run_tool(build_tools / "aapt2", "dump", "xmltree", helper, "--file", "AndroidManifest.xml")
        if re.search(r"^\s*E: (?:activity|activity-alias|provider|receiver)(?:\s|$)", manifest, re.MULTILINE):
            raise VerificationError("Bundled Discover support must remain a service-only package")
        if len(re.findall(r"^\s*E: service(?:\s|$)", manifest, re.MULTILINE)) != 1:
            raise VerificationError("Bundled Discover support must contain exactly one service")
    return {**identity, "bundled": True, "signerMatches": True, "serviceOnly": True}


def unique_json_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise VerificationError("Duplicate Discover metadata field: " + key)
        result[key] = value
    return result


def verify_qa(apk, baseline_apk, version_name, version_code, build_tools):
    if not version_name or not 1 <= version_code <= 2_100_000_000:
        raise VerificationError("Expected versionName must be nonempty and versionCode must be 1..2100000000")
    for name in ("apksigner", "aapt2"):
        executable = build_tools / name
        if not executable.is_file() or not os.access(executable, os.X_OK):
            raise VerificationError("Missing executable Android build tool: " + str(executable))

    candidate = inspect_apk(apk, build_tools)
    baseline = inspect_apk(baseline_apk, build_tools)
    for label, artifact in (("Candidate", candidate), ("Baseline", baseline)):
        if artifact["packageName"] != QA_PACKAGE:
            raise VerificationError(f"{label} package must be {QA_PACKAGE}; got {artifact['packageName']}")
        if artifact["debuggable"]:
            raise VerificationError(f"{label} APK is debuggable; deliver the release-signed Qa variant")
        if artifact["certificateSha256"] != EXPECTED_CERTIFICATE_SHA256:
            raise VerificationError(f"{label} signing certificate does not match the durable Expressive QA signer")
    if candidate["certificateSha256"] != baseline["certificateSha256"]:
        raise VerificationError("Candidate signing certificate differs from the delivered baseline")
    if candidate["versionName"] != version_name or candidate["versionCode"] != version_code:
        raise VerificationError(
            f"Candidate version is {candidate['versionName']}/code{candidate['versionCode']}; "
            f"expected {version_name}/code{version_code}"
        )
    if candidate["versionCode"] <= baseline["versionCode"]:
        raise VerificationError("Candidate versionCode must be strictly newer than the delivered baseline")

    feed = verify_feed_bundle(apk, candidate, build_tools) if candidate["versionCode"] >= 10 else None

    # Recheck both files after all tool calls, including time spent inspecting the baseline.
    for path, artifact in ((apk, candidate), (baseline_apk, baseline)):
        if file_digest(path) != (artifact["sizeBytes"], artifact["sha256"]):
            raise VerificationError("APK changed before metadata could be written: " + str(path))
    return {
        "schemaVersion": 1,
        "channel": "qa",
        **candidate,
        **({"googleDiscoverSupport": feed} if feed is not None else {}),
        "baseline": {**baseline, "candidateIsNewer": True, "signerMatches": True},
    }


def write_metadata(output, metadata):
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output.parent, delete=False) as target:
            temporary = Path(target.name)
            json.dump(metadata, target, indent=2, sort_keys=True)
            target.write("\n")
        temporary.replace(output)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--baseline-apk", required=True, type=Path)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--build-tools", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        protected = [args.apk, args.baseline_apk, args.build_tools / "apksigner", args.build_tools / "aapt2"]
        for path in protected:
            if args.output.resolve() == path.resolve() or (
                args.output.exists() and path.exists() and args.output.samefile(path)
            ):
                raise VerificationError("Metadata output must not overwrite an APK or Android build tool")
        metadata = verify_qa(args.apk, args.baseline_apk, args.version_name, args.version_code, args.build_tools)
        write_metadata(args.output, metadata)
    except (VerificationError, OSError) as error:
        print("QA verification failed: " + str(error), file=sys.stderr)
        return 1
    print(f"Verified QA {metadata['versionName']}/code{metadata['versionCode']}: "
          f"{metadata['sizeBytes']} bytes, SHA-256 {metadata['sha256']}")
    print("Metadata: " + str(args.output))
    return 0


if __name__ == "__main__":
    sys.exit(main())

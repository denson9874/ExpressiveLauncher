#!/usr/bin/env python3
"""Publish an already-tested QA artifact with rclone; promote only with --promote.

Authentication uses the worker's existing gcloud login. Tokens live only in memory
and the rclone child environment. This script never authenticates interactively,
changes folder permissions, deletes history, or addresses the production feed.
Jenkins must serialize publishers: Drive does not offer compare-and-swap via rclone.
"""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request


QA_FOLDER_ID = "1x9QK-ZRgIJUqXXekfxr_KEzGqN_A0TiZ"
QA_FEED_ID = "1_A529DlPEMzwizq-6j3fpMBElGugY-_J"
QA_FEED_NAME = "expressive-launcher-qa-latest.json"
QA_PACKAGE = "dev.launcher.expressive.l3.debug"
QA_CERTIFICATE = "c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2"
FEED_FIELDS = ("schemaVersion", "channel", "packageName", "versionCode", "versionName",
               "apkUrl", "sha256", "sizeBytes", "releaseNotes")
REMOTE = "QADRIVE:"
MAX_JSON_BYTES = 1024 * 1024


class PublishError(Exception):
    """A failed gate or transport operation; safe to show in a build log."""


def require(condition, message):
    if not condition:
        raise PublishError(message)


def file_hash(path, algorithm="sha256"):
    digest = hashlib.new(algorithm)
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json_bytes(data, label):
    require(len(data) <= MAX_JSON_BYTES, f"{label} exceeds JSON size limit")
    try:
        result = json.loads(data)
    except (ValueError, UnicodeDecodeError):
        raise PublishError(f"{label} is not valid JSON") from None
    require(isinstance(result, dict), f"{label} must contain a JSON object")
    return result


def positive_integer(value):
    return type(value) is int and value > 0


def validate_identity(value, label):
    require(type(value.get("schemaVersion")) is int and value["schemaVersion"] == 1,
            f"{label}: unsupported schema")
    require(value.get("channel") == "qa", f"{label}: wrong channel")
    require(value.get("packageName") == QA_PACKAGE, f"{label}: wrong package")
    require(positive_integer(value.get("versionCode")), f"{label}: invalid versionCode")
    require(isinstance(value.get("versionName"), str) and value["versionName"].strip(),
            f"{label}: missing versionName")
    require(positive_integer(value.get("sizeBytes")), f"{label}: invalid sizeBytes")
    require(isinstance(value.get("sha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", value["sha256"]), f"{label}: invalid sha256")


def validate_feed(feed):
    validate_identity(feed, "QA feed")
    require(isinstance(feed.get("apkUrl"), str) and feed["apkUrl"].startswith("https://"),
            "QA feed: invalid apkUrl")
    require(isinstance(feed.get("releaseNotes"), str) and feed["releaseNotes"].strip(),
            "QA feed: missing releaseNotes")


def validate_transition(current, candidate, compare_url=True):
    """Reject rollback and same-version mutation, permitting exact retries."""
    validate_feed(current)
    validate_feed(candidate)
    require(candidate["versionCode"] >= current["versionCode"],
            "Refusing to publish an older version than the current QA feed")
    if candidate["versionCode"] == current["versionCode"]:
        fields = FEED_FIELDS if compare_url else tuple(k for k in FEED_FIELDS if k != "apkUrl")
        require(all(current.get(key) == candidate.get(key) for key in fields),
                "Current QA feed has the same versionCode with conflicting payload")
        return "unchanged"
    return "advance"


def load_artifacts(artifact_dir):
    directory = Path(artifact_dir).resolve(strict=True)
    seal_path = directory / "seal.json"
    require(seal_path.is_file() and not seal_path.is_symlink(),
            "Release seal.json is missing; an incomplete build cannot be published")
    seal = read_json_bytes(seal_path.read_bytes(), "seal.json")
    require(type(seal.get("schemaVersion")) is int and seal["schemaVersion"] == 1,
            "seal.json: unsupported schema")
    require(seal.get("complete") is True, "Release seal is incomplete")
    metadata = read_json_bytes((directory / "metadata.json").read_bytes(), "metadata.json")
    validate_identity(metadata, "metadata.json")
    require(metadata.get("certificateSha256") == QA_CERTIFICATE,
            "APK signer does not match the shipped QA certificate")
    require(metadata.get("signatureVerified") is True, "APK signature verification did not pass")
    require(metadata.get("debuggable") is False, "Refusing a debuggable APK")
    revision = metadata.get("sourceRevision")
    require(isinstance(revision, str) and re.fullmatch(r"[0-9a-f]{40,64}", revision),
            "metadata.json: missing exact sourceRevision")
    filename = metadata.get("fileName")
    require(isinstance(filename, str)
            and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*\.apk", filename),
            "metadata.json: unsafe APK filename")
    apk = directory / filename
    require(apk.is_file() and not apk.is_symlink(), "Exact metadata APK is missing or is a symlink")
    require(apk.stat().st_size == metadata["sizeBytes"], "APK size differs from metadata")
    require(file_hash(apk) == metadata["sha256"], "APK SHA-256 differs from metadata")
    baseline = metadata.get("baseline")
    require(isinstance(baseline, dict) and positive_integer(baseline.get("versionCode")),
            "metadata.json: missing verified baseline versionCode")
    require(metadata["versionCode"] > baseline["versionCode"],
            "Candidate versionCode must be newer than its tested baseline")
    if "certificateSha256" in baseline:
        require(baseline["certificateSha256"] == QA_CERTIFICATE, "Baseline signer is incompatible")
    if "packageName" in baseline:
        require(baseline["packageName"] == QA_PACKAGE, "Baseline package is incompatible")
    qa_path, report_path = directory / "qa-result.json", directory / "QA-report.md"
    qa = read_json_bytes(qa_path.read_bytes(), "qa-result.json")
    require(qa.get("passed") is True, "QA did not pass")
    require(qa.get("sha256") == metadata["sha256"], "QA result refers to a different APK")
    require(qa.get("sourceRevision") == revision, "QA result refers to a different source revision")
    report = report_path.read_text(encoding="utf-8")
    require(all(item in report for item in (metadata["sha256"], filename, revision)),
            "QA report must identify this exact APK filename, SHA-256, and source revision")
    if "reportSha256" in qa:
        require(qa["reportSha256"] == file_hash(report_path), "QA report hash differs from QA result")
    stem = apk.stem
    files = {filename: apk, f"{stem}-QA-report.md": report_path,
             f"{stem}-metadata.json": directory / "metadata.json",
             f"{stem}-qa-result.json": qa_path}
    for path in files.values():
        require(path.is_file() and not path.is_symlink(), "Artifacts must be regular files")
    require(seal.get("sourceRevision") == revision, "Release seal refers to a different source revision")
    require(seal.get("sha256") == metadata["sha256"], "Release seal refers to a different APK")
    for field, path in (("metadataSha256", directory / "metadata.json"),
                        ("qaResultSha256", qa_path), ("reportSha256", report_path)):
        require(seal.get(field) == file_hash(path), f"Release seal {field} is missing or differs")
    return metadata, files


def candidate_feed(metadata, apk_url):
    feed = {key: metadata[key] for key in FEED_FIELDS if key in metadata}
    feed["apkUrl"] = apk_url
    feed["releaseNotes"] = metadata.get("releaseNotes") or (
        f"Expressive Launcher {metadata['versionName']} QA build. "
        "The exact APK passed the retained automated and emulator QA gates.")
    validate_feed(feed)
    return feed


def download_url(file_id, apk=False):
    require(isinstance(file_id, str) and re.fullmatch(r"[A-Za-z0-9_-]+", file_id),
            "Drive returned an invalid file ID")
    return (f"https://drive.usercontent.google.com/download?id={file_id}&export=download"
            + ("&confirm=t" if apk else ""))


class RcloneDrive:
    def __init__(self):
        self._token = None
        self._token_time = 0

    def environment(self):
        # Strip inherited rclone options, including logging/dump settings. Never
        # persist a config file or refresh token, and never put a token in argv.
        if self._token is None or time.monotonic() - self._token_time > 1800:
            try:
                result = subprocess.run(["gcloud", "auth", "print-access-token", "--quiet"],
                                        capture_output=True, text=True, timeout=60)
            except (OSError, subprocess.TimeoutExpired):
                raise PublishError("Cannot obtain an access token from the worker's gcloud login") from None
            require(result.returncode == 0 and result.stdout.strip(),
                    "gcloud access-token request failed; worker authentication requires attention")
            self._token = result.stdout.strip()
            require(not any(char.isspace() for char in self._token), "gcloud returned an invalid token")
            self._token_time = time.monotonic()
        env = {key: value for key, value in os.environ.items() if not key.startswith("RCLONE_")}
        expiry = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=45)).isoformat()
        env.update({"RCLONE_CONFIG_QADRIVE_TYPE": "drive",
                    "RCLONE_CONFIG_QADRIVE_ROOT_FOLDER_ID": QA_FOLDER_ID,
                    "RCLONE_CONFIG_QADRIVE_SCOPE": "drive",
                    "RCLONE_CONFIG_QADRIVE_TOKEN": json.dumps({
                        "access_token": self._token, "token_type": "Bearer", "expiry": expiry})})
        return env

    def run(self, *args):
        try:
            result = subprocess.run(
                ["rclone", *args, "--config", os.devnull, "--log-level", "ERROR",
                 "--retries", "4", "--low-level-retries", "8", "--retries-sleep", "3s"],
                env=self.environment(), capture_output=True, timeout=900)
        except (OSError, subprocess.TimeoutExpired):
            raise PublishError(f"rclone {args[0]} could not complete") from None
        # Raw tool output is deliberately not logged: OAuth errors may include secrets.
        require(result.returncode == 0, f"rclone {args[0]} failed (exit {result.returncode})")
        return result.stdout

    def listing(self):
        try:
            entries = json.loads(self.run("lsjson", REMOTE, "--hash", "--files-only"))
        except ValueError:
            raise PublishError("rclone returned invalid directory JSON") from None
        require(isinstance(entries, list), "rclone did not return a directory listing")
        return entries

    def stat(self, name, optional=False):
        # Drive permits duplicate names. Reject ambiguity instead of selecting one.
        matches = [entry for entry in self.listing() if entry.get("Name") == name]
        require(len(matches) <= 1, f"Duplicate remote names make {name} ambiguous")
        if not matches:
            require(optional, f"Required remote file is missing: {name}")
            return None
        result = read_json_bytes(self.run("lsjson", REMOTE + name, "--stat", "--hash"),
                                 "rclone stat")
        require(result.get("ID") == matches[0].get("ID") and not result.get("IsDir"),
                f"Remote identity changed while inspecting {name}")
        return result

    def feed(self):
        stat = self.stat(QA_FEED_NAME)
        require(stat.get("ID") == QA_FEED_ID, "QA feed ID differs from the pinned existing file")
        feed = read_json_bytes(self.run("cat", REMOTE + QA_FEED_NAME), "remote QA feed")
        validate_feed(feed)
        return feed

    def verify_file(self, name, path, stat):
        require(stat.get("ID"), f"Remote file has no Drive ID: {name}")
        require(stat.get("Size") == path.stat().st_size, f"Remote size conflict: {name}")
        hashes = {key.lower(): value.lower() for key, value in stat.get("Hashes", {}).items()}
        require(hashes.get("md5") == file_hash(path, "md5"), f"Remote checksum conflict: {name}")
        # MD5 is Drive's transfer checksum. Download and compare SHA-256 as well.
        with tempfile.TemporaryDirectory(prefix="qa-drive-verify-") as temporary:
            downloaded = Path(temporary) / "artifact"
            self.run("copyto", REMOTE + name, str(downloaded), "--checksum")
            require(file_hash(downloaded) == file_hash(path), f"Remote SHA-256 conflict: {name}")

    def stage(self, name, path):
        before = self.stat(name, optional=True)
        if before:
            self.verify_file(name, path, before)
        self.run("copyto", str(path), REMOTE + name, "--immutable", "--checksum")
        after = self.stat(name)
        if before:
            require(before["ID"] == after["ID"], f"Existing artifact identity changed: {name}")
        self.verify_file(name, path, after)
        return after

    def make_apk_public(self, name):
        link = self.run("link", REMOTE + name).decode("utf-8").strip()
        require(link.startswith("https://"), "rclone did not return an HTTPS APK link")
        return link

    def update_feed(self, feed):
        # copyto updates this existing Drive object's content; never move/delete it.
        current = self.feed()  # Includes the pinned ID check immediately before the write.
        if validate_transition(current, feed) == "unchanged":
            return
        with tempfile.TemporaryDirectory(prefix="qa-feed-") as temporary:
            path = Path(temporary) / QA_FEED_NAME
            path.write_text(json.dumps(feed, indent=2) + "\n", encoding="utf-8")
            self.run("copyto", str(path), REMOTE + QA_FEED_NAME, "--checksum")
        require(self.feed() == feed, "QA feed content readback differs after update")


def public_download_digest(url, expected_size):
    request = urllib.request.Request(url, headers={"Cache-Control": "no-cache"})
    digest, size = hashlib.sha256(), 0
    with urllib.request.urlopen(request, timeout=60) as response:
        require(response.geturl().startswith("https://"), "Public download redirected to insecure HTTP")
        for block in iter(lambda: response.read(1024 * 1024), b""):
            size += len(block)
            require(size <= expected_size, "Public APK download exceeds expected size")
            digest.update(block)
    return size, digest.hexdigest()


def public_feed():
    request = urllib.request.Request(download_url(QA_FEED_ID), headers={"Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=60) as response:
        require(response.geturl().startswith("https://"), "Public feed redirected to insecure HTTP")
        feed = read_json_bytes(response.read(MAX_JSON_BYTES + 1), "public QA feed")
    validate_feed(feed)
    return feed


def publish(artifact_dir, promote, receipt):
    metadata, files = load_artifacts(artifact_dir)
    receipt.update({"status": "validated-local", "channel": "qa", "folderId": QA_FOLDER_ID,
                    "feedId": QA_FEED_ID, "sourceRevision": metadata["sourceRevision"],
                    "versionCode": metadata["versionCode"], "versionName": metadata["versionName"],
                    "sha256": metadata["sha256"], "sizeBytes": metadata["sizeBytes"], "files": {}})
    drive = RcloneDrive()
    initial_feed = drive.feed()
    # Reject rollback/conflicts before creating any remote files or permissions.
    proposed = candidate_feed(metadata, initial_feed["apkUrl"])
    validate_transition(initial_feed, proposed, compare_url=False)
    require(public_feed() == initial_feed, "Authenticated and public QA feeds disagree")
    for name, path in files.items():
        existing = drive.stat(name, optional=True)
        if existing:
            drive.verify_file(name, path, existing)
    receipt["status"] = "staging"
    for name, path in files.items():
        remote = drive.stage(name, path)
        receipt["files"][name] = {"id": remote["ID"], "sizeBytes": remote["Size"],
                                  "sha256": file_hash(path), "verified": True}
    # Existing artifacts can already be public from an earlier successful run.
    # Staging never changes their permissions or claims they became private.
    receipt["status"] = "staged-verified"
    apk_name = metadata["fileName"]
    apk_id = receipt["files"][apk_name]["id"]
    proposed = candidate_feed(metadata, download_url(apk_id, apk=True))
    validate_transition(drive.feed(), proposed)
    if not promote:
        return
    receipt["files"][apk_name]["shareUrl"] = drive.make_apk_public(apk_name)
    receipt["status"] = "apk-shared-verifying"
    apk_url = proposed["apkUrl"]
    verified = False
    for attempt in range(4):
        try:
            size, digest = public_download_digest(apk_url, metadata["sizeBytes"])
            if size == metadata["sizeBytes"] and digest == metadata["sha256"]:
                verified = True
                break
        except (OSError, ValueError, PublishError):
            pass
        if attempt < 3:
            time.sleep(3 * (attempt + 1))
    require(verified, "Anonymous APK download failed full size/SHA-256 verification; feed was not updated")
    receipt["files"][apk_name].update({"downloadUrl": apk_url, "publicDownloadVerified": True})
    receipt["status"] = "apk-public-verified"
    current = drive.feed()
    transition = validate_transition(current, proposed)
    if transition == "advance":
        receipt["status"] = "promoting-feed"
        drive.update_feed(proposed)
    receipt["status"] = "feed-written-verifying"
    for attempt in range(4):
        try:
            if public_feed() == proposed:
                break
        except (OSError, ValueError, PublishError):
            pass
        if attempt == 3:
            raise PublishError("Feed may have advanced, but anonymous readback was not verified")
        time.sleep(3 * (attempt + 1))
    require(drive.feed() == proposed, "Final authenticated feed or pinned ID verification failed")
    receipt.update({"status": "released", "feedUrl": download_url(QA_FEED_ID),
                    "feedVerified": True, "feedChanged": transition == "advance"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-dir", required=True, type=Path)
    parser.add_argument("--promote", action="store_true",
                        help="Share the verified APK and promote the existing QA feed")
    parser.add_argument("--output", required=True, type=Path, help="Publication receipt JSON")
    args = parser.parse_args()
    receipt = {"status": "started", "promoteRequested": args.promote,
               "startedAt": dt.datetime.now(dt.timezone.utc).isoformat()}
    code = 0
    try:
        publish(args.artifact_dir, args.promote, receipt)
    except (PublishError, OSError, ValueError) as error:
        receipt["lastCompletedState"] = receipt["status"]
        receipt["status"] = "failed"
        # PublishError messages are controlled; network/OS exception strings can
        # contain response URLs or environment details and are intentionally omitted.
        receipt["error"] = str(error) if isinstance(error, PublishError) else type(error).__name__
        code = 1
    receipt["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())

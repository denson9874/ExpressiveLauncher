#!/usr/bin/env python3
"""Stage sealed QA bytes in GitHub Releases; publish and advance QA only with --promote.

The worker's existing GitHub CLI authentication stays outside the application and
receipts. No interactive login, completed asset replacement/deletion, repository
visibility change, or production-channel write is performed. An empty upload
placeholder from a failed transfer can be removed only from the matching draft.
Jenkins serializes publishers;
manifest updates also use the previous Git blob SHA to reject concurrent changes.
"""

import argparse
import base64
import binascii
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
import urllib.parse
import urllib.request


GITHUB_REPOSITORY = "denson9874/ExpressiveLauncher"
UPDATES_BRANCH = "updates"
QA_FEED_PATH = "qa/latest.json"
QA_FEED_URL = f"https://raw.githubusercontent.com/{GITHUB_REPOSITORY}/{UPDATES_BRANCH}/{QA_FEED_PATH}"
API_VERSION = "2026-03-10"
QA_PACKAGE = "dev.launcher.expressive.l3.debug"
QA_CERTIFICATE = "c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2"
FEED_FIELDS = ("schemaVersion", "channel", "packageName", "versionCode", "versionName",
               "apkUrl", "sha256", "sizeBytes", "releaseNotes")
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


def empty_upload_placeholder(asset, name):
    return (isinstance(asset, dict) and positive_integer(asset.get("id"))
            and asset.get("name") == name and asset.get("state") == "starter"
            and type(asset.get("size")) is int and asset["size"] == 0)


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


def release_tag(metadata):
    require(re.fullmatch(r"\d+\.\d+\.\d+", metadata["versionName"]),
            "QA versionName must have three numeric components")
    return f"qa-v{metadata['versionName']}-{metadata['versionCode']}"


def download_url(tag, filename):
    require(isinstance(tag, str) and re.fullmatch(r"qa-v\d+\.\d+\.\d+-[1-9]\d*", tag),
            "Invalid QA release tag")
    require(isinstance(filename, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", filename),
            "Unsafe GitHub asset filename")
    return f"https://github.com/{GITHUB_REPOSITORY}/releases/download/{tag}/{filename}"


def validate_github_feed(feed):
    validate_feed(feed)
    tag = release_tag(feed)
    parsed = urllib.parse.urlsplit(feed["apkUrl"])
    filename = parsed.path.rsplit("/", 1)[-1]
    require(filename.endswith(".apk") and feed["apkUrl"] == download_url(tag, filename),
            "QA feed APK must belong to the pinned GitHub repository and version tag")


def release_identity(metadata):
    return (f"<!-- expressive-qa source={metadata['sourceRevision']} "
            f"sha256={metadata['sha256']} versionCode={metadata['versionCode']} -->")


class GitHub:
    def __init__(self):
        self.root = f"repos/{GITHUB_REPOSITORY}"

    def environment(self):
        # gh reads the existing worker login or a Jenkins-provided GH_TOKEN.
        # Strip debug/trace controls so credentials cannot appear in subprocess logs.
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(("GH_DEBUG", "GIT_TRACE", "GIT_CURL_VERBOSE"))}
        env.update({"GH_HOST": "github.com", "GH_PROMPT_DISABLED": "1"})
        return env

    def command(self, endpoint):
        require(endpoint.startswith(self.root + "/") or endpoint == self.root
                or endpoint.startswith(f"https://uploads.github.com/{self.root}/releases/"),
                "GitHub request is outside the pinned exports repository")
        return ["gh", "api", "--hostname", "github.com", endpoint,
                "--header", f"X-GitHub-Api-Version: {API_VERSION}"]

    def api(self, endpoint, method="GET", body=None, missing_ok=False, file=None):
        command = self.command(endpoint) + ["--method", method, "--include",
                                            "--header", "Accept: application/vnd.github+json"]
        data = None
        if file is not None:
            command += ["--input", str(file), "--header", "Content-Type: application/octet-stream"]
        elif body is not None:
            command += ["--input", "-", "--header", "Content-Type: application/json"]
            data = json.dumps(body).encode("utf-8")
        try:
            result = subprocess.run(command, input=data, capture_output=True,
                                    env=self.environment(), timeout=900)
        except (OSError, subprocess.TimeoutExpired):
            raise PublishError("GitHub CLI request could not complete") from None
        # --include supplies an HTTP status even for API errors. Never log raw
        # stderr/body: authentication errors can contain credentials or URL details.
        parts = re.split(br"\r?\n\r?\n", result.stdout, maxsplit=1)
        status = re.match(br"HTTP/\S+\s+(\d{3})", parts[0])
        require(status is not None and len(parts) == 2,
                "GitHub CLI returned no HTTP response; worker authentication requires attention")
        code = int(status.group(1))
        if code == 404 and missing_ok:
            return None
        require(result.returncode == 0 and 200 <= code < 300,
                f"GitHub {method} request failed (HTTP {code})")
        if code == 204:
            require(method == "DELETE" and not parts[1].strip(),
                    "GitHub returned an unexpected empty response")
            return None
        require(len(parts[1]) <= MAX_JSON_BYTES, "GitHub API response exceeds JSON size limit")
        try:
            return json.loads(parts[1])
        except (ValueError, UnicodeDecodeError):
            raise PublishError("GitHub returned invalid JSON") from None

    def preflight(self):
        repository = self.api(self.root)
        require(repository.get("full_name", "").lower() == GITHUB_REPOSITORY.lower()
                and repository.get("private") is False and repository.get("archived") is False,
                "Exports repository must be the pinned public, active GitHub repository")
        require(repository.get("permissions", {}).get("push") is True,
                "GitHub worker needs write access to the exports repository")
        branch = self.api(f"{self.root}/git/ref/heads/{UPDATES_BRANCH}")
        revision = branch.get("object", {}).get("sha")
        require(isinstance(revision, str) and re.fullmatch(r"[0-9a-f]{40}", revision),
                "The exports updates branch is missing or invalid")
        return revision

    def feed(self):
        # preflight separately verifies repo access and branch existence, so 404
        # here means the manifest is absent (first GitHub publication).
        result = self.api(f"{self.root}/contents/{QA_FEED_PATH}?ref={UPDATES_BRANCH}", missing_ok=True)
        if result is None:
            return None, None
        require(result.get("type") == "file" and result.get("path") == QA_FEED_PATH
                and result.get("encoding") == "base64", "GitHub QA manifest is not the expected file")
        blob_sha = result.get("sha")
        require(isinstance(blob_sha, str) and re.fullmatch(r"[0-9a-f]{40}", blob_sha),
                "GitHub QA manifest has an invalid blob SHA")
        try:
            raw = base64.b64decode("".join(result["content"].split()), validate=True)
        except (KeyError, TypeError, ValueError, binascii.Error):
            raise PublishError("GitHub QA manifest has invalid base64 content") from None
        feed = read_json_bytes(raw, "GitHub QA manifest")
        validate_github_feed(feed)
        return feed, blob_sha

    def find_release(self, tag):
        release = self.api(f"{self.root}/releases/tags/{tag}", missing_ok=True)
        if release is not None:
            return release
        # Drafts can be absent from the tag endpoint. Authenticated listings
        # include them, and allow retries after a response was interrupted.
        for page in range(1, 101):
            releases = self.api(f"{self.root}/releases?per_page=100&page={page}")
            require(isinstance(releases, list), "GitHub did not return a release list")
            matches = [item for item in releases if item.get("tag_name") == tag]
            require(len(matches) <= 1, "Duplicate release tags are ambiguous")
            if matches:
                return matches[0]
            if len(releases) < 100:
                return None
        raise PublishError("GitHub release lookup exceeded its pagination limit")

    def validate_release(self, release, metadata):
        tag = release_tag(metadata)
        require(positive_integer(release.get("id")), "GitHub returned an invalid release ID")
        require(release.get("tag_name") == tag and release.get("prerelease") is True
                and type(release.get("draft")) is bool,
                "Existing GitHub release has conflicting tag or QA state")
        require(isinstance(release.get("body"), str)
                and release_identity(metadata) in release["body"],
                "Existing GitHub release conflicts with the sealed source or APK")

    def ensure_release(self, metadata, target_revision):
        tag = release_tag(metadata)
        release = self.find_release(tag)
        if release is None:
            body = (f"Expressive Launcher {metadata['versionName']} QA\n\n"
                    f"Source revision: `{metadata['sourceRevision']}`\n\n"
                    f"APK SHA-256: `{metadata['sha256']}`\n\n"
                    "Release-signed, minified QA package. Automated tests and isolated "
                    "Android upgrade checks passed for the exact attached APK.\n\n"
                    "Derived from Lawnchair and AOSP Launcher3; see the repository's "
                    "license and upstream notices.\n\n" + release_identity(metadata))
            release = self.api(f"{self.root}/releases", method="POST", body={
                "tag_name": tag, "target_commitish": target_revision,
                "name": f"Expressive Launcher {metadata['versionName']} QA ({metadata['versionCode']})",
                "body": body, "draft": True, "prerelease": True, "make_latest": "false"})
        self.validate_release(release, metadata)
        return release

    def assets(self, release_id):
        result = []
        for page in range(1, 12):
            items = self.api(f"{self.root}/releases/{release_id}/assets?per_page=100&page={page}")
            require(isinstance(items, list), "GitHub did not return an asset list")
            result.extend(items)
            if len(items) < 100:
                return result
        raise PublishError("GitHub asset lookup exceeded its pagination limit")

    def asset(self, release_id, name):
        matches = [item for item in self.assets(release_id) if item.get("name") == name]
        require(len(matches) <= 1, "Duplicate GitHub asset names are ambiguous")
        return matches[0] if matches else None

    def verify_file(self, name, path, asset):
        require(positive_integer(asset.get("id")) and asset.get("name") == name
                and asset.get("state") == "uploaded", f"Incomplete or conflicting GitHub asset: {name}")
        require(asset.get("size") == path.stat().st_size, f"Remote size conflict: {name}")
        digest = file_hash(path)
        if asset.get("digest") is not None:
            require(asset["digest"] == "sha256:" + digest, f"Remote checksum conflict: {name}")
        with tempfile.TemporaryDirectory(prefix="qa-github-verify-") as temporary:
            downloaded = Path(temporary) / "artifact"
            command = self.command(f"{self.root}/releases/assets/{asset['id']}") + [
                "--header", "Accept: application/octet-stream"]
            try:
                with downloaded.open("wb") as output:
                    result = subprocess.run(command, stdout=output, stderr=subprocess.PIPE,
                                            env=self.environment(), timeout=900)
            except (OSError, subprocess.TimeoutExpired):
                raise PublishError("GitHub verification download could not complete") from None
            require(result.returncode == 0, f"GitHub verification download failed: {name}")
            require(downloaded.stat().st_size == path.stat().st_size
                    and file_hash(downloaded) == digest, f"Remote SHA-256 conflict: {name}")

    def remove_empty_upload_placeholder(self, release_id, metadata, name, asset_id):
        # A documented 502 upload failure can leave a zero-byte `starter` asset.
        # Recheck identity and membership immediately before removing that exact
        # placeholder; completed bytes and published releases are never deleted.
        require(positive_integer(release_id) and positive_integer(asset_id),
                "Upload placeholder recovery requires positive release and asset IDs")
        stem = Path(metadata["fileName"]).stem
        require(name in (metadata["fileName"], f"{stem}-QA-report.md",
                         f"{stem}-metadata.json", f"{stem}-qa-result.json"),
                "Refusing to remove an unexpected GitHub artifact name")
        release = self.api(f"{self.root}/releases/{release_id}")
        self.validate_release(release, metadata)
        require(release["id"] == release_id and release["draft"] is True,
                "Upload placeholder recovery requires the matching draft release")
        asset = self.asset(release_id, name)
        require(empty_upload_placeholder(asset, name) and asset["id"] == asset_id,
                f"Upload placeholder changed or is not safely recoverable: {name}")
        self.api(f"{self.root}/releases/assets/{asset_id}", method="DELETE")
        require(self.asset(release_id, name) is None,
                f"Upload placeholder is still present after deletion: {name}")

    def stage(self, release_id, name, path):
        asset = self.asset(release_id, name)
        if asset is None:
            self.api(f"https://uploads.github.com/{self.root}/releases/{release_id}/assets?"
                     + urllib.parse.urlencode({"name": name}), method="POST", file=path)
            asset = self.asset(release_id, name)
            require(asset is not None, f"Uploaded GitHub asset is missing: {name}")
        self.verify_file(name, path, asset)
        return asset

    def publish_release(self, release, metadata):
        if release["draft"]:
            self.api(f"{self.root}/releases/{release['id']}", method="PATCH",
                     body={"draft": False, "prerelease": True, "make_latest": "false"})
        result = self.api(f"{self.root}/releases/{release['id']}")
        self.validate_release(result, metadata)
        require(result["draft"] is False, "GitHub release is still a draft")
        return result

    def update_feed(self, proposed):
        current, blob_sha = self.feed()
        if current is not None and validate_transition(current, proposed) == "unchanged":
            return False
        raw = (json.dumps(proposed, indent=2) + "\n").encode("utf-8")
        body = {"message": f"Publish QA {proposed['versionName']} ({proposed['versionCode']})",
                "content": base64.b64encode(raw).decode("ascii"), "branch": UPDATES_BRANCH}
        if blob_sha is not None:
            body["sha"] = blob_sha
        self.api(f"{self.root}/contents/{QA_FEED_PATH}", method="PUT", body=body)
        require(self.feed()[0] == proposed, "GitHub QA feed readback differs after update")
        return True


def public_download_digest(url, expected_size):
    request = urllib.request.Request(url, headers={"Cache-Control": "no-cache"})
    digest, size = hashlib.sha256(), 0
    with urllib.request.urlopen(request, timeout=60) as response:
        require(response.geturl().startswith("https://"), "Public download redirected to insecure HTTP")
        for block in iter(lambda: response.read(1024 * 1024), b""):
            size += len(block)
            require(size <= expected_size, "Public download exceeds expected size")
            digest.update(block)
    return size, digest.hexdigest()


def public_feed(optional=False):
    # A cache-busting query prevents an earlier cached missing manifest from
    # obscuring the first promotion. No credentials accompany anonymous checks.
    url = QA_FEED_URL + "?verification=" + str(time.time_ns())
    request = urllib.request.Request(url, headers={"Cache-Control": "no-cache"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            require(response.geturl().startswith("https://"), "Public feed redirected to insecure HTTP")
            feed = read_json_bytes(response.read(MAX_JSON_BYTES + 1), "public QA feed")
    except urllib.error.HTTPError as error:
        if optional and error.code == 404:
            return None
        raise
    validate_github_feed(feed)
    return feed


def publish(artifact_dir, promote, receipt):
    metadata, files = load_artifacts(artifact_dir)
    tag = release_tag(metadata)
    proposed = candidate_feed(metadata, download_url(tag, metadata["fileName"]))
    validate_github_feed(proposed)
    receipt.update({"status": "validated-local", "provider": "github", "channel": "qa",
                    "repository": GITHUB_REPOSITORY, "tag": tag, "feedUrl": QA_FEED_URL,
                    "sourceRevision": metadata["sourceRevision"],
                    "versionCode": metadata["versionCode"], "versionName": metadata["versionName"],
                    "sha256": metadata["sha256"], "sizeBytes": metadata["sizeBytes"], "files": {}})
    github = GitHub()
    target_revision = github.preflight()
    current, _ = github.feed()
    if current is not None:
        validate_transition(current, proposed)
    require(public_feed(optional=current is None) == current,
            "Authenticated and public GitHub QA feeds disagree")
    release = github.ensure_release(metadata, target_revision)
    release_id = release["id"]
    receipt.update({"status": "staging", "releaseId": release_id,
                    "releaseUrl": f"https://github.com/{GITHUB_REPOSITORY}/releases/tag/{tag}",
                    "draft": release["draft"]})
    # Reject every existing conflict before any upload or placeholder cleanup.
    incomplete_uploads = []
    for name, path in files.items():
        asset = github.asset(release_id, name)
        if asset is not None:
            if release["draft"] is True and empty_upload_placeholder(asset, name):
                incomplete_uploads.append((name, asset["id"]))
            else:
                github.verify_file(name, path, asset)
    for name, asset_id in incomplete_uploads:
        github.remove_empty_upload_placeholder(release_id, metadata, name, asset_id)
    for name, path in files.items():
        asset = github.stage(release_id, name, path)
        receipt["files"][name] = {"id": asset["id"], "sizeBytes": path.stat().st_size,
                                  "sha256": file_hash(path), "verified": True,
                                  "downloadUrl": download_url(tag, name)}
    receipt["status"] = "draft-staged-verified" if release["draft"] else "published-staged-verified"
    current, _ = github.feed()
    if current is not None:
        validate_transition(current, proposed)
    if not promote:
        return
    release = github.publish_release(release, metadata)
    receipt.update({"status": "release-published-verifying", "draft": False})
    for name, path in files.items():
        asset = github.asset(release_id, name)
        require(asset is not None and asset.get("browser_download_url") == download_url(tag, name),
                f"Published GitHub asset URL differs from the pinned release: {name}")
        verified = False
        for attempt in range(4):
            try:
                size, digest = public_download_digest(download_url(tag, name), path.stat().st_size)
                if size == path.stat().st_size and digest == file_hash(path):
                    verified = True
                    break
            except (OSError, ValueError, PublishError):
                pass
            if attempt < 3:
                time.sleep(3 * (attempt + 1))
        require(verified, f"Anonymous size/SHA-256 verification failed; feed was not updated: {name}")
        receipt["files"][name]["publicDownloadVerified"] = True
    receipt["status"] = "promoting-feed"
    changed = github.update_feed(proposed)
    receipt["status"] = "feed-written-verifying"
    for attempt in range(4):
        try:
            if public_feed() == proposed:
                break
        except (OSError, ValueError, PublishError):
            pass
        if attempt == 3:
            raise PublishError("GitHub feed may have advanced, but anonymous readback was not verified")
        time.sleep(3 * (attempt + 1))
    require(github.feed()[0] == proposed, "Final authenticated GitHub QA feed verification failed")
    receipt.update({"status": "released", "feedVerified": True, "feedChanged": changed})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-provider", choices=["github"], default="github",
                        help="Reject candidates without the GitHub publication contract")
    parser.add_argument("--artifact-dir", required=True, type=Path)
    parser.add_argument("--promote", action="store_true",
                        help="Publish the verified QA prerelease and advance its GitHub update manifest")
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
        receipt["error"] = str(error) if isinstance(error, PublishError) else type(error).__name__
        code = 1
    receipt["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())

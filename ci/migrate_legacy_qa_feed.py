#!/usr/bin/env python3
"""One-time Jenkins bridge from the existing QA Drive manifest to a published GitHub APK.

Only the pinned existing JSON file can be updated. No APK, new Drive object,
permission, folder, or production channel is created or modified. The GitHub
publication must already be verified. Retries never roll back either channel.
"""

import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


REPOSITORY = "denson9874/ExpressiveLauncher"
GITHUB_FEED_URL = f"https://raw.githubusercontent.com/{REPOSITORY}/updates/qa/latest.json"
LEGACY_FILE_ID = "1_A529DlPEMzwizq-6j3fpMBElGugY-_J"
LEGACY_FILE_NAME = "expressive-launcher-qa-latest.json"
LEGACY_PUBLIC_URL = f"https://drive.usercontent.google.com/download?id={LEGACY_FILE_ID}&export=download"
FIELDS = "id,name,size,md5Checksum,mimeType,version,trashed,modifiedTime"
METADATA_URL = f"https://www.googleapis.com/drive/v3/files/{LEGACY_FILE_ID}?fields={FIELDS}"
MEDIA_URL = f"https://www.googleapis.com/drive/v3/files/{LEGACY_FILE_ID}?alt=media"
UPDATE_URL = f"https://www.googleapis.com/upload/drive/v3/files/{LEGACY_FILE_ID}?uploadType=media&fields={FIELDS}"
QA_PACKAGE = "dev.launcher.expressive.l3.debug"
MAX_JSON_BYTES = 1024 * 1024
FEED_FIELDS = ("schemaVersion", "channel", "packageName", "versionCode", "versionName",
               "apkUrl", "sha256", "sizeBytes", "releaseNotes")


class MigrationError(Exception):
    """Controlled diagnostic safe to include in a Jenkins receipt."""


def require(condition, message):
    if not condition:
        raise MigrationError(message)


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def json_bytes(raw, label):
    require(len(raw) <= MAX_JSON_BYTES, f"{label} exceeds the JSON size limit")
    try:
        result = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        raise MigrationError(f"{label} is not valid JSON") from None
    require(isinstance(result, dict), f"{label} must be a JSON object")
    return result


def positive_integer(value):
    return type(value) is int and value > 0


def valid_digest(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value)


def validate_feed(feed):
    require(type(feed.get("schemaVersion")) is int and feed["schemaVersion"] == 1,
            "Unsupported QA manifest schema")
    require(feed.get("channel") == "qa" and feed.get("packageName") == QA_PACKAGE,
            "Manifest is not the QA package and channel")
    require(positive_integer(feed.get("versionCode")) and positive_integer(feed.get("sizeBytes")),
            "QA manifest has invalid version or size")
    require(isinstance(feed.get("versionName"), str)
            and re.fullmatch(r"\d+\.\d+\.\d+", feed["versionName"]), "Invalid QA version name")
    require(valid_digest(feed.get("sha256")), "Invalid QA APK digest")
    require(isinstance(feed.get("apkUrl"), str) and feed["apkUrl"].startswith("https://"),
            "QA APK URL must use HTTPS")
    require(isinstance(feed.get("releaseNotes"), str) and feed["releaseNotes"].strip(),
            "Missing QA release notes")


def transition(current, candidate):
    validate_feed(current)
    validate_feed(candidate)
    require(candidate["versionCode"] >= current["versionCode"],
            "Refusing to roll back the legacy QA manifest")
    if candidate["versionCode"] == current["versionCode"]:
        require(all(current.get(key) == candidate.get(key) for key in FEED_FIELDS),
                "Legacy QA manifest has a conflicting payload at the same version")
        return "unchanged"
    return "advance"


def validate_publication(publication):
    require(publication.get("provider") == "github" and publication.get("status") == "released"
            and publication.get("feedVerified") is True and publication.get("promoteRequested") is True
            and publication.get("draft") is False,
            "Legacy bridge requires a successfully promoted and verified GitHub publication")
    require(publication.get("repository") == REPOSITORY and publication.get("channel") == "qa"
            and publication.get("feedUrl") == GITHUB_FEED_URL,
            "GitHub publication is outside the pinned QA exports channel")
    require(positive_integer(publication.get("versionCode"))
            and positive_integer(publication.get("sizeBytes"))
            and positive_integer(publication.get("releaseId"))
            and valid_digest(publication.get("sha256")), "Invalid GitHub publication identity")
    require(isinstance(publication.get("sourceRevision"), str)
            and re.fullmatch(r"[0-9a-f]{40}", publication["sourceRevision"]),
            "GitHub publication is missing its exact source revision")
    require(isinstance(publication.get("versionName"), str)
            and re.fullmatch(r"\d+\.\d+\.\d+", publication["versionName"]), "Invalid GitHub version name")
    tag = f"qa-v{publication['versionName']}-{publication['versionCode']}"
    require(publication.get("tag") == tag
            and publication.get("releaseUrl") == f"https://github.com/{REPOSITORY}/releases/tag/{tag}",
            "GitHub release tag or URL differs from the published QA version")
    if "publisherSourceRevision" in publication:
        require(publication["publisherSourceRevision"] == publication["sourceRevision"],
                "GitHub publisher source differs from the candidate source")
    assets = publication.get("files")
    require(isinstance(assets, dict), "GitHub publication is missing verified assets")
    matches = [(name, asset) for name, asset in assets.items() if name.endswith(".apk")]
    require(len(matches) == 1, "GitHub publication must identify one exact QA APK")
    name, asset = matches[0]
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*\.apk", name), "Unsafe GitHub APK filename")
    url = f"https://github.com/{REPOSITORY}/releases/download/{tag}/{name}"
    require(isinstance(asset, dict) and positive_integer(asset.get("id"))
            and asset.get("verified") is True and asset.get("publicDownloadVerified") is True
            and asset.get("downloadUrl") == url
            and asset.get("sha256") == publication["sha256"]
            and asset.get("sizeBytes") == publication["sizeBytes"],
            "GitHub APK does not have an exact verified public download receipt")
    return url


def public_bytes(url):
    request = urllib.request.Request(url, headers={"Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=60) as response:
        require(response.geturl().startswith("https://"), "Public manifest redirected to insecure HTTP")
        raw = response.read(MAX_JSON_BYTES + 1)
    require(len(raw) <= MAX_JSON_BYTES, "Public manifest exceeds the JSON size limit")
    return raw


def public_apk_digest(url, expected_size):
    request = urllib.request.Request(url, headers={"Cache-Control": "no-cache"})
    size, digest = 0, hashlib.sha256()
    with urllib.request.urlopen(request, timeout=60) as response:
        require(response.geturl().startswith("https://"), "GitHub APK redirected to insecure HTTP")
        for block in iter(lambda: response.read(1024 * 1024), b""):
            size += len(block)
            require(size <= expected_size, "GitHub APK exceeds the receipt's verified size")
            digest.update(block)
    return size, digest.hexdigest()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        # Never send the worker's Google credential beyond the fixed API host.
        raise MigrationError("Authenticated Drive API unexpectedly redirected")


class LegacyDrive:
    def __init__(self):
        self._token = None
        self.opener = urllib.request.build_opener(NoRedirect())

    def token(self):
        if self._token is None:
            try:
                result = subprocess.run(["gcloud", "auth", "print-access-token", "--quiet"],
                                        capture_output=True, text=True, timeout=60)
            except (OSError, subprocess.TimeoutExpired):
                raise MigrationError("Cannot obtain the worker's existing Google access token") from None
            require(result.returncode == 0 and result.stdout.strip(),
                    "Worker Google authentication requires attention")
            self._token = result.stdout.strip()
            require(not any(char.isspace() for char in self._token), "Google returned an invalid access token")
        return self._token

    def request(self, method, url, data=None, etag=None):
        require((method == "GET" and url in (METADATA_URL, MEDIA_URL) and data is None)
                or (method == "PATCH" and url == UPDATE_URL and isinstance(data, bytes)),
                "Only the pinned existing QA manifest content may be accessed")
        headers = {"Authorization": "Bearer " + self.token(), "Cache-Control": "no-cache"}
        if data is not None:
            headers["Content-Type"] = "application/json"
            headers["Content-Length"] = str(len(data))
        if etag is not None:
            require(isinstance(etag, str) and "\n" not in etag and "\r" not in etag,
                    "Invalid Drive ETag")
            headers["If-Match"] = etag
        request = urllib.request.Request(url, method=method, data=data, headers=headers)
        try:
            with self.opener.open(request, timeout=60) as response:
                raw = response.read(MAX_JSON_BYTES + 1)
                response_etag = response.headers.get("ETag")
        except urllib.error.HTTPError as error:
            raise MigrationError(f"Pinned QA Drive {method} request failed (HTTP {error.code})") from None
        require(len(raw) <= MAX_JSON_BYTES, "Drive QA response exceeds the JSON size limit")
        return raw, response_etag

    def metadata(self):
        raw, etag = self.request("GET", METADATA_URL)
        metadata = json_bytes(raw, "Drive metadata")
        require(metadata.get("id") == LEGACY_FILE_ID and metadata.get("name") == LEGACY_FILE_NAME
                and metadata.get("mimeType") == "application/json" and metadata.get("trashed") is False,
                "Drive object is not the pinned existing QA JSON manifest")
        require(isinstance(metadata.get("version"), str) and metadata["version"].isdigit()
                and isinstance(metadata.get("size"), str) and metadata["size"].isdigit()
                and isinstance(metadata.get("md5Checksum"), str)
                and re.fullmatch(r"[0-9a-f]{32}", metadata["md5Checksum"]),
                "Drive QA metadata is missing its content identity")
        return metadata, etag

    def snapshot(self):
        before, before_etag = self.metadata()
        raw, _ = self.request("GET", MEDIA_URL)
        after, after_etag = self.metadata()
        require(before == after and before_etag == after_etag,
                "Legacy QA manifest changed while reading its snapshot")
        require(len(raw) == int(after["size"]) and hashlib.md5(raw).hexdigest() == after["md5Checksum"],
                "Legacy QA manifest bytes do not match the Drive metadata")
        feed = json_bytes(raw, "Legacy QA manifest")
        validate_feed(feed)
        return {"metadata": after, "etag": after_etag, "raw": raw, "feed": feed}

    def update(self, before, candidate_bytes):
        latest = self.snapshot()
        require(latest == before, "Legacy QA manifest changed before the content update; retry required")
        # Drive v3 currently supplies no ETag for this object. In that case,
        # Jenkins serialization plus the immediate snapshot comparison applies.
        # If a future response supplies one, send it as an additional precondition.
        raw, _ = self.request("PATCH", UPDATE_URL, data=candidate_bytes, etag=latest["etag"])
        metadata = json_bytes(raw, "Updated Drive metadata")
        require(metadata.get("id") == LEGACY_FILE_ID, "Drive update returned a different file ID")


def backup_snapshot(snapshot, output_path):
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    directory = output_path.parent / f"{output_path.stem}-backup-{stamp}-{uuid.uuid4().hex[:8]}"
    directory.mkdir(parents=True, exist_ok=False)
    (directory / "legacy-qa-before.json").write_bytes(snapshot["raw"])
    metadata = {"capturedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                "fileId": LEGACY_FILE_ID, "sha256": sha256(snapshot["raw"]),
                "sizeBytes": len(snapshot["raw"]), "etag": snapshot["etag"],
                "metadata": snapshot["metadata"]}
    (directory / "backup-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    return directory


def migrate(publication_path, output_path, receipt):
    publication_raw = publication_path.read_bytes()
    publication = json_bytes(publication_raw, "GitHub publication receipt")
    apk_url = validate_publication(publication)
    receipt.update({"status": "validated-publication", "provider": "legacy-drive-qa-bridge",
                    "sourceRevision": publication["sourceRevision"], "fileId": LEGACY_FILE_ID,
                    "publicationReceiptSha256": sha256(publication_raw),
                    "githubReleaseUrl": publication["releaseUrl"], "githubFeedUrl": GITHUB_FEED_URL,
                    "targetVersionName": publication["versionName"], "targetVersionCode": publication["versionCode"]})
    candidate_raw = public_bytes(GITHUB_FEED_URL + "?verification=" + str(time.time_ns()))
    candidate = json_bytes(candidate_raw, "Public GitHub QA manifest")
    validate_feed(candidate)
    require(candidate["apkUrl"] == apk_url
            and all(candidate[key] == publication[key] for key in ("versionCode", "versionName", "sha256", "sizeBytes")),
            "Public GitHub QA manifest differs from the verified publication")
    require(public_apk_digest(apk_url, candidate["sizeBytes"]) == (candidate["sizeBytes"], candidate["sha256"]),
            "Public GitHub APK differs from the verified publication")
    receipt["status"] = "github-public-verified"
    drive = LegacyDrive()
    before = drive.snapshot()
    operation = transition(before["feed"], candidate)
    require(json_bytes(public_bytes(LEGACY_PUBLIC_URL + "&verification=" + str(time.time_ns())),
                       "Public legacy QA manifest") == before["feed"],
            "Authenticated and anonymous legacy QA manifests disagree")
    backup = backup_snapshot(before, output_path)
    receipt.update({"status": "backed-up", "backupDirectory": str(backup),
                    "beforeSha256": sha256(before["raw"]), "beforeDriveVersion": before["metadata"]["version"],
                    "beforeVersionCode": before["feed"]["versionCode"], "beforeApkUrl": before["feed"]["apkUrl"],
                    "concurrencyMode": "if-match-and-snapshot" if before["etag"] else "serialized-snapshot-compare"})
    if operation == "advance":
        require(json_bytes(public_bytes(GITHUB_FEED_URL + "?verification=" + str(time.time_ns())),
                           "Public GitHub QA manifest") == candidate,
                "GitHub QA manifest changed before the legacy bridge; retry required")
        receipt["status"] = "updating-legacy-qa"
        drive.update(before, candidate_raw)
    receipt["status"] = "verifying-legacy-qa"
    after = drive.snapshot()
    require(after["metadata"]["id"] == LEGACY_FILE_ID and after["feed"] == candidate,
            "Final authenticated legacy QA manifest differs from the GitHub candidate")
    if operation == "advance":
        require(after["raw"] == candidate_raw, "Legacy QA content bytes differ after update")
    for attempt in range(4):
        try:
            public = json_bytes(public_bytes(LEGACY_PUBLIC_URL + "&verification=" + str(time.time_ns())),
                                "Public legacy QA manifest")
            if public == candidate:
                break
        except (OSError, ValueError, MigrationError):
            pass
        if attempt == 3:
            raise MigrationError("Legacy QA content may have advanced, but public readback was not verified")
        time.sleep(3 * (attempt + 1))
    receipt.update({"status": "migrated" if operation == "advance" else "unchanged",
                    "afterSha256": sha256(after["raw"]), "afterDriveVersion": after["metadata"]["version"],
                    "afterVersionCode": after["feed"]["versionCode"], "afterApkUrl": after["feed"]["apkUrl"],
                    "publicFeedVerified": True, "githubApkVerified": True, "fileIdentityVerified": True})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publication-receipt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    receipt = {"status": "started", "startedAt": dt.datetime.now(dt.timezone.utc).isoformat()}
    code = 0
    try:
        migrate(args.publication_receipt, args.output, receipt)
    except (MigrationError, OSError, ValueError) as error:
        receipt["lastCompletedState"] = receipt["status"]
        receipt["status"] = "failed"
        receipt["error"] = str(error) if isinstance(error, MigrationError) else type(error).__name__
        code = 1
    receipt["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())

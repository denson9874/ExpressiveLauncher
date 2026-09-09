"""The one-time bridge may change only an existing QA JSON manifest after GitHub verification."""

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location("migration", Path(__file__).parents[1] / "migrate_legacy_qa_feed.py")
migration = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(migration)


class LegacyQaMigrationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.publication_path = self.directory / "publication-4.json"
        self.output = self.directory / "legacy-bridge-4.json"
        self.apk = b"exact public GitHub APK"
        self.tag = "qa-v1.0.11-12"
        self.apk_url = f"https://github.com/{migration.REPOSITORY}/releases/download/{self.tag}/Expressive-1.0.11.apk"
        self.candidate = {"schemaVersion": 1, "channel": "qa", "packageName": migration.QA_PACKAGE,
                          "versionCode": 12, "versionName": "1.0.11", "apkUrl": self.apk_url,
                          "sha256": migration.sha256(self.apk), "sizeBytes": len(self.apk),
                          "releaseNotes": "GitHub update migration"}
        self.publication = {"provider": "github", "status": "released", "feedVerified": True,
                            "promoteRequested": True, "draft": False, "repository": migration.REPOSITORY,
                            "channel": "qa", "feedUrl": migration.GITHUB_FEED_URL,
                            "versionCode": 12, "versionName": "1.0.11", "sizeBytes": len(self.apk),
                            "sha256": migration.sha256(self.apk), "sourceRevision": "a" * 40,
                            "publisherSourceRevision": "a" * 40, "tag": self.tag, "releaseId": 100,
                            "releaseUrl": f"https://github.com/{migration.REPOSITORY}/releases/tag/{self.tag}",
                            "files": {"Expressive-1.0.11.apk": {
                                "id": 123, "verified": True, "publicDownloadVerified": True,
                                "downloadUrl": self.apk_url, "sha256": migration.sha256(self.apk),
                                "sizeBytes": len(self.apk)}}}
        self.legacy = {**self.candidate, "versionCode": 9, "versionName": "1.0.8",
                       "apkUrl": "https://drive.usercontent.google.com/download?id=old-qa-apk"}
        self.write_receipt()

    def write_receipt(self):
        self.publication_path.write_text(json.dumps(self.publication))

    def snapshot(self, feed=None, version="14", etag=None):
        feed = feed or self.legacy
        raw = (json.dumps(feed, indent=2) + "\n").encode()
        metadata = {"id": migration.LEGACY_FILE_ID, "name": migration.LEGACY_FILE_NAME,
                    "size": str(len(raw)), "version": version, "md5Checksum": hashlib.md5(raw).hexdigest(),
                    "mimeType": "application/json", "trashed": False, "modifiedTime": "2026-09-07T15:00:00Z"}
        return {"metadata": metadata, "etag": etag, "raw": raw, "feed": copy.deepcopy(feed)}

    def publication_context(self, before=None):
        state = {"snapshot": before or self.snapshot(), "updates": 0}
        drive = mock.Mock(spec=migration.LegacyDrive)
        drive.snapshot.side_effect = lambda: copy.deepcopy(state["snapshot"])
        def update(before, raw):
            self.assertTrue(list(self.directory.glob("legacy-bridge-4-backup-*/legacy-qa-before.json")),
                            "Original bytes must be backed up before PATCH")
            self.assertEqual(before, state["snapshot"])
            state["updates"] += 1
            state["snapshot"] = self.snapshot(json.loads(raw), version="15")
        drive.update.side_effect = update
        def public_bytes(url):
            if url.startswith(migration.GITHUB_FEED_URL):
                return (json.dumps(self.candidate, indent=2) + "\n").encode()
            self.assertTrue(url.startswith(migration.LEGACY_PUBLIC_URL))
            return state["snapshot"]["raw"]
        return drive, state, public_bytes

    def test_non_promoted_failed_or_wrong_provider_receipt_rejected_before_network(self):
        for field, invalid in (("provider", "drive"), ("status", "draft-staged-verified"),
                               ("feedVerified", False), ("promoteRequested", False), ("draft", True),
                               ("repository", "another/Repo"), ("channel", "release"),
                               ("sourceRevision", "main"), ("publisherSourceRevision", "b" * 40),
                               ("feedUrl", "https://example.invalid/feed"), ("tag", "release-v1.0.11-12")):
            with self.subTest(field=field):
                original = self.publication[field]
                self.publication[field] = invalid
                self.write_receipt()
                with mock.patch.object(migration, "public_bytes") as public, \
                        mock.patch.object(migration, "LegacyDrive") as drive:
                    with self.assertRaises(migration.MigrationError):
                        migration.migrate(self.publication_path, self.output, {})
                    public.assert_not_called()
                    drive.assert_not_called()
                self.publication[field] = original

    def test_apk_receipt_conflicts_or_missing_verification_fail(self):
        asset = self.publication["files"]["Expressive-1.0.11.apk"]
        for field, invalid in (("id", False), ("verified", False), ("publicDownloadVerified", False),
                               ("sha256", "b" * 64), ("sizeBytes", 100),
                               ("downloadUrl", self.apk_url.replace(migration.REPOSITORY, "another/Repo"))):
            with self.subTest(field=field):
                original = asset[field]
                asset[field] = invalid
                with self.assertRaises(migration.MigrationError):
                    migration.validate_publication(self.publication)
                asset[field] = original

    def test_changed_public_github_manifest_prevents_drive_access(self):
        for field, invalid in (("versionCode", 13), ("sha256", "b" * 64),
                               ("packageName", "dev.launcher.expressive.l3"),
                               ("apkUrl", "https://example.invalid/other.apk")):
            with self.subTest(field=field), \
                    mock.patch.object(migration, "public_bytes", return_value=json.dumps({**self.candidate, field: invalid}).encode()), \
                    mock.patch.object(migration, "LegacyDrive") as drive:
                with self.assertRaises(migration.MigrationError):
                    migration.migrate(self.publication_path, self.output, {})
                drive.assert_not_called()

    def test_corrupt_public_github_apk_prevents_drive_access(self):
        with mock.patch.object(migration, "public_bytes", return_value=json.dumps(self.candidate).encode()), \
                mock.patch.object(migration, "public_apk_digest", return_value=(len(self.apk), "b" * 64)), \
                mock.patch.object(migration, "LegacyDrive") as drive:
            with self.assertRaisesRegex(migration.MigrationError, "Public GitHub APK differs"):
                migration.migrate(self.publication_path, self.output, {})
            drive.assert_not_called()

    def test_rollback_and_same_version_conflicts_are_rejected(self):
        with self.assertRaisesRegex(migration.MigrationError, "roll back"):
            migration.transition({**self.candidate, "versionCode": 13}, self.candidate)
        for field, invalid in (("apkUrl", self.legacy["apkUrl"]), ("sha256", "b" * 64),
                               ("releaseNotes", "Different release"), ("sizeBytes", 101)):
            with self.subTest(field=field):
                with self.assertRaisesRegex(migration.MigrationError, "same version"):
                    migration.transition({**self.candidate, field: invalid}, self.candidate)

    def test_success_backs_up_original_bytes_before_only_content_update(self):
        drive, state, public_bytes = self.publication_context()
        before = copy.deepcopy(state["snapshot"])
        receipt = {}
        with mock.patch.object(migration, "LegacyDrive", return_value=drive), \
                mock.patch.object(migration, "public_bytes", side_effect=public_bytes), \
                mock.patch.object(migration, "public_apk_digest", return_value=(len(self.apk), migration.sha256(self.apk))):
            migration.migrate(self.publication_path, self.output, receipt)
        self.assertEqual(receipt["status"], "migrated")
        self.assertEqual(receipt["provider"], "legacy-drive-qa-bridge")
        self.assertEqual(receipt["sourceRevision"], "a" * 40)
        self.assertEqual(receipt["beforeVersionCode"], 9)
        self.assertEqual(receipt["afterVersionCode"], 12)
        self.assertEqual(receipt["afterApkUrl"], self.apk_url)
        self.assertEqual(receipt["concurrencyMode"], "serialized-snapshot-compare")
        self.assertTrue(receipt["publicFeedVerified"])
        backup = Path(receipt["backupDirectory"])
        self.assertEqual((backup / "legacy-qa-before.json").read_bytes(), before["raw"])
        saved = json.loads((backup / "backup-metadata.json").read_text())
        self.assertEqual(saved["metadata"], before["metadata"])
        self.assertEqual(saved["sha256"], migration.sha256(before["raw"]))
        self.assertEqual(state["updates"], 1)

    def test_identical_retry_is_unchanged_and_does_not_write_drive(self):
        drive, state, public_bytes = self.publication_context(self.snapshot(self.candidate, version="15"))
        receipt = {}
        with mock.patch.object(migration, "LegacyDrive", return_value=drive), \
                mock.patch.object(migration, "public_bytes", side_effect=public_bytes), \
                mock.patch.object(migration, "public_apk_digest", return_value=(len(self.apk), migration.sha256(self.apk))):
            migration.migrate(self.publication_path, self.output, receipt)
        self.assertEqual(receipt["status"], "unchanged")
        drive.update.assert_not_called()
        self.assertEqual(receipt["beforeSha256"], receipt["afterSha256"])

    def test_uncertain_patch_can_retry_without_rolling_back_or_writing_twice(self):
        drive, state, public_bytes = self.publication_context()
        original_update = drive.update.side_effect
        def uncertain_update(*args):
            original_update(*args)
            raise migration.MigrationError("Transport response interrupted")
        drive.update.side_effect = uncertain_update
        with mock.patch.object(migration, "LegacyDrive", return_value=drive), \
                mock.patch.object(migration, "public_bytes", side_effect=public_bytes), \
                mock.patch.object(migration, "public_apk_digest", return_value=(len(self.apk), migration.sha256(self.apk))):
            with self.assertRaisesRegex(migration.MigrationError, "interrupted"):
                migration.migrate(self.publication_path, self.output, {})
            receipt = {}
            migration.migrate(self.publication_path, self.output, receipt)
        self.assertEqual(receipt["status"], "unchanged")
        self.assertEqual(state["updates"], 1)
        self.assertEqual(len(list(self.directory.glob("legacy-bridge-4-backup-*"))), 2)

    def test_concurrent_legacy_change_prevents_patch(self):
        drive = migration.LegacyDrive()
        before = self.snapshot()
        changed = self.snapshot(version="15")
        with mock.patch.object(drive, "snapshot", return_value=changed), \
                mock.patch.object(drive, "request") as request:
            with self.assertRaisesRegex(migration.MigrationError, "changed before"):
                drive.update(before, json.dumps(self.candidate).encode())
            request.assert_not_called()

    def test_drive_update_uses_only_fixed_media_patch_and_optional_if_match(self):
        for etag in (None, '"observed-etag"'):
            with self.subTest(etag=etag):
                drive = migration.LegacyDrive()
                before = self.snapshot(etag=etag)
                candidate_bytes = json.dumps(self.candidate).encode()
                with mock.patch.object(drive, "snapshot", return_value=before), \
                        mock.patch.object(drive, "request", return_value=(json.dumps(before["metadata"]).encode(), None)) as request:
                    drive.update(before, candidate_bytes)
                request.assert_called_once_with("PATCH", migration.UPDATE_URL, data=candidate_bytes, etag=etag)

    def test_request_rejects_other_files_create_delete_and_permissions_before_auth(self):
        drive = migration.LegacyDrive()
        for method, url, data in (("POST", migration.UPDATE_URL, b"{}"),
                                  ("DELETE", migration.METADATA_URL, None),
                                  ("GET", migration.METADATA_URL.replace(migration.LEGACY_FILE_ID, "another-file"), None),
                                  ("PATCH", migration.UPDATE_URL + "&addParents=folder", b"{}"),
                                  ("POST", "https://www.googleapis.com/drive/v3/files", b"{}"),
                                  ("GET", f"https://www.googleapis.com/drive/v3/files/{migration.LEGACY_FILE_ID}/permissions", None)):
            with self.subTest(method=method, url=url), mock.patch.object(drive, "token") as token:
                with self.assertRaisesRegex(migration.MigrationError, "Only the pinned"):
                    drive.request(method, url, data=data)
                token.assert_not_called()

    def test_snapshot_rejects_wrong_identity_and_changing_bytes(self):
        drive = migration.LegacyDrive()
        snapshot = self.snapshot()
        for key, invalid in (("id", "another-file"), ("name", "release-latest.json"),
                             ("mimeType", "application/vnd.google-apps.document"), ("trashed", True)):
            with self.subTest(key=key), \
                    mock.patch.object(drive, "request", return_value=(json.dumps({**snapshot["metadata"], key: invalid}).encode(), None)):
                with self.assertRaisesRegex(migration.MigrationError, "pinned existing"):
                    drive.metadata()
        with mock.patch.object(drive, "metadata", return_value=(snapshot["metadata"], None)), \
                mock.patch.object(drive, "request", return_value=(b"X" * len(snapshot["raw"]), None)):
            with self.assertRaisesRegex(migration.MigrationError, "bytes do not match"):
                drive.snapshot()

    def test_authenticated_redirect_cannot_forward_google_credentials(self):
        with self.assertRaisesRegex(migration.MigrationError, "unexpectedly redirected"):
            migration.NoRedirect().redirect_request(None, None, 302, "Found", {}, "https://other.invalid/")

    def test_backup_failure_prevents_remote_update(self):
        drive, _, public_bytes = self.publication_context()
        with mock.patch.object(migration, "LegacyDrive", return_value=drive), \
                mock.patch.object(migration, "public_bytes", side_effect=public_bytes), \
                mock.patch.object(migration, "public_apk_digest", return_value=(len(self.apk), migration.sha256(self.apk))), \
                mock.patch.object(migration, "backup_snapshot", side_effect=OSError("Disk full")):
            with self.assertRaises(OSError):
                migration.migrate(self.publication_path, self.output, {})
        drive.update.assert_not_called()


if __name__ == "__main__":
    unittest.main()

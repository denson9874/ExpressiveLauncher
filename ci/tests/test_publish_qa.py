"""Local release gates and monotonic feed policy; no credentials or remote writes."""

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location("publish_qa", Path(__file__).parents[1] / "publish_qa.py")
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class PublishPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.apk = self.directory / "ExpressiveLauncher-1.0.8-Qa.apk"
        self.apk.write_bytes(b"exact artifact bytes covered by the QA result")
        self.metadata = {
            "schemaVersion": 1, "channel": "qa", "packageName": publisher.QA_PACKAGE,
            "versionCode": 9, "versionName": "1.0.8", "fileName": self.apk.name,
            "sizeBytes": self.apk.stat().st_size, "sha256": publisher.file_hash(self.apk),
            "certificateSha256": publisher.QA_CERTIFICATE, "debuggable": False,
            "signatureVerified": True, "sourceRevision": "a" * 40,
            "baseline": {"versionCode": 8, "certificateSha256": publisher.QA_CERTIFICATE},
            "releaseNotes": "Validated date and swipe repairs.",
        }
        self.qa = {"passed": True, "sha256": self.metadata["sha256"], "sourceRevision": "a" * 40}
        self.report = (f"APK: {self.apk.name}\nSHA-256: {self.metadata['sha256']}\n"
                       f"Source: {self.metadata['sourceRevision']}\n")
        self.write_artifacts()

    def write_artifacts(self):
        (self.directory / "metadata.json").write_text(json.dumps(self.metadata))
        (self.directory / "qa-result.json").write_text(json.dumps(self.qa))
        (self.directory / "QA-report.md").write_text(self.report)
        seal = {"schemaVersion": 1, "complete": True,
                "sourceRevision": self.metadata["sourceRevision"], "sha256": self.metadata["sha256"],
                "metadataSha256": publisher.file_hash(self.directory / "metadata.json"),
                "qaResultSha256": publisher.file_hash(self.directory / "qa-result.json"),
                "reportSha256": publisher.file_hash(self.directory / "QA-report.md")}
        (self.directory / "seal.json").write_text(json.dumps(seal))

    def feed(self):
        return publisher.candidate_feed(self.metadata, publisher.download_url("example_drive_id", apk=True))

    def test_verified_local_bundle_is_accepted_and_names_are_versioned(self):
        metadata, files = publisher.load_artifacts(self.directory)
        self.assertEqual(metadata["sha256"], hashlib.sha256(self.apk.read_bytes()).hexdigest())
        self.assertEqual(set(files), {self.apk.name, self.apk.stem + "-QA-report.md",
                                     self.apk.stem + "-metadata.json", self.apk.stem + "-qa-result.json"})

    def test_apk_changed_after_qa_is_rejected_even_at_same_size(self):
        self.apk.write_bytes(b"X" * self.apk.stat().st_size)
        with self.assertRaisesRegex(publisher.PublishError, "SHA-256 differs"):
            publisher.load_artifacts(self.directory)

    def test_production_wrong_signer_and_debug_variants_are_rejected(self):
        for field, invalid in (("packageName", "dev.launcher.expressive.l3"),
                               ("channel", "release"), ("certificateSha256", "b" * 64),
                               ("debuggable", True), ("signatureVerified", False),
                               ("versionCode", True)):
            with self.subTest(field=field):
                original = self.metadata[field]
                self.metadata[field] = invalid
                self.write_artifacts()
                with self.assertRaises(publisher.PublishError):
                    publisher.load_artifacts(self.directory)
                self.metadata[field] = original

    def test_qa_failure_or_different_hash_or_source_is_rejected(self):
        for field, invalid in (("passed", False), ("sha256", "b" * 64), ("sourceRevision", "b" * 40)):
            with self.subTest(field=field):
                original = self.qa[field]
                self.qa[field] = invalid
                self.write_artifacts()
                with self.assertRaises(publisher.PublishError):
                    publisher.load_artifacts(self.directory)
                self.qa[field] = original

    def test_report_must_identify_exact_artifact_and_revision(self):
        for required in (self.apk.name, self.metadata["sha256"], self.metadata["sourceRevision"]):
            with self.subTest(required=required):
                (self.directory / "QA-report.md").write_text(self.report.replace(required, "different"))
                with self.assertRaisesRegex(publisher.PublishError, "report must identify"):
                    publisher.load_artifacts(self.directory)

    def test_report_hash_when_provided_is_checked(self):
        self.qa["reportSha256"] = publisher.file_hash(self.directory / "QA-report.md")
        self.write_artifacts()
        publisher.load_artifacts(self.directory)
        (self.directory / "QA-report.md").write_text(self.report + "Changed result narrative.")
        with self.assertRaisesRegex(publisher.PublishError, "report hash differs"):
            publisher.load_artifacts(self.directory)

    def test_unsafe_filename_is_rejected_before_file_lookup(self):
        self.metadata["fileName"] = "../outside.apk"
        self.write_artifacts()
        with self.assertRaisesRegex(publisher.PublishError, "unsafe APK filename"):
            publisher.load_artifacts(self.directory)

    def test_baseline_must_precede_candidate(self):
        self.metadata["baseline"]["versionCode"] = self.metadata["versionCode"]
        self.write_artifacts()
        with self.assertRaisesRegex(publisher.PublishError, "newer than its tested baseline"):
            publisher.load_artifacts(self.directory)

    def test_missing_seal_rejects_publication_before_remote_client_creation(self):
        (self.directory / "seal.json").unlink()
        with mock.patch.object(publisher, "RcloneDrive", side_effect=AssertionError("Remote access forbidden")):
            with self.assertRaisesRegex(publisher.PublishError, "seal.json is missing"):
                publisher.publish(self.directory, True, {})

    def test_partial_or_mismatched_seal_is_rejected(self):
        path = self.directory / "seal.json"
        original = json.loads(path.read_text())
        for field, invalid in (("schemaVersion", 2), ("complete", False), ("complete", 1),
                               ("sourceRevision", "b" * 40), ("sha256", "b" * 64),
                               ("metadataSha256", None), ("qaResultSha256", None),
                               ("reportSha256", None)):
            with self.subTest(field=field, invalid=invalid):
                changed = {**original, field: invalid}
                path.write_text(json.dumps(changed))
                with mock.patch.object(publisher, "RcloneDrive", side_effect=AssertionError("Remote access forbidden")):
                    with self.assertRaises(publisher.PublishError):
                        publisher.publish(self.directory, True, {})

    def test_files_changed_after_sealing_are_rejected_even_when_local_checks_pass(self):
        for name, field in (("metadata.json", "metadataSha256"),
                            ("qa-result.json", "qaResultSha256"), ("QA-report.md", "reportSha256")):
            with self.subTest(file=name):
                path = self.directory / name
                original = path.read_text()
                # A trailing newline preserves every parsed policy field, but
                # these are no longer the bytes accepted by the completed build.
                path.write_text(original + "\n")
                with mock.patch.object(publisher, "RcloneDrive", side_effect=AssertionError("Remote access forbidden")):
                    with self.assertRaisesRegex(publisher.PublishError, field):
                        publisher.publish(self.directory, True, {})
                path.write_text(original)

    def test_feed_advances_and_exact_rerun_is_idempotent(self):
        current, candidate = self.feed(), self.feed()
        current["versionCode"] -= 1
        self.assertEqual(publisher.validate_transition(current, candidate), "advance")
        self.assertEqual(publisher.validate_transition(candidate, candidate), "unchanged")

    def test_feed_rejects_rollback(self):
        current, candidate = self.feed(), self.feed()
        current["versionCode"] += 1
        with self.assertRaisesRegex(publisher.PublishError, "older version"):
            publisher.validate_transition(current, candidate)

    def test_same_version_conflicting_payload_never_promotes(self):
        candidate = self.feed()
        for key, value in (("sha256", "b" * 64), ("sizeBytes", 1000),
                           ("apkUrl", "https://example.invalid/other.apk"),
                           ("releaseNotes", "Different release"), ("versionName", "1.0.9")):
            with self.subTest(key=key):
                conflicting = copy.deepcopy(candidate)
                conflicting[key] = value
                with self.assertRaisesRegex(publisher.PublishError, "conflicting payload"):
                    publisher.validate_transition(candidate, conflicting)

    def test_feed_rejects_other_channel_package_or_schema(self):
        for key, value in (("channel", "release"), ("packageName", "dev.launcher.expressive.l3"),
                           ("schemaVersion", 2)):
            with self.subTest(key=key):
                current = self.feed()
                current[key] = value
                with self.assertRaises(publisher.PublishError):
                    publisher.validate_transition(current, self.feed())

    def test_preupload_check_ignores_only_not_yet_known_drive_url(self):
        candidate = self.feed()
        current = self.feed()
        current["apkUrl"] = "https://example.invalid/current.apk"
        self.assertEqual(publisher.validate_transition(current, candidate, compare_url=False), "unchanged")
        candidate["sha256"] = "b" * 64
        with self.assertRaises(publisher.PublishError):
            publisher.validate_transition(current, candidate, compare_url=False)

    def test_remote_name_ambiguity_is_rejected_before_stat_or_copy(self):
        class AmbiguousDrive(publisher.RcloneDrive):
            def listing(self):
                return [{"Name": "artifact.apk", "ID": "first"},
                        {"Name": "artifact.apk", "ID": "second"}]

            def run(self, *args):
                raise AssertionError("Ambiguous remote names must not reach a command")

        with self.assertRaisesRegex(publisher.PublishError, "Duplicate remote names"):
            AmbiguousDrive().stat("artifact.apk")

    def test_wrong_existing_feed_id_is_rejected_before_reading_content(self):
        class WrongFeedDrive(publisher.RcloneDrive):
            def stat(self, name, optional=False):
                return {"ID": "replacement-feed"}

            def run(self, *args):
                raise AssertionError("Wrong feed ID must prevent subsequent commands")

        with self.assertRaisesRegex(publisher.PublishError, "pinned existing file"):
            WrongFeedDrive().feed()

    def test_remote_same_size_checksum_conflict_is_rejected_without_download(self):
        stat = {"ID": "artifact", "Size": self.apk.stat().st_size, "Hashes": {"md5": "0" * 32}}
        with self.assertRaisesRegex(publisher.PublishError, "Remote checksum conflict"):
            publisher.RcloneDrive().verify_file(self.apk.name, self.apk, stat)

    def test_remote_download_must_match_sha256_even_if_listing_checksums_match(self):
        class CorruptDownloadDrive(publisher.RcloneDrive):
            def run(inner, *args):
                if args[0] != "copyto":
                    raise AssertionError("Only a verification download is expected")
                Path(args[2]).write_bytes(b"X" * self.apk.stat().st_size)

        stat = {"ID": "artifact", "Size": self.apk.stat().st_size,
                "Hashes": {"md5": publisher.file_hash(self.apk, "md5")}}
        with self.assertRaisesRegex(publisher.PublishError, "Remote SHA-256 conflict"):
            CorruptDownloadDrive().verify_file(self.apk.name, self.apk, stat)


if __name__ == "__main__":
    unittest.main()

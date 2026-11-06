#!/usr/bin/env python3
"""scripts/release_unified.py attaches only the sealed APK of the release it announces."""

import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import scripts.release_unified as release_unified

ROOT = Path(__file__).parents[2]
SPEC = importlib.util.spec_from_file_location("publish_qa", ROOT / "ci/publish_qa.py")
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)

REVISION = "a1e27964210493582f3dff9e34ebaf726a5067d0"


class SealedFullApkTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        # Resolved, because load_artifacts resolves the release directory and macOS temp dirs sit behind symlinks.
        self.releases = Path(temporary.name).resolve()
        patcher = patch.object(release_unified, "CI_RELEASES_DIR", self.releases)
        patcher.start()
        self.addCleanup(patcher.stop)

    def seal(self, release_id, version_name="5.1.1", version_code=73, revision=REVISION, variant=None):
        """A sealed release directory that passes the publish job's checks."""
        directory = self.releases / release_id
        directory.mkdir()
        apk = directory / f"ExpressiveLauncherL3-{version_name}-{release_id}.apk"
        apk.write_bytes(f"signed APK bytes of {release_id}".encode())
        metadata = {
            "schemaVersion": 1, "channel": "qa", "packageName": publisher.QA_PACKAGE,
            "versionCode": version_code, "versionName": version_name, "fileName": apk.name,
            "sizeBytes": apk.stat().st_size, "sha256": publisher.file_hash(apk),
            "certificateSha256": publisher.QA_CERTIFICATE, "debuggable": False,
            "signatureVerified": True, "sourceRevision": revision,
            "baseline": {"versionCode": version_code - 1, "packageName": publisher.QA_PACKAGE,
                         "certificateSha256": publisher.QA_CERTIFICATE, "sha256": "c" * 64},
            "baselineChannel": "qa", "baselineFeed": publisher.feed_url("qa"),
        }
        if variant:
            metadata["variant"] = variant
            metadata["coreManifest"] = {"forbiddenDeclarations": []}
        (directory / "metadata.json").write_text(json.dumps(metadata))
        qa = {"passed": True, "sha256": metadata["sha256"], "sourceRevision": revision, "channel": "qa",
              "packageName": publisher.QA_PACKAGE, "baselineSha256": "c" * 64}
        (directory / "qa-result.json").write_text(json.dumps(qa))
        (directory / "QA-report.md").write_text(f"APK: {apk.name}\nSHA-256: {metadata['sha256']}\nSource: {revision}\n")
        seal = {"schemaVersion": 1, "complete": True, "sourceRevision": revision, "sha256": metadata["sha256"],
                "metadataSha256": publisher.file_hash(directory / "metadata.json"),
                "qaResultSha256": publisher.file_hash(directory / "qa-result.json"),
                "reportSha256": publisher.file_hash(directory / "QA-report.md")}
        (directory / "seal.json").write_text(json.dumps(seal))
        return apk

    def find(self, revision=REVISION, release_id=None):
        with contextlib.redirect_stdout(io.StringIO()):
            return release_unified.sealed_full_apk("5.1.1", "73", revision, release_id)

    def test_named_release_returns_its_sealed_apk(self):
        apk = self.seal("qa-5.1.1-73-build-25")
        self.assertEqual(apk, self.find(release_id="qa-5.1.1-73-build-25"))

    def test_a_named_release_is_the_only_one_checked(self):
        self.seal("qa-5.1.1-73-build-25")
        apk = self.seal("qa-5.1.1-73-build-26")
        self.assertEqual(apk, self.find(release_id="qa-5.1.1-73-build-26"))
        self.assertIsNone(self.find(release_id="qa-5.1.1-73-build-27"))

    def test_without_a_release_id_only_the_build_of_that_revision_matches(self):
        self.seal("qa-5.1.1-73-build-23", revision="b" * 40)
        apk = self.seal("qa-5.1.1-73-build-25")
        self.seal("qa-5.1.0-72-build-20")
        self.assertEqual(apk, self.find())
        self.assertEqual(apk, self.find(revision=REVISION[:12]))

    def test_core_is_never_picked(self):
        self.seal("qa-core-5.1.1-73-build-25", variant="core")
        self.assertIsNone(self.find())

    def test_no_apk_when_nothing_or_too_much_matches(self):
        self.assertIsNone(self.find())
        self.seal("qa-5.1.1-73-build-25")
        self.assertIsNone(self.find(revision=REVISION[:6]))
        self.seal("qa-5.1.1-73-build-26")
        self.assertIsNone(self.find())
        self.assertIsNone(self.find(revision="a1e2"))

    def test_an_apk_changed_after_sealing_is_refused(self):
        apk = self.seal("qa-5.1.1-73-build-25")
        apk.write_bytes(b"X" * apk.stat().st_size)
        self.assertIsNone(self.find(release_id="qa-5.1.1-73-build-25"))

    def test_named_release_of_another_version_is_refused(self):
        for number, (name, code) in enumerate((("5.0.3", 65), ("5.0.3", 73), ("5.1.1", 74))):
            with self.subTest(name=name, code=code):
                self.seal(f"qa-5.1.1-73-build-{number}", version_name=name, version_code=code)
                self.assertIsNone(self.find(release_id=f"qa-5.1.1-73-build-{number}"))

    def run_telegram_only(self, *extra):
        argv = ["release_unified.py", "--skip-jenkins", "--skip-xda", "--version-name", "5.1.1",
                "--version-code", "73", "--revision", REVISION, *extra]
        with patch.object(sys, "argv", argv), \
                patch.object(release_unified, "run_telegram_stage", return_value="Skipped") as stage, \
                contextlib.redirect_stdout(io.StringIO()):
            release_unified.main()
        return stage.call_args.args[0]

    def test_telegram_post_attaches_the_sealed_apk(self):
        apk = self.seal("qa-5.1.1-73-build-25")
        command = self.run_telegram_only()
        self.assertEqual(str(apk), command[command.index("--apk") + 1])

    def test_a_rebuilt_release_is_picked_by_its_release_id(self):
        self.seal("qa-5.1.1-73-build-25")
        apk = self.seal("qa-5.1.1-73-build-26")
        self.assertNotIn("--apk", self.run_telegram_only())
        command = self.run_telegram_only("--release-id", "qa-5.1.1-73-build-26")
        self.assertEqual(str(apk), command[command.index("--apk") + 1])

    def test_telegram_post_has_no_apk_without_a_matching_seal(self):
        self.seal("qa-5.1.1-73-build-23", revision="b" * 40)
        self.assertNotIn("--apk", self.run_telegram_only())


if __name__ == "__main__":
    unittest.main()

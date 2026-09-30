"""Sealing-gate regressions: only exact, fully tested QA bytes may be sealed for publication."""

import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("finalize_qa", Path(__file__).parents[1] / "finalize_qa.py")
finalize = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(finalize)
REVISION = "a" * 40
APK = b"expressive qa apk bytes"
TEST_RESULTS = "build/test-results/testLawnWithQuickstepExpressiveDebugUnitTest"


class FinalizeQaTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        base = Path(temp.name)
        self.artifact, self.source, self.release = base / "artifact", base / "source", base / "sealed" / "qa-1"
        self.artifact.mkdir()
        sha = hashlib.sha256(APK).hexdigest()
        (self.artifact / "Expressive-QA.apk").write_bytes(APK)
        self.write("metadata.json", {
            "fileName": "Expressive-QA.apk", "sha256": sha, "versionName": "2.0.9", "versionCode": 27,
            "packageName": "dev.launcher.expressive.l3.qa", "sizeBytes": len(APK), "certificateSha256": "c" * 64,
        })
        self.write("source.json", {"sourceRevision": REVISION})
        self.write("qa-result.json", {"passed": True, "sha256": sha, "sourceRevision": REVISION})
        self.junit(tests=3)

    def write(self, name, value):
        (self.artifact / name).write_text(json.dumps(value))

    def junit(self, name="TEST-Suite.xml", tests=3, failures=0, errors=0, skipped=0):
        results = self.source / TEST_RESULTS
        results.mkdir(parents=True, exist_ok=True)
        (results / name).write_text(
            f'<testsuite name="s" tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}"/>'
        )

    def run_finalize(self):
        argv = ["finalize_qa.py", "--artifact-dir", str(self.artifact), "--source-dir", str(self.source),
                "--release-dir", str(self.release), "--build-url", "https://jenkins.example/job/qa/1/"]
        with patch.object(sys, "argv", argv), patch("builtins.print"):
            finalize.main()

    def assertRejected(self, message):
        with self.assertRaisesRegex(SystemExit, message):
            self.run_finalize()
        self.assertFalse(self.release.exists())

    def test_seals_passing_build_with_matching_digests(self):
        self.junit("TEST-Other.xml", tests=2)
        self.run_finalize()
        seal = json.loads((self.release / "seal.json").read_text())
        self.assertEqual((True, REVISION, hashlib.sha256(APK).hexdigest()),
                         (seal["complete"], seal["sourceRevision"], seal["sha256"]))
        for name, key in [("metadata.json", "metadataSha256"), ("qa-result.json", "qaResultSha256"),
                          ("QA-report.md", "reportSha256")]:
            self.assertEqual(hashlib.sha256((self.release / name).read_bytes()).hexdigest(), seal[key])
        metadata = json.loads((self.release / "metadata.json").read_text())
        self.assertEqual({"tests": 5, "failures": 0, "errors": 0, "skipped": 0}, metadata["unitTests"])
        self.assertEqual(REVISION, metadata["sourceRevision"])
        self.assertTrue((self.release / "junit" / "TEST-Other.xml").exists())
        self.assertEqual([], list(self.release.parent.glob("*.preparing-*")))

    def test_rejects_failed_qa_or_mismatched_apk_bytes(self):
        qa = {"passed": True, "sha256": hashlib.sha256(APK).hexdigest(), "sourceRevision": REVISION}
        for change in [{"passed": False}, {"passed": "true"}, {"sha256": "0" * 64}]:
            with self.subTest(change=change):
                self.write("qa-result.json", {**qa, **change})
                self.assertRejected("not a pass for these exact APK bytes")
        self.write("qa-result.json", qa)
        metadata = json.loads((self.artifact / "metadata.json").read_text())
        self.write("metadata.json", {**metadata, "sha256": "0" * 64})
        self.assertRejected("not a pass for these exact APK bytes")
        self.write("metadata.json", metadata)
        (self.artifact / "Expressive-QA.apk").write_bytes(APK + b"tampered")
        self.assertRejected("not a pass for these exact APK bytes")

    def test_rejects_qa_from_a_different_source_revision(self):
        self.write("source.json", {"sourceRevision": "b" * 40})
        self.assertRejected("different source revision")

    def test_requires_complete_passing_unskipped_unit_tests(self):
        for counts in [{"tests": 0}, {"failures": 1}, {"errors": 1}, {"skipped": 1}]:
            with self.subTest(counts=counts):
                self.junit(**{"tests": 3, **counts})
                self.assertRejected("complete passing, unskipped unit test suite")
        (self.source / TEST_RESULTS / "TEST-Suite.xml").unlink()
        self.assertRejected("complete passing, unskipped unit test suite")

    def test_never_overwrites_an_existing_sealed_release(self):
        self.release.mkdir(parents=True)
        (self.release / "seal.json").write_text("original")
        with self.assertRaisesRegex(SystemExit, "will not be overwritten"):
            self.run_finalize()
        self.assertEqual("original", (self.release / "seal.json").read_text())


if __name__ == "__main__":
    unittest.main()

"""Real ZIP/metadata failure cases with Android package-tool results supplied at the boundary."""

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("feed_bundle_verify", Path(__file__).parents[1] / "verify_qa.py")
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


class FeedBundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "launcher.apk"
        self.payload = b"signed helper fixture"
        self.metadata = {
            "schemaVersion": 1, "packageName": "dev.launcher.expressive.feed",
            "versionCode": 10, "versionName": "1.0.9", "sizeBytes": len(self.payload),
            "sha256": hashlib.sha256(self.payload).hexdigest(), "fileName": "ExpressiveFeed.apk",
        }
        self.candidate = {"versionCode": 10, "versionName": "1.0.9", "certificateSha256": "a" * 64}
        self.identity = {**self.metadata, "debuggable": True, "certificateSha256": "a" * 64,
                         "signatureVerified": True}

    def bundle(self, metadata=None, payload=None, include_helper=True):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("assets/expressive-feed/metadata.json", json.dumps(metadata or self.metadata))
            if include_helper:
                archive.writestr("assets/expressive-feed/ExpressiveFeed.apk", self.payload if payload is None else payload)

    def check(self, identity=None, manifest="E: manifest\n  E: application\n    E: service\n"):
        with patch.object(verify, "inspect_apk", return_value=identity or self.identity), \
                patch.object(verify, "run_tool", return_value=manifest):
            return verify.verify_feed_bundle(self.apk, self.candidate, self.root)

    def test_complete_same_signed_service_bundle_passes(self):
        self.bundle()
        result = self.check()
        self.assertTrue(result["bundled"] and result["signerMatches"] and result["serviceOnly"])

    def test_missing_helper_and_invalid_archive_fail(self):
        self.bundle(include_helper=False)
        with self.assertRaises(verify.VerificationError):
            self.check()
        self.apk.write_bytes(b"not a zip")
        with self.assertRaises(verify.VerificationError):
            self.check()

    def test_corrupt_bytes_and_wrong_metadata_fail_before_android_tools(self):
        for field, value in (("sha256", "0" * 64), ("sizeBytes", 1), ("versionCode", 9),
                             ("versionName", "1.0.8"), ("schemaVersion", True),
                             ("packageName", "another.app"), ("fileName", "../helper.apk")):
            with self.subTest(field=field):
                self.bundle({**self.metadata, field: value})
                with self.assertRaises(verify.VerificationError), patch.object(verify, "inspect_apk") as inspect:
                    verify.verify_feed_bundle(self.apk, self.candidate, self.root)
                inspect.assert_not_called()
        self.bundle(payload=self.payload[:-1])
        with self.assertRaises(verify.VerificationError):
            self.check()

    def test_untrusted_signer_and_wrong_android_identity_fail(self):
        self.bundle()
        for field, value in (("certificateSha256", "b" * 64), ("packageName", "another.app"),
                             ("versionCode", 9), ("debuggable", False)):
            with self.subTest(field=field), self.assertRaises(verify.VerificationError):
                self.check({**self.identity, field: value})

    def test_visible_or_extra_components_fail(self):
        self.bundle()
        for component in ("activity", "activity-alias", "provider", "receiver", "service"):
            with self.subTest(component=component), self.assertRaises(verify.VerificationError):
                self.check(manifest="E: manifest\n  E: application\n    E: service\n    E: " + component + "\n")

    def test_duplicate_json_keys_are_rejected(self):
        with self.assertRaises(verify.VerificationError):
            json.loads('{"versionCode":10,"versionCode":9}', object_pairs_hook=verify.unique_json_fields)


if __name__ == "__main__":
    unittest.main()

"""Unit tests for Play Protect & Android Developer Verification pipeline."""

import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).parents[2]
SPEC = importlib.util.spec_from_file_location(
    "verify_developer_play_protect",
    ROOT / "scripts" / "verify_developer_play_protect.py",
)
verify_tool = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify_tool)


class PlayProtectVerificationTests(unittest.TestCase):
    def test_default_metadata_matches_registered_developer(self):
        self.assertEqual(verify_tool.VERIFIED_DEVELOPER_ID, "5547708187557586870")
        self.assertEqual(verify_tool.VERIFIED_DEVELOPER_NAME, "Daryl Denson")
        self.assertEqual(
            verify_tool.VERIFICATION_URL,
            "https://play.google.com/console/u/0/developers/5547708187557586870/android-developer-verification",
        )
        self.assertEqual(
            verify_tool.VERIFIED_CERT_SHA256,
            "c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2",
        )

    def test_format_fingerprint_outputs_colon_delimited_uppercase(self):
        formatted = verify_tool.format_fingerprint(
            "c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2"
        )
        self.assertEqual(
            formatted,
            "C1:41:60:30:6D:5C:05:9B:3D:11:9F:15:FB:74:E0:8C:57:CC:27:23:16:E8:0B:36:E1:92:C7:1D:C9:E4:D0:D2",
        )

    def test_verify_package_without_apk_emits_valid_developer_receipt(self):
        receipt = verify_tool.verify_package(apk_path=None, package_name="dev.launcher.expressive.l3")
        self.assertEqual(receipt["developerId"], "5547708187557586870")
        self.assertEqual(receipt["developerName"], "Daryl Denson")
        self.assertEqual(receipt["packageName"], "dev.launcher.expressive.l3")
        self.assertEqual(
            receipt["certificateSha256"],
            "C1:41:60:30:6D:5C:05:9B:3D:11:9F:15:FB:74:E0:8C:57:CC:27:23:16:E8:0B:36:E1:92:C7:1D:C9:E4:D0:D2",
        )
        self.assertEqual(receipt["status"], "verified")
        self.assertIn("verifiedAt", receipt)


if __name__ == "__main__":
    unittest.main()

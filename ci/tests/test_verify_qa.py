"""Release-contract regressions; Android tool responses are boundary fixtures."""

import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("verify_qa", Path(__file__).parents[1] / "verify_qa.py")
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)
CERT = verify.EXPECTED_CERTIFICATE_SHA256


def badging(code=9, version="1.0.8", package=verify.QA_PACKAGE, debug=False):
    return (
        f"package: name='{package}' versionCode='{code}' versionName='{version}' compileSdkVersion='37'\n"
        "minSdkVersion:'37'\ntargetSdkVersion:'37'\n"
        "application: label='Expressive Launcher L3' icon='res/s31.xml'\n"
        + ("application-debuggable\n" if debug else "")
    )


def signing(cert=CERT, count=1, label="V2 Signer"):
    return (
        "Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n"
        f"Number of signers: {count}\n{label}: certificate SHA-256 digest: {cert}\n"
        f"{label}: public key SHA-256 digest: {'b' * 64}\n"
    )


class ParserTests(unittest.TestCase):
    def test_current_build_tools_output(self):
        self.assertEqual(CERT, verify.parse_signer(signing()))
        self.assertEqual(
            {"packageName": verify.QA_PACKAGE, "versionCode": 9, "versionName": "1.0.8", "debuggable": False},
            verify.parse_badging(badging()),
        )

    def test_legacy_signer_label_and_colon_delimited_uppercase(self):
        colon_digest = ":".join(CERT[i:i + 2] for i in range(0, 64, 2)).upper()
        self.assertEqual(CERT, verify.parse_signer(signing(colon_digest, label="Signer #1")))

    def test_multiple_signature_schemes_with_same_certificate(self):
        output = signing() + f"V3 Signer: certificate SHA-256 digest: {CERT}\n"
        self.assertEqual(CERT, verify.parse_signer(output))

    def test_multiple_signers_rejected_even_if_same_certificate(self):
        with self.assertRaisesRegex(verify.VerificationError, "exactly one signer"):
            verify.parse_signer(signing(count=2))
        with self.assertRaisesRegex(verify.VerificationError, "more than one signer"):
            verify.parse_signer(signing() + f"Signer #2: certificate SHA-256 digest: {CERT}\n")

    def test_unverified_missing_malformed_and_ambiguous_certificates_rejected(self):
        for output in (
            signing().replace("Verifies\n", ""),
            "Verifies\nNumber of signers: 1\n",
            signing("abcd"),
            signing() + f"V3 Signer: certificate SHA-256 digest: {'a' * 64}\n",
        ):
            with self.subTest(output=output), self.assertRaises(verify.VerificationError):
                verify.parse_signer(output)

    def test_badging_fails_closed_for_missing_ambiguous_or_invalid_identity(self):
        for output in (
            "", badging() + badging(), badging().replace("versionName='1.0.8'", ""),
            badging().replace("versionCode='9'", "versionCode='9' versionCode='10'"),
            badging(code="9x"), badging(code=0), badging(code=2_100_000_001),
            badging().replace("application: ", "missing: "),
        ):
            with self.subTest(output=output), self.assertRaises(verify.VerificationError):
                verify.parse_badging(output)


class ReleaseContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "candidate.apk"
        self.baseline = self.root / "delivered-1.0.7.apk"
        self.apk.write_bytes(b"immutable candidate bytes")
        self.baseline.write_bytes(b"previously delivered baseline bytes")
        self.tools = self.root / "build-tools"
        self.tools.mkdir()
        for name in ("apksigner", "aapt2"):
            tool = self.tools / name
            tool.write_text("fixture executable")
            tool.chmod(0o700)
        self.responses = {
            ("apksigner", self.apk.name): signing(),
            ("aapt2", self.apk.name): badging(),
            ("apksigner", self.baseline.name): signing(label="Signer #1"),
            ("aapt2", self.baseline.name): badging(8, "1.0.7"),
        }
        self.commands = []

    def run_fixture(self, command, **kwargs):
        self.commands.append(command)
        self.assertEqual(120, kwargs["timeout"])
        self.assertFalse(kwargs["check"])
        return subprocess.CompletedProcess(command, 0, self.responses[(Path(command[0]).name, Path(command[-1]).name)], "")

    def verify_candidate(self):
        with patch.object(verify.subprocess, "run", side_effect=self.run_fixture):
            return verify.verify_qa(self.apk, self.baseline, "1.0.8", 9, self.tools)

    def test_success_metadata_hashes_and_real_tool_argument_contract(self):
        metadata = self.verify_candidate()
        self.assertEqual("qa", metadata["channel"])
        self.assertEqual(1, metadata["schemaVersion"])
        self.assertEqual(9, metadata["versionCode"])
        self.assertEqual(CERT, metadata["certificateSha256"])
        self.assertEqual(len(b"immutable candidate bytes"), metadata["sizeBytes"])
        self.assertEqual(hashlib.sha256(b"immutable candidate bytes").hexdigest(), metadata["sha256"])
        self.assertTrue(metadata["baseline"]["candidateIsNewer"])
        self.assertTrue(metadata["baseline"]["signerMatches"])
        self.assertEqual(8, metadata["baseline"]["versionCode"])
        self.assertEqual(["verify", "--verbose", "--print-certs"], self.commands[0][1:-1])
        self.assertEqual(["dump", "badging"], self.commands[1][1:-1])
        self.assertEqual(b"immutable candidate bytes", self.apk.read_bytes())
        self.assertEqual(b"previously delivered baseline bytes", self.baseline.read_bytes())

    def test_wrong_candidate_or_baseline_package_rejected(self):
        for path in (self.apk, self.baseline):
            with self.subTest(path=path), patch.dict(self.responses, {("aapt2", path.name): badging(package="dev.launcher.expressive.l3")}):
                with self.assertRaisesRegex(verify.VerificationError, "package must be"):
                    self.verify_candidate()

    def test_debuggable_candidate_or_baseline_rejected(self):
        for path in (self.apk, self.baseline):
            with self.subTest(path=path), patch.dict(self.responses, {("aapt2", path.name): badging(debug=True)}):
                with self.assertRaisesRegex(verify.VerificationError, "debuggable"):
                    self.verify_candidate()

    def test_wrong_candidate_or_baseline_certificate_rejected(self):
        for path in (self.apk, self.baseline):
            with self.subTest(path=path), patch.dict(self.responses, {("apksigner", path.name): signing("a" * 64)}):
                with self.assertRaisesRegex(verify.VerificationError, "durable Expressive QA signer"):
                    self.verify_candidate()

    def test_both_wrong_certificates_cannot_establish_a_new_trusted_baseline(self):
        self.responses[("apksigner", self.apk.name)] = signing("a" * 64)
        self.responses[("apksigner", self.baseline.name)] = signing("a" * 64)
        with self.assertRaisesRegex(verify.VerificationError, "durable Expressive QA signer"):
            self.verify_candidate()

    def test_wrong_expected_version_and_non_newer_candidate_rejected(self):
        for output in (badging(code=10), badging(version="1.0.9")):
            with self.subTest(output=output), patch.dict(self.responses, {("aapt2", self.apk.name): output}):
                with self.assertRaisesRegex(verify.VerificationError, "expected 1.0.8/code9"):
                    self.verify_candidate()
        for baseline_code in (9, 10):
            with self.subTest(code=baseline_code), patch.dict(self.responses, {("aapt2", self.baseline.name): badging(code=baseline_code)}):
                with self.assertRaisesRegex(verify.VerificationError, "strictly newer"):
                    self.verify_candidate()

    def test_signature_tool_failure_is_never_accepted_as_metadata(self):
        with patch.object(verify.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, signing(), "DOES NOT VERIFY")):
            with self.assertRaisesRegex(verify.VerificationError, "DOES NOT VERIFY"):
                verify.verify_qa(self.apk, self.baseline, "1.0.8", 9, self.tools)

    def test_candidate_mutation_during_baseline_inspection_rejected(self):
        def mutate(command, **kwargs):
            result = self.run_fixture(command, **kwargs)
            if Path(command[-1]) == self.baseline:
                self.apk.write_bytes(b"changed after verification")
            return result
        with patch.object(verify.subprocess, "run", side_effect=mutate):
            with self.assertRaisesRegex(verify.VerificationError, "changed before metadata"):
                verify.verify_qa(self.apk, self.baseline, "1.0.8", 9, self.tools)

    def cli_arguments(self, output):
        return ["--apk", str(self.apk), "--baseline-apk", str(self.baseline),
                "--version-name", "1.0.8", "--version-code", "9", "--build-tools", str(self.tools),
                "--output", str(output)]

    def test_cli_writes_json_only_after_success(self):
        output = self.root / "metadata" / "qa.json"
        with patch.object(verify.subprocess, "run", side_effect=self.run_fixture), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, verify.main(self.cli_arguments(output)))
        metadata = json.loads(output.read_text())
        self.assertEqual(CERT, metadata["certificateSha256"])
        self.assertFalse(metadata["debuggable"])
        rejected = self.root / "rejected.json"
        self.responses[("aapt2", self.apk.name)] = badging(debug=True)
        with patch.object(verify.subprocess, "run", side_effect=self.run_fixture), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, verify.main(self.cli_arguments(rejected)))
        self.assertFalse(rejected.exists())

    def test_output_cannot_replace_apk_or_hard_link_to_apk(self):
        link = self.root / "linked-output.json"
        link.hardlink_to(self.apk)
        for output in (self.apk, self.baseline, link, self.tools / "apksigner"):
            with self.subTest(output=output), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, verify.main(self.cli_arguments(output)))
        self.assertEqual(b"immutable candidate bytes", self.apk.read_bytes())


if __name__ == "__main__":
    unittest.main()

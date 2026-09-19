# Copyright 2026 Daryl Denson and Expressive Launcher contributors.
# SPDX-License-Identifier: Apache-2.0
# https://github.com/denson9874/ExpressiveLauncher

"""Exercise attribution loss against a small, isolated repository fixture."""

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("attribution", REPOSITORY / "ci/check_attribution.py")
attribution = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(attribution)


class AttributionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for relative in attribution.REQUIRED_FILES:
            self.write(relative, "Required attribution documentation.\n")
        # Copy only the three retained license texts, not the repository or builds.
        for relative in attribution.LICENSE_SHA256:
            self.write(relative, (REPOSITORY / relative).read_bytes())
        self.notice = ("Expressive Launcher\nhttps://github.com/denson9874/ExpressiveLauncher\n"
                       "Copyright 2026 Daryl Denson and Expressive Launcher contributors.\n"
                       "The Android Open Source Project\nLawnchair\n")
        self.write("NOTICE", self.notice)
        self.write(attribution.PACKAGED_NOTICE, self.notice)
        self.header = ("/* Copyright 2026 Daryl Denson and Expressive Launcher contributors.\n"
                       " * SPDX-License-Identifier: Apache-2.0\n"
                       " * Developed for Expressive Launcher.\n"
                       " * https://github.com/denson9874/ExpressiveLauncher\n */\n")
        for relative in attribution.ATTRIBUTED_SOURCES:
            self.write(relative, self.header + "package fixture\n")

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content.encode("utf-8") if isinstance(content, str) else content)

    def test_complete_fixture_passes(self):
        self.assertEqual(attribution.check(self.root), [])

    def test_missing_required_files_fail(self):
        for relative in (*attribution.REQUIRED_FILES, attribution.PACKAGED_NOTICE):
            with self.subTest(path=relative):
                path = self.root / relative
                original = path.read_bytes()
                path.unlink()
                self.assertTrue(any(relative in error and "missing" in error
                                    for error in attribution.check(self.root)))
                self.write(relative, original)

    def test_stripping_each_attribution_fails_even_when_notices_match(self):
        for marker in attribution.NOTICE_MARKERS:
            with self.subTest(marker=marker):
                stripped = self.notice.replace(marker, "")
                self.write("NOTICE", stripped)
                self.write(attribution.PACKAGED_NOTICE, stripped)
                self.assertTrue(any("missing attribution" in error and marker in error
                                    for error in attribution.check(self.root)))

    def test_modifying_each_retained_license_fails(self):
        for relative in attribution.LICENSE_SHA256:
            with self.subTest(path=relative):
                original = (self.root / relative).read_bytes()
                self.write(relative, original + b"\nChanged license terms.\n")
                self.assertTrue(any(relative in error and "retained license text changed" in error
                                    for error in attribution.check(self.root)))
                self.write(relative, original)

    def test_notice_project_name_does_not_replace_contributor_copyright(self):
        copyright = "Copyright 2026 Daryl Denson and Expressive Launcher contributors."
        stripped = self.notice.replace(copyright, "")
        self.assertIn("Expressive Launcher", stripped)
        self.write("NOTICE", stripped)
        self.write(attribution.PACKAGED_NOTICE, stripped)
        self.assertTrue(any("NOTICE: missing attribution" in error and copyright in error
                            for error in attribution.check(self.root)))

    def test_source_project_name_does_not_replace_contributor_copyright(self):
        copyright = "Copyright 2026 Daryl Denson and Expressive Launcher contributors."
        stripped = self.header.replace(copyright, "")
        self.assertIn("Expressive Launcher", stripped)
        for relative in attribution.ATTRIBUTED_SOURCES:
            with self.subTest(path=relative):
                self.write(relative, stripped + "package fixture\n")
                self.assertTrue(any(relative in error and copyright in error
                                    for error in attribution.check(self.root)))
                self.write(relative, self.header + "package fixture\n")

    def test_packaged_notice_must_match_exact_bytes(self):
        self.write(attribution.PACKAGED_NOTICE, self.notice.replace("\n", "\r\n"))
        self.assertTrue(any("must exactly match" in error for error in attribution.check(self.root)))

    def test_removing_each_source_header_marker_fails(self):
        for relative in attribution.ATTRIBUTED_SOURCES:
            for marker in attribution.SOURCE_MARKERS:
                with self.subTest(path=relative, marker=marker):
                    self.write(relative, self.header.replace(marker, "") + "package fixture\n")
                    self.assertTrue(any(relative in error and marker in error
                                        for error in attribution.check(self.root)))
                    self.write(relative, self.header + "package fixture\n")

    def test_attribution_only_at_end_of_source_is_insufficient(self):
        relative = attribution.ATTRIBUTED_SOURCES[0]
        self.write(relative, "// padding\n" * 150 + self.header)
        self.assertTrue(any(relative in error for error in attribution.check(self.root)))

    def test_empty_required_document_fails(self):
        self.write("docs/SOURCE_PROVENANCE.md", " \n")
        self.assertTrue(any("docs/SOURCE_PROVENANCE.md" in error and "empty" in error
                            for error in attribution.check(self.root)))

    def test_invalid_utf8_notice_reports_error(self):
        self.write("NOTICE", b"\xff")
        self.assertTrue(any("NOTICE: cannot read as UTF-8" in error
                            for error in attribution.check(self.root)))

    def test_cli_returns_failure_for_missing_notice(self):
        (self.root / "NOTICE").unlink()
        result = subprocess.run([sys.executable, str(REPOSITORY / "ci/check_attribution.py"),
                                 "--root", str(self.root)], capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 1)
        self.assertIn("NOTICE: missing required", result.stdout)
        self.assertEqual(result.stderr, "")


if __name__ == "__main__":
    unittest.main()

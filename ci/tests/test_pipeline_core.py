"""Expressive Core (XDA-021) staging and publication routing in the Jenkins adapter."""

import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("pipeline", Path(__file__).parents[1] / "pipeline.py")
pipeline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pipeline)

FULL = Path("ExpressiveLauncherL3.4.1.1.expressive.qa.apk")
CORE = Path("ExpressiveLauncherL3-Core.4.1.1.expressive.qa.apk")


class PipelineCoreTest(unittest.TestCase):
    def run_main(self, *argv, env=None):
        with mock.patch.object(sys, "argv", ["pipeline.py", *argv]), mock.patch.dict(os.environ, env or {}):
            pipeline.main()

    def test_each_variant_stages_only_its_own_apk(self):
        self.assertEqual(FULL, pipeline.select_apk([FULL, CORE], "full"))
        self.assertEqual(CORE, pipeline.select_apk([FULL, CORE], "core"))
        self.assertEqual(FULL, pipeline.select_apk([FULL], "full"))
        for apks, variant in (([FULL], "core"), ([CORE], "full"), ([CORE, CORE], "core"), ([], "full")):
            with self.subTest(apks=apks, variant=variant), self.assertRaises(SystemExit):
                pipeline.select_apk(apks, variant)

    def test_core_evidence_lives_beside_full_evidence(self):
        workspace = Path("/w")
        self.assertEqual(workspace / "artifacts", pipeline.artifact_dir(workspace, "full"))
        self.assertEqual(workspace / "artifacts-core", pipeline.artifact_dir(workspace, "core"))

    def test_release_ids_cannot_cross_variants(self):
        full, core = pipeline.release_id_pattern("qa", "full"), pipeline.release_id_pattern("qa", "core")
        self.assertTrue(re.fullmatch(full, "qa-4.1.1-60-build-66"))
        self.assertFalse(re.fullmatch(full, "qa-core-4.1.1-60-build-66"))
        self.assertTrue(re.fullmatch(core, "qa-core-4.1.1-60-build-66"))
        self.assertFalse(re.fullmatch(core, "qa-4.1.1-60-build-66"))

    def test_core_is_qa_only(self):
        for argv in (["publish", "--variant", "core", "--channel", "release", "--release-id", "release-core-4.1.1-60-build-1"],
                     ["bridge-legacy-qa", "--variant", "core", "--release-id", "qa-core-4.1.1-60-build-1"]):
            with self.subTest(argv=argv), self.assertRaisesRegex(SystemExit, "only for QA"):
                self.run_main(*argv)

    def test_publish_refuses_a_seal_of_the_other_variant(self):
        with tempfile.TemporaryDirectory() as home:
            for release_id, metadata, variant in (
                    ("qa-core-4.1.1-60-build-66", {"channel": "qa"}, "core"),
                    ("qa-4.1.1-60-build-66", {"channel": "qa", "variant": "core"}, "full")):
                release = Path(home) / "releases" / release_id
                release.mkdir(parents=True)
                (release / "metadata.json").write_text(json.dumps(metadata))
                argv = ["publish", "--release-id", release_id, "--output", str(Path(home) / "receipt.json")]
                if variant == "core":
                    argv += ["--variant", "core"]
                with self.subTest(release_id=release_id), self.assertRaisesRegex(SystemExit, "different build variant"):
                    self.run_main(*argv, env={"EXPRESSIVE_CI_HOME": home})


if __name__ == "__main__":
    unittest.main()

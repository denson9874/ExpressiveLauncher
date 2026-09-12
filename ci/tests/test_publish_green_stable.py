"""Offline integration of green authorization, stable branch, and updater publication."""

import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest import mock

import test_publish_qa as fixtures


publisher = fixtures.publisher


def load_helper(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parents[1] / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class GreenStablePublicationTest(unittest.TestCase):
    # Reuse data builders without inheriting or rediscovering the existing tests.
    write_artifacts = fixtures.StablePublishPolicyTest.write_artifacts
    feed = fixtures.StablePublishPolicyTest.feed
    release = fixtures.StablePublishPolicyTest.release
    asset = fixtures.StablePublishPolicyTest.asset

    def setUp(self):
        fixtures.StablePublishPolicyTest.setUp(self)
        self.directory = self.directory.resolve()
        self.apk = self.apk.resolve()
        self.metadata["validationOnly"] = True
        self.metadata["weeklyReleaseGate"]["status"] = "validation-only"
        self.report += "Private validation artifact; original publication eligibility was withheld.\n"
        self.write_artifacts()
        self.authorization = {"schemaVersion": 1, "policy": "green-stable-v1",
                              "releaseId": "release-1.0.8-9-build-7"}
        self.authorization_path = self.directory / "publication-authorization.json"
        self.authorization_path.write_text(json.dumps(self.authorization))
        self.enterContext(mock.patch.dict(sys.modules, {"publish_qa": publisher}))
        self.green = load_helper("green_stable")
        self.branch = load_helper("stable_branch")
        self.enterContext(mock.patch.dict(sys.modules, {
            "green_stable": self.green, "stable_branch": self.branch,
        }))
        self.validate = self.enterContext(mock.patch.object(self.green, "validate_authorization"))
        self.enterContext(mock.patch.object(publisher.subprocess, "run",
                                           side_effect=AssertionError("Live process forbidden in offline test")))
        self.enterContext(mock.patch.object(publisher.urllib.request, "urlopen",
                                           side_effect=AssertionError("Live HTTP forbidden in offline test")))
        self.files = {
            self.apk.name: self.apk,
            f"{self.apk.stem}-QA-report.md": self.directory / "QA-report.md",
            f"{self.apk.stem}-metadata.json": self.directory / "metadata.json",
            f"{self.apk.stem}-qa-result.json": self.directory / "qa-result.json",
            f"{self.apk.stem}-seal.json": self.directory / "seal.json",
            f"{self.apk.stem}-publication-authorization.json": self.authorization_path,
        }

    def publish(self, receipt, promote=True):
        publisher.publish(self.directory, promote, receipt, channel="release",
                          authorization_path=self.authorization_path)

    def fake_publication(self):
        events = []
        state = {"feed": None, "assets": {}, "draft": True}
        github = mock.Mock(spec=publisher.GitHub)
        github.preflight.return_value = "b" * 40
        github.feed.side_effect = lambda: (copy.deepcopy(state["feed"]), "c" * 40)
        github.ensure_release.side_effect = lambda *_: self.release(state["draft"])
        github.asset.side_effect = lambda _, name: state["assets"].get(name)

        def stage(_, name, path):
            state["assets"].setdefault(name, self.asset(name, path))
            return state["assets"][name]

        def publish_release(*_):
            state["draft"] = False
            events.append("release-published")
            return self.release(False)

        def digest(url, size):
            path = self.files[url.rsplit("/", 1)[-1]]
            events.append("asset-verified")
            return path.stat().st_size, publisher.file_hash(path)

        def update_feed(proposed, metadata=None):
            self.assertEqual(metadata, self.metadata)
            events.append("compatibility-feed")
            state["feed"] = copy.deepcopy(proposed)
            return True

        github.stage.side_effect = stage
        github.publish_release.side_effect = publish_release
        github.update_feed.side_effect = update_feed
        self.enterContext(mock.patch.object(publisher, "GitHub", return_value=github))
        self.enterContext(mock.patch.object(publisher, "public_feed",
                                           side_effect=lambda **_: copy.deepcopy(state["feed"])))
        self.enterContext(mock.patch.object(publisher, "public_download_digest", side_effect=digest))
        return github, state, events

    def test_historical_marker_is_overridden_only_by_validated_authorization(self):
        original = {path: path.read_bytes() for path in self.files.values()}
        metadata, files = publisher.load_artifacts(self.directory, "release", self.authorization)
        self.validate.assert_called_once_with(self.authorization, self.directory, self.metadata)
        self.assertTrue(metadata["validationOnly"])
        self.assertEqual(len(files), 4)
        self.assertEqual(original, {path: path.read_bytes() for path in original})
        with self.assertRaisesRegex(publisher.PublishError, "Validation-only"):
            publisher.load_artifacts(self.directory, "release")

    def test_rejected_authorization_prevents_any_remote_access(self):
        self.validate.side_effect = ValueError("Originating Jenkins build is not green")
        with mock.patch.object(publisher, "GitHub") as github:
            with self.assertRaisesRegex(ValueError, "not green"):
                self.publish({})
        github.assert_not_called()

    def test_qa_cannot_use_stable_authorization(self):
        self.metadata.update(channel="qa", packageName=publisher.QA_PACKAGE)
        self.write_artifacts()
        with mock.patch.object(publisher, "GitHub") as github:
            with self.assertRaisesRegex(publisher.PublishError, "cannot be used for QA"):
                publisher.publish(self.directory, True, {}, channel="qa",
                                  authorization_path=self.authorization_path)
        self.validate.assert_not_called()
        github.assert_not_called()

    def test_authorization_does_not_bypass_seal_or_signer_checks(self):
        for damage, message in (("signer", "durable release certificate"),
                                ("seal", "metadataSha256")):
            with self.subTest(damage=damage):
                self.metadata["certificateSha256"] = publisher.QA_CERTIFICATE
                if damage == "signer":
                    self.metadata["certificateSha256"] = "b" * 64
                self.write_artifacts()
                if damage == "seal":
                    path = self.directory / "metadata.json"
                    path.write_text(path.read_text() + "\n")
                with mock.patch.object(publisher, "GitHub") as github:
                    with self.assertRaisesRegex(publisher.PublishError, message):
                        self.publish({})
                github.assert_not_called()

    def test_branch_failure_prevents_compatibility_feed_promotion(self):
        github, state, events = self.fake_publication()
        receipt = {}
        with mock.patch.object(self.branch, "publish_stable_branch",
                               side_effect=publisher.PublishError("Stable branch readback failed")):
            with self.assertRaisesRegex(publisher.PublishError, "branch readback failed"):
                self.publish(receipt)
        github.update_feed.assert_not_called()
        self.assertIsNone(state["feed"])
        self.assertEqual(events.count("asset-verified"), 6)
        self.assertEqual(receipt["status"], "publishing-stable-branch")
        self.assertNotIn("feedVerified", receipt)

    def test_verified_branch_precedes_compatibility_feed_and_is_in_receipt(self):
        github, state, events = self.fake_publication()
        branch_receipt = {"branch": "stable", "commit": "d" * 40, "verified": True}

        def branch_write(actual_github, metadata, files, authorization):
            self.assertIs(actual_github, github)
            self.assertEqual(metadata, self.metadata)
            self.assertEqual(files, self.files)
            self.assertEqual(authorization, self.authorization)
            self.assertEqual(events.count("asset-verified"), 6)
            events.append("branch-verified")
            return branch_receipt

        receipt = {}
        with mock.patch.object(self.branch, "publish_stable_branch", side_effect=branch_write):
            self.publish(receipt)
        self.assertLess(events.index("branch-verified"), events.index("compatibility-feed"))
        self.assertEqual(state["feed"], self.feed())
        self.assertEqual(receipt["stableBranch"], branch_receipt)
        self.assertEqual(receipt["publicationAuthorization"], self.authorization)
        self.assertEqual(receipt["publicationAuthorizationSha256"],
                         publisher.file_hash(self.authorization_path))
        self.assertEqual(receipt["status"], "released")
        self.assertTrue(receipt["feedVerified"])
        self.assertEqual(set(receipt["files"]), set(self.files))
        self.assertTrue(all(asset["publicDownloadVerified"] for asset in receipt["files"].values()))

    def test_published_retry_reuses_original_seal_and_authorization_assets(self):
        github = publisher.GitHub("release")
        assets = {name: {**self.asset(name, path), "id": index}
                  for index, (name, path) in enumerate(self.files.items(), start=17)}
        remote_bytes = {assets[name]["id"]: path.read_bytes() for name, path in self.files.items()}

        def download(command, stdout, **_):
            endpoint = next(value for value in command if "/releases/assets/" in value)
            stdout.write(remote_bytes[int(endpoint.rsplit("/", 1)[-1])])
            return mock.Mock(returncode=0)

        original = {path: path.read_bytes() for path in self.files.values()}
        receipt = {}
        with mock.patch.object(publisher, "GitHub", return_value=github), \
                mock.patch.object(github, "preflight", return_value="b" * 40), \
                mock.patch.object(github, "feed", return_value=(self.feed(), "c" * 40)), \
                mock.patch.object(github, "find_release", return_value=self.release(False)), \
                mock.patch.object(github, "asset", side_effect=lambda _, name: assets[name]), \
                mock.patch.object(github, "api", return_value=self.release(False)) as api, \
                mock.patch.object(publisher.subprocess, "run", side_effect=download), \
                mock.patch.object(publisher, "public_feed", return_value=self.feed()), \
                mock.patch.object(publisher, "public_download_digest", side_effect=lambda url, size:
                                  (size, publisher.file_hash(self.files[url.rsplit("/", 1)[-1]]))), \
                mock.patch.object(self.branch, "publish_stable_branch",
                                  return_value={"branch": "stable", "changed": False}):
            self.publish(receipt)
            self.publish(receipt)
        self.assertEqual(api.call_args_list, [mock.call(github.root + "/releases/23")] * 2)
        self.assertEqual(original, {path: path.read_bytes() for path in original})
        self.assertEqual(set(receipt["files"]), set(self.files))
        self.assertFalse(receipt["feedChanged"])

    def test_staging_authorized_assets_does_not_publish_branch_or_feed(self):
        github, _, events = self.fake_publication()
        receipt = {}
        with mock.patch.object(self.branch, "publish_stable_branch") as branch:
            self.publish(receipt, promote=False)
        branch.assert_not_called()
        github.update_feed.assert_not_called()
        github.publish_release.assert_not_called()
        self.assertEqual(events, [])
        self.assertEqual(len(receipt["files"]), 6)
        self.assertEqual(receipt["status"], "draft-staged-verified")


if __name__ == "__main__":
    unittest.main()

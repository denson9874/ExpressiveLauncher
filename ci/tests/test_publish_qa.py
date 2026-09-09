"""Local release gates and monotonic feed policy; no credentials or remote writes."""

import copy
import base64
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
        return publisher.candidate_feed(self.metadata, publisher.download_url(publisher.release_tag(self.metadata), self.apk.name))

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
        with mock.patch.object(publisher, "GitHub", side_effect=AssertionError("Remote access forbidden")):
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
                with mock.patch.object(publisher, "GitHub", side_effect=AssertionError("Remote access forbidden")):
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
                with mock.patch.object(publisher, "GitHub", side_effect=AssertionError("Remote access forbidden")):
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

    def test_transition_can_ignore_only_the_download_url(self):
        candidate = self.feed()
        current = self.feed()
        current["apkUrl"] = "https://example.invalid/current.apk"
        self.assertEqual(publisher.validate_transition(current, candidate, compare_url=False), "unchanged")
        candidate["sha256"] = "b" * 64
        with self.assertRaises(publisher.PublishError):
            publisher.validate_transition(current, candidate, compare_url=False)

    def asset(self, name=None, path=None):
        name, path = name or self.apk.name, path or self.apk
        return {"id": 17, "name": name, "size": path.stat().st_size, "state": "uploaded",
                "digest": "sha256:" + publisher.file_hash(path),
                "browser_download_url": publisher.download_url(publisher.release_tag(self.metadata), name)}

    def test_github_feed_rejects_other_repository_tag_and_non_apk_asset(self):
        for url in (self.feed()["apkUrl"].replace(publisher.GITHUB_REPOSITORY, "another/Repository"),
                    self.feed()["apkUrl"].replace("qa-v1.0.8-9", "qa-v1.0.9-10"),
                    self.feed()["apkUrl"].replace(".apk", ".json"),
                    self.feed()["apkUrl"] + "?redirect=other"):
            with self.subTest(url=url):
                with self.assertRaises(publisher.PublishError):
                    publisher.validate_github_feed({**self.feed(), "apkUrl": url})

    def test_missing_feed_is_distinct_from_failed_request(self):
        github = publisher.GitHub()
        with mock.patch.object(github, "api", return_value=None) as api:
            self.assertEqual(github.feed(), (None, None))
            self.assertTrue(api.call_args.kwargs["missing_ok"])
        with mock.patch.object(github, "api", side_effect=publisher.PublishError("HTTP 403")):
            with self.assertRaisesRegex(publisher.PublishError, "HTTP 403"):
                github.feed()

    def test_feed_decodes_exact_content_and_retains_blob_sha(self):
        github = publisher.GitHub()
        response = {"type": "file", "path": publisher.QA_FEED_PATH, "encoding": "base64",
                    "sha": "c" * 40, "content": base64.b64encode(json.dumps(self.feed()).encode()).decode()}
        with mock.patch.object(github, "api", return_value=response):
            self.assertEqual(github.feed(), (self.feed(), "c" * 40))
        response["content"] = "@@@"
        with mock.patch.object(github, "api", return_value=response):
            with self.assertRaisesRegex(publisher.PublishError, "invalid base64"):
                github.feed()

    def test_preflight_rejects_private_wrong_or_read_only_repository(self):
        valid = {"full_name": publisher.GITHUB_REPOSITORY, "private": False,
                 "archived": False, "permissions": {"push": True}}
        for field, value in (("full_name", "another/Repository"), ("private", True),
                             ("archived", True), ("permissions", {"push": False})):
            with self.subTest(field=field):
                github = publisher.GitHub()
                with mock.patch.object(github, "api", return_value={**valid, field: value}) as api:
                    with self.assertRaises(publisher.PublishError):
                        github.preflight()
                    self.assertEqual(api.call_count, 1)

    def test_existing_release_must_match_exact_source_and_hash(self):
        github = publisher.GitHub()
        release = {"id": 23, "tag_name": publisher.release_tag(self.metadata), "draft": True,
                   "prerelease": True, "body": publisher.release_identity(self.metadata)}
        github.validate_release(release, self.metadata)
        for field, value in (("body", "Another source"), ("tag_name", "release-v1.0.8-9"),
                             ("prerelease", False), ("draft", 1)):
            with self.subTest(field=field):
                with self.assertRaises(publisher.PublishError):
                    github.validate_release({**release, field: value}, self.metadata)

    def test_new_release_is_a_draft_qa_prerelease_pinned_to_exports_commit(self):
        github = publisher.GitHub()
        release = {"id": 23, "tag_name": publisher.release_tag(self.metadata), "draft": True,
                   "prerelease": True, "body": publisher.release_identity(self.metadata)}
        with mock.patch.object(github, "find_release", return_value=None), \
                mock.patch.object(github, "api", return_value=release) as api:
            github.ensure_release(self.metadata, "b" * 40)
        payload = api.call_args.kwargs["body"]
        self.assertEqual(api.call_args.kwargs["method"], "POST")
        self.assertEqual(payload["target_commitish"], "b" * 40)
        self.assertTrue(payload["draft"])
        self.assertTrue(payload["prerelease"])
        self.assertEqual(payload["make_latest"], "false")
        self.assertIn(publisher.release_identity(self.metadata), payload["body"])

    def test_draft_lookup_falls_back_to_authenticated_release_list(self):
        github = publisher.GitHub()
        draft = {"id": 23, "tag_name": publisher.release_tag(self.metadata), "draft": True}
        with mock.patch.object(github, "api", side_effect=[None, [draft]]) as api:
            self.assertEqual(github.find_release(draft["tag_name"]), draft)
        self.assertIn("/releases?per_page=100&page=1", api.call_args.args[0])

    def test_promotion_keeps_qa_prerelease_and_never_marks_repository_latest(self):
        github = publisher.GitHub()
        release = {"id": 23, "tag_name": publisher.release_tag(self.metadata), "draft": True,
                   "prerelease": True, "body": publisher.release_identity(self.metadata)}
        published = {**release, "draft": False}
        with mock.patch.object(github, "api", side_effect=[published, published]) as api:
            self.assertEqual(github.publish_release(release, self.metadata), published)
        self.assertEqual(api.call_args_list[0].kwargs,
                         {"method": "PATCH", "body": {"draft": False, "prerelease": True, "make_latest": "false"}})

    def test_existing_published_release_retry_does_not_edit_release(self):
        github = publisher.GitHub()
        release = {"id": 23, "tag_name": publisher.release_tag(self.metadata), "draft": False,
                   "prerelease": True, "body": publisher.release_identity(self.metadata)}
        with mock.patch.object(github, "api", return_value=release) as api:
            github.publish_release(release, self.metadata)
        api.assert_called_once_with(github.root + "/releases/23")

    def test_same_name_asset_retry_verifies_without_upload(self):
        github = publisher.GitHub()
        asset = self.asset()
        with mock.patch.object(github, "asset", return_value=asset), \
                mock.patch.object(github, "verify_file") as verify, mock.patch.object(github, "api") as api:
            self.assertEqual(github.stage(23, self.apk.name, self.apk), asset)
            verify.assert_called_once_with(self.apk.name, self.apk, asset)
            api.assert_not_called()

    def test_existing_conflicting_asset_is_never_overwritten(self):
        github = publisher.GitHub()
        with mock.patch.object(github, "asset", return_value=self.asset()), \
                mock.patch.object(github, "verify_file", side_effect=publisher.PublishError("conflict")), \
                mock.patch.object(github, "api") as api:
            with self.assertRaisesRegex(publisher.PublishError, "conflict"):
                github.stage(23, self.apk.name, self.apk)
            api.assert_not_called()

    def test_new_asset_upload_uses_binary_input_without_replacement_or_token(self):
        github = publisher.GitHub()
        asset = self.asset()
        response = mock.Mock(returncode=0, stdout=b"HTTP/2.0 201 Created\r\n\r\n{}")
        with mock.patch.object(github, "asset", side_effect=[None, asset]), \
                mock.patch.object(github, "verify_file") as verify, \
                mock.patch.object(publisher.subprocess, "run", return_value=response) as run:
            self.assertEqual(github.stage(23, self.apk.name, self.apk), asset)
        command = run.call_args.args[0]
        self.assertIn(str(self.apk), command)
        self.assertIn("Content-Type: application/octet-stream", command)
        self.assertNotIn("--clobber", command)
        self.assertFalse(any("Authorization" in part for part in command))
        self.assertIn(f"https://uploads.github.com/repos/{publisher.GITHUB_REPOSITORY}/releases/23/assets?name="
                      + self.apk.name, command)
        verify.assert_called_once_with(self.apk.name, self.apk, asset)

    def draft_release(self):
        return {"id": 23, "tag_name": publisher.release_tag(self.metadata), "draft": True,
                "prerelease": True, "body": publisher.release_identity(self.metadata)}

    def test_502_empty_placeholder_retry_uploads_same_sealed_bytes(self):
        github = publisher.GitHub()
        assets, uploads, deletions = {}, [], []
        failed_once = False

        def api(endpoint, method="GET", file=None):
            nonlocal failed_once
            if method == "GET":
                self.assertEqual(endpoint, github.root + "/releases/23")
                return self.draft_release()
            if method == "DELETE":
                self.assertEqual(endpoint, github.root + "/releases/assets/17")
                deletions.append(assets.pop(self.apk.name))
                return None
            self.assertEqual(method, "POST")
            name = publisher.urllib.parse.parse_qs(publisher.urllib.parse.urlsplit(endpoint).query)["name"][0]
            uploads.append((name, file.read_bytes()))
            if not failed_once:
                failed_once = True
                assets[name] = {"id": 17, "name": name, "state": "starter", "size": 0}
                raise publisher.PublishError("GitHub POST request failed (HTTP 502)")
            assets[name] = self.asset(name, file)
            return assets[name]

        def verify(name, path, asset):
            self.assertEqual(asset["state"], "uploaded")
            self.assertEqual(asset["size"], path.stat().st_size)
            self.assertEqual(asset["digest"], "sha256:" + publisher.file_hash(path))

        with mock.patch.object(publisher, "GitHub", return_value=github), \
                mock.patch.object(github, "preflight", return_value="b" * 40), \
                mock.patch.object(github, "feed", return_value=(None, None)), \
                mock.patch.object(github, "ensure_release", side_effect=lambda *_: self.draft_release()), \
                mock.patch.object(github, "asset", side_effect=lambda _, name: assets.get(name)), \
                mock.patch.object(github, "api", side_effect=api), \
                mock.patch.object(github, "verify_file", side_effect=verify), \
                mock.patch.object(publisher, "public_feed", return_value=None):
            with self.assertRaisesRegex(publisher.PublishError, "HTTP 502"):
                publisher.publish(self.directory, False, {})
            receipt = {}
            publisher.publish(self.directory, False, receipt)

        self.assertEqual(receipt["status"], "draft-staged-verified")
        self.assertEqual(len(assets), 4)
        self.assertEqual(uploads[:2], [(self.apk.name, self.apk.read_bytes())] * 2)
        self.assertEqual(len(uploads), 5)
        self.assertEqual(deletions, [{"id": 17, "name": self.apk.name, "state": "starter", "size": 0}])

    def test_placeholder_recovery_rejects_published_or_conflicting_release(self):
        for changed in ({"draft": False}, {"body": "different source or hash"}, {"id": 24}):
            with self.subTest(changed=changed):
                github = publisher.GitHub()
                with mock.patch.object(github, "api", return_value={**self.draft_release(), **changed}) as api, \
                        mock.patch.object(github, "asset") as asset:
                    with self.assertRaises(publisher.PublishError):
                        github.remove_empty_upload_placeholder(23, self.metadata, self.apk.name, 17)
                api.assert_called_once_with(github.root + "/releases/23")
                asset.assert_not_called()

    def test_placeholder_recovery_never_deletes_completed_nonempty_or_changed_assets(self):
        starter = {"id": 17, "name": self.apk.name, "state": "starter", "size": 0}
        invalid = [None] + [{**starter, key: value} for key, value in (
            ("state", "uploaded"), ("size", 1), ("size", False), ("size", "0"),
            ("name", "foreign.apk"), ("id", 0), ("id", True), ("id", 18))]
        for asset in invalid:
            with self.subTest(asset=asset):
                github = publisher.GitHub()
                with mock.patch.object(github, "api", return_value=self.draft_release()) as api, \
                        mock.patch.object(github, "asset", return_value=asset):
                    with self.assertRaisesRegex(publisher.PublishError, "not safely recoverable"):
                        github.remove_empty_upload_placeholder(23, self.metadata, self.apk.name, 17)
                api.assert_called_once_with(github.root + "/releases/23")

    def test_placeholder_recovery_refuses_unexpected_artifact_name(self):
        github = publisher.GitHub()
        with mock.patch.object(github, "api") as api:
            with self.assertRaisesRegex(publisher.PublishError, "unexpected GitHub artifact name"):
                github.remove_empty_upload_placeholder(23, self.metadata, "foreign.apk", 17)
            api.assert_not_called()

    def test_placeholder_must_be_absent_after_delete_before_upload(self):
        github = publisher.GitHub()
        starter = {"id": 17, "name": self.apk.name, "state": "starter", "size": 0}
        with mock.patch.object(github, "api", side_effect=[self.draft_release(), None]) as api, \
                mock.patch.object(github, "asset", return_value=starter):
            with self.assertRaisesRegex(publisher.PublishError, "still present after deletion"):
                github.remove_empty_upload_placeholder(23, self.metadata, self.apk.name, 17)
        self.assertEqual(api.call_args_list, [mock.call(github.root + "/releases/23"),
                                            mock.call(github.root + "/releases/assets/17", method="DELETE")])

    def test_incomplete_or_checksum_conflicting_asset_fails_before_download(self):
        for key, value in (("state", "starter"), ("digest", "sha256:" + "b" * 64),
                           ("name", "different.apk"), ("size", 0)):
            with self.subTest(key=key), mock.patch.object(publisher.subprocess, "run") as run:
                with self.assertRaises(publisher.PublishError):
                    publisher.GitHub().verify_file(self.apk.name, self.apk, {**self.asset(), key: value})
                run.assert_not_called()

    def test_download_must_match_even_when_github_digest_matches(self):
        def corrupt_download(command, stdout, **kwargs):
            stdout.write(b"X" * self.apk.stat().st_size)
            return mock.Mock(returncode=0)
        with mock.patch.object(publisher.subprocess, "run", side_effect=corrupt_download):
            with self.assertRaisesRegex(publisher.PublishError, "Remote SHA-256 conflict"):
                publisher.GitHub().verify_file(self.apk.name, self.apk, self.asset())

    def test_download_uses_existing_gh_auth_without_exporting_a_token(self):
        def exact_download(command, stdout, **kwargs):
            self.assertEqual(command[:4], ["gh", "api", "--hostname", "github.com"])
            self.assertIn("Accept: application/octet-stream", command)
            self.assertFalse(any("Authorization" in item for item in command))
            stdout.write(self.apk.read_bytes())
            return mock.Mock(returncode=0)
        with mock.patch.object(publisher.subprocess, "run", side_effect=exact_download):
            publisher.GitHub().verify_file(self.apk.name, self.apk, self.asset())

    def test_feed_update_uses_old_blob_sha_for_compare_and_swap(self):
        github = publisher.GitHub()
        current = self.feed()
        current["versionCode"] -= 1
        with mock.patch.object(github, "feed", side_effect=[(current, "c" * 40), (self.feed(), "d" * 40)]), \
                mock.patch.object(github, "api", return_value={}) as api:
            self.assertTrue(github.update_feed(self.feed()))
            args = api.call_args.kwargs
            self.assertEqual(args["method"], "PUT")
            self.assertEqual(args["body"]["sha"], "c" * 40)
            self.assertEqual(args["body"]["branch"], "updates")
            self.assertEqual(json.loads(base64.b64decode(args["body"]["content"])), self.feed())

    def test_bootstrap_feed_create_has_no_old_blob_sha(self):
        github = publisher.GitHub()
        with mock.patch.object(github, "feed", side_effect=[(None, None), (self.feed(), "d" * 40)]), \
                mock.patch.object(github, "api", return_value={}) as api:
            self.assertTrue(github.update_feed(self.feed()))
            self.assertNotIn("sha", api.call_args.kwargs["body"])

    def test_feed_concurrent_change_fails_without_unconditional_retry(self):
        github = publisher.GitHub()
        current = self.feed()
        current["versionCode"] -= 1
        with mock.patch.object(github, "feed", return_value=(current, "c" * 40)), \
                mock.patch.object(github, "api", side_effect=publisher.PublishError("HTTP 409")) as api:
            with self.assertRaisesRegex(publisher.PublishError, "HTTP 409"):
                github.update_feed(self.feed())
            self.assertEqual(api.call_count, 1)

    def test_unchanged_feed_does_not_create_a_commit(self):
        github = publisher.GitHub()
        with mock.patch.object(github, "feed", return_value=(self.feed(), "c" * 40)), \
                mock.patch.object(github, "api") as api:
            self.assertFalse(github.update_feed(self.feed()))
            api.assert_not_called()

    def test_api_distinguishes_missing_from_auth_failure_without_leaking_response(self):
        github = publisher.GitHub()
        response = mock.Mock(returncode=1, stdout=b"HTTP/2.0 404 Not Found\r\nServer: GitHub\r\n\r\n{}")
        with mock.patch.object(publisher.subprocess, "run", return_value=response):
            self.assertIsNone(github.api(github.root + "/contents/qa/latest.json", missing_ok=True))
        response.stdout = b"HTTP/2.0 403 Forbidden\r\n\r\n{\"secret\":\"do-not-log\"}"
        with mock.patch.object(publisher.subprocess, "run", return_value=response):
            with self.assertRaisesRegex(publisher.PublishError, "^GitHub GET request failed \\(HTTP 403\\)$"):
                github.api(github.root, missing_ok=True)

    def test_api_cannot_address_another_repository(self):
        with mock.patch.object(publisher.subprocess, "run") as run:
            with self.assertRaisesRegex(publisher.PublishError, "outside the pinned"):
                publisher.GitHub().api("repos/another/Repository/releases")
            run.assert_not_called()

    def test_api_accepts_204_only_for_successful_delete_with_empty_body(self):
        github = publisher.GitHub()
        response = mock.Mock(returncode=0, stdout=b"HTTP/2.0 204 No Content\r\n\r\n")
        with mock.patch.object(publisher.subprocess, "run", return_value=response):
            self.assertIsNone(github.api(github.root + "/releases/assets/17", method="DELETE"))
        for method, body, returncode in (("GET", b"", 0), ("DELETE", b"{}", 0), ("DELETE", b"", 1)):
            with self.subTest(method=method, body=body, returncode=returncode):
                response.stdout = b"HTTP/2.0 204 No Content\r\n\r\n" + body
                response.returncode = returncode
                with mock.patch.object(publisher.subprocess, "run", return_value=response):
                    with self.assertRaises(publisher.PublishError):
                        github.api(github.root + "/releases/assets/17", method=method)

    def fake_publication(self, current=None, draft=True):
        _, files = publisher.load_artifacts(self.directory)
        state = {"feed": current, "assets": {}, "uploads": 0, "draft": draft}
        github = mock.Mock(spec=publisher.GitHub)
        github.preflight.return_value = "b" * 40
        github.feed.side_effect = lambda: (copy.deepcopy(state["feed"]), "c" * 40 if state["feed"] else None)
        github.ensure_release.side_effect = lambda *_: {"id": 23, "draft": state["draft"]}
        github.asset.side_effect = lambda _, name: state["assets"].get(name)
        def stage(release_id, name, path):
            if name not in state["assets"]:
                state["uploads"] += 1
                state["assets"][name] = self.asset(name, path)
            return state["assets"][name]
        github.stage.side_effect = stage
        def publish_release(*_):
            state["draft"] = False
            return {"id": 23, "draft": False}
        github.publish_release.side_effect = publish_release
        def update_feed(feed):
            changed = state["feed"] != feed
            state["feed"] = copy.deepcopy(feed)
            return changed
        github.update_feed.side_effect = update_feed
        def public_digest(url, expected_size):
            path = files[url.rsplit("/", 1)[-1]]
            return path.stat().st_size, publisher.file_hash(path)
        return github, state, public_digest

    def test_default_staging_keeps_draft_and_never_writes_channel(self):
        github, state, _ = self.fake_publication()
        receipt = {}
        with mock.patch.object(publisher, "GitHub", return_value=github), \
                mock.patch.object(publisher, "public_feed", return_value=None), \
                mock.patch.object(publisher, "public_download_digest") as download:
            publisher.publish(self.directory, False, receipt)
        self.assertEqual(receipt["status"], "draft-staged-verified")
        self.assertTrue(receipt["draft"])
        self.assertEqual(len(receipt["files"]), 4)
        github.publish_release.assert_not_called()
        github.update_feed.assert_not_called()
        download.assert_not_called()

    def test_existing_conflict_is_rejected_before_any_placeholder_cleanup_or_upload(self):
        github, state, _ = self.fake_publication()
        _, files = publisher.load_artifacts(self.directory)
        state["assets"][self.apk.name] = {"id": 17, "name": self.apk.name, "state": "starter", "size": 0}
        report_name = self.apk.stem + "-QA-report.md"
        state["assets"][report_name] = self.asset(report_name, files[report_name])
        github.verify_file.side_effect = publisher.PublishError("Remote checksum conflict")
        with mock.patch.object(publisher, "GitHub", return_value=github), \
                mock.patch.object(publisher, "public_feed", return_value=None):
            with self.assertRaisesRegex(publisher.PublishError, "checksum conflict"):
                publisher.publish(self.directory, False, {})
        github.remove_empty_upload_placeholder.assert_not_called()
        github.stage.assert_not_called()

    def test_published_release_with_starter_is_rejected_without_cleanup(self):
        github, state, _ = self.fake_publication(draft=False)
        state["assets"][self.apk.name] = {"id": 17, "name": self.apk.name, "state": "starter", "size": 0}
        github.verify_file.side_effect = publisher.PublishError("Incomplete GitHub asset")
        with mock.patch.object(publisher, "GitHub", return_value=github), \
                mock.patch.object(publisher, "public_feed", return_value=None):
            with self.assertRaisesRegex(publisher.PublishError, "Incomplete"):
                publisher.publish(self.directory, False, {})
        github.remove_empty_upload_placeholder.assert_not_called()
        github.stage.assert_not_called()

    def test_promotion_verifies_public_assets_before_bootstrap_manifest(self):
        github, state, digest = self.fake_publication()
        receipt = {}
        events = []
        original_update = github.update_feed.side_effect
        github.update_feed.side_effect = lambda feed: (events.append("feed"), original_update(feed))[1]
        def download(url, size):
            events.append("download")
            return digest(url, size)
        with mock.patch.object(publisher, "GitHub", return_value=github), \
                mock.patch.object(publisher, "public_feed", side_effect=lambda **_: state["feed"]), \
                mock.patch.object(publisher, "public_download_digest", side_effect=download):
            publisher.publish(self.directory, True, receipt)
        self.assertEqual(events, ["download"] * 4 + ["feed"])
        self.assertEqual(receipt["status"], "released")
        self.assertEqual(receipt["provider"], "github")
        self.assertTrue(receipt["feedVerified"])
        self.assertFalse(receipt["draft"])
        self.assertTrue(all(item["publicDownloadVerified"] for item in receipt["files"].values()))

    def test_failed_public_download_never_advances_feed(self):
        github, _, _ = self.fake_publication()
        with mock.patch.object(publisher, "GitHub", return_value=github), \
                mock.patch.object(publisher, "public_feed", return_value=None), \
                mock.patch.object(publisher, "public_download_digest", return_value=(1, "b" * 64)), \
                mock.patch.object(publisher.time, "sleep"):
            with self.assertRaisesRegex(publisher.PublishError, "feed was not updated"):
                publisher.publish(self.directory, True, {})
        github.update_feed.assert_not_called()

    def test_published_release_feed_failure_retries_exact_existing_assets(self):
        github, state, digest = self.fake_publication()
        original_update = github.update_feed.side_effect
        github.update_feed.side_effect = publisher.PublishError("HTTP 409")
        with mock.patch.object(publisher, "GitHub", return_value=github), \
                mock.patch.object(publisher, "public_feed", side_effect=lambda **_: state["feed"]), \
                mock.patch.object(publisher, "public_download_digest", side_effect=digest):
            with self.assertRaisesRegex(publisher.PublishError, "HTTP 409"):
                publisher.publish(self.directory, True, {})
            self.assertFalse(state["draft"])
            self.assertEqual(state["uploads"], 4)
            github.update_feed.side_effect = original_update
            receipt = {}
            publisher.publish(self.directory, True, receipt)
        self.assertEqual(receipt["status"], "released")
        self.assertEqual(state["uploads"], 4)

    def test_rollback_fails_before_release_or_asset_creation(self):
        newer = self.feed()
        newer["versionCode"] += 1
        github, _, _ = self.fake_publication(newer)
        with mock.patch.object(publisher, "GitHub", return_value=github):
            with self.assertRaisesRegex(publisher.PublishError, "older version"):
                publisher.publish(self.directory, True, {})
        github.ensure_release.assert_not_called()
        github.stage.assert_not_called()

    def test_asset_url_mismatch_after_publication_prevents_feed_advance(self):
        github, state, digest = self.fake_publication()
        original_publish = github.publish_release.side_effect
        def changed_url(*args):
            result = original_publish(*args)
            state["assets"][self.apk.name]["browser_download_url"] = "https://example.invalid/other.apk"
            return result
        github.publish_release.side_effect = changed_url
        with mock.patch.object(publisher, "GitHub", return_value=github), \
                mock.patch.object(publisher, "public_feed", return_value=None), \
                mock.patch.object(publisher, "public_download_digest", side_effect=digest):
            with self.assertRaisesRegex(publisher.PublishError, "asset URL differs"):
                publisher.publish(self.directory, True, {})
        github.update_feed.assert_not_called()


if __name__ == "__main__":
    unittest.main()

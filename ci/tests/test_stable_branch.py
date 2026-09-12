"""Atomic stable branch publication and immutable retry behavior without a network."""

import base64
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


CI = Path(__file__).parents[1]
with mock.patch.object(sys, "path", [str(CI), *sys.path]):
    SPEC = importlib.util.spec_from_file_location("stable_branch", CI / "stable_branch.py")
    stable = importlib.util.module_from_spec(SPEC)
    SPEC.loader.exec_module(stable)
publisher = stable.publisher


class MemoryGitHub:
    channel = "release"
    root = "repos/" + publisher.GITHUB_REPOSITORY

    def __init__(self):
        self.ref = None
        self.blobs, self.trees, self.commits = {}, {}, {}
        self.calls = []
        self.before_ref_write = None
        self.before_ref_read = None

    def api(self, endpoint, method="GET", body=None, missing_ok=False):
        self.calls.append((endpoint, method, copy.deepcopy(body)))
        path = endpoint.removeprefix(self.root + "/")
        if path == "git/ref/heads/stable":
            if self.before_ref_read is not None:
                self.before_ref_read(self)
            if self.ref is None:
                assert missing_ok
                return None
            return {"ref": "refs/heads/stable", "object": {"type": "commit", "sha": self.ref}}
        if path == "git/blobs" and method == "POST":
            raw = base64.b64decode(body["content"])
            sha = stable.blob_sha(raw)
            self.blobs[sha] = raw
            return {"sha": sha}
        if path.startswith("git/blobs/"):
            sha = path.rsplit("/", 1)[1]
            raw = self.blobs[sha]
            return {"sha": sha, "encoding": "base64", "size": len(raw),
                    "content": base64.b64encode(raw).decode()}
        if path == "git/trees" and method == "POST":
            entries = copy.deepcopy(self.trees.get(body.get("base_tree"), {}))
            for item in body["tree"]:
                entries[item["path"]] = {**item, "size": len(self.blobs[item["sha"]])}
            sha = hashlib.sha1(stable.json_bytes(entries)).hexdigest()
            self.trees[sha] = entries
            return {"sha": sha}
        if path.startswith("git/trees/"):
            sha = path.split("/")[2].split("?")[0]
            return {"sha": sha, "truncated": False, "tree": list(self.trees[sha].values())}
        if path == "git/commits" and method == "POST":
            sha = hashlib.sha1(stable.json_bytes(body)).hexdigest()
            self.commits[sha] = {"sha": sha, "tree": {"sha": body["tree"]},
                                 "parents": [{"sha": p} for p in body["parents"]]}
            return {"sha": sha}
        if path.startswith("git/commits/"):
            return copy.deepcopy(self.commits[path.rsplit("/", 1)[1]])
        if path in ("git/refs", "git/refs/heads/stable"):
            if self.before_ref_write is not None:
                self.before_ref_write(self)
            if method == "POST":
                publisher.require(self.ref is None, "HTTP 422 ref already exists")
                assert body["ref"] == "refs/heads/stable"
            else:
                assert method == "PATCH" and body["force"] is False
                parents = self.commits[body["sha"]]["parents"]
                publisher.require(parents == [{"sha": self.ref}], "HTTP 422 not fast forward")
            self.ref = body["sha"]
            return {"ref": "refs/heads/stable", "object": {"type": "commit", "sha": self.ref}}
        raise AssertionError((endpoint, method, body))

    def contents(self):
        return self.trees[self.commits[self.ref]["tree"]["sha"]]


class StableBranchTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.github = MemoryGitHub()
        self.apk = self.directory / "ExpressiveLauncher-1.0.16-Release.apk"
        self.apk.write_bytes(b"signed and sealed stable APK")
        self.metadata = {"schemaVersion": 1, "channel": "release", "packageName": publisher.RELEASE_PACKAGE,
                         "sourceRevision": "a" * 40, "versionName": "1.0.16", "versionCode": 17,
                         "fileName": self.apk.name, "sizeBytes": self.apk.stat().st_size,
                         "sha256": publisher.file_hash(self.apk)}
        self.authorization = {"releaseId": "release-1.0.16-17-build-7", "policy": "green-stable"}
        self.files = {self.apk.name: self.apk}
        for name, value in (("metadata.json", self.metadata), ("seal.json", {"complete": True}),
                            ("publication-authorization.json", self.authorization)):
            path = self.directory / name
            path.write_bytes(stable.json_bytes(value))
            self.files[name] = path
        self.public = mock.patch.object(stable, "public_manifest", side_effect=lambda _: self.feed())
        self.public.start()
        self.addCleanup(self.public.stop)
        self.download = mock.patch.object(publisher, "public_download_digest",
            side_effect=lambda *_: (self.metadata["sizeBytes"], self.metadata["sha256"]))
        self.download.start()
        self.addCleanup(self.download.stop)
        self.sleep = mock.patch.object(stable.time, "sleep")
        self.sleep.start()
        self.addCleanup(self.sleep.stop)

    def feed(self):
        return publisher.candidate_feed(self.metadata, publisher.download_url(
            publisher.release_tag(self.metadata, "release"), self.apk.name, "release"), "release")

    def publish(self):
        return stable.publish_stable_branch(self.github, self.metadata, self.files, self.authorization)

    def advance(self, code=18):
        self.metadata.update(versionCode=code, versionName=f"1.0.{code - 1}")
        self.authorization["releaseId"] = f"release-1.0.{code - 1}-{code}-build-8"
        self.files["metadata.json"].write_bytes(stable.json_bytes(self.metadata))
        self.files["publication-authorization.json"].write_bytes(stable.json_bytes(self.authorization))

    def test_first_publish_commits_exact_apk_and_evidence_as_an_orphan(self):
        original = {name: path.read_bytes() for name, path in self.files.items()}
        receipt = self.publish()
        self.assertEqual(receipt["status"], "published-verified")
        self.assertTrue(receipt["changed"])
        self.assertTrue(receipt["publicApkVerified"])
        self.assertEqual(self.github.commits[receipt["commit"]]["parents"], [])
        self.assertEqual(json.loads(self.github.blobs[self.github.contents()["latest.json"]["sha"]]), self.feed())
        for name, raw in original.items():
            path = "releases/" + self.authorization["releaseId"] + "/" + name
            self.assertEqual(self.github.blobs[self.github.contents()[path]["sha"]], raw)
            self.assertEqual(self.files[name].read_bytes(), raw)
        self.assertTrue(all("updates" not in endpoint for endpoint, _, _ in self.github.calls))

    def test_exact_retry_performs_no_mutations_and_verifies_existing_bytes(self):
        first = self.publish()
        self.github.calls.clear()
        second = self.publish()
        self.assertEqual(first["commit"], second["commit"])
        self.assertFalse(second["changed"])
        self.assertTrue(all(method == "GET" for _, method, _ in self.github.calls))

    def test_next_version_preserves_history_and_unrelated_branch_content(self):
        first = self.publish()
        tree = self.github.contents()
        raw = b"keep unrelated content"
        digest = stable.blob_sha(raw)
        self.github.blobs[digest] = raw
        tree["unrelated.txt"] = {"path": "unrelated.txt", "sha": digest, "mode": "100644",
                                 "type": "blob", "size": len(raw)}
        old_paths = set(tree)
        self.advance()
        second = self.publish()
        self.assertNotEqual(first["commit"], second["commit"])
        self.assertTrue(old_paths <= set(self.github.contents()))
        self.assertEqual(self.github.contents()["unrelated.txt"]["sha"], digest)
        self.assertEqual(self.github.commits[second["commit"]]["parents"], [{"sha": first["commit"]}])

    def test_rollback_does_not_upload_or_change_branch(self):
        first = self.publish()
        self.advance(16)
        self.github.calls.clear()
        with self.assertRaisesRegex(publisher.PublishError, "older version"):
            self.publish()
        self.assertEqual(self.github.ref, first["commit"])
        self.assertTrue(all(method == "GET" for _, method, _ in self.github.calls))

    def test_same_version_cannot_change_authorization_or_original_evidence(self):
        self.publish()
        for change in ("authorization", "evidence"):
            with self.subTest(change=change):
                original = copy.deepcopy(self.authorization)
                old = self.files["seal.json"].read_bytes()
                if change == "authorization":
                    self.authorization["policy"] = "different policy"
                else:
                    self.files["seal.json"].write_text('{"complete":true,"changed":true}')
                self.github.calls.clear()
                with self.assertRaisesRegex(publisher.PublishError, "conflicting"):
                    self.publish()
                self.assertTrue(all(method == "GET" for _, method, _ in self.github.calls))
                self.authorization = original
                self.files["seal.json"].write_bytes(old)

    def test_apk_mutation_is_rejected_before_remote_calls(self):
        self.apk.write_bytes(b"substituted unsigned APK")
        with self.assertRaisesRegex(publisher.PublishError, "APK bytes differ"):
            self.publish()
        self.assertEqual(self.github.calls, [])

    def test_unsafe_paths_and_qa_channel_are_rejected_before_remote_calls(self):
        self.files["../unsafe.apk"] = self.apk
        with self.assertRaisesRegex(publisher.PublishError, "Unsafe"):
            self.publish()
        del self.files["../unsafe.apk"]
        self.github.channel = "qa"
        with self.assertRaisesRegex(publisher.PublishError, "release channel"):
            self.publish()
        self.assertEqual(self.github.calls, [])

    def test_ref_change_before_write_is_detected_without_ref_mutation(self):
        self.publish()
        self.advance()
        reads = 0
        def change_on_second_read(github):
            nonlocal reads
            reads += 1
            if reads == 2:
                github.ref = "b" * 40
        self.github.before_ref_read = change_on_second_read
        self.github.calls.clear()
        with self.assertRaisesRegex(publisher.PublishError, "advanced concurrently"):
            self.publish()
        self.assertFalse(any(method in ("PATCH", "POST") and "/git/refs" in endpoint
                             for endpoint, method, _ in self.github.calls))

    def test_ref_change_between_check_and_patch_is_rejected_by_non_force_update(self):
        self.publish()
        self.advance()
        self.github.before_ref_write = lambda github: setattr(github, "ref", "c" * 40)
        with self.assertRaisesRegex(publisher.PublishError, "not fast forward"):
            self.publish()
        self.assertEqual(self.github.ref, "c" * 40)

    def test_wrong_uploaded_blob_hash_does_not_advance_branch(self):
        original = self.github.api
        def altered(endpoint, **kwargs):
            result = original(endpoint, **kwargs)
            if endpoint.endswith("/git/blobs"):
                return {"sha": "d" * 40}
            return result
        with mock.patch.object(self.github, "api", side_effect=altered):
            with self.assertRaisesRegex(publisher.PublishError, "blob SHA differs"):
                self.publish()
        self.assertIsNone(self.github.ref)

    def test_authenticated_committed_artifact_mismatch_fails(self):
        self.publish()
        path = "releases/" + self.authorization["releaseId"] + "/" + self.apk.name
        self.github.contents()[path]["sha"] = "e" * 40
        with self.assertRaisesRegex(publisher.PublishError, "conflicting committed artifact"):
            self.publish()

    def test_missing_current_manifest_does_not_reset_existing_branch_history(self):
        self.publish()
        del self.github.contents()["latest.json"]
        del self.github.contents()["publication.json"]
        self.advance()
        self.github.calls.clear()
        with self.assertRaisesRegex(publisher.PublishError, "history exists without"):
            self.publish()
        self.assertTrue(all(method == "GET" for _, method, _ in self.github.calls))

    def test_public_download_failure_is_retryable_without_rewriting_branch(self):
        with mock.patch.object(publisher, "public_download_digest", return_value=(1, "bad")):
            with self.assertRaisesRegex(publisher.PublishError, "anonymous APK/manifest"):
                self.publish()
        commit = self.github.ref
        self.github.calls.clear()
        receipt = self.publish()
        self.assertEqual(receipt["commit"], commit)
        self.assertFalse(receipt["changed"])
        self.assertTrue(all(method == "GET" for _, method, _ in self.github.calls))

    def test_public_manifest_mismatch_never_returns_success(self):
        with mock.patch.object(stable, "public_manifest", return_value={"wrong": "candidate"}):
            with self.assertRaisesRegex(publisher.PublishError, "anonymous APK/manifest"):
                self.publish()


if __name__ == "__main__":
    unittest.main()

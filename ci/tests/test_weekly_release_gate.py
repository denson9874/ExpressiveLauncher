"""Strict weekly QA policy, timezone boundaries, and publication recovery evidence."""

import copy
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location("weekly_gate", Path(__file__).parents[1] / "weekly_release_gate.py")
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


def params(**values):
    return [{"parameters": [{"name": key, "value": value} for key, value in values.items()]}]


def make_build(number, started):
    return {"number": number, "timestamp": int(started.timestamp() * 1000),
            "duration": 60_000, "building": False, "result": "SUCCESS",
            "url": f"{gate.JENKINS_URL}job/{gate.BUILD_JOB}/{number}/",
            "actions": params(SOURCE_REVISION=f"{number:040x}", VERSION_NAME=f"1.0.{number}", VERSION_CODE=str(number))}


def evidence_for(build):
    identity = gate.build_identity(build)
    metadata = {key: identity[key] for key in ("sourceRevision", "versionName", "versionCode", "buildUrl")}
    metadata.update(packageName=gate.QA_PACKAGE, channel="qa", sha256=f"{build['number']:064x}", sizeBytes=100,
                    unitTests={"tests": 10, "failures": 0, "errors": 0, "skipped": 0})
    files = {name: {"sizeBytes": 100, "sha256": metadata["sha256"]}
             for name in ("candidate.apk", "candidate-QA-report.md", "candidate-metadata.json", "candidate-qa-result.json")}
    return {"verified": True, "metadata": metadata, "source": {"sourceRevision": identity["sourceRevision"]},
            "qa": {"passed": True, "sourceRevision": identity["sourceRevision"], "sha256": metadata["sha256"],
                   "flows": {"cold_start": {"passed": True}}}, "files": files}


def publication_for(build, evidence, number=None):
    number = number or build["number"]
    identity = gate.build_identity(build)
    run = {"number": number, "timestamp": build["timestamp"] + 120_000, "duration": 60_000,
           "building": False, "result": "SUCCESS",
           "url": f"{gate.JENKINS_URL}job/{gate.PUBLISH_JOB}/{number}/",
           "actions": params(RELEASE_ID=identity["qaReleaseId"], PROMOTE_QA_FEED=True)}
    tag = f"qa-v{identity['versionName']}-{identity['versionCode']}"
    receipt = {key: evidence["metadata"][key] for key in
               ("sourceRevision", "versionName", "versionCode", "sha256", "sizeBytes")}
    receipt.update(provider="github", status="released", feedVerified=True, draft=False, promoteRequested=True,
                   publisherSourceRevision=identity["sourceRevision"], repository=gate.REPOSITORY, channel="qa",
                   tag=tag, feedUrl=gate.FEED_URL, releaseId=number,
                   releaseUrl=f"https://github.com/{gate.REPOSITORY}/releases/tag/{tag}")
    receipt["files"] = {name: {**data, "id": index + 1, "verified": True, "publicDownloadVerified": True,
                               "downloadUrl": f"https://github.com/{gate.REPOSITORY}/releases/download/{tag}/{name}"}
                        for index, (name, data) in enumerate(evidence["files"].items())}
    return run, receipt


class WeeklyGateTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 9, 12, 6, tzinfo=gate.ZONE)
        self.builds = [make_build(index + 1, datetime(2026, 9, 7 + day, 3, tzinfo=gate.ZONE))
                       for index, day in enumerate((0, 2, 4))]
        self.sealed = {build["number"]: evidence_for(build) for build in self.builds}
        pairs = [publication_for(build, self.sealed[build["number"]]) for build in self.builds]
        self.publications = [pair[0] for pair in pairs]
        self.receipts = {pair[0]["number"]: pair[1] for pair in pairs}

    def evaluate(self, **overrides):
        values = dict(now=self.now, builds=self.builds, publications=self.publications,
                      sealed=self.sealed, receipts=self.receipts)
        values.update(overrides)
        return gate.evaluate_week(**values)

    def held(self, result, reason=None):
        self.assertEqual(result["status"], "held")
        self.assertIsNone(result["selected"])
        if reason:
            self.assertIn(reason, "\n".join(result["reasons"]))

    def test_complete_week_selects_exact_final_friday_candidate(self):
        result = self.evaluate()
        self.assertEqual(result["status"], "eligible")
        self.assertEqual(result["selected"], gate.build_identity(self.builds[-1]))
        self.assertEqual(result["windowStart"], "2026-09-07T00:00:00-04:00")
        self.assertEqual(result["windowEnd"], "2026-09-12T00:00:00-04:00")

    def test_missing_each_required_day_cannot_be_green(self):
        for index, day in enumerate(("Monday", "Wednesday", "Friday")):
            with self.subTest(day=day):
                self.held(self.evaluate(builds=self.builds[:index] + self.builds[index + 1:]), f"Missing {day}")

    def test_all_build_failures_hold_even_if_same_source_later_passes(self):
        for status in ("FAILURE", "UNSTABLE", "ABORTED", "NOT_BUILT", None):
            with self.subTest(status=status):
                earlier = copy.deepcopy(self.builds[-1])
                earlier.update(number=4, result=status, timestamp=earlier["timestamp"] - 3_600_000)
                self.held(self.evaluate(builds=self.builds + [earlier]), "QA build #4")

    def test_running_friday_crossing_saturday_holds(self):
        run = self.builds[-1]
        run.update(timestamp=int(datetime(2026, 9, 11, 23, 59, tzinfo=gate.ZONE).timestamp() * 1000),
                   building=True, result=None)
        self.held(self.evaluate(), "in progress")

    def test_friday_started_build_completed_saturday_keeps_friday_coverage(self):
        run = self.builds[-1]
        run["timestamp"] = int(datetime(2026, 9, 11, 23, 59, tzinfo=gate.ZONE).timestamp() * 1000)
        self.publications[-1], self.receipts[3] = publication_for(run, self.sealed[3])
        self.assertEqual(self.evaluate()["status"], "eligible")

    def test_latest_friday_failure_does_not_fall_back_to_earlier_success(self):
        failed = make_build(4, datetime(2026, 9, 11, 20, tzinfo=gate.ZONE))
        failed["result"] = "FAILURE"
        self.held(self.evaluate(builds=self.builds + [failed]), "QA build #4")

    def test_unscheduled_tuesday_failure_also_holds_the_week(self):
        failed = make_build(4, datetime(2026, 9, 8, 3, tzinfo=gate.ZONE))
        failed["result"] = "FAILURE"
        self.held(self.evaluate(builds=self.builds + [failed]), "QA build #4")

    def test_successful_intermediate_unpublished_build_can_be_superseded(self):
        earlier = make_build(4, datetime(2026, 9, 11, 1, tzinfo=gate.ZONE))
        self.sealed[4] = evidence_for(earlier)
        self.assertEqual(self.evaluate(builds=self.builds + [earlier])["status"], "eligible")

    def test_latest_required_day_must_be_published_even_if_earlier_was(self):
        later = make_build(4, datetime(2026, 9, 11, 20, tzinfo=gate.ZONE))
        self.sealed[4] = evidence_for(later)
        self.held(self.evaluate(builds=self.builds + [later]), "no promoted publication")

    def test_missing_or_unsealed_device_and_unit_evidence_holds(self):
        original = copy.deepcopy(self.sealed[3])
        for mutate in (
            lambda evidence: evidence.update(verified=False),
            lambda evidence: evidence["metadata"]["unitTests"].update(skipped=1),
            lambda evidence: evidence["metadata"]["unitTests"].update(tests=0),
            lambda evidence: evidence["qa"].update(passed=False),
            lambda evidence: evidence["qa"].update(flows={}),
            lambda evidence: evidence["source"].update(sourceRevision="f" * 40),
        ):
            self.sealed[3] = copy.deepcopy(original)
            mutate(self.sealed[3])
            self.held(self.evaluate(), "QA build #3")
        del self.sealed[3]
        self.held(self.evaluate(), "sealed QA evidence")

    def test_foreign_or_mismatched_source_version_and_url_hold(self):
        for key, bad in (("SOURCE_REVISION", "main"), ("VERSION_NAME", "1.0"),
                         ("VERSION_CODE", True), ("VERSION_CODE", "0"), ("VERSION_CODE", "999999999999")):
            original = copy.deepcopy(self.builds[-1]["actions"])
            for item in self.builds[-1]["actions"][0]["parameters"]:
                if item["name"] == key:
                    item["value"] = bad
            self.held(self.evaluate(), "QA build #3")
            self.builds[-1]["actions"] = original
        self.sealed[3]["metadata"]["versionCode"] = 99
        self.held(self.evaluate(), "metadata differs")
        self.sealed[3]["metadata"]["versionCode"] = 3
        self.builds[-1]["url"] = "https://foreign.example/job/3/"
        self.held(self.evaluate(), "foreign Jenkins")

    def test_missing_staged_wrong_or_unverified_receipts_hold(self):
        original = copy.deepcopy(self.receipts[3])
        for key, bad in (("provider", "drive"), ("status", "staged-verified"), ("feedVerified", False),
                         ("draft", True), ("sourceRevision", "f" * 40), ("versionCode", 4),
                         ("sha256", "f" * 64), ("repository", "foreign/repo"), ("feedUrl", "https://foreign.example")):
            self.receipts[3] = copy.deepcopy(original)
            self.receipts[3][key] = bad
            self.held(self.evaluate(), "publication")
        del self.receipts[3]
        self.held(self.evaluate(), "exact publication receipt")

    def test_asset_hash_or_anonymous_verification_is_required(self):
        asset = self.receipts[3]["files"]["candidate.apk"]
        asset["publicDownloadVerified"] = False
        self.held(self.evaluate(), "asset verification")
        asset["publicDownloadVerified"] = True
        asset["sha256"] = "f" * 64
        self.held(self.evaluate(), "asset bytes")

    def test_publication_retry_can_recover_same_exact_seal(self):
        failed = copy.deepcopy(self.publications[-1])
        failed.update(number=10, timestamp=failed["timestamp"] - 60_000, result="FAILURE")
        self.assertEqual(self.evaluate(publications=self.publications + [failed])["status"], "eligible")

    def test_later_failed_or_running_publication_cannot_borrow_old_success(self):
        for status, building in (("FAILURE", False), ("ABORTED", False), (None, True)):
            later = copy.deepcopy(self.publications[-1])
            later.update(number=10, timestamp=later["timestamp"] + 60_000, result=status, building=building)
            self.held(self.evaluate(publications=self.publications + [later]), "publication")

    def test_different_candidate_success_does_not_recover_failed_publication(self):
        self.publications[-1]["result"] = "FAILURE"
        foreign = make_build(9, datetime(2026, 9, 11, 4, tzinfo=gate.ZONE))
        run, receipt = publication_for(foreign, evidence_for(foreign))
        self.receipts[9] = receipt
        self.held(self.evaluate(publications=self.publications + [run]), "run #3 is FAILURE")

    def test_queue_or_running_qa_outside_week_still_holds(self):
        for job in (gate.BUILD_JOB, gate.PUBLISH_JOB):
            self.held(self.evaluate(queue=[{"id": 90, "task": {"name": job}}]), "remains queued")
        saturday = make_build(4, datetime(2026, 9, 12, 3, tzinfo=gate.ZONE))
        saturday.update(building=True, result=None)
        self.held(self.evaluate(builds=self.builds + [saturday]), "remains in progress")
        self.held(self.evaluate(queue=[{"id": 90, "task": {}}]), "unknown task")

    def test_saturday_manual_success_never_changes_selected_friday(self):
        saturday = make_build(4, datetime(2026, 9, 12, 3, tzinfo=gate.ZONE))
        result = self.evaluate(builds=self.builds + [saturday])
        self.assertEqual(result["selected"]["buildNumber"], 3)

    def test_new_york_calendar_controls_saturday_not_utc(self):
        self.held(self.evaluate(now=datetime(2026, 9, 12, 1, tzinfo=timezone.utc)), "only on Saturday")
        self.assertEqual(self.evaluate(now=datetime(2026, 9, 13, 3, 59, tzinfo=timezone.utc))["status"], "eligible")
        self.held(self.evaluate(now=datetime(2026, 9, 13, 4, tzinfo=timezone.utc)), "only on Saturday")

    def test_utc_monday_before_local_midnight_is_not_monday_coverage(self):
        self.builds[0]["timestamp"] = int(datetime(2026, 9, 7, 3, tzinfo=timezone.utc).timestamp() * 1000)
        self.held(self.evaluate(), "Missing Monday")

    def test_dst_windows_use_local_midnight(self):
        for now, start, end in (
            (datetime(2026, 3, 14, 6, tzinfo=gate.ZONE), "2026-03-09T00:00:00-04:00", "2026-03-14T00:00:00-04:00"),
            (datetime(2026, 11, 7, 6, tzinfo=gate.ZONE), "2026-11-02T00:00:00-05:00", "2026-11-07T00:00:00-05:00"),
        ):
            result = gate.evaluate_week(now, [], [], {}, {})
            self.assertEqual(result["windowStart"], start)
            self.assertEqual(result["windowEnd"], end)

    def test_unknown_history_and_completion_after_snapshot_hold(self):
        self.held(self.evaluate(evidence_errors=["history incomplete"]), "history incomplete")
        self.builds[-1]["duration"] = 10 * 86_400_000
        self.held(self.evaluate(), "completion is later")

    def test_duplicate_run_or_parameter_is_not_accepted(self):
        self.held(self.evaluate(builds=self.builds + [self.builds[-1]]), "duplicate Jenkins build")
        self.held(self.evaluate(publications=self.publications + [self.publications[-1]]), "duplicate Jenkins publication")
        self.builds[-1]["actions"][0]["parameters"].append({"name": "SOURCE_REVISION", "value": "f" * 40})
        self.held(self.evaluate(), "duplicate Jenkins parameter")


class HistoryCoverageTest(unittest.TestCase):
    def response(self, numbers, first=1, next_number=None):
        start = datetime(2026, 9, 9, 3, tzinfo=gate.ZONE)
        return {"builds": [make_build(number, start) for number in numbers],
                "lastBuild": {"number": numbers[0]} if numbers else None,
                "firstBuild": {"number": first} if numbers else None,
                "nextBuildNumber": next_number or (numbers[0] + 1 if numbers else 1)}

    def read(self, response):
        class Client:
            def json(self, unused):
                return response
        return gate.read_history(Client(), gate.BUILD_JOB, datetime(2026, 9, 7, tzinfo=gate.ZONE))

    def test_complete_new_job_history_is_readable(self):
        self.assertEqual(len(self.read(self.response([3, 2, 1]))), 3)
        self.assertEqual(self.read(self.response([])), [])

    def test_gaps_missing_latest_and_retention_inside_week_hold(self):
        for response in (self.response([3, 1]), self.response([3, 2], first=2),
                         self.response([3, 2, 1], next_number=5), self.response([], next_number=10)):
            with self.subTest(response=response):
                with self.assertRaises(ValueError):
                    self.read(response)


class SnapshotReceiptScopeTest(unittest.TestCase):
    def setUp(self):
        WeeklyGateTest.setUp(self)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.ci_home = Path(self.temporary.name)
        self.file_sets = {}
        self.publications, self.receipts = [], {}
        for build in self.builds:
            number = build["number"]
            identity = gate.build_identity(build)
            directory = self.ci_home / "releases" / identity["qaReleaseId"]
            directory.mkdir(parents=True)
            evidence = self.sealed[number]
            payload = f"retained candidate {number}".encode()
            digest = hashlib.sha256(payload).hexdigest()
            evidence["metadata"].update(sha256=digest, sizeBytes=len(payload))
            evidence["qa"]["sha256"] = digest
            files = {}
            for name in evidence["files"]:
                path = directory / name
                path.write_bytes(payload)
                files[name] = path
                evidence["files"][name] = {"sha256": digest, "sizeBytes": len(payload)}
            (directory / "source.json").write_text(json.dumps(evidence["source"]))
            (directory / "qa-result.json").write_text(json.dumps(evidence["qa"]))
            self.file_sets[directory] = (evidence["metadata"], files)
            run, receipt = publication_for(build, evidence, number + 9)
            self.publications.append(run)
            self.receipts[run["number"]] = receipt

    def collect(self, missing=()):
        requested = []
        owner = self
        histories = {gate.BUILD_JOB: sorted(self.builds, key=lambda run: run["number"], reverse=True),
                     gate.PUBLISH_JOB: sorted(self.publications, key=lambda run: run["number"], reverse=True)}

        class Client:
            def json(self, path):
                if path.startswith("queue/"):
                    return {"items": []}
                if "/artifact/" in path:
                    number = int(path.rsplit("publication-", 1)[1].split(".", 1)[0])
                    requested.append(number)
                    if number in missing:
                        raise FileNotFoundError("receipt no longer retained")
                    return owner.receipts[number]
                job = path.split("/")[1]
                return {"lastBuild": {"number": histories[job][0]["number"]}}

        publisher = SimpleNamespace(load_artifacts=lambda directory: self.file_sets[directory])
        with mock.patch.object(gate, "read_history", side_effect=lambda client, job, start: histories[job]), \
                mock.patch.object(gate, "load_module", return_value=publisher):
            snapshot = gate.collect_snapshot(Client(), self.now, self.ci_home)
        return gate.evaluate_week(self.now, *snapshot), requested

    def old_publication(self):
        run = copy.deepcopy(self.publications[0])
        run.update(number=1, timestamp=int(datetime(2026, 9, 1, 3, tzinfo=gate.ZONE).timestamp() * 1000),
                   url=f"{gate.JENKINS_URL}job/{gate.PUBLISH_JOB}/1/",
                   actions=params(RELEASE_ID="qa-1.0.1-1-build-99", PROMOTE_QA_FEED=True))
        return run

    def test_missing_old_unused_receipt_is_not_fetched_or_a_hold(self):
        self.publications.append(self.old_publication())
        result, requested = self.collect(missing={1})
        self.assertEqual(result["status"], "eligible")
        self.assertEqual(set(requested), {10, 11, 12})

    def test_missing_draft_staging_receipt_is_not_fetched_or_a_hold(self):
        draft = copy.deepcopy(self.publications[-1])
        draft.update(number=13, timestamp=draft["timestamp"] + 60_000,
                     actions=params(RELEASE_ID=gate.build_identity(self.builds[-1])["qaReleaseId"],
                                    PROMOTE_QA_FEED=False))
        self.publications.append(draft)
        result, requested = self.collect(missing={13})
        self.assertEqual(result["status"], "eligible")
        self.assertNotIn(13, requested)

    def test_missing_earlier_recovered_receipt_is_not_fetched_or_a_hold(self):
        retry = copy.deepcopy(self.publications[-1])
        retry.update(number=20, timestamp=retry["timestamp"] + 60_000,
                     url=f"{gate.JENKINS_URL}job/{gate.PUBLISH_JOB}/20/")
        self.publications.append(retry)
        self.receipts[20] = copy.deepcopy(self.receipts[12])
        result, requested = self.collect(missing={12})
        self.assertEqual(result["status"], "eligible")
        self.assertEqual(set(requested), {10, 11, 20})

    def test_missing_latest_required_receipt_still_holds(self):
        result, requested = self.collect(missing={12})
        self.assertEqual(result["status"], "held")
        self.assertIsNone(result["selected"])
        self.assertIn(12, requested)
        self.assertIn("Exact archived receipt is unavailable for publication #12", result["reasons"])

    def test_old_completed_malformed_parameters_are_ignored_but_running_still_holds(self):
        old = self.old_publication()
        old["actions"] = [{"parameters": [{"value": "obsolete-schema"}]}]
        self.publications.append(old)
        result, requested = self.collect(missing={1})
        self.assertEqual(result["status"], "eligible")
        self.assertNotIn(1, requested)
        old.update(building=True, result=None)
        result, requested = self.collect(missing={1})
        self.assertEqual(result["status"], "held")
        self.assertIn("QA publication #1 remains in progress or unknown", result["reasons"])


if __name__ == "__main__":
    unittest.main()

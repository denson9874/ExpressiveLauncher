#!/usr/bin/env python3
"""Read-only Saturday eligibility gate for the current New York QA week.

The CLI always uses the system clock. Tests inject an aware datetime into evaluate_week;
there is deliberately no command-line date override or caller-selected source revision.
"""

import argparse
from datetime import datetime, time, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
from zoneinfo import ZoneInfo


ZONE = ZoneInfo("America/New_York")
JENKINS_URL = "http://127.0.0.1:8091/"
BUILD_JOB = "expressive-qa-build"
PUBLISH_JOB = "expressive-qa-publish"
QA_PACKAGE = "dev.launcher.expressive.l3"
REPOSITORY = "denson9874/ExpressiveLauncher"
FEED_URL = f"https://raw.githubusercontent.com/{REPOSITORY}/updates/qa-v2/latest.json"
REQUIRED_DAYS = {0: "Monday", 2: "Wednesday", 4: "Friday"}
RUN_FIELDS = "number,url,timestamp,duration,building,result,queueId,actions[parameters[name,value]]"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def positive_int(value):
    return type(value) is int and value > 0


def parameters(run):
    result = {}
    for action in run.get("actions", []):
        for item in action.get("parameters", []):
            name = item["name"]
            require(name not in result, "duplicate Jenkins parameter " + name)
            result[name] = item["value"]
    return result


def run_start(run):
    require(positive_int(run.get("number")), "missing Jenkins run number")
    require(positive_int(run.get("timestamp")), "missing Jenkins timestamp")
    return datetime.fromtimestamp(run["timestamp"] / 1000, timezone.utc)


def completed_success(run, now):
    require(run.get("building") is False and run.get("result") == "SUCCESS",
            f"run #{run['number']} is {run.get('result') or 'in progress/unknown'}")
    require(type(run.get("duration")) is int and run["duration"] >= 0,
            "missing Jenkins terminal duration")
    require(run_start(run) + timedelta(milliseconds=run["duration"]) <= now,
            "Jenkins completion is later than the gate snapshot")


def build_identity(run):
    values = parameters(run)
    revision = values.get("SOURCE_REVISION")
    version = values.get("VERSION_NAME")
    code = values.get("VERSION_CODE")
    require(isinstance(revision, str) and re.fullmatch(r"[0-9a-f]{40}", revision),
            "invalid full source revision")
    require(isinstance(version, str) and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version),
            "invalid version name")
    require(type(code) in (str, int) and re.fullmatch(r"[1-9][0-9]*", str(code))
            and int(code) <= 2_100_000_000, "invalid version code")
    return {
        "sourceRevision": revision, "versionName": version, "versionCode": int(code),
        "qaReleaseId": f"qa-{version}-{code}-build-{run['number']}",
        "buildNumber": run["number"],
        "buildUrl": f"{JENKINS_URL}job/{BUILD_JOB}/{run['number']}/",
    }


def verify_build_evidence(run, evidence):
    identity = build_identity(run)
    require(run.get("url") == identity["buildUrl"], "foreign Jenkins build URL")
    require(isinstance(evidence, dict) and evidence.get("verified") is True,
            "sealed QA evidence is missing or invalid")
    metadata = evidence["metadata"]
    for key in ("sourceRevision", "versionName", "versionCode", "buildUrl"):
        require(metadata.get(key) == identity[key], "sealed metadata differs: " + key)
    require(metadata.get("packageName") == QA_PACKAGE and metadata.get("channel") == "qa",
            "sealed package/channel is not QA")
    require(evidence["source"].get("sourceRevision") == identity["sourceRevision"],
            "retained source provenance differs")
    totals = metadata.get("unitTests", {})
    require(positive_int(totals.get("tests")) and all(type(totals.get(k)) is int and totals[k] == 0
            for k in ("failures", "errors", "skipped")), "full unit suite is not a complete pass")
    qa = evidence["qa"]
    require(qa.get("passed") is True and qa.get("sourceRevision") == identity["sourceRevision"]
            and qa.get("sha256") == metadata.get("sha256"), "retained device QA identity differs")
    require(isinstance(qa.get("flows"), dict) and bool(qa["flows"])
            and all(isinstance(flow, dict) and flow.get("passed") is True for flow in qa["flows"].values()),
            "retained device flows are missing or not passing")
    require(isinstance(evidence.get("files"), dict) and len(evidence["files"]) == 4,
            "sealed publication file identities are incomplete")
    return identity


def verify_receipt(run, receipt, identity, evidence):
    require(isinstance(receipt, dict), "exact publication receipt is missing")
    require(run.get("url") == f"{JENKINS_URL}job/{PUBLISH_JOB}/{run['number']}/",
            "foreign Jenkins publication URL")
    values = parameters(run)
    require(values.get("RELEASE_ID") == identity["qaReleaseId"]
            and values.get("PROMOTE_QA_FEED") is True, "publication did not promote this exact seal")
    require(receipt.get("provider") == "github" and receipt.get("status") == "released"
            and receipt.get("feedVerified") is True and receipt.get("draft") is False
            and receipt.get("promoteRequested") is True, "publication is not released and feed verified")
    metadata = evidence["metadata"]
    for key in ("sourceRevision", "versionName", "versionCode", "sha256", "sizeBytes"):
        require(receipt.get(key) == metadata.get(key), "publication identity differs: " + key)
    require(receipt.get("publisherSourceRevision") == identity["sourceRevision"],
            "publisher source revision differs")
    tag = f"qa-v{identity['versionName']}-{identity['versionCode']}"
    require(receipt.get("repository") == REPOSITORY and receipt.get("channel") == "qa"
            and receipt.get("tag") == tag and receipt.get("feedUrl") == FEED_URL
            and receipt.get("releaseUrl") == f"https://github.com/{REPOSITORY}/releases/tag/{tag}",
            "publication repository, release, or feed differs")
    require(positive_int(receipt.get("releaseId")), "publication release ID is missing")
    files = receipt.get("files", {})
    require(isinstance(files, dict), "publication assets must be a file map")
    if "releaseAssetPolicy" in receipt:
        require(receipt["releaseAssetPolicy"] == "apk-only", "unknown publication asset policy")
        apk_name = metadata.get("fileName")
        require(isinstance(apk_name, str) and apk_name in evidence["files"],
                "sealed publication APK identity is missing")
        expected_files = {apk_name: evidence["files"][apk_name]}
    else:
        # Retained receipts predate the APK-only download policy. Keep checking
        # all four historical attachments against the unchanged internal seal.
        expected_files = evidence["files"]
    require(set(files) == set(expected_files), "publication assets differ from the seal or asset policy")
    for name, expected in expected_files.items():
        asset = files[name]
        require(asset.get("verified") is True and asset.get("publicDownloadVerified") is True
                and positive_int(asset.get("id")), "asset verification is incomplete: " + name)
        require(all(asset.get(key) == expected[key] for key in ("sha256", "sizeBytes")),
                "asset bytes differ from the seal: " + name)
        require(asset.get("downloadUrl") == f"https://github.com/{REPOSITORY}/releases/download/{tag}/{name}",
                "foreign asset URL: " + name)


def relevant_promotions(publications, release_ids, window_start, now):
    """Select only the latest promoted attempt for this week's candidate identities."""
    promoted, reasons, seen = {}, [], set()
    for run in publications:
        try:
            timestamp = run_start(run)
            if run.get("building") is not False or run.get("result") is None:
                reasons.append(f"QA publication #{run['number']} remains in progress or unknown")
            try:
                values = parameters(run)
            except (ValueError, KeyError, TypeError):
                if timestamp < window_start:
                    # Old complete rows may predate the current parameter contract.
                    continue
                raise
            release_id = values.get("RELEASE_ID")
            if timestamp < window_start and release_id not in release_ids:
                continue
            require(isinstance(release_id, str)
                    and re.fullmatch(r"qa-\d+\.\d+\.\d+-[1-9]\d*-build-[1-9]\d*", release_id),
                    "invalid publication release ID")
            if release_id not in release_ids:
                continue
            require(run["number"] not in seen, "duplicate Jenkins publication number")
            seen.add(run["number"])
            if values.get("PROMOTE_QA_FEED") is True and timestamp <= now:
                previous = promoted.get(release_id)
                if previous is None or (timestamp, run["number"]) > (run_start(previous), previous["number"]):
                    promoted[release_id] = run
        except (ValueError, KeyError, TypeError, OverflowError) as error:
            reasons.append("Unusable QA publication history: " + str(error))
    return promoted, reasons


def evaluate_week(now, builds, publications, sealed, receipts, queue=(), evidence_errors=()):
    """Evaluate a complete immutable evidence snapshot; never chooses an arbitrary source."""
    require(now.tzinfo is not None and now.utcoffset() is not None, "an aware datetime is required")
    local = now.astimezone(ZONE)
    monday = local.date() - timedelta(days=local.weekday())
    start = datetime.combine(monday, time.min, ZONE)
    end = datetime.combine(monday + timedelta(days=5), time.min, ZONE)
    reasons = list(evidence_errors)
    result = {"schemaVersion": 1, "status": "held", "checkedAt": now.isoformat(),
              "timezone": ZONE.key, "windowStart": start.isoformat(), "windowEnd": end.isoformat(),
              "selected": None, "coverage": {}, "builds": [], "reasons": reasons}
    if local.weekday() != 5:
        reasons.append("Stable release eligibility is evaluated only on Saturday in America/New_York")
    for item in queue:
        task = item.get("task", {}) if isinstance(item, dict) else {}
        if not isinstance(task, dict) or not isinstance(task.get("name"), str):
            reasons.append("Jenkins queue contains an unknown task; QA inactivity cannot be established")
        elif task.get("name") in (BUILD_JOB, PUBLISH_JOB):
            reasons.append(f"QA job remains queued: {task['name']} queue {item.get('id', 'unknown')}")
    week_builds = []
    seen = set()
    for run in builds:
        try:
            timestamp = run_start(run)
            require(run["number"] not in seen, "duplicate Jenkins build number")
            seen.add(run["number"])
            if run.get("building") is not False or run.get("result") is None:
                reasons.append(f"QA build #{run['number']} remains in progress or unknown")
            if start <= timestamp < end:
                week_builds.append(run)
        except (ValueError, KeyError, TypeError, OverflowError) as error:
            reasons.append("Unusable QA build history: " + str(error))
    identities = {}
    for run in sorted(week_builds, key=lambda item: (item["timestamp"], item["number"])):
        try:
            completed_success(run, now)
            identities[run["number"]] = verify_build_evidence(run, sealed.get(run["number"]))
            result["builds"].append({**identities[run["number"]], "status": "SUCCESS"})
        except (ValueError, KeyError, TypeError) as error:
            reasons.append(f"QA build #{run['number']} holds the week: {error}")
    promoted, publication_errors = relevant_promotions(
        publications, {identity["qaReleaseId"] for identity in identities.values()}, start, now)
    reasons.extend(publication_errors)
    for weekday, name in REQUIRED_DAYS.items():
        day_builds = [run for run in week_builds if run_start(run).astimezone(ZONE).weekday() == weekday]
        if not day_builds:
            reasons.append(f"Missing {name} QA build coverage")
            result["coverage"][name] = {"status": "missing"}
            continue
        run = max(day_builds, key=lambda item: (item["timestamp"], item["number"]))
        identity = identities.get(run["number"])
        result["coverage"][name] = {"status": "held", "buildNumber": run["number"]}
        if identity is None:
            continue
        publication = promoted.get(identity["qaReleaseId"])
        try:
            require(publication is not None, "no promoted publication for the latest candidate")
            completed_success(publication, now)
            require(run_start(publication) >= run_start(run) + timedelta(milliseconds=run["duration"]),
                    "publication predates build completion")
            verify_receipt(publication, receipts.get(publication["number"]), identity, sealed[run["number"]])
            result["coverage"][name] = {"status": "released", **identity,
                                         "publicationNumber": publication["number"], "publicationUrl": publication["url"]}
        except (ValueError, KeyError, TypeError) as error:
            reasons.append(f"{name} QA publication holds the week: {error}")
    # A promoted attempt for an intermediate candidate must also be resolved. A successful
    # intermediate build that was never promoted may be superseded by that day's final build.
    for identity in identities.values():
        publication = promoted.get(identity["qaReleaseId"])
        if publication is not None:
            try:
                completed_success(publication, now)
                verify_receipt(publication, receipts.get(publication["number"]), identity,
                               sealed[identity["buildNumber"]])
            except (ValueError, KeyError, TypeError) as error:
                reason = f"QA publication #{publication['number']} holds the week: {error}"
                if reason not in reasons:
                    reasons.append(reason)
    if not reasons:
        friday = result["coverage"]["Friday"]
        result["status"] = "eligible"
        result["selected"] = {key: friday[key] for key in
                              ("sourceRevision", "versionName", "versionCode", "qaReleaseId", "buildNumber", "buildUrl")}
    return result


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_history(client, job, window_start):
    """Read through the week boundary, rejecting retention gaps and incomplete API pages."""
    runs = []
    for offset in range(0, 10_000, 100):
        data = client.json(f"job/{job}/api/json?tree=builds[{RUN_FIELDS}]"
                           f"{{{offset},{offset + 100}}},firstBuild[number],lastBuild[number],nextBuildNumber")
        page = data.get("builds")
        require(isinstance(page, list), "Jenkins history response is incomplete")
        if not runs:
            latest = data.get("lastBuild")
            require(latest is not None or data.get("nextBuildNumber") == 1,
                    "Jenkins history was deleted or is unavailable")
            if latest is not None:
                require(data.get("nextBuildNumber") == latest["number"] + 1,
                        "Jenkins latest run is missing from retained history")
        if page:
            runs.extend(page)
            for run in page:
                run_start(run)
            numbers = [run["number"] for run in runs]
            require(numbers == list(range(numbers[0], numbers[-1] - 1, -1)),
                    "Jenkins run history contains gaps or reordered records")
            require(numbers[0] == data["lastBuild"]["number"], "Jenkins history changed during collection")
            if run_start(page[-1]) < window_start:
                return runs
        if not page or len(page) < 100:
            first = data.get("firstBuild")
            require(not runs or (first is not None and runs[-1]["number"] == first["number"] == 1),
                    "Retained Jenkins history does not cover the start of the week")
            return runs
    raise ValueError("Jenkins history exceeds the bounded audit limit")


def collect_snapshot(client, now, ci_home):
    start = datetime.fromisoformat(evaluate_week(now, [], [], {}, {})["windowStart"])
    end = datetime.fromisoformat(evaluate_week(now, [], [], {}, {})["windowEnd"])
    builds = read_history(client, BUILD_JOB, start)
    publications = read_history(client, PUBLISH_JOB, start)
    publisher = load_module("weekly_qa_artifacts", Path(__file__).with_name("publish_qa.py"))
    sealed, receipts, errors, week_release_ids = {}, {}, [], set()
    for run in builds:
        if not start <= run_start(run) < end or run.get("result") != "SUCCESS" or run.get("building") is not False:
            continue
        try:
            identity = build_identity(run)
            week_release_ids.add(identity["qaReleaseId"])
            directory = ci_home / "releases" / identity["qaReleaseId"]
            metadata, files = publisher.load_artifacts(directory)
            sealed[run["number"]] = {
                "verified": True, "metadata": metadata,
                "source": json.loads((directory / "source.json").read_text()),
                "qa": json.loads((directory / "qa-result.json").read_text()),
                "files": {name: {"sizeBytes": path.stat().st_size,
                                   "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                          for name, path in files.items()},
            }
        except Exception as error:
            errors.append(f"Cannot verify sealed evidence for QA build #{run['number']}: {type(error).__name__}: {error}")
    promoted, _ = relevant_promotions(publications, week_release_ids, start, now)
    for run in promoted.values():
        if run.get("building") is False and run.get("result") == "SUCCESS":
            try:
                # The job archives receipts from older runs too. Never borrow another run's file.
                receipts[run["number"]] = client.json(
                    f"job/{PUBLISH_JOB}/{run['number']}/artifact/publication-{run['number']}.json")
            except Exception:
                errors.append(f"Exact archived receipt is unavailable for publication #{run['number']}")
    queue_response = client.json("queue/api/json?tree=items[id,task[name,url]]")
    require(isinstance(queue_response.get("items"), list), "Jenkins queue response is incomplete")
    for job, runs in ((BUILD_JOB, builds), (PUBLISH_JOB, publications)):
        latest = client.json(f"job/{job}/api/json?tree=lastBuild[number]").get("lastBuild")
        require((latest or {}).get("number") == (runs[0]["number"] if runs else None),
                "Jenkins changed while the gate evidence was read")
    return builds, publications, sealed, receipts, queue_response["items"], errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    now = datetime.now(timezone.utc)
    try:
        control = load_module("weekly_jenkins_control", Path(__file__).parent / "jenkins" / "control.py")
        snapshot = collect_snapshot(control.Client(), now, control.BASE)
        result = evaluate_week(now, *snapshot)
    except Exception as error:
        result = evaluate_week(now, [], [], {}, {}, evidence_errors=[
            f"Weekly evidence collection failed: {type(error).__name__}: {error}"])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"status": result["status"], "selected": result["selected"], "reasons": result["reasons"]}))
    return 0 if result["status"] == "eligible" else 2


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Publish an already authorized, sealed release to the literal stable branch.

GitHub Releases remains the updater download provider. This branch also retains
the exact APK and its original evidence, with one atomic commit per publication.
The caller owns build authorization, sealing, signing, and baseline validation.
"""

import base64
import binascii
import hashlib
import json
from pathlib import Path
import re
import sys
import time
import urllib.parse
import urllib.request

# The publisher is also executed directly by Jenkins. Reuse its live module so
# its main() handler catches this helper's PublishError and writes a failed
# receipt instead of creating a second exception class through a second import.
publisher = sys.modules.get("__main__")
if not (getattr(publisher, "GITHUB_REPOSITORY", None) == "denson9874/ExpressiveLauncher"
        and hasattr(publisher, "PublishError") and hasattr(publisher, "candidate_feed")):
    import publish_qa as publisher


BRANCH = "stable"


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def blob_sha(data):
    return hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()


def valid_sha(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value) is not None


def raw_url(ref, path):
    return (f"https://raw.githubusercontent.com/{publisher.GITHUB_REPOSITORY}/"
            f"{urllib.parse.quote(ref, safe='')}/{urllib.parse.quote(path, safe='/')}")


def branch_ref(github):
    response = github.api(f"{github.root}/git/ref/heads/{BRANCH}", missing_ok=True)
    if response is None:
        return None
    publisher.require(response.get("ref") == f"refs/heads/{BRANCH}"
                      and response.get("object", {}).get("type") == "commit"
                      and valid_sha(response["object"].get("sha")),
                      "Stable branch returned an invalid commit reference")
    return response["object"]["sha"]


def commit_tree(github, commit):
    record = github.api(f"{github.root}/git/commits/{commit}")
    tree = record.get("tree", {}).get("sha")
    publisher.require(record.get("sha") == commit and valid_sha(tree),
                      "Stable commit returned an invalid tree")
    response = github.api(f"{github.root}/git/trees/{tree}?recursive=1")
    publisher.require(response.get("sha") == tree and response.get("truncated") is False
                      and isinstance(response.get("tree"), list),
                      "Stable tree is missing, invalid, or truncated")
    entries = {}
    for entry in response["tree"]:
        path = entry.get("path")
        publisher.require(isinstance(path, str) and path not in entries,
                          "Stable tree contains an invalid or duplicate path")
        entries[path] = entry
    return tree, entries


def read_tree_json(github, entries, path):
    entry = entries.get(path)
    if entry is None:
        return None
    publisher.require(entry.get("type") == "blob" and entry.get("mode") == "100644"
                      and valid_sha(entry.get("sha"))
                      and type(entry.get("size")) is int
                      and 0 < entry["size"] <= publisher.MAX_JSON_BYTES,
                      f"Stable {path} is not a bounded regular JSON file")
    response = github.api(f"{github.root}/git/blobs/{entry['sha']}")
    publisher.require(response.get("encoding") == "base64", "Stable JSON blob encoding is invalid")
    try:
        data = base64.b64decode("".join(response["content"].split()), validate=True)
    except (KeyError, TypeError, ValueError, binascii.Error):
        raise publisher.PublishError("Stable JSON blob is invalid base64") from None
    publisher.require(response.get("sha") == entry["sha"] == blob_sha(data)
                      and response.get("size") == entry["size"] == len(data),
                      "Stable JSON blob content differs from its tree identity")
    return publisher.read_json_bytes(data, f"stable/{path}")


def public_manifest(url):
    request = urllib.request.Request(url + "?verification=" + str(time.time_ns()),
                                     headers={"Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=60) as response:
        publisher.require(response.geturl().startswith("https://"),
                          "Stable manifest redirected to insecure HTTP")
        return publisher.read_json_bytes(response.read(publisher.MAX_JSON_BYTES + 1),
                                         "public stable manifest")


def verify_public(commit, apk_path, metadata, proposed):
    apk_url = raw_url(commit, apk_path)
    for attempt in range(4):
        try:
            size, digest = publisher.public_download_digest(
                apk_url + "?verification=" + str(time.time_ns()), metadata["sizeBytes"])
            if (size == metadata["sizeBytes"] and digest == metadata["sha256"]
                    and public_manifest(raw_url(BRANCH, "latest.json")) == proposed):
                return
        except (OSError, ValueError, publisher.PublishError):
            pass
        if attempt < 3:
            time.sleep(2 * (attempt + 1))
    raise publisher.PublishError("Stable branch exists, but anonymous APK/manifest verification failed")


def publish_stable_branch(github, metadata, files, authorization):
    """Return a receipt only after commit, artifact, and public readback agree.

    The authorization is supplied and validated by the caller. This helper binds
    its full contents and every local evidence file to the immutable branch
    publication identity, so retries cannot substitute a new authorization.
    """
    publisher.validate_identity(metadata, "stable branch metadata", "release")
    publisher.require(getattr(github, "channel", None) == "release",
                      "Stable branch publication requires the release channel")
    publisher.require(isinstance(authorization, dict), "Stable publication authorization is missing")
    release_id = authorization.get("releaseId")
    prefix = f"release-{metadata['versionName']}-{metadata['versionCode']}-build-"
    publisher.require(isinstance(release_id, str)
                      and re.fullmatch(re.escape(prefix) + r"[1-9]\d*", release_id),
                      "Stable authorization identifies a different release")
    publisher.require(isinstance(files, dict) and metadata.get("fileName") in files,
                      "Stable branch requires the exact APK in its artifact files")
    directory = f"releases/{release_id}"
    desired, artifacts = {}, {}
    for name, path in sorted(files.items()):
        publisher.require(isinstance(name, str)
                          and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name)
                          and name != "publication.json",
                          "Unsafe or reserved stable artifact filename")
        path = Path(path)
        publisher.require(path.is_file() and not path.is_symlink(),
                          "Stable artifacts must be regular files")
        data = path.read_bytes()
        artifact = {"sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                    "gitBlobSha1": blob_sha(data)}
        if name == metadata["fileName"]:
            publisher.require(artifact["sizeBytes"] == metadata["sizeBytes"]
                              and artifact["sha256"] == metadata["sha256"],
                              "Stable APK bytes differ from sealed metadata")
        artifacts[name] = artifact
        desired[f"{directory}/{name}"] = data
    tag = publisher.release_tag(metadata, "release")
    proposed = publisher.candidate_feed(metadata,
        publisher.download_url(tag, metadata["fileName"], "release"), "release")
    identity = {key: metadata[key] for key in
                ("sourceRevision", "versionName", "versionCode", "packageName", "sha256", "sizeBytes")}
    identity.update(schemaVersion=1, channel="release", releaseId=release_id,
                    authorizationSha256=hashlib.sha256(json_bytes(authorization)).hexdigest(),
                    artifacts=artifacts, feed=proposed)
    desired.update({"latest.json": json_bytes(proposed), "publication.json": json_bytes(identity),
                    f"{directory}/publication.json": json_bytes(identity)})
    desired["README.md"] = (
        f"# Expressive Launcher {metadata['versionName']} — Stable\n\n"
        f"[Download the signed stable APK]({proposed['apkUrl']}) · "
        f"[Browse this build and its evidence]({directory}/)\n\n"
        f"The exact APK is also stored at [{metadata['fileName']}]({directory}/{metadata['fileName']}). "
        "This branch contains released binaries and publication evidence; it is not an application source snapshot.\n\n"
        f"- Version code: {metadata['versionCode']}\n"
        f"- Package: `{metadata['packageName']}`\n"
        f"- APK SHA-256: `{metadata['sha256']}`\n"
        f"- Application source revision: `{metadata['sourceRevision']}`\n"
        f"- Immutable build identity: `{release_id}`\n\n"
        "Original build reports and seals are preserved exactly as produced. The accompanying publication "
        "authorization records the later approval to distribute this green stable build, including any "
        "superseded build-time publication restriction. `publication.json` binds that authorization and "
        "every artifact to their checksums.\n\n"
        "`latest.json` mirrors the stable updater manifest at `updates/release/latest.json`. "
        "The APK URL continues to use the immutable GitHub Release asset.\n"
    ).encode("utf-8")

    old_commit = branch_ref(github)
    old_tree, entries = commit_tree(github, old_commit) if old_commit else (None, {})
    current = read_tree_json(github, entries, "latest.json")
    existing = read_tree_json(github, entries, "publication.json")
    retained = read_tree_json(github, entries, f"{directory}/publication.json")
    publisher.require((current is None) == (existing is None),
                      "Stable manifest and publication identity are inconsistent")
    if current is None:
        publisher.require(not any(path.startswith("releases/") for path in entries),
                          "Stable release history exists without its current publication identity")
        for path in ("README.md", "latest.json", "publication.json"):
            entry = entries.get(path)
            publisher.require(entry is None or (entry.get("type") == "blob"
                              and entry.get("mode") == "100644"
                              and entry.get("sha") == blob_sha(desired[path])),
                              f"Refusing to replace unmanaged stable branch content: {path}")
    if retained is not None:
        publisher.require(retained == identity, "Stable release directory has conflicting immutable evidence")
    else:
        publisher.require(not any(path.startswith(directory + "/") for path in entries),
                          "Stable release directory exists without its publication identity")
    for path, data in desired.items():
        entry = entries.get(path)
        if path.startswith(directory + "/") and entry is not None:
            publisher.require(entry.get("type") == "blob" and entry.get("mode") == "100644"
                              and entry.get("sha") == blob_sha(data) and entry.get("size") == len(data),
                              f"Stable release directory has conflicting committed artifact: {path}")
    unchanged = False
    if current is not None:
        publisher.validate_github_feed(current, "release")
        publisher.require(existing.get("feed") == current,
                          "Stable current publication identity differs from its manifest")
        unchanged = publisher.validate_transition(current, proposed, channel="release") == "unchanged"
        if unchanged:
            publisher.require(existing == identity and retained == identity,
                              "Stable same-version retry has conflicting authorization or evidence")

    if unchanged:
        commit = old_commit
    else:
        tree_updates = []
        for path, data in sorted(desired.items()):
            digest = blob_sha(data)
            prior = entries.get(path, {})
            if prior.get("sha") == digest and prior.get("mode") == "100644" and prior.get("type") == "blob":
                continue
            response = github.api(f"{github.root}/git/blobs", method="POST",
                                  body={"content": base64.b64encode(data).decode("ascii"), "encoding": "base64"})
            publisher.require(response.get("sha") == digest, "Uploaded stable blob SHA differs from local bytes")
            tree_updates.append({"path": path, "mode": "100644", "type": "blob", "sha": digest})
        body = {"tree": tree_updates}
        if old_tree is not None:
            body["base_tree"] = old_tree
        response = github.api(f"{github.root}/git/trees", method="POST", body=body)
        tree = response.get("sha")
        publisher.require(valid_sha(tree), "GitHub returned an invalid new stable tree")
        response = github.api(f"{github.root}/git/commits", method="POST",
                              body={"message": f"Publish stable {metadata['versionName']} ({metadata['versionCode']})",
                                    "tree": tree, "parents": [old_commit] if old_commit else []})
        commit = response.get("sha")
        publisher.require(valid_sha(commit), "GitHub returned an invalid new stable commit")
        publisher.require(branch_ref(github) == old_commit,
                          "Stable branch advanced concurrently; retry against its current state")
        if old_commit is None:
            github.api(f"{github.root}/git/refs", method="POST",
                       body={"ref": f"refs/heads/{BRANCH}", "sha": commit})
        else:
            # Descends only from the observed parent. A concurrent sibling push
            # is rejected by GitHub's non-fast-forward guard, without force.
            github.api(f"{github.root}/git/refs/heads/{BRANCH}", method="PATCH",
                       body={"sha": commit, "force": False})

    publisher.require(branch_ref(github) == commit, "Stable branch commit readback differs after publication")
    _, published = commit_tree(github, commit)
    for path, data in desired.items():
        entry = published.get(path, {})
        publisher.require(entry.get("type") == "blob" and entry.get("mode") == "100644"
                          and entry.get("sha") == blob_sha(data) and entry.get("size") == len(data),
                          f"Stable committed artifact readback differs: {path}")
    publisher.require(read_tree_json(github, published, "latest.json") == proposed,
                      "Stable authenticated manifest readback differs")
    apk_path = f"{directory}/{metadata['fileName']}"
    verify_public(commit, apk_path, metadata, proposed)
    publisher.require(branch_ref(github) == commit,
                      "Stable branch advanced during final verification; retry against its current state")
    return {"status": "published-verified", "branch": BRANCH, "commit": commit,
            "changed": not unchanged, "releaseId": release_id,
            "branchUrl": f"https://github.com/{publisher.GITHUB_REPOSITORY}/tree/{BRANCH}",
            "commitUrl": f"https://github.com/{publisher.GITHUB_REPOSITORY}/commit/{commit}",
            "manifestUrl": raw_url(BRANCH, "latest.json"), "apkUrl": raw_url(commit, apk_path),
            "authenticatedArtifactsVerified": True, "publicApkVerified": True,
            "publicManifestVerified": True,
            "files": {name: {**record, "path": f"{directory}/{name}",
                               "rawUrl": raw_url(commit, f"{directory}/{name}")}
                      for name, record in artifacts.items()}}

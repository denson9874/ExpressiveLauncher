#!/usr/bin/env python3
"""Authorize publication of an immutable stable candidate after Jenkins succeeds.

The original seal and its reports describe the build as it ran. This separate
record applies the user's later green-stable publication policy without changing
those records or accepting a failed, incomplete, or different build.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import subprocess
import time


POLICY = 'green-stable-v1'
USER_INSTRUCTION = 'When stable builds are green, they need to be uploaded to the stable branch on gituhub'
JOB = 'expressive-release-build'
JENKINS_URL = 'http://127.0.0.1:8091/'
RELEASE_PACKAGE = 'dev.launcher.expressive.l3'
CERTIFICATE = 'c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2'
REPOSITORY = Path(__file__).resolve().parents[1]
COMMON_FLOWS = {
    'guest_identity', 'seed_preference', 'preference_retention', 'warm_start',
    'cold_start', 'drawer_swipe', 'local_search', 'current_date_handoff', 'clean_app_logs',
}
EVIDENCE_FILES = {
    'metadataSha256': 'metadata.json',
    'qaResultSha256': 'qa-result.json',
    'reportSha256': 'QA-report.md',
}


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _digest(path):
    _require(path.is_file() and not path.is_symlink(), f'Required regular artifact is missing: {path.name}')
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def _object(path):
    _digest(path)
    try:
        value = json.loads(path.read_text())
    except (ValueError, UnicodeError) as error:
        raise ValueError(f'Invalid artifact JSON: {path.name}') from error
    _require(isinstance(value, dict), f'Artifact must contain an object: {path.name}')
    return value


def _publisher_revision(repository):
    repository = Path(repository)
    status = subprocess.check_output(
        ['git', '-C', str(repository), 'status', '--porcelain'], text=True)
    _require(not status.strip(), 'Stable publication requires a clean publisher checkout')
    revision = subprocess.check_output(
        ['git', '-C', str(repository), 'rev-parse', '--verify', 'HEAD'], text=True).strip()
    _require(re.fullmatch(r'[0-9a-f]{40}', revision) is not None, 'Publisher revision must be a full commit SHA')
    return revision


def _committed_publisher_revision(revision):
    _require(isinstance(revision, str) and re.fullmatch(r'[0-9a-f]{40}', revision) is not None,
             'Authorization publisher revision must be a full commit SHA')
    try:
        subprocess.run(['git', '-C', str(REPOSITORY), 'cat-file', '-e', revision + '^{commit}'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as error:
        raise ValueError('Authorization publisher revision does not identify a retained commit') from error
    return revision


def _jenkins_client():
    from jenkins.control import Client
    return Client()


def _green_build(number, *, wait_seconds=120):
    """Wait briefly for an asynchronous downstream job's upstream to finish."""
    expected_url = f'{JENKINS_URL}job/{JOB}/{number}/'
    try:
        client = _jenkins_client()
    except Exception as error:
        raise ValueError('Could not create the authenticated Jenkins client') from error
    deadline = time.monotonic() + wait_seconds
    while True:
        try:
            result = client.json(
                f'job/{JOB}/{number}/api/json?tree=number,url,building,result,timestamp,duration')
        except Exception as error:
            raise ValueError('Could not verify the stable Jenkins build') from error
        _require(isinstance(result, dict), 'Jenkins build response must be an object')
        _require(type(result.get('number')) is int and result['number'] == number and
                 result.get('url') == expected_url, 'Jenkins build identity differs from the sealed artifact')
        _require(type(result.get('building')) is bool, 'Jenkins building status is missing')
        if result['building']:
            _require(result.get('result') is None, 'A running Jenkins build has an inconsistent result')
            _require(time.monotonic() < deadline, 'Jenkins stable build is still running; publication is not authorized')
            time.sleep(min(2, max(0, deadline - time.monotonic())))
            continue
        _require(result.get('result') == 'SUCCESS', 'Jenkins stable build must finish with SUCCESS')
        for key in ('timestamp', 'duration'):
            _require(type(result.get(key)) is int and result[key] >= 0, f'Jenkins {key} is missing or invalid')
        return {'job': JOB, **{key: result[key] for key in
                             ('number', 'url', 'result', 'building', 'timestamp', 'duration')}}


def _verified_artifacts(artifact_dir, release_id, supplied_metadata=None):
    root = Path(artifact_dir)
    metadata = _object(root / 'metadata.json')
    if supplied_metadata is not None:
        _require(json.dumps(metadata, sort_keys=True) == json.dumps(supplied_metadata, sort_keys=True),
                 'Supplied metadata differs from the sealed metadata')
    match = re.fullmatch(r'release-(\d+\.\d+\.\d+)-([1-9]\d*)-build-([1-9]\d*)', release_id or '')
    _require(match is not None, 'Invalid stable release ID')
    version, code, build_number = match.groups()
    _require(metadata.get('versionName') == version and type(metadata.get('versionCode')) is int and
             metadata['versionCode'] == int(code), 'Release ID version differs from the sealed artifact')
    _require(metadata.get('buildUrl') == f'{JENKINS_URL}job/{JOB}/{build_number}/',
             'Sealed build URL differs from the stable release ID')
    _require(metadata.get('channel') == 'release' and metadata.get('packageName') == RELEASE_PACKAGE,
             'Green stable authorization requires the stable package and release channel')
    _require(metadata.get('signatureVerified') is True and metadata.get('debuggable') is False and
             metadata.get('certificateSha256') == CERTIFICATE,
             'Stable APK must be non-debuggable and verified with the release certificate')
    _require(type(metadata.get('validationOnly')) is bool, 'Original validation-only state must be explicit')
    _require(re.fullmatch(r'[0-9a-f]{40}', metadata.get('sourceRevision', '')) is not None,
             'Stable source revision must be a full commit SHA')
    _require(re.fullmatch(r'[0-9a-f]{64}', metadata.get('sha256', '')) is not None,
             'Stable APK SHA-256 is missing or invalid')
    filename = metadata.get('fileName')
    _require(isinstance(filename, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*\.apk', filename),
             'Unsafe stable APK filename')
    apk = root / filename
    _require(_digest(apk) == metadata['sha256'], 'Stable APK SHA-256 differs from the sealed metadata')
    _require(type(metadata.get('sizeBytes')) is int and metadata['sizeBytes'] > 0 and
             metadata['sizeBytes'] == apk.stat().st_size, 'Stable APK size differs from the sealed metadata')
    seal = _object(root / 'seal.json')
    _require(type(seal.get('schemaVersion')) is int and seal['schemaVersion'] == 1 and seal.get('complete') is True,
             'A complete schema-1 artifact seal is required')
    _require(seal.get('sha256') == metadata['sha256'] and
             seal.get('sourceRevision') == metadata['sourceRevision'], 'Seal identity differs from the stable artifact')
    hashes = {key: _digest(root / filename) for key, filename in EVIDENCE_FILES.items()}
    for key, value in hashes.items():
        _require(seal.get(key) == value, f'Sealed {key} differs from retained evidence')
    hashes['sealSha256'] = _digest(root / 'seal.json')
    units = metadata.get('unitTests')
    _require(isinstance(units, dict) and type(units.get('tests')) is int and units['tests'] > 0 and
             all(type(units.get(key)) is int and units[key] == 0 for key in ('failures', 'errors', 'skipped')),
             'Stable publication requires passing, complete, unskipped unit tests')
    qa = _object(root / 'qa-result.json')
    _require(qa.get('passed') is True and qa.get('sha256') == metadata['sha256'] and
             qa.get('apkSha256') == metadata['sha256'] and
             qa.get('sourceRevision') == metadata['sourceRevision'] and
             qa.get('channel') == 'release' and qa.get('packageName') == RELEASE_PACKAGE and
             qa.get('versionName') == version and type(qa.get('versionCode')) is int and
             qa['versionCode'] == metadata['versionCode'] and
             qa.get('reportSha256') == hashes['reportSha256'], 'Device QA must pass for the exact sealed stable APK')
    bootstrap = metadata.get('stableBootstrap')
    _require(type(bootstrap) is bool, 'Stable bootstrap state must be explicit')
    mode = 'first-stable-install' if bootstrap else 'quiesced-upgrade'
    _require(metadata.get('baselineMode') == mode and qa.get('baselineMode') == mode and
             qa.get('stableBootstrap') is bootstrap, 'Stable baseline mode differs between build and device QA')
    if bootstrap:
        _require(metadata.get('baseline') is None and qa.get('baseline') is None,
                 'First stable installation cannot claim a prior stable baseline')
        required_flows = COMMON_FLOWS | {'first_stable_install', 'same_version_reinstall'}
    else:
        baseline = metadata.get('baseline')
        _require(isinstance(baseline, dict) and
                 re.fullmatch(r'[0-9a-f]{64}', baseline.get('sha256', '')) is not None and
                 qa.get('baselineSha256') == baseline['sha256'] and
                 type(baseline.get('versionCode')) is int and 0 < baseline['versionCode'] < metadata['versionCode'] and
                 isinstance(baseline.get('versionName'), str),
                 'Stable upgrade requires the same earlier baseline in build and device QA')
        required_flows = COMMON_FLOWS | {'baseline_install', 'same_signer_upgrade'}
    flows = qa.get('flows')
    _require(isinstance(flows, dict) and required_flows <= flows.keys() and
             all(isinstance(flow, dict) and flow.get('passed') is True for flow in flows.values()),
             'Every required stable device flow must be present and passed')
    install_flow = 'same_version_reinstall' if bootstrap else 'same_signer_upgrade'
    _require(flows[install_flow].get('installedSha256') == metadata['sha256'],
             'Installed device APK differs from the sealed stable candidate')
    if not bootstrap:
        _require(flows['baseline_install'].get('version') == baseline['versionName'] and
                 str(flows['baseline_install'].get('versionCode')) == str(baseline['versionCode']),
                 'Installed baseline version differs from the sealed stable baseline')
    return metadata, hashes, int(build_number)


def _record(metadata, hashes, release_id, jenkins, publisher_revision):
    return {
        'schemaVersion': 1, 'policy': POLICY, 'releaseId': release_id, 'channel': 'release',
        **{key: metadata[key] for key in ('sourceRevision', 'versionName', 'versionCode', 'sha256')},
        **hashes, 'jenkins': jenkins, 'publisherSourceRevision': publisher_revision,
        'originalValidationOnly': metadata['validationOnly'],
        'supersedesOriginalPublicationRestriction': True, 'userInstruction': USER_INSTRUCTION,
    }


def authorize(artifact_dir, release_id, repository):
    """Return a reproducible authorization; callers retain it separately from the seal."""
    metadata, hashes, number = _verified_artifacts(artifact_dir, release_id)
    revision = _publisher_revision(repository)
    jenkins = _green_build(number)
    return _record(metadata, hashes, release_id, jenkins, revision)


def validate_authorization(authorization, artifact_dir, metadata):
    """Recheck immutable bindings, issuing publisher commit, and live Jenkins SUCCESS.

    A retry can run corrected publisher code while retaining the original policy
    record. The current publisher revision is independently recorded in its receipt.
    """
    _require(isinstance(authorization, dict), 'Stable publication authorization must be an object')
    sealed, hashes, number = _verified_artifacts(artifact_dir, authorization.get('releaseId'), metadata)
    revision = _committed_publisher_revision(authorization.get('publisherSourceRevision'))
    expected = _record(sealed, hashes, authorization['releaseId'], _green_build(number, wait_seconds=0), revision)
    _require(json.dumps(authorization, sort_keys=True) == json.dumps(expected, sort_keys=True),
             'Stable publication authorization differs from the verified candidate and green build')

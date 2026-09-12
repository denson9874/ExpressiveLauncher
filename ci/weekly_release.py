#!/usr/bin/env python3
"""Jenkins adapters for scheduled builds and automatic green stable publication."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request


STABLE_FEED = 'https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/release/latest.json'


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def git(repository, *args):
    return subprocess.check_output(['git', '-C', str(repository), *args], text=True).strip()


def guard(repository):
    if git(repository, 'branch', '--show-current') != 'codex/pixel-parity':
        raise ValueError('Weekly releases require codex/pixel-parity')
    if git(repository, 'status', '--porcelain'):
        raise ValueError('Weekly releases require a clean saved checkout')


def validate_selection(selected):
    if not re.fullmatch(r'[0-9a-f]{40}', selected.get('sourceRevision', '')):
        raise ValueError('Weekly gate did not select a full source revision')
    if not re.fullmatch(r'\d+\.\d+\.\d+', selected.get('versionName', '')):
        raise ValueError('Weekly gate did not select a valid version')
    if type(selected.get('versionCode')) is not int or not 0 < selected['versionCode'] <= 2_100_000_000:
        raise ValueError('Weekly gate did not select a valid version code')
    expected = f"qa-{selected['versionName']}-{selected['versionCode']}-build-"
    if not re.fullmatch(re.escape(expected) + r'[1-9]\d*', selected.get('qaReleaseId', '')):
        raise ValueError('Weekly gate selected a different QA identity')


def require_publish_selection(metadata, gate):
    if metadata.get('validationOnly') is True:
        raise ValueError('Validation-only candidates can never be published')
    if metadata.get('channel') != 'release' or gate.get('status') != 'eligible':
        raise ValueError('A stable candidate and an eligible live weekly gate are required')
    selected = gate.get('selected', {})
    validate_selection(selected)
    if any(metadata.get(key) != selected[key] for key in ('sourceRevision', 'versionName', 'versionCode')):
        raise ValueError('Stable candidate is not the currently selected green Friday source/version')
    original = metadata.get('weeklyReleaseGate', {})
    if original.get('status') != 'eligible' or original.get('selected', {}).get('qaReleaseId') != selected['qaReleaseId']:
        raise ValueError('Stable candidate was not built from this weekly QA selection')
    if original.get('windowStart') != gate.get('windowStart'):
        raise ValueError('Stable candidate belongs to another release week')


def live_gate(repository, output):
    run('python3', repository / 'ci/weekly_release_gate.py', '--output', output)
    gate = json.loads(output.read_text())
    if gate.get('status') != 'eligible':
        raise ValueError('This week is held by the QA gate')
    validate_selection(gate.get('selected', {}))
    return gate


def stable_feed_missing():
    try:
        with urllib.request.urlopen(STABLE_FEED, timeout=30) as response:
            if response.status != 200:
                raise ValueError('Cannot establish the stable baseline')
            return False
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return True
        raise


def prepare(args, repository, ci_home):
    guard(repository)
    workspace = args.workspace
    workspace.mkdir(parents=True, exist_ok=True)
    manual_selection = bool(args.validation_qa_release_id)
    # Preserve the legacy source-difference marker consumed by candidate scripts.
    # Publication eligibility is now recorded separately after terminal SUCCESS.
    validation = manual_selection
    if manual_selection:
        release_id = args.validation_qa_release_id
        if not re.fullmatch(r'qa-\d+\.\d+\.\d+-[1-9]\d*-build-[1-9]\d*', release_id):
            raise ValueError('Invalid validation QA release ID')
        qa = json.loads((ci_home / 'releases' / release_id / 'metadata.json').read_text())
        selected = {key: qa[key] for key in ('sourceRevision', 'versionName', 'versionCode')}
        selected.update(qaReleaseId=release_id)
        validate_selection(selected)
        gate = {'status': 'manual-selection', 'selected': selected,
                'reason': 'Explicit stable build selection; publication requires a sealed green Jenkins build'}
        revision = git(repository, 'rev-parse', 'HEAD')
    else:
        gate = live_gate(repository, workspace / 'weekly-gate.json')
        selected = gate['selected']
        revision = selected['sourceRevision']
    (workspace / 'weekly-gate.json').write_text(json.dumps(gate, indent=2) + '\n')
    script = workspace / 'prepare-stable.py'
    script.write_bytes(subprocess.check_output(['git', '-C', str(repository), 'show', revision + ':ci/prepare_build.py']))
    command = ['python3', script, '--repository', repository, '--revision', revision,
               '--workspace', workspace, '--channel', 'release', '--qa-release-id', selected['qaReleaseId']]
    bootstrap = stable_feed_missing()
    if bootstrap:
        command.append('--bootstrap-stable')
    if validation:
        command.append('--validation-only')
    run(*command)
    source_path = workspace / 'artifacts/source.json'
    source = json.loads(source_path.read_text())
    source.update(validationOnly=validation, weeklyReleaseGate=gate)
    source_path.write_text(json.dumps(source, indent=2) + '\n')
    number = os.environ['BUILD_NUMBER']
    if not re.fullmatch(r'[1-9]\d*', number):
        raise ValueError('Invalid Jenkins build number')
    info = dict(selected, sourceRevision=revision, validationOnly=validation,
                releaseId=f"release-{selected['versionName']}-{selected['versionCode']}-build-{number}")
    (workspace / 'run-info.json').write_text(json.dumps(info, indent=2) + '\n')
    (workspace / 'artifacts/weekly-gate.json').write_text(json.dumps(gate, indent=2) + '\n')
    print(json.dumps(info))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=['prepare', 'stage', 'smoke', 'seal', 'publish'])
    parser.add_argument('--workspace', type=Path)
    parser.add_argument('--validation-qa-release-id', default='')
    parser.add_argument('--release-id')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    repository = Path(os.environ['SOURCE_REPOSITORY'])
    ci_home = Path(os.environ['EXPRESSIVE_CI_HOME'])
    if args.operation == 'prepare':
        prepare(args, repository, ci_home)
    elif args.operation == 'publish':
        guard(repository)
        if not re.fullmatch(r'release-\d+\.\d+\.\d+-[1-9]\d*-build-[1-9]\d*', args.release_id or ''):
            raise ValueError('Invalid stable release ID')
        run('python3', repository / 'ci/pipeline.py', 'publish', '--release-id', args.release_id,
            '--channel', 'release', '--output', args.output)
    else:
        info = json.loads((args.workspace / 'run-info.json').read_text())
        source = args.workspace / 'source'
        if args.operation == 'seal':
            run('python3', source / 'ci/finalize_qa.py', '--artifact-dir', args.workspace / 'artifacts',
                '--source-dir', source, '--release-dir', ci_home / 'releases' / info['releaseId'],
                '--build-url', os.environ['BUILD_URL'])
            (args.workspace / 'artifacts/sealed-release.json').write_text(json.dumps(info, indent=2) + '\n')
        else:
            run('python3', source / 'ci/pipeline.py', args.operation, '--workspace', args.workspace,
                '--channel', 'release', '--version-name', info['versionName'],
                '--version-code', info['versionCode'], '--source-revision', info['sourceRevision'])


if __name__ == '__main__':
    main()

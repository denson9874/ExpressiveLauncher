#!/usr/bin/env python3
"""Create a clean, immutable-source workspace and fetch the currently shipped QA baseline."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.parse
import urllib.request

FEED = 'https://drive.usercontent.google.com/download?id=1_A529DlPEMzwizq-6j3fpMBElGugY-_J&export=download'


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()


def download(url, destination):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname not in {'drive.usercontent.google.com', 'drive.google.com'}:
        raise ValueError('Baseline must use the existing HTTPS Google Drive distribution channel')
    with urllib.request.urlopen(url, timeout=120) as response, destination.open('wb') as output:
        while chunk := response.read(1024 * 1024):
            output.write(chunk)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repository', required=True, type=Path)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--workspace', required=True, type=Path)
    args = parser.parse_args()
    revision = args.revision
    if not re.fullmatch('[0-9a-f]{40}', revision):
        raise SystemExit('SOURCE_REVISION must be a full recorded commit SHA')
    subprocess.run(['git', '-C', str(args.repository), 'merge-base', '--is-ancestor',
                    revision, 'codex/pixel-parity'], check=True)
    source = args.workspace / 'source'
    if source.exists():
        raise SystemExit('Build workspace already exists; use a new Jenkins build number')
    args.workspace.mkdir(parents=True, exist_ok=True)
    git(args.repository, 'worktree', 'add', '--detach', str(source), revision)
    module = 'platform_frameworks_libs_systemui'
    module_sha = git(source, 'rev-parse', 'HEAD:' + module)
    git(args.repository / module, 'worktree', 'add', '--detach', str(source / module), module_sha)
    if git(source, 'status', '--porcelain'):
        raise SystemExit('Prepared source is not clean')
    output = args.workspace / 'artifacts'; output.mkdir()
    download(FEED, output / 'baseline-feed.json')
    feed = json.loads((output / 'baseline-feed.json').read_text())
    if feed.get('channel') != 'qa' or feed.get('applicationId') != 'dev.launcher.expressive.l3.debug':
        # Current updater manifest uses packageName; accept that exact schema spelling too.
        if feed.get('channel') != 'qa' or feed.get('packageName') != 'dev.launcher.expressive.l3.debug':
            raise SystemExit('The delivered baseline is outside the QA channel')
    url = feed.get('apkUrl') or feed.get('downloadUrl')
    if not url:
        raise SystemExit('QA baseline manifest has no supported APK download URL')
    download(url, output / 'baseline.apk')
    raw = (output / 'baseline.apk').read_bytes()
    if hashlib.sha256(raw).hexdigest() != feed['sha256']:
        raise SystemExit('Downloaded baseline digest disagrees with the live feed')
    expected_size = feed.get('sizeBytes') or feed.get('size')
    if expected_size is not None and len(raw) != int(expected_size):
        raise SystemExit('Downloaded baseline size disagrees with the live feed')
    (output / 'source.json').write_text(json.dumps({'sourceRevision': revision,
        'submoduleRevision': module_sha, 'baselineFeed': FEED}, indent=2) + '\n')
    print(f'Prepared source {revision} and delivered QA baseline {feed["versionName"]}')


if __name__ == '__main__':
    main()

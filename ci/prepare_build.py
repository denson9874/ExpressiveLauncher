#!/usr/bin/env python3
"""Create a clean, immutable-source workspace and fetch the currently shipped QA baseline."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.parse
import urllib.request

REPOSITORY = 'denson9874/ExpressiveLauncher'
FEED = f'https://raw.githubusercontent.com/{REPOSITORY}/updates/qa/latest.json'
QA_PACKAGE = 'dev.launcher.expressive.l3.debug'


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()


def validate_download_url(url, redirect=False):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError('Baseline must use HTTPS without embedded credentials')
    if url == FEED:
        return
    if (parsed.hostname == 'github.com' and not parsed.query and not parsed.fragment and
            re.fullmatch('/' + re.escape(REPOSITORY) + r'/releases/download/qa-v\d+\.\d+\.\d+-\d+/[^/]+\.apk', parsed.path)):
        return
    if redirect and parsed.hostname in {'release-assets.githubusercontent.com', 'objects.githubusercontent.com'}:
        return
    raise ValueError('Baseline must use the owned GitHub QA distribution channel')


class GithubRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_download_url(newurl, redirect=True)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url, destination):
    validate_download_url(url)
    opener = urllib.request.build_opener(GithubRedirects())
    with opener.open(url, timeout=120) as response, destination.open('wb') as output:
        while chunk := response.read(1024 * 1024):
            output.write(chunk)


def retained_baseline(ci_home, release_id, output):
    """An explicit sealed baseline bootstraps a new channel without a network fallback."""
    if not re.fullmatch(r'qa-\d+\.\d+\.\d+-\d+-build-\d+', release_id):
        raise ValueError('Invalid baseline release ID')
    release = ci_home / 'releases' / release_id
    seal = json.loads((release / 'seal.json').read_text())
    metadata = json.loads((release / 'metadata.json').read_text())
    qa = json.loads((release / 'qa-result.json').read_text())
    if seal.get('complete') is not True or qa.get('passed') is not True:
        raise ValueError('Baseline must be a complete sealed QA pass')
    if (metadata.get('sourceRevision') != seal.get('sourceRevision') or
            qa.get('sourceRevision') != seal.get('sourceRevision')):
        raise ValueError('Baseline provenance differs from its seal')
    if (metadata.get('packageName') != QA_PACKAGE or metadata.get('channel') != 'qa' or
            metadata.get('signatureVerified') is not True or metadata.get('debuggable') is not False):
        raise ValueError('Baseline must be the signed non-debuggable QA package')
    name = metadata['fileName']
    if Path(name).name != name or not name.endswith('.apk'):
        raise ValueError('Invalid baseline APK filename')
    for name_to_hash, key in [('metadata.json', 'metadataSha256'), ('qa-result.json', 'qaResultSha256'),
                              ('QA-report.md', 'reportSha256'), (name, 'sha256')]:
        if hashlib.sha256((release / name_to_hash).read_bytes()).hexdigest() != seal.get(key):
            raise ValueError('Baseline sealed bytes changed: ' + name_to_hash)
    if qa.get('sha256') != metadata['sha256'] or metadata['sha256'] != seal['sha256']:
        raise ValueError('Baseline device QA belongs to different APK bytes')
    if (release / name).stat().st_size != metadata['sizeBytes']:
        raise ValueError('Baseline APK differs from metadata')
    shutil.copyfile(release / name, output / 'baseline.apk')
    (output / 'baseline-feed.json').write_text(json.dumps(metadata, indent=2) + '\n')
    return metadata


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repository', required=True, type=Path)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--workspace', required=True, type=Path)
    parser.add_argument('--baseline-release-id', default='', help='Explicit sealed QA baseline for channel bootstrap')
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
    if args.baseline_release_id:
        feed = retained_baseline(Path(os.environ['EXPRESSIVE_CI_HOME']), args.baseline_release_id, output)
        baseline_source = 'sealed:' + args.baseline_release_id
    else:
        download(FEED, output / 'baseline-feed.json')
        feed = json.loads((output / 'baseline-feed.json').read_text())
        if feed.get('schemaVersion') != 1 or feed.get('channel') != 'qa' or feed.get('packageName') != QA_PACKAGE:
            raise SystemExit('The delivered baseline is outside the GitHub QA channel')
        download(feed['apkUrl'], output / 'baseline.apk')
        baseline_source = FEED
    raw = (output / 'baseline.apk').read_bytes()
    if hashlib.sha256(raw).hexdigest() != feed['sha256']:
        raise SystemExit('Downloaded baseline digest disagrees with the live feed')
    if len(raw) != int(feed['sizeBytes']):
        raise SystemExit('Downloaded baseline size disagrees with the live feed')
    (output / 'source.json').write_text(json.dumps({'sourceRevision': revision,
        'submoduleRevision': module_sha, 'baselineFeed': baseline_source}, indent=2) + '\n')
    print(f'Prepared source {revision} and delivered QA baseline {feed["versionName"]}')


if __name__ == '__main__':
    main()

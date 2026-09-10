#!/usr/bin/env python3
"""Prepare immutable source and its channel's baseline, or an explicit first stable install."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.parse
import urllib.error
import urllib.request

REPOSITORY = 'denson9874/ExpressiveLauncher'
FEED = f'https://raw.githubusercontent.com/{REPOSITORY}/updates/qa/latest.json'
QA_PACKAGE = 'dev.launcher.expressive.l3.debug'
RELEASE_PACKAGE = 'dev.launcher.expressive.l3'
RELEASE_FEED = f'https://raw.githubusercontent.com/{REPOSITORY}/updates/release/latest.json'
EXPECTED_CERTIFICATE_SHA256 = 'c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2'


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()


def validate_download_url(url, redirect=False, channel='qa'):
    if channel not in ('qa', 'release'):
        raise ValueError('Unknown distribution channel')
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError('Baseline must use HTTPS without embedded credentials')
    if url == (FEED if channel == 'qa' else RELEASE_FEED):
        return
    tag = 'qa-v' if channel == 'qa' else 'v'
    if (parsed.hostname == 'github.com' and not parsed.query and not parsed.fragment and
            re.fullmatch('/' + re.escape(REPOSITORY) + '/releases/download/' + tag + r'\d+\.\d+\.\d+-\d+/[^/]+\.apk', parsed.path)):
        return
    if redirect and parsed.hostname in {'release-assets.githubusercontent.com', 'objects.githubusercontent.com'}:
        return
    raise ValueError('Baseline must use the owned GitHub ' + channel + ' distribution channel')


class GithubRedirects(urllib.request.HTTPRedirectHandler):
    def __init__(self, channel='qa'):
        super().__init__()
        self.channel = channel

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_download_url(newurl, redirect=True, channel=self.channel)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url, destination, channel='qa'):
    validate_download_url(url, channel=channel)
    opener = urllib.request.build_opener(GithubRedirects(channel))
    with opener.open(url, timeout=120) as response, destination.open('wb') as output:
        while chunk := response.read(1024 * 1024):
            output.write(chunk)


def retained_baseline(ci_home, release_id, output=None):
    """Verify a retained QA seal; copy it only when requested as a QA baseline."""
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
    if output is not None:
        shutil.copyfile(release / name, output / 'baseline.apk')
        (output / 'baseline-feed.json').write_text(json.dumps(metadata, indent=2) + '\n')
    return metadata


def retain_qa_provenance(ci_home, release_id, output, revision, validation_only=False):
    metadata = retained_baseline(ci_home, release_id)
    if metadata.get('certificateSha256') != EXPECTED_CERTIFICATE_SHA256:
        raise ValueError('Selected QA release does not have the durable Expressive certificate')
    if metadata.get('sourceRevision') != revision and not validation_only:
        raise ValueError('Stable source must exactly match the selected sealed QA source')
    if (not re.fullmatch(r'\d+\.\d+\.\d+', str(metadata.get('versionName', ''))) or
            type(metadata.get('versionCode')) is not int or metadata['versionCode'] <= 0):
        raise ValueError('Selected QA version is invalid')
    release = ci_home / 'releases' / release_id
    seal_bytes = (release / 'seal.json').read_bytes()
    provenance = {'releaseId': release_id, 'sealSha256': hashlib.sha256(seal_bytes).hexdigest(),
                  'sourceRevision': metadata['sourceRevision'], 'versionName': metadata['versionName'],
                  'versionCode': metadata['versionCode'], 'sha256': metadata['sha256']}
    (output / 'qa-metadata.json').write_bytes((release / 'metadata.json').read_bytes())
    (output / 'qa-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    return provenance


def authenticated_github_json(endpoint, paginate=False):
    """Use gh's existing authentication without extracting or printing credentials."""
    command = ['gh', 'api', '--hostname', 'github.com', '--method', 'GET']
    if paginate:
        command.extend(['--paginate', '--slurp'])
    command.append(endpoint)
    try:
        result = subprocess.run(command, capture_output=True, text=True, stdin=subprocess.DEVNULL,
                                timeout=120, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError('Cannot establish authenticated stable distribution history') from error
    if result.returncode:
        # gh stderr is deliberately not copied into build logs.
        raise ValueError(f'Authenticated stable history request failed (exit {result.returncode})')
    try:
        return json.loads(result.stdout)
    except (ValueError, TypeError) as error:
        raise ValueError('Authenticated stable history response is not valid JSON') from error


def verify_first_stable_history():
    """A missing public feed is first-install evidence only if no stable history exists."""
    commits_endpoint = f'/repos/{REPOSITORY}/commits?sha=updates&path=release/latest.json&per_page=1'
    commits = authenticated_github_json(commits_endpoint)
    if not isinstance(commits, list):
        raise ValueError('Stable feed history response must be a commit list')
    if commits:
        raise ValueError('Stable feed has publication history; restore its delivered baseline instead of bootstrapping')
    releases_endpoint = f'/repos/{REPOSITORY}/releases?per_page=100'
    pages = authenticated_github_json(releases_endpoint, paginate=True)
    if not isinstance(pages, list) or not pages or any(not isinstance(page, list) for page in pages):
        raise ValueError('Stable release history response must contain every paginated release list')
    count = 0
    for page in pages:
        for release in page:
            if (not isinstance(release, dict) or type(release.get('draft')) is not bool or
                    type(release.get('prerelease')) is not bool or
                    not isinstance(release.get('tag_name'), str) or not release['tag_name'] or
                    (not release['draft'] and (not isinstance(release.get('published_at'), str) or
                                              not release['published_at']))):
                raise ValueError('Stable release history contains an incomplete release identity')
            if not release['draft'] and (not release['prerelease'] or
                                        re.fullmatch(r'v\d+\.\d+\.\d+-\d+', release['tag_name'])):
                raise ValueError('A published stable release already exists; first-stable bootstrap is unavailable')
            count += 1
    return {'schemaVersion': 1, 'checkedAt': datetime.now(timezone.utc).isoformat(),
            'repository': REPOSITORY, 'feedPath': 'release/latest.json', 'branch': 'updates',
            'authenticated': True, 'feedHistoryCount': 0, 'publishedStableReleaseCount': 0,
            'releasesInspected': count}


def delivered_baseline(output, channel='qa', bootstrap_stable=False):
    if bootstrap_stable and channel != 'release':
        raise ValueError('Stable bootstrap cannot be used for QA')
    url = FEED if channel == 'qa' else RELEASE_FEED
    try:
        download(url, output / 'baseline-feed.json', channel=channel)
    except urllib.error.HTTPError as error:
        if channel == 'release' and bootstrap_stable and error.code == 404 and error.url == url:
            error.close()
            history = verify_first_stable_history()
            (output / 'stable-bootstrap-history.json').write_text(json.dumps(history, indent=2) + '\n')
            (output / 'baseline-feed.json').unlink(missing_ok=True)
            return None, url
        raise
    if bootstrap_stable:
        raise ValueError('Stable bootstrap requires an absent public stable feed (HTTP 404)')
    feed = json.loads((output / 'baseline-feed.json').read_text())
    package = QA_PACKAGE if channel == 'qa' else RELEASE_PACKAGE
    if feed.get('schemaVersion') != 1 or feed.get('channel') != channel or feed.get('packageName') != package:
        raise ValueError('The delivered baseline is outside the GitHub ' + channel + ' channel')
    download(feed['apkUrl'], output / 'baseline.apk', channel=channel)
    return feed, url


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--repository', required=True, type=Path)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--workspace', required=True, type=Path)
    parser.add_argument('--baseline-release-id', default='', help='Explicit sealed QA baseline for channel bootstrap')
    parser.add_argument('--channel', choices=('qa', 'release'), default='qa')
    parser.add_argument('--bootstrap-stable', action='store_true')
    parser.add_argument('--qa-release-id', default='')
    parser.add_argument('--validation-only', action='store_true')
    args = parser.parse_args(argv)
    if args.channel == 'qa' and (args.bootstrap_stable or args.qa_release_id or args.validation_only):
        parser.error('Stable preparation flags cannot be used for QA')
    if args.channel == 'release' and (not args.qa_release_id or args.baseline_release_id):
        parser.error('Stable preparation requires --qa-release-id and only a public stable baseline')
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
    provenance = None
    if args.channel == 'release':
        provenance = retain_qa_provenance(Path(os.environ['EXPRESSIVE_CI_HOME']), args.qa_release_id,
                                          output, revision, args.validation_only)
    if args.baseline_release_id:
        feed = retained_baseline(Path(os.environ['EXPRESSIVE_CI_HOME']), args.baseline_release_id, output)
        baseline_source = 'sealed:' + args.baseline_release_id
    else:
        feed, baseline_source = delivered_baseline(output, args.channel, args.bootstrap_stable)
    if feed is not None:
        raw = (output / 'baseline.apk').read_bytes()
        if hashlib.sha256(raw).hexdigest() != feed['sha256']:
            raise SystemExit('Downloaded baseline digest disagrees with the live feed')
        if len(raw) != int(feed['sizeBytes']):
            raise SystemExit('Downloaded baseline size disagrees with the live feed')
    source_metadata = {'sourceRevision': revision, 'submoduleRevision': module_sha, 'baselineFeed': baseline_source}
    if args.channel == 'release':
        source_metadata.update(channel='release', baselineMode='first-stable-install' if feed is None else 'quiesced-upgrade',
                               stableBootstrap=feed is None, validationOnly=args.validation_only, qaProvenance=provenance)
    (output / 'source.json').write_text(json.dumps(source_metadata, indent=2) + '\n')
    print(f'Prepared source {revision} and {args.channel} baseline: ' + ('first stable install' if feed is None else feed['versionName']))


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Small build-specific adapters; Jenkins owns execution, state, retries, and archives."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=['stage', 'smoke', 'publish', 'bridge-legacy-qa'])
    parser.add_argument('--workspace', type=Path)
    parser.add_argument('--version-name')
    parser.add_argument('--version-code')
    parser.add_argument('--source-revision')
    parser.add_argument('--release-id')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--publication-receipt', type=Path)
    parser.add_argument('--channel', choices=['qa', 'release'], default='qa')
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    if args.operation == 'stage':
        source = args.workspace / 'source'
        output = args.workspace / 'artifacts'
        apks = list((source / 'build/outputs/apk/lawnWithQuickstepExpressive' / args.channel).glob('*.apk'))
        if len(apks) != 1: raise SystemExit('Expected exactly one APK for the selected channel')
        label = 'QA' if args.channel == 'qa' else 'Release'
        name = f'ExpressiveLauncherL3-{args.version_name}-Android17-QPR2-Beta4-Jenkins-{label}-release-signed.apk'
        apk = output / name
        shutil.copyfile(apks[0], apk)
        command = ['python3', str(here / 'verify_qa.py'), '--apk', str(apk),
            '--version-name', args.version_name,
            '--version-code', args.version_code, '--build-tools', str(Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0'),
            '--output', str(output / 'metadata.json')]
        if args.channel == 'release':
            source_info = json.loads((output / 'source.json').read_text())
            command += ['--channel', 'release', '--qa-metadata', str(output / 'qa-metadata.json'),
                        '--source-revision', source_info['sourceRevision']]
            if source_info.get('baselineMode') == 'first-stable-install': command.append('--bootstrap-stable')
            else: command += ['--baseline-apk', str(output / 'baseline.apk')]
            if source_info.get('validationOnly') is True: command.append('--validation-only')
        else:
            command += ['--baseline-apk', str(output / 'baseline.apk')]
        subprocess.run(command, check=True)
    elif args.operation == 'smoke':
        output = args.workspace / 'artifacts'
        metadata = json.loads((output / 'metadata.json').read_text())
        evidence = output / 'device-qa'
        command = ['python3', str(here / 'smoke_qa.py'), '--apk', str(output / metadata['fileName']),
            '--output-dir', str(evidence), '--source-revision', args.source_revision]
        if args.channel == 'release':
            command += ['--channel', 'release']
            if metadata.get('stableBootstrap') is True: command.append('--bootstrap-stable')
            else: command += ['--baseline-apk', str(output / 'baseline.apk')]
        else:
            command += ['--baseline-apk', str(output / 'baseline.apk')]
        subprocess.run(command, check=True)
        shutil.copyfile(evidence / 'qa-result.json', output / 'qa-result.json')
    else:
        if not re.fullmatch(args.channel + r'-\d+\.\d+\.\d+-\d+-build-\d+', args.release_id or ''):
            raise SystemExit('Invalid sealed release ID')
        release = Path(os.environ['EXPRESSIVE_CI_HOME']) / 'releases' / args.release_id
        metadata = json.loads((release / 'metadata.json').read_text())
        if metadata.get('channel') != args.channel:
            raise SystemExit('Sealed candidate belongs to a different publication channel')
        if args.channel == 'release' and args.operation != 'publish':
            raise SystemExit('Legacy migration is restricted to QA')
        if args.channel == 'release':
            from weekly_release import guard, live_gate, require_publish_selection
            repository = Path(os.environ['SOURCE_REPOSITORY'])
            guard(repository)
            gate = live_gate(repository, args.output.with_suffix('.pipeline-gate.json'))
            require_publish_selection(metadata, gate)
        revision = metadata['sourceRevision']
        if not re.fullmatch('[0-9a-f]{40}', revision): raise SystemExit('Invalid recorded source revision')
        # Reuse publication/migration code from the candidate's exact source commit.
        repository = Path(os.environ['SOURCE_REPOSITORY'])
        entry_name = 'publish_qa.py' if args.operation == 'publish' else 'migrate_legacy_qa_feed.py'
        if args.operation == 'bridge-legacy-qa':
            if args.publication_receipt is None:
                raise SystemExit('Legacy migration requires the successful GitHub publication receipt')
            published = json.loads(args.publication_receipt.read_text())
            if (published.get('provider') != 'github' or published.get('status') != 'released' or
                    published.get('sourceRevision') != revision or published.get('sha256') != metadata['sha256']):
                raise SystemExit('Legacy migration requires this exact sealed candidate to be published on GitHub')
        script = subprocess.check_output(['git', '-C', str(repository), 'show', revision + ':ci/' + entry_name])
        with tempfile.TemporaryDirectory(prefix='expressive-publish-') as temp:
            entry = Path(temp) / entry_name; entry.write_bytes(script)
            command = ['python3', str(entry), '--output', str(args.output)]
            if args.operation == 'publish':
                # Old Drive publishers reject this flag before performing any upload.
                command += ['--artifact-dir', str(release), '--expected-provider', 'github']
                if args.channel == 'release': command += ['--channel', 'release']
                promotion = 'PROMOTE_QA_FEED' if args.channel == 'qa' else 'PROMOTE_RELEASE_FEED'
                if os.environ.get(promotion, '').lower() == 'true': command.append('--promote')
            else:
                command += ['--publication-receipt', str(args.publication_receipt)]
            subprocess.run(command, check=True)
        receipt = json.loads(args.output.read_text())
        if args.operation == 'publish':
            if receipt.get('provider') != 'github':
                raise SystemExit('Publication receipt is not from the required GitHub provider')
            receipt['publisherSourceRevision'] = revision
            receipt['publisherSha256'] = hashlib.sha256(script).hexdigest()
        else:
            if receipt.get('provider') != 'legacy-drive-qa-bridge' or receipt.get('status') not in ('migrated', 'unchanged'):
                raise SystemExit('Legacy migration did not return a verified completion receipt')
            receipt['migrationSourceRevision'] = revision
            receipt['migrationSha256'] = hashlib.sha256(script).hexdigest()
        args.output.write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__':
    main()

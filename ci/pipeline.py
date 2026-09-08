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
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    if args.operation == 'stage':
        source = args.workspace / 'source'
        output = args.workspace / 'artifacts'
        apks = list((source / 'build/outputs/apk/lawnWithQuickstepExpressive/qa').glob('*.apk'))
        if len(apks) != 1: raise SystemExit('Expected exactly one Qa APK')
        name = f'ExpressiveLauncherL3-{args.version_name}-Android17-QPR2-Beta4-Jenkins-QA-release-signed.apk'
        apk = output / name
        shutil.copyfile(apks[0], apk)
        subprocess.run(['python3', str(here / 'verify_qa.py'), '--apk', str(apk),
            '--baseline-apk', str(output / 'baseline.apk'), '--version-name', args.version_name,
            '--version-code', args.version_code, '--build-tools', str(Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0'),
            '--output', str(output / 'metadata.json')], check=True)
    elif args.operation == 'smoke':
        output = args.workspace / 'artifacts'
        metadata = json.loads((output / 'metadata.json').read_text())
        evidence = output / 'device-qa'
        subprocess.run(['python3', str(here / 'smoke_qa.py'), '--apk', str(output / metadata['fileName']),
            '--baseline-apk', str(output / 'baseline.apk'), '--output-dir', str(evidence),
            '--source-revision', args.source_revision], check=True)
        shutil.copyfile(evidence / 'qa-result.json', output / 'qa-result.json')
    else:
        if not re.fullmatch(r'qa-\d+\.\d+\.\d+-\d+-build-\d+', args.release_id or ''):
            raise SystemExit('Invalid sealed release ID')
        release = Path(os.environ['EXPRESSIVE_CI_HOME']) / 'releases' / args.release_id
        metadata = json.loads((release / 'metadata.json').read_text())
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
                if os.environ.get('PROMOTE_QA_FEED', '').lower() == 'true': command.append('--promote')
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

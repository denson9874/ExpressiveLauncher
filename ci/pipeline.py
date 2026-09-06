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
    parser.add_argument('operation', choices=['stage', 'smoke', 'publish'])
    parser.add_argument('--workspace', type=Path)
    parser.add_argument('--version-name')
    parser.add_argument('--version-code')
    parser.add_argument('--source-revision')
    parser.add_argument('--release-id')
    parser.add_argument('--output', type=Path)
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
        # Reuse the publisher checked into the candidate's exact source commit.
        repository = Path(os.environ['SOURCE_REPOSITORY'])
        script = subprocess.check_output(['git', '-C', str(repository), 'show', revision + ':ci/publish_qa.py'])
        with tempfile.TemporaryDirectory(prefix='expressive-publish-') as temp:
            entry = Path(temp) / 'publish_qa.py'; entry.write_bytes(script)
            command = ['python3', str(entry), '--artifact-dir', str(release), '--output', str(args.output)]
            if os.environ.get('PROMOTE_QA_FEED', '').lower() == 'true': command.append('--promote')
            subprocess.run(command, check=True)
        receipt = json.loads(args.output.read_text())
        receipt['publisherSourceRevision'] = revision
        receipt['publisherSha256'] = hashlib.sha256(script).hexdigest()
        args.output.write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__':
    main()

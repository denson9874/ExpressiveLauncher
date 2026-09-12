#!/usr/bin/env python3
"""Seal the tested artifact and provenance for independently retryable publication."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import xml.etree.ElementTree as ET


def validate_provenance(metadata, source, qa):
    channel = metadata.get('channel')
    if channel not in ('qa', 'release'):
        raise ValueError('Unknown artifact channel')
    if qa.get('channel', channel) != channel:
        raise ValueError('Device QA belongs to a different channel')
    if qa.get('packageName', metadata['packageName']) != metadata['packageName']:
        raise ValueError('Device QA belongs to a different package')
    if metadata.get('sourceRevision', source['sourceRevision']) != source['sourceRevision']:
        raise ValueError('Verified APK belongs to a different source revision')
    if channel == 'release':
        bootstrap = metadata.get('stableBootstrap')
        mode = 'first-stable-install' if bootstrap else 'quiesced-upgrade'
        if type(bootstrap) is not bool or metadata.get('baselineMode') != mode:
            raise ValueError('Stable baseline mode is missing or inconsistent')
        for record in (source, qa):
            if (record.get('channel') != channel or record.get('baselineMode') != mode or
                    record.get('stableBootstrap') is not bootstrap):
                raise ValueError('Stable source and device QA must agree on channel and baseline mode')
        if qa.get('packageName') != 'dev.launcher.expressive.l3' or metadata['packageName'] != qa['packageName']:
            raise ValueError('Stable QA requires the release package')
        if bootstrap and (metadata.get('baseline') is not None or qa.get('baseline') is not None):
            raise ValueError('First stable install cannot claim a prior baseline')
        validation_only = source.get('validationOnly')
        if type(validation_only) is not bool or metadata.get('validationOnly') is not validation_only:
            raise ValueError('Stable validation-only status must agree with verified metadata')
        metadata['validationOnly'] = validation_only
        if 'weeklyReleaseGate' in source:
            if not isinstance(source['weeklyReleaseGate'], dict):
                raise ValueError('Weekly release gate must be an evidence object')
            metadata['weeklyReleaseGate'] = source['weeklyReleaseGate']
        if not isinstance(source.get('qaProvenance'), dict):
            raise ValueError('Selected QA provenance is required for stable sealing')
        selected = metadata.get('selectedQa', {})
        if any(selected.get(key) != source['qaProvenance'].get(key)
               for key in ('sourceRevision', 'versionName', 'versionCode', 'sha256')):
            raise ValueError('Selected QA provenance differs from APK verification')
        metadata['qaProvenance'] = source['qaProvenance']


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--artifact-dir', type=Path, required=True)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--release-dir', type=Path, required=True)
    parser.add_argument('--build-url', required=True)
    args = parser.parse_args(argv)
    root = args.artifact_dir
    metadata = json.loads((root / 'metadata.json').read_text())
    source = json.loads((root / 'source.json').read_text())
    qa = json.loads((root / 'qa-result.json').read_text())
    sha = hashlib.sha256((root / metadata['fileName']).read_bytes()).hexdigest()
    if qa.get('passed') is not True or qa.get('sha256') != sha or sha != metadata['sha256']:
        raise SystemExit('Device QA is not a pass for these exact APK bytes')
    if qa.get('sourceRevision') != source['sourceRevision']:
        raise SystemExit('Device QA belongs to a different source revision')
    try:
        validate_provenance(metadata, source, qa)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    results = list((args.source_dir / 'build/test-results/testLawnWithQuickstepExpressiveDebugUnitTest').glob('TEST-*.xml'))
    totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for path in results:
        suite = ET.parse(path).getroot()
        for key in totals: totals[key] += int(suite.get(key, 0))
    if not results or totals['tests'] == 0 or totals['failures'] or totals['errors'] or totals['skipped']:
        raise SystemExit('A complete passing, unskipped unit test suite is required')
    metadata.update(sourceRevision=source['sourceRevision'], buildUrl=args.build_url, unitTests=totals)
    (root / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    channel_label = 'QA' if metadata['channel'] == 'qa' else 'stable'
    bootstrap = metadata.get('stableBootstrap') is True
    packaging_result = ('Signature, non-debuggable packaging, and selected QA version/certificate checks passed. '
                        'No prior stable release exists; no prior-version upgrade is claimed.' if bootstrap else
                        'Signature, non-debuggable packaging, version progression, and prior-certificate checks passed.')
    device_scope = ('The first stable installation was tested on an isolated emulator, including a same-version '
                    'reinstall and preference retention. This is not an upgrade from a previously shipped stable version.'
                    if bootstrap else 'The automated upgrade uses the documented quiesced ADB install sequence on an isolated emulator.')
    validation_notice = ('\nThis is a private pipeline-validation artifact and is not eligible for publication.\n'
                         if metadata.get('validationOnly') else '')
    if metadata.get('weeklyReleaseGate', {}).get('status') == 'manual-selection':
        validation_notice = ('\nThis build uses an explicitly selected QA version and current committed source. '
                             'The legacy validationOnly marker records that source selection; green stable '
                             'publication requires a separate exact-build authorization and receipt.\n')
    report = f'''# Expressive Launcher {metadata['versionName']} — Jenkins {channel_label} release

This report describes the exact APK built and tested by Jenkins. Publication status is recorded separately in the publication receipt.
{validation_notice}

- APK: `{metadata['fileName']}`
- Version code: {metadata['versionCode']}
- Package/channel: `{metadata['packageName']}` / {channel_label}
- Source revision: `{source['sourceRevision']}`
- Jenkins run: {args.build_url}
- Bytes: {metadata['sizeBytes']}
- SHA-256: `{sha}`
- Certificate SHA-256: `{metadata['certificateSha256']}`
- Unit tests: {totals['tests']} passed, {totals['failures']} failures, {totals['errors']} errors, {totals['skipped']} skipped.
- {packaging_result}
- Automated device result: passed. The attached qa-result.json and evidence describe the observed flows and guest identity.

{device_scope} It does not establish a live updater download or system-installer result; those are recorded separately after publication. The Android foreground replacement limitation remains documented in docs/DIRECT_DISTRIBUTION.md.
'''
    (root / 'QA-report.md').write_text(report)
    qa['reportSha256'] = hashlib.sha256(report.encode()).hexdigest()
    (root / 'qa-result.json').write_text(json.dumps(qa, indent=2) + '\n')
    shutil.copytree(args.source_dir / 'build/test-results/testLawnWithQuickstepExpressiveDebugUnitTest', root / 'junit')
    mapping_variant = 'Qa' if metadata['channel'] == 'qa' else 'Release'
    mapping = args.source_dir / ('build/outputs/mapping/lawnWithQuickstepExpressive' + mapping_variant)
    if mapping.exists(): shutil.copytree(mapping, root / 'mapping')
    if args.release_dir.exists(): raise SystemExit('A sealed release directory already exists; it will not be overwritten')
    args.release_dir.parent.mkdir(parents=True, exist_ok=True)
    pending = Path(tempfile.mkdtemp(prefix=args.release_dir.name + '.preparing-', dir=args.release_dir.parent))
    shutil.copytree(root, pending, dirs_exist_ok=True)
    seal = {'schemaVersion': 1, 'complete': True, 'sourceRevision': source['sourceRevision'], 'sha256': sha}
    for name, key in [('metadata.json', 'metadataSha256'), ('qa-result.json', 'qaResultSha256'), ('QA-report.md', 'reportSha256')]:
        seal[key] = hashlib.sha256((pending / name).read_bytes()).hexdigest()
    (pending / 'seal.json').write_text(json.dumps(seal, indent=2) + '\n')
    pending.rename(args.release_dir)
    print(f'Sealed {metadata["versionName"]} at {args.release_dir}')


if __name__ == '__main__':
    main()

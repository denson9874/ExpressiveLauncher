#!/usr/bin/env python3
"""Seal the tested artifact and provenance for independently retryable publication."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--artifact-dir', type=Path, required=True)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--release-dir', type=Path, required=True)
    parser.add_argument('--build-url', required=True)
    args = parser.parse_args()
    root = args.artifact_dir
    metadata = json.loads((root / 'metadata.json').read_text())
    source = json.loads((root / 'source.json').read_text())
    qa = json.loads((root / 'qa-result.json').read_text())
    sha = hashlib.sha256((root / metadata['fileName']).read_bytes()).hexdigest()
    if qa.get('passed') is not True or qa.get('sha256') != sha or sha != metadata['sha256']:
        raise SystemExit('Device QA is not a pass for these exact APK bytes')
    if qa.get('sourceRevision') != source['sourceRevision']:
        raise SystemExit('Device QA belongs to a different source revision')
    results = list((args.source_dir / 'build/test-results/testLawnWithQuickstepExpressiveDebugUnitTest').glob('TEST-*.xml'))
    totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for path in results:
        suite = ET.parse(path).getroot()
        for key in totals: totals[key] += int(suite.get(key, 0))
    if not results or totals['tests'] == 0 or totals['failures'] or totals['errors'] or totals['skipped']:
        raise SystemExit('A complete passing, unskipped unit test suite is required')
    metadata.update(sourceRevision=source['sourceRevision'], buildUrl=args.build_url, unitTests=totals)
    (root / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    report = f'''# Expressive Launcher {metadata['versionName']} — Jenkins QA release

This report describes the exact APK built and tested by Jenkins. Publication status is recorded separately in the publication receipt.

- APK: `{metadata['fileName']}`
- Version code: {metadata['versionCode']}
- Package/channel: `{metadata['packageName']}` / QA
- Source revision: `{source['sourceRevision']}`
- Jenkins run: {args.build_url}
- Bytes: {metadata['sizeBytes']}
- SHA-256: `{sha}`
- Certificate SHA-256: `{metadata['certificateSha256']}`
- Unit tests: {totals['tests']} passed, {totals['failures']} failures, {totals['errors']} errors, {totals['skipped']} skipped.
- Signature, non-debuggable packaging, version progression, and prior-certificate checks passed.
- Automated device result: passed. The attached qa-result.json and evidence describe the observed flows and guest identity.

The automated upgrade uses the documented quiesced ADB install sequence on an isolated emulator. It does not establish a live updater download or system-installer result; those are recorded separately after publication. The Android foreground replacement limitation remains documented in docs/DIRECT_DISTRIBUTION.md.
'''
    (root / 'QA-report.md').write_text(report)
    qa['reportSha256'] = hashlib.sha256(report.encode()).hexdigest()
    (root / 'qa-result.json').write_text(json.dumps(qa, indent=2) + '\n')
    shutil.copytree(args.source_dir / 'build/test-results/testLawnWithQuickstepExpressiveDebugUnitTest', root / 'junit')
    mapping = args.source_dir / 'build/outputs/mapping/lawnWithQuickstepExpressiveQa'
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

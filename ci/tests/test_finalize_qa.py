"""Seal-channel and first-stable evidence must remain consistent through finalization."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('finalize_qa', Path(__file__).parents[1] / 'finalize_qa.py')
finalize = importlib.util.module_from_spec(spec)
spec.loader.exec_module(finalize)


class StableSealingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.artifacts = self.root / 'artifacts'
        self.artifacts.mkdir()
        self.source_dir = self.root / 'source'
        tests = self.source_dir / 'build/test-results/testLawnWithQuickstepExpressiveDebugUnitTest'
        tests.mkdir(parents=True)
        (tests / 'TEST-fixture.xml').write_text('<testsuite tests="2" failures="0" errors="0" skipped="0"/>')
        for variant in ('Qa', 'Release'):
            mapping = self.source_dir / ('build/outputs/mapping/lawnWithQuickstepExpressive' + variant)
            mapping.mkdir(parents=True)
            (mapping / 'mapping.txt').write_text(variant)
        apk = b'stable fixture APK'
        sha = hashlib.sha256(apk).hexdigest()
        (self.artifacts / 'stable.apk').write_bytes(apk)
        selected = dict(sourceRevision='a' * 40, versionName='1.0.13', versionCode=14, sha256='b' * 64)
        self.metadata = dict(channel='release', packageName='dev.launcher.expressive.l3',
                             sourceRevision='a' * 40, versionName='1.0.13', versionCode=14,
                             fileName='stable.apk', sha256=sha, sizeBytes=len(apk),
                             certificateSha256='c' * 64, baselineMode='first-stable-install',
                             baseline=None, stableBootstrap=True, validationOnly=False, selectedQa=selected)
        self.source = dict(sourceRevision='a' * 40, channel='release', baselineMode='first-stable-install',
                           stableBootstrap=True, validationOnly=False,
                           qaProvenance={**selected, 'releaseId': 'qa-1.0.13-14-build-7', 'sealSha256': 'd' * 64},
                           weeklyReleaseGate={'status': 'eligible', 'selected': selected})
        self.qa = dict(passed=True, sourceRevision='a' * 40, sha256=sha, channel='release',
                       packageName='dev.launcher.expressive.l3', baselineMode='first-stable-install',
                       baseline=None, stableBootstrap=True)
        self.release = self.root / 'releases/release-1.0.13-14-build-1'

    def run_finalize(self):
        for name, record in [('metadata.json', self.metadata), ('source.json', self.source), ('qa-result.json', self.qa)]:
            (self.artifacts / name).write_text(json.dumps(record))
        with contextlib.redirect_stdout(io.StringIO()):
            finalize.main(['--artifact-dir', str(self.artifacts), '--source-dir', str(self.source_dir),
                           '--release-dir', str(self.release), '--build-url', 'http://localhost/job/stable/1/'])

    def test_bootstrap_seal_preserves_gate_provenance_release_mapping_and_honest_report(self):
        self.run_finalize()
        metadata = json.loads((self.release / 'metadata.json').read_text())
        self.assertEqual(self.source['weeklyReleaseGate'], metadata['weeklyReleaseGate'])
        self.assertEqual(self.source['qaProvenance'], metadata['qaProvenance'])
        self.assertFalse(metadata['validationOnly'])
        self.assertIsNone(metadata['baseline'])
        self.assertEqual('Release', (self.release / 'mapping/mapping.txt').read_text())
        report = (self.release / 'QA-report.md').read_text()
        self.assertIn('Jenkins stable release', report)
        self.assertIn('same-version reinstall', report)
        self.assertIn('no prior-version upgrade is claimed', report)
        self.assertNotIn('prior-certificate checks passed', report)
        seal = json.loads((self.release / 'seal.json').read_text())
        for name, key in [('metadata.json', 'metadataSha256'), ('qa-result.json', 'qaResultSha256'), ('QA-report.md', 'reportSha256')]:
            self.assertEqual(hashlib.sha256((self.release / name).read_bytes()).hexdigest(), seal[key])

    def test_private_validation_marker_survives_seal_and_report(self):
        self.source['validationOnly'] = self.metadata['validationOnly'] = True
        self.run_finalize()
        self.assertTrue(json.loads((self.release / 'metadata.json').read_text())['validationOnly'])
        self.assertIn('not eligible for publication', (self.release / 'QA-report.md').read_text())

    def test_channel_package_mode_and_validation_mismatches_cannot_seal(self):
        cases = [(self.qa, 'channel', 'qa'), (self.qa, 'packageName', 'dev.launcher.expressive.l3.debug'),
                 (self.qa, 'baselineMode', 'quiesced-upgrade'), (self.qa, 'stableBootstrap', False),
                 (self.source, 'validationOnly', True), (self.metadata, 'sourceRevision', 'e' * 40)]
        for record, key, value in cases:
            before = record[key]
            record[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(SystemExit):
                self.run_finalize()
            self.assertFalse(self.release.exists())
            record[key] = before

    def test_selected_qa_mismatch_cannot_seal(self):
        self.source['qaProvenance']['sha256'] = 'e' * 64
        with self.assertRaisesRegex(SystemExit, 'Selected QA provenance differs'):
            self.run_finalize()

    def test_existing_qa_without_new_optional_fields_remains_supported(self):
        self.metadata.update(channel='qa', packageName='dev.launcher.expressive.l3.debug')
        self.qa.pop('channel')
        self.qa.pop('packageName')
        for key in ('baselineMode', 'stableBootstrap', 'validationOnly', 'selectedQa'):
            self.metadata.pop(key, None)
        self.run_finalize()
        self.assertEqual('Qa', (self.release / 'mapping/mapping.txt').read_text())
        self.assertIn('Jenkins QA release', (self.release / 'QA-report.md').read_text())


if __name__ == '__main__':
    unittest.main()

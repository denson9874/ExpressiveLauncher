"""Prevent channel confusion and unverified bootstrap baselines during GitHub migration."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('prepare_build', Path(__file__).parents[1] / 'prepare_build.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class BaselineSourceTests(unittest.TestCase):
    def test_owned_manifest_and_versioned_qa_apk_are_accepted(self):
        prepare.validate_download_url(prepare.FEED)
        prepare.validate_download_url('https://github.com/denson9874/ExpressiveLauncher/releases/download/qa-v1.0.11-12/launcher.apk')

    def test_other_repo_channel_and_legacy_drive_are_rejected(self):
        for url in (
            'https://github.com/another/ExpressiveLauncher/releases/download/qa-v1.0.11-12/launcher.apk',
            'https://github.com/denson9874/ExpressiveLauncher/releases/download/v1.0.11-12/launcher.apk',
            'https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/release/latest.json',
            'https://drive.google.com/example.apk',
            'http://github.com/denson9874/ExpressiveLauncher/releases/download/qa-v1.0.11-12/launcher.apk',
            'https://user:secret@github.com/denson9874/ExpressiveLauncher/releases/download/qa-v1.0.11-12/launcher.apk',
        ):
            with self.subTest(url=url), self.assertRaises(ValueError):
                prepare.validate_download_url(url)

    def test_github_asset_cdn_is_allowed_only_as_https_redirect(self):
        url = 'https://release-assets.githubusercontent.com/github-production-release-asset/123?signature=temporary'
        prepare.validate_download_url(url, redirect=True)
        with self.assertRaises(ValueError):
            prepare.validate_download_url(url)
        with self.assertRaises(ValueError):
            prepare.validate_download_url(url.replace('https:', 'http:'), redirect=True)
        with self.assertRaises(ValueError):
            prepare.validate_download_url('https://unrelated.example/apk', redirect=True)


class SealedBaselineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.release_id = 'qa-1.0.10-11-build-3'
        self.release = self.home / 'releases' / self.release_id
        self.release.mkdir(parents=True)
        self.output = self.home / 'output'
        self.output.mkdir()
        self.apk = b'signed QA fixture'
        self.sha = hashlib.sha256(self.apk).hexdigest()
        self.metadata = dict(fileName='candidate.apk', sourceRevision='a' * 40, sha256=self.sha,
                             sizeBytes=len(self.apk), channel='qa', packageName=prepare.QA_PACKAGE,
                             signatureVerified=True, debuggable=False, versionCode=11, versionName='1.0.10')
        self.qa = dict(passed=True, sourceRevision='a' * 40, sha256=self.sha)
        (self.release / 'candidate.apk').write_bytes(self.apk)
        (self.release / 'QA-report.md').write_text('QA passed')
        self.seal()

    def seal(self):
        (self.release / 'metadata.json').write_text(json.dumps(self.metadata))
        (self.release / 'qa-result.json').write_text(json.dumps(self.qa))
        seal = dict(complete=True, sourceRevision='a' * 40, sha256=self.sha)
        for name, key in [('metadata.json', 'metadataSha256'), ('qa-result.json', 'qaResultSha256'), ('QA-report.md', 'reportSha256')]:
            seal[key] = hashlib.sha256((self.release / name).read_bytes()).hexdigest()
        (self.release / 'seal.json').write_text(json.dumps(seal))

    def test_bootstrap_copies_exact_sealed_bytes_and_records_metadata(self):
        metadata = prepare.retained_baseline(self.home, self.release_id, self.output)
        self.assertEqual((self.output / 'baseline.apk').read_bytes(), self.apk)
        self.assertEqual(json.loads((self.output / 'baseline-feed.json').read_text()), metadata)

    def test_changed_apk_report_or_metadata_blocks_bootstrap(self):
        for name in ('candidate.apk', 'QA-report.md', 'metadata.json'):
            path = self.release / name
            original = path.read_bytes()
            with self.subTest(name=name):
                path.write_bytes(original + b' ')
                with self.assertRaises(ValueError):
                    prepare.retained_baseline(self.home, self.release_id, self.output)
                self.assertFalse((self.output / 'baseline.apk').exists())
                path.write_bytes(original)

    def test_signed_production_and_debug_builds_cannot_bootstrap_qa(self):
        for key, value in [('packageName', 'dev.launcher.expressive.l3'), ('channel', 'release'),
                           ('signatureVerified', False), ('debuggable', True)]:
            with self.subTest(key=key):
                prior = self.metadata[key]
                self.metadata[key] = value
                self.seal()
                with self.assertRaises(ValueError):
                    prepare.retained_baseline(self.home, self.release_id, self.output)
                self.metadata[key] = prior
        self.seal()

    def test_failed_qa_and_foreign_provenance_block_bootstrap(self):
        for change in ({'passed': False}, {'sourceRevision': 'b' * 40}, {'sha256': 'b' * 64}):
            prior = dict(self.qa)
            self.qa.update(change)
            self.seal()
            with self.subTest(change=change), self.assertRaises(ValueError):
                prepare.retained_baseline(self.home, self.release_id, self.output)
            self.qa = prior

    def test_invalid_release_id_cannot_select_another_path(self):
        with self.assertRaises(ValueError):
            prepare.retained_baseline(self.home, '../another', self.output)


if __name__ == '__main__':
    unittest.main()

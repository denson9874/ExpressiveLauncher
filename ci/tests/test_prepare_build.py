"""Prevent channel confusion and unverified bootstrap baselines during GitHub migration."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

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


class StablePreparationTests(unittest.TestCase):
    setUp = SealedBaselineTests.setUp
    seal = SealedBaselineTests.seal

    def test_stable_urls_cannot_cross_into_qa(self):
        prepare.validate_download_url(prepare.RELEASE_FEED, channel='release')
        prepare.validate_download_url('https://github.com/denson9874/ExpressiveLauncher/releases/download/v1.0.13-14/launcher.apk', channel='release')
        for url in (prepare.FEED, 'https://github.com/denson9874/ExpressiveLauncher/releases/download/qa-v1.0.13-14/launcher.apk'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                prepare.validate_download_url(url, channel='release')

    def test_first_stable_requires_explicit_flag_and_exact_public_404(self):
        missing = urllib.error.HTTPError(prepare.RELEASE_FEED, 404, 'Not Found', {}, None)
        self.addCleanup(missing.close)
        with patch.object(prepare, 'download', side_effect=missing):
            self.assertEqual((None, prepare.RELEASE_FEED), prepare.delivered_baseline(self.output, 'release', True))
            with self.assertRaises(urllib.error.HTTPError):
                prepare.delivered_baseline(self.output, 'release')
        self.assertFalse((self.output / 'baseline.apk').exists())
        for error in (urllib.error.HTTPError(prepare.RELEASE_FEED, 403, 'Denied', {}, None),
                      urllib.error.HTTPError(prepare.RELEASE_FEED, 500, 'Error', {}, None),
                      urllib.error.HTTPError(prepare.FEED, 404, 'Not Found', {}, None),
                      urllib.error.URLError('offline')):
            if isinstance(error, urllib.error.HTTPError):
                self.addCleanup(error.close)
            with self.subTest(error=error), patch.object(prepare, 'download', side_effect=error), self.assertRaises(Exception):
                prepare.delivered_baseline(self.output, 'release', True)

    def test_existing_stable_feed_blocks_bootstrap_even_if_empty(self):
        with patch.object(prepare, 'download'), self.assertRaisesRegex(ValueError, 'absent public stable feed'):
            prepare.delivered_baseline(self.output, 'release', True)
        with self.assertRaisesRegex(ValueError, 'cannot be used for QA'):
            prepare.delivered_baseline(self.output, 'qa', True)

    def test_stable_download_uses_only_stable_package_and_owned_tag(self):
        feed = {'schemaVersion': 1, 'channel': 'release', 'packageName': prepare.RELEASE_PACKAGE,
                'apkUrl': 'https://github.com/denson9874/ExpressiveLauncher/releases/download/v1.0.13-14/launcher.apk'}
        def fetch(url, destination, channel):
            self.assertEqual('release', channel)
            prepare.validate_download_url(url, channel=channel)
            destination.write_text(json.dumps(feed) if url == prepare.RELEASE_FEED else 'APK')
        with patch.object(prepare, 'download', side_effect=fetch):
            self.assertEqual(feed, prepare.delivered_baseline(self.output, 'release')[0])
            for key, value in (('channel', 'qa'), ('packageName', prepare.QA_PACKAGE)):
                old = feed[key]
                feed[key] = value
                with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'outside'):
                    prepare.delivered_baseline(self.output, 'release')
                feed[key] = old

    def test_selected_qa_retains_seal_proof_without_becoming_stable_baseline(self):
        self.metadata['certificateSha256'] = prepare.EXPECTED_CERTIFICATE_SHA256
        self.seal()
        provenance = prepare.retain_qa_provenance(self.home, self.release_id, self.output, 'a' * 40)
        self.assertEqual(self.sha, provenance['sha256'])
        self.assertEqual(hashlib.sha256((self.release / 'seal.json').read_bytes()).hexdigest(), provenance['sealSha256'])
        self.assertEqual(self.metadata, json.loads((self.output / 'qa-metadata.json').read_text()))
        self.assertFalse((self.output / 'baseline.apk').exists())
        with self.assertRaisesRegex(ValueError, 'exactly match'):
            prepare.retain_qa_provenance(self.home, self.release_id, self.output, 'b' * 40)
        proof = prepare.retain_qa_provenance(self.home, self.release_id, self.output, 'b' * 40, validation_only=True)
        self.assertEqual('a' * 40, proof['sourceRevision'])

    def test_validation_only_never_bypasses_seal_or_certificate(self):
        self.metadata['certificateSha256'] = 'c' * 64
        self.seal()
        with self.assertRaisesRegex(ValueError, 'durable'):
            prepare.retain_qa_provenance(self.home, self.release_id, self.output, 'b' * 40, True)
        self.metadata['certificateSha256'] = prepare.EXPECTED_CERTIFICATE_SHA256
        self.seal()
        (self.release / 'candidate.apk').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'sealed bytes changed'):
            prepare.retain_qa_provenance(self.home, self.release_id, self.output, 'b' * 40, True)


if __name__ == '__main__':
    unittest.main()

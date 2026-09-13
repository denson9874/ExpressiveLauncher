"""Prevent channel confusion and unverified bootstrap baselines during GitHub migration."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
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
        for key, value in [('packageName', 'dev.launcher.expressive.l3.debug'), ('channel', 'release'),
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
        with patch.object(prepare, 'download', side_effect=missing), patch.object(prepare, 'verify_first_stable_history', return_value={'authenticated': True}) as history:
            self.assertEqual((None, prepare.RELEASE_FEED), prepare.delivered_baseline(self.output, 'release', True))
            with self.assertRaises(urllib.error.HTTPError):
                prepare.delivered_baseline(self.output, 'release')
            history.assert_called_once_with()
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
            for key, value in (('channel', 'qa'), ('packageName', prepare.LEGACY_QA_PACKAGE)):
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


class StableBootstrapHistoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        self.qa_release = dict(tag_name='qa-v1.0.13-14', prerelease=True, draft=False,
                               published_at='2026-09-09T20:36:58Z')

    def bootstrap(self):
        missing = urllib.error.HTTPError(prepare.RELEASE_FEED, 404, 'Not Found', {}, None)
        self.addCleanup(missing.close)
        with patch.object(prepare, 'download', side_effect=missing):
            return prepare.delivered_baseline(self.output, 'release', True)

    def test_empty_authenticated_history_allows_first_stable_and_retains_proof(self):
        with patch.object(prepare, 'authenticated_github_json', side_effect=[[], [[self.qa_release]]]) as api:
            self.assertEqual((None, prepare.RELEASE_FEED), self.bootstrap())
        self.assertEqual([
            unittest.mock.call(f'/repos/{prepare.REPOSITORY}/commits?sha=updates&path=release/latest.json&per_page=1'),
            unittest.mock.call(f'/repos/{prepare.REPOSITORY}/releases?per_page=100', paginate=True),
        ], api.call_args_list)
        proof = json.loads((self.output / 'stable-bootstrap-history.json').read_text())
        self.assertTrue(proof['authenticated'])
        self.assertEqual(0, proof['feedHistoryCount'])
        self.assertEqual(0, proof['publishedStableReleaseCount'])
        self.assertEqual(1, proof['releasesInspected'])
        self.assertFalse((self.output / 'baseline.apk').exists())

    def test_deleted_feed_with_history_cannot_rebootstrap(self):
        with patch.object(prepare, 'authenticated_github_json', return_value=[{'sha': 'a' * 40}]) as api:
            with self.assertRaisesRegex(ValueError, 'publication history'):
                self.bootstrap()
        self.assertEqual(1, api.call_count)
        self.assertFalse((self.output / 'stable-bootstrap-history.json').exists())

    def test_published_stable_on_later_page_blocks_even_without_feed_history(self):
        stable = dict(self.qa_release, tag_name='v1.0.13-14', prerelease=False)
        for release in (stable, dict(stable, prerelease=True), dict(stable, tag_name='historic-stable')):
            with self.subTest(release=release), patch.object(prepare, 'authenticated_github_json',
                    side_effect=[[], [[self.qa_release], [release]]]), self.assertRaisesRegex(ValueError, 'published stable release'):
                self.bootstrap()
        self.assertFalse((self.output / 'stable-bootstrap-history.json').exists())

    def test_unpublished_draft_is_not_claimed_as_previously_delivered_stable(self):
        draft = dict(tag_name='v1.0.13-14', prerelease=False, draft=True, published_at=None)
        with patch.object(prepare, 'authenticated_github_json', side_effect=[[], [[draft], []]]):
            proof = prepare.verify_first_stable_history()
        self.assertEqual(1, proof['releasesInspected'])

    def test_unknown_api_shapes_and_incomplete_release_records_fail_closed(self):
        for replies in ([{}], [[], {}], [[], []], [[], [self.qa_release]],
                        [[], [[{}]]], [[], [[dict(self.qa_release, published_at=None)]]],
                        [[], [[dict(self.qa_release, prerelease='true')]]]):
            with self.subTest(replies=replies), patch.object(prepare, 'authenticated_github_json', side_effect=replies), self.assertRaises(ValueError):
                self.bootstrap()
        self.assertFalse((self.output / 'stable-bootstrap-history.json').exists())

    def test_api_uncertainty_cannot_establish_absence(self):
        for replies in ([ValueError('authentication unavailable')], [[], ValueError('releases unavailable')]):
            with self.subTest(replies=replies), patch.object(prepare, 'authenticated_github_json', side_effect=replies), self.assertRaises(ValueError):
                self.bootstrap()
        self.assertFalse((self.output / 'stable-bootstrap-history.json').exists())

    def test_authenticated_cli_uses_get_and_all_release_pages_without_credentials(self):
        response = subprocess.CompletedProcess([], 0, '[[]]', '')
        endpoint = f'/repos/{prepare.REPOSITORY}/releases?per_page=100'
        with patch.object(prepare.subprocess, 'run', return_value=response) as run:
            self.assertEqual([[]], prepare.authenticated_github_json(endpoint, paginate=True))
        self.assertEqual(['gh', 'api', '--hostname', 'github.com', '--method', 'GET', '--paginate', '--slurp', endpoint], run.call_args.args[0])
        self.assertEqual(120, run.call_args.kwargs['timeout'])
        self.assertEqual(subprocess.DEVNULL, run.call_args.kwargs['stdin'])

    def test_cli_errors_timeouts_and_invalid_json_are_sanitized_failures(self):
        for result in (subprocess.CompletedProcess([], 1, '', 'sensitive diagnostic'),
                       subprocess.CompletedProcess([], 0, 'not json', ''),
                       subprocess.TimeoutExpired('gh', 120), FileNotFoundError('gh missing')):
            kwargs = {'side_effect': result} if isinstance(result, Exception) else {'return_value': result}
            with self.subTest(result=result), patch.object(prepare.subprocess, 'run', **kwargs):
                with self.assertRaises(ValueError) as error:
                    prepare.authenticated_github_json('/repos/example/repository/commits')
                self.assertNotIn('sensitive diagnostic', str(error.exception))

class UnifiedQaMigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        self.branch = {'object': {'sha': 'a' * 40}}
        self.legacy = dict(tag_name='qa-v1.0.16-17', prerelease=True, draft=False, published_at='2026-09-12T12:00:00Z')
        self.stable = dict(schemaVersion=1, channel='release', packageName=prepare.RELEASE_PACKAGE,
                           versionName='1.0.16', versionCode=17, sha256='b' * 64, sizeBytes=3,
                           apkUrl=f'https://github.com/{prepare.REPOSITORY}/releases/download/v1.0.16-17/stable.apk')

    def download(self, url, destination, channel='qa'):
        if url == prepare.FEED:
            raise urllib.error.HTTPError(url, 404, 'Not Found', {}, None)
        self.assertEqual('release', channel)
        destination.write_text(json.dumps(self.stable) if url == prepare.RELEASE_FEED else 'APK')

    def test_first_unified_qa_downloads_stable_as_same_package_upgrade_baseline(self):
        with patch.object(prepare, 'download', side_effect=self.download), patch.object(
                prepare, 'authenticated_github_json', side_effect=[self.branch, [], [[self.legacy]]]):
            baseline, url = prepare.delivered_baseline(self.output)
        self.assertEqual(self.stable, baseline)
        self.assertEqual(prepare.RELEASE_FEED, url)
        proof = json.loads((self.output / 'qa-channel-migration.json').read_text())
        self.assertEqual(('release', 'qa'), (proof['fromChannel'], proof['toChannel']))
        self.assertEqual(self.stable['sha256'], proof['baselineSha256'])
        self.assertTrue(proof['history']['authenticated'])
        self.assertEqual('qa-v2/latest.json', proof['history']['feedPath'])
        self.assertEqual('APK', (self.output / 'baseline.apk').read_text())

    def test_deleted_unified_qa_feed_or_previously_published_v2_blocks_migration(self):
        published = dict(self.legacy, tag_name='qa-v2.0.0-18')
        for replies in ([self.branch, [{'sha': 'c' * 40}]], [self.branch, [], [[self.legacy], [published]]]):
            with self.subTest(replies=replies), patch.object(prepare, 'download', side_effect=self.download), patch.object(
                    prepare, 'authenticated_github_json', side_effect=replies), self.assertRaises(ValueError):
                prepare.delivered_baseline(self.output)
        self.assertFalse((self.output / 'qa-channel-migration.json').exists())
        self.assertFalse((self.output / 'baseline.apk').exists())

    def test_uncertain_identity_or_history_never_establishes_new_channel(self):
        for replies in ([{}], [self.branch, {}], [self.branch, [], []], [self.branch, [], [[{}]]]):
            with self.subTest(replies=replies), patch.object(prepare, 'authenticated_github_json', side_effect=replies), self.assertRaises(ValueError):
                prepare.verify_first_unified_qa_history()

    def test_non404_and_redirect404_do_not_fall_back_to_stable(self):
        for url, code in ((prepare.FEED, 403), (prepare.FEED, 500), (prepare.RELEASE_FEED, 404)):
            error = urllib.error.HTTPError(url, code, 'Unavailable', {}, None)
            self.addCleanup(error.close)
            with patch.object(prepare, 'download', side_effect=error), patch.object(prepare, 'verify_first_unified_qa_history') as history:
                with self.assertRaises(urllib.error.HTTPError):
                    prepare.delivered_baseline(self.output)
                history.assert_not_called()

    def test_legacy_qa_package_cannot_be_the_unified_upgrade_baseline(self):
        feed = dict(self.stable, channel='qa', packageName=prepare.LEGACY_QA_PACKAGE)
        def deliver(url, destination, channel='qa'):
            destination.write_text(json.dumps(feed))
        with patch.object(prepare, 'download', side_effect=deliver), self.assertRaisesRegex(ValueError, 'outside'):
            prepare.delivered_baseline(self.output)


if __name__ == '__main__':
    unittest.main()

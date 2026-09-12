"""A green-stable policy record can supersede eligibility, never artifact evidence."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location('green_stable', Path(__file__).parents[1] / 'green_stable.py')
green = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(green)


class GreenStableTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.release_id = 'release-1.0.16-17-build-7'
        self.apk = self.root / 'ExpressiveLauncher-1.0.16-release.apk'
        self.apk.write_bytes(b'exact signed stable candidate')
        self.sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.metadata = {
            'schemaVersion': 1, 'channel': 'release', 'packageName': green.RELEASE_PACKAGE,
            'versionName': '1.0.16', 'versionCode': 17, 'sourceRevision': 'a' * 40,
            'fileName': self.apk.name, 'sha256': self.sha, 'sizeBytes': self.apk.stat().st_size,
            'signatureVerified': True, 'debuggable': False, 'certificateSha256': green.CERTIFICATE,
            'validationOnly': True, 'stableBootstrap': True, 'baselineMode': 'first-stable-install',
            'baseline': None, 'buildUrl': green.JENKINS_URL + 'job/' + green.JOB + '/7/',
            'unitTests': {'tests': 197, 'failures': 0, 'errors': 0, 'skipped': 0},
        }
        self.qa = {
            'passed': True, 'sha256': self.sha, 'apkSha256': self.sha, 'sourceRevision': 'a' * 40,
            'channel': 'release', 'packageName': green.RELEASE_PACKAGE, 'versionName': '1.0.16',
            'versionCode': 17, 'stableBootstrap': True, 'baselineMode': 'first-stable-install',
            'baseline': None, 'flows': {name: {'passed': True} for name in
                                      green.COMMON_FLOWS | {'first_stable_install', 'same_version_reinstall'}},
        }
        self.qa['flows']['same_version_reinstall']['installedSha256'] = self.sha
        (self.root / 'QA-report.md').write_text('Original private validation report.\n')
        self.write_artifacts()
        self.build = {'number': 7, 'url': self.metadata['buildUrl'], 'building': False,
                      'result': 'SUCCESS', 'timestamp': 1789248000000, 'duration': 321000}
        self.client = mock.Mock()
        self.client.json.side_effect = lambda _: copy.deepcopy(self.build)
        for name, value in [('_jenkins_client', self.client), ('_publisher_revision', 'b' * 40),
                            ('_committed_publisher_revision', 'b' * 40)]:
            patcher = mock.patch.object(green, name, return_value=value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def write_artifacts(self):
        self.qa['reportSha256'] = green._digest(self.root / 'QA-report.md')
        for filename, value in [('metadata.json', self.metadata), ('qa-result.json', self.qa)]:
            (self.root / filename).write_text(json.dumps(value))
        seal = {'schemaVersion': 1, 'complete': True, 'sourceRevision': self.metadata['sourceRevision'],
                'sha256': self.metadata['sha256'],
                **{key: green._digest(self.root / filename) for key, filename in green.EVIDENCE_FILES.items()}}
        (self.root / 'seal.json').write_text(json.dumps(seal))

    def authorize(self):
        return green.authorize(self.root, self.release_id, Path('/saved/repository'))

    def test_green_formerly_private_build_gets_deterministic_separate_authorization(self):
        original = {path.name: path.read_bytes() for path in self.root.iterdir()}
        record = self.authorize()
        self.assertEqual(record, self.authorize())
        self.assertEqual(record['policy'], 'green-stable-v1')
        self.assertEqual(record['sha256'], self.sha)
        self.assertEqual(record['sealSha256'], green._digest(self.root / 'seal.json'))
        self.assertTrue(record['originalValidationOnly'])
        self.assertTrue(record['supersedesOriginalPublicationRestriction'])
        self.assertEqual(record['jenkins']['result'], 'SUCCESS')
        green.validate_authorization(record, self.root, self.metadata)
        self.assertEqual(original, {path.name: path.read_bytes() for path in self.root.iterdir()})

    def test_ordinary_green_build_is_authorized_under_the_same_policy(self):
        self.metadata['validationOnly'] = False
        self.write_artifacts()
        self.assertFalse(self.authorize()['originalValidationOnly'])

    def test_green_upgrade_requires_its_upgrade_flows(self):
        for record in (self.metadata, self.qa):
            record.update(stableBootstrap=False, baselineMode='quiesced-upgrade')
        self.metadata['baseline'] = {'versionCode': 16, 'versionName': '1.0.15', 'sha256': 'c' * 64}
        del self.qa['baseline']
        self.qa.update(baselineSha256='c' * 64, baselineApk='/retained/baseline.apk')
        self.write_artifacts()
        with self.assertRaisesRegex(ValueError, 'Every required stable device flow'):
            self.authorize()
        self.qa['flows']['baseline_install'] = self.qa['flows'].pop('first_stable_install')
        self.qa['flows']['baseline_install'].update(version='1.0.15', versionCode='16')
        self.qa['flows']['same_signer_upgrade'] = self.qa['flows'].pop('same_version_reinstall')
        self.write_artifacts()
        self.authorize()

    def test_upgrade_rejects_different_baseline_bytes_or_installed_version(self):
        self.test_green_upgrade_requires_its_upgrade_flows()
        self.qa['baselineSha256'] = 'd' * 64
        self.write_artifacts()
        with self.assertRaisesRegex(ValueError, 'same earlier baseline'):
            self.authorize()
        self.qa['baselineSha256'] = 'c' * 64
        self.qa['flows']['baseline_install']['versionCode'] = '15'
        self.write_artifacts()
        with self.assertRaisesRegex(ValueError, 'Installed baseline version differs'):
            self.authorize()

    def test_failed_aborted_unstable_or_unbuilt_jenkins_is_not_green(self):
        for status in ('FAILURE', 'ABORTED', 'UNSTABLE', 'NOT_BUILT', None):
            with self.subTest(status=status):
                self.build['result'] = status
                with self.assertRaisesRegex(ValueError, 'must finish with SUCCESS'):
                    self.authorize()

    def test_waits_for_upstream_to_finish_before_authorizing(self):
        running = dict(self.build, building=True, result=None)
        self.client.json.side_effect = [running, self.build]
        with mock.patch.object(green.time, 'sleep') as sleep:
            record = self.authorize()
        sleep.assert_called_once()
        self.assertIs(record['jenkins']['building'], False)

    def test_running_upstream_cannot_authorize_after_deadline(self):
        self.build.update(building=True, result=None)
        with self.assertRaisesRegex(ValueError, 'still running'):
            green._green_build(7, wait_seconds=0)

    def test_running_upstream_cannot_claim_a_success_result(self):
        self.build['building'] = True
        with self.assertRaisesRegex(ValueError, 'inconsistent result'):
            self.authorize()

    def test_jenkins_response_must_be_for_exact_build_with_typed_terminal_fields(self):
        original = copy.deepcopy(self.build)
        for field, value in [('number', 8), ('number', True), ('url', original['url'].replace('/7/', '/8/')),
                             ('building', None), ('timestamp', None), ('duration', True)]:
            with self.subTest(field=field, value=value):
                self.build = dict(original, **{field: value})
                with self.assertRaises(ValueError):
                    self.authorize()

    def test_jenkins_unavailable_fails_closed(self):
        self.client.json.side_effect = OSError('connection unavailable')
        with self.assertRaisesRegex(ValueError, 'Could not verify'):
            self.authorize()

    def test_release_id_and_sealed_url_must_bind_same_stable_build(self):
        for release_id in ('../release-1.0.16-17-build-7', 'qa-1.0.16-17-build-7',
                           'release-1.0.17-17-build-7', 'release-1.0.16-18-build-7',
                           'release-1.0.16-17-build-8'):
            with self.subTest(release_id=release_id), self.assertRaises(ValueError):
                green.authorize(self.root, release_id, Path('/saved/repository'))
        for url in ('https://example.com/job/expressive-release-build/7/',
                    green.JENKINS_URL + 'job/expressive-qa-build/7/'):
            self.metadata['buildUrl'] = url
            self.write_artifacts()
            with self.subTest(url=url), self.assertRaisesRegex(ValueError, 'Sealed build URL'):
                self.authorize()

    def test_mutating_any_sealed_file_blocks_authorization(self):
        for filename in ('metadata.json', 'qa-result.json', 'QA-report.md', self.apk.name):
            path = self.root / filename
            original = path.read_bytes()
            path.write_bytes(original + b' ')
            with self.subTest(filename=filename), self.assertRaises(ValueError):
                self.authorize()
            path.write_bytes(original)

    def test_incomplete_or_different_seal_is_rejected(self):
        path = self.root / 'seal.json'
        original = json.loads(path.read_text())
        for field, value in [('complete', False), ('complete', 1), ('schemaVersion', True),
                             ('sourceRevision', 'd' * 40), ('sha256', 'd' * 64),
                             ('qaResultSha256', 'd' * 64)]:
            path.write_text(json.dumps(dict(original, **{field: value})))
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.authorize()

    def test_failed_missing_or_skipped_unit_tests_are_not_green(self):
        original = copy.deepcopy(self.metadata['unitTests'])
        for field, value in [('tests', 0), ('tests', True), ('failures', 1), ('errors', 1),
                             ('skipped', 1), ('skipped', None)]:
            self.metadata['unitTests'] = dict(original, **{field: value})
            self.write_artifacts()
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'unskipped unit tests'):
                self.authorize()

    def test_missing_failed_or_extra_failed_smoke_flow_is_not_green(self):
        original = copy.deepcopy(self.qa['flows'])
        for name in sorted(original):
            self.qa['flows'] = copy.deepcopy(original)
            del self.qa['flows'][name]
            self.write_artifacts()
            with self.subTest(missing=name), self.assertRaisesRegex(ValueError, 'Every required stable device flow'):
                self.authorize()
        for name in ('cold_start', 'additional_probe'):
            self.qa['flows'] = {**copy.deepcopy(original), name: {'passed': False}}
            self.write_artifacts()
            with self.subTest(failed=name), self.assertRaisesRegex(ValueError, 'Every required stable device flow'):
                self.authorize()

    def test_installed_device_artifact_must_match_sealed_apk(self):
        self.qa['flows']['same_version_reinstall']['installedSha256'] = 'd' * 64
        self.write_artifacts()
        with self.assertRaisesRegex(ValueError, 'Installed device APK differs'):
            self.authorize()

    def test_failed_or_mismatched_device_evidence_is_not_green(self):
        original = copy.deepcopy(self.qa)
        for field, value in [('passed', False), ('sha256', 'd' * 64), ('apkSha256', 'd' * 64),
                             ('sourceRevision', 'd' * 40), ('channel', 'qa'), ('versionCode', 18),
                             ('stableBootstrap', False), ('baselineMode', 'quiesced-upgrade')]:
            self.qa = dict(original, **{field: value})
            self.write_artifacts()
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.authorize()

    def test_wrong_package_unsigned_debuggable_or_unsafe_artifact_is_rejected(self):
        original = copy.deepcopy(self.metadata)
        for field, value in [('packageName', green.RELEASE_PACKAGE + '.debug'), ('channel', 'qa'),
                             ('signatureVerified', False), ('debuggable', True),
                             ('certificateSha256', 'd' * 64), ('fileName', '../other.apk'),
                             ('validationOnly', None), ('versionCode', True)]:
            self.metadata = dict(original, **{field: value})
            self.write_artifacts()
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.authorize()

    def test_symlinked_artifacts_are_rejected(self):
        report = self.root / 'QA-report.md'
        retained = self.root / 'original-report.md'
        report.rename(retained)
        report.symlink_to(retained)
        with self.assertRaisesRegex(ValueError, 'Required regular artifact'):
            self.authorize()

    def test_validation_rechecks_live_jenkins_even_after_authorization(self):
        record = self.authorize()
        self.build['result'] = 'ABORTED'
        with self.assertRaisesRegex(ValueError, 'must finish with SUCCESS'):
            green.validate_authorization(record, self.root, self.metadata)

    def test_validation_rejects_changed_authorization_fields(self):
        record = self.authorize()
        for field, value in [('policy', 'force'), ('userInstruction', 'Publish anything'),
                             ('sha256', 'd' * 64), ('sealSha256', 'd' * 64),
                             ('sourceRevision', 'd' * 40), ('versionName', '1.0.17'),
                             ('originalValidationOnly', False), ('schemaVersion', True),
                             ('supersedesOriginalPublicationRestriction', 1), ('extra', True)]:
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'authorization differs'):
                green.validate_authorization(dict(record, **{field: value}), self.root, self.metadata)
        changed = copy.deepcopy(record)
        changed['jenkins']['duration'] += 1
        with self.assertRaisesRegex(ValueError, 'authorization differs'):
            green.validate_authorization(changed, self.root, self.metadata)

    def test_validation_rejects_changed_evidence_or_supplied_metadata(self):
        record = self.authorize()
        altered = dict(self.metadata, validationOnly=False)
        with self.assertRaisesRegex(ValueError, 'Supplied metadata differs'):
            green.validate_authorization(record, self.root, altered)
        self.metadata['unitTests']['tests'] += 1
        self.write_artifacts()
        with self.assertRaisesRegex(ValueError, 'authorization differs'):
            green.validate_authorization(record, self.root, self.metadata)

    def test_retry_preserves_original_publisher_commit(self):
        record = self.authorize()
        green._publisher_revision.return_value = 'c' * 40
        green.validate_authorization(record, self.root, self.metadata)
        green._committed_publisher_revision.assert_called_with('b' * 40)


class PublisherCommitTests(unittest.TestCase):
    def test_new_authorization_requires_clean_commit(self):
        with mock.patch.object(green.subprocess, 'check_output', side_effect=[' M ci/publish_qa.py\n']):
            with self.assertRaisesRegex(ValueError, 'clean publisher checkout'):
                green._publisher_revision(Path('/saved/repository'))
        with mock.patch.object(green.subprocess, 'check_output', side_effect=['', 'a' * 40 + '\n']):
            self.assertEqual(green._publisher_revision(Path('/saved/repository')), 'a' * 40)

    def test_retry_authorization_requires_retained_full_commit(self):
        with mock.patch.object(green.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'full commit SHA'):
                green._committed_publisher_revision('HEAD')
            run.assert_not_called()
            self.assertEqual(green._committed_publisher_revision('a' * 40), 'a' * 40)
            run.side_effect = subprocess.CalledProcessError(1, ['git'])
            with self.assertRaisesRegex(ValueError, 'retained commit'):
                green._committed_publisher_revision('b' * 40)


if __name__ == '__main__':
    unittest.main()

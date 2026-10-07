"""Require explicit candidate versions before a QA build can be queued."""
import importlib.util
import io
from pathlib import Path
import unittest
import urllib.parse
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location(
    'jenkins_control', Path(__file__).parents[1] / 'jenkins/control.py')
control = importlib.util.module_from_spec(spec)
spec.loader.exec_module(control)


class QaCandidateParametersTests(unittest.TestCase):
    def command(self, version_arguments):
        return ['control.py', 'run', '--job', 'build', '--revision', 'a' * 40, *version_arguments]

    def test_missing_or_malformed_version_cannot_queue_build(self):
        for arguments in (
            [], ['--version-name', '2.0.0'], ['--version-code', '18'],
            ['--version-name', '2.0', '--version-code', '18'],
            ['--version-name', '2.0.0', '--version-code', '0'],
            ['--version-name', '2.0.0', '--version-code', '-1'],
        ):
            client = MagicMock()
            with self.subTest(arguments=arguments), patch.object(control, 'Client', return_value=client), \
                    patch('sys.argv', self.command(arguments)), patch('sys.stderr', new_callable=io.StringIO), \
                    self.assertRaises(SystemExit) as stopped:
                control.main()
            self.assertEqual(2, stopped.exception.code)
            client.request.assert_not_called()

    def test_explicit_new_series_and_subsequent_versions_reach_jenkins_unchanged(self):
        for version, code in [('2.0.0', '18'), ('2.0.1', '19')]:
            client = MagicMock()
            response = client.request.return_value.__enter__.return_value
            response.status = 201
            response.headers = {'Location': control.URL + 'queue/item/70/'}
            with self.subTest(version=version), patch.object(control, 'Client', return_value=client), \
                    patch('sys.argv', self.command(['--version-name', version, '--version-code', code])), \
                    patch('sys.stdout', new_callable=io.StringIO):
                control.main()
            endpoint, encoded = client.request.call_args.args
            self.assertEqual('job/expressive-qa-build/buildWithParameters', endpoint)
            self.assertEqual({
                'SOURCE_REVISION': ['a' * 40], 'VERSION_NAME': [version], 'VERSION_CODE': [code],
                'BASELINE_RELEASE_ID': [''],
            }, urllib.parse.parse_qs(encoded.decode(), keep_blank_values=True))


class CorePublishJobTests(unittest.TestCase):
    """XDA-021: Core publishes from its own job; the weekly gate audits only expressive-qa-publish."""

    def run_control(self, *arguments):
        client = MagicMock()
        response = client.request.return_value.__enter__.return_value
        response.status = 201
        response.headers = {'Location': control.URL + 'queue/item/71/'}
        with patch.object(control, 'Client', return_value=client), patch('sys.argv', ['control.py', *arguments]), \
                patch('sys.stdout', new_callable=io.StringIO), patch('sys.stderr', new_callable=io.StringIO):
            control.main()
        return client

    def test_core_publish_queues_only_core_seals_on_its_own_job(self):
        client = self.run_control('run', '--job', 'core-publish', '--release-id', 'qa-core-4.1.1-60-build-66', '--promote')
        endpoint, encoded = client.request.call_args.args
        self.assertEqual('job/expressive-qa-core-publish/buildWithParameters', endpoint)
        self.assertEqual({'RELEASE_ID': ['qa-core-4.1.1-60-build-66'], 'PROMOTE_QA_FEED': ['true']},
                         urllib.parse.parse_qs(encoded.decode()))

    def test_full_and_core_release_ids_cannot_cross_jobs(self):
        for arguments in (['--job', 'core-publish', '--release-id', 'qa-4.1.1-60-build-66'],
                          ['--job', 'publish', '--release-id', 'qa-core-4.1.1-60-build-66'],
                          ['--job', 'core-publish', '--release-id', 'qa-core-4.1.1-60-build-66', '--promote',
                           '--bridge-legacy-qa']):
            with self.subTest(arguments=arguments), self.assertRaises(SystemExit) as stopped:
                self.run_control('run', *arguments)
            self.assertEqual(2, stopped.exception.code)

    def test_core_publish_job_definition_has_only_release_id_and_promotion(self):
        client = MagicMock()
        control.configure(client, 'core-publish')
        endpoint, xml = client.request.call_args.args[:2]
        self.assertEqual('job/expressive-qa-core-publish/config.xml', endpoint)
        text = xml.decode()
        self.assertIn('<name>RELEASE_ID</name>', text)
        self.assertIn('<name>PROMOTE_QA_FEED</name>', text)
        self.assertNotIn('BRIDGE_LEGACY_QA_FEED', text)
        self.assertIn('publish --variant core', text)


if __name__ == '__main__':
    unittest.main()

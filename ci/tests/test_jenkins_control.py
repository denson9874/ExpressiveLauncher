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


if __name__ == '__main__':
    unittest.main()

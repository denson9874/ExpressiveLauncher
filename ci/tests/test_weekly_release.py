"""Stable publication cannot escape the current green week or trial-only boundary."""
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('weekly_release', Path(__file__).parents[1] / 'weekly_release.py')
weekly = importlib.util.module_from_spec(spec)
spec.loader.exec_module(weekly)


class PublicationSelectionTests(unittest.TestCase):
    def setUp(self):
        self.selected = dict(sourceRevision='a' * 40, versionName='1.0.14', versionCode=15,
                             qaReleaseId='qa-1.0.14-15-build-8', buildNumber=8)
        self.gate = dict(status='eligible', selected=self.selected, windowStart='2026-09-07T00:00:00-04:00')
        self.metadata = dict(sourceRevision='a' * 40, versionName='1.0.14', versionCode=15,
                             channel='release', weeklyReleaseGate=copy.deepcopy(self.gate), validationOnly=False)

    def test_exact_stable_candidate_from_current_eligible_week_is_accepted(self):
        weekly.require_publish_selection(self.metadata, self.gate)

    def test_private_validation_candidate_cannot_be_published_even_when_week_is_green(self):
        self.metadata['validationOnly'] = True
        with self.assertRaisesRegex(ValueError, 'Validation-only'):
            weekly.require_publish_selection(self.metadata, self.gate)

    def test_changed_friday_candidate_or_held_week_blocks_old_seal(self):
        for key, value in [('sourceRevision', 'b' * 40), ('versionName', '1.0.15'), ('versionCode', 16)]:
            with self.subTest(key=key):
                modified = copy.deepcopy(self.metadata)
                modified[key] = value
                with self.assertRaises(ValueError):
                    weekly.require_publish_selection(modified, self.gate)
        self.gate['status'] = 'held'
        with self.assertRaises(ValueError):
            weekly.require_publish_selection(self.metadata, self.gate)

    def test_prior_week_or_different_qa_build_is_not_reusable(self):
        for key, value in [('windowStart', '2026-08-31T00:00:00-04:00'), ('status', 'validation-only')]:
            candidate = copy.deepcopy(self.metadata)
            candidate['weeklyReleaseGate'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                weekly.require_publish_selection(candidate, self.gate)
        self.metadata['weeklyReleaseGate']['selected']['qaReleaseId'] = 'qa-1.0.14-15-build-9'
        with self.assertRaises(ValueError):
            weekly.require_publish_selection(self.metadata, self.gate)

    def test_qa_package_and_missing_gate_are_not_stable_authorization(self):
        self.metadata['channel'] = 'qa'
        with self.assertRaises(ValueError):
            weekly.require_publish_selection(self.metadata, self.gate)
        self.metadata['channel'] = 'release'
        del self.metadata['weeklyReleaseGate']
        with self.assertRaises(ValueError):
            weekly.require_publish_selection(self.metadata, self.gate)

    def test_selection_rejects_path_traversal_and_inconsistent_version(self):
        for key, value in [('qaReleaseId', '../secret'), ('qaReleaseId', 'qa-9.9.9-15-build-8'),
                           ('sourceRevision', 'HEAD'), ('versionCode', True), ('versionCode', 0)]:
            candidate = dict(self.selected, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                weekly.validate_selection(candidate)

    def test_saved_branch_or_dirty_checkout_blocks_weekly_actions(self):
        for outputs in [('main', ''), ('codex/pixel-parity', ' M build.gradle')]:
            with patch.object(weekly, 'git', side_effect=outputs), self.assertRaises(ValueError):
                weekly.guard(Path('/unused'))


if __name__ == '__main__':
    unittest.main()

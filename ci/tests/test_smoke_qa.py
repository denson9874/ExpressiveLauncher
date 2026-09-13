"""Regression coverage for the crash gate's distinction between warnings and failures."""
import importlib.util
import argparse
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("smoke_qa", Path(__file__).parents[1] / "smoke_qa.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class AppCrashGateTests(unittest.TestCase):
    def test_launcher_runtime_fatal_blocks(self):
        log = "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: dev.launcher.expressive.l3, PID: 123\n"
        self.assertEqual(smoke.app_log_failures(log, "", ""), ["fatal_exception"])

    def test_crash_buffer_alone_blocks(self):
        crash = "FATAL EXCEPTION: main\nProcess: dev.launcher.expressive.l3, PID: 123"
        self.assertEqual(smoke.app_log_failures("", crash, ""), ["fatal_exception"])

    def test_anr_event_blocks_without_main_log_message(self):
        events = "I am_anr: [0,123,dev.launcher.expressive.l3,0,Input dispatching timed out]"
        self.assertEqual(smoke.app_log_failures("", "", events), ["anr"])

    def test_process_crash_event_blocks(self):
        events = "I am_crash: [0,123,dev.launcher.expressive.l3,0,java.lang.RuntimeException]"
        self.assertEqual(smoke.app_log_failures("", "", events), ["process_crash"])

    def test_other_package_crash_does_not_claim_launcher_crash(self):
        log = "FATAL EXCEPTION: main\nProcess: com.example.unrelated, PID: 456"
        self.assertEqual(smoke.app_log_failures(log, "", ""), [])

    def test_native_tombstone_blocks(self):
        crash = "F DEBUG: pid: 123, tid: 123, name: launcher >>> dev.launcher.expressive.l3 <<<"
        self.assertEqual(smoke.app_log_failures("", crash, ""), ["native_crash"])

    def test_caught_launcher_warning_is_not_a_fatal(self):
        log = "E SystemUiProxy: dev.launcher.expressive.l3 recent-tasks unavailable\nE Snapshot: caught java.lang.NullPointerException"
        self.assertEqual(smoke.app_log_failures(log, "", ""), [])


class SnapshotTransitionTests(unittest.TestCase):
    def test_transient_null_root_retries_snapshot_and_preserves_failure(self):
        runner = smoke.Smoke.__new__(smoke.Smoke)
        runner.adb = Mock(side_effect=["ERROR: null root node", '<?xml version="1.0"?><hierarchy><node text="Calendar"/></hierarchy>\nUI dump complete', b"PNG"])
        runner.save = Mock()
        with patch.object(smoke.time, "sleep"):
            root = runner.capture("transition")
        self.assertEqual(root.find("node").get("text"), "Calendar")
        runner.save.assert_any_call("transition-dump-attempt-1.txt", "ERROR: null root node")
        self.assertEqual(runner.adb.call_count, 3)

    def test_persistently_missing_root_fails_after_three_snapshots(self):
        runner = smoke.Smoke.__new__(smoke.Smoke)
        runner.adb = Mock(return_value="ERROR: null root node")
        runner.save = Mock()
        with patch.object(smoke.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "after 3 snapshots"):
                runner.capture("broken")
        self.assertEqual(runner.adb.call_count, 3)
        self.assertEqual(runner.save.call_count, 3)


class KeyboardReadinessTests(unittest.TestCase):
    @staticmethod
    def ime_dump(shown=True, package=smoke.RELEASE_PACKAGE):
        flag = str(shown).lower()
        return f"""Input Method Manager Service state:
  mCurrentImeUserId=0
  mStartInputHistory:
    mImeWindowVis=3
    mInputShown=true
  UserId=0
    mBindingController:
      mImeWindowVis={3 if shown else 0}
    mVisibilityStateComputer:
      mInputShown={flag}
    Input Methods:
Input method client state for client:
  mActive=true
Input method service state for Gboard:
  mDecorViewVisible={flag} mWindowVisible={flag} mInShowWindow=false
  mInputStarted=true mInputViewStarted={flag}
  mInputEditorInfo:
    packageName={package} fieldId=2131362281
  mShowInputRequested={flag}
  History:
    mDecorViewVisible=true mWindowVisible=true
    mInputStarted=true mInputViewStarted=true
"""

    def test_active_visible_keyboard_serving_exact_launcher_is_ready(self):
        self.assertTrue(smoke.keyboard_ready(self.ime_dump(), smoke.RELEASE_PACKAGE))
        self.assertTrue(smoke.keyboard_ready(self.ime_dump().replace('mImeWindowVis=3', 'mImeWindowVis=0x3'), smoke.RELEASE_PACKAGE))
        self.assertFalse(smoke.keyboard_ready(self.ime_dump(package=smoke.PACKAGE + ".debug"), smoke.RELEASE_PACKAGE))

    def test_focused_or_show_requested_keyboard_and_historical_visibility_are_not_ready(self):
        hidden = self.ime_dump(False)
        self.assertFalse(smoke.keyboard_ready(hidden, smoke.RELEASE_PACKAGE))
        self.assertFalse(smoke.keyboard_ready(hidden.replace('mInputShown=false', 'mInputShown=true'), smoke.RELEASE_PACKAGE))
        self.assertFalse(smoke.keyboard_ready(self.ime_dump().replace('mInputViewStarted=true', 'mInputViewStarted=false', 1), smoke.RELEASE_PACKAGE))
        self.assertFalse(smoke.keyboard_ready(self.ime_dump().replace('  UserId=0', '  UserId=10'), smoke.RELEASE_PACKAGE))
        self.assertFalse(smoke.keyboard_ready('', smoke.RELEASE_PACKAGE))

    def runner(self):
        runner = smoke.Smoke.__new__(smoke.Smoke)
        runner.package = smoke.RELEASE_PACKAGE
        runner.save = Mock()
        return runner

    def test_cold_keyboard_waits_for_current_visible_state_and_retains_evidence(self):
        runner = self.runner()
        runner.shell = Mock(side_effect=[self.ime_dump(False), self.ime_dump()])
        now = [0.0]
        with patch.object(smoke.time, 'monotonic', side_effect=lambda: now[0]), \
             patch.object(smoke.time, 'sleep', side_effect=lambda delay: now.__setitem__(0, now[0] + delay)):
            runner.wait_for_keyboard('search', timeout=2)
        self.assertEqual(runner.shell.call_count, 2)
        self.assertEqual(now[0], 1)
        runner.save.assert_any_call('search-ime-attempt-1.txt', self.ime_dump(False))
        runner.save.assert_any_call('search-ime-ready.txt', self.ime_dump())
        self.assertTrue(all(call.args == ('dumpsys', '-t', '3', 'input_method') for call in runner.shell.call_args_list))

    def test_timeout_is_bounded_preserves_failure_and_never_injects_text(self):
        runner = self.runner()
        runner.shell = Mock(return_value=self.ime_dump(False))
        now = [0.0]
        with patch.object(smoke.time, 'monotonic', side_effect=lambda: now[0]), \
             patch.object(smoke.time, 'sleep', side_effect=lambda delay: now.__setitem__(0, now[0] + delay)):
            with self.assertRaisesRegex(RuntimeError, 'within 2s'):
                runner.wait_for_keyboard('search', timeout=2)
        self.assertEqual(now[0], 2)
        self.assertEqual(runner.shell.call_count, 2)
        runner.save.assert_any_call('search-ime-timeout.txt', self.ime_dump(False))
        self.assertTrue(all(call.args == ('dumpsys', '-t', '3', 'input_method') for call in runner.shell.call_args_list))


class StableSmokeContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / 'stable.apk'
        self.apk.write_bytes(b'stable fixture')

    def args(self, **changes):
        fields = dict(apk=self.apk, baseline_apk=None, output_dir=self.root / 'evidence',
                      android_home=self.root / 'sdk', source_revision='a' * 40,
                      channel='release', bootstrap_stable=True)
        fields.update(changes)
        return argparse.Namespace(**fields)

    def test_bootstrap_records_reinstall_without_claiming_prior_version(self):
        runner = smoke.Smoke(self.args())
        self.assertEqual(smoke.RELEASE_PACKAGE, runner.package)
        self.assertEqual(self.apk, runner.install_baseline)
        self.assertIsNone(runner.result['baselineApk'])
        self.assertIsNone(runner.result['baselineSha256'])
        self.assertIsNone(runner.result['baseline'])
        self.assertEqual('first-stable-install', runner.result['baselineMode'])
        self.assertTrue(runner.result['stableBootstrap'])
        self.assertEqual('release', runner.result['channel'])
        self.assertEqual(11, len(runner.required_flows))
        self.assertNotIn('same_signer_upgrade', runner.required_flows)
        self.assertIn('same_version_reinstall', runner.required_flows)

    def test_normal_stable_retains_upgrade_gate(self):
        runner = smoke.Smoke(self.args(bootstrap_stable=False, baseline_apk=self.apk))
        self.assertEqual(smoke.REQUIRED_FLOWS, runner.required_flows)
        self.assertFalse(runner.result['stableBootstrap'])
        self.assertEqual('quiesced-upgrade', runner.result['baselineMode'])
        self.assertEqual('quiesced-adb-upgrade-smoke', runner.result['scope'])

    def test_invalid_modes_fail_before_allocating_evidence_or_device(self):
        for changes in ({'channel': 'qa'}, {'baseline_apk': self.apk}, {'bootstrap_stable': False}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                smoke.Smoke(self.args(**changes))
        self.assertFalse((self.root / 'evidence').exists())

    def test_release_crashes_are_gated_without_matching_qa_package_prefix(self):
        for package in (smoke.RELEASE_PACKAGE, smoke.RELEASE_PACKAGE + ':worker'):
            log = f'FATAL EXCEPTION: main\nProcess: {package}, PID: 123'
            self.assertEqual(['fatal_exception'], smoke.app_log_failures(log, '', '', smoke.RELEASE_PACKAGE))
        qa_log = f'FATAL EXCEPTION: main\nProcess: {smoke.PACKAGE}.debug, PID: 123'
        self.assertEqual([], smoke.app_log_failures(qa_log, '', '', smoke.RELEASE_PACKAGE))
        self.assertEqual(['anr'], smoke.app_log_failures('', '', f'am_anr: [0,123,{smoke.RELEASE_PACKAGE},0]', smoke.RELEASE_PACKAGE))
        self.assertEqual(['process_crash'], smoke.app_log_failures('', '', f'am_crash: [0,123,{smoke.RELEASE_PACKAGE},0]', smoke.RELEASE_PACKAGE))
        self.assertEqual(['native_crash'], smoke.app_log_failures('', f'>>> {smoke.RELEASE_PACKAGE} <<<', '', smoke.RELEASE_PACKAGE))

    def test_qa_home_cannot_satisfy_stable_home_retention_by_prefix(self):
        runner = smoke.Smoke(self.args())
        runner.save = Mock()
        runner.shell = Mock(side_effect=[
            'versionCode=14 minSdk=37\nversionName=1.0.13\nfirstInstallTime=1\nlastUpdateTime=2',
            smoke.PACKAGE + '.debug', smoke.PACKAGE + '.debug/app.lawnchair.LawnchairLauncher'])
        with self.assertRaisesRegex(RuntimeError, 'retained default HOME'):
            runner.metadata('candidate')


if __name__ == "__main__":
    unittest.main()

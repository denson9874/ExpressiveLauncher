"""Regression coverage for the crash gate's distinction between warnings and failures."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("smoke_qa", Path(__file__).parents[1] / "smoke_qa.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class AppCrashGateTests(unittest.TestCase):
    def test_launcher_runtime_fatal_blocks(self):
        log = "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: dev.launcher.expressive.l3.debug, PID: 123\n"
        self.assertEqual(smoke.app_log_failures(log, "", ""), ["fatal_exception"])

    def test_crash_buffer_alone_blocks(self):
        crash = "FATAL EXCEPTION: main\nProcess: dev.launcher.expressive.l3.debug, PID: 123"
        self.assertEqual(smoke.app_log_failures("", crash, ""), ["fatal_exception"])

    def test_anr_event_blocks_without_main_log_message(self):
        events = "I am_anr: [0,123,dev.launcher.expressive.l3.debug,0,Input dispatching timed out]"
        self.assertEqual(smoke.app_log_failures("", "", events), ["anr"])

    def test_process_crash_event_blocks(self):
        events = "I am_crash: [0,123,dev.launcher.expressive.l3.debug,0,java.lang.RuntimeException]"
        self.assertEqual(smoke.app_log_failures("", "", events), ["process_crash"])

    def test_other_package_crash_does_not_claim_launcher_crash(self):
        log = "FATAL EXCEPTION: main\nProcess: com.example.unrelated, PID: 456"
        self.assertEqual(smoke.app_log_failures(log, "", ""), [])

    def test_native_tombstone_blocks(self):
        crash = "F DEBUG: pid: 123, tid: 123, name: launcher >>> dev.launcher.expressive.l3.debug <<<"
        self.assertEqual(smoke.app_log_failures("", crash, ""), ["native_crash"])

    def test_caught_launcher_warning_is_not_a_fatal(self):
        log = "E SystemUiProxy: dev.launcher.expressive.l3.debug recent-tasks unavailable\nE Snapshot: caught java.lang.NullPointerException"
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


if __name__ == "__main__":
    unittest.main()

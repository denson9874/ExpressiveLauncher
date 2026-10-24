"""Regression coverage for the crash gate's distinction between warnings and failures."""
import importlib.util
import argparse
from pathlib import Path
import tempfile
import xml.etree.ElementTree as ET
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
        self.assertEqual(12, len(runner.required_flows))
        self.assertIn('relaunch_dpad', runner.required_flows)
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


class RelaunchDpadTests(unittest.TestCase):
    """D01/L03/L01: Home must survive in-process relaunches live, focusable and leak-free."""

    PID = "4242"
    WORKSPACE = '<node resource-id="{p}:id/workspace" bounds="[0,200][1000,2000]"/>'
    # Shaped like a real relaunch-dpad capture: hotseat icons in one child, the search bar
    # (described "Search", no id) in another, with focus on a View deep inside it.
    BAR_BOUNDS = [91, 2674, 1253, 2830]
    DOCK = ('<hierarchy><node resource-id="{p}:id/search_container_workspace" content-desc="Search" '
            'focused="{workspace}" bounds="[24,183][1320,524]"/>'
            '<node resource-id="{p}:id/hotseat" focused="false" bounds="[0,2306][1344,2992]">'
            '<node bounds="[58,2306][1286,2648]"><node class="android.widget.TextView" content-desc="Phone" '
            'focused="{icon}" bounds="[60,2306][305,2648]"/></node>'
            '<node content-desc="Search" focused="false" bounds="[91,2656][1253,2848]">'
            '<node bounds="[91,2674][1253,2830]"><node focused="{bar}" bounds="[91,2674][1253,2830]">'
            '<node content-desc="Voice Search" focused="false" bounds="[1097,2704][1205,2800]"/>'
            '</node></node></node></node></hierarchy>')

    def dock(self, focus=None):
        """A Home capture with focus on the dock search bar, a dock icon, the workspace bar, or nothing."""
        flags = {key: str(focus == key).lower() for key in ("bar", "icon", "workspace")}
        return ET.fromstring(self.DOCK.format(p=smoke.PACKAGE, **flags))

    def test_flow_is_required_for_upgrade_and_stable_bootstrap_smoke(self):
        self.assertIn("relaunch_dpad", smoke.REQUIRED_FLOWS)
        self.assertIn("relaunch_dpad", smoke.BOOTSTRAP_FLOWS)

    def test_relaunches_count_only_home_relaunches_after_the_toggle(self):
        logs = "\n".join([
            "--------- beginning of main",
            "1792809000.100  4242  4242 I ActivityThread: Schedule relaunch activity: app.lawnchair.LawnchairLauncher",
            "1792809010.500  4242  4242 I ActivityThread: Schedule relaunch activity: com.android.quickstep.RecentsActivity",
            "1792809010.600  4242  4242 I ActivityThread: Schedule relaunch activity: app.lawnchair.LawnchairLauncher",
        ])
        self.assertEqual(2, smoke.relaunches_since(logs, 1792808999.0))
        self.assertEqual(1, smoke.relaunches_since(logs, 1792809005.0))
        self.assertEqual(0, smoke.relaunches_since(logs, 1792809010.6))

    def test_dropbox_counts_crashes_of_exactly_this_package(self):
        dump = (f"2026-10-23 21:24:03 data_app_crash (text, 3665 bytes)\nProcess: {smoke.PACKAGE}\nPID: 1\n"
                f"2026-10-23 21:25:03 data_app_crash (text, 3665 bytes)\nProcess: {smoke.PACKAGE}.debug\nPID: 2\n"
                f"Process: {smoke.PACKAGE}:worker\n")
        self.assertEqual(1, smoke.dropbox_crashes(dump, smoke.PACKAGE))
        self.assertEqual(1, smoke.dropbox_crashes(dump, smoke.PACKAGE + ".debug"))

    def test_meminfo_reports_the_objects_activity_count(self):
        dump = " Objects\n               Views:      367         ViewRootImpl:        1\n" \
               "         AppContexts:       17           Activities:        5\n"
        self.assertEqual(5, smoke.meminfo_activities(dump))
        with self.assertRaisesRegex(RuntimeError, "exactly one Activities"):
            smoke.meminfo_activities("No process found for: " + smoke.PACKAGE)

    def test_dock_search_focus_is_only_focus_inside_the_dock_search_bar(self):
        focused = smoke.dock_search_focus(self.dock("bar"))
        self.assertIsNotNone(focused)
        self.assertEqual(self.BAR_BOUNDS, list(smoke.bounds(focused)))
        # A focused dock icon, the workspace search bar (also "Search"), or no focus at all.
        for elsewhere in ("icon", "workspace", None):
            with self.subTest(focus=elsewhere):
                self.assertIsNone(smoke.dock_search_focus(self.dock(elsewhere)))
        self.assertIsNone(smoke.dock_search_focus(ET.fromstring("<hierarchy/>")))

    def runner(self):
        runner = smoke.Smoke.__new__(smoke.Smoke)
        runner.package = smoke.PACKAGE
        runner.result = {"flows": {}, "evidence": []}
        runner.save = Mock()
        return runner

    def test_toggle_waits_for_this_toggles_relaunch_within_a_bound(self):
        runner = self.runner()
        late = f"1792809020.000  {self.PID}  {self.PID} I ActivityThread: {smoke.RELAUNCH_LOG}"
        runner.shell = Mock(side_effect=["1792809010.000000000", "", "Status: ok"])
        runner.adb = Mock(side_effect=["", late])
        with patch.object(smoke.time, "sleep"):
            runner.toggle_night("yes", self.PID, "cycle")
        runner.shell.assert_any_call("cmd", "uimode", "night", "yes")
        self.assertEqual(2, runner.adb.call_count)
        self.assertIn(f"--pid={self.PID}", runner.adb.call_args.args)

    def test_toggle_without_a_relaunch_fails_and_keeps_the_log(self):
        runner = self.runner()
        stale = f"1792809000.000  {self.PID}  {self.PID} I ActivityThread: {smoke.RELAUNCH_LOG}"
        runner.shell = Mock(side_effect=["1792809010.000000000", ""])
        runner.adb = Mock(return_value=stale)
        now = [0.0]
        with patch.object(smoke.time, "monotonic", side_effect=lambda: now[0]), \
             patch.object(smoke.time, "sleep", side_effect=lambda delay: now.__setitem__(0, now[0] + delay)):
            with self.assertRaisesRegex(RuntimeError, "did not relaunch in-process within 2s"):
                runner.toggle_night("yes", self.PID, "cycle", timeout=2)
        runner.save.assert_called_once_with("cycle-relaunch-timeout.txt", stale)

    def test_running_activities_counts_each_record_of_exactly_this_package(self):
        dump = (f"    * Hist  #0: ActivityRecord{{2588 u0 {smoke.PACKAGE}/app.lawnchair.LawnchairLauncher t89}}\n"
                f"          * ActivityRecord{{2588 u0 {smoke.PACKAGE}/app.lawnchair.LawnchairLauncher t89}}\n"
                f"    * Hist  #0: ActivityRecord{{1609 u0 {smoke.PACKAGE}/app.lawnchair.ui.preferences.PreferenceActivity t90}}\n"
                f"    * Hist  #0: ActivityRecord{{7777 u0 {smoke.PACKAGE}.debug/app.lawnchair.LawnchairLauncher t91}}\n")
        self.assertEqual(2, smoke.running_activities(dump, smoke.PACKAGE))
        self.assertEqual(1, smoke.running_activities(dump, smoke.PACKAGE + ".debug"))

    def test_activity_count_retries_while_a_destroyed_activity_is_finalized(self):
        runner = self.runner()
        meminfo = "         AppContexts:        9           Activities:        {}\n"
        home = f"ActivityRecord{{2588 u0 {smoke.PACKAGE}/app.lawnchair.LawnchairLauncher t89}}"
        runner.shell = Mock(side_effect=[home, meminfo.format(2), home, meminfo.format(1)])
        with patch.object(smoke.time, "sleep"):
            self.assertEqual((1, 1, 2), runner.activity_count("leak"))
        runner.shell = Mock(side_effect=lambda *args, **kwargs: meminfo.format(5) if "meminfo" in args else home)
        with patch.object(smoke.time, "sleep"):
            self.assertEqual((5, 1, 3), runner.activity_count("leak"))

    def flow_runner(self, crashes=(0, 0), activities=1, running=1, pids=None, typed_clear=True, dpad=("bar",)):
        """`dpad` is the focus each D-pad capture shows in turn; the last one repeats."""
        runner = self.runner()
        p = runner.package
        home = ET.fromstring(f"<hierarchy>{self.WORKSPACE.format(p=p)}</hierarchy>")
        drawer = ET.fromstring(f'<hierarchy><node resource-id="{p}:id/apps_view" bounds="[0,0][1000,2000]"/>'
                               f'<node resource-id="{p}:id/search_container_all_apps" bounds="[0,100][1000,200]"/></hierarchy>')
        focused = ET.fromstring(f'<hierarchy><node resource-id="{p}:id/input" bounds="[0,100][1000,200]"/></hierarchy>')
        clear = '<node content-desc="Clear search box" bounds="[900,100][1000,200]"/>' if typed_clear else ""
        typed = ET.fromstring(f"<hierarchy>{clear}</hierarchy>")
        captures = {"relaunch-drawer": drawer, "relaunch-search-focused": focused}
        dpad_roots, last = iter([self.dock(focus) for focus in dpad]), []

        def capture(name):
            if name.startswith("relaunch-dpad"):
                last[:] = [next(dpad_roots, last[0] if last else None)]
                return last[0]
            return captures.get(name, typed)
        runner.capture = Mock(side_effect=capture)
        runner.home = Mock(return_value=home)
        runner.night_mode = Mock(return_value="no")
        runner.app_crashes = Mock(side_effect=list(crashes))
        runner.toggle_night = Mock()
        runner.front_home = Mock()
        runner.metadata = Mock()
        runner.tap = Mock()
        runner.wait_for_keyboard = Mock()
        runner.activity_count = Mock(return_value=(activities, running, 1))
        pid_answers = iter(pids or [self.PID] * 3)
        runner.shell = Mock(side_effect=lambda *args, **kwargs: next(pid_answers) if args[0] == "pidof" else "")
        return runner

    def test_relaunch_dpad_cycles_night_mode_presses_dpad_and_records_the_flow(self):
        runner = self.flow_runner()
        with patch.object(smoke.time, "sleep"):
            runner.relaunch_dpad()
        self.assertEqual([c.args[0] for c in runner.toggle_night.call_args_list], ["yes", "no", "yes", "no"])
        self.assertTrue(all(c.args[1] == self.PID for c in runner.toggle_night.call_args_list))
        presses = [c for c in runner.shell.call_args_list if c.args == ("input", "keyevent", "KEYCODE_DPAD_DOWN")]
        self.assertEqual(smoke.DPAD_PRESSES, len(presses))
        runner.shell.assert_any_call("input", "text", "Cal")
        runner.metadata.assert_called_once_with("relaunch")
        flow = runner.result["flows"]["relaunch_dpad"]
        self.assertEqual((True, 4, 1, self.PID), (flow["passed"], flow["relaunches"], flow["activities"], flow["pid"]))
        self.assertEqual((smoke.DPAD_PRESSES, self.BAR_BOUNDS), (flow["dpadDown"], flow["focusedBounds"]))
        self.assertEqual(["relaunch-dpad"], self.dpad_captures(runner))
        runner.shell.assert_called_with("cmd", "uimode", "night", "no", check=False)

    @staticmethod
    def dpad_captures(runner):
        return [c.args[0] for c in runner.capture.call_args_list if c.args[0].startswith("relaunch-dpad")]

    @staticmethod
    def dpad_presses(runner):
        return sum(c.args == ("input", "keyevent", "KEYCODE_DPAD_DOWN") for c in runner.shell.call_args_list)

    def test_focus_short_of_the_dock_search_bar_presses_again_until_it_arrives(self):
        runner = self.flow_runner(dpad=(None, "icon", "bar"))
        with patch.object(smoke.time, "sleep"):
            runner.relaunch_dpad()
        self.assertEqual(smoke.DPAD_PRESSES + 2, self.dpad_presses(runner))
        first = smoke.DPAD_PRESSES + 1
        self.assertEqual(["relaunch-dpad", f"relaunch-dpad-{first}", f"relaunch-dpad-{first + 1}"],
                         self.dpad_captures(runner))
        flow = runner.result["flows"]["relaunch_dpad"]
        self.assertEqual((smoke.DPAD_PRESSES + 2, self.BAR_BOUNDS), (flow["dpadDown"], flow["focusedBounds"]))

    def test_focus_that_never_reaches_the_dock_search_bar_fails_after_the_limit(self):
        for elsewhere in ("icon", "workspace", None):
            with self.subTest(focus=elsewhere):
                runner = self.flow_runner(dpad=(elsewhere,))
                limit = smoke.DPAD_PRESSES + smoke.DPAD_FOCUS_RETRIES
                with patch.object(smoke.time, "sleep"):
                    with self.assertRaisesRegex(RuntimeError, f"did not reach the dock search bar after {limit} DPAD_DOWN"):
                        runner.relaunch_dpad()
                self.assertEqual(limit, self.dpad_presses(runner))
                self.assertEqual(1 + smoke.DPAD_FOCUS_RETRIES, len(self.dpad_captures(runner)))
                self.assertNotIn("relaunch_dpad", runner.result["flows"])
                runner.metadata.assert_not_called()
                runner.shell.assert_called_with("cmd", "uimode", "night", "no", check=False)

    def test_a_crash_is_reported_before_missing_focus(self):
        runner = self.flow_runner(crashes=(0, 1), dpad=(None,))
        with patch.object(smoke.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, r"D-pad focus .*\(1 new crash"):
                runner.relaunch_dpad()

    def test_new_crash_after_dpad_fails_and_restores_night_mode(self):
        runner = self.flow_runner(crashes=(1, 2))
        with patch.object(smoke.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, r"D-pad focus .*\(1 new crash"):
                runner.relaunch_dpad()
        self.assertNotIn("relaunch_dpad", runner.result["flows"])
        runner.shell.assert_called_with("cmd", "uimode", "night", "no", check=False)

    def test_process_restart_is_not_an_in_process_relaunch(self):
        runner = self.flow_runner(pids=[self.PID, "5151"])
        with patch.object(smoke.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "restarted instead of relaunching"):
                runner.relaunch_dpad()

    def test_frozen_search_bar_without_clear_button_fails(self):
        runner = self.flow_runner(typed_clear=False)
        with patch.object(smoke.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "desc=Clear search box"):
                runner.relaunch_dpad()

    def test_leaked_activities_fail(self):
        runner = self.flow_runner(activities=5)
        with patch.object(smoke.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, r"kept 4 destroyed Activities after 4 relaunches \(5 in memory, 1 running\)"):
                runner.relaunch_dpad()

    def test_a_running_settings_screen_is_not_a_leak(self):
        runner = self.flow_runner(activities=2, running=2)
        with patch.object(smoke.time, "sleep"):
            runner.relaunch_dpad()
        self.assertEqual(2, runner.result["flows"]["relaunch_dpad"]["runningActivities"])


class InstallRetryTests(unittest.TestCase):
    def test_install_succeeds_first_try(self):
        runner = smoke.Smoke.__new__(smoke.Smoke)
        runner.adb = Mock(return_value="Performing Streamed Install\nSuccess")
        res = runner.install_apk(Path("/fake/test.apk"))
        self.assertIn("Success", res)
        self.assertEqual(runner.adb.call_count, 1)

    def test_install_retries_on_transient_failure_and_succeeds(self):
        runner = smoke.Smoke.__new__(smoke.Smoke)
        runner.adb = Mock(side_effect=[
            RuntimeError("Command failed (1): adb: device offline"),
            "Performing Streamed Install\nSuccess"
        ])
        with patch.object(smoke.time, "sleep"):
            res = runner.install_apk(Path("/fake/test.apk"))
        self.assertIn("Success", res)
        self.assertEqual(runner.adb.call_count, 2)

    def test_install_fails_after_max_attempts(self):
        runner = smoke.Smoke.__new__(smoke.Smoke)
        runner.adb = Mock(side_effect=RuntimeError("Command failed (1): adb: device offline"))
        with patch.object(smoke.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "Install failed for .* after 3 attempts"):
                runner.install_apk(Path("/fake/test.apk"))
        self.assertEqual(runner.adb.call_count, 3)


if __name__ == "__main__":
    unittest.main()

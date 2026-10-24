#!/usr/bin/env python3
"""Run bounded, artifact-specific QA on a fresh, exclusively owned Android AVD.

This validates a quiesced ADB upgrade, not Drive download or the system installer.
Every run retains its own AVD and evidence; it never wipes an existing AVD.
"""
from __future__ import annotations

import sys

if sys.version_info < (3, 11):
    sys.exit("Python 3.11+ is required (this is %d.%d); run it with /opt/homebrew/bin/python3.14" % sys.version_info[:2])

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "dev.launcher.expressive.l3"
RELEASE_PACKAGE = "dev.launcher.expressive.l3"
ROLE = "android.app.role.HOME"
REQUIRED_FLOWS = {
    "guest_identity", "baseline_install", "seed_preference", "same_signer_upgrade",
    "preference_retention", "warm_start", "cold_start", "drawer_swipe",
    "local_search", "relaunch_dpad", "current_date_handoff", "clean_app_logs",
}
BOOTSTRAP_FLOWS = (REQUIRED_FLOWS - {'baseline_install', 'same_signer_upgrade'}) | {'first_stable_install', 'same_version_reinstall'}
# relaunch_dpad (D01/L03/L01): dark mode on and off twice relaunches Home in-process four times
# with its window kept; D-pad focus then reaches the dock search bar.
NIGHT_CYCLES = 2
DPAD_PRESSES = 6
# Extra DPAD_DOWN presses, one capture each, while focus has not reached the dock search bar yet.
DPAD_FOCUS_RETRIES = 4
# The dock search bar (search_container_hotseat) has no id; it is the hotseat child described as
# @string/label_search. Hotseat app icons sit in a separate child, so their focus never matches.
DOCK_SEARCH_DESC = "Search"
RELAUNCH_LOG = "Schedule relaunch activity: app.lawnchair.LawnchairLauncher"
CLEAR_SEARCH = "Clear search box"
CRASH_TAGS = ("data_app_crash", "data_app_native_crash")


def relaunches_since(logs: str, since: float) -> int:
    """Count Home relaunches in `logcat -v epoch` output logged after the device epoch `since`."""
    count = 0
    for line in logs.splitlines():
        stamp = re.match(r"\s*(\d+\.\d+)\s", line)
        if stamp and float(stamp[1]) > since and RELAUNCH_LOG in line:
            count += 1
    return count


def dock_search_focus(root: ET.Element) -> ET.Element | None:
    """The focused node inside the dock (hotseat) search bar, or None while focus is elsewhere."""
    for hotseat in root.iter("node"):
        if not hotseat.get("resource-id", "").endswith(":id/hotseat"):
            continue
        for bar in hotseat.findall("node"):
            if bar.get("content-desc") == DOCK_SEARCH_DESC:
                for node in bar.iter("node"):
                    if node.get("focused") == "true":
                        return node
    return None


def dropbox_crashes(dump: str, package: str) -> int:
    """Count crash records in `dumpsys dropbox --print` output for exactly this package."""
    return len(re.findall(r"(?m)^Process: " + re.escape(package) + r"\s*$", dump))


def meminfo_activities(dump: str) -> int:
    """Activity objects in the heap from `dumpsys meminfo <package>`, which ART counts after a GC."""
    counts = re.findall(r"\bActivities:\s+(\d+)\b", dump)
    if len(counts) != 1:
        raise RuntimeError("dumpsys meminfo did not report exactly one Activities count")
    return int(counts[0])


def running_activities(dump: str, package: str) -> int:
    """Activities of exactly this package that `dumpsys activity activities` still tracks."""
    return len(set(re.findall(r"ActivityRecord\{(\w+) u\d+ " + re.escape(package) + r"/", dump)))


def keyboard_ready(dump: str, package: str) -> bool:
    """Require the current user's visible IME and its active editor, never dump history."""
    manager = dump.split("\nInput method client state for ", 1)[0]
    current = re.search(r"(?m)^\s*mCurrentImeUserId=(\d+)\s*$", manager)
    if current is None:
        return False
    user = re.search(r"(?ms)^  UserId=" + current[1] + r"\n(.*?)(?=^  UserId=|\Z)", manager)
    if user is None:
        return False
    state = user[1].split("\n    Input Methods:", 1)[0]
    visibility = re.findall(r"(?m)^\s*mImeWindowVis=(0x[0-9a-fA-F]+|\d+)\s*$", state)
    shown = re.findall(r"(?m)^\s*mInputShown=(true|false)\s*$", state)
    if len(visibility) != 1 or shown != ["true"] or int(visibility[0], 0) & 3 != 3:
        return False
    service = dump.partition("\nInput method service state for ")[2]
    lifecycle, separator, editor = service.partition("\n  mInputEditorInfo:")
    if not separator:
        return False
    for field in ("mDecorViewVisible", "mWindowVisible", "mInputStarted", "mInputViewStarted"):
        if re.findall(r"\b" + field + r"=(true|false)\b", lifecycle) != ["true"]:
            return False
    editor = editor.split("\n  mShowInputRequested=", 1)[0]
    return re.findall(r"(?m)^\s*packageName=(\S+)", editor) == [package]


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def bounds(node: ET.Element) -> tuple[int, int, int, int]:
    values = tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
        raise RuntimeError(f"Missing/empty UI bounds: {node.attrib}")
    return values


def app_log_failures(logs: str, crash: str, events: str, package: str = PACKAGE) -> list[str]:
    """Recognize process-specific fatal/ANR records while retaining nonfatal warnings."""
    combined = logs + "\n" + crash
    failures = []
    exact_package = re.escape(package) + r'(?=[:,\s]|$)'
    if re.search(r"FATAL EXCEPTION[\s\S]{0,1500}?Process: " + exact_package, combined):
        failures.append("fatal_exception")
    if re.search(r"(?:ANR in |am_anr[^\n]*)" + exact_package, logs + "\n" + events):
        failures.append("anr")
    if re.search(r"am_crash[^\n]*" + exact_package, events):
        failures.append("process_crash")
    if re.search(r">>> " + re.escape(package) + r"(?::[^ ]+)? <<<", combined):
        failures.append("native_crash")
    return failures


class Smoke:
    def __init__(self, args):
        self.args = args
        self.channel = getattr(args, 'channel', 'qa')
        self.bootstrap = getattr(args, 'bootstrap_stable', False)
        if self.channel not in ('qa', 'release') or (self.bootstrap and self.channel != 'release'):
            raise ValueError('Stable bootstrap requires the release channel')
        if self.bootstrap and args.baseline_apk is not None:
            raise ValueError('First stable install must not claim a prior baseline')
        if not self.bootstrap and args.baseline_apk is None:
            raise ValueError('A delivered baseline is required for upgrade QA')
        self.package = PACKAGE if self.channel == 'qa' else RELEASE_PACKAGE
        self.install_baseline = args.apk if self.bootstrap else args.baseline_apk
        self.required_flows = BOOTSTRAP_FLOWS if self.bootstrap else REQUIRED_FLOWS
        self.out = args.output_dir.resolve()
        self.out.mkdir(parents=True, exist_ok=False)
        self.sdk = args.android_home.resolve()
        self.adb_path = self.sdk / "platform-tools/adb"
        self.serial = "emulator-5580"
        self.proc = None
        self.avd_name = "Expressive_CI_" + time.strftime("%Y%m%d_%H%M%S_") + uuid.uuid4().hex[:8]
        self.result = {
            "schemaVersion": 1, "passed": False,
            "channel": self.channel, "packageName": self.package,
            "sourceRevision": args.source_revision,
            "apk": str(args.apk.resolve()), "apkSha256": digest(args.apk),
            "sha256": digest(args.apk),
            "baselineApk": None if self.bootstrap else str(args.baseline_apk.resolve()),
            "baselineSha256": None if self.bootstrap else digest(args.baseline_apk),
            "avd": self.avd_name, "serial": self.serial,
            "flows": {}, "evidence": [],
            "scope": "first-stable-install-and-reinstall-smoke" if self.bootstrap else "quiesced-adb-upgrade-smoke",
            "limitations": [
                "Does not test live updater download, notifications, or installer UI.",
                "No physical device, privileged Quickstep, widgets, rotation, or midnight rollover coverage.",
            ],
        }
        if self.channel == 'release':
            self.result.update(channel='release', packageName=self.package,
                               baselineMode='first-stable-install' if self.bootstrap else 'quiesced-upgrade',
                               stableBootstrap=self.bootstrap)
            if self.bootstrap:
                self.result['baseline'] = None
                self.result['limitations'].append('No previously shipped stable version exists; same-version reinstall is not an upgrade test.')

    def save(self, name, value):
        p = self.out / name
        p.write_bytes(value if isinstance(value, bytes) else value.encode())
        if name not in self.result["evidence"]:
            self.result["evidence"].append(name)
        return p

    def command(self, argv, timeout=45, check=True, binary=False):
        with (self.out / "commands.log").open("a") as log:
            log.write(json.dumps([str(x) for x in argv]) + "\n")
        proc = subprocess.run([str(x) for x in argv], capture_output=True, timeout=timeout)
        if check and proc.returncode:
            raise RuntimeError(f"Command failed ({proc.returncode}): {argv}\n{proc.stderr.decode(errors='replace')}\n{proc.stdout.decode(errors='replace')}")
        return proc.stdout if binary else proc.stdout.decode(errors="replace").strip()

    def adb(self, *args, **kwargs):
        return self.command([self.adb_path, "-s", self.serial, *args], **kwargs)

    def shell(self, *args, **kwargs):
        return self.adb("shell", *args, **kwargs)

    def require(self, condition, message):
        if not condition:
            raise RuntimeError(message)

    def passed(self, flow, **data):
        self.result["flows"][flow] = {"passed": True, **data}
        print(f"PASS {flow}", flush=True)

    def start(self):
        # Bind probes and adb checks fail closed before allocating our own AVD.
        for port in (5580, 5581):
            with socket.socket() as probe:
                # Permit the previous owned run's TIME_WAIT sockets, not a live listener.
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(("127.0.0.1", port))
        devices = self.command([self.adb_path, "devices"])
        self.require(self.serial not in devices, "Port 5580 already belongs to an emulator")
        avd_base = Path(os.environ.get("ANDROID_AVD_HOME", str(Path.home() / ".android/avd")))
        template = avd_base / f"{self.args.avd_template}.avd/config.ini"
        config = dict(line.split("=", 1) for line in template.read_text().splitlines() if "=" in line)
        image = self.sdk / config["image.sysdir.1"]
        self.require(image.is_dir(), f"Missing pinned system image: {image}")
        self.save("image-source.properties", (image / "source.properties").read_text())
        owned = avd_base / f"{self.avd_name}.avd"
        owned.mkdir()
        config.update({"AvdId": self.avd_name, "avd.ini.displayname": self.avd_name,
                       "fastboot.forceColdBoot": "yes", "fastboot.forceFastBoot": "no"})
        # Only the static hardware/image config is copied. No snapshots/userdata are copied.
        for key in list(config):
            if key.startswith("disk.dataPartition.path") or key in ("sdcard.path", "snapshot.present"):
                del config[key]
        (owned / "config.ini").write_text("\n".join(f"{k}={v}" for k, v in config.items()) + "\n")
        (avd_base / f"{self.avd_name}.ini").write_text(
            f"avd.ini.encoding=UTF-8\npath={owned}\ntarget={config['target']}\n")
        self.result["avdPath"] = str(owned)
        emulator = self.sdk / "emulator/emulator"
        self.save("emulator-version.txt", self.command([emulator, "-version"]))
        emulog = (self.out / "emulator.log").open("wb")
        self.proc = subprocess.Popen([str(emulator), "-avd", self.avd_name, "-port", "5580",
                                      "-no-window", "-no-audio", "-no-snapshot", "-gpu", self.args.gpu],
                                     stdout=emulog, stderr=subprocess.STDOUT)
        emulog.close()
        print(f"Booting {self.avd_name}", flush=True)
        deadline = time.monotonic() + 360
        while time.monotonic() < deadline:
            self.require(self.proc.poll() is None, "Owned emulator exited during boot; see emulator.log")
            if self.shell("getprop", "sys.boot_completed", check=False, timeout=10) == "1":
                if self.shell("pm", "path", "android", check=False, timeout=10).startswith("package:"):
                    break
            time.sleep(3)
        else:
            raise RuntimeError("Emulator boot exceeded 360 seconds")
        self.require(self.adb("emu", "avd", "name").splitlines()[0] == self.avd_name,
                     "Emulator identity changed; refusing to interact")
        build = self.shell("getprop", "ro.build.id")
        sdk = self.shell("getprop", "ro.build.version.sdk")
        fingerprint = self.shell("getprop", "ro.build.fingerprint")
        self.save("guest-properties.txt", self.shell("getprop"))
        self.result.update({"guestBuild": build, "guestSdk": int(sdk), "guestFingerprint": fingerprint})
        self.require(build == self.args.expected_build and sdk == "37", f"Unexpected guest: {build} SDK {sdk}")
        self.shell("input", "keyevent", "82")
        self.passed("guest_identity", build=build, sdk=int(sdk))

    def capture(self, name):
        # App transitions can briefly expose no accessibility root. Retry snapshots
        # only, never the user action itself, and preserve every unsuccessful dump.
        for attempt in range(1, 4):
            raw = self.adb("exec-out", "uiautomator", "dump", "/dev/tty", timeout=45)
            start, end = raw.find("<?xml"), raw.find("</hierarchy>")
            if start >= 0 and end >= start:
                break
            self.save(f"{name}-dump-attempt-{attempt}.txt", raw)
            if attempt < 3:
                time.sleep(attempt * 2)
        else:
            raise RuntimeError(f"No UI hierarchy for {name} after 3 snapshots: {raw}")
        # UIAutomator sometimes appends its status line after XML.
        data = raw[start:end + len("</hierarchy>")]
        self.save(f"{name}.xml", data)
        self.save(f"{name}.png", self.adb("exec-out", "screencap", "-p", binary=True))
        return ET.fromstring(data)

    def node(self, root, *, text=None, rid=None, desc=None):
        candidates = [n for n in root.iter("node") if
                      (text is None or n.get("text") == text) and
                      (rid is None or n.get("resource-id", "").endswith(":" + "id/" + rid)) and
                      (desc is None or n.get("content-desc") == desc)]
        self.require(bool(candidates), f"UI target missing: text={text} id={rid} desc={desc}")
        return candidates[0]

    def swipe_to_date(self, root, max_pages=4):
        """At a Glance may open on another card (e.g. "Tap to set up") on any page; swipe its pager
        forward, then back, until the date card shows. Returns the node and the swipes it took."""
        swipes = 0
        for forward in (True, False):
            for _ in range(max_pages):
                dates = [n for n in root.iter("node") if n.get("resource-id", "").endswith(":id/date")]
                if dates:
                    return dates[0], swipes
                pager = self.node(root, rid="smartspace_card_pager")
                x1, y1, x2, y2 = bounds(pager)
                y = (y1 + y2) // 2
                # The QA emulator is left-to-right: the next card is to the right.
                right, left = x1 + (x2 - x1) * 4 // 5, x1 + (x2 - x1) // 5
                start_x, end_x = (right, left) if forward else (left, right)
                self.shell("input", "swipe", str(start_x), str(y), str(end_x), str(y), "300")
                time.sleep(1)
                swipes += 1
                root = self.capture(f"candidate-date-home-swipe-{swipes}")
        self.require(False, f"At a Glance date card not found within {max_pages} pages either way")

    def tap(self, node):
        x1, y1, x2, y2 = bounds(node)
        self.shell("input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(1)

    def wait_for_keyboard(self, name, timeout=30):
        deadline = time.monotonic() + timeout
        attempt, last_dump = 0, ""
        while time.monotonic() < deadline:
            attempt += 1
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            try:
                last_dump = self.shell("dumpsys", "-t", "3", "input_method", timeout=min(5, remaining))
            except subprocess.TimeoutExpired:
                last_dump = "Input method dump timed out before keyboard readiness was established."
            self.save(f"{name}-ime-attempt-{attempt}.txt", last_dump)
            if time.monotonic() < deadline and keyboard_ready(last_dump, self.package):
                self.save(f"{name}-ime-ready.txt", last_dump)
                return
            time.sleep(min(1, max(0, deadline - time.monotonic())))
        self.save(f"{name}-ime-timeout.txt", last_dump)
        raise RuntimeError(f"Keyboard did not become visible for {self.package} within {timeout}s")

    def home(self, name):
        output = self.shell("am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
        self.save(f"{name}-launch.txt", output)
        self.require("Status: ok" in output, f"HOME launch failed: {output}")
        time.sleep(2)
        root = self.capture(name)
        self.node(root, rid="workspace")
        self.node(root, rid="folder_icon_name")
        return root

    def metadata(self, name):
        dump = self.shell("dumpsys", "package", self.package)
        self.save(name + "-package.txt", dump)
        data = {}
        for key in ("versionCode", "versionName", "firstInstallTime", "lastUpdateTime"):
            match = re.search(r"\b" + key + r"=([^\n]+)", dump)
            self.require(match is not None, f"Missing package field {key}")
            data[key] = match.group(1).split(" minSdk=")[0].strip()
        data["role"] = self.shell("cmd", "role", "get-role-holders", ROLE)
        data["resolver"] = self.shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
        self.save(name + "-metadata.json", json.dumps(data, indent=2))
        self.require(self.package in data["role"].splitlines() and
                     any(line.startswith(self.package + '/') for line in data['resolver'].splitlines()),
                     "Launcher is not the retained default HOME")
        return data

    def preferences(self, name):
        self.shell("am", "start", "-W", "-n", self.package + "/app.lawnchair.ui.preferences.PreferenceActivity")
        time.sleep(1)
        root = self.capture(name + "-root")
        self.tap(self.node(root, text="Home screen"))
        root = self.capture(name)
        label = self.node(root, text="Infinite scrolling")
        parents = {child: parent for parent in root.iter() for child in parent}
        switches = [n for n in parents[label].iter("node") if n.get("checkable") == "true"]
        self.require(len(switches) == 1, "Infinite scrolling switch missing or ambiguous")
        return root, switches[0]

    def device_epoch(self) -> float:
        return float(self.shell("date", "+%s.%N"))

    def night_mode(self) -> str:
        output = self.shell("cmd", "uimode", "night")
        match = re.search(r"Night mode: (\S+)", output)
        self.require(match is not None, f"Unreadable night mode: {output}")
        return match[1]

    def front_home(self):
        output = self.shell("am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
        self.require("Status: ok" in output, f"HOME launch failed: {output}")

    def toggle_night(self, mode, pid, name, timeout=15):
        """Switch dark mode and wait, bounded, until Home (still process `pid`) relaunches for it."""
        since = self.device_epoch()
        self.shell("cmd", "uimode", "night", mode)
        deadline = time.monotonic() + timeout
        logs = ""
        while time.monotonic() < deadline:
            logs = self.adb("logcat", "-d", "-v", "epoch", f"--pid={pid}", "-s", "ActivityThread:I")
            if relaunches_since(logs, since):
                break
            time.sleep(0.5)
        else:
            self.save(name + "-relaunch-timeout.txt", logs)
            raise RuntimeError(f"Home did not relaunch in-process within {timeout}s of night mode {mode}")
        # Return to Home as a user does after flipping the setting.
        self.front_home()
        time.sleep(1)

    def app_crashes(self, name) -> int:
        dump = "\n".join(self.shell("dumpsys", "dropbox", "--print", tag, timeout=60) for tag in CRASH_TAGS)
        self.save(name + "-dropbox.txt", dump)
        return dropbox_crashes(dump, self.package)

    def activity_count(self, name, attempts=3):
        """Activities in Home's heap and still running, retried because a finalizer can keep a
        destroyed one alive for one more GC. Any surplus is a destroyed, leaked Activity."""
        for attempt in range(1, attempts + 1):
            running = self.shell("dumpsys", "activity", "activities", timeout=60)
            dump = self.shell("dumpsys", "meminfo", self.package, timeout=60)
            self.save(f"{name}-activities-{attempt}.txt", running)
            self.save(f"{name}-meminfo-{attempt}.txt", dump)
            count, live = meminfo_activities(dump), running_activities(running, self.package)
            if count <= live or attempt == attempts:
                return count, live, attempt
            time.sleep(2)

    def relaunch_dpad(self):
        """D01/L03/L01: in-process relaunches must leave Home live, focusable and leak-free."""
        started = time.monotonic()
        original = self.night_mode()
        crashes = self.app_crashes("relaunch-before")
        root = self.home("relaunch-home")
        pid = self.shell("pidof", "-s", self.package, check=False)
        self.require(pid, "Home is not running before the relaunch cycles")
        try:
            if original != "no":
                # Start from light mode; this change may or may not relaunch Home.
                self.shell("cmd", "uimode", "night", "no")
                time.sleep(2)
                self.front_home()
            for cycle in range(1, NIGHT_CYCLES + 1):
                for mode in ("yes", "no"):
                    self.toggle_night(mode, pid, f"relaunch-{cycle}-{mode}")
            self.require(self.shell("pidof", "-s", self.package, check=False) == pid,
                         "Home restarted instead of relaunching in-process")
            # D-pad focus walks down to the dock search bar, whose focus ripple threw (D01).
            for _ in range(DPAD_PRESSES):
                self.shell("input", "keyevent", "KEYCODE_DPAD_DOWN")
                time.sleep(0.3)
            presses = DPAD_PRESSES
            time.sleep(2)
            focus = dock_search_focus(self.capture("relaunch-dpad"))
            # Focus resumes wherever Home last left it, so it may still be on its way down: press a
            # bounded few more times, capturing after each, and fail if it never reaches the bar.
            while focus is None and presses < DPAD_PRESSES + DPAD_FOCUS_RETRIES:
                self.shell("input", "keyevent", "KEYCODE_DPAD_DOWN")
                presses += 1
                time.sleep(1)
                focus = dock_search_focus(self.capture(f"relaunch-dpad-{presses}"))
            same_process = self.shell("pidof", "-s", self.package, check=False) == pid
            new_crashes = self.app_crashes("relaunch-after-dpad") - crashes
            self.require(same_process and new_crashes == 0,
                         f"Home crashed on D-pad focus after the relaunches ({new_crashes} new crash records)")
            self.require(focus is not None,
                         f"D-pad focus did not reach the dock search bar after {presses} DPAD_DOWN presses")
            focused_bounds = list(bounds(focus))
            self.metadata("relaunch")
            # Typing must recompose the drawer search bar: it shows its clear button (L03).
            x1, y1, x2, y2 = bounds(self.node(root, rid="workspace"))
            x = (x1 + x2) // 2
            for attempt in range(1, 4):
                self.shell("input", "swipe", str(x), str(y1 + (y2 - y1) * 3 // 4), str(x), str(y1 + (y2 - y1) * 15 // 100), "250")
                time.sleep(2)
                root = self.capture("relaunch-drawer")
                if any(n.get("resource-id", "").endswith(":id/apps_view") for n in root.iter("node")):
                    break
            self.tap(self.node(root, rid="search_container_all_apps"))
            root = self.capture("relaunch-search-focused")
            self.tap(self.node(root, rid="input"))
            self.wait_for_keyboard("relaunch-search")
            self.shell("input", "text", "Cal")
            for attempt in range(1, 4):
                time.sleep(1)
                root = self.capture(f"relaunch-search-typed-{attempt}")
                if any(n.get("content-desc") == CLEAR_SEARCH for n in root.iter("node")):
                    break
            self.node(root, desc=CLEAR_SEARCH)
            self.front_home()
            # A smoke run has only Home running here (1 Activity); count against what is running
            # so a Settings screen left in the background is not mistaken for a leak.
            activities, running, attempts = self.activity_count("relaunch")
            self.require(activities <= running,
                         f"Home kept {activities - running} destroyed Activities after {2 * NIGHT_CYCLES} "
                         f"relaunches ({activities} in memory, {running} running)")
        finally:
            # Leave the guest as found, without hiding the flow's own failure.
            self.shell("cmd", "uimode", "night", original, check=False)
        self.passed("relaunch_dpad", relaunches=2 * NIGHT_CYCLES, dpadDown=presses, focusedBounds=focused_bounds,
                    pid=pid, activities=activities, runningActivities=running, meminfoAttempts=attempts,
                    nightMode=original, seconds=round(time.monotonic() - started, 1))

    def quiesce(self):
        self.shell("am", "start", "-W", "-a", "android.settings.SETTINGS")
        self.shell("am", "force-stop", self.package)
        self.require(not self.shell("pidof", self.package, check=False), "Launcher did not stop")

    def install_apk(self, path: Path, *flags, timeout: int = 180, attempts: int = 3) -> str:
        last_error = ""
        for attempt in range(1, attempts + 1):
            try:
                out = self.adb("install", *flags, str(path.resolve()), timeout=timeout)
                if "Success" in out:
                    return out
                last_error = out
            except RuntimeError as error:
                last_error = str(error)
            if attempt < attempts:
                time.sleep(attempt * 3)
        raise RuntimeError(f"Install failed for {path} after {attempts} attempts: {last_error}")

    def run(self):
        self.start()
        self.adb("logcat", "-c")
        self.install_apk(self.install_baseline)
        self.shell("cmd", "role", "add-role-holder", ROLE, self.package)
        self.shell("cmd", "package", "set-home-activity", self.package)
        self.home("baseline-home")
        before = self.metadata("baseline")
        self.passed("first_stable_install" if self.bootstrap else "baseline_install",
                    version=before["versionName"], versionCode=before["versionCode"])
        _, switch = self.preferences("baseline-preference")
        self.require(switch.get("checked") == "false", "Fresh baseline preference unexpectedly enabled")
        self.tap(switch)
        root = self.capture("baseline-preference-seeded")
        # Re-read through the normal screen to verify state was persisted before upgrading.
        self.home("baseline-seeded-home")
        _, switch = self.preferences("baseline-preference-reopened")
        self.require(switch.get("checked") == "true", "Preference did not persist before upgrade")
        self.passed("seed_preference", preference="Infinite scrolling", value=True)
        self.quiesce()
        self.install_apk(self.args.apk, "-r")
        after = self.metadata("candidate")
        self.require(before["firstInstallTime"] == after["firstInstallTime"], "Upgrade changed firstInstallTime")
        if self.bootstrap:
            self.require(after['versionCode'] == before['versionCode'] and after['versionName'] == before['versionName'],
                         'First stable reinstall must keep the same candidate version')
        else:
            self.require(int(after["versionCode"]) > int(before["versionCode"]), "Candidate version must advance baseline")
        installed = self.shell("pm", "path", self.package).splitlines()[0].removeprefix("package:")
        remote_digest = self.shell("sha256sum", installed).split()[0]
        self.require(remote_digest == self.result["apkSha256"], "Installed APK bytes differ from candidate")
        self.result["versionName"] = after["versionName"]
        self.result["versionCode"] = int(after["versionCode"])
        self.passed("same_version_reinstall" if self.bootstrap else "same_signer_upgrade",
                    firstInstallTime=after["firstInstallTime"], installedSha256=remote_digest)
        self.home("candidate-first-home")
        _, switch = self.preferences("candidate-preference-retained")
        self.require(switch.get("checked") == "true", "Seeded preference lost during upgrade")
        self.passed("preference_retention", preference="Infinite scrolling", value=True)
        pid = self.shell("pidof", "-s", self.package)
        self.home("candidate-warm-home")
        self.require(pid and self.shell("pidof", "-s", self.package) == pid, "Warm HOME recreated process")
        self.passed("warm_start", pid=pid)
        self.quiesce()
        root = self.home("candidate-cold-home")
        cold_pid = self.shell("pidof", "-s", self.package)
        self.require(cold_pid and cold_pid != pid, "Cold HOME did not create a new process")
        self.passed("cold_start", pid=cold_pid)
        x1, y1, x2, y2 = bounds(self.node(root, rid="workspace"))
        # Both endpoints are derived from the current workspace's actual bounds.
        x = (x1+x2)//2
        start_y, end_y = y1 + int((y2-y1)*0.75), y1 + int((y2-y1)*0.15)
        duration_ms = 250
        # A real user swipes once. Retries absorb emulator timing just after a cold start
        # (measured 2026-10-04: 9/10 first-swipe on swiftshader, 20/20 on host GPU), but the
        # attempt count is recorded so a launcher regression cannot hide behind them.
        for attempt in range(1, 4):
            self.shell("input", "swipe", str(x), str(start_y), str(x), str(end_y), str(duration_ms))
            time.sleep(2)
            root = self.capture("candidate-drawer")
            if any(n.get("resource-id", "").endswith(":id/apps_view") for n in root.iter("node")):
                break
            time.sleep(1)
        self.node(root, rid="apps_view")
        self.node(root, rid="search_container_all_apps")
        if attempt > 1:
            print(f"WARN drawer_swipe needed {attempt} swipes; inspect candidate-drawer evidence", flush=True)
        self.passed("drawer_swipe", path=[x, start_y, x, end_y], durationMs=duration_ms, attempts=attempt)
        self.tap(self.node(root, rid="search_container_all_apps"))
        root = self.capture("candidate-search-focused")
        # Opening the search surface can focus its editor without requesting the IME.
        # Activate the actual editor and wait before injecting any text into a cold keyboard.
        self.tap(self.node(root, rid="input"))
        self.wait_for_keyboard("candidate-search")
        self.shell("input", "text", "Calendar")
        time.sleep(2)
        root = self.capture("candidate-search-calendar")
        self.require(self.node(root, rid="input").get("text") == "Calendar", "Search query did not populate")
        result_list = self.node(root, rid="search_results_list_view")
        results = [n for n in result_list.iter("node") if n.get("text") == "Calendar"
                   and n.get("content-desc") == "Calendar" and n.get("class") == "android.widget.TextView"]
        self.require(results, "Local Calendar search returned no visible app result")
        self.passed("local_search", query="Calendar")
        self.relaunch_dpad()
        root = self.home("candidate-date-home")
        date, date_page = self.swipe_to_date(root)
        self.result["dateSwipes"] = date_page
        epoch = int(self.shell("date", "+%s"))
        stamps = []
        for attempt in range(3):
            self.tap(date)
            self.capture("candidate-date-handoff")
            intents = self.shell("dumpsys", "activity", "activities")
            self.save("candidate-date-intents.txt", intents)
            stamps = [int(v)//1000 for v in re.findall(r"content://com.android.calendar/time/(\d+)", intents)]
            if any(abs(value-epoch) <= 30 for value in stamps):
                break
            time.sleep(2)
        self.require(any(abs(value-epoch) <= 30 for value in stamps), "Date tap did not hand off the current guest time")
        self.passed("current_date_handoff", guestEpoch=epoch, intentEpochs=stamps)
        self.home("candidate-final-home")
        logs = self.adb("logcat", "-d", "-v", "threadtime")
        crash = self.adb("logcat", "-b", "crash", "-d")
        events = self.adb("logcat", "-b", "events", "-d")
        self.save("logcat.txt", logs)
        self.save("crash.txt", crash)
        self.save("events.txt", events)
        # Keep nonfatal E-level warnings as evidence; fail process-specific crashes/ANRs.
        failures = app_log_failures(logs, crash, events, self.package)
        self.require(not failures, f"QA launcher fatal exception or ANR captured: {failures}")
        self.passed("clean_app_logs", fatal=False, anr=False)
        self.require(self.required_flows == set(self.result["flows"]), "Required flow evidence incomplete")
        self.result["passed"] = True

    def finish(self):
        if self.proc is not None:
            try:
                identity = self.adb("emu", "avd", "name", check=False, timeout=10)
                if identity.splitlines() and identity.splitlines()[0] == self.avd_name:
                    self.save("emulator-stop.txt", self.adb("emu", "kill", check=False, timeout=10))
                self.proc.wait(timeout=30)
            except (subprocess.TimeoutExpired, OSError):
                # Popen is the owned child; no pkill, no shared adb-server shutdown.
                self.proc.terminate()
                try:
                    self.proc.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    self.result["passed"] = False
                    self.result["cleanupError"] = "Owned emulator did not stop after graceful shutdown and SIGTERM"
        self.result["evidence"] = sorted(p.name for p in self.out.iterdir() if p.is_file() and p.name != "qa-result.json")
        (self.out / "qa-result.json").write_text(json.dumps(self.result, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--baseline-apk", type=Path)
    parser.add_argument('--channel', choices=('qa', 'release'), default='qa')
    parser.add_argument('--bootstrap-stable', action='store_true')
    parser.add_argument("--output-dir", required=True, type=Path, help="New directory; existing evidence is never replaced")
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--android-home", type=Path, default=Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))))
    parser.add_argument("--avd-template", default="Pixel_8_Pro_Android_17_QPR2")
    parser.add_argument("--expected-build", default="CP41.260831.007")
    # Apple Silicon host GPU rendering boots ~45% faster than software swiftshader with
    # identical screenshots (measured 2026-10-04); swiftshader_indirect remains the fallback.
    parser.add_argument("--gpu", choices=("host", "swiftshader_indirect"), default="host")
    args = parser.parse_args()
    smoke = Smoke(args)
    def interrupted(signum, frame):
        raise RuntimeError(f"Interrupted by signal {signum}")
    signal.signal(signal.SIGTERM, interrupted)
    try:
        smoke.run()
    except (Exception, KeyboardInterrupt) as error:
        smoke.result["passed"] = False
        smoke.result["error"] = str(error)
        print(f"FAIL: {error}", file=sys.stderr, flush=True)
        if smoke.proc is not None:
            try:
                smoke.capture("failure")
                smoke.save("failure-logcat.txt", smoke.adb("logcat", "-d"))
            except Exception:
                pass
    finally:
        smoke.finish()
    print(smoke.out / "qa-result.json", flush=True)
    return 0 if smoke.result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())

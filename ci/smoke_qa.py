#!/usr/bin/env python3
"""Run bounded, artifact-specific QA on a fresh, exclusively owned Android AVD.

This validates a quiesced ADB upgrade, not Drive download or the system installer.
Every run retains its own AVD and evidence; it never wipes an existing AVD.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "dev.launcher.expressive.l3.debug"
ROLE = "android.app.role.HOME"
REQUIRED_FLOWS = {
    "guest_identity", "baseline_install", "seed_preference", "same_signer_upgrade",
    "preference_retention", "warm_start", "cold_start", "drawer_swipe",
    "local_search", "current_date_handoff", "clean_app_logs",
}


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def bounds(node: ET.Element) -> tuple[int, int, int, int]:
    values = tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
        raise RuntimeError(f"Missing/empty UI bounds: {node.attrib}")
    return values


def app_log_failures(logs: str, crash: str, events: str) -> list[str]:
    """Recognize process-specific fatal/ANR records while retaining nonfatal warnings."""
    combined = logs + "\n" + crash
    failures = []
    if re.search(r"FATAL EXCEPTION[\s\S]{0,1500}?Process: " + re.escape(PACKAGE), combined):
        failures.append("fatal_exception")
    if re.search(r"(?:ANR in |am_anr[^\n]*)" + re.escape(PACKAGE), logs + "\n" + events):
        failures.append("anr")
    if re.search(r"am_crash[^\n]*" + re.escape(PACKAGE), events):
        failures.append("process_crash")
    if re.search(r">>> " + re.escape(PACKAGE) + r"(?::[^ ]+)? <<<", combined):
        failures.append("native_crash")
    return failures


class Smoke:
    def __init__(self, args):
        self.args = args
        self.out = args.output_dir.resolve()
        self.out.mkdir(parents=True, exist_ok=False)
        self.sdk = args.android_home.resolve()
        self.adb_path = self.sdk / "platform-tools/adb"
        self.serial = "emulator-5580"
        self.proc = None
        self.avd_name = "Expressive_CI_" + time.strftime("%Y%m%d_%H%M%S_") + uuid.uuid4().hex[:8]
        self.result = {
            "schemaVersion": 1, "passed": False,
            "sourceRevision": args.source_revision,
            "apk": str(args.apk.resolve()), "apkSha256": digest(args.apk),
            "sha256": digest(args.apk),
            "baselineApk": str(args.baseline_apk.resolve()),
            "baselineSha256": digest(args.baseline_apk),
            "avd": self.avd_name, "serial": self.serial,
            "flows": {}, "evidence": [],
            "scope": "quiesced-adb-upgrade-smoke",
            "limitations": [
                "Does not test live updater download, notifications, or installer UI.",
                "No physical device, privileged Quickstep, widgets, rotation, or midnight rollover coverage.",
            ],
        }

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
                                      "-no-window", "-no-audio", "-no-snapshot", "-gpu", "swiftshader_indirect"],
                                     stdout=emulog, stderr=subprocess.STDOUT)
        emulog.close()
        print(f"Booting {self.avd_name}", flush=True)
        deadline = time.monotonic() + 360
        while time.monotonic() < deadline:
            self.require(self.proc.poll() is None, "Owned emulator exited during boot; see emulator.log")
            if self.shell("getprop", "sys.boot_completed", check=False, timeout=10) == "1":
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

    def tap(self, node):
        x1, y1, x2, y2 = bounds(node)
        self.shell("input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(1)

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
        dump = self.shell("dumpsys", "package", PACKAGE)
        self.save(name + "-package.txt", dump)
        data = {}
        for key in ("versionCode", "versionName", "firstInstallTime", "lastUpdateTime"):
            match = re.search(r"\b" + key + r"=([^\n]+)", dump)
            self.require(match is not None, f"Missing package field {key}")
            data[key] = match.group(1).split(" minSdk=")[0].strip()
        data["role"] = self.shell("cmd", "role", "get-role-holders", ROLE)
        data["resolver"] = self.shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
        self.save(name + "-metadata.json", json.dumps(data, indent=2))
        self.require(PACKAGE in data["role"] and PACKAGE in data["resolver"], "QA launcher is not the retained default HOME")
        return data

    def preferences(self, name):
        self.shell("am", "start", "-W", "-n", PACKAGE + "/app.lawnchair.ui.preferences.PreferenceActivity")
        time.sleep(1)
        root = self.capture(name + "-root")
        self.tap(self.node(root, text="Home screen"))
        root = self.capture(name)
        label = self.node(root, text="Infinite scrolling")
        parents = {child: parent for parent in root.iter() for child in parent}
        switches = [n for n in parents[label].iter("node") if n.get("checkable") == "true"]
        self.require(len(switches) == 1, "Infinite scrolling switch missing or ambiguous")
        return root, switches[0]

    def quiesce(self):
        self.shell("am", "start", "-W", "-a", "android.settings.SETTINGS")
        self.shell("am", "force-stop", PACKAGE)
        self.require(not self.shell("pidof", PACKAGE, check=False), "Launcher did not stop")

    def run(self):
        self.start()
        self.adb("logcat", "-c")
        self.require("Success" in self.adb("install", str(self.args.baseline_apk.resolve()), timeout=180), "Baseline install failed")
        self.shell("cmd", "role", "add-role-holder", ROLE, PACKAGE)
        self.shell("cmd", "package", "set-home-activity", PACKAGE)
        self.home("baseline-home")
        before = self.metadata("baseline")
        self.passed("baseline_install", version=before["versionName"], versionCode=before["versionCode"])
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
        self.require("Success" in self.adb("install", "-r", str(self.args.apk.resolve()), timeout=180), "Candidate upgrade failed")
        after = self.metadata("candidate")
        self.require(before["firstInstallTime"] == after["firstInstallTime"], "Upgrade changed firstInstallTime")
        self.require(int(after["versionCode"]) > int(before["versionCode"]), "Candidate version must advance baseline")
        installed = self.shell("pm", "path", PACKAGE).splitlines()[0].removeprefix("package:")
        remote_digest = self.shell("sha256sum", installed).split()[0]
        self.require(remote_digest == self.result["apkSha256"], "Installed APK bytes differ from candidate")
        self.result["versionName"] = after["versionName"]
        self.result["versionCode"] = int(after["versionCode"])
        self.passed("same_signer_upgrade", firstInstallTime=after["firstInstallTime"], installedSha256=remote_digest)
        self.home("candidate-first-home")
        _, switch = self.preferences("candidate-preference-retained")
        self.require(switch.get("checked") == "true", "Seeded preference lost during upgrade")
        self.passed("preference_retention", preference="Infinite scrolling", value=True)
        pid = self.shell("pidof", "-s", PACKAGE)
        self.home("candidate-warm-home")
        self.require(pid and self.shell("pidof", "-s", PACKAGE) == pid, "Warm HOME recreated process")
        self.passed("warm_start", pid=pid)
        self.quiesce()
        root = self.home("candidate-cold-home")
        cold_pid = self.shell("pidof", "-s", PACKAGE)
        self.require(cold_pid and cold_pid != pid, "Cold HOME did not create a new process")
        self.passed("cold_start", pid=cold_pid)
        x1, y1, x2, y2 = bounds(self.node(root, rid="workspace"))
        # Both endpoints are derived from the current workspace's actual bounds.
        x = (x1+x2)//2
        start_y, end_y = y1 + int((y2-y1)*0.70), y1 + int((y2-y1)*0.22)
        self.shell("input", "swipe", str(x), str(start_y), str(x), str(end_y), "350")
        time.sleep(2)
        root = self.capture("candidate-drawer")
        self.node(root, rid="apps_view")
        self.node(root, rid="search_container_all_apps")
        self.passed("drawer_swipe", path=[x, start_y, x, end_y], durationMs=350)
        self.tap(self.node(root, rid="search_container_all_apps"))
        root = self.capture("candidate-search-focused")
        self.node(root, rid="input")
        self.shell("input", "text", "Calendar")
        time.sleep(2)
        root = self.capture("candidate-search-calendar")
        self.require(self.node(root, rid="input").get("text") == "Calendar", "Search query did not populate")
        result_list = self.node(root, rid="search_results_list_view")
        results = [n for n in result_list.iter("node") if n.get("text") == "Calendar"
                   and n.get("content-desc") == "Calendar" and n.get("class") == "android.widget.TextView"]
        self.require(results, "Local Calendar search returned no visible app result")
        self.passed("local_search", query="Calendar")
        root = self.home("candidate-date-home")
        date = self.node(root, rid="date")
        epoch = int(self.shell("date", "+%s"))
        self.tap(date)
        self.capture("candidate-date-handoff")
        intents = self.shell("dumpsys", "activity", "activities")
        self.save("candidate-date-intents.txt", intents)
        stamps = [int(v)//1000 for v in re.findall(r"content://com.android.calendar/time/(\d+)", intents)]
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
        failures = app_log_failures(logs, crash, events)
        self.require(not failures, f"QA launcher fatal exception or ANR captured: {failures}")
        self.passed("clean_app_logs", fatal=False, anr=False)
        self.require(REQUIRED_FLOWS == set(self.result["flows"]), "Required flow evidence incomplete")
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
    parser.add_argument("--baseline-apk", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path, help="New directory; existing evidence is never replaced")
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--android-home", type=Path, default=Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))))
    parser.add_argument("--avd-template", default="Pixel_8_Pro_Android_17_QPR2_Beta4")
    parser.add_argument("--expected-build", default="CP41.260814.003.B1")
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

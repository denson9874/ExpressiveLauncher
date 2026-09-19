# Copyright 2026 Daryl Denson and Expressive Launcher contributors.
# SPDX-License-Identifier: Apache-2.0
# https://github.com/denson9874/ExpressiveLauncher

import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("security_manifests", Path(__file__).parents[1] / "check_security_manifests.py")
security_manifests = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(security_manifests)
check_manifest = security_manifests.check_manifest


MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.launcher">
  <permission android:name="test.launcher.permission.READ_SETTINGS" android:protectionLevel="signature" />
  <permission android:name="test.launcher.permission.WRITE_SETTINGS" android:protectionLevel="signature" />
  <application android:debuggable="false">
    <provider android:name="com.android.launcher3.LauncherProvider"
      android:authorities="test.launcher.settings" android:exported="true"
      android:readPermission="test.launcher.permission.READ_SETTINGS"
      android:writePermission="test.launcher.permission.WRITE_SETTINGS" />
    <receiver android:name="app.lawnchair.bugreport.BugReportReceiver" android:exported="false" />
  </application>
</manifest>"""


class SecurityManifestTest(unittest.TestCase):
    def check(self, contents):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text(contents)
            check_manifest(path)

    def test_accepts_signature_only_provider_and_non_debuggable_launcher(self):
        self.check(MANIFEST)
        self.check(MANIFEST.replace(' android:debuggable="false"', ""))

    def test_rejects_debuggable_launcher(self):
        with self.assertRaisesRegex(ValueError, "debuggable"):
            self.check(MANIFEST.replace('debuggable="false"', 'debuggable="true"'))

    def test_rejects_weakened_or_missing_read_and_write_permissions(self):
        for operation in ("READ", "WRITE"):
            with self.subTest(operation=operation):
                with self.assertRaisesRegex(ValueError, "signature-only"):
                    self.check(MANIFEST.replace(
                        f'{operation}_SETTINGS" android:protectionLevel="signature"',
                        f'{operation}_SETTINGS" android:protectionLevel="normal"',
                    ))
                with self.assertRaisesRegex(ValueError, "missing provider"):
                    self.check(MANIFEST.replace(
                        f'android:{operation.lower()}Permission="test.launcher.permission.{operation}_SETTINGS"', "",
                    ))

    def test_rejects_uri_grants_and_path_overrides(self):
        with self.assertRaisesRegex(ValueError, "URI grants"):
            self.check(MANIFEST.replace('<provider ', '<provider android:grantUriPermissions="true" '))
        for child in ('<path-permission />', '<grant-uri-permission />'):
            with self.subTest(child=child), self.assertRaisesRegex(ValueError, "override"):
                self.check(MANIFEST.replace(
                    'android:writePermission="test.launcher.permission.WRITE_SETTINGS" />',
                    f'android:writePermission="test.launcher.permission.WRITE_SETTINGS">{child}</provider>',
                ))

    def test_rejects_exported_bug_report_receiver(self):
        with self.assertRaisesRegex(ValueError, "receiver"):
            self.check(MANIFEST.replace('BugReportReceiver" android:exported="false"', 'BugReportReceiver" android:exported="true"'))


if __name__ == "__main__":
    unittest.main()

"""Expressive Core manifest contract (XDA-021).

The Core launcher manifest must stay identical to the Full one except that it removes the accessibility
service and the notification listener, which Play Protect's enhanced fraud protection blocks for
browser-installed apps.
"""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
FULL = ROOT / "expressive/AndroidManifest-launcher.xml"
CORE = ROOT / "expressive/AndroidManifest-launcher-core.xml"
REMOVED_SERVICES = {
    "app.lawnchair.LawnchairAccessibilityService",
    "com.android.launcher3.notification.NotificationListener",
}
REMOVAL_BLOCK = re.compile(
    r"\s*<!-- Core: no accessibility service or notification listener[^>]*-->"
    r"(\s*<service\s+android:name=\"[^\"]+\"\s+tools:node=\"remove\"\s*/>)+",
)
CORE_NOTE = re.compile(r"\nExpressive Core variant \(-PexpressiveCore=true\):.*?keeps the two in sync\.\n", re.S)


class CoreManifestTest(unittest.TestCase):
    def test_core_removes_exactly_the_flagged_services(self):
        core = CORE.read_text()
        removed = set(re.findall(r'<service\s+android:name="([^"]+)"\s+tools:node="remove"', core))
        self.assertEqual(REMOVED_SERVICES, removed)

    def test_core_is_otherwise_identical_to_full(self):
        core = REMOVAL_BLOCK.sub("", CORE.read_text())
        core = CORE_NOTE.sub("", core, count=1)
        self.assertEqual(FULL.read_text(), core,
                         "Update expressive/AndroidManifest-launcher-core.xml whenever the Full manifest changes")

    def test_core_never_declares_the_flagged_bind_permissions(self):
        core = CORE.read_text()
        for permission in ("BIND_ACCESSIBILITY_SERVICE", "BIND_NOTIFICATION_LISTENER_SERVICE",
                           "RECEIVE_SMS", "READ_SMS"):
            self.assertNotIn(permission, core)


if __name__ == "__main__":
    unittest.main()

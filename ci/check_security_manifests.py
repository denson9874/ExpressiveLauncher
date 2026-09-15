#!/usr/bin/env python3
# Copyright 2026 Daryl Denson and Expressive Launcher contributors.
# SPDX-License-Identifier: Apache-2.0
# https://github.com/denson9874/ExpressiveLauncher

"""Check the merged Expressive launcher manifests, after variant overlay resolution."""

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"


def check_manifest(path: Path) -> None:
    root = ET.parse(path).getroot()
    package = root.attrib["package"]
    application = root.find("application")
    if application is None:
        raise ValueError(f"{path}: missing launcher application")
    if application.get(ANDROID + "debuggable", "false") != "false":
        raise ValueError(f"{path}: launcher must not be debuggable")
    permissions = {
        node.get(ANDROID + "name"): node.get(ANDROID + "protectionLevel")
        for node in root.findall("permission")
    }
    providers = [
        node for node in application.findall("provider")
        if node.get(ANDROID + "name") == "com.android.launcher3.LauncherProvider"
    ]
    if len(providers) != 1:
        raise ValueError(f"{path}: expected one LauncherProvider")
    provider = providers[0]
    if provider.get(ANDROID + "authorities") != package + ".settings":
        raise ValueError(f"{path}: unexpected settings authority")
    for operation in ("read", "write"):
        permission = f"{package}.permission.{operation.upper()}_SETTINGS"
        if provider.get(ANDROID + operation + "Permission") != permission:
            raise ValueError(f"{path}: missing provider {operation} permission")
        if permissions.get(permission) != "signature":
            raise ValueError(f"{path}: {operation} permission must be signature-only")
    if provider.get(ANDROID + "grantUriPermissions", "false") != "false":
        raise ValueError(f"{path}: settings URI grants must remain disabled")
    if provider.findall("grant-uri-permission") or provider.findall("path-permission"):
        raise ValueError(f"{path}: unexpected settings permission override")
    receivers = [
        node for node in application.findall("receiver")
        if node.get(ANDROID + "name") == "app.lawnchair.bugreport.BugReportReceiver"
    ]
    if len(receivers) != 1 or receivers[0].get(ANDROID + "exported") != "false":
        raise ValueError(f"{path}: bug report receiver must remain private")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifests", nargs="+", type=Path)
    args = parser.parse_args()
    for path in args.manifests:
        check_manifest(path)
        print(f"PASS: {path}")


if __name__ == "__main__":
    main()

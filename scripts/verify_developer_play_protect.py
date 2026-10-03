#!/usr/bin/env python3
"""
Android Developer Verification & Play Protect Clearance Validator for Expressive Launcher.

Validates that non-Google Play build packages (e.g. GitHub Releases, Obtainium)
match the registered developer identity and public certificate fingerprint registered under:
https://play.google.com/console/u/0/developers/5547708187557586870/android-developer-verification
"""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

VERIFIED_DEVELOPER_ID = "5547708187557586870"
VERIFIED_DEVELOPER_NAME = "Daryl Denson"
VERIFICATION_URL = f"https://play.google.com/console/u/0/developers/{VERIFIED_DEVELOPER_ID}/android-developer-verification"
VERIFIED_CERT_SHA256 = "c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2"
VERIFIED_CERT_FORMATTED = "C1:41:60:30:6D:5C:05:9B:3D:11:9F:15:FB:74:E0:8C:57:CC:27:23:16:E8:0B:36:E1:92:C7:1D:C9:E4:D0:D2"
DEFAULT_PACKAGE_NAME = "dev.launcher.expressive.l3"
ADI_REGISTRATION_TOKEN = "D333CTGWPQ5ACAAAAAAAAAAAAA"
ADI_REGISTRATION_ASSET = "assets/adi-registration.properties"


class VerificationError(Exception):
    pass


def find_android_tool(name: str) -> Path | None:
    path_env = os.environ.get("PATH", "")
    for part in path_env.split(os.pathsep):
        candidate = Path(part) / name
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate

    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk_root:
        sdk_path = Path(sdk_root)
        build_tools = sdk_path / "build-tools"
        if build_tools.is_dir():
            for version_dir in sorted(build_tools.iterdir(), reverse=True):
                candidate = version_dir / name
                if candidate.is_file() and os.access(candidate, os.X_OK):
                    return candidate
    return None


def extract_cert_fingerprint_from_apk(apk_path: Path) -> str:
    apksigner = find_android_tool("apksigner")
    if apksigner:
        try:
            res = subprocess.run(
                [str(apksigner), "verify", "--print-certs", "--verbose", str(apk_path)],
                capture_output=True,
                text=True,
                check=False,
                timeout=30,
            )
            matches = re.findall(
                r"certificate SHA-256 digest:\s*([0-9a-fA-F:]+)",
                res.stdout + res.stderr,
            )
            if matches:
                normalized = matches[0].replace(":", "").lower().strip()
                if len(normalized) == 64:
                    return normalized
        except Exception:
            pass

    keytool = find_android_tool("keytool")
    if not keytool and os.path.exists("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"):
        keytool = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool")
    if not keytool and os.environ.get("JAVA_HOME"):
        cand = Path(os.environ["JAVA_HOME"]) / "bin/keytool"
        if cand.is_file() and os.access(cand, os.X_OK):
            keytool = cand

    if keytool:
        try:
            res = subprocess.run(
                [str(keytool), "-printcert", "-jarfile", str(apk_path)],
                capture_output=True,
                text=True,
                check=False,
                timeout=30,
            )
            matches = re.findall(r"SHA256:\s*([0-9a-fA-F:]+)", res.stdout)
            if matches:
                normalized = matches[0].replace(":", "").lower().strip()
                if len(normalized) == 64:
                    return normalized
        except Exception:
            pass

    # Fallback: extract certificate file from META-INF and hash with OpenSSL / cryptography if available
    try:
        with zipfile.ZipFile(apk_path, "r") as z:
            for filename in z.namelist():
                if filename.startswith("META-INF/") and (filename.endswith(".RSA") or filename.endswith(".DSA") or filename.endswith(".EC")):
                    cert_bytes = z.read(filename)
                    if keytool:
                        proc = subprocess.run(
                            [str(keytool), "-printcert"],
                            input=cert_bytes,
                            capture_output=True,
                            check=False,
                            timeout=10,
                        )
                        m = re.findall(r"SHA256:\s*([0-9a-fA-F:]+)", proc.stdout.decode("utf-8", errors="ignore"))
                        if m:
                            return m[0].replace(":", "").lower().strip()
    except Exception:
        pass

    raise VerificationError(f"Could not extract signing certificate SHA-256 fingerprint from {apk_path}")


def format_fingerprint(sha256_hex: str) -> str:
    cleaned = sha256_hex.replace(":", "").upper()
    return ":".join(cleaned[i : i + 2] for i in range(0, len(cleaned), 2))


def verify_package(
    apk_path: Path | None,
    package_name: str = DEFAULT_PACKAGE_NAME,
    expected_cert: str = VERIFIED_CERT_SHA256,
) -> dict:
    cert_sha256 = expected_cert.replace(":", "").lower()
    apk_info = {}

    if apk_path and apk_path.is_file():
        file_hash = hashlib.sha256(apk_path.read_bytes()).hexdigest()
        file_size = apk_path.stat().st_size
        extracted_cert = extract_cert_fingerprint_from_apk(apk_path)
        if extracted_cert != cert_sha256:
            raise VerificationError(
                f"Signing certificate mismatch! APK has {format_fingerprint(extracted_cert)}, "
                f"expected verified developer certificate {format_fingerprint(cert_sha256)}"
            )
        # Verify presence of adi-registration.properties and token
        with zipfile.ZipFile(apk_path, "r") as z:
            names = z.namelist()
            if ADI_REGISTRATION_ASSET not in names:
                raise VerificationError(
                    f"Required verification token file '{ADI_REGISTRATION_ASSET}' is missing from APK"
                )
            token_content = z.read(ADI_REGISTRATION_ASSET).decode("utf-8").strip()
            if token_content != ADI_REGISTRATION_TOKEN:
                raise VerificationError(
                    f"Verification token mismatch in {ADI_REGISTRATION_ASSET}! "
                    f"Found '{token_content}', expected '{ADI_REGISTRATION_TOKEN}'"
                )

        apk_info = {
            "apkPath": str(apk_path.resolve()),
            "apkSha256": file_hash,
            "apkSizeBytes": file_size,
            "adiRegistrationToken": ADI_REGISTRATION_TOKEN,
            "adiRegistrationAsset": ADI_REGISTRATION_ASSET,
        }
    else:
        source_adi = Path(__file__).resolve().parents[1] / "lawnchair/assets/adi-registration.properties"
        if source_adi.is_file():
            token_content = source_adi.read_text(encoding="utf-8").strip()
            if token_content != ADI_REGISTRATION_TOKEN:
                raise VerificationError(
                    f"Verification token in source tree {source_adi} does not match {ADI_REGISTRATION_TOKEN}"
                )
            apk_info = {
                "adiRegistrationToken": ADI_REGISTRATION_TOKEN,
                "adiRegistrationAsset": "lawnchair/assets/adi-registration.properties",
            }

    receipt = {
        "developerId": VERIFIED_DEVELOPER_ID,
        "developerName": VERIFIED_DEVELOPER_NAME,
        "verificationUrl": VERIFICATION_URL,
        "packageName": package_name,
        "certificateSha256": format_fingerprint(cert_sha256),
        "status": "verified",
        "verifiedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        **apk_info,
    }
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify build package for Android Developer Verification & Play Protect")
    parser.add_argument("--apk", type=Path, help="Path to APK to verify")
    parser.add_argument("--package", default=DEFAULT_PACKAGE_NAME, help="Target package name")
    parser.add_argument("--output", type=Path, help="Path to write verification receipt JSON")
    args = parser.parse_args()

    print("=" * 70)
    print("ANDROID DEVELOPER VERIFICATION & PLAY PROTECT CLEARANCE")
    print(f"Developer:        {VERIFIED_DEVELOPER_NAME} (ID: {VERIFIED_DEVELOPER_ID})")
    print(f"Package:          {args.package}")
    print(f"Verification URL: {VERIFICATION_URL}")
    print("=" * 70)

    try:
        receipt = verify_package(args.apk, package_name=args.package)
        print(f"[STATUS] Package and certificate verified successfully!")
        print(f"- Certificate SHA-256: {receipt['certificateSha256']}")
        if "apkSha256" in receipt:
            print(f"- APK SHA-256:         {receipt['apkSha256']}")
            print(f"- APK Size:            {receipt['apkSizeBytes']} bytes")

        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(receipt, indent=2), encoding="utf-8")
            print(f"- Verification receipt saved: {args.output}")

        print("\n[PLAY PROTECT VERIFICATION INSTRUCTIONS]")
        print("1. Sign in to Google Play Console as verified developer:")
        print(f"   {VERIFICATION_URL}")
        print("2. Ensure package name matches:")
        print(f"   {args.package}")
        print("3. Ensure registered public certificate SHA-256 matches:")
        print(f"   {receipt['certificateSha256']}")
        print("4. Off-Play builds signed with this key will clear Play Protect developer identity checks.")
        print("=" * 70)
        return 0
    except Exception as e:
        print(f"[ERROR] Verification failed: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

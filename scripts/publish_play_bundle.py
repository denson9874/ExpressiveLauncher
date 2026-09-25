#!/usr/bin/env python3
"""
Automated Google Play Store Publisher script for Expressive Launcher.
Authenticates via Google Cloud Service Account, creates an edit, uploads
an Android App Bundle (.aab), assigns it to a release track, and commits the edit.
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


def create_jwt_assertion(key_data: dict) -> str:
    header = {"alg": "RS256", "typ": "JWT"}
    now = int(time.time())
    payload = {
        "iss": key_data["client_email"],
        "scope": "https://www.googleapis.com/auth/androidpublisher",
        "aud": key_data.get("token_uri", "https://oauth2.googleapis.com/token"),
        "exp": now + 3600,
        "iat": now,
    }

    def b64url(data: bytes) -> str:
        return base64.urlsafe_b64encode(data).decode("utf-8").rstrip("=")

    seg1 = b64url(json.dumps(header).encode("utf-8"))
    seg2 = b64url(json.dumps(payload).encode("utf-8"))
    signing_input = f"{seg1}.{seg2}".encode("utf-8")

    with tempfile.NamedTemporaryFile("w", delete=False) as key_temp:
        key_temp.write(key_data["private_key"])
        key_temp_path = key_temp.name

    try:
        proc = subprocess.Popen(
            ["openssl", "dgst", "-sha256", "-sign", key_temp_path],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        sig, err = proc.communicate(input=signing_input)
        if proc.returncode != 0:
            raise RuntimeError(f"OpenSSL failed to sign JWT: {err.decode('utf-8')}")
    finally:
        if os.path.exists(key_temp_path):
            os.unlink(key_temp_path)

    return f"{seg1}.{seg2}.{b64url(sig)}"


def get_access_token(key_data: dict) -> str:
    token_uri = key_data.get("token_uri", "https://oauth2.googleapis.com/token")
    assertion = create_jwt_assertion(key_data)
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    }).encode("utf-8")

    req = urllib.request.Request(token_uri, data=body, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")

    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data["access_token"]
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Failed to obtain access token (HTTP {e.code}): {error_body}")


def upload_bundle_resumable(package_name: str, edit_id: str, bundle_path: str, access_token: str) -> dict:
    file_size = os.path.getsize(bundle_path)
    init_url = (
        f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/"
        f"{package_name}/edits/{edit_id}/bundles?uploadType=resumable"
    )

    req = urllib.request.Request(init_url, data=b"", method="POST")
    req.add_header("Authorization", f"Bearer {access_token}")
    req.add_header("Content-Type", "application/octet-stream")
    req.add_header("X-Upload-Content-Type", "application/octet-stream")
    req.add_header("X-Upload-Content-Length", str(file_size))

    try:
        with urllib.request.urlopen(req) as resp:
            location = resp.headers.get("Location")
            if not location:
                raise RuntimeError("Google Play upload initiation did not return a Location header")
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Failed to initiate bundle upload (HTTP {e.code}): {error_body}")

    print(f"Uploading bundle ({file_size / (1024 * 1024):.2f} MB) to Google Play...")
    with open(bundle_path, "rb") as f:
        bundle_bytes = f.read()

    put_req = urllib.request.Request(location, data=bundle_bytes, method="PUT")
    put_req.add_header("Authorization", f"Bearer {access_token}")
    put_req.add_header("Content-Type", "application/octet-stream")
    put_req.add_header("Content-Length", str(file_size))

    try:
        with urllib.request.urlopen(put_req) as resp:
            bundle_info = json.loads(resp.read().decode("utf-8"))
            print(f"Bundle uploaded successfully! Version Code: {bundle_info.get('versionCode')}, SHA-256: {bundle_info.get('sha256')}")
            return bundle_info
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Failed to upload bundle content (HTTP {e.code}): {error_body}")


def publish(package_name: str, key_path: str, bundle_path: str, track: str, changelog_path: str, version_name: str):
    if not os.path.exists(key_path):
        raise FileNotFoundError(f"Service account key not found at: {key_path}")
    if not os.path.exists(bundle_path):
        raise FileNotFoundError(f"Bundle file not found at: {bundle_path}")

    with open(key_path, "r", encoding="utf-8") as f:
        key_data = json.load(f)

    print(f"Authenticating service account: {key_data.get('client_email')}...")
    access_token = get_access_token(key_data)

    headers = {
        "Authorization": f"Bearer {access_token}",
        "Content-Type": "application/json",
    }

    # 1. Create Edit
    print(f"Creating edit for package '{package_name}'...")
    edit_url = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{package_name}/edits"
    create_edit_req = urllib.request.Request(edit_url, data=b"{}", headers=headers, method="POST")
    try:
        with urllib.request.urlopen(create_edit_req) as resp:
            edit_data = json.loads(resp.read().decode("utf-8"))
            edit_id = edit_data["id"]
            print(f"Edit created with ID: {edit_id}")
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Failed to create edit session (HTTP {e.code}): {error_body}")

    try:
        # 2. Upload Bundle
        bundle_info = upload_bundle_resumable(package_name, edit_id, bundle_path, access_token)
        version_code = str(bundle_info["versionCode"])

        # 3. Read Changelog
        changelog_text = ""
        if changelog_path and os.path.exists(changelog_path):
            with open(changelog_path, "r", encoding="utf-8") as f:
                changelog_text = f.read().strip()
        if not changelog_text:
            changelog_text = (
                f"Expressive Launcher L3 version {version_name or version_code}.\n"
                f"- Official Google Play release signed by verified developer Daryl Denson.\n"
                f"- Material You expressive theming with dynamic colors and adaptive icons.\n"
                f"- Home layout, app drawer, search, and widget enhancements."
            )

        release_name = f"{version_name} ({version_code})" if version_name else version_code

        release_payload = {
            "track": track,
            "releases": [
                {
                    "name": release_name,
                    "versionCodes": [version_code],
                    "status": "completed",
                    "releaseNotes": [
                        {
                            "language": "en-US",
                            "text": changelog_text,
                        }
                    ],
                }
            ],
        }

        # 4. Assign Track
        print(f"Assigning bundle {version_code} to track '{track}'...")
        track_url = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{package_name}/edits/{edit_id}/tracks/{track}"
        track_req = urllib.request.Request(
            track_url,
            data=json.dumps(release_payload).encode("utf-8"),
            headers=headers,
            method="PUT",
        )
        with urllib.request.urlopen(track_req) as resp:
            track_res = json.loads(resp.read().decode("utf-8"))
            print(f"Track updated successfully: {track_res.get('track')}")

        # 5. Commit Edit
        print(f"Committing edit {edit_id} to Google Play...")
        commit_url = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{package_name}/edits/{edit_id}:commit"
        commit_req = urllib.request.Request(commit_url, data=b"", headers=headers, method="POST")
        with urllib.request.urlopen(commit_req) as resp:
            commit_res = json.loads(resp.read().decode("utf-8"))
            print(f"Edit committed successfully! Status: {commit_res}")

        print(f"\nSUCCESS: Published {package_name} v{version_code} to Google Play track '{track}'!")

    except Exception as e:
        print(f"Rolling back/discarding edit {edit_id} due to error: {e}")
        try:
            delete_req = urllib.request.Request(
                f"{edit_url}/{edit_id}",
                headers=headers,
                method="DELETE",
            )
            urllib.request.urlopen(delete_req)
        except Exception:
            pass
        raise


def find_default_key_file() -> str:
    candidates = [
        os.environ.get("PLAY_STORE_JSON_KEY"),
        "/Users/daryldenson/Documents/Expressive Launcher Signing/play-service-account.json",
        "/Users/daryldenson/Documents/Expressive Launcher Signing/expressive-launcher-l3-bc48b467b47e.json",
    ]
    for c in candidates:
        if c and os.path.exists(c):
            return c
    return ""


def main():
    parser = argparse.ArgumentParser(description="Publish AAB bundle to Google Play Store via Developer API")
    parser.add_argument("--key-file", default=find_default_key_file(), help="Path to Google Cloud Service Account JSON key")
    parser.add_argument("--package-name", default="com.denson9874.Expressive_Launcher_L3", help="Package ID on Play Store")
    parser.add_argument("--track", default="internal", help="Google Play track (internal, alpha, beta, production)")
    parser.add_argument("--bundle", required=True, help="Path to signed .aab bundle")
    parser.add_argument("--changelog", default="", help="Path to release notes file")
    parser.add_argument("--version-name", default="", help="User-facing version name")

    args = parser.parse_args()

    if not args.key_file:
        print("Error: No Service Account JSON key found.", file=sys.stderr)
        sys.exit(1)

    try:
        publish(
            package_name=args.package_name,
            key_path=args.key_file,
            bundle_path=args.bundle,
            track=args.track,
            changelog_path=args.changelog,
            version_name=args.version_name,
        )
    except Exception as err:
        print(f"Error publishing to Google Play: {err}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()

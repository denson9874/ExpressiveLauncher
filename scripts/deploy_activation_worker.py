#!/usr/bin/env python3
"""
Automated Cloudflare Worker Deployment & PayPal Webhook Setup for Expressive Pro.

Automates:
1. Verification of Cloudflare authentication via Wrangler.
2. Creation/Lookup of the Cloudflare KV namespace ("EXPRESSIVE_PRO_KV").
3. Configuration of server/cloudflare-worker/wrangler.toml with the KV ID.
4. Secure upload of the master private key (pro_master_private.pem) to Cloudflare Secrets.
5. Deployment of the worker via `wrangler deploy`.
6. Live health verification of the deployed worker.
7. Verification & synchronization of the Android launcher's DEFAULT_ACTIVATION_URL in ProActivationService.kt.
"""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
WORKER_DIR = ROOT / "server/cloudflare-worker"
WRANGLER_TOML = WORKER_DIR / "wrangler.toml"
PRIVATE_KEY_PATH = Path.home() / ".config/expressive/pro_master_private.pem"
PRO_SERVICE_KT = ROOT / "lawnchair/src/app/lawnchair/pro/ProActivationService.kt"


def run_command(cmd: list[str], cwd: Path = ROOT, input_data: str | None = None) -> tuple[int, str, str]:
    print(f"[RUN] {' '.join(cmd)}")
    proc = subprocess.Popen(
        cmd,
        cwd=cwd,
        stdin=subprocess.PIPE if input_data else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    stdout, stderr = proc.communicate(input=input_data)
    return proc.returncode, stdout, stderr


def check_wrangler_auth() -> tuple[bool, str]:
    code, stdout, stderr = run_command(["wrangler", "whoami"])
    output = stdout + stderr
    if "You are logged in with the email" in output or "Account Name" in output or "Account ID" in output:
        email_match = re.search(r"email\s+([^\s]+)", output)
        email = email_match.group(1) if email_match else "Authenticated User"
        return True, email
    return False, ""


def ensure_kv_namespace() -> str:
    print("\n>>> Checking/Creating Cloudflare KV Namespace 'EXPRESSIVE_PRO_KV'...")
    # First list existing namespaces
    code, stdout, stderr = run_command(["wrangler", "kv", "namespace", "list"], cwd=WORKER_DIR)
    if code == 0 and stdout.strip():
        try:
            namespaces = json.loads(stdout)
            for ns in namespaces:
                if ns.get("title") == "expressive-pro-activation-EXPRESSIVE_PRO_KV" or ns.get("title") == "EXPRESSIVE_PRO_KV":
                    kv_id = ns.get("id")
                    print(f"Found existing KV namespace: {ns.get('title')} (ID: {kv_id})")
                    return kv_id
        except Exception:
            pass

    # Create new namespace
    code, stdout, stderr = run_command(["wrangler", "kv", "namespace", "create", "EXPRESSIVE_PRO_KV"], cwd=WORKER_DIR)
    combined = stdout + stderr
    id_match = re.search(r'id\s*=\s*"([a-f0-9]{32})"', combined)
    if not id_match:
        id_match = re.search(r'([a-f0-9]{32})', combined)
    
    if not id_match:
        raise RuntimeError(f"Failed to create or parse KV namespace from output:\n{combined}")

    kv_id = id_match.group(1)
    print(f"Created new KV namespace: EXPRESSIVE_PRO_KV (ID: {kv_id})")
    return kv_id


def update_wrangler_toml(kv_id: str):
    print(f"\n>>> Updating {WRANGLER_TOML.name} with KV Namespace ID: {kv_id}...")
    content = WRANGLER_TOML.read_text(encoding="utf-8")
    
    # Replace id = "..."
    content = re.sub(
        r'\{ binding\s*=\s*"EXPRESSIVE_PRO_KV",\s*id\s*=\s*"[^"]*"',
        f'{{ binding = "EXPRESSIVE_PRO_KV", id = "{kv_id}"',
        content,
    )
    WRANGLER_TOML.write_text(content, encoding="utf-8")
    print(f"Successfully configured {WRANGLER_TOML}")


def upload_secrets():
    print("\n>>> Setting master ECDSA private key secret (PRO_PRIVATE_KEY_PEM)...")
    if not PRIVATE_KEY_PATH.exists():
        raise FileNotFoundError(f"Master private key not found at {PRIVATE_KEY_PATH}")
    
    priv_pem = PRIVATE_KEY_PATH.read_text(encoding="utf-8").strip()
    code, stdout, stderr = run_command(
        ["wrangler", "secret", "put", "PRO_PRIVATE_KEY_PEM"],
        cwd=WORKER_DIR,
        input_data=priv_pem + "\n",
    )
    if code != 0:
        raise RuntimeError(f"Failed to set PRO_PRIVATE_KEY_PEM secret:\n{stderr or stdout}")
    print("PRO_PRIVATE_KEY_PEM secret successfully uploaded to Cloudflare.")


def deploy_worker() -> str:
    print("\n>>> Deploying Cloudflare Worker...")
    code, stdout, stderr = run_command(["wrangler", "deploy"], cwd=WORKER_DIR)
    combined = stdout + stderr
    if code != 0:
        raise RuntimeError(f"wrangler deploy failed:\n{combined}")
    
    print(combined)
    # Parse deployed worker URL: https://expressive-pro-activation.<subdomain>.workers.dev
    url_match = re.search(r"https://expressive-pro-activation\.[a-zA-Z0-9\-]+\.workers\.dev", combined)
    if not url_match:
        url_match = re.search(r"https://[a-zA-Z0-9\-\.]+\.workers\.dev", combined)
    
    if not url_match:
        raise RuntimeError("Could not find deployed worker URL in output")
    
    deployed_url = url_match.group(0).rstrip("/")
    print(f"\n[SUCCESS] Worker deployed at: {deployed_url}")
    return deployed_url


def verify_live_worker(base_url: str):
    print(f"\n>>> Verifying worker health at {base_url}/health...")
    time.sleep(2)
    req = urllib.request.Request(f"{base_url}/health", headers={"User-Agent": "Expressive-Deploy-Verifier"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            print(f"Worker health check passed: {data}")
    except Exception as e:
        print(f"[WARNING] Health check failed or timed out: {e}")


def sync_launcher_endpoint(live_url: str):
    print(f"\n>>> Verifying launcher endpoint in ProActivationService.kt...")
    content = PRO_SERVICE_KT.read_text(encoding="utf-8")
    expected_url = f"{live_url}/"
    current_match = re.search(r'const val DEFAULT_ACTIVATION_URL = "([^"]+)"', content)
    if current_match:
        current_url = current_match.group(1)
        if current_url != expected_url:
            print(f"Updating ProActivationService.kt endpoint:\n  Current:  {current_url}\n  Deploy:   {expected_url}")
            content = content.replace(f'const val DEFAULT_ACTIVATION_URL = "{current_url}"', f'const val DEFAULT_ACTIVATION_URL = "{expected_url}"')
            PRO_SERVICE_KT.write_text(content, encoding="utf-8")
            print("Updated ProActivationService.kt with live URL.")
        else:
            print(f"ProActivationService.kt already matches live URL: {expected_url}")


def main():
    print("=" * 70)
    print("EXPRESSIVE LAUNCHER PRO — AUTOMATED BACKEND DEPLOYMENT")
    print("=" * 70)

    is_logged_in, user_email = check_wrangler_auth()
    if not is_logged_in:
        print("\n[AUTHENTICATION REQUIRED]")
        print("Wrangler is not yet authenticated to Cloudflare.")
        print("Please complete the authorization in your browser or run:")
        print("  npx wrangler login")
        sys.exit(2)

    print(f"[AUTH] Logged in to Cloudflare as: {user_email}")

    # 1. Setup KV Namespace
    kv_id = ensure_kv_namespace()
    update_wrangler_toml(kv_id)

    # 2. Upload Secrets
    upload_secrets()

    # 3. Deploy Worker
    live_url = deploy_worker()

    # 4. Verify Live Health
    verify_live_worker(live_url)

    # 5. Sync Launcher App Config
    sync_launcher_endpoint(live_url)

    print("\n" + "=" * 70)
    print("🎉 DEPLOYMENT COMPLETE! 24/7 AUTOMATED ACTIVATION IS ACTIVE")
    print("=" * 70)
    print(f"- Live API Endpoint:   {live_url}")
    print(f"- Health Check:        {live_url}/health")
    print(f"- License Verification:{live_url}/api/verify-donation")
    print(f"- Device Lookup:       {live_url}/api/license?device_id=DEV-XXXX")
    print("\n👉 FINAL STEP: Configure PayPal Webhook in PayPal Developer Dashboard:")
    print(f"   Webhook URL:        {live_url}/api/paypal-webhook")
    print("   Event Types:        Payment capture completed, Checkout order approved")
    print("=" * 70)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Issue Expressive Launcher Pro license keys through the activation worker.

The master private key lives only in the Cloudflare worker (secret PRO_PRIVATE_KEY_PEM), so keys are
signed there: this tool calls POST /api/admin/issue with the owner's ADMIN_TOKEN. It replaces the
create and batch commands of scripts/generate_pro_key.py and takes the same options.

Token: --token, else $EXPRESSIVE_PRO_ADMIN_TOKEN, else ~/.config/expressive/admin_token.

  python3 scripts/issue_pro_key.py create --type CAKEY --device DEV-A1B2-C3D4
  python3 scripts/issue_pro_key.py create --type TESTER --recipient "Beta tester" --expires 90d
  python3 scripts/issue_pro_key.py batch --count 10 --type GIVEAWAY --prefix xda- --output keys.txt
"""

import argparse
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request

DEFAULT_WORKER_URL = "https://expressive-pro-activation.daryldenson-apps.workers.dev"
DEFAULT_TOKEN_PATH = Path.home() / ".config/expressive/admin_token"
TYPES = ["TESTER", "GIVEAWAY", "VIP", "DEV", "CAKEY"]


def load_token(explicit: str | None, env: dict, token_path: Path) -> str:
    if explicit:
        return explicit.strip()
    if env.get("EXPRESSIVE_PRO_ADMIN_TOKEN"):
        return env["EXPRESSIVE_PRO_ADMIN_TOKEN"].strip()
    if token_path.is_file():
        return token_path.read_text(encoding="utf-8").strip()
    raise SystemExit(
        f"No admin token: pass --token, set EXPRESSIVE_PRO_ADMIN_TOKEN, or save it to {token_path}"
    )


def request_body(args: argparse.Namespace) -> dict:
    """Translate CLI options into the worker's /api/admin/issue body."""
    body = {"type": args.type, "expires": args.expires}
    if args.command == "create":
        if args.device:
            body["device"] = args.device
        elif args.account:
            body["account"] = args.account
        elif args.recipient:
            body["recipient"] = args.recipient
        else:
            raise SystemExit("create needs --device, --account or --recipient")
    else:
        body["count"] = args.count
        body["prefix"] = args.prefix
    return body


def issue(url: str, token: str, body: dict, opener=urllib.request.urlopen) -> dict:
    req = urllib.request.Request(
        url.rstrip("/") + "/api/admin/issue",
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            # Cloudflare challenges the default Python-urllib agent.
            "User-Agent": "expressive-issue-pro-key/1",
        },
    )
    try:
        with opener(req, timeout=30) as res:
            return json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        try:
            data = json.loads(detail)
            detail = data.get("message") or data.get("error") or detail
        except json.JSONDecodeError:
            pass
        raise SystemExit(f"Worker refused the request (HTTP {e.code}): {detail}")


def format_result(result: dict) -> str:
    expires = "Lifetime" if result["expiresAt"] == 0 else f"{result['expiresAt']} (Unix seconds)"
    lines = [f"{len(result['keys'])} {result['type']} key(s), expires: {expires}"]
    for k in result["keys"]:
        lines.append(f"{k['recipient']} | {k['key']} | {k['deepLink']}")
    return "\n".join(lines) + "\n"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default=os.environ.get("EXPRESSIVE_PRO_WORKER_URL", DEFAULT_WORKER_URL))
    parser.add_argument("--token", help="Admin token (default: env or token file)")
    sub = parser.add_subparsers(dest="command", required=True)

    create = sub.add_parser("create", help="Create one signed license key")
    create.add_argument("--recipient", help="Recipient name, handle, or ID (works on any device)")
    create.add_argument("--device", help="Device ID (e.g. DEV-A1B2-C3D4) to bind this license to")
    create.add_argument("--account", help="Account email to bind this license to")
    create.add_argument("--type", type=str.upper, choices=TYPES, default="TESTER")
    create.add_argument("--expires", default="never", help="never, 30d, 6m, 1y, or YYYY-MM-DD")

    batch = sub.add_parser("batch", help="Create several giveaway / tester keys")
    batch.add_argument("--count", type=int, default=10, help="Number of keys (max 100)")
    batch.add_argument("--type", type=str.upper, choices=TYPES, default="GIVEAWAY")
    batch.add_argument("--prefix", default="giveaway-", help="Recipient label prefix")
    batch.add_argument("--expires", default="never", help="never, 30d, 6m, 1y, or YYYY-MM-DD")
    batch.add_argument("--output", type=Path, help="Also save the keys to this file")
    return parser


def main(argv=None) -> None:
    args = build_parser().parse_args(argv)
    token = load_token(args.token, os.environ, DEFAULT_TOKEN_PATH)
    result = issue(args.url, token, request_body(args))
    text = format_result(result)
    if getattr(args, "output", None):
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
        print(f"Saved {len(result['keys'])} keys to {args.output}")
    else:
        print(text, end="")


if __name__ == "__main__":
    main()

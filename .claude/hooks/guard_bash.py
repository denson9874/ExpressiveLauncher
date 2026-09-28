#!/usr/bin/env python3
"""PreToolUse guard for Bash: blocks commands the Expressive release workflow forbids.

Exit 2 with a reason on stderr blocks the call and tells Claude why.
"""
import json
import re
import sys

RULES = [
    (r"\bgit\s+push\b", "Pushing source is not part of the workflow; the user pushes manually."),
    (r"\bgit\s+(switch|checkout)\b", "Branch switches/checkouts are forbidden; stay on codex/pixel-parity and preserve work."),
    (r"\bgit\s+(rebase|merge)\b", "Merges/rebases are forbidden in this checkout."),
    (r"\bgit\s+reset\b[^;&|]*--hard", "Hard resets discard work."),
    (r"\bgit\s+clean\b", "git clean deletes untracked user files."),
    (r"\bgit\s+branch\b[^;&|]*\s-(-delete|-force|[dDfM]\b)", "Deleting or force-moving branches is forbidden."),
    (r"\bgit\s+tag\b[^;&|]*\s-(d|-delete)\b", "Deleting tags is forbidden."),
    (r"\bgit\s+stash\s+(drop|clear)\b", "Dropping stashes discards work."),
    (r"\bgit\s+update-ref\b", "Force-updating refs is forbidden."),
    (r"\bgh\s+release\s+(delete|delete-asset)\b", "Never delete releases or assets."),
    (r"\bgh\s+release\s+upload\b[^;&|]*--clobber", "Never replace existing release assets."),
    (r"\bgh\s+api\b[^;&|]*(-X|--method)\s*DELETE", "DELETE calls against GitHub are forbidden."),
    (r"\bgh\s+repo\s+delete\b", "Never delete repositories."),
    (r"control\.py\s+configure\b", "Reconfiguring Jenkins jobs requires the user."),
    (r"\bkeytool\b[^;&|]*-(genkey|genkeypair|delete|importkeystore|changealias|storepasswd)", "Signing keys must not be created or modified."),
    (r"\brm\b[^;&|]*(\.jks|\.keystore|Expressive CI)", "Never delete keystores or Expressive CI state."),
    (r"\badb\b[^;&|]*\b(uninstall|pm\s+clear|pm\s+uninstall)\b", "Never uninstall or clear app data on a device."),
    (r"\bavdmanager\b[^;&|]*\bdelete\b", "Existing AVDs must be retained."),
]


def main():
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError:
        return 0
    command = (payload.get("tool_input") or {}).get("command") or ""
    for pattern, reason in RULES:
        if re.search(pattern, command):
            print(f"Blocked by .claude/hooks/guard_bash.py: {reason} Ask the user if this is really needed.", file=sys.stderr)
            return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

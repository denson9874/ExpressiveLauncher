#!/usr/bin/env python3
"""PreToolUse guard for Bash: blocks commands the Expressive release workflow forbids.

Exit 2 with a reason on stderr blocks the call and tells Claude why.
"""
import json
import re
import sys

PUSH_ALLOWED_REFS = re.compile(r"^(codex/pixel-parity|HEAD:codex/pixel-parity|(claude|docs)/[\w./-]+)$")

RULES = [
    (r"\bgit\s+push\b[^;&|]*(\s--?f\b|--force|--mirror|--delete|\s-d\b|\s\+\S|\s:\S)",
     "Force-pushes, mirror pushes and remote ref deletions are forbidden."),
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


def push_violation(command):
    """Allow only explicit `git push origin <allowed-ref>`; main/stable/updates/gh-pages belong to the publisher and PRs."""
    for match in re.finditer(r"\bgit\s+push\b([^;&|]*)", command):
        args = [a for a in match.group(1).split() if not a.startswith("-")]
        if len(args) < 2 or args[0] != "origin":
            return "Push must name the remote and branch explicitly: git push origin codex/pixel-parity."
        bad = [ref for ref in args[1:] if not PUSH_ALLOWED_REFS.match(ref)]
        if bad:
            return f"Pushing {', '.join(bad)} is not allowed; only codex/pixel-parity and claude/* or docs/* PR branches."
    return None


def block(reason):
    print(f"Blocked by .claude/hooks/guard_bash.py: {reason} Ask the user if this is really needed.", file=sys.stderr)
    return 2


def main():
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError:
        return 0
    command = (payload.get("tool_input") or {}).get("command") or ""
    for pattern, reason in RULES:
        if re.search(pattern, command):
            return block(reason)
    reason = push_violation(command)
    if reason:
        return block(reason)
    return 0


if __name__ == "__main__":
    sys.exit(main())

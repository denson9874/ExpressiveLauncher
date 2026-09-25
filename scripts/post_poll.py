#!/usr/bin/env python3
"""Post a structured poll (and optional announcement message) to Telegram via Bot API.

Supports both direct group chats (e.g. @ExpressiveLauncherFeedback) and broadcast
channels (e.g. @ExpressiveLauncher).
"""

import argparse
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request

DEFAULT_CHANNEL = "@ExpressiveLauncher"
DEFAULT_FEEDBACK_GROUP = "@ExpressiveLauncherFeedback"
CONFIG_PATHS = [
    Path.home() / "Library/Application Support/Expressive CI/config/telegram.json",
    Path.home() / ".config/expressive/telegram.json",
]


def load_config():
    """Load configuration from config JSON files or environment variables."""
    config = {
        "bot_token": os.getenv("TELEGRAM_BOT_TOKEN", "").strip(),
        "channel_id": os.getenv("TELEGRAM_CHANNEL_ID", "").strip() or DEFAULT_CHANNEL,
    }
    for config_path in CONFIG_PATHS:
        if config_path.is_file():
            try:
                data = json.loads(config_path.read_text(encoding="utf-8"))
                if not config["bot_token"] and data.get("bot_token"):
                    config["bot_token"] = str(data["bot_token"]).strip()
                if (
                    config["channel_id"] == DEFAULT_CHANNEL
                    and data.get("channel_id")
                ):
                    config["channel_id"] = str(data["channel_id"]).strip()
            except Exception as e:
                print(f"Warning: could not read {config_path}: {e}", file=sys.stderr)
    return config


def send_telegram_message(bot_token: str, chat_id: str, text: str, disable_preview: bool = True):
    """Send an HTML-formatted message via the Telegram Bot API."""
    url = f"https://api.telegram.org/bot{bot_token}/sendMessage"
    payload = {
        "chat_id": chat_id,
        "text": text,
        "parse_mode": "HTML",
        "disable_web_page_preview": disable_preview,
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def send_telegram_poll(
    bot_token: str,
    chat_id: str,
    question: str,
    options: list[str],
    is_anonymous: bool = True,
    allows_multiple_answers: bool = False,
):
    """Send a poll via Telegram Bot API."""
    url = f"https://api.telegram.org/bot{bot_token}/sendPoll"
    # Format options for Telegram API
    formatted_options = [{"text": opt} for opt in options]
    payload = {
        "chat_id": chat_id,
        "question": question,
        "options": formatted_options,
        "is_anonymous": is_anonymous,
        "allows_multiple_answers": allows_multiple_answers,
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description="Post a poll to Telegram.")
    parser.add_argument(
        "--chat-id",
        default=DEFAULT_FEEDBACK_GROUP,
        help=f"Telegram chat username or ID (default: {DEFAULT_FEEDBACK_GROUP}).",
    )
    parser.add_argument(
        "--token",
        default=None,
        help="Telegram bot token (default: reads from config).",
    )
    parser.add_argument(
        "--question",
        required=True,
        help="Poll question.",
    )
    parser.add_argument(
        "--option",
        action="append",
        dest="options",
        required=True,
        help="Poll option (can be specified multiple times, 2-10 options).",
    )
    parser.add_argument(
        "--message",
        default=None,
        help="Optional companion text/HTML message to send immediately before the poll.",
    )
    parser.add_argument(
        "--public",
        action="store_true",
        help="Make the poll public (default is anonymous).",
    )
    parser.add_argument(
        "--multiple-answers",
        action="store_true",
        help="Allow multiple answers.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Show what would be posted without contacting Telegram API.",
    )

    args = parser.parse_args()

    if len(args.options) < 2 or len(args.options) > 10:
        print("Error: A poll must have between 2 and 10 options.", file=sys.stderr)
        sys.exit(1)

    config = load_config()
    token = args.token or config["bot_token"]

    if args.dry_run or not token:
        print("\n--- Telegram Poll Preview ---")
        print(f"Target Chat: {args.chat_id}")
        if args.message:
            print(f"\nCompanion Message:\n{args.message}\n")
        print(f"Question: {args.question}")
        print("Options:")
        for i, opt in enumerate(args.options, 1):
            print(f"  {i}. {opt}")
        print(f"Anonymous: {not args.public}")
        print(f"Multiple Answers: {args.multiple_answers}")
        if not token:
            print("\nError: No bot token configured.", file=sys.stderr)
            sys.exit(1)
        return

    # If companion message provided, send it first
    if args.message:
        print(f"Sending companion message to {args.chat_id}...")
        try:
            msg_res = send_telegram_message(token, args.chat_id, args.message)
            if msg_res.get("ok"):
                print(f"Message sent! Message ID: {msg_res['result']['message_id']}")
            else:
                print(f"Message warning: {msg_res}", file=sys.stderr)
        except urllib.error.HTTPError as e:
            print(f"Failed to send companion message: HTTP {e.code}: {e.read().decode('utf-8')}", file=sys.stderr)
            sys.exit(1)

    print(f"Sending poll to {args.chat_id}...")
    try:
        poll_res = send_telegram_poll(
            bot_token=token,
            chat_id=args.chat_id,
            question=args.question,
            options=args.options,
            is_anonymous=not args.public,
            allows_multiple_answers=args.multiple_answers,
        )
        if poll_res.get("ok"):
            msg_id = poll_res["result"]["message_id"]
            print(f"Successfully posted poll! Message ID: {msg_id}")
            print(f"Poll ID: {poll_res['result']['poll']['id']}")
        else:
            print(f"Poll API error: {poll_res}", file=sys.stderr)
            sys.exit(1)
    except urllib.error.HTTPError as e:
        print(f"HTTP Error {e.code}: {e.read().decode('utf-8')}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Failed to post poll: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()

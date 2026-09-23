#!/usr/bin/env python3
"""Format and post Expressive Launcher release notes to Telegram.

This script parses GitHub release notes Markdown, formats it into Telegram-compliant
HTML (with native <tg-spoiler> tags for riddles and <blockquote> for quotes), and
transmits the announcement (and optionally the signed APK document) via the
Telegram Bot API to the designated channel.
"""

import argparse
import html
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_CHANNEL = "@ExpressiveLauncher"
DEFAULT_FEEDBACK_GROUP = "https://t.me/ExpressiveLauncherFeedback"
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


def markdown_to_telegram_html(markdown_text: str) -> str:
    """Convert standard release notes markdown into Telegram-safe HTML."""
    text = markdown_text.strip()

    # 1. Remove HTML comments (e.g. <!-- expressive-qa ... -->)
    text = re.sub(r"<!--[\s\S]*?-->", "", text)

    # 2. Convert <details> / <summary> riddles into <tg-spoiler>
    def replace_details(match):
        inner_content = match.group(2).strip()
        # Clean leading <br/> or extra tags
        inner_content = re.sub(r"^<br\s*/?>\s*", "", inner_content, flags=re.IGNORECASE)
        return f"\n<tg-spoiler>{inner_content}</tg-spoiler>\n"

    text = re.sub(
        r"<details>\s*<summary>([\s\S]*?)</summary>([\s\S]*?)</details>",
        replace_details,
        text,
        flags=re.IGNORECASE,
    )

    # 3. Clean up horizontal rules
    text = re.sub(r"^[ \t]*---[ \t]*$", "───────────────────────", text, flags=re.MULTILINE)

    # 4. Convert headers (# Header -> <b>Header</b>)
    text = re.sub(r"^#{1,6}\s*(.+)$", r"<b>\1</b>", text, flags=re.MULTILINE)

    # 5. Convert markdown blockquotes (> text) into Telegram <blockquote>
    # Group consecutive blockquote lines
    lines = text.split("\n")
    processed_lines = []
    in_quote = False
    quote_buffer = []

    for line in lines:
        if line.startswith("> ") or line == ">":
            in_quote = True
            quote_buffer.append(line[2:] if line.startswith("> ") else "")
        else:
            if in_quote:
                quote_text = "\n".join(quote_buffer).strip()
                processed_lines.append(f"<blockquote>{quote_text}</blockquote>")
                in_quote = False
                quote_buffer = []
            processed_lines.append(line)
    if in_quote:
        quote_text = "\n".join(quote_buffer).strip()
        processed_lines.append(f"<blockquote>{quote_text}</blockquote>")

    text = "\n".join(processed_lines)

    # 6. Convert inline markdown bold (**text**) -> <b>text</b>
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)

    # 7. Convert inline markdown italic (*text* or _text_) -> <i>text</i>
    # Avoid matching inside words or links
    text = re.sub(r"(?<!\w)\*([^\*\n]+?)\*(?!\w)", r"<i>\1</i>", text)
    text = re.sub(r"(?<!\w)_([^_\n]+?)_(?!\w)", r"<i>\1</i>", text)

    # 8. Convert inline code (`code`) -> <code>code</code>
    text = re.sub(r"`([^`\n]+?)`", r"<code>\1</code>", text)

    # 9. Convert markdown links [label](url) -> <a href="url">label</a>
    text = re.sub(r"\[([^\]]+)\]\((https?://[^\)]+)\)", r'<a href="\2">\1</a>', text)

    # 10. Clean bullet points: replace "- " with "• "
    text = re.sub(r"^\s*-\s+", "• ", text, flags=re.MULTILINE)

    # 11. Normalize excess newlines (3+ to 2)
    text = re.sub(r"\n{3,}", "\n\n", text)

    return text.strip()


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


def send_telegram_document(bot_token: str, chat_id: str, file_path: Path, caption: str = ""):
    """Send a document file (such as an APK) via multipart/form-data."""
    import mimetypes
    import uuid

    url = f"https://api.telegram.org/bot{bot_token}/sendDocument"
    boundary = f"----WebKitFormBoundary{uuid.uuid4().hex}"
    
    body = bytearray()
    
    # chat_id field
    body.extend(f"--{boundary}\r\n".encode("utf-8"))
    body.extend(b'Content-Disposition: form-data; name="chat_id"\r\n\r\n')
    body.extend(f"{chat_id}\r\n".encode("utf-8"))
    
    # caption field
    if caption:
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(b'Content-Disposition: form-data; name="caption"\r\n\r\n')
        body.extend(f"{caption}\r\n".encode("utf-8"))
        
    # parse_mode field
    body.extend(f"--{boundary}\r\n".encode("utf-8"))
    body.extend(b'Content-Disposition: form-data; name="parse_mode"\r\n\r\n')
    body.extend(b"HTML\r\n")

    # document field
    filename = file_path.name
    mime_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    body.extend(f"--{boundary}\r\n".encode("utf-8"))
    body.extend(
        f'Content-Disposition: form-data; name="document"; filename="{filename}"\r\n'.encode("utf-8")
    )
    body.extend(f"Content-Type: {mime_type}\r\n\r\n".encode("utf-8"))
    body.extend(file_path.read_bytes())
    body.extend(b"\r\n")
    
    body.extend(f"--{boundary}--\r\n".encode("utf-8"))
    
    req = urllib.request.Request(
        url,
        data=bytes(body),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description="Post release notes to Telegram.")
    parser.add_argument(
        "--notes-file",
        type=Path,
        required=True,
        help="Path to markdown release notes file.",
    )
    parser.add_argument(
        "--channel",
        default="",
        help=f"Telegram channel username or chat ID (default: {DEFAULT_CHANNEL}).",
    )
    parser.add_argument(
        "--token",
        default="",
        help="Telegram bot token (default: reads from TELEGRAM_BOT_TOKEN or config).",
    )
    parser.add_argument(
        "--apk",
        type=Path,
        default=None,
        help="Optional path to signed APK file to upload.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("TELEGRAM_CHANGELOG.txt"),
        help="Path to write rendered Telegram changelog (default: TELEGRAM_CHANGELOG.txt).",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Only render and save the changelog without transmitting via Telegram API.",
    )

    args = parser.parse_args()

    if not args.notes_file.is_file():
        parser.error(f"Notes file not found: {args.notes_file}")

    config = load_config()
    token = args.token.strip() or config["bot_token"]
    channel = args.channel.strip() or config["channel_id"]

    raw_notes = args.notes_file.read_text(encoding="utf-8")
    telegram_html = markdown_to_telegram_html(raw_notes)

    # Save rendered output
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(telegram_html, encoding="utf-8")
    print(f"Rendered Telegram changelog saved to {args.output}")

    if args.dry_run:
        print("\n--- Rendered Telegram HTML (Dry Run) ---")
        print(telegram_html)
        print("----------------------------------------\n")
        return

    if not token:
        print("\nNotice: Telegram bot token not configured.", file=sys.stderr)
        print(
            "To enable automated posting, provide --token <TOKEN>, export TELEGRAM_BOT_TOKEN=<TOKEN>,\n"
            "or create ~/Library/Application Support/Expressive CI/config/telegram.json with {\"bot_token\": \"...\"}.",
            file=sys.stderr,
        )
        print("\nThe rendered message has been saved to TELEGRAM_CHANGELOG.txt and is ready to post:\n")
        print(telegram_html)
        return

    print(f"Posting release announcement to Telegram channel: {channel}...")
    try:
        res = send_telegram_message(token, channel, telegram_html)
        if res.get("ok"):
            msg_id = res.get("result", {}).get("message_id")
            print(f"Successfully posted to Telegram! Message ID: {msg_id}")
        else:
            print(f"Telegram API response: {res}", file=sys.stderr)
            sys.exit(1)
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        print(f"Telegram API HTTP error {e.code}: {error_body}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Failed to post to Telegram: {e}", file=sys.stderr)
        sys.exit(1)

    if args.apk:
        if args.apk.is_file():
            print(f"Uploading APK document {args.apk.name} to {channel}...")
            try:
                doc_res = send_telegram_document(token, channel, args.apk, caption=f"📦 {args.apk.name}")
                if doc_res.get("ok"):
                    print("APK document uploaded successfully to Telegram.")
                else:
                    print(f"Telegram document API response: {doc_res}", file=sys.stderr)
            except Exception as e:
                print(f"Warning: APK upload failed: {e}", file=sys.stderr)
        else:
            print(f"Warning: APK file not found at {args.apk}", file=sys.stderr)


if __name__ == "__main__":
    main()

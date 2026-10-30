#!/usr/bin/env python3
"""Format and post Expressive Launcher release notes to Telegram.

This script parses GitHub release notes Markdown, formats it into Telegram-compliant
HTML (with native <tg-spoiler> tags for riddles and <blockquote> for quotes), and
transmits the announcement (and optionally the signed APK document) via the
Telegram Bot API to the designated channel.
"""

import sys

if sys.version_info < (3, 11):
    sys.exit("Python 3.11+ is required (this is %d.%d); run it with /opt/homebrew/bin/python3.14" % sys.version_info[:2])

import argparse
import html
import json
import os
from pathlib import Path
import re
import socket
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

# Prefer IPv4 on macOS to avoid IPv6 route hang/timeout to api.telegram.org
_orig_getaddrinfo = socket.getaddrinfo


def _ipv4_getaddrinfo(host, port, family=0, *args, **kwargs):
    try:
        return _orig_getaddrinfo(host, port, socket.AF_INET, *args, **kwargs)
    except Exception:
        return _orig_getaddrinfo(host, port, family, *args, **kwargs)


socket.getaddrinfo = _ipv4_getaddrinfo

DEFAULT_CHANNEL = "@ExpressiveGroup"
# Telegram's limit is 4,096 characters of visible text (tags don't count), measured in UTF-16 code units.
TELEGRAM_MESSAGE_LIMIT = 4096
DIVIDER = "───────────────────────"
# Raw HTML the notes may contain on purpose; any other <, > or & is text and gets escaped.
NOTES_HTML = re.compile(
    r"</?(?:b|strong|i|em|u|ins|s|strike|del|code|pre|details|summary|tg-spoiler|blockquote)>"
    r"|<br\s*/?>|<a href=\"[^\"<>]*\">|</a>",
    re.IGNORECASE,
)
TELEGRAM_TAGS = {"b", "strong", "i", "em", "u", "ins", "s", "strike", "del", "a", "code", "pre", "blockquote",
                 "tg-spoiler"}
ENTITY = r"&(?:#\d+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);"
MARKUP = re.compile(rf"<[^<>]*>|{ENTITY}")
TAG = re.compile(r"<(/?)([a-zA-Z][\w-]*)([^<>]*)>")
DIVIDER_RUN = rf"{DIVIDER}[─\s]*"
# Telegram Desktop's markdown, by the HTML tag it stands for. <u> and <ins> have none.
DESKTOP_MARKERS = {"b": "**", "strong": "**", "i": "__", "em": "__", "tg-spoiler": "||", "code": "`",
                   "pre": "```", "s": "~~", "strike": "~~", "del": "~~"}
# Distinct from API failures (1) and usage errors (2): the notes were rendered but nothing was sent.
EXIT_NOT_CONFIGURED = 3
# The rendered notes would be refused (too long a word, bad markup, nothing to post); nothing was sent.
EXIT_NOT_POSTABLE = 4
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

    # 1b. Escape text so a stray <, > or & can't break Telegram's HTML parser. A leading "> " stays a
    # quote marker (step 5 reads it as "&gt; ").
    # An entity the notes spell out (&amp;, &#42;) shows as its character, except in `code`, which GitHub
    # shows as typed. It stands in as a placeholder until after the Markdown steps, so &#42; stays a "*".
    entities = []

    def hold(match):
        entities.append(html.escape(html.unescape(match.group()), quote=False))
        return f"\ue000{chr(0xE100 + len(entities) - 1)}"

    def escape_text(part):
        pieces = re.split(r"(`[^`\n]+`)", part)
        held = "".join(piece if piece.startswith("`") else re.sub(ENTITY, hold, piece) for piece in pieces)
        return held.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    parts = NOTES_HTML.split(text)
    tags = NOTES_HTML.findall(text)
    text = "".join(escape_text(part) + (tags[i] if i < len(tags) else "") for i, part in enumerate(parts))

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
    # Telegram has no <br>.
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.IGNORECASE)

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
        if line.startswith("&gt; ") or line == "&gt;":
            in_quote = True
            quote_buffer.append(line[5:] if line.startswith("&gt; ") else "")
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

    # 9. Convert markdown links [label](url) -> <a href="url">label</a>. Telegram only opens web links,
    # so another scheme (obtainium://) shows its address as code, which a tap copies.
    text = re.sub(r"\[([^\]]+)\]\((https?://[^\)]+)\)", r'<a href="\2">\1</a>', text)
    text = re.sub(r"\[([^\]]+)\]\(([a-zA-Z][a-zA-Z0-9+.-]*://[^\s)]+)\)", r"\1: <code>\2</code>", text)

    # 10. Bullets: "- " becomes "• " (taking the blank lines before it, as before) and an indented "- " a
    # nested "◦ ". An indented paragraph inside a list is the item's own, so it stays under the item,
    # indented; any other indented line loses its indent.
    lines = []
    in_list = False
    for line in text.split("\n"):
        content = line.lstrip(" \t")
        indented = len(content) < len(line)
        bullet = re.match(r"-\s+", content)
        if not content:
            lines.append("")
        elif bullet and not indented:
            while lines and not lines[-1]:
                lines.pop()
            lines.append("• " + content[bullet.end():])
            in_list = True
        elif bullet:
            lines.append("    ◦ " + content[bullet.end():])
            in_list = True
        elif indented and in_list:
            while lines and not lines[-1]:
                lines.pop()
            lines.append("   " + content)
        else:
            lines.append(content)
            in_list = False
    text = "\n".join(lines)

    # 11. Normalize excess newlines (3+ to 2)
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = re.sub("\ue000(.)", lambda m: entities[ord(m.group(1)) - 0xE100], text)

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
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except (urllib.error.URLError, socket.error, TimeoutError) as e:
            if attempt == 2:
                raise
            time.sleep(2)


def visible_length(html_text: str) -> int:
    """Characters Telegram counts toward its limit: text without tags, entities decoded, in UTF-16 units."""
    text = html.unescape(re.sub(r"<[^<>]*>", "", html_text))
    return len(text.encode("utf-16-le")) // 2


def _visible_prefix(html_text: str) -> list[int]:
    """prefix[i] is the visible length of html_text[:i]; a tag adds nothing, an entity adds its character."""
    prefix = [0]
    position = 0

    def add_text(chunk):
        for character in chunk:
            prefix.append(prefix[-1] + (2 if ord(character) > 0xFFFF else 1))

    for match in MARKUP.finditer(html_text):
        add_text(html_text[position:match.start()])
        markup = match.group()
        width = 0 if markup.startswith("<") else visible_length(markup)
        prefix.extend([prefix[-1]] * (len(markup) - 1))
        prefix.append(prefix[-1] + width)
        position = match.end()
    add_text(html_text[position:])
    return prefix


def _balance_tags(chunks: list[str]) -> list[str]:
    """Close the tags still open at the end of each chunk and reopen them at the start of the next."""
    balanced = []
    open_tags = []
    for chunk in chunks:
        reopened = "".join(markup for _, markup in open_tags)
        for match in TAG.finditer(chunk):
            name = match.group(2).lower()
            if match.group(1):
                if open_tags and open_tags[-1][0] == name:
                    open_tags.pop()
            elif not match.group(3).rstrip().endswith("/"):
                open_tags.append((name, match.group()))
        closing = "".join(f"</{name}>" for name, _ in reversed(open_tags))
        balanced.append(reopened + chunk + closing)
    return balanced


def chunk_telegram_html(html_text: str, max_chars: int = 3800) -> list[str]:
    """Split rendered HTML into messages of at most max_chars visible characters.

    Each message ends at the last divider that fits, else the last blank line, line break or space, and
    never inside a tag or entity. A tag left open at a split (a quote, say) is closed there and reopened in
    the next message, so every message parses on its own.
    """
    text = html_text.strip()
    prefix = _visible_prefix(text)
    if prefix[-1] <= max_chars:
        return [text] if text else []

    inside_markup = [False] * (len(text) + 1)
    for match in MARKUP.finditer(text):
        for index in range(match.start() + 1, match.end()):
            inside_markup[index] = True
    breaks = []  # (priority, start, end): a message may end at start and the next start at end.
    patterns = (r"[ \t]+", r"\n", r"\n(?=• |<b>|\d+\. )", r"\n[ \t]*\n\s*", rf"\s*{DIVIDER_RUN}")
    for priority, pattern in enumerate(patterns):
        for m in re.finditer(pattern, text):
            if inside_markup[m.start()]:
                continue
            # Ending a message on a heading ("🔗 <b>Links</b>:", "• <b>Open</b>:") strands it from its
            # section, so that is the last resort.
            before = text[:m.start()].rstrip()
            line = before[before.rfind("\n") + 1:]
            heading = re.fullmatch(r"[^<\n]{0,8}<b>[^\n]*</b>:?", line) is not None
            breaks.append((-1 if heading else priority, m.start(), m.end()))

    chunks = []
    start = 0
    while prefix[-1] - prefix[start] > max_chars:
        limit = prefix[start] + max_chars
        fitting = [b for b in breaks if b[1] > start and prefix[b[1]] <= limit]
        if fitting:
            _, cut, start_next = max(fitting)
        else:
            # One word longer than a message: cut it at the last character that fits.
            cuts = [i for i in range(start + 1, len(text)) if not inside_markup[i] and prefix[i] <= limit]
            if not cuts:
                raise ValueError(f"can't split the announcement near character {start}")
            cut = cuts[-1]
            start_next = cut
        chunks.append(text[start:cut])
        start = start_next
    chunks.append(text[start:])

    # A piece with no text of its own (a divider, or only a tag cut off at a split) joins its neighbour.
    merged, carry = [], ""
    for chunk in chunks:
        chunk = re.sub(rf"^\s*{DIVIDER_RUN}|\s*{DIVIDER_RUN}$", "", chunk).strip()
        if not _has_text(chunk):
            if merged:
                merged[-1] += chunk
            else:
                carry += chunk
            continue
        merged.append(carry + chunk)
        carry = ""
    if carry and merged:
        merged[-1] += carry
    return _balance_tags(merged)


def _has_text(html_text: str) -> bool:
    return bool(html.unescape(re.sub(r"<[^<>]*>", "", html_text)).strip())


def check_telegram_chunks(chunks: list[str], limit: int = TELEGRAM_MESSAGE_LIMIT) -> None:
    """Raise ValueError if any message would be refused, so a post never stops halfway."""
    if not chunks:
        raise ValueError("there is nothing to post")
    for number, chunk in enumerate(chunks, 1):
        length = visible_length(chunk)
        if length > limit:
            raise ValueError(f"message {number} has {length} characters; Telegram allows {limit}")
        if not _has_text(chunk):
            raise ValueError(f"message {number} has no text")
        stack = []
        for match in re.finditer(r"<[^<>]*>|<", chunk):
            tag = TAG.fullmatch(match.group())
            name = tag.group(2).lower() if tag else ""
            if name not in TELEGRAM_TAGS:
                raise ValueError(f"message {number} has markup Telegram can't parse: {match.group()!r}")
            if not tag.group(1):
                stack.append(name)
            elif not stack or stack.pop() != name:
                raise ValueError(f"message {number} closes <{name}> out of order")
        if stack:
            raise ValueError(f"message {number} leaves <{stack[-1]}> open")


def _desktop_markdown(html_text: str) -> tuple[str, list[str]]:
    """The message as Telegram Desktop markdown, and the spots where that markdown won't come out right."""
    def link(match):
        href, label = match.group(1), match.group(2)
        url = html.unescape(href)
        # A bare address, or an @name for its own t.me link, is linked by Telegram already.
        if html.unescape(label) in (url, "@" + url.removeprefix("https://t.me/")):
            return label
        return f"{label}: {href}"

    text = re.sub(r'<a href="([^"]*)">([\s\S]*?)</a>', link, html_text)
    out, markers, problems, open_tags = [], [], [], []
    position = 0
    for match in list(TAG.finditer(text)) + [None]:
        piece = html.unescape(text[position:match.start() if match else len(text)])
        if not open_tags or open_tags[0] not in ("code", "pre"):
            for literal in ("**", "__", "||", "~~", "`"):
                if literal in piece:
                    at = piece.find(literal)
                    context = piece[max(0, at - 25):at + 25].replace("\n", " ")
                    problems.append(f"{literal!r} in the text will turn into formatting: {context!r}")
        out.append(piece)
        if not match:
            break
        position = match.end()
        name = match.group(2).lower()
        if name not in DESKTOP_MARKERS:
            continue
        # Telegram Desktop applies only the outermost marker, so a nested one would show as text.
        if not match.group(1):
            open_tags.append(name)
            if len(open_tags) == 1:
                markers.append((len("".join(out)), DESKTOP_MARKERS[name], True))
                out.append(DESKTOP_MARKERS[name])
        elif open_tags:
            open_tags.pop()
            if not open_tags:
                markers.append((len("".join(out)), DESKTOP_MARKERS[name], False))
                out.append(DESKTOP_MARKERS[name])
    result = "".join(out)

    # A marker counts only next to a space, the line's edge, one of Telegram Desktop's separators, or an
    # emoji (its own text fragment).
    def stuck(character):
        emoji = ord(character) >= 0x1F000 or character in "\ufe0f\u200d" or unicodedata.category(character) == "So"
        return not (character.isspace() or character in ".,:;<>|'\"[]{}!?%^()-+=" or emoji)

    for index, marker, opening in markers:
        neighbour = result[index - 1:index] if opening else result[index + len(marker):index + len(marker) + 1]
        if neighbour and stuck(neighbour):
            context = result[max(0, index - 20):index + len(marker) + 20].replace("\n", " ")
            problems.append(f"{marker} next to {neighbour!r} won't format: {context!r}")
    return result, problems


def telegram_html_to_desktop_markdown(html_text: str) -> str:
    """One message as Telegram Desktop's own markdown, for posting by hand without a bot.

    Telegram Desktop formats **bold**, __italic__, ||spoiler||, ~~strikethrough~~, `code` and ```pre```
    when the message is sent, and links bare web addresses itself. It applies only the outermost of nested
    markers, so only those are written. It has no markdown for quotes or underline, so those stay plain.
    """
    return _desktop_markdown(html_text)[0]


def desktop_length(markdown: str) -> int:
    """Characters a Telegram Desktop message counts once its markdown markers are applied."""
    return len(re.sub(r"\*\*|__|\|\||~~|`", "", markdown).encode("utf-16-le")) // 2


def desktop_parts(html_text: str, max_chars: int = 3800) -> tuple[list[str], list[str]]:
    """Split for Telegram Desktop, where a link's address counts too, and report formatting problems."""
    for budget in range(max_chars, 0, -200):
        converted = [_desktop_markdown(chunk) for chunk in chunk_telegram_html(html_text, budget)]
        if all(desktop_length(text) <= TELEGRAM_MESSAGE_LIMIT for text, _ in converted):
            problems = [f"part {n}: {p}" for n, (_, found) in enumerate(converted, 1) for p in found]
            return [text for text, _ in converted], problems
    raise ValueError("the announcement doesn't fit Telegram Desktop's message limit")


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
        "--desktop-parts",
        type=Path,
        default=None,
        help="Also write each message as Telegram Desktop markdown to DIR/part-N.txt, for posting by hand.",
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

    try:
        # The whole post first: a split closes any tag left open, which would hide it in the messages.
        check_telegram_chunks([telegram_html], limit=sys.maxsize)
        chunks = chunk_telegram_html(telegram_html)
        check_telegram_chunks(chunks)
        parts, problems = desktop_parts(telegram_html) if args.desktop_parts else ([], [])
    except ValueError as error:
        print(f"The rendered announcement can't be posted, so nothing was sent: {error}", file=sys.stderr)
        sys.exit(EXIT_NOT_POSTABLE)
    sizes = ", ".join(str(visible_length(chunk)) for chunk in chunks)
    print(f"Announcement fits in {len(chunks)} message(s) of {sizes} characters.")
    if args.desktop_parts:
        args.desktop_parts.mkdir(parents=True, exist_ok=True)
        for stale in args.desktop_parts.glob("part-*.txt"):
            stale.unlink()
        for number, text in enumerate(parts, 1):
            part = args.desktop_parts / f"part-{number}.txt"
            part.write_text(text + "\n", encoding="utf-8")
            print(f"Telegram Desktop message {number} ({desktop_length(text)} characters): {part}")
        for problem in problems:
            print(f"Warning: Telegram Desktop {problem}", file=sys.stderr)

    if args.dry_run:
        print("\n--- Rendered Telegram HTML (Dry Run) ---")
        print(telegram_html)
        print("----------------------------------------\n")
        return

    if not token:
        print("\nNotice: Telegram bot token not configured; the announcement was NOT broadcast.", file=sys.stderr)
        print(
            "To enable automated posting, provide --token <TOKEN>, export TELEGRAM_BOT_TOKEN=<TOKEN>,\n"
            "or create ~/Library/Application Support/Expressive CI/config/telegram.json with {\"bot_token\": \"...\"}.",
            file=sys.stderr,
        )
        print(f"\nThe rendered message has been saved to {args.output} and is ready to post:\n")
        print(telegram_html)
        sys.exit(EXIT_NOT_CONFIGURED)

    print(f"Posting release announcement to Telegram channel: {channel}...")
    try:
        for idx, chunk in enumerate(chunks, 1):
            if idx > 1:
                time.sleep(1.5)
            res = send_telegram_message(token, channel, chunk)
            if res.get("ok"):
                msg_id = res.get("result", {}).get("message_id")
                print(f"Successfully posted chunk {idx}/{len(chunks)} to Telegram! Message ID: {msg_id}")
            else:
                print(f"Telegram API response for chunk {idx}: {res}", file=sys.stderr)
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

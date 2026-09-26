#!/usr/bin/env python3
"""Format Expressive Launcher release notes into XDA-compliant BBCode.

Converts release notes Markdown into XDA BBCode with proper [B], [I], [URL],
[LIST], [CODE], [ICODE], [HR], and [SPOILER] formatting.
"""

import argparse
from pathlib import Path
import re
import sys


def markdown_to_xda_bbcode(markdown_text: str, version_name: str, version_code: str) -> str:
    text = markdown_text.strip()

    # 1. Remove HTML comments
    text = re.sub(r"<!--[\s\S]*?-->", "", text)

    # 2. Extract Top Title / Subtitle if present
    first_heading_match = re.search(r"^#\s+(.+)$", text, re.MULTILINE)
    title = first_heading_match.group(1).strip() if first_heading_match else f"Expressive Launcher {version_name} (Build {version_code})"
    
    # Remove the first # Header from body
    text = re.sub(r"^#\s+.+$", "", text, count=1, flags=re.MULTILINE).strip()

    # 3. Handle <details><summary>Title</summary>Body</details> -> [SPOILER="Title"]Body[/SPOILER]
    def replace_details(match):
        summary = match.group(1).strip()
        body = match.group(2).strip()
        # Clean <b> inside summary if any
        summary = re.sub(r"</?b>", "", summary, flags=re.IGNORECASE).strip()
        return f'[SPOILER="{summary}"]{body}[/SPOILER]'

    text = re.sub(
        r"<details>\s*<summary>([\s\S]*?)</summary>([\s\S]*?)</details>",
        replace_details,
        text,
        flags=re.IGNORECASE,
    )

    # 4. Convert Horizontal Rules (---) -> [HR][/HR]
    text = re.sub(r"^[ \t]*---[ \t]*$", "[HR][/HR]", text, flags=re.MULTILINE)

    # 5. Convert Headers:
    # ### Header -> [SIZE=5][B]Header[/B][/SIZE]
    # ## Header -> [SIZE=5][B]Header[/B][/SIZE]
    text = re.sub(r"^#{2,6}\s*(.+)$", r"[SIZE=5][B]\1[/B][/SIZE]", text, flags=re.MULTILINE)

    # 6. Convert Code Blocks (```lang ... ```) -> [CODE]...[/CODE]
    text = re.sub(r"```[a-zA-Z0-9_-]*\n([\s\S]*?)```", r"[CODE]\1[/CODE]", text)

    # 7. Convert Inline Code (`code`) -> [ICODE]code[/ICODE]
    text = re.sub(r"`([^`\n]+)`", r"[ICODE]\1[/ICODE]", text)

    # 8. Convert Bold (**text** or __text__) -> [B]text[/B]
    text = re.sub(r"\*\*([^*]+)\*\*", r"[B]\1[/B]", text)
    text = re.sub(r"__([^_]+)__", r"[B]\1[/B]", text)

    # 9. Convert Italic (*text* or _text_) -> [I]text[/I]
    text = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", r"[I]\1[/I]", text)

    # 10. Convert Markdown links [Label](URL) -> [URL="URL"]Label[/URL]
    text = re.sub(r"\[([^\]]+)\]\((https?://[^\s)]+)\)", r'[URL="\2"]\1[/URL]', text)

    # 11. Convert Bullet Lists: consecutive lines starting with - or * into [LIST]...[/LIST]
    lines = text.split("\n")
    processed_lines = []
    in_list = False

    for line in lines:
        stripped = line.strip()
        bullet_match = re.match(r"^[-*]\s+(.+)$", stripped)
        if bullet_match:
            if not in_list:
                processed_lines.append("[LIST]")
                in_list = True
            item_text = bullet_match.group(1).strip()
            processed_lines.append(f"[*]{item_text}")
        else:
            if in_list and stripped != "":
                processed_lines.append("[/LIST]")
                in_list = False
            processed_lines.append(line)

    if in_list:
        processed_lines.append("[/LIST]")

    body = "\n".join(processed_lines)

    # 12. Build the final XDA post with centered header
    header = (
        f"[CENTER][SIZE=6][B]{title}[/B][/SIZE]\n"
        f"[SIZE=4][I]Official Release Announcement & Download Links[/I][/SIZE][/CENTER]\n\n"
        f"[HR][/HR]\n\n"
    )

    footer = (
        f"\n\n[HR][/HR]\n"
        f"[SIZE=5][B]🔗 Downloads & Official Links[/B][/SIZE]\n"
        f"[LIST]\n"
        f'[*][B]Google Play Closed Alpha / Internal Track:[/B] [URL="https://play.google.com/apps/internaltest/4700941727845829659"]Play Store Opt-In Link[/URL]\n'
        f'[*][B]GitHub Releases & APK:[/B] [URL="https://github.com/denson9874/ExpressiveLauncher/releases/tag/qa-v{version_name}-{version_code}"]GitHub Release qa-v{version_name}-{version_code}[/URL]\n'
        f'[*][B]Companion Feed Landing Page:[/B] [URL="https://denson9874.github.io/ExpressiveLauncher/feed/"]https://denson9874.github.io/ExpressiveLauncher/feed/[/URL]\n'
        f'[*][B]Telegram Channel:[/B] [URL="https://t.me/ExpressiveLauncher"]@ExpressiveLauncher[/URL]\n'
        f'[*][B]Telegram Community & Feedback:[/B] [URL="https://t.me/ExpressiveLauncherFeedback"]@ExpressiveLauncherFeedback[/URL]\n'
        f"[/LIST]"
    )

    return header + body.strip() + footer + "\n"


def main():
    parser = argparse.ArgumentParser(description="Convert Release Notes Markdown to XDA BBCode")
    parser.add_argument("input_file", help="Path to release notes Markdown file")
    parser.add_argument("--output", help="Path to output .bbcode file (default: replaces .md with .bbcode)")
    parser.add_argument("--version-name", default="", help="Version name (e.g. 3.0.9)")
    parser.add_argument("--version-code", default="", help="Version code (e.g. 41)")
    args = parser.parse_args()

    input_path = Path(args.input_file)
    if not input_path.exists():
        print(f"Error: input file {input_path} does not exist.", file=sys.stderr)
        sys.exit(1)

    raw_text = input_path.read_text(encoding="utf-8")
    version_name = args.version_name
    version_code = args.version_code

    if not version_name or not version_code:
        # Try inferring from filename or content
        m = re.search(r"(\d+\.\d+\.\d+)", input_path.stem)
        if m and not version_name:
            version_name = m.group(1)

    bbcode = markdown_to_xda_bbcode(raw_text, version_name or "3.0.9", version_code or "41")
    output_path = Path(args.output) if args.output else input_path.with_suffix(".bbcode")
    output_path.write_text(bbcode, encoding="utf-8")
    print(f"Successfully generated XDA BBCode at: {output_path}")


if __name__ == "__main__":
    main()

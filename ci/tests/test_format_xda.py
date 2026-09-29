"""XDA renders BBCode only; leftover Markdown or HTML shows up as literal text in the forum post."""

import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).parents[2]
SPEC = importlib.util.spec_from_file_location("format_xda", ROOT / "scripts" / "format_xda.py")
format_xda = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(format_xda)


def convert(markdown: str) -> str:
    return format_xda.markdown_to_xda_bbcode(markdown, "9.9.9", "99")


class FormatXdaTests(unittest.TestCase):
    def test_non_http_links_become_url_tags(self):
        out = convert("# T\n\n- **Obtainium**: [Add to Obtainium](obtainium://add/https://github.com/x/y)")
        self.assertIn('[URL="obtainium://add/https://github.com/x/y"]Add to Obtainium[/URL]', out)
        self.assertNotIn("](obtainium", out)

    def test_html_bold_and_italic_inside_a_spoiler_become_bbcode(self):
        out = convert("# T\n\n<details>\n<summary><b>Tap</b></summary>\n<b>Answer</b> and <i>why</i>\n</details>")
        self.assertIn('[SPOILER="Tap"][B]Answer[/B] and [I]why[/I][/SPOILER]', out)
        self.assertNotIn("<b>", out)

    def test_consecutive_quote_lines_become_one_quote_and_blank_lines_split_quotes(self):
        out = convert("# T\n\n> **Q:** *One?*\n> **A:** *Two.*\n\n> Three.")
        self.assertIn("[QUOTE][B]Q:[/B] [I]One?[/I]\n[B]A:[/B] [I]Two.[/I][/QUOTE]", out)
        self.assertIn("[QUOTE]Three.[/QUOTE]", out)
        self.assertNotIn("\n> ", out)

    def test_published_announcement_has_no_leftover_markdown_or_html(self):
        notes = ROOT / "docs" / "release_notes" / "3.1.5_announcement.md"
        out = convert(notes.read_text(encoding="utf-8"))
        for leftover in ("<b>", "</b>", "<details>", "](", "\n> "):
            self.assertNotIn(leftover, out, leftover)


if __name__ == "__main__":
    unittest.main()

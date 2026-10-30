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

    def test_release_link_uses_the_qa_tag_for_qa_notes(self):
        out = convert("# Expressive Launcher 9.9.9 QA — T\n\nBody")
        self.assertIn("/releases/tag/qa-v9.9.9-99", out)

    def test_release_link_uses_the_stable_tag_for_stable_notes(self):
        out = convert("# Expressive Launcher 9.9.9 Stable — T\n\nBody")
        self.assertIn("/releases/tag/v9.9.9-99", out)
        self.assertNotIn("qa-v9.9.9", out)

    def test_explicit_channel_overrides_the_title(self):
        out = format_xda.markdown_to_xda_bbcode("# T\n\nBody", "9.9.9", "99", channel="release")
        self.assertIn("/releases/tag/v9.9.9-99", out)

    def test_footer_links_the_live_telegram_channel(self):
        out = convert("# T\n\nBody")
        self.assertIn('[URL="https://t.me/ExpressiveGroup"]@ExpressiveGroup[/URL]', out)
        self.assertNotRegex(out, r"t\.me/ExpressiveLauncher\b")  # the moved stub; ...LauncherFeedback stays

    def test_indented_bullets_become_a_nested_list(self):
        out = convert("# T\n\n- **Open**:\n  - One\n  - Two\n- Three\n\nAfter")
        self.assertIn("[LIST]\n[*][B]Open[/B]:\n[LIST]\n[*]One\n[*]Two\n[/LIST]\n[*]Three\n\n[/LIST]\nAfter", out)

    def test_an_indented_paragraph_stays_in_its_list_item(self):
        out = convert("# T\n\n- One\n\n  More about one.\n- Two")
        self.assertIn("[LIST]\n[*]One\n\nMore about one.\n[*]Two\n[/LIST]", out)

    def test_a_paragraph_after_a_sub_list_belongs_to_the_parent_item(self):
        out = convert("# T\n\n- Validation\n  - Taskbar\n\n  Reports welcome.\n- Support")
        self.assertIn("[*]Validation\n[LIST]\n[*]Taskbar\n\n[/LIST]\nReports welcome.\n[*]Support\n[/LIST]", out)
        out = convert("# T\n\n1. Item\n   - a\n\n   Para\n2. Next")
        self.assertIn("[*]a\n\n[/LIST]\nPara\n2. Next", out)

    def test_tabs_and_uneven_indents_nest_like_markdown(self):
        self.assertIn("[*]a\n[LIST]\n[*]tabbed\n[*]spaced\n[/LIST]\n[*]b", convert("# T\n\n- a\n\t- tabbed\n    - spaced\n- b"))
        self.assertIn("[*]a\n[LIST]\n[*]b\n[*]c\n[/LIST]\n[*]d", convert("# T\n\n- a\n    - b\n  - c\n- d"))

    def test_every_announcement_has_balanced_lists_and_no_indented_lines(self):
        for notes in sorted((ROOT / "docs" / "release_notes").glob("*_announcement.md")):
            with self.subTest(notes=notes.name):
                out = convert(notes.read_text(encoding="utf-8"))
                depth = 0
                for line in out.split("\n"):
                    depth += line.count("[LIST]") - line.count("[/LIST]")
                    self.assertGreaterEqual(depth, 0)
                    self.assertNotRegex(line, r"^[ \t]+\S", line)
                self.assertEqual(0, depth)

    def test_published_announcement_has_no_leftover_markdown_or_html(self):
        notes = ROOT / "docs" / "release_notes" / "3.1.5_announcement.md"
        out = convert(notes.read_text(encoding="utf-8"))
        for leftover in ("<b>", "</b>", "<details>", "](", "\n> "):
            self.assertNotIn(leftover, out, leftover)


if __name__ == "__main__":
    unittest.main()

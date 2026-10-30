#!/usr/bin/env python3
"""Tests for scripts/post_telegram.py formatting and configuration loading."""

import contextlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import scripts.post_poll as post_poll
import scripts.post_telegram as post_telegram
import scripts.release_unified as release_unified

ROOT = Path(__file__).parents[2]


class PostTelegramFormattingTests(unittest.TestCase):
    def test_strip_html_comments(self):
        md = "Title\n<!-- expressive-qa source=abc sha256=123 -->\nEnd"
        html = post_telegram.markdown_to_telegram_html(md)
        self.assertNotIn("<!--", html)
        self.assertNotIn("expressive-qa", html)
        self.assertIn("Title", html)
        self.assertIn("End", html)

    def test_riddle_details_converted_to_tg_spoiler(self):
        md = """### 🧩 Release Riddle
<details>
<summary><b>Click to reveal the answer</b></summary>
<br/>
<b>The App Drawer!</b>
</details>"""
        html = post_telegram.markdown_to_telegram_html(md)
        self.assertIn("<tg-spoiler><b>The App Drawer!</b></tg-spoiler>", html)
        self.assertNotIn("<details>", html)
        self.assertNotIn("<summary>", html)

    def test_markdown_formatting_conversions(self):
        md = """# Release Title
Welcome to **Expressive Launcher**!
- Check `feature_flag` here.
- See [Issue #17](https://github.com/denson9874/ExpressiveLauncher/issues/17).

> This is a quote.
> Second line of quote."""
        html = post_telegram.markdown_to_telegram_html(md)
        self.assertIn("<b>Release Title</b>", html)
        self.assertIn("<b>Expressive Launcher</b>", html)
        self.assertIn("<code>feature_flag</code>", html)
        self.assertIn('<a href="https://github.com/denson9874/ExpressiveLauncher/issues/17">Issue #17</a>', html)
        self.assertIn("<blockquote>This is a quote.\nSecond line of quote.</blockquote>", html)

    def test_load_config_defaults_and_env(self):
        with patch.dict(os.environ, {"TELEGRAM_BOT_TOKEN": "test_token_123", "TELEGRAM_CHANNEL_ID": "@custom_channel"}):
            config = post_telegram.load_config()
            self.assertEqual(config["bot_token"], "test_token_123")
            self.assertEqual(config["channel_id"], "@custom_channel")

    def test_send_message_dispatches_json(self):
        with patch("urllib.request.urlopen") as mock_urlopen:
            mock_response = MagicMock()
            mock_response.read.return_value = b'{"ok": true, "result": {"message_id": 999}}'
            mock_urlopen.return_value.__enter__.return_value = mock_response

            res = post_telegram.send_telegram_message("test_tok", "@test_chan", "<b>Hello</b>")
            self.assertTrue(res.get("ok"))
            self.assertEqual(res["result"]["message_id"], 999)


class TelegramMarkupTests(unittest.TestCase):
    def render(self, markdown):
        return post_telegram.markdown_to_telegram_html(markdown)

    def test_text_is_escaped_but_the_notes_own_tags_are_kept(self):
        html = self.render("Settings > Colors & more, <5% of runs\n\n<b>Kept</b>")
        self.assertIn("Settings &gt; Colors &amp; more, &lt;5% of runs", html)
        self.assertIn("<b>Kept</b>", html)

    def test_quotes_still_work_after_escaping(self):
        html = self.render("> One\n>\n> Two")
        self.assertEqual("<blockquote>One\n\nTwo</blockquote>", html)

    def test_line_breaks_become_newlines(self):
        self.assertEqual("a\nb", self.render("a<br/>b"))

    def test_nested_bullets_are_indented_and_continued_items_lose_their_indent(self):
        html = self.render("- **Still open**:\n  - One\n   - Two\n- Three\n\n  More about three.")
        self.assertEqual("• <b>Still open</b>:\n    ◦ One\n    ◦ Two\n• Three\n\nMore about three.", html)

    def test_entities_in_the_notes_are_shown_as_their_characters(self):
        # GitHub shows an entity inside `code` as typed, so Telegram must too.
        self.assertEqual("Q&amp;A — x <code>&amp;amp;</code>", self.render("Q&amp;A &#8212; x `&amp;`"))

    def test_non_web_links_show_their_address_as_code(self):
        html = self.render("- [Add to Obtainium](obtainium://add/https://github.com/x/y)")
        self.assertEqual("• Add to Obtainium: <code>obtainium://add/https://github.com/x/y</code>", html)


class TelegramChunkTests(unittest.TestCase):
    NOTES = ROOT / "docs" / "release_notes"

    def assert_postable(self, chunks, max_chars=3800):
        post_telegram.check_telegram_chunks(chunks)
        for chunk in chunks:
            self.assertLessEqual(post_telegram.visible_length(chunk), max_chars)

    def visible_words(self, html_text):
        return post_telegram.html.unescape(post_telegram.re.sub(r"<[^<>]*>", " ", html_text)).split()

    def test_every_announcement_splits_into_messages_telegram_accepts(self):
        for notes in sorted(self.NOTES.glob("*_announcement.md")):
            with self.subTest(notes=notes.name):
                html = post_telegram.markdown_to_telegram_html(notes.read_text(encoding="utf-8"))
                chunks = post_telegram.chunk_telegram_html(html)
                self.assert_postable(chunks)
                words = [w for c in chunks for w in self.visible_words(c) if w != post_telegram.DIVIDER]
                self.assertEqual([w for w in self.visible_words(html) if w != post_telegram.DIVIDER], words)

    def test_a_long_section_splits_at_blank_lines_not_mid_paragraph(self):
        paragraphs = [f"<b>{n}.</b> " + "word " * 300 for n in range(6)]
        chunks = post_telegram.chunk_telegram_html("\n\n".join(paragraphs), max_chars=4000)
        self.assert_postable(chunks, 4000)
        self.assertEqual(3, len(chunks))
        for chunk in chunks:
            self.assertRegex(chunk, r"^<b>\d\.</b>")

    def test_dividers_win_over_blank_lines(self):
        text = "intro\n\n" + post_telegram.DIVIDER + "\n\n" + "a " * 50 + "\n\n" + "b " * 50
        chunks = post_telegram.chunk_telegram_html(text, max_chars=150)
        self.assertEqual("intro", chunks[0])

    def test_a_quote_split_across_messages_is_closed_and_reopened(self):
        text = "<blockquote>" + "\n\n".join(["<i>" + "joke " * 60 + "</i>"] * 3) + "</blockquote>"
        chunks = post_telegram.chunk_telegram_html(text, max_chars=400)
        self.assert_postable(chunks, 400)
        self.assertGreater(len(chunks), 1)
        for chunk in chunks:
            self.assertTrue(chunk.startswith("<blockquote>") and chunk.endswith("</blockquote>"), chunk)

    def test_never_splits_inside_a_tag_or_entity(self):
        text = " ".join(['<a href="https://example.com/a b">x &amp; y</a>'] * 400)
        chunks = post_telegram.chunk_telegram_html(text, max_chars=100)
        self.assert_postable(chunks, 100)
        for chunk in chunks:
            self.assertNotRegex(chunk, r"<[^<>]*$|&[a-z]*$")

    def test_a_heading_stays_with_its_section(self):
        text = "<b>✨ What's New</b>\n\nThese came in:\n" + "\n".join(f"• item {i} " + "lorem " * 25 for i in range(30))
        chunks = post_telegram.chunk_telegram_html(text, max_chars=1000)
        self.assert_postable(chunks, 1000)
        self.assertIn("These came in", chunks[0])

    def test_a_split_prefers_a_top_level_line_over_a_nested_one(self):
        child = "    ◦ child " + "y " * 20
        text = "\n".join(["• parent " + "x " * 20, child, child, "• next " + "z " * 20, child, child])
        chunks = post_telegram.chunk_telegram_html(text, max_chars=280)
        self.assertTrue(chunks[1].startswith("• next"), chunks)

    def test_runs_of_dividers_and_tag_only_pieces_never_make_a_message(self):
        divider = post_telegram.DIVIDER
        text = "intro " * 30 + f"\n\n{divider}\n\n{divider}──\n\n" + "<b>" + "x" * 95 + " </b>\n\n" + "more " * 40
        chunks = post_telegram.chunk_telegram_html(text, max_chars=200)
        self.assert_postable(chunks, 200)
        for chunk in chunks:
            self.assertNotRegex(chunk, rf"^\s*{divider}|{divider}─*\s*$")

    def test_a_word_longer_than_a_message_is_cut(self):
        chunks = post_telegram.chunk_telegram_html("x" * 250, max_chars=100)
        self.assertEqual(["x" * 100, "x" * 100, "x" * 50], chunks)

    def test_check_refuses_what_telegram_would_refuse(self):
        for bad in ("a" * 4097, "a < b", "<b>open", "<b><i>x</b></i>", "<div>x</div>", "</b>", "<b> </b>"):
            with self.subTest(bad=bad[:20]), self.assertRaises(ValueError):
                post_telegram.check_telegram_chunks([bad])
        post_telegram.check_telegram_chunks(["😀" * 2048, "<a href=\"https://x\">x</a> &lt; <tg-spoiler>y</tg-spoiler>"])
        with self.assertRaises(ValueError):
            post_telegram.check_telegram_chunks(["😀" * 2049])  # 4,098 UTF-16 units
        with self.assertRaises(ValueError):
            post_telegram.check_telegram_chunks([])


class TelegramDesktopMarkdownTests(unittest.TestCase):
    def test_tags_become_telegram_desktop_markdown(self):
        html = ('<b>Title</b> &amp; <i>joke</i>\n<blockquote>🤝 <i>quote</i></blockquote>\n'
                '<tg-spoiler><b>Answer</b></tg-spoiler> <code>5.0.8</code>\n'
                '• <a href="https://t.me/ExpressiveGroup">@ExpressiveGroup</a>\n'
                '• <a href="https://github.com/x/y">https://github.com/x/y</a>\n'
                '• <a href="https://github.com/x/y/releases">Release</a>')
        self.assertEqual(
            "**Title** & __joke__\n🤝 __quote__\n||Answer|| `5.0.8`\n"
            "• @ExpressiveGroup\n• https://github.com/x/y\n• Release: https://github.com/x/y/releases",
            post_telegram.telegram_html_to_desktop_markdown(html),
        )

    def test_only_the_outermost_marker_is_written(self):
        # Telegram Desktop applies only the outer one; an inner ** would be sent as text.
        self.assertEqual("||An answer! Why.||",
                         post_telegram.telegram_html_to_desktop_markdown("<tg-spoiler><b>An answer!</b> Why.</tg-spoiler>"))
        self.assertEqual("~~old~~ ```x``` plain",
                         post_telegram.telegram_html_to_desktop_markdown("<s>old</s> <pre>x</pre> <u>plain</u>"))

    def test_link_addresses_are_unescaped_once(self):
        html = '<a href="https://e.com/?a=1&amp;region=2">Map</a>'
        self.assertEqual("Map: https://e.com/?a=1&region=2", post_telegram.telegram_html_to_desktop_markdown(html))

    def test_markers_that_wont_format_are_reported(self):
        problems = post_telegram._desktop_markdown("Android<b>17</b> and __init__.py")[1]
        self.assertEqual(2, len(problems), problems)
        self.assertEqual([], post_telegram._desktop_markdown("<b>🧩 Riddle</b>: (<i>a</i>) —")[1])

    def test_desktop_parts_fit_once_link_addresses_are_written_out(self):
        link = '<a href="https://github.com/denson9874/ExpressiveLauncher/issues/1234567890">#1</a>'
        html = "\n".join(f"• Fixed {link}" for _ in range(300))
        self.assertLessEqual(max(map(post_telegram.visible_length, post_telegram.chunk_telegram_html(html))), 3800)
        parts, _ = post_telegram.desktop_parts(html)
        for part in parts:
            self.assertLessEqual(post_telegram.desktop_length(part), post_telegram.TELEGRAM_MESSAGE_LIMIT)

    def test_dry_run_writes_one_desktop_part_per_message(self):
        with tempfile.TemporaryDirectory() as directory:
            notes = ROOT / "docs" / "release_notes" / "5.0.8_announcement.md"
            parts = Path(directory) / "parts"
            parts.mkdir()
            (parts / "part-9.txt").write_text("stale", encoding="utf-8")
            argv = ["post_telegram.py", "--notes-file", str(notes), "--output", str(Path(directory) / "out.txt"),
                    "--desktop-parts", str(parts), "--dry-run"]
            with patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()):
                post_telegram.main()
            written = sorted(parts.glob("part-*.txt"))
            self.assertEqual([f"part-{n}.txt" for n in range(1, len(written) + 1)], [p.name for p in written])
            self.assertGreater(len(written), 1)
            for part in written:
                text = part.read_text(encoding="utf-8")
                self.assertLessEqual(len(text.encode("utf-16-le")) // 2, post_telegram.TELEGRAM_MESSAGE_LIMIT)
                self.assertNotRegex(text, r"<[a-z/]|&(?:amp|lt|gt);")


class TelegramChannelTests(unittest.TestCase):
    """@ExpressiveLauncher is a 'moved' stub; announcements go to @ExpressiveGroup."""

    def setUp(self):
        environment = patch.dict(os.environ)
        environment.start()
        self.addCleanup(environment.stop)
        for key in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHANNEL_ID"):
            os.environ.pop(key, None)
        no_config = patch.object(post_telegram, "CONFIG_PATHS", [])
        no_config.start()
        self.addCleanup(no_config.stop)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        self.notes = self.directory / "notes.md"
        self.notes.write_text("# Release Title\n\nBody\n", encoding="utf-8")
        self.output = self.directory / "TELEGRAM_CHANGELOG.txt"

    def run_main(self, *extra):
        argv = ["post_telegram.py", "--notes-file", str(self.notes), "--output", str(self.output), *extra]
        stderr = io.StringIO()
        with patch.object(sys, "argv", argv), \
                patch.object(post_telegram, "send_telegram_message", side_effect=AssertionError("network used")), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(stderr):
            try:
                post_telegram.main()
            except SystemExit as error:
                return error.code, stderr.getvalue()
        return 0, stderr.getvalue()

    def test_default_channel_is_expressive_group(self):
        self.assertEqual("@ExpressiveGroup", post_telegram.DEFAULT_CHANNEL)
        self.assertEqual("@ExpressiveGroup", post_telegram.load_config()["channel_id"])
        self.assertEqual("@ExpressiveGroup", post_poll.DEFAULT_CHANNEL)
        self.assertEqual("@ExpressiveGroup", release_unified.TELEGRAM_DEFAULT_CHANNEL)

    def test_missing_token_renders_and_exits_with_a_distinct_code(self):
        code, stderr = self.run_main()
        self.assertEqual(post_telegram.EXIT_NOT_CONFIGURED, code)
        self.assertNotIn(code, (0, 1, 2))  # success, API failure, argparse usage error
        self.assertIn("NOT broadcast", stderr)
        self.assertIn("<b>Release Title</b>", self.output.read_text(encoding="utf-8"))

    def test_unpostable_notes_exit_before_sending_with_or_without_a_token(self):
        cases = {
            "empty": "<!-- expressive-qa build=70 -->\n",
            "unclosed tag in a long post": "<b>Start\n\n" + "\n\n".join(["word " * 300] * 4),
        }
        for name, notes in cases.items():
            self.notes.write_text(notes, encoding="utf-8")
            for extra in ((), ("--token", "123:T"), ("--dry-run",)):
                with self.subTest(name=name, extra=extra):
                    code, stderr = self.run_main(*extra)
                    self.assertEqual(post_telegram.EXIT_NOT_POSTABLE, code)
                    self.assertIn("nothing was sent", stderr)

    def test_release_summary_reports_unpostable_notes(self):
        self.assertEqual(post_telegram.EXIT_NOT_POSTABLE, release_unified.TELEGRAM_NOT_POSTABLE_EXIT)
        completed = subprocess.CompletedProcess(["post_telegram.py"], post_telegram.EXIT_NOT_POSTABLE,
                                                stdout="", stderr="message 2 has no text")
        with patch.object(release_unified.subprocess, "run", return_value=completed), \
                contextlib.redirect_stdout(io.StringIO()):
            status = release_unified.run_telegram_stage(["post_telegram.py"], self.output, "")
        self.assertTrue(status.startswith("NOT broadcast: Telegram would refuse"), status)

    def test_dry_run_still_succeeds_without_a_token(self):
        self.assertEqual(0, self.run_main("--dry-run")[0])
        self.assertTrue(self.output.is_file())

    def test_release_summary_reports_not_broadcast_for_the_missing_token_code(self):
        self.assertEqual(post_telegram.EXIT_NOT_CONFIGURED, release_unified.TELEGRAM_NOT_CONFIGURED_EXIT)
        for code, expected in ((0, "Rendered & broadcast via @ExpressiveGroup"),
                               (post_telegram.EXIT_NOT_CONFIGURED, "NOT broadcast: no bot token configured"),
                               (1, "NOT broadcast: post_telegram.py failed")):
            completed = subprocess.CompletedProcess(["post_telegram.py"], code, stdout="", stderr="")
            with self.subTest(code=code), patch.object(release_unified.subprocess, "run", return_value=completed), \
                    contextlib.redirect_stdout(io.StringIO()):
                status = release_unified.run_telegram_stage(["post_telegram.py"], self.output, "")
                self.assertTrue(status.startswith(expected), status)

    def test_release_stage_without_a_token_end_to_end_is_not_broadcast(self):
        os.environ["HOME"] = str(self.directory)  # post_telegram.py finds no telegram.json under this home
        command = [sys.executable, str(release_unified.TELEGRAM_SCRIPT),
                   "--notes-file", str(self.notes), "--output", str(self.output)]
        with contextlib.redirect_stdout(io.StringIO()):
            status = release_unified.run_telegram_stage(command, self.output, "")
        self.assertEqual("NOT broadcast: no bot token configured (rendered to TELEGRAM_CHANGELOG.txt)", status)
        self.assertIn("<b>Release Title</b>", self.output.read_text(encoding="utf-8"))


class RunCmdRedactionTests(unittest.TestCase):
    def test_token_values_never_reach_the_log(self):
        command = ["post_telegram.py", "--token", "123:SECRET", "--channel", "@ExpressiveGroup",
                   "--telegram-token=456:SECRET"]
        shown = release_unified.redact_cmd(command)
        self.assertNotIn("SECRET", shown)
        self.assertIn("--token ***", shown)
        self.assertIn("--telegram-token=***", shown)
        self.assertIn("--channel @ExpressiveGroup", shown)

    def test_failed_command_error_is_redacted(self):
        failed = subprocess.CompletedProcess([], 1, stdout="", stderr="")
        with patch.object(release_unified.subprocess, "run", return_value=failed), \
                contextlib.redirect_stdout(io.StringIO()) as out:
            with self.assertRaises(RuntimeError) as raised:
                release_unified.run_cmd(["post_telegram.py", "--token", "123:SECRET"])
        self.assertNotIn("SECRET", str(raised.exception))
        self.assertNotIn("SECRET", out.getvalue())


if __name__ == "__main__":
    unittest.main()

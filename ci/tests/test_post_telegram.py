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

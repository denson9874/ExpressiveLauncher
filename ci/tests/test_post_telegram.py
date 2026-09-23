#!/usr/bin/env python3
"""Tests for scripts/post_telegram.py formatting and configuration loading."""

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import scripts.post_telegram as post_telegram


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


if __name__ == "__main__":
    unittest.main()

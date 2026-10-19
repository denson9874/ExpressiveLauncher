#!/usr/bin/env python3
import io
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error

from scripts.issue_pro_key import build_parser, format_result, issue, load_token, request_body

RESULT = {
    "success": True,
    "type": "CAKEY",
    "expiresAt": 0,
    "keys": [{"recipient": "device:DEV-A1B2-C3D4", "key": "EXPR-PRO-AAAA", "deepLink": "expressive://pro/activate?key=EXPR-PRO-AAAA"}],
}


class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class TestIssueProKey(unittest.TestCase):

    def parse(self, *argv):
        return build_parser().parse_args(list(argv))

    def test_create_maps_binding_options_like_generate_pro_key(self):
        self.assertEqual(
            request_body(self.parse("create", "--type", "cakey", "--device", "DEV-A1B2-C3D4")),
            {"type": "CAKEY", "expires": "never", "device": "DEV-A1B2-C3D4"},
        )
        self.assertEqual(
            request_body(self.parse("create", "--account", "fan@example.com", "--expires", "1y")),
            {"type": "TESTER", "expires": "1y", "account": "fan@example.com"},
        )
        with self.assertRaises(SystemExit):
            request_body(self.parse("create"))

    def test_batch_sends_count_and_prefix(self):
        self.assertEqual(
            request_body(self.parse("batch", "--count", "3", "--prefix", "xda-")),
            {"type": "GIVEAWAY", "expires": "never", "count": 3, "prefix": "xda-"},
        )

    def test_token_precedence_flag_then_env_then_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "admin_token"
            path.write_text("from-file\n", encoding="utf-8")
            self.assertEqual(load_token("flag", {"EXPRESSIVE_PRO_ADMIN_TOKEN": "env"}, path), "flag")
            self.assertEqual(load_token(None, {"EXPRESSIVE_PRO_ADMIN_TOKEN": "env"}, path), "env")
            self.assertEqual(load_token(None, {}, path), "from-file")
            with self.assertRaises(SystemExit):
                load_token(None, {}, Path(tmp) / "missing")

    def test_issue_posts_bearer_token_to_admin_endpoint(self):
        seen = {}

        def opener(req, timeout):
            seen["url"] = req.full_url
            seen["auth"] = req.get_header("Authorization")
            seen["body"] = json.loads(req.data)
            return FakeResponse(json.dumps(RESULT).encode())

        result = issue("https://worker.test/", "secret", {"type": "CAKEY"}, opener=opener)
        self.assertEqual(result, RESULT)
        self.assertEqual(seen["url"], "https://worker.test/api/admin/issue")
        self.assertEqual(seen["auth"], "Bearer secret")
        self.assertEqual(seen["body"], {"type": "CAKEY"})

    def test_issue_reports_worker_errors(self):
        def opener(req, timeout):
            body = io.BytesIO(json.dumps({"error": "unauthorized"}).encode())
            raise urllib.error.HTTPError(req.full_url, 401, "Unauthorized", {}, body)

        with self.assertRaises(SystemExit) as ctx:
            issue("https://worker.test", "wrong", {}, opener=opener)
        self.assertIn("401", str(ctx.exception))
        self.assertIn("unauthorized", str(ctx.exception))

    def test_format_lists_recipient_key_and_link(self):
        text = format_result(RESULT)
        self.assertIn("1 CAKEY key(s), expires: Lifetime", text)
        self.assertIn("device:DEV-A1B2-C3D4 | EXPR-PRO-AAAA | expressive://pro/activate?key=EXPR-PRO-AAAA", text)


if __name__ == "__main__":
    unittest.main()

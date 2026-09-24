#!/usr/bin/env python3
import datetime
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

from scripts.generate_pro_key import (
    CROCKFORD_ALPHABET,
    MAGIC,
    SCHEMA_VERSION,
    TYPE_GIVEAWAY,
    TYPE_TESTER,
    build_and_sign_key,
    crc16_ccitt,
    crockford_decode,
    crockford_encode,
    ensure_key_pair,
    get_public_key_base64,
    parse_expiration,
    verify_and_inspect_key,
)


class TestProLicenseTool(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.key_dir = Path(self.temp_dir.name)
        self.priv_path = self.key_dir / "test_priv.pem"
        self.pub_path = self.key_dir / "test_pub.pem"
        ensure_key_pair(self.priv_path, self.pub_path)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_crockford_encode_decode_roundtrip(self):
        sample_bytes = b"Hello, Expressive Pro!\x00\xFF\x80"
        encoded = crockford_encode(sample_bytes)
        self.assertTrue(all(c in CROCKFORD_ALPHABET for c in encoded))
        decoded = crockford_decode(encoded)
        self.assertEqual(sample_bytes, decoded)

    def test_crockford_normalization_and_typo_tolerance(self):
        data = b"\x00\x08\x10\x20"
        encoded = crockford_encode(data)
        # Find if '0' or '1' is present, or craft a test string with zero padding
        # "00" = 10 bits of 0 -> byte 0x00, 2 padding bits of 0.
        decoded_00 = crockford_decode("00")
        self.assertEqual(decoded_00, b"\x00")
        self.assertEqual(crockford_decode("OO"), b"\x00")
        self.assertEqual(crockford_decode("oo"), b"\x00")

        # "10" = 00001 00000 = 00001000 (0x08) + 00 padding
        self.assertEqual(crockford_decode("10"), b"\x08")
        self.assertEqual(crockford_decode("I0"), b"\x08")
        self.assertEqual(crockford_decode("L0"), b"\x08")
        self.assertEqual(crockford_decode("l0"), b"\x08")
        self.assertEqual(crockford_decode("i0"), b"\x08")

    def test_crc16_ccitt(self):
        data = b"Expressive-12345"
        crc1 = crc16_ccitt(data)
        crc2 = crc16_ccitt(data)
        self.assertEqual(crc1, crc2)
        # Tampered data has different crc
        tampered_crc = crc16_ccitt(b"Expressive-12346")
        self.assertNotEqual(crc1, tampered_crc)

    def test_parse_expiration(self):
        self.assertEqual(parse_expiration("never"), 0)
        self.assertEqual(parse_expiration("0"), 0)
        self.assertEqual(parse_expiration("lifetime"), 0)

        now = int(time.time())
        exp_30d = parse_expiration("30d")
        self.assertTrue(now + 29 * 86400 <= exp_30d <= now + 31 * 86400)

        dt_str = "2030-01-01"
        exp_dt = parse_expiration(dt_str)
        self.assertGreater(exp_dt, now)

        with self.assertRaises(ValueError):
            parse_expiration("invalid-duration")

    def test_build_and_verify_lifetime_key(self):
        key, deep_link = build_and_sign_key(
            self.priv_path,
            recipient="Alice Tester",
            license_type=TYPE_TESTER,
            expires_at=0,
        )
        self.assertTrue(key.startswith("EXPR-PRO-"))
        self.assertTrue(deep_link.startswith("expressive://pro/activate?key=EXPR-PRO-"))

        info = verify_and_inspect_key(key, self.pub_path)
        self.assertTrue(info["valid"])
        self.assertEqual(info["recipient"], "Alice Tester")
        self.assertEqual(info["type"], "TESTER")
        self.assertEqual(info["expiresAt"], "Lifetime (Never)")
        self.assertFalse(info["isExpired"])

    def test_build_and_verify_expired_key(self):
        # Expired in past
        past_timestamp = 1000000000  # Year 2001
        key, _ = build_and_sign_key(
            self.priv_path,
            recipient="Old User",
            license_type=TYPE_GIVEAWAY,
            expires_at=past_timestamp,
        )
        info = verify_and_inspect_key(key, self.pub_path)
        self.assertTrue(info["valid"])
        self.assertTrue(info["isExpired"])

    def test_tampered_key_fails(self):
        key, _ = build_and_sign_key(
            self.priv_path,
            recipient="Bob",
            license_type=TYPE_TESTER,
            expires_at=0,
        )
        # Tamper one char in the middle
        body = key.replace("EXPR-PRO-", "")
        tampered_char = "Z" if body[10] != "Z" else "0"
        tampered_body = body[:10] + tampered_char + body[11:]
        tampered_key = "EXPR-PRO-" + tampered_body

        with self.assertRaises(ValueError):
            verify_and_inspect_key(tampered_key, self.pub_path)

    def test_key_signed_by_different_key_fails(self):
        other_priv = self.key_dir / "other_priv.pem"
        other_pub = self.key_dir / "other_pub.pem"
        ensure_key_pair(other_priv, other_pub)

        key, _ = build_and_sign_key(
            other_priv,
            recipient="Eve",
            license_type=TYPE_TESTER,
            expires_at=0,
        )
        # Attempting verification against self.pub_path must fail
        with self.assertRaises(ValueError) as ctx:
            verify_and_inspect_key(key, self.pub_path)
        self.assertIn("signature", str(ctx.exception).lower())


if __name__ == "__main__":
    unittest.main()

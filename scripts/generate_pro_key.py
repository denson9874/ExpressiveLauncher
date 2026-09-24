#!/usr/bin/env python3
"""Expressive Launcher Pro offline license key generator and inspector.

Uses ECDSA NIST P-256 (prime256v1) digital signatures to generate 100% offline,
cryptographically unforgeable license keys for Expressive Launcher Pro.
"""

import argparse
import datetime
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

MAGIC = b"EP"
SCHEMA_VERSION = 1

TYPE_TESTER = 1
TYPE_GIVEAWAY = 2
TYPE_VIP = 3
TYPE_DEV = 4

TYPE_NAMES = {
    TYPE_TESTER: "TESTER",
    TYPE_GIVEAWAY: "GIVEAWAY",
    TYPE_VIP: "VIP",
    TYPE_DEV: "DEV",
}

TYPE_MAP = {
    "TESTER": TYPE_TESTER,
    "GIVEAWAY": TYPE_GIVEAWAY,
    "VIP": TYPE_VIP,
    "DEV": TYPE_DEV,
}

DEFAULT_KEY_DIR = Path.home() / ".config/expressive"
DEFAULT_PRIVATE_KEY_PATH = DEFAULT_KEY_DIR / "pro_master_private.pem"
DEFAULT_PUBLIC_KEY_PATH = DEFAULT_KEY_DIR / "pro_master_public.pem"

CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
DECODE_MAP = {c: i for i, c in enumerate(CROCKFORD_ALPHABET)}
DECODE_MAP["O"] = 0
DECODE_MAP["o"] = 0
DECODE_MAP["I"] = 1
DECODE_MAP["i"] = 1
DECODE_MAP["L"] = 1
DECODE_MAP["l"] = 1


def crc16_ccitt(data: bytes) -> int:
    """Compute CRC-16-CCITT checksum (poly 0x1021, init 0xFFFF)."""
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


def crockford_encode(raw: bytes) -> str:
    """Encode binary data into Crockford Base32 string."""
    bit_buf = 0
    bits_in_buf = 0
    chars = []
    for b in raw:
        bit_buf = (bit_buf << 8) | b
        bits_in_buf += 8
        while bits_in_buf >= 5:
            bits_in_buf -= 5
            chars.append(CROCKFORD_ALPHABET[(bit_buf >> bits_in_buf) & 0x1F])
    if bits_in_buf > 0:
        chars.append(CROCKFORD_ALPHABET[(bit_buf << (5 - bits_in_buf)) & 0x1F])
    return "".join(chars)


def crockford_decode(s: str) -> bytes:
    """Decode Crockford Base32 string into binary bytes."""
    clean = [c for c in s.upper() if c in DECODE_MAP]
    bit_buf = 0
    bits_in_buf = 0
    out = bytearray()
    for c in clean:
        bit_buf = (bit_buf << 5) | DECODE_MAP[c]
        bits_in_buf += 5
        while bits_in_buf >= 8:
            bits_in_buf -= 8
            out.append((bit_buf >> bits_in_buf) & 0xFF)
    if bits_in_buf > 0:
        # Remaining bits must be zero padding
        if (bit_buf & ((1 << bits_in_buf) - 1)) != 0:
            raise ValueError("Invalid padding bits in license key")
    return bytes(out)


def ensure_key_pair(private_key_path: Path, public_key_path: Path) -> tuple[Path, Path]:
    """Ensure EC private and public key pair exists."""
    private_key_path.parent.mkdir(parents=True, exist_ok=True)
    if not private_key_path.is_file():
        print(f"Generating new master private key at {private_key_path}...")
        subprocess.run(
            ["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(private_key_path)],
            check=True,
        )
        private_key_path.chmod(0o600)
    # Always refresh / write public key PEM
    subprocess.run(
        ["openssl", "ec", "-in", str(private_key_path), "-pubout", "-out", str(public_key_path)],
        check=True,
        capture_output=True,
    )
    return private_key_path, public_key_path


def get_public_key_base64(public_key_path: Path) -> str:
    """Return the raw Base64 DER bytes from public key PEM."""
    lines = public_key_path.read_text(encoding="utf-8").strip().splitlines()
    key_lines = [l.strip() for l in lines if not l.startswith("-----")]
    return "".join(key_lines)


def parse_expiration(expires_str: str) -> int:
    """Parse expiration parameter into Unix timestamp (seconds). 0 = lifetime."""
    s = expires_str.strip().lower()
    if s in ("never", "0", "none", "lifetime"):
        return 0
    now = int(time.time())
    match = re.match(r"^(\d+)([dmyh])$", s)
    if match:
        val = int(match.group(1))
        unit = match.group(2)
        if unit == "h":
            return now + val * 3600
        elif unit == "d":
            return now + val * 86400
        elif unit == "m":
            return now + val * 30 * 86400
        elif unit == "y":
            return now + val * 365 * 86400
    # Try YYYY-MM-DD
    try:
        dt = datetime.datetime.strptime(s, "%Y-%m-%d").replace(tzinfo=datetime.timezone.utc)
        return int(dt.timestamp())
    except ValueError:
        pass
    raise ValueError(f"Unrecognized expiration format: {expires_str}. Use 'never', '30d', '1y', or 'YYYY-MM-DD'")


def build_and_sign_key(
    private_key_path: Path,
    recipient: str,
    license_type: int = TYPE_TESTER,
    expires_at: int = 0,
    features: int = 0xFFFFFFFF,
) -> tuple[str, str]:
    """Build binary payload, sign it with ECDSA SHA-256, and return formatted key + deep link."""
    recipient_bytes = recipient.strip().encode("utf-8")
    if len(recipient_bytes) > 255:
        raise ValueError("Recipient string must be <= 255 UTF-8 bytes")
    issued_at = int(time.time())

    # Payload = MAGIC(2) + VER(1) + TYPE(1) + ISSUED(4) + EXPIRES(4) + FEATURES(4) + RECIPIENT_LEN(1) + RECIPIENT(N)
    payload = bytearray()
    payload.extend(MAGIC)
    payload.append(SCHEMA_VERSION)
    payload.append(license_type)
    payload.extend(issued_at.to_bytes(4, byteorder="big"))
    payload.extend(expires_at.to_bytes(4, byteorder="big"))
    payload.extend(features.to_bytes(4, byteorder="big"))
    payload.append(len(recipient_bytes))
    payload.extend(recipient_bytes)

    # Sign payload with openssl
    with tempfile.NamedTemporaryFile() as data_file, tempfile.NamedTemporaryFile() as sig_file:
        data_file.write(payload)
        data_file.flush()
        subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(private_key_path), "-out", sig_file.name, data_file.name],
            check=True,
            capture_output=True,
        )
        sig_bytes = sig_file.read()

    # Packet = Payload + SIG_LEN(1) + Signature(S)
    packet = bytearray(payload)
    packet.append(len(sig_bytes))
    packet.extend(sig_bytes)

    # CRC-16 checksum (2 bytes big endian)
    crc = crc16_ccitt(bytes(packet))
    packet.extend(crc.to_bytes(2, byteorder="big"))

    # Crockford Base32 encode
    encoded = crockford_encode(bytes(packet))

    # Chunk with hyphens every 4 chars
    chunks = [encoded[i : i + 4] for i in range(0, len(encoded), 4)]
    key_code = "EXPR-PRO-" + "-".join(chunks)
    deep_link = f"expressive://pro/activate?key={key_code}"
    return key_code, deep_link


def verify_and_inspect_key(key_str: str, public_key_path: Path) -> dict:
    """Verify license key against public key and return inspected metadata."""
    clean = key_str.strip().upper()
    if clean.startswith("EXPR-PRO-"):
        clean = clean[len("EXPR-PRO-") :]
    elif clean.startswith("EXPR-"):
        clean = clean[len("EXPR-") :]
    clean = clean.replace("-", "").replace(" ", "")

    raw = crockford_decode(clean)
    if len(raw) < 17 + 1 + 64 + 2:
        raise ValueError("Key data too short to be a valid Expressive Pro license")

    data_without_crc = raw[:-2]
    expected_crc = int.from_bytes(raw[-2:], byteorder="big")
    actual_crc = crc16_ccitt(data_without_crc)
    if actual_crc != expected_crc:
        raise ValueError("CRC checksum failed. Key contains a typo or was corrupted.")

    magic = raw[:2]
    if magic != MAGIC:
        raise ValueError(f"Invalid magic bytes: {magic}")
    version = raw[2]
    if version != SCHEMA_VERSION:
        raise ValueError(f"Unsupported schema version: {version}")

    license_type = raw[3]
    issued_at = int.from_bytes(raw[4:8], byteorder="big")
    expires_at = int.from_bytes(raw[8:12], byteorder="big")
    features = int.from_bytes(raw[12:16], byteorder="big")
    recip_len = raw[16]

    payload_len = 17 + recip_len
    if len(data_without_crc) < payload_len + 1:
        raise ValueError("Malformed packet length")

    recipient = raw[17 : 17 + recip_len].decode("utf-8", errors="replace")
    payload = raw[:payload_len]

    sig_len = raw[payload_len]
    sig_bytes = raw[payload_len + 1 : payload_len + 1 + sig_len]

    # Verify signature
    with tempfile.NamedTemporaryFile() as data_file, tempfile.NamedTemporaryFile() as sig_file:
        data_file.write(payload)
        data_file.flush()
        sig_file.write(sig_bytes)
        sig_file.flush()
        res = subprocess.run(
            [
                "openssl",
                "dgst",
                "-sha256",
                "-verify",
                str(public_key_path),
                "-signature",
                sig_file.name,
                data_file.name,
            ],
            capture_output=True,
            text=True,
        )
        if "Verified OK" not in res.stdout:
            raise ValueError("Cryptographic signature verification failed! Key is invalid or forged.")

    now = int(time.time())
    is_expired = (expires_at > 0) and (now > expires_at)

    issued_dt = datetime.datetime.fromtimestamp(issued_at, tz=datetime.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    expires_dt = (
        "Lifetime (Never)"
        if expires_at == 0
        else datetime.datetime.fromtimestamp(expires_at, tz=datetime.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    )

    is_device_bound = recipient.lower().startswith("device:")
    bound_device_id = recipient[len("device:"):].strip() if is_device_bound else None
    is_account_bound = recipient.lower().startswith("account:")
    bound_account_email = recipient[len("account:"):].strip() if is_account_bound else None

    return {
        "valid": True,
        "type": TYPE_NAMES.get(license_type, f"UNKNOWN({license_type})"),
        "recipient": recipient,
        "deviceBound": is_device_bound,
        "boundDeviceId": bound_device_id,
        "accountBound": is_account_bound,
        "boundAccount": bound_account_email,
        "issuedAt": issued_dt,
        "expiresAt": expires_dt,
        "isExpired": is_expired,
        "features": f"0x{features:08X}",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    # init
    p_init = subparsers.add_parser("init", help="Initialize master ECDSA key pair")
    p_init.add_argument("--private-key", type=Path, default=DEFAULT_PRIVATE_KEY_PATH)
    p_init.add_argument("--public-key", type=Path, default=DEFAULT_PUBLIC_KEY_PATH)

    # create
    p_create = subparsers.add_parser("create", help="Create a signed license key")
    p_create.add_argument("--recipient", help="Recipient name, handle, or ID")
    p_create.add_argument("--device", help="Device ID (e.g. DEV-A1B2-C3D4) to bind this license to")
    p_create.add_argument("--account", help="Account email (e.g. user@example.com) to bind this license to")
    p_create.add_argument("--type", choices=["TESTER", "GIVEAWAY", "VIP", "DEV"], default="TESTER")
    p_create.add_argument("--expires", default="never", help="Duration (e.g. 30d, 90d, 1y) or 'never'")
    p_create.add_argument("--private-key", type=Path, default=DEFAULT_PRIVATE_KEY_PATH)

    # batch
    p_batch = subparsers.add_parser("batch", help="Batch generate multiple giveaway / tester keys")
    p_batch.add_argument("--count", type=int, default=10, help="Number of keys to generate")
    p_batch.add_argument("--type", choices=["TESTER", "GIVEAWAY", "VIP", "DEV"], default="GIVEAWAY")
    p_batch.add_argument("--prefix", default="giveaway-", help="Recipient label prefix")
    p_batch.add_argument("--expires", default="never", help="Duration (e.g. 30d, 1y) or 'never'")
    p_batch.add_argument("--output", type=Path, help="Optional output text file path")
    p_batch.add_argument("--private-key", type=Path, default=DEFAULT_PRIVATE_KEY_PATH)

    # inspect
    p_inspect = subparsers.add_parser("inspect", help="Verify and inspect a license key")
    p_inspect.add_argument("--key", required=True, help="License key string")
    p_inspect.add_argument("--public-key", type=Path, default=DEFAULT_PUBLIC_KEY_PATH)

    args = parser.parse_args()

    if args.command == "init":
        priv, pub = ensure_key_pair(args.private_key, args.public_key)
        b64 = get_public_key_base64(pub)
        print("\n--- Expressive Pro Master Key Pair Initialized ---")
        print(f"Private Key : {priv} (Permissions 0600, NEVER commit to git)")
        print(f"Public Key  : {pub}")
        print("\nBase64 Public Key to embed in ProLicenseVerifier.kt:")
        print(f'const val PRO_PUBLIC_KEY_BASE64 = "{b64}"\n')

    elif args.command == "create":
        if not args.private_key.is_file():
            print(f"Error: Master private key not found at {args.private_key}. Run 'init' first.", file=sys.stderr)
            sys.exit(1)
        if args.device:
            recipient = f"device:{args.device.strip().upper()}"
        elif args.account:
            recipient = f"account:{args.account.strip().lower()}"
        elif args.recipient:
            recipient = args.recipient.strip()
        else:
            print("Error: Must specify --recipient, --device, or --account", file=sys.stderr)
            sys.exit(1)

        expires_at = parse_expiration(args.expires)
        l_type = TYPE_MAP[args.type]
        key, deep_link = build_and_sign_key(args.private_key, recipient, l_type, expires_at)
        print("\n=== Expressive Pro License Key Created ===")
        print(f"Recipient : {recipient}")
        if args.device:
            print(f"Binding   : Device ({args.device.strip().upper()})")
        elif args.account:
            print(f"Binding   : Account ({args.account.strip().lower()})")
        else:
            print("Binding   : Universal (Offline)")
        print(f"Type      : {args.type}")
        print(f"Expires   : {args.expires} ({'Lifetime' if expires_at == 0 else datetime.datetime.fromtimestamp(expires_at, tz=datetime.timezone.utc)})")
        print(f"\nLicense Key:\n{key}")
        print(f"\nOne-Tap Deep Link:\n{deep_link}\n")

    elif args.command == "batch":
        if not args.private_key.is_file():
            print(f"Error: Master private key not found at {args.private_key}. Run 'init' first.", file=sys.stderr)
            sys.exit(1)
        expires_at = parse_expiration(args.expires)
        l_type = TYPE_MAP[args.type]
        results = []
        now_ts = int(time.time())
        for i in range(1, args.count + 1):
            recipient = f"{args.prefix}{now_ts}-{i:03d}"
            key, deep_link = build_and_sign_key(args.private_key, recipient, l_type, expires_at)
            results.append((recipient, key, deep_link))
        out_lines = []
        for r, k, dl in results:
            out_lines.append(f"{r} | {k} | {dl}")
        output_text = "\n".join(out_lines) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(output_text, encoding="utf-8")
            print(f"Generated {args.count} {args.type} keys and saved to {args.output}")
        else:
            print(f"\n=== Generated {args.count} {args.type} Keys ===")
            print(output_text)

    elif args.command == "inspect":
        pub = args.public_key
        if not pub.is_file():
            # If public key file doesn't exist, try generating it from private key if present
            if DEFAULT_PRIVATE_KEY_PATH.is_file():
                ensure_key_pair(DEFAULT_PRIVATE_KEY_PATH, pub)
            else:
                print(f"Error: Public key not found at {pub}. Run 'init' first.", file=sys.stderr)
                sys.exit(1)
        try:
            info = verify_and_inspect_key(args.key, pub)
            print("\n=== License Key Verification: VALID ===")
            for k, v in info.items():
                print(f"  {k:12}: {v}")
            print()
        except Exception as e:
            print(f"\n=== License Key Verification: FAILED ===\nError: {e}\n", file=sys.stderr)
            sys.exit(1)


if __name__ == "__main__":
    main()

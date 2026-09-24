/**
 * Expressive Launcher Pro — Serverless Activation Worker (Cloudflare Worker)
 *
 * Handles automated PayPal donation verification, ECDSA P-256 license key generation,
 * license storage in Cloudflare KV, and deep link redemption redirects.
 *
 * 100% serverless, zero external npm dependencies, pure Web Crypto API.
 */

const MAGIC = new Uint8Array([0x45, 0x50]); // "EP"
const SCHEMA_VERSION = 1;
const TYPE_TESTER = 1;
const TYPE_VIP = 3;
const CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // Handle CORS preflight
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS_HEADERS });
    }

    try {
      if (url.pathname === "/" || url.pathname === "/health") {
        return jsonResponse({
          status: "ok",
          service: "Expressive Pro Activation Service",
          version: "3.0.0",
          timestamp: new Date().toISOString(),
        });
      }

      // 1. License Lookup for Device ID
      if (url.pathname === "/api/license" && request.method === "GET") {
        return await handleLicenseLookup(url, env);
      }

      // 2. PayPal Webhook
      if (url.pathname === "/api/paypal-webhook" && request.method === "POST") {
        return await handlePayPalWebhook(request, env);
      }

      // 3. Manual Donation Verification (Transaction ID + Device ID)
      if (url.pathname === "/api/verify-donation" && request.method === "POST") {
        return await handleVerifyDonation(request, env);
      }

      // 4. Web Deep Link Redirect / Launcher Activation Page
      if (url.pathname === "/activate" && request.method === "GET") {
        return handleActivationRedirect(url);
      }

      return jsonResponse({ error: "Endpoint not found" }, 404);
    } catch (err) {
      console.error("Worker error:", err);
      return jsonResponse(
        { success: false, error: err.message || "Internal server error" },
        500
      );
    }
  },
};

/**
 * Handle GET /api/license?device_id=DEV-...
 */
async function handleLicenseLookup(url, env) {
  const deviceId = url.searchParams.get("device_id")?.trim().toUpperCase();
  const email = url.searchParams.get("email")?.trim().toLowerCase();

  if (!deviceId && !email) {
    return jsonResponse(
      { success: false, message: "Missing device_id or email parameter" },
      400
    );
  }

  if (!env.EXPRESSIVE_PRO_KV) {
    return jsonResponse(
      { success: false, message: "Storage backend not configured" },
      503
    );
  }

  // Look up by device ID
  if (deviceId) {
    const raw = await env.EXPRESSIVE_PRO_KV.get(`license:device:${deviceId}`);
    if (raw) {
      const data = JSON.parse(raw);
      return jsonResponse({
        success: true,
        key: data.key,
        recipient: data.recipient,
        createdAt: data.createdAt,
      });
    }
  }

  // Look up by email
  if (email) {
    const raw = await env.EXPRESSIVE_PRO_KV.get(`license:email:${email}`);
    if (raw) {
      const data = JSON.parse(raw);
      return jsonResponse({
        success: true,
        key: data.key,
        recipient: data.recipient,
        createdAt: data.createdAt,
      });
    }
  }

  return jsonResponse({
    success: false,
    message: "No active license found for this device.",
  });
}

/**
 * Handle POST /api/paypal-webhook
 */
async function handlePayPalWebhook(request, env) {
  const body = await request.json();
  const eventType = body.event_type;

  // Supported PayPal capture and payment events
  const validEvents = [
    "PAYMENT.CAPTURE.COMPLETED",
    "CHECKOUT.ORDER.APPROVED",
    "PAYMENT.SALE.COMPLETED",
  ];

  if (!validEvents.includes(eventType)) {
    // Acknowledge other webhook notifications without error
    return jsonResponse({ received: true, ignored: eventType });
  }

  const resource = body.resource || {};
  const customId = (
    resource.custom_id ||
    resource.custom ||
    resource.invoice_id ||
    ""
  ).trim();

  const transactionId = resource.id || body.id || `TX-${Date.now()}`;
  const payerEmail = (
    resource.payer?.email_address ||
    resource.payer_email ||
    ""
  ).toLowerCase().trim();

  // Determine recipient
  let recipient = "";
  let isDevice = false;

  if (customId && customId.toUpperCase().startsWith("DEV-")) {
    recipient = `device:${customId.toUpperCase()}`;
    isDevice = true;
  } else if (payerEmail) {
    recipient = `account:${payerEmail}`;
  } else if (customId) {
    recipient = customId;
  } else {
    recipient = `device:DEV-DONATION-${transactionId.slice(-8)}`;
  }

  // Generate ECDSA P-256 signed license key
  const licenseKey = await generateProLicense(recipient, TYPE_VIP, 0, env);

  const record = {
    key: licenseKey,
    recipient: recipient,
    transactionId: transactionId,
    payerEmail: payerEmail,
    eventType: eventType,
    createdAt: new Date().toISOString(),
  };

  if (env.EXPRESSIVE_PRO_KV) {
    if (isDevice) {
      const devId = customId.toUpperCase();
      await env.EXPRESSIVE_PRO_KV.put(
        `license:device:${devId}`,
        JSON.stringify(record)
      );
    }
    if (payerEmail) {
      await env.EXPRESSIVE_PRO_KV.put(
        `license:email:${payerEmail}`,
        JSON.stringify(record)
      );
    }
    await env.EXPRESSIVE_PRO_KV.put(
      `tx:${transactionId}`,
      JSON.stringify(record)
    );
  }

  return jsonResponse({
    success: true,
    key: licenseKey,
    recipient: recipient,
    transactionId: transactionId,
  });
}

/**
 * Handle POST /api/verify-donation
 */
async function handleVerifyDonation(request, env) {
  const { transaction_id, device_id, email } = await request.json();

  if (!transaction_id || (!device_id && !email)) {
    return jsonResponse(
      {
        success: false,
        message: "Missing transaction_id or device_id/email in request",
      },
      400
    );
  }

  const cleanTx = transaction_id.trim();
  const cleanDev = device_id ? device_id.trim().toUpperCase() : null;
  const cleanEmail = email ? email.trim().toLowerCase() : null;

  // Check if already in KV
  if (env.EXPRESSIVE_PRO_KV) {
    const existingTx = await env.EXPRESSIVE_PRO_KV.get(`tx:${cleanTx}`);
    if (existingTx) {
      const data = JSON.parse(existingTx);
      // Link to new device if needed
      if (cleanDev && !data.recipient.includes(cleanDev)) {
        await env.EXPRESSIVE_PRO_KV.put(
          `license:device:${cleanDev}`,
          JSON.stringify(data)
        );
      }
      return jsonResponse({
        success: true,
        key: data.key,
        recipient: data.recipient,
      });
    }
  }

  // If PayPal API credentials configured, verify directly via PayPal REST API
  if (env.PAYPAL_CLIENT_ID && env.PAYPAL_CLIENT_SECRET) {
    const verified = await verifyWithPayPalApi(cleanTx, env);
    if (!verified.valid) {
      return jsonResponse(
        {
          success: false,
          message: verified.message || "Invalid or unverified PayPal transaction.",
        },
        400
      );
    }

    const recipient = cleanDev
      ? `device:${cleanDev}`
      : cleanEmail
      ? `account:${cleanEmail}`
      : `tx:${cleanTx}`;

    const key = await generateProLicense(recipient, TYPE_VIP, 0, env);
    const record = {
      key,
      recipient,
      transactionId: cleanTx,
      createdAt: new Date().toISOString(),
    };

    if (env.EXPRESSIVE_PRO_KV) {
      if (cleanDev) {
        await env.EXPRESSIVE_PRO_KV.put(
          `license:device:${cleanDev}`,
          JSON.stringify(record)
        );
      }
      if (cleanEmail) {
        await env.EXPRESSIVE_PRO_KV.put(
          `license:email:${cleanEmail}`,
          JSON.stringify(record)
        );
      }
      await env.EXPRESSIVE_PRO_KV.put(
        `tx:${cleanTx}`,
        JSON.stringify(record)
      );
    }

    return jsonResponse({ success: true, key, recipient });
  }

  return jsonResponse(
    {
      success: false,
      message:
        "Transaction verification is pending PayPal webhook processing. Please wait 1-2 minutes and tap 'Check Payment Status'.",
    },
    202
  );
}

/**
 * Verify transaction via PayPal REST API v2
 */
async function verifyWithPayPalApi(transactionId, env) {
  try {
    const auth = btoa(`${env.PAYPAL_CLIENT_ID}:${env.PAYPAL_CLIENT_SECRET}`);
    const tokenRes = await fetch(
      "https://api-m.paypal.com/v1/oauth2/token",
      {
        method: "POST",
        headers: {
          Authorization: `Basic ${auth}`,
          "Content-Type": "application/x-www-form-urlencoded",
        },
        body: "grant_type=client_credentials",
      }
    );
    const tokenData = await tokenRes.json();
    if (!tokenData.access_token) {
      return { valid: false, message: "PayPal authentication failed" };
    }

    // Check capture
    const captureRes = await fetch(
      `https://api-m.paypal.com/v2/payments/captures/${encodeURIComponent(transactionId)}`,
      {
        headers: {
          Authorization: `Bearer ${tokenData.access_token}`,
          "Content-Type": "application/json",
        },
      }
    );

    if (captureRes.ok) {
      const captureData = await captureRes.json();
      if (captureData.status === "COMPLETED") {
        return { valid: true };
      }
    }

    return { valid: false, message: "PayPal capture status is not COMPLETED." };
  } catch (err) {
    return { valid: false, message: err.message };
  }
}

/**
 * Handle GET /activate?key=...
 */
function handleActivationRedirect(url) {
  const key = url.searchParams.get("key") || "";
  const deepLink = `expressive://pro/activate?key=${encodeURIComponent(key)}`;

  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Activate Expressive Pro</title>
  <style>
    body {
      background: #101216;
      color: #e3e2e6;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      display: flex;
      justify-content: center;
      align-items: center;
      min-height: 100vh;
      margin: 0;
      padding: 20px;
    }
    .card {
      background: #1a1d24;
      border: 1px solid rgba(255,255,255,0.08);
      border-radius: 20px;
      padding: 32px 24px;
      max-width: 440px;
      width: 100%;
      text-align: center;
    }
    h1 { font-size: 22px; color: #fff; margin-bottom: 8px; }
    p { font-size: 14px; color: #c4c6d0; line-height: 1.5; margin-bottom: 24px; }
    .btn {
      display: block;
      background: #a8c7fa;
      color: #042f66;
      font-weight: 700;
      text-decoration: none;
      padding: 14px 20px;
      border-radius: 12px;
      font-size: 15px;
      margin-bottom: 12px;
    }
    .key-box {
      background: #121418;
      color: #a8c7fa;
      font-family: monospace;
      font-size: 12px;
      padding: 12px;
      border-radius: 8px;
      word-break: break-all;
      margin-top: 16px;
    }
  </style>
</head>
<body>
  <div class="card">
    <h1>Expressive Pro Activated</h1>
    <p>Thank you for supporting Expressive Launcher development! Tap below to finish activation on your device.</p>
    <a href="${deepLink}" class="btn">Open Expressive Launcher</a>
    <div class="key-box">${escapeHtml(key)}</div>
  </div>
</body>
</html>`;

  return new Response(html, {
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      Location: deepLink,
    },
    status: 200,
  });
}

/**
 * Generate cryptographic ECDSA P-256 Expressive Pro license key
 */
async function generateProLicense(
  recipient,
  licenseType = TYPE_VIP,
  expiresAt = 0,
  env
) {
  const privateKeyPem = env.PRO_PRIVATE_KEY_PEM;
  if (!privateKeyPem) {
    throw new Error(
      "PRO_PRIVATE_KEY_PEM environment variable is not configured."
    );
  }

  const privateKey = await importPrivateKey(privateKeyPem);

  const recipientBytes = new TextEncoder().encode(recipient.trim());
  if (recipientBytes.length > 255) {
    throw new Error("Recipient string exceeds 255 bytes");
  }

  const issuedAt = Math.floor(Date.now() / 1000);
  const features = 0xffffffff;

  // Payload: MAGIC(2) + VER(1) + TYPE(1) + ISSUED(4) + EXPIRES(4) + FEATURES(4) + RECIPIENT_LEN(1) + RECIPIENT(N)
  const payload = new Uint8Array(17 + recipientBytes.length);
  payload[0] = MAGIC[0];
  payload[1] = MAGIC[1];
  payload[2] = SCHEMA_VERSION;
  payload[3] = licenseType;

  const view = new DataView(payload.buffer);
  view.setUint32(4, issuedAt, false);
  view.setUint32(8, expiresAt, false);
  view.setUint32(12, features, false);
  payload[16] = recipientBytes.length;
  payload.set(recipientBytes, 17);

  // Web Crypto Sign (returns raw 64-byte P1363 (r || s))
  const rawSig = await crypto.subtle.sign(
    { name: "ECDSA", hash: { name: "SHA-256" } },
    privateKey,
    payload
  );

  // Convert raw (r, s) to ASN.1 DER format
  const derSig = convertP1363ToDer(new Uint8Array(rawSig));

  // Packet without CRC: payload + sigLen (1) + derSig
  const packetWithoutCrc = new Uint8Array(payload.length + 1 + derSig.length);
  packetWithoutCrc.set(payload, 0);
  packetWithoutCrc[payload.length] = derSig.length;
  packetWithoutCrc.set(derSig, payload.length + 1);

  // Compute CRC16-CCITT
  const crc = computeCrc16Ccitt(packetWithoutCrc);

  // Full packet: packetWithoutCrc + crc (2 bytes big-endian)
  const fullPacket = new Uint8Array(packetWithoutCrc.length + 2);
  fullPacket.set(packetWithoutCrc, 0);
  fullPacket[packetWithoutCrc.length] = (crc >> 8) & 0xff;
  fullPacket[packetWithoutCrc.length + 1] = crc & 0xff;

  // Crockford Base32 encode
  const encoded = encodeCrockford(fullPacket);

  // Format into chunks of 4 characters with EXPR-PRO- prefix
  const chunks = [];
  for (let i = 0; i < encoded.length; i += 4) {
    chunks.push(encoded.slice(i, i + 4));
  }

  return "EXPR-PRO-" + chunks.join("-");
}

/**
 * Import PKCS#8 or SEC1 private key into CryptoKey
 */
async function importPrivateKey(pem) {
  // Strip PEM headers and whitespace
  const cleanB64 = pem
    .replace(/-----[^\n]+-----/g, "")
    .replace(/\s+/g, "");
  const binaryDer = base64ToUint8Array(cleanB64);

  // Try importing as PKCS#8
  try {
    return await crypto.subtle.importKey(
      "pkcs8",
      binaryDer.buffer,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["sign"]
    );
  } catch (err) {
    // If input was raw SEC1 EC private key, wrap in PKCS#8 header
    const pkcs8Der = wrapSec1ToPkcs8(binaryDer);
    return await crypto.subtle.importKey(
      "pkcs8",
      pkcs8Der.buffer,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["sign"]
    );
  }
}

/**
 * Convert raw 64-byte IEEE P1363 (r || s) signature to ASN.1 DER SEQUENCE { INTEGER r, INTEGER s }
 */
function convertP1363ToDer(rawSig) {
  const r = rawSig.subarray(0, 32);
  const s = rawSig.subarray(32, 64);

  function encodeDerInteger(bytes) {
    let start = 0;
    while (start < bytes.length - 1 && bytes[start] === 0) {
      start++;
    }
    const trimmed = bytes.subarray(start);
    if ((trimmed[0] & 0x80) !== 0) {
      const out = new Uint8Array(trimmed.length + 3);
      out[0] = 0x02; // INTEGER tag
      out[1] = trimmed.length + 1;
      out[2] = 0x00; // positive sign padding
      out.set(trimmed, 3);
      return out;
    } else {
      const out = new Uint8Array(trimmed.length + 2);
      out[0] = 0x02;
      out[1] = trimmed.length;
      out.set(trimmed, 2);
      return out;
    }
  }

  const rDer = encodeDerInteger(r);
  const sDer = encodeDerInteger(s);

  const totalLen = rDer.length + sDer.length;
  const der = new Uint8Array(2 + totalLen);
  der[0] = 0x30; // SEQUENCE tag
  der[1] = totalLen;
  der.set(rDer, 2);
  der.set(sDer, 2 + rDer.length);

  return der;
}

/**
 * Compute CRC-16-CCITT checksum (poly 0x1021, init 0xFFFF)
 */
function computeCrc16Ccitt(data) {
  let crc = 0xffff;
  for (let i = 0; i < data.length; i++) {
    crc ^= data[i] << 8;
    for (let j = 0; j < 8; j++) {
      if ((crc & 0x8000) !== 0) {
        crc = ((crc << 1) ^ 0x1021) & 0xffff;
      } else {
        crc = (crc << 1) & 0xffff;
      }
    }
  }
  return crc;
}

/**
 * Encode byte array into Crockford Base32 string
 */
function encodeCrockford(bytes) {
  let bitBuf = 0;
  let bitsInBuf = 0;
  let result = "";

  for (let i = 0; i < bytes.length; i++) {
    bitBuf = (bitBuf << 8) | bytes[i];
    bitsInBuf += 8;
    while (bitsInBuf >= 5) {
      bitsInBuf -= 5;
      result += CROCKFORD_ALPHABET[(bitBuf >> bitsInBuf) & 0x1f];
    }
  }

  if (bitsInBuf > 0) {
    result += CROCKFORD_ALPHABET[(bitBuf << (5 - bitsInBuf)) & 0x1f];
  }

  return result;
}

/**
 * Wrap SEC1 EC private key into PKCS#8 structure for Web Crypto
 */
function wrapSec1ToPkcs8(sec1Bytes) {
  // P-256 PKCS#8 prefix:
  // SEQUENCE (total)
  //   INTEGER 0 (v1)
  //   SEQUENCE
  //     OID 1.2.840.10045.2.1 (ecPublicKey)
  //     OID 1.2.840.10045.3.1.7 (prime256v1)
  //   OCTET STRING (sec1Bytes)
  const prefix = new Uint8Array([
    0x30,
    sec1Bytes.length + 16,
    0x02, 0x01, 0x00,
    0x30, 0x13,
    0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
    0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07,
    0x04,
    sec1Bytes.length,
  ]);

  const pkcs8 = new Uint8Array(prefix.length + sec1Bytes.length);
  pkcs8.set(prefix, 0);
  pkcs8.set(sec1Bytes, prefix.length);
  return pkcs8;
}

function base64ToUint8Array(base64) {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      ...CORS_HEADERS,
    },
  });
}

function escapeHtml(str) {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

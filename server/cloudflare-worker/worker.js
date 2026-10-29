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
const TYPE_TRIAL = 6;
// Types an admin may issue through POST /api/admin/issue (same names as scripts/generate_pro_key.py).
const ADMIN_TYPES = { TESTER: 1, GIVEAWAY: 2, VIP: 3, DEV: 4, CAKEY: 5 };
const ADMIN_BATCH_LIMIT = 100;
const TRIAL_DAYS = 7;
const TRIAL_RATE_LIMIT_PER_HOUR = 5;
// A license may move to this many new devices (reset, reinstall) per rolling window.
const MAX_DEVICE_MOVES = 3;
const DEVICE_MOVE_WINDOW_SECONDS = 30 * 86400;
const REBIND_RATE_LIMIT_PER_HOUR = 10;
const DEVICE_ID_PATTERN = /^DEV-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/;
const FINGERPRINT_PATTERN = /^[0-9a-f]{64}$/;
const CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const PAYPAL_API_BASE = "https://api-m.paypal.com";
const RESTORE_NEEDS_TRANSACTION =
  "To restore a contribution, enter the Transaction ID from your PayPal receipt.";
// The Pro price on the app's PayPal link. A capture for less, or in another currency, is a tip, not a
// purchase. PRO_PRICE and PRO_CURRENCY ([vars] in wrangler.toml) override them if the price changes.
const PRO_PRICE = "4.99";
const PRO_CURRENCY = "USD";
const VERIFY_RATE_LIMIT_PER_HOUR = 10;
// PayPal transaction (capture) IDs: 17 letters and digits on receipts; allow some slack, nothing else.
const PAYPAL_TX_PATTERN = /^[A-Z0-9-]{8,40}$/i;
// Events that take a payment back: the license stops being handed out (keys already on a device keep
// working offline; the app cannot be told).
const PAYMENT_REVOKED_EVENTS = ["PAYMENT.CAPTURE.DENIED", "PAYMENT.CAPTURE.REFUNDED", "PAYMENT.CAPTURE.REVERSED"];
const PAYMENT_NOT_CONFIRMED =
  "We couldn't confirm this payment with PayPal. Check the Transaction ID on your PayPal receipt and try again.";
const PAYMENT_REVOKED = "This payment was refunded or reversed, so it no longer unlocks Pro.";
const PAYMENT_PENDING =
  "PayPal is still processing this payment. Please try again once your PayPal receipt shows it as completed.";

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

      // 4. Seven-day Pro trial, one per hashed device fingerprint
      if (url.pathname === "/api/trial" && request.method === "POST") {
        return await handleTrialRequest(request, env);
      }

      // 5. Move a device-bound key to this device's new install ID
      if (url.pathname === "/api/rebind" && request.method === "POST") {
        return await handleRebind(request, env);
      }

      // 6. Owner-only key issuing (replaces the offline generate_pro_key.py)
      if (url.pathname === "/api/admin/issued" && request.method === "GET") {
        return await handleAdminIssued(request, env);
      }

      if (url.pathname === "/api/admin/issue" && request.method === "POST") {
        return await handleAdminIssue(request, env);
      }

      // 7. Owner-only key registration (registers an offline-generated key into KV)
      if (url.pathname === "/api/admin/register" && request.method === "POST") {
        return await handleAdminRegister(request, env);
      }

      // 8. Web Deep Link Redirect / Launcher Activation Page
      if (url.pathname === "/activate" && request.method === "GET") {
        return handleActivationRedirect(url);
      }

      return jsonResponse({ error: "Endpoint not found" }, 404);
    } catch (err) {
      // The details stay in the log: an error message can name a secret, a KV key or PayPal's reply.
      console.error("Worker error:", err);
      return jsonResponse({ success: false, error: "Internal server error" }, 500);
    }
  },
};

/**
 * Handle GET /api/license?device_id=DEV-...
 *
 * Email lookups no longer return keys: anyone who knew a supporter's address could take their license.
 * Restores go through POST /api/verify-donation with the PayPal transaction ID instead.
 */
async function handleLicenseLookup(url, env) {
  const deviceId = url.searchParams.get("device_id")?.trim().toUpperCase();
  const email = url.searchParams.get("email")?.trim();

  if (!deviceId && !email) {
    return jsonResponse(
      { success: false, message: "Missing device_id parameter" },
      400
    );
  }

  if (!env.EXPRESSIVE_PRO_KV) {
    return jsonResponse(
      { success: false, message: "Storage backend not configured" },
      503
    );
  }

  if (deviceId) {
    const raw = await env.EXPRESSIVE_PRO_KV.get(`license:device:${deviceId}`);
    if (raw) {
      const data = JSON.parse(raw);
      if (data.revokedAt) {
        return jsonResponse({ success: false, error: "payment_revoked", message: PAYMENT_REVOKED });
      }
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
    message: email ? RESTORE_NEEDS_TRANSACTION : "No active license found for this device.",
  });
}

/**
 * Handle POST /api/trial with { device_id, trial_fingerprint }.
 *
 * trial_fingerprint is a SHA-256 (hex) computed on the device from its Android ID and package name;
 * the raw identifier never reaches this service. Each fingerprint gets one 7-day trial. Asking again
 * before it ends (e.g. after a reinstall that changed device_id) re-issues a key with the same expiry;
 * asking after it ends returns 409 trial_used.
 */
async function handleTrialRequest(request, env, now = Date.now()) {
  if (!env.EXPRESSIVE_PRO_KV) {
    return jsonResponse({ success: false, message: "Storage backend not configured" }, 503);
  }

  let body;
  try {
    body = await request.json();
  } catch (err) {
    return jsonResponse({ success: false, error: "invalid_request" }, 400);
  }
  const deviceId = String(body?.device_id ?? "").trim().toUpperCase();
  const fingerprint = String(body?.trial_fingerprint ?? "").trim().toLowerCase();
  if (!DEVICE_ID_PATTERN.test(deviceId) || !FINGERPRINT_PATTERN.test(fingerprint)) {
    return jsonResponse({ success: false, error: "invalid_request" }, 400);
  }

  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const rateKey = `trial:rate:${ip}:${Math.floor(now / 3600000)}`;
  const attempts = parseInt((await env.EXPRESSIVE_PRO_KV.get(rateKey)) || "0", 10);
  if (attempts >= TRIAL_RATE_LIMIT_PER_HOUR) {
    return jsonResponse({ success: false, error: "rate_limited" }, 429);
  }
  await env.EXPRESSIVE_PRO_KV.put(rateKey, String(attempts + 1), { expirationTtl: 7200 });

  const nowSeconds = Math.floor(now / 1000);
  const recordKey = `trial:fp:${fingerprint}`;
  const existing = await env.EXPRESSIVE_PRO_KV.get(recordKey);
  let expiresAt;
  if (existing) {
    const record = JSON.parse(existing);
    if (nowSeconds >= record.expiresAt) {
      return jsonResponse({ success: false, error: "trial_used", expiresAt: record.expiresAt }, 409);
    }
    expiresAt = record.expiresAt;
    if (record.deviceId !== deviceId) {
      record.deviceId = deviceId;
      await env.EXPRESSIVE_PRO_KV.put(recordKey, JSON.stringify(record));
    }
  } else {
    expiresAt = nowSeconds + TRIAL_DAYS * 86400;
    await env.EXPRESSIVE_PRO_KV.put(
      recordKey,
      JSON.stringify({ deviceId, expiresAt, createdAt: new Date(now).toISOString() })
    );
  }

  const key = await generateProLicense(`device:${deviceId}`, TYPE_TRIAL, expiresAt, env);
  return jsonResponse({ success: true, key, expiresAt });
}

/**
 * Handle POST /api/paypal-webhook
 *
 * Every event is checked with PayPal's verify-webhook-signature API before a key is signed, so a forged
 * "payment completed" request can't mint a license. Without PAYPAL_WEBHOOK_ID and the API credentials
 * the endpoint refuses events (PayPal retries them for three days, so none are lost while it is set up).
 */
async function handlePayPalWebhook(request, env, now = Date.now()) {
  let body;
  try {
    body = await request.json();
  } catch (err) {
    return jsonResponse({ success: false, error: "invalid_request" }, 400);
  }

  if (!env.PAYPAL_WEBHOOK_ID || !env.PAYPAL_CLIENT_ID || !env.PAYPAL_CLIENT_SECRET) {
    console.error("PayPal webhook received but PAYPAL_WEBHOOK_ID / API credentials are not configured");
    return jsonResponse({ success: false, error: "webhook_verification_not_configured" }, 503);
  }
  const verification = await verifyPayPalWebhookSignature(request.headers, body, env);
  if (!verification.valid) {
    return jsonResponse({ success: false, error: "invalid_signature" }, 401);
  }

  const eventType = body.event_type;
  const resource = body.resource || {};

  if (PAYMENT_REVOKED_EVENTS.includes(eventType)) {
    const captureId = captureIdOf(resource, eventType);
    if (!captureId) {
      console.warn(`PayPal ${eventType} ${resource.id || "?"} names no capture`);
    } else {
      // A refund names the running total refunded on its capture: a partial one can leave Pro paid.
      const refundedCents = eventType === "PAYMENT.CAPTURE.REFUNDED"
        ? toCents(resource.seller_payable_breakdown?.total_refunded_amount?.value)
        : null;
      await revokePayment(env, { captureId, reason: eventType, refundedCents, now });
    }
    return jsonResponse({ received: true });
  }

  // Only a completed capture is money received. CHECKOUT.ORDER.APPROVED comes before payment (the
  // capture can still fail), and the app's checkout never sends v1 sale events.
  if (eventType !== "PAYMENT.CAPTURE.COMPLETED") {
    return jsonResponse({ received: true, ignored: eventType });
  }
  const problem = capturePaysForPro(resource, env);
  if (problem || !resource.id) {
    // HTTP 200 so PayPal doesn't redeliver: the event is genuine, it just isn't a Pro purchase.
    console.warn(`PayPal capture ${resource.id || "?"} is not a Pro purchase: ${problem || "no id"}`);
    return jsonResponse({ received: true, ignored: problem || "no_capture_id" });
  }

  // The app adds its device ID to the PayPal link (custom / invoice_id). When PayPal passes it on, the
  // purchase is bound to that device and "Check payment status" finds it. Otherwise the payment waits,
  // unclaimed, until the buyer restores it with the Transaction ID from their receipt.
  const deviceId = [resource.custom_id, resource.invoice_id]
    .map((id) => String(id || "").trim().toUpperCase())
    .find((id) => DEVICE_ID_PATTERN.test(id)) || null;
  const orderId = resource.supplementary_data?.related_ids?.order_id || "";

  const { record } = await claimPayment(env, {
    captureId: resource.id,
    deviceId,
    payment: paymentDetails(resource, { orderId, eventType }),
    now,
  });
  // A restore with the Transaction ID got there first and bound the license to another device: the
  // device the buyer paid from still gets its copy, as a restore from it would.
  const bound = boundDeviceOf(record.recipient);
  if (deviceId && record.key && bound && bound !== deviceId) {
    await deliverToDevice(record, deviceId, env, now);
  }
  // PayPal ignores the body; the key is only ever handed to the app.
  return jsonResponse({ success: true, claimed: Boolean(record.key), transactionId: resource.id });
}

/**
 * Why a PayPal capture is not a Pro purchase, or null when it is: COMPLETED, in PRO_CURRENCY, for at
 * least PRO_PRICE and, when PAYPAL_MERCHANT_ID is set, paid to that merchant. A capture that names no
 * payee passes that last check: the webhook ID and the API credentials already tie it to this account.
 */
function capturePaysForPro(capture, env) {
  const price = proPriceCents(env);
  if (!price) {
    // Fail closed: an unreadable price must never let any amount through.
    console.error(`PRO_PRICE "${env.PRO_PRICE}" is not an amount like 4.99`);
    return "price_misconfigured";
  }
  if (capture?.status !== "COMPLETED") return "not_completed";
  const amount = capture.amount || {};
  const currency = String(env.PRO_CURRENCY || PRO_CURRENCY).toUpperCase();
  if (String(amount.currency_code || "").toUpperCase() !== currency) return "wrong_currency";
  const paid = toCents(amount.value);
  if (paid === null || paid < price) return "underpaid";
  const payee = capture.payee?.merchant_id;
  if (env.PAYPAL_MERCHANT_ID && payee && payee !== env.PAYPAL_MERCHANT_ID) return "wrong_payee";
  return null;
}

/** The Pro price in cents, or null when PRO_PRICE is set to something that isn't an amount. */
function proPriceCents(env) {
  const cents = toCents(env.PRO_PRICE || PRO_PRICE);
  return cents > 0 ? cents : null;
}

/** "4.99" -> 499, without floating point; null if it is not a plain amount. */
function toCents(value) {
  const match = /^(\d{1,9})(?:\.(\d{1,2}))?$/.exec(String(value ?? "").trim());
  return match ? parseInt(match[1], 10) * 100 + parseInt((match[2] || "0").padEnd(2, "0"), 10) : null;
}

/**
 * The capture a refund, reversal or denial is about. REFUNDED and REVERSED describe a refund, which
 * links "up" to its capture; DENIED describes the capture itself.
 */
function captureIdOf(resource, eventType) {
  const related = resource.supplementary_data?.related_ids?.capture_id;
  if (related) return related;
  const up = (resource.links || []).find((link) => link.rel === "up" && /\/captures\//.test(link.href || ""));
  if (up) return up.href.split("/captures/")[1].split(/[/?#]/)[0];
  return eventType === "PAYMENT.CAPTURE.DENIED" ? resource.id || null : null;
}

/** What is kept about a payment: enough to answer support questions, nothing about the payer. */
function paymentDetails(capture, extra = {}) {
  return {
    amount: capture.amount?.value,
    currency: capture.amount?.currency_code,
    orderId: extra.orderId || undefined,
    eventType: extra.eventType || undefined,
  };
}

function paypalApiBase(env) {
  // PAYPAL_API_BASE=https://api-m.sandbox.paypal.com points a separate test worker at PayPal's sandbox.
  return env.PAYPAL_API_BASE || PAYPAL_API_BASE;
}

/**
 * Ask PayPal whether a webhook delivery really came from it, for the webhook registered as PAYPAL_WEBHOOK_ID.
 */
async function verifyPayPalWebhookSignature(headers, event, env) {
  const fields = {
    auth_algo: headers.get("PAYPAL-AUTH-ALGO"),
    cert_url: headers.get("PAYPAL-CERT-URL"),
    transmission_id: headers.get("PAYPAL-TRANSMISSION-ID"),
    transmission_sig: headers.get("PAYPAL-TRANSMISSION-SIG"),
    transmission_time: headers.get("PAYPAL-TRANSMISSION-TIME"),
  };
  if (Object.values(fields).some((v) => !v)) {
    return { valid: false, message: "Missing PayPal transmission headers" };
  }
  try {
    const token = await getPayPalAccessToken(env);
    if (!token) {
      return { valid: false, message: "PayPal authentication failed" };
    }
    const res = await fetch(`${paypalApiBase(env)}/v1/notifications/verify-webhook-signature`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ ...fields, webhook_id: env.PAYPAL_WEBHOOK_ID, webhook_event: event }),
    });
    if (!res.ok) {
      if (res.status === 401) forgetPayPalAccessToken();
      return { valid: false, message: `PayPal verification returned ${res.status}` };
    }
    const data = await res.json();
    return { valid: data.verification_status === "SUCCESS" };
  } catch (err) {
    return { valid: false, message: err.message };
  }
}

// PayPal access tokens last about nine hours. One per isolate saves an OAuth round trip on every
// webhook and restore; a request PayPal answers with 401 drops it (forgetPayPalAccessToken).
let paypalToken = null; // { value, clientId, expiresAt }

async function getPayPalAccessToken(env) {
  const now = Date.now();
  if (paypalToken && paypalToken.clientId === env.PAYPAL_CLIENT_ID && now < paypalToken.expiresAt) {
    return paypalToken.value;
  }
  const auth = btoa(`${env.PAYPAL_CLIENT_ID}:${env.PAYPAL_CLIENT_SECRET}`);
  const tokenRes = await fetch(`${paypalApiBase(env)}/v1/oauth2/token`, {
    method: "POST",
    headers: {
      Authorization: `Basic ${auth}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: "grant_type=client_credentials",
  });
  if (!tokenRes.ok) {
    console.error(`PayPal token request returned ${tokenRes.status}`);
    return null;
  }
  const tokenData = await tokenRes.json();
  if (!tokenData.access_token) return null;
  // Renew a minute early so a token never expires between this check and PayPal's.
  const lifetimeMs = Math.max(0, (Number(tokenData.expires_in) || 0) - 60) * 1000;
  paypalToken = { value: tokenData.access_token, clientId: env.PAYPAL_CLIENT_ID, expiresAt: now + lifetimeMs };
  return tokenData.access_token;
}

function forgetPayPalAccessToken() {
  paypalToken = null;
}

/**
 * Handle POST /api/verify-donation
 */
async function handleVerifyDonation(request, env, now = Date.now()) {
  let body;
  try {
    body = await request.json();
  } catch (err) {
    return jsonResponse({ success: false, error: "invalid_request" }, 400);
  }
  const cleanTx = String(body?.transaction_id ?? "").trim();
  const cleanDev = String(body?.device_id ?? "").trim().toUpperCase();

  if (!DEVICE_ID_PATTERN.test(cleanDev)) {
    return jsonResponse({ success: false, message: "Missing or invalid device_id in request" }, 400);
  }
  // Older apps send an email (or the device ID) here. Neither proves the payment, so ask for the
  // transaction ID; HTTP 200 lets those apps show the message instead of a connection error.
  if (!cleanTx || cleanTx.includes("@") || DEVICE_ID_PATTERN.test(cleanTx.toUpperCase())) {
    return jsonResponse({ success: false, error: "transaction_required", message: RESTORE_NEEDS_TRANSACTION });
  }
  if (!env.EXPRESSIVE_PRO_KV) {
    return jsonResponse({ success: false, message: "Storage backend not configured" }, 503);
  }

  // PayPal's IDs are upper case and receipts get retyped in lower case; IDs an admin registered keep
  // whatever spelling they were registered with.
  const txId = cleanTx.toUpperCase();
  const raw =
    (await env.EXPRESSIVE_PRO_KV.get(`tx:${cleanTx}`)) ||
    (txId !== cleanTx ? await env.EXPRESSIVE_PRO_KV.get(`tx:${txId}`) : null);
  const stored = raw ? JSON.parse(raw) : null;
  if (stored?.key) {
    return await deliverToDevice(stored, cleanDev, env, now);
  }

  // A payment the webhook recorded without a device (PayPal dropped the app's device ID) is claimed
  // by the first device that brings its Transaction ID; an order ID's copy points at its capture.
  // Anything else is checked with PayPal first.
  let captureId;
  let payment;
  if (stored) {
    captureId = stored.transactionId || txId;
    payment = { orderId: stored.orderId };
  } else {
    if (!PAYPAL_TX_PATTERN.test(txId)) {
      return jsonResponse({ success: false, error: "payment_not_confirmed", message: PAYMENT_NOT_CONFIRMED });
    }
    if (!env.PAYPAL_CLIENT_ID || !env.PAYPAL_CLIENT_SECRET) {
      return jsonResponse(
        {
          success: false,
          message:
            "Transaction verification is pending PayPal webhook processing. Please wait 1-2 minutes and tap 'Check Payment Status'.",
        },
        202
      );
    }
    // Each lookup costs PayPal API calls; a person types a few Transaction IDs at most. HTTP 200 so
    // older apps show the message (they treat any error status as "server unreachable").
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    const rateKey = `verify:rate:${ip}:${Math.floor(now / 3600000)}`;
    const attempts = parseInt((await env.EXPRESSIVE_PRO_KV.get(rateKey)) || "0", 10);
    if (attempts >= VERIFY_RATE_LIMIT_PER_HOUR) {
      return jsonResponse({
        success: false,
        error: "rate_limited",
        message: "Too many attempts. Please try again in an hour.",
      });
    }
    await env.EXPRESSIVE_PRO_KV.put(rateKey, String(attempts + 1), { expirationTtl: 7200 });

    const verified = await verifyWithPayPalApi(txId, env);
    if (verified.pending) {
      return jsonResponse({ success: false, error: "payment_pending", message: PAYMENT_PENDING });
    }
    if (!verified.valid) {
      return jsonResponse({ success: false, error: "payment_not_confirmed", message: PAYMENT_NOT_CONFIRMED });
    }
    // Claim under PayPal's own spelling of the ID, so one payment has one record whatever was typed.
    captureId = verified.capture.id || txId;
    payment = paymentDetails(verified.capture, {
      orderId: verified.capture.supplementary_data?.related_ids?.order_id,
      eventType: "api_lookup",
    });
  }

  const { record } = await claimPayment(env, { captureId, deviceId: cleanDev, payment, now });
  // Signed for this device just now, or earlier for another: delivery moves a copy if needed.
  return await deliverToDevice(record, cleanDev, env, now);
}

/**
 * Hand a stored purchase to deviceId. A key bound to another device (the buyer reset or reinstalled)
 * is re-signed for this one, within the license's device-move allowance.
 */
async function deliverToDevice(record, deviceId, env, now) {
  if (record.revokedAt) {
    return jsonResponse({ success: false, error: "payment_revoked", message: PAYMENT_REVOKED });
  }
  const boundDevice = boundDeviceOf(record.recipient);
  if (!boundDevice || boundDevice === deviceId) {
    return jsonResponse({ success: true, key: record.key, recipient: record.recipient });
  }
  const license = await parseSignedKey(record.key, env);
  if (!license) {
    throw new Error("Stored license key failed verification");
  }
  const moved = await moveLicenseToDevice(license, deviceId, env, now);
  if (moved.error) {
    // HTTP 200 so older apps show the message; newer ones read `error`.
    return jsonResponse({ success: false, error: moved.error, message: moved.message });
  }
  const recipient = `device:${deviceId}`;
  await env.EXPRESSIVE_PRO_KV.put(
    `license:device:${deviceId}`,
    JSON.stringify({ ...record, key: moved.key, recipient, movedFrom: record.recipient })
  );
  return jsonResponse({ success: true, key: moved.key, recipient, movesLeft: moved.movesLeft });
}

/**
 * Handle POST /api/rebind with { key, device_id }.
 *
 * A device-bound key stops working when the device gets a new install ID (factory reset, reinstall,
 * cleared data). The key itself proves the license, so this re-signs it for the new ID with the same
 * type, expiry and features, within the license's device-move allowance. Keys that are not bound to a
 * device already work everywhere and are returned unchanged; trial keys go through POST /api/trial.
 */
async function handleRebind(request, env, now = Date.now()) {
  if (!env.EXPRESSIVE_PRO_KV) {
    return jsonResponse({ success: false, message: "Storage backend not configured" }, 503);
  }

  let body;
  try {
    body = await request.json();
  } catch (err) {
    return jsonResponse({ success: false, error: "invalid_request" }, 400);
  }
  const deviceId = String(body?.device_id ?? "").trim().toUpperCase();
  const rawKey = String(body?.key ?? "").trim();
  if (!DEVICE_ID_PATTERN.test(deviceId) || !rawKey) {
    return jsonResponse({ success: false, error: "invalid_request" }, 400);
  }

  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const rateKey = `rebind:rate:${ip}:${Math.floor(now / 3600000)}`;
  const attempts = parseInt((await env.EXPRESSIVE_PRO_KV.get(rateKey)) || "0", 10);
  if (attempts >= REBIND_RATE_LIMIT_PER_HOUR) {
    return jsonResponse({ success: false, error: "rate_limited" }, 429);
  }
  await env.EXPRESSIVE_PRO_KV.put(rateKey, String(attempts + 1), { expirationTtl: 7200 });

  const license = await parseSignedKey(rawKey, env);
  if (!license) {
    return jsonResponse({ success: false, error: "invalid_key" }, 400);
  }
  if (license.type === TYPE_TRIAL) {
    return jsonResponse({ success: false, error: "trial_key" }, 400);
  }
  if (license.expiresAt > 0 && Math.floor(now / 1000) > license.expiresAt) {
    return jsonResponse({ success: false, error: "expired" }, 400);
  }

  const boundDevice = boundDeviceOf(license.recipient);
  if (!boundDevice || boundDevice === deviceId) {
    return jsonResponse({ success: true, key: rawKey, recipient: license.recipient });
  }

  const moved = await moveLicenseToDevice(license, deviceId, env, now);
  if (moved.error) {
    return jsonResponse({ success: false, error: moved.error, message: moved.message }, 409);
  }
  return jsonResponse({ success: true, key: moved.key, recipient: `device:${deviceId}`, movesLeft: moved.movesLeft });
}

/**
 * Re-sign a verified license for deviceId through the LICENSE_LEDGER Durable Object, which runs one
 * move at a time so parallel requests can't each pass the move limit.
 */
async function moveLicenseToDevice(license, deviceId, env, now) {
  return callLedger(env, "/move", { license, deviceId, now });
}

/**
 * Durable Object holding every license move. One instance serves all licenses (moves are rare), and
 * blockConcurrencyWhile makes each read, limit check, signature and write run alone. Its storage is
 * strongly consistent, so a key moved a moment ago is traced back to its license at once, which KV
 * (eventually consistent) could not promise.
 */
export class LicenseLedger {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const body = await request.json();
    const operation = {
      "/move": () => recordMove(this.state.storage, body.license, body.deviceId, body.now, this.env),
      "/payment": () => recordPayment(this.state.storage, body, this.env),
      "/revoke": () => recordRevocation(this.state.storage, body, this.env),
    }[new URL(request.url).pathname];
    if (!operation) {
      return jsonResponse({ error: "unknown_operation" }, 404);
    }
    return jsonResponse(await this.state.blockConcurrencyWhile(operation));
  }
}

/**
 * The one record of a PayPal payment, keyed by its capture (Transaction) ID. The first claim with a
 * device ID signs the license; later claims, a redelivered webhook or a webhook racing a restore, get
 * that same record back, so one payment never becomes two licenses. `seed` is the payment's record
 * from before the ledger kept them (KV only): it is adopted, never signed again.
 */
async function recordPayment(storage, { captureId, deviceId, payment, seed, now }, env) {
  const storeKey = `payment:${captureId}`;
  const existing = await storage.get(storeKey);
  const record = existing || seed || { transactionId: captureId, ...payment, paidAt: new Date(now).toISOString() };
  if (record.key || record.revokedAt || !deviceId) {
    if (!existing) await storage.put(storeKey, record);
    return { record, created: false };
  }
  record.recipient = `device:${deviceId}`;
  record.key = await generateProLicense(record.recipient, TYPE_VIP, 0, env);
  record.createdAt = new Date(now).toISOString();
  await storage.put(storeKey, record);
  return { record, created: true };
}

/**
 * Marks a payment refunded, reversed or denied, blocks its license from moving to new devices, and
 * names every device the license reached (the first one and each move) so their lookups stop handing
 * the key out. A refund that leaves at least the Pro price paid (a returned tip) revokes nothing.
 */
async function recordRevocation(storage, { captureId, reason, refundedCents, seed, now }, env) {
  const storeKey = `payment:${captureId}`;
  const record = (await storage.get(storeKey)) || seed || { transactionId: captureId };
  const paid = toCents(record.amount);
  const price = proPriceCents(env);
  if (reason === "PAYMENT.CAPTURE.REFUNDED" && refundedCents != null && paid != null && price) {
    if (paid - refundedCents >= price) {
      record.refundedCents = refundedCents;
      await storage.put(storeKey, record);
      return { record, devices: [], revoked: false };
    }
  }
  record.revokedAt = record.revokedAt || new Date(now).toISOString();
  record.revokedReason = reason;
  await storage.put(storeKey, record);

  const devices = new Set();
  const bound = boundDeviceOf(record.recipient);
  if (bound) devices.add(bound);
  if (record.key) {
    const id = await keyId(record.key);
    const rootId = (await storage.get(`keyroot:${id}`)) || id;
    await storage.put(`revoked:${rootId}`, reason);
    const moves = await storage.get(`moves:${rootId}`);
    for (const device of Object.keys(moves?.devices || {})) devices.add(device);
  }
  return { record, devices: [...devices], revoked: true };
}

/** The payment's KV record when it holds a key: one stored before the ledger kept payments. */
async function legacyPaymentRecord(env, captureId) {
  const raw = await env.EXPRESSIVE_PRO_KV.get(`tx:${captureId}`);
  const record = raw ? JSON.parse(raw) : null;
  return record?.key ? record : null;
}

/**
 * Claims a payment through the ledger and mirrors the result into KV, where the lookups read it:
 * tx:<capture> (and tx:<order>) always, license:device:<id> once a device holds the license.
 */
async function claimPayment(env, { captureId, deviceId, payment, now }) {
  const seed = await legacyPaymentRecord(env, captureId);
  const result = await callLedger(env, "/payment", { captureId, deviceId, payment, seed, now });
  const { record } = result;
  for (const id of new Set([captureId, record.orderId].filter(Boolean))) {
    await env.EXPRESSIVE_PRO_KV.put(`tx:${id}`, JSON.stringify(record));
  }
  const bound = boundDeviceOf(record.recipient);
  if (record.key && bound) {
    // Replace whatever the device held before (an expired tester key, a refunded purchase), unless it
    // already is this payment's record.
    const raw = await env.EXPRESSIVE_PRO_KV.get(`license:device:${bound}`);
    const previous = raw ? JSON.parse(raw) : null;
    if (previous?.transactionId !== record.transactionId) {
      await env.EXPRESSIVE_PRO_KV.put(`license:device:${bound}`, JSON.stringify(record));
    }
  }
  return result;
}

async function revokePayment(env, { captureId, reason, refundedCents, now }) {
  const seed = await legacyPaymentRecord(env, captureId);
  const { record, devices, revoked } = await callLedger(env, "/revoke", { captureId, reason, refundedCents, seed, now });
  if (!revoked) return;
  const marks = { revokedAt: record.revokedAt, revokedReason: reason };
  const mark = async (kvKey) => {
    const raw = await env.EXPRESSIVE_PRO_KV.get(kvKey);
    const current = raw ? JSON.parse(raw) : { transactionId: captureId };
    await env.EXPRESSIVE_PRO_KV.put(kvKey, JSON.stringify({ ...current, ...marks }));
  };
  await mark(`tx:${captureId}`);
  if (record.orderId) await mark(`tx:${record.orderId}`);
  for (const device of devices) {
    const raw = await env.EXPRESSIVE_PRO_KV.get(`license:device:${device}`);
    // Only that payment's copies: a device may since hold a different license.
    if (raw && JSON.parse(raw).transactionId === captureId) await mark(`license:device:${device}`);
  }
}

async function callLedger(env, path, body) {
  if (!env.LICENSE_LEDGER) {
    throw new Error("LICENSE_LEDGER Durable Object binding is not configured.");
  }
  const ledger = env.LICENSE_LEDGER.get(env.LICENSE_LEDGER.idFromName("license-moves"));
  const res = await ledger.fetch(`https://license-ledger${path}`, { method: "POST", body: JSON.stringify(body) });
  if (!res.ok) {
    throw new Error(`License ledger returned ${res.status}`);
  }
  return res.json();
}

/**
 * Moves are counted per license: the id of the first key issued, which every re-signed copy points
 * back to through `keyroot:`. Asking again for a device the license already moved to returns that key
 * without using a move.
 */
async function recordMove(storage, license, deviceId, now, env) {
  const nowSeconds = Math.floor(now / 1000);
  const rootId = (await storage.get(`keyroot:${license.id}`)) || license.id;
  if (await storage.get(`revoked:${rootId}`)) {
    return { error: "payment_revoked", message: PAYMENT_REVOKED };
  }
  const movesKey = `moves:${rootId}`;
  const moves = (await storage.get(movesKey)) || { devices: {} };

  const known = moves.devices[deviceId];
  if (known) {
    return { key: known.key, movesLeft: movesLeftIn(moves, nowSeconds) };
  }
  if (movesLeftIn(moves, nowSeconds) <= 0) {
    return {
      error: "transfer_limit",
      message: `This license has already moved to ${MAX_DEVICE_MOVES} new devices in the last 30 days. Contact support to move it again.`,
    };
  }

  const key = await generateProLicense(`device:${deviceId}`, license.type, license.expiresAt, env, license.features);
  moves.devices[deviceId] = { key, movedAt: nowSeconds };
  await storage.put({ [movesKey]: moves, [`keyroot:${await keyId(key)}`]: rootId });
  return { key, movesLeft: movesLeftIn(moves, nowSeconds) };
}

function movesLeftIn(moves, nowSeconds) {
  const recent = Object.values(moves.devices).filter(
    (d) => nowSeconds - d.movedAt < DEVICE_MOVE_WINDOW_SECONDS
  ).length;
  return Math.max(0, MAX_DEVICE_MOVES - recent);
}

function boundDeviceOf(recipient) {
  const r = String(recipient || "").trim();
  return r.toLowerCase().startsWith("device:") ? r.slice("device:".length).trim().toUpperCase() : null;
}

/**
 * Handle POST /api/admin/issue (Authorization: Bearer <ADMIN_TOKEN>).
 *
 * Signs keys on request, so the master private key only has to live in this worker; replaces the
 * offline scripts/generate_pro_key.py (use scripts/issue_pro_key.py). Body mirrors its options:
 * { type, device | account | recipient, expires, count, prefix }.
 */
async function handleAdminIssue(request, env, now = Date.now()) {
  if (!env.ADMIN_TOKEN) {
    return jsonResponse({ error: "Endpoint not found" }, 404);
  }
  const header = request.headers.get("Authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!(await secretsEqual(token, env.ADMIN_TOKEN))) {
    return jsonResponse({ success: false, error: "unauthorized" }, 401);
  }

  let body;
  try {
    body = await request.json();
  } catch (err) {
    return jsonResponse({ success: false, error: "invalid_request" }, 400);
  }

  const typeName = String(body?.type ?? "TESTER").trim().toUpperCase();
  const licenseType = ADMIN_TYPES[typeName];
  if (!licenseType) {
    return badAdminRequest(`type must be one of ${Object.keys(ADMIN_TYPES).join(", ")}`);
  }
  const expiresAt = parseExpiry(String(body?.expires ?? "never"), now);
  if (expiresAt === null) {
    return badAdminRequest("expires must be never, a duration like 30d / 6m / 1y, or a future YYYY-MM-DD");
  }
  const count = body?.count === undefined ? 1 : Number(body.count);
  if (!Number.isInteger(count) || count < 1 || count > ADMIN_BATCH_LIMIT) {
    return badAdminRequest(`count must be a whole number from 1 to ${ADMIN_BATCH_LIMIT}`);
  }

  const recipients = [];
  if (count === 1 && !body.prefix) {
    if (body.device) {
      const device = String(body.device).trim().toUpperCase();
      if (!DEVICE_ID_PATTERN.test(device)) return badAdminRequest("device must look like DEV-XXXX-XXXX");
      recipients.push(`device:${device}`);
    } else if (body.account) {
      recipients.push(`account:${String(body.account).trim().toLowerCase()}`);
    } else if (body.recipient && String(body.recipient).trim()) {
      recipients.push(String(body.recipient).trim());
    } else {
      return badAdminRequest("give one of device, account or recipient (or count/prefix for a batch)");
    }
  } else {
    if (body.device || body.account || body.recipient) {
      return badAdminRequest("a batch takes prefix, not device, account or recipient");
    }
    const prefix = String(body.prefix ?? "giveaway-");
    const stamp = Math.floor(now / 1000);
    for (let i = 1; i <= count; i++) {
      recipients.push(`${prefix}${stamp}-${String(i).padStart(3, "0")}`);
    }
  }

  const keys = [];
  for (const recipient of recipients) {
    if (new TextEncoder().encode(recipient).length > 255) {
      return badAdminRequest("recipient must be at most 255 bytes");
    }
    const key = await generateProLicense(recipient, licenseType, expiresAt, env);
    keys.push({ recipient, key, deepLink: `expressive://pro/activate?key=${key}` });
  }

  if (env.EXPRESSIVE_PRO_KV) {
    if (body.device && keys.length === 1) {
      const devId = String(body.device).trim().toUpperCase();
      await env.EXPRESSIVE_PRO_KV.put(
        `license:device:${devId}`,
        JSON.stringify({
          key: keys[0].key,
          recipient: keys[0].recipient,
          createdAt: new Date(now).toISOString(),
          type: typeName,
          expiresAt,
        })
      );
    }
    // A record of what was issued, since the keys can't be regenerated offline any more.
    const id = `${Math.floor(now / 1000)}-${crypto.randomUUID().slice(0, 8)}`;
    await env.EXPRESSIVE_PRO_KV.put(
      `admin:issued:${id}`,
      JSON.stringify({ type: typeName, expiresAt, keys, createdAt: new Date(now).toISOString() })
    );
  }

  return jsonResponse({ success: true, type: typeName, expiresAt, keys });
}

/**
 * Handle POST /api/admin/register (Authorization: Bearer <ADMIN_TOKEN>).
 *
 * Registers one or more offline-generated EXPR-PRO keys into Cloudflare KV.
 * Body can be a single item:
 *   { key: "EXPR-PRO-...", device?: "DEV-...", transaction_id?: "..." }
 * Or a batch:
 *   { keys: [ { key: "...", device?: "...", transaction_id?: "..." }, ... ] }
 */
async function handleAdminRegister(request, env, now = Date.now()) {
  if (!env.ADMIN_TOKEN) {
    return jsonResponse({ error: "Endpoint not found" }, 404);
  }
  const header = request.headers.get("Authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!(await secretsEqual(token, env.ADMIN_TOKEN))) {
    return jsonResponse({ success: false, error: "unauthorized" }, 401);
  }

  let body;
  try {
    body = await request.json();
  } catch (err) {
    return jsonResponse({ success: false, error: "invalid_request" }, 400);
  }

  const items = [];
  if (Array.isArray(body?.keys)) {
    if (body.keys.length === 0 || body.keys.length > ADMIN_BATCH_LIMIT) {
      return badAdminRequest(`keys array must contain between 1 and ${ADMIN_BATCH_LIMIT} items`);
    }
    for (const item of body.keys) {
      if (!item?.key) return badAdminRequest("each item in keys must include a key string");
      items.push({
        key: String(item.key).trim(),
        device: item.device ? String(item.device).trim().toUpperCase() : null,
        transactionId: item.transaction_id ? String(item.transaction_id).trim() : null,
      });
    }
  } else if (body?.key) {
    items.push({
      key: String(body.key).trim(),
      device: body.device ? String(body.device).trim().toUpperCase() : null,
      transactionId: body.transaction_id ? String(body.transaction_id).trim() : null,
    });
  } else {
    return badAdminRequest("request body must provide 'key' or 'keys' array");
  }

  const registeredKeys = [];
  const nowSeconds = Math.floor(now / 1000);

  for (const item of items) {
    const license = await parseSignedKey(item.key, env);
    if (!license) {
      return badAdminRequest(`Key signature, CRC, or format is invalid: ${item.key}`);
    }
    if (license.expiresAt > 0 && nowSeconds > license.expiresAt) {
      return badAdminRequest(`Key is already expired: ${item.key}`);
    }

    let boundDevice = item.device || boundDeviceOf(license.recipient);
    if (boundDevice && !DEVICE_ID_PATTERN.test(boundDevice)) {
      return badAdminRequest(`Invalid device ID '${boundDevice}'; must match DEV-XXXX-XXXX`);
    }

    const typeName = Object.keys(ADMIN_TYPES).find((k) => ADMIN_TYPES[k] === license.type) || `TYPE_${license.type}`;
    const txId = item.transactionId || `OFFLINE-${nowSeconds}-${crypto.randomUUID().slice(0, 6)}`;

    const record = {
      key: item.key,
      recipient: license.recipient,
      deviceId: boundDevice || undefined,
      transactionId: txId,
      type: typeName,
      expiresAt: license.expiresAt,
      createdAt: new Date(now).toISOString(),
    };

    if (env.EXPRESSIVE_PRO_KV) {
      if (boundDevice) {
        await env.EXPRESSIVE_PRO_KV.put(`license:device:${boundDevice}`, JSON.stringify(record));
      }
      if (item.transactionId) {
        await env.EXPRESSIVE_PRO_KV.put(`tx:${item.transactionId}`, JSON.stringify(record));
      }
    }

    registeredKeys.push({
      key: item.key,
      recipient: license.recipient,
      device: boundDevice,
      type: typeName,
      expiresAt: license.expiresAt,
      transactionId: txId,
      deepLink: `expressive://pro/activate?key=${item.key}`,
    });
  }

  if (env.EXPRESSIVE_PRO_KV) {
    const auditId = `${nowSeconds}-${crypto.randomUUID().slice(0, 8)}`;
    await env.EXPRESSIVE_PRO_KV.put(
      `admin:registered:${auditId}`,
      JSON.stringify({
        count: registeredKeys.length,
        keys: registeredKeys,
        createdAt: new Date(now).toISOString(),
      })
    );
  }

  return jsonResponse({
    success: true,
    registered: registeredKeys.length,
    keys: registeredKeys,
  });
}

const ISSUED_PAGE_SIZE = 50;

/**
 * Handle GET /api/admin/issued[?cursor=N] (Authorization: Bearer <ADMIN_TOKEN>): the issue and
 * register records (`admin:issued:*`, `admin:registered:*`), newest first, 50 per page. `cursor`
 * is the offset of the next page, or null after the last one.
 */
async function handleAdminIssued(request, env) {
  if (!env.ADMIN_TOKEN) {
    return jsonResponse({ error: "Endpoint not found" }, 404);
  }
  const header = request.headers.get("Authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!(await secretsEqual(token, env.ADMIN_TOKEN))) {
    return jsonResponse({ success: false, error: "unauthorized" }, 401);
  }
  if (!env.EXPRESSIVE_PRO_KV) {
    return jsonResponse({ success: false, message: "Storage backend not configured" }, 503);
  }

  const names = [];
  for (const prefix of ["admin:issued:", "admin:registered:"]) {
    let cursor;
    do {
      const page = await env.EXPRESSIVE_PRO_KV.list({ prefix, cursor });
      for (const k of page.keys) names.push({ name: k.name, id: k.name.slice(prefix.length) });
      cursor = page.list_complete ? undefined : page.cursor;
    } while (cursor);
  }
  // Ids start with the issue time in seconds, so a descending sort is newest first.
  names.sort((a, b) => (a.id < b.id ? 1 : a.id > b.id ? -1 : 0));

  const offset = Math.max(0, parseInt(new URL(request.url).searchParams.get("cursor") || "0", 10) || 0);
  const page = names.slice(offset, offset + ISSUED_PAGE_SIZE);
  const records = [];
  for (const { name, id } of page) {
    const raw = await env.EXPRESSIVE_PRO_KV.get(name);
    if (!raw) continue;
    const data = JSON.parse(raw);
    const keys = (data.keys || []).map((k) => ({
      key: k.key,
      recipient: k.recipient ?? null,
      deepLink: k.deepLink ?? null,
    }));
    const type = data.type ?? data.keys?.[0]?.type ?? null;
    const expiresAt = data.expiresAt ?? data.keys?.[0]?.expiresAt ?? 0;
    records.push({ id, type, expiresAt, createdAt: data.createdAt ?? null, keys });
  }
  const next = offset + ISSUED_PAGE_SIZE < names.length ? String(offset + ISSUED_PAGE_SIZE) : null;
  return jsonResponse({ success: true, records, cursor: next });
}

function badAdminRequest(message) {
  return jsonResponse({ success: false, error: "invalid_request", message }, 400);
}

/**
 * Parse an expiry like scripts/generate_pro_key.py: never/0/none/lifetime, <n>h/d/m/y (a month is 30
 * days), or YYYY-MM-DD (UTC). Returns Unix seconds, 0 for lifetime, or null when invalid or past.
 */
function parseExpiry(value, now) {
  const s = value.trim().toLowerCase();
  if (["never", "0", "none", "lifetime"].includes(s)) return 0;
  const nowSeconds = Math.floor(now / 1000);
  let at = null;
  const duration = s.match(/^(\d+)([hdmy])$/);
  if (duration) {
    const unit = { h: 3600, d: 86400, m: 30 * 86400, y: 365 * 86400 }[duration[2]];
    at = nowSeconds + parseInt(duration[1], 10) * unit;
  } else if (/^\d{4}-\d{2}-\d{2}$/.test(s)) {
    const ms = Date.parse(`${s}T00:00:00Z`);
    at = Number.isNaN(ms) ? null : Math.floor(ms / 1000);
  }
  if (at === null || at <= nowSeconds || at > 0xffffffff) return null;
  return at;
}

/** Constant-time comparison of two secrets (by their SHA-256 digests, so lengths don't leak). */
async function secretsEqual(a, b) {
  if (!a || !b) return false;
  const encoder = new TextEncoder();
  const [da, db] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(a)),
    crypto.subtle.digest("SHA-256", encoder.encode(b)),
  ]);
  const x = new Uint8Array(da);
  const y = new Uint8Array(db);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

/**
 * Verify transaction via PayPal REST API v2
 */
async function verifyWithPayPalApi(transactionId, env) {
  try {
    // Only this merchant's captures are visible with its credentials; a receipt's Transaction ID is
    // the capture ID. A cached token PayPal no longer accepts gets one retry with a fresh one.
    let captureRes;
    for (let attempt = 0; attempt < 2; attempt++) {
      const token = await getPayPalAccessToken(env);
      if (!token) return { valid: false };
      captureRes = await fetch(
        `${paypalApiBase(env)}/v2/payments/captures/${encodeURIComponent(transactionId)}`,
        { headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" } }
      );
      if (captureRes.status !== 401) break;
      forgetPayPalAccessToken();
    }
    if (!captureRes.ok) {
      console.warn(`PayPal capture lookup for ${transactionId} returned ${captureRes.status}`);
      return { valid: false };
    }
    const capture = await captureRes.json();
    if (capture.status === "PENDING") {
      return { valid: false, pending: true };
    }
    const problem = capturePaysForPro(capture, env);
    if (problem) {
      console.warn(`PayPal capture ${transactionId} is not a Pro purchase: ${problem}`);
      return { valid: false };
    }
    return { valid: true, capture };
  } catch (err) {
    console.error("PayPal capture lookup failed:", err);
    return { valid: false };
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
  env,
  features = 0xffffffff
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
async function importPrivateKey(pem, extractable = false) {
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
      extractable,
      ["sign"]
    );
  } catch (err) {
    // If input was raw SEC1 EC private key, wrap in PKCS#8 header
    const pkcs8Der = wrapSec1ToPkcs8(binaryDer);
    return await crypto.subtle.importKey(
      "pkcs8",
      pkcs8Der.buffer,
      { name: "ECDSA", namedCurve: "P-256" },
      extractable,
      ["sign"]
    );
  }
}

let cachedVerifyKey = null;

/**
 * The public half of PRO_PRIVATE_KEY_PEM, so the worker checks keys against exactly the key it signs
 * with (the same key ProLicenseVerifier.kt embeds).
 */
async function importPublicKey(pem) {
  if (cachedVerifyKey?.pem === pem) return cachedVerifyKey.key;
  const jwk = await crypto.subtle.exportKey("jwk", await importPrivateKey(pem, true));
  const key = await crypto.subtle.importKey(
    "jwk",
    { kty: "EC", crv: "P-256", x: jwk.x, y: jwk.y },
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"]
  );
  cachedVerifyKey = { pem, key };
  return key;
}

/**
 * Decode and verify an EXPR-PRO key the way ProLicenseVerifier.kt does. Returns its fields plus `id`
 * (SHA-256 of the decoded bytes), or null if it is malformed or not signed by this worker's key.
 */
async function parseSignedKey(rawKey, env) {
  if (!env.PRO_PRIVATE_KEY_PEM) {
    throw new Error("PRO_PRIVATE_KEY_PEM environment variable is not configured.");
  }
  const bytes = decodeKeyBytes(rawKey);
  if (!bytes || bytes.length < 17 + 1 + 64 + 2) return null;

  const body = bytes.subarray(0, bytes.length - 2);
  const crc = (bytes[bytes.length - 2] << 8) | bytes[bytes.length - 1];
  if (computeCrc16Ccitt(body) !== crc) return null;
  if (bytes[0] !== MAGIC[0] || bytes[1] !== MAGIC[1] || bytes[2] !== SCHEMA_VERSION) return null;

  const payloadLen = 17 + bytes[16];
  if (body.length < payloadLen + 1) return null;
  const sigLen = body[payloadLen];
  if (body.length < payloadLen + 1 + sigLen) return null;
  const rawSig = convertDerToP1363(body.subarray(payloadLen + 1, payloadLen + 1 + sigLen));
  if (!rawSig) return null;

  const payload = bytes.slice(0, payloadLen);
  const valid = await crypto.subtle.verify(
    { name: "ECDSA", hash: { name: "SHA-256" } },
    await importPublicKey(env.PRO_PRIVATE_KEY_PEM),
    rawSig,
    payload
  );
  if (!valid) return null;

  const view = new DataView(payload.buffer);
  return {
    type: payload[3],
    issuedAt: view.getUint32(4, false),
    expiresAt: view.getUint32(8, false),
    features: view.getUint32(12, false),
    recipient: new TextDecoder().decode(payload.subarray(17, payloadLen)),
    id: await sha256Hex(bytes),
  };
}

/** Stable id of a key: SHA-256 of its decoded bytes, so dashes, case and O/0 typos don't matter. */
async function keyId(rawKey) {
  return sha256Hex(decodeKeyBytes(rawKey));
}

/**
 * Crockford Base32 decode of a key, matching ProLicenseVerifier.kt: drop the EXPR-PRO- / EXPR-
 * prefix, read O as 0 and I/L as 1, skip other characters. Null if the padding bits are not zero.
 */
function decodeKeyBytes(rawKey) {
  let clean = String(rawKey).trim().toUpperCase();
  if (clean.startsWith("EXPR-PRO-")) clean = clean.slice("EXPR-PRO-".length);
  else if (clean.startsWith("EXPR-")) clean = clean.slice("EXPR-".length);

  let bitBuf = 0;
  let bitsInBuf = 0;
  const out = [];
  for (const c of clean) {
    const v = CROCKFORD_ALPHABET.indexOf(c === "O" ? "0" : c === "I" || c === "L" ? "1" : c);
    if (v === -1) continue;
    bitBuf = ((bitBuf << 5) | v) & 0xfff;
    bitsInBuf += 5;
    if (bitsInBuf >= 8) {
      bitsInBuf -= 8;
      out.push((bitBuf >> bitsInBuf) & 0xff);
    }
  }
  if (bitsInBuf > 0 && (bitBuf & ((1 << bitsInBuf) - 1)) !== 0) return null;
  return Uint8Array.from(out);
}

/**
 * Convert an ASN.1 DER SEQUENCE { INTEGER r, INTEGER s } signature to the raw 64-byte form Web Crypto
 * verifies. Null if it is not a well-formed P-256 signature.
 */
function convertDerToP1363(der) {
  if (der.length < 8 || der[0] !== 0x30 || der[1] !== der.length - 2) return null;
  const out = new Uint8Array(64);
  let offset = 2;
  for (const target of [0, 32]) {
    if (der[offset] !== 0x02) return null;
    const len = der[offset + 1];
    let value = der.subarray(offset + 2, offset + 2 + len);
    if (value.length !== len) return null;
    while (value.length > 32 && value[0] === 0) value = value.subarray(1);
    if (value.length > 32) return null;
    out.set(value, target + 32 - value.length);
    offset += 2 + len;
  }
  return offset === der.length ? out : null;
}

async function sha256Hex(bytes) {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join("");
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
  const octetString = [0x04, ...derLength(sec1Bytes.length)];
  const body = [
    0x02, 0x01, 0x00,
    0x30, 0x13,
    0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
    0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07,
    ...octetString,
  ];
  // An OpenSSL SEC1 key (with its public key) is 121 bytes, so the outer length needs the long form.
  const prefix = new Uint8Array([0x30, ...derLength(body.length + sec1Bytes.length), ...body]);

  const pkcs8 = new Uint8Array(prefix.length + sec1Bytes.length);
  pkcs8.set(prefix, 0);
  pkcs8.set(sec1Bytes, prefix.length);
  return pkcs8;
}

function derLength(n) {
  return n < 0x80 ? [n] : n < 0x100 ? [0x81, n] : [0x82, n >> 8, n & 0xff];
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

// Exported for unit tests (node --test server/cloudflare-worker/).
export {
  forgetPayPalAccessToken,
  handleAdminIssued,
  handleTrialRequest,
  handleRebind,
  handleVerifyDonation,
  handleAdminIssue,
  handleAdminRegister,
  parseSignedKey,
  generateProLicense,
  TYPE_TRIAL,
  TRIAL_DAYS,
  MAX_DEVICE_MOVES,
};

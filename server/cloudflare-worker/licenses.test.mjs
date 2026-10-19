// Unit tests for license moves, restores, PayPal webhooks and admin issuing.
// Run: node --test server/cloudflare-worker/
import test from "node:test";
import assert from "node:assert/strict";
import worker, {
  handleRebind,
  handleVerifyDonation,
  handleAdminIssue,
  parseSignedKey,
  generateProLicense,
  TYPE_TRIAL,
  MAX_DEVICE_MOVES,
} from "./worker.js";

const OLD_DEVICE = "DEV-AB12-CD34";
const NEW_DEVICE = "DEV-ZZ99-XY00";
const TYPE_VIP = 3;
const TYPE_CAKEY = 5;
const DAY_MS = 86400 * 1000;
const NOW = Date.UTC(2026, 9, 19, 12);

class MemoryKv {
  constructor() { this.map = new Map(); }
  async get(key) { return this.map.has(key) ? this.map.get(key) : null; }
  async put(key, value) { this.map.set(key, value); }
}

async function testEnv(extra = {}) {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const pkcs8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64");
  return {
    EXPRESSIVE_PRO_KV: new MemoryKv(),
    PRO_PRIVATE_KEY_PEM: `-----BEGIN PRIVATE KEY-----\n${pkcs8}\n-----END PRIVATE KEY-----`,
    ...extra,
  };
}

function post(path, body, headers = {}) {
  return new Request(`https://worker.test${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": "203.0.113.7", ...headers },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

const rebind = (env, key, deviceId, now = NOW, ip) =>
  handleRebind(post("/api/rebind", { key, device_id: deviceId }, ip ? { "CF-Connecting-IP": ip } : {}), env, now);

/** Replaces fetch for one test with a handler keyed by URL path. */
function stubFetch(t, routes) {
  const original = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init = {}) => {
    const path = new URL(url).pathname;
    calls.push({ path, body: init.body });
    const handler = routes[path];
    if (!handler) throw new Error(`unexpected fetch ${path}`);
    return new Response(JSON.stringify(handler(init)), { status: 200 });
  };
  t.after(() => { globalThis.fetch = original; });
  return calls;
}

test("parseSignedKey reads a worker key and tolerates case, dashes and O/0 typos", async () => {
  const env = await testEnv();
  const key = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_CAKEY, 0, env);
  const parsed = await parseSignedKey(key, env);
  assert.equal(parsed.type, TYPE_CAKEY);
  assert.equal(parsed.recipient, `device:${OLD_DEVICE}`);
  assert.equal(parsed.features, 0xffffffff);
  const sloppy = key.toLowerCase().replace(/-/g, "").replace(/^exprpro/, "EXPR-PRO-").replace(/0/g, "O");
  assert.equal((await parseSignedKey(sloppy, env)).id, parsed.id);
});

test("signs and verifies with an OpenSSL-style SEC1 key (BEGIN EC PRIVATE KEY)", async () => {
  const { generateKeyPairSync } = await import("node:crypto");
  const { privateKey } = generateKeyPairSync("ec", {
    namedCurve: "P-256",
    privateKeyEncoding: { type: "sec1", format: "pem" },
    publicKeyEncoding: { type: "spki", format: "pem" },
  });
  const env = { EXPRESSIVE_PRO_KV: new MemoryKv(), PRO_PRIVATE_KEY_PEM: privateKey };
  const key = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_VIP, 0, env);
  assert.equal((await parseSignedKey(key, env)).recipient, `device:${OLD_DEVICE}`);
});

test("parseSignedKey rejects keys from another signer and corrupted keys", async () => {
  const env = await testEnv();
  const other = await testEnv();
  const foreign = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_VIP, 0, other);
  assert.equal(await parseSignedKey(foreign, env), null);
  const key = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_VIP, 0, env);
  const flipped = key.slice(0, 20) + (key[20] === "A" ? "B" : "A") + key.slice(21);
  assert.equal(await parseSignedKey(flipped, env), null);
  assert.equal(await parseSignedKey("EXPR-PRO-NOPE", env), null);
});

test("rebind re-signs a device-bound key for the new device, keeping type, expiry and features", async () => {
  const env = await testEnv();
  const expiresAt = Math.floor(NOW / 1000) + 90 * 86400;
  const key = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_CAKEY, expiresAt, env, 0x0000000f);
  const res = await rebind(env, key, NEW_DEVICE);
  assert.equal(res.status, 200);
  const data = await res.json();
  assert.equal(data.recipient, `device:${NEW_DEVICE}`);
  assert.equal(data.movesLeft, MAX_DEVICE_MOVES - 1);
  const moved = await parseSignedKey(data.key, env);
  assert.deepEqual(
    { type: moved.type, expiresAt: moved.expiresAt, features: moved.features, recipient: moved.recipient },
    { type: TYPE_CAKEY, expiresAt, features: 0x0000000f, recipient: `device:${NEW_DEVICE}` }
  );
});

test("rebind to a device the license already moved to returns that key without using a move", async () => {
  const env = await testEnv();
  const key = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_VIP, 0, env);
  const first = await (await rebind(env, key, NEW_DEVICE)).json();
  const again = await (await rebind(env, key, NEW_DEVICE, NOW + 60_000)).json();
  assert.equal(again.key, first.key);
  assert.equal(again.movesLeft, MAX_DEVICE_MOVES - 1);
});

test("a license moves to at most MAX_DEVICE_MOVES new devices per 30 days, counted across its copies", async () => {
  const env = await testEnv();
  let key = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_VIP, 0, env);
  // Chain the moves: each reset presents the key the previous move produced.
  for (let i = 0; i < MAX_DEVICE_MOVES; i++) {
    const res = await rebind(env, key, `DEV-MVXX-000${i}`, NOW + i * DAY_MS);
    assert.equal(res.status, 200);
    key = (await res.json()).key;
  }
  const blocked = await rebind(env, key, "DEV-MVXX-0009", NOW + 5 * DAY_MS);
  assert.equal(blocked.status, 409);
  assert.equal((await blocked.json()).error, "transfer_limit");

  const later = await rebind(env, key, "DEV-MVXX-0009", NOW + 31 * DAY_MS);
  assert.equal(later.status, 200, "the first move has left the 30-day window");
});

test("rebind returns keys that are not device-bound unchanged", async () => {
  const env = await testEnv();
  for (const recipient of ["account:fan@example.com", "giveaway-1791000000-001"]) {
    const key = await generateProLicense(recipient, TYPE_VIP, 0, env);
    const data = await (await rebind(env, key, NEW_DEVICE)).json();
    assert.equal(data.key, key);
  }
  assert.equal([...env.EXPRESSIVE_PRO_KV.map.keys()].filter((k) => k.startsWith("moves:")).length, 0);
});

test("rebind refuses trial, expired, forged and malformed requests", async () => {
  const env = await testEnv();
  const nowSeconds = Math.floor(NOW / 1000);
  const trial = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_TRIAL, nowSeconds + 86400, env);
  const expired = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_VIP, nowSeconds - 1, env);
  const forged = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_VIP, 0, await testEnv());
  const cases = [
    [trial, NEW_DEVICE, "trial_key"],
    [expired, NEW_DEVICE, "expired"],
    [forged, NEW_DEVICE, "invalid_key"],
    [forged, "not-a-device", "invalid_request"],
  ];
  for (const [key, device, error] of cases) {
    const res = await rebind(env, key, device);
    assert.equal(res.status, 400);
    assert.equal((await res.json()).error, error);
  }
});

test("rebind is rate limited per address", async () => {
  const env = await testEnv();
  const forged = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_VIP, 0, await testEnv());
  const statuses = [];
  for (let i = 0; i < 11; i++) statuses.push((await rebind(env, forged, NEW_DEVICE, NOW, "198.51.100.4")).status);
  assert.deepEqual(statuses, [...Array(10).fill(400), 429]);
});

test("verify-donation restores a stored purchase on a new device with a key for that device", async () => {
  const env = await testEnv();
  const original = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_VIP, 0, env);
  const record = { key: original, recipient: `device:${OLD_DEVICE}`, transactionId: "5Y6345678A1234567" };
  await env.EXPRESSIVE_PRO_KV.put("tx:5Y6345678A1234567", JSON.stringify(record));

  const same = await (await handleVerifyDonation(post("/api/verify-donation", { transaction_id: "5Y6345678A1234567", device_id: OLD_DEVICE }), env, NOW)).json();
  assert.equal(same.key, original);

  const res = await handleVerifyDonation(post("/api/verify-donation", { transaction_id: "5Y6345678A1234567", device_id: NEW_DEVICE }), env, NOW);
  const data = await res.json();
  assert.equal(data.success, true);
  assert.equal((await parseSignedKey(data.key, env)).recipient, `device:${NEW_DEVICE}`);
  const stored = JSON.parse(await env.EXPRESSIVE_PRO_KV.get(`license:device:${NEW_DEVICE}`));
  assert.equal(stored.key, data.key);

  // The app's own key and the restore share one move allowance.
  const viaKey = await (await rebind(env, original, NEW_DEVICE)).json();
  assert.equal(viaKey.key, data.key);
});

test("verify-donation asks for the transaction ID instead of accepting an email or device ID", async () => {
  const env = await testEnv();
  await env.EXPRESSIVE_PRO_KV.put(`tx:${OLD_DEVICE}`, JSON.stringify({ key: "x", recipient: `device:${OLD_DEVICE}` }));
  const before = env.EXPRESSIVE_PRO_KV.map.size;
  for (const body of [
    { transaction_id: "", email: "fan@example.com", device_id: NEW_DEVICE },
    { transaction_id: "fan@example.com", device_id: NEW_DEVICE },
    { transaction_id: OLD_DEVICE, device_id: NEW_DEVICE },
  ]) {
    const res = await handleVerifyDonation(post("/api/verify-donation", body), env, NOW);
    assert.equal(res.status, 200, "older apps show the message on HTTP 200");
    const data = await res.json();
    assert.equal(data.success, false);
    assert.equal(data.error, "transaction_required");
  }
  assert.equal(env.EXPRESSIVE_PRO_KV.map.size, before);
});

test("license lookup by email no longer returns a key", async () => {
  const env = await testEnv();
  await env.EXPRESSIVE_PRO_KV.put("license:email:fan@example.com", JSON.stringify({ key: "EXPR-PRO-SECRET", recipient: "account:fan@example.com" }));
  const res = await worker.fetch(new Request(`https://worker.test/api/license?device_id=${NEW_DEVICE}&email=fan@example.com`), env, {});
  const data = await res.json();
  assert.equal(data.success, false);
  assert.equal(data.key, undefined);
  assert.equal(env.EXPRESSIVE_PRO_KV.map.has(`license:device:${NEW_DEVICE}`), false);
});

const PAYPAL_HEADERS = {
  "PAYPAL-AUTH-ALGO": "SHA256withRSA",
  "PAYPAL-CERT-URL": "https://api.paypal.com/v1/notifications/certs/CERT-1",
  "PAYPAL-TRANSMISSION-ID": "tx-1",
  "PAYPAL-TRANSMISSION-SIG": "sig",
  "PAYPAL-TRANSMISSION-TIME": "2026-10-19T12:00:00Z",
};
const PAYPAL_SECRETS = { PAYPAL_WEBHOOK_ID: "WH-1", PAYPAL_CLIENT_ID: "id", PAYPAL_CLIENT_SECRET: "secret" };
const captureEvent = (captureId = "CAPTURE-1") => ({
  event_type: "PAYMENT.CAPTURE.COMPLETED",
  resource: { id: captureId, custom_id: NEW_DEVICE, invoice_id: NEW_DEVICE, payer: { email_address: "fan@example.com" } },
});

test("webhook refuses events while signature verification is not configured", async () => {
  const env = await testEnv();
  const res = await worker.fetch(post("/api/paypal-webhook", captureEvent(), PAYPAL_HEADERS), env, {});
  assert.equal(res.status, 503);
  assert.equal(env.EXPRESSIVE_PRO_KV.map.size, 0);
});

test("webhook refuses events PayPal does not vouch for, or that lack its headers", async (t) => {
  const env = await testEnv(PAYPAL_SECRETS);
  stubFetch(t, {
    "/v1/oauth2/token": () => ({ access_token: "token" }),
    "/v1/notifications/verify-webhook-signature": () => ({ verification_status: "FAILURE" }),
  });
  const forged = await worker.fetch(post("/api/paypal-webhook", captureEvent(), PAYPAL_HEADERS), env, {});
  assert.equal(forged.status, 401);
  const bare = await worker.fetch(post("/api/paypal-webhook", captureEvent()), env, {});
  assert.equal(bare.status, 401);
  assert.equal(env.EXPRESSIVE_PRO_KV.map.size, 0);
});

test("a verified webhook stores one license per payment and never returns the key", async (t) => {
  const env = await testEnv(PAYPAL_SECRETS);
  const calls = stubFetch(t, {
    "/v1/oauth2/token": () => ({ access_token: "token" }),
    "/v1/notifications/verify-webhook-signature": () => ({ verification_status: "SUCCESS" }),
  });
  const res = await worker.fetch(post("/api/paypal-webhook", captureEvent(), PAYPAL_HEADERS), env, {});
  assert.equal(res.status, 200);
  assert.equal((await res.json()).key, undefined);
  const sent = JSON.parse(calls.find((c) => c.path.endsWith("verify-webhook-signature")).body);
  assert.equal(sent.webhook_id, "WH-1");
  assert.equal(sent.transmission_sig, "sig");

  const stored = JSON.parse(await env.EXPRESSIVE_PRO_KV.get(`license:device:${NEW_DEVICE}`));
  assert.equal((await parseSignedKey(stored.key, env)).recipient, `device:${NEW_DEVICE}`);
  assert.ok(env.EXPRESSIVE_PRO_KV.map.has("tx:CAPTURE-1"));
  assert.equal(env.EXPRESSIVE_PRO_KV.map.has(`tx:${NEW_DEVICE}`), false, "a device ID is not a proof of payment");

  await worker.fetch(post("/api/paypal-webhook", captureEvent(), PAYPAL_HEADERS), env, {});
  const after = JSON.parse(await env.EXPRESSIVE_PRO_KV.get(`license:device:${NEW_DEVICE}`));
  assert.equal(after.key, stored.key, "a redelivered event keeps the first key");
});

const admin = (env, body, token = "admin-secret") =>
  handleAdminIssue(post("/api/admin/issue", body, token ? { Authorization: `Bearer ${token}` } : {}), env, NOW);

test("admin issuing is off without ADMIN_TOKEN and needs the right token", async () => {
  assert.equal((await admin(await testEnv(), { device: OLD_DEVICE })).status, 404);
  const env = await testEnv({ ADMIN_TOKEN: "admin-secret" });
  assert.equal((await admin(env, { device: OLD_DEVICE }, "wrong")).status, 401);
  assert.equal((await admin(env, { device: OLD_DEVICE }, null)).status, 401);
});

test("admin issues a single key like generate_pro_key.py create", async () => {
  const env = await testEnv({ ADMIN_TOKEN: "admin-secret" });
  const res = await admin(env, { type: "cakey", device: "dev-a1b2-c3d4", expires: "30d" });
  assert.equal(res.status, 200);
  const data = await res.json();
  assert.equal(data.type, "CAKEY");
  assert.equal(data.expiresAt, Math.floor(NOW / 1000) + 30 * 86400);
  const [issued] = data.keys;
  assert.equal(issued.recipient, "device:DEV-A1B2-C3D4");
  assert.equal(issued.deepLink, `expressive://pro/activate?key=${issued.key}`);
  const parsed = await parseSignedKey(issued.key, env);
  assert.equal(parsed.type, TYPE_CAKEY);
  assert.equal(parsed.expiresAt, data.expiresAt);
  assert.equal([...env.EXPRESSIVE_PRO_KV.map.keys()].filter((k) => k.startsWith("admin:issued:")).length, 1);

  const account = await (await admin(env, { type: "VIP", account: "Fan@Example.com" })).json();
  assert.equal(account.keys[0].recipient, "account:fan@example.com");
  assert.equal(account.expiresAt, 0);
});

test("admin batches keys like generate_pro_key.py batch", async () => {
  const env = await testEnv({ ADMIN_TOKEN: "admin-secret" });
  const data = await (await admin(env, { type: "GIVEAWAY", count: 3, prefix: "xda-", expires: "2027-01-01" })).json();
  const stamp = Math.floor(NOW / 1000);
  assert.deepEqual(data.keys.map((k) => k.recipient), [`xda-${stamp}-001`, `xda-${stamp}-002`, `xda-${stamp}-003`]);
  assert.equal(data.expiresAt, Date.UTC(2027, 0, 1) / 1000);
  for (const k of data.keys) assert.equal((await parseSignedKey(k.key, env)).type, 2);
});

test("admin rejects bad types, expiries, counts and mixed batch options", async () => {
  const env = await testEnv({ ADMIN_TOKEN: "admin-secret" });
  for (const body of [
    { type: "TRIAL", device: OLD_DEVICE },
    { device: OLD_DEVICE, expires: "soon" },
    { device: OLD_DEVICE, expires: "2020-01-01" },
    { count: 0 },
    { count: 101 },
    { count: 2, device: OLD_DEVICE },
    { device: "DEV-1" },
    {},
  ]) {
    const res = await admin(env, body);
    assert.equal(res.status, 400, JSON.stringify(body));
  }
  assert.equal(env.EXPRESSIVE_PRO_KV.map.size, 0);
});

test("routes the new endpoints", async () => {
  const env = await testEnv({ ADMIN_TOKEN: "admin-secret" });
  const issued = await worker.fetch(post("/api/admin/issue", { device: OLD_DEVICE }, { Authorization: "Bearer admin-secret" }), env, {});
  const { keys } = await issued.json();
  const moved = await worker.fetch(post("/api/rebind", { key: keys[0].key, device_id: NEW_DEVICE }), env, {});
  assert.equal(moved.status, 200);
  assert.equal((await moved.json()).recipient, `device:${NEW_DEVICE}`);
});

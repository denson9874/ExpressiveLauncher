// Unit tests for license moves, restores, PayPal webhooks and admin issuing.
// Run: node --test server/cloudflare-worker/
import test from "node:test";
import assert from "node:assert/strict";
import worker, {
  handleRebind,
  handleVerifyDonation,
  handleAdminIssue,
  handleAdminIssued,
  handleAdminRegister,
  parseSignedKey,
  generateProLicense,
  LicenseLedger,
  TYPE_TRIAL,
  MAX_DEVICE_MOVES,
  forgetPayPalAccessToken,
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
  async list({ prefix = "", cursor } = {}) {
    // Two keys per page, to exercise cursor paging like Cloudflare's 1000-key pages.
    const names = [...this.map.keys()].filter((k) => k.startsWith(prefix)).sort();
    const start = cursor ? parseInt(cursor, 10) : 0;
    const keys = names.slice(start, start + 2).map((name) => ({ name }));
    const done = start + 2 >= names.length;
    return { keys, list_complete: done, cursor: done ? undefined : String(start + 2) };
  }
}

/** Durable Object storage whose reads yield to other work, so unserialized code would interleave. */
class MemoryStorage {
  constructor() { this.map = new Map(); }
  async get(key) {
    await new Promise((resolve) => setImmediate(resolve));
    return this.map.has(key) ? structuredClone(this.map.get(key)) : undefined;
  }
  async put(keyOrEntries, value) {
    await new Promise((resolve) => setImmediate(resolve));
    const entries = typeof keyOrEntries === "string" ? { [keyOrEntries]: value } : keyOrEntries;
    for (const [k, v] of Object.entries(entries)) this.map.set(k, structuredClone(v));
  }
}

/** Stand-in for a Durable Object namespace: one instance per name; blockConcurrencyWhile queues calls. */
class FakeDurableObjects {
  constructor(ObjectClass, env) {
    this.ObjectClass = ObjectClass;
    this.env = env;
    this.instances = new Map();
  }
  idFromName(name) { return name; }
  get(id) {
    if (!this.instances.has(id)) {
      let queue = Promise.resolve();
      const state = {
        storage: new MemoryStorage(),
        blockConcurrencyWhile(callback) {
          const run = queue.then(callback);
          queue = run.catch(() => {});
          return run;
        },
      };
      this.instances.set(id, { object: new this.ObjectClass(state, this.env), storage: state.storage });
    }
    const { object } = this.instances.get(id);
    return { fetch: (url, init) => object.fetch(new Request(url, init)) };
  }
  storageKeys() {
    return [...this.instances.values()].flatMap((i) => [...i.storage.map.keys()]);
  }
}

async function testEnv(extra = {}) {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const pkcs8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64");
  const env = {
    EXPRESSIVE_PRO_KV: new MemoryKv(),
    PRO_PRIVATE_KEY_PEM: `-----BEGIN PRIVATE KEY-----\n${pkcs8}\n-----END PRIVATE KEY-----`,
    ...extra,
  };
  env.LICENSE_LEDGER = new FakeDurableObjects(LicenseLedger, env);
  return env;
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

/**
 * Replaces fetch for one test with a handler keyed by URL path. A handler may return
 * { status, body } to answer with another status. The cached PayPal token is dropped before and after.
 */
function stubFetch(t, routes) {
  const original = globalThis.fetch;
  const calls = [];
  forgetPayPalAccessToken();
  globalThis.fetch = async (url, init = {}) => {
    const path = new URL(url).pathname;
    calls.push({ path, body: init.body });
    const handler = routes[path];
    if (!handler) throw new Error(`unexpected fetch ${path}`);
    const out = handler(init);
    const withStatus = out && typeof out.status === "number" && "body" in out;
    return new Response(JSON.stringify(withStatus ? out.body : out), { status: withStatus ? out.status : 200 });
  };
  t.after(() => { globalThis.fetch = original; forgetPayPalAccessToken(); });
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

test("parallel moves to different devices can't exceed the limit", async () => {
  const env = await testEnv();
  const key = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_VIP, 0, env);
  const devices = ["DEV-PAR0-0001", "DEV-PAR0-0002", "DEV-PAR0-0003", "DEV-PAR0-0004", "DEV-PAR0-0005"];
  const responses = await Promise.all(devices.map((d, i) => rebind(env, key, d, NOW, `198.51.100.${i + 20}`)));
  const statuses = responses.map((r) => r.status).sort();
  assert.deepEqual(statuses, [200, 200, 200, 409, 409]);

  // Every key that was handed out is on record, so later moves still count all of them.
  const blocked = await rebind(env, key, "DEV-PAR0-0009", NOW + DAY_MS);
  assert.equal(blocked.status, 409);
});

test("a just-moved key is traced to its license, so chaining moves uses the same allowance", async () => {
  const env = await testEnv();
  const original = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_VIP, 0, env);
  const first = await (await rebind(env, original, NEW_DEVICE)).json();
  const second = await (await rebind(env, first.key, "DEV-CHAN-0001")).json();
  assert.equal(second.movesLeft, MAX_DEVICE_MOVES - 2);
});

test("rebind returns keys that are not device-bound unchanged", async () => {
  const env = await testEnv();
  for (const recipient of ["account:fan@example.com", "giveaway-1791000000-001"]) {
    const key = await generateProLicense(recipient, TYPE_VIP, 0, env);
    const data = await (await rebind(env, key, NEW_DEVICE)).json();
    assert.equal(data.key, key);
  }
  assert.equal(env.LICENSE_LEDGER.storageKeys().length, 0);
});

test("moves fail closed when the ledger binding is missing", async () => {
  const env = await testEnv();
  delete env.LICENSE_LEDGER;
  const key = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_VIP, 0, env);
  const res = await worker.fetch(post("/api/rebind", { key, device_id: NEW_DEVICE }), env, {});
  assert.equal(res.status, 500);
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
/** A PayPal capture as the API and PAYMENT.CAPTURE.* webhooks describe it: the Pro price, paid. */
const capture = (id = "CAPTURE-1", extra = {}) => ({
  id,
  status: "COMPLETED",
  amount: { currency_code: "USD", value: "4.99" },
  supplementary_data: { related_ids: { order_id: `ORDER-${id}` } },
  ...extra,
});
const captureEvent = (captureId = "CAPTURE-1", extra = { custom_id: NEW_DEVICE, invoice_id: NEW_DEVICE }) => ({
  event_type: "PAYMENT.CAPTURE.COMPLETED",
  resource: capture(captureId, { payer: { email_address: "fan@example.com" }, ...extra }),
});
/** PayPal vouching for every webhook delivery, and knowing the given captures by ID. */
const paypal = (captures = {}) => ({
  "/v1/oauth2/token": () => ({ access_token: "token", expires_in: 32400 }),
  "/v1/notifications/verify-webhook-signature": () => ({ verification_status: "SUCCESS" }),
  ...Object.fromEntries(Object.entries(captures).map(([id, c]) => [`/v2/payments/captures/${id}`, () => c])),
});
const webhook = (env, event) => worker.fetch(post("/api/paypal-webhook", event, PAYPAL_HEADERS), env, {});
const restore = (env, transactionId, deviceId, ip) =>
  handleVerifyDonation(
    post("/api/verify-donation", { transaction_id: transactionId, device_id: deviceId }, ip ? { "CF-Connecting-IP": ip } : {}),
    env,
    NOW
  );
const lookup = async (env, deviceId) =>
  (await worker.fetch(new Request(`https://worker.test/api/license?device_id=${deviceId}`), env, {})).json();
const keysWith = (env, prefix) => [...env.EXPRESSIVE_PRO_KV.map.keys()].filter((k) => k.startsWith(prefix));

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

test("only a completed capture of the Pro price mints: approvals, sales and short payments are ignored", async (t) => {
  const env = await testEnv(PAYPAL_SECRETS);
  stubFetch(t, paypal());
  const ignored = [
    { event_type: "CHECKOUT.ORDER.APPROVED", resource: { id: "ORDER-9", purchase_units: [{ custom_id: NEW_DEVICE }] } },
    { event_type: "PAYMENT.SALE.COMPLETED", resource: { id: "SALE-9", custom: NEW_DEVICE } },
    captureEvent("C-PENDING", { custom_id: NEW_DEVICE, status: "PENDING" }),
    captureEvent("C-CHEAP", { custom_id: NEW_DEVICE, amount: { currency_code: "USD", value: "4.98" } }),
    captureEvent("C-EUR", { custom_id: NEW_DEVICE, amount: { currency_code: "EUR", value: "9.99" } }),
    captureEvent("C-JUNK", { custom_id: NEW_DEVICE, amount: { currency_code: "USD", value: "4.99e9" } }),
    // Paid in full, but not a capture event: only PAYMENT.CAPTURE.COMPLETED means the money arrived.
    { ...captureEvent("C-ORDER", { custom_id: NEW_DEVICE }), event_type: "CHECKOUT.ORDER.COMPLETED" },
  ];
  for (const event of ignored) {
    const res = await webhook(env, event);
    assert.equal(res.status, 200, "genuine but not a purchase: PayPal must not redeliver");
  }
  assert.deepEqual([...env.EXPRESSIVE_PRO_KV.map.keys()], []);

  // More than the price (a tip on top) still buys Pro.
  await webhook(env, captureEvent("C-TIP", { custom_id: NEW_DEVICE, amount: { currency_code: "USD", value: "10" } }));
  assert.ok((await lookup(env, NEW_DEVICE)).key);
});

test("PAYPAL_MERCHANT_ID refuses captures paid to someone else", async (t) => {
  const env = await testEnv({ ...PAYPAL_SECRETS, PAYPAL_MERCHANT_ID: "MERCHANT-A" });
  stubFetch(t, paypal());
  await webhook(env, captureEvent("C-OTHER", { custom_id: NEW_DEVICE, payee: { merchant_id: "MERCHANT-B" } }));
  assert.equal((await lookup(env, NEW_DEVICE)).success, false);
  await webhook(env, captureEvent("C-OURS", { custom_id: NEW_DEVICE, payee: { merchant_id: "MERCHANT-A" } }));
  assert.ok((await lookup(env, NEW_DEVICE)).key);
});

test("a payment without the device ID waits, unsigned, for the first restore with its Transaction ID", async (t) => {
  const env = await testEnv(PAYPAL_SECRETS);
  stubFetch(t, paypal());
  // PayPal's no-code checkout may drop the app's custom / invoice_id; a malformed one is no device either.
  await webhook(env, captureEvent("CAPNODEV00000001", { custom_id: "hello", invoice_id: undefined }));
  const held = JSON.parse(await env.EXPRESSIVE_PRO_KV.get("tx:CAPNODEV00000001"));
  assert.equal(held.key, undefined, "no license until a device claims it");
  assert.equal(held.amount, "4.99");
  assert.ok(env.EXPRESSIVE_PRO_KV.map.has("tx:ORDER-CAPNODEV00000001"));
  assert.deepEqual(keysWith(env, "license:device:"), []);

  const first = await (await restore(env, "CAPNODEV00000001", OLD_DEVICE)).json();
  assert.equal(first.success, true);
  assert.equal((await parseSignedKey(first.key, env)).recipient, `device:${OLD_DEVICE}`);
  assert.equal((await lookup(env, OLD_DEVICE)).key, first.key, "Check payment status finds it from now on");

  // The order ID from the same receipt leads to the same license, not a second one.
  const viaOrder = await (await restore(env, "ORDER-CAPNODEV00000001", OLD_DEVICE)).json();
  assert.equal(viaOrder.key, first.key);

  // Another device with the same receipt gets a moved copy, within the license's move allowance.
  const second = await (await restore(env, "CAPNODEV00000001", NEW_DEVICE)).json();
  assert.equal((await parseSignedKey(second.key, env)).recipient, `device:${NEW_DEVICE}`);
  assert.equal(second.movesLeft, MAX_DEVICE_MOVES - 1);
});

test("a webhook and restores racing for one payment produce exactly one license", async (t) => {
  const env = await testEnv(PAYPAL_SECRETS);
  stubFetch(t, paypal({ "CAPRACE000000001": capture("CAPRACE000000001") }));
  const results = await Promise.all([
    webhook(env, captureEvent("CAPRACE000000001", { custom_id: OLD_DEVICE })),
    restore(env, "CAPRACE000000001", NEW_DEVICE),
    restore(env, "CAPRACE000000001", NEW_DEVICE, "198.51.100.9"),
  ]);
  assert.ok(results.every((r) => r.status === 200));
  const payments = env.LICENSE_LEDGER.storageKeys().filter((k) => k.startsWith("payment:"));
  assert.deepEqual(payments, ["payment:CAPRACE000000001"]);
  const ledger = env.LICENSE_LEDGER.get("license-moves");
  const record = await (await ledger.fetch("https://license-ledger/payment", {
    method: "POST",
    body: JSON.stringify({ captureId: "CAPRACE000000001", deviceId: null, now: NOW }),
  })).json();
  const root = await parseSignedKey(record.record.key, env);
  // One key was signed for the payment. Whichever device lost the race holds a moved copy of it, so
  // the license's move ledger names exactly that device.
  const winner = root.recipient.slice("device:".length);
  const loser = winner === OLD_DEVICE ? NEW_DEVICE : OLD_DEVICE;
  const storage = env.LICENSE_LEDGER.instances.get("license-moves").storage;
  assert.deepEqual(Object.keys(storage.map.get(`moves:${root.id}`)?.devices || {}), [loser]);
  const copy = await (await restore(env, "CAPRACE000000001", loser, "192.0.2.1")).json();
  assert.equal((await parseSignedKey(copy.key, env)).recipient, `device:${loser}`);
  assert.equal(copy.movesLeft, MAX_DEVICE_MOVES - 1, "asking again uses no second move");
});

test("a refund or reversal stops the license being handed out, on every device it reached", async (t) => {
  const env = await testEnv(PAYPAL_SECRETS);
  stubFetch(t, paypal());
  await webhook(env, captureEvent("CAPREFUND0000001", { custom_id: OLD_DEVICE }));
  const moved = await (await restore(env, "CAPREFUND0000001", NEW_DEVICE)).json();
  assert.ok(moved.key);

  await webhook(env, {
    event_type: "PAYMENT.CAPTURE.REFUNDED",
    resource: {
      id: "REFUND-1",
      status: "COMPLETED",
      links: [{ rel: "up", href: "https://api.paypal.com/v2/payments/captures/CAPREFUND0000001" }],
    },
  });
  for (const device of [OLD_DEVICE, NEW_DEVICE]) {
    const res = await lookup(env, device);
    assert.equal(res.success, false);
    assert.equal(res.error, "payment_revoked");
    assert.equal(res.key, undefined);
  }
  const again = await (await restore(env, "CAPREFUND0000001", "DEV-QQ11-RR22")).json();
  assert.equal(again.error, "payment_revoked");
  // The receipt's order ID, on the device that bought it, is refused too.
  const viaOrder = await (await restore(env, "ORDER-CAPREFUND0000001", OLD_DEVICE)).json();
  assert.equal(viaOrder.error, "payment_revoked");
  assert.equal(viaOrder.key, undefined);

  // The license can't move on with its own key either.
  const viaKey = await (await rebind(env, moved.key, "DEV-QQ11-RR22", NOW, "192.0.2.50")).json();
  assert.equal(viaKey.error, "payment_revoked");
  assert.equal(viaKey.key, undefined);

  // A reversal arriving before its capture event: the late claim never signs a key.
  await webhook(env, {
    event_type: "PAYMENT.CAPTURE.REVERSED",
    resource: { id: "REFUND-2", links: [{ rel: "up", href: "https://api.paypal.com/v2/payments/captures/CAPEARLY00000001" }] },
  });
  await webhook(env, captureEvent("CAPEARLY00000001", { custom_id: "DEV-QQ11-RR22" }));
  assert.equal((await lookup(env, "DEV-QQ11-RR22")).success, false);
  assert.equal(JSON.parse(await env.EXPRESSIVE_PRO_KV.get("tx:CAPEARLY00000001")).key, undefined, "no key was signed");

  // A denied capture names itself.
  await webhook(env, { event_type: "PAYMENT.CAPTURE.DENIED", resource: { id: "CAPDENIED0000001", status: "DECLINED" } });
  await webhook(env, captureEvent("CAPDENIED0000001", { custom_id: "DEV-SS33-TT44" }));
  assert.equal((await lookup(env, "DEV-SS33-TT44")).success, false);
});

const refundEvent = (captureId, refunded) => ({
  event_type: "PAYMENT.CAPTURE.REFUNDED",
  resource: {
    id: `REFUND-${captureId}`,
    status: "COMPLETED",
    seller_payable_breakdown: { total_refunded_amount: { currency_code: "USD", value: refunded } },
    links: [{ rel: "up", href: `https://api.paypal.com/v2/payments/captures/${captureId}` }],
  },
});

test("a partial refund that leaves the Pro price paid keeps the license; one below it revokes", async (t) => {
  const env = await testEnv(PAYPAL_SECRETS);
  stubFetch(t, paypal());
  await webhook(env, captureEvent("CAPTIPPED0000001", { custom_id: OLD_DEVICE, amount: { currency_code: "USD", value: "10.00" } }));
  await webhook(env, refundEvent("CAPTIPPED0000001", "5.01"));
  assert.ok((await lookup(env, OLD_DEVICE)).key, "the tip came back, the Pro price stayed");
  assert.equal(JSON.parse(await env.EXPRESSIVE_PRO_KV.get("tx:CAPTIPPED0000001")).revokedReason, undefined);
  await webhook(env, refundEvent("CAPTIPPED0000001", "5.02"));
  assert.equal((await lookup(env, OLD_DEVICE)).error, "payment_revoked");
});

test("payments recorded in KV before the ledger kept them are adopted, never signed again", async (t) => {
  const env = await testEnv(PAYPAL_SECRETS);
  stubFetch(t, paypal());
  const key = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_VIP, 0, env);
  const legacy = { key, recipient: `device:${OLD_DEVICE}`, transactionId: "CAPLEGACY0000001" };
  await env.EXPRESSIVE_PRO_KV.put("tx:CAPLEGACY0000001", JSON.stringify(legacy));
  await env.EXPRESSIVE_PRO_KV.put(`license:device:${OLD_DEVICE}`, JSON.stringify(legacy));

  // PayPal redelivers the old capture event, now naming another device.
  await webhook(env, captureEvent("CAPLEGACY0000001", { custom_id: NEW_DEVICE }));
  assert.equal(JSON.parse(await env.EXPRESSIVE_PRO_KV.get("tx:CAPLEGACY0000001")).key, key);
  const moved = await lookup(env, NEW_DEVICE);
  assert.equal((await parseSignedKey(moved.key, env)).recipient, `device:${NEW_DEVICE}`, "a moved copy, not a new license");

  // Its refund reaches both devices, though the ledger never saw the payment arrive.
  await webhook(env, refundEvent("CAPLEGACY0000001", "4.99"));
  assert.equal((await lookup(env, OLD_DEVICE)).error, "payment_revoked");
  assert.equal((await lookup(env, NEW_DEVICE)).error, "payment_revoked");
});

test("a new purchase replaces a device's refunded license", async (t) => {
  const env = await testEnv(PAYPAL_SECRETS);
  stubFetch(t, paypal());
  await webhook(env, captureEvent("CAPFIRST00000001", { custom_id: OLD_DEVICE }));
  await webhook(env, refundEvent("CAPFIRST00000001", "4.99"));
  assert.equal((await lookup(env, OLD_DEVICE)).error, "payment_revoked");
  await webhook(env, captureEvent("CAPSECOND0000001", { custom_id: OLD_DEVICE }));
  const now = await lookup(env, OLD_DEVICE);
  assert.equal(now.success, true);
  assert.ok(now.key);
});

test("a restore checks the capture with PayPal: the Pro price, completed, else a generic refusal", async (t) => {
  const env = await testEnv(PAYPAL_SECRETS);
  const calls = stubFetch(t, {
    ...paypal({
      "7AB12345CD678901E": capture("7AB12345CD678901E"),
      "8AB12345CD678901E": capture("8AB12345CD678901E", { amount: { currency_code: "USD", value: "1.00" } }),
    }),
    "/v2/payments/captures/9AB12345CD678901E": () => ({ status: 404, body: { name: "RESOURCE_NOT_FOUND", debug_id: "abc" } }),
  });
  const ok = await (await restore(env, "7AB12345CD678901E", NEW_DEVICE)).json();
  assert.equal(ok.success, true);
  assert.equal((await parseSignedKey(ok.key, env)).recipient, `device:${NEW_DEVICE}`);
  assert.ok(env.EXPRESSIVE_PRO_KV.map.has("tx:7AB12345CD678901E"));

  for (const tx of ["8AB12345CD678901E", "9AB12345CD678901E", "not a tx!"]) {
    const res = await restore(env, tx, OLD_DEVICE);
    const data = await res.json();
    assert.equal(data.success, false);
    assert.equal(data.error, "payment_not_confirmed");
    assert.doesNotMatch(JSON.stringify(data), /RESOURCE_NOT_FOUND|debug|1\.00|status/i, "no PayPal details leak");
  }
  assert.equal(calls.filter((c) => c.path === "/v1/oauth2/token").length, 1, "one token serves every lookup");
});

test("restores are rate limited per address", async (t) => {
  const env = await testEnv(PAYPAL_SECRETS);
  const calls = stubFetch(t, { ...paypal(), "/v2/payments/captures/1AB12345CD678901E": () => ({ status: 404, body: {} }) });
  const responses = [];
  for (let i = 0; i < 11; i++) responses.push(await restore(env, "1AB12345CD678901E", NEW_DEVICE));
  assert.ok(responses.every((r) => r.status === 200));
  const bodies = await Promise.all(responses.map((r) => r.json()));
  assert.deepEqual(bodies.slice(0, 10).map((b) => b.error), Array(10).fill("payment_not_confirmed"));
  // HTTP 200: 5.0.x apps call any other status "server unreachable".
  assert.equal(bodies[10].error, "rate_limited");
  assert.equal(calls.filter((c) => c.path.startsWith("/v2/payments/captures/")).length, 10, "limited requests reach no PayPal API");
  const other = await (await restore(env, "1AB12345CD678901E", NEW_DEVICE, "198.51.100.20")).json();
  assert.equal(other.error, "payment_not_confirmed", "other addresses are not affected");
});

test("restores accept a retyped lower-case Transaction ID and wait out a pending capture", async (t) => {
  const env = await testEnv(PAYPAL_SECRETS);
  stubFetch(t, paypal({
    "2AB12345CD678901E": capture("2AB12345CD678901E"),
    "3AB12345CD678901E": capture("3AB12345CD678901E", { status: "PENDING" }),
  }));
  const typed = await (await restore(env, "2ab12345cd678901e", NEW_DEVICE)).json();
  assert.equal(typed.success, true);
  assert.ok(env.EXPRESSIVE_PRO_KV.map.has("tx:2AB12345CD678901E"), "one record under PayPal's spelling");
  assert.equal((await (await restore(env, "2ab12345cd678901e", NEW_DEVICE)).json()).key, typed.key);

  const pending = await restore(env, "3AB12345CD678901E", NEW_DEVICE);
  assert.equal(pending.status, 200);
  assert.equal((await pending.json()).error, "payment_pending");
  assert.equal(env.EXPRESSIVE_PRO_KV.map.has("tx:3AB12345CD678901E"), false);
});

test("a PayPal token that stopped working is replaced once, and PAYPAL_API_BASE picks the API", async (t) => {
  const env = await testEnv({ ...PAYPAL_SECRETS, PAYPAL_API_BASE: "https://api-m.sandbox.paypal.com" });
  let tokens = 0;
  const hosts = [];
  const original = globalThis.fetch;
  globalThis.fetch = async (url, init = {}) => {
    const { host, pathname } = new URL(url);
    hosts.push(host);
    if (pathname === "/v1/oauth2/token") {
      tokens++;
      return new Response(JSON.stringify({ access_token: `token-${tokens}`, expires_in: 32400 }));
    }
    const auth = new Headers(init.headers).get("Authorization");
    if (auth === "Bearer token-1") return new Response("{}", { status: 401 });
    return new Response(JSON.stringify(capture("4AB12345CD678901E")));
  };
  t.after(() => { globalThis.fetch = original; });
  const res = await (await restore(env, "4AB12345CD678901E", NEW_DEVICE)).json();
  assert.equal(res.success, true);
  assert.equal(tokens, 2);
  assert.deepEqual([...new Set(hosts)], ["api-m.sandbox.paypal.com"]);
});

test("a PRO_PRICE that isn't a price refuses every payment", async (t) => {
  const env = await testEnv({ ...PAYPAL_SECRETS, PRO_PRICE: "4,99" });
  stubFetch(t, paypal());
  const res = await webhook(env, captureEvent("CAPBADPRICE00001", { custom_id: NEW_DEVICE }));
  assert.equal(res.status, 200);
  assert.equal((await lookup(env, NEW_DEVICE)).success, false);
});

test("the ledger refuses operations it doesn't know", async () => {
  const env = await testEnv();
  const ledger = env.LICENSE_LEDGER.get("license-moves");
  const res = await ledger.fetch("https://license-ledger/drop", { method: "POST", body: "{}" });
  assert.equal(res.status, 404);
});

test("an internal error answers with a generic body", async () => {
  const env = await testEnv(PAYPAL_SECRETS);
  delete env.PRO_PRIVATE_KEY_PEM;
  const res = await worker.fetch(post("/api/rebind", { key: "EXPR-PRO-AAAA", device_id: NEW_DEVICE }), env, {});
  assert.equal(res.status, 500);
  assert.deepEqual(await res.json(), { success: false, error: "Internal server error" });
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

const adminRegister = (env, body, token = "admin-secret") =>
  handleAdminRegister(post("/api/admin/register", body, token ? { Authorization: `Bearer ${token}` } : {}), env, NOW);

test("admin register requires valid ADMIN_TOKEN", async () => {
  const env = await testEnv({ ADMIN_TOKEN: "admin-secret" });
  const key = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_CAKEY, 0, env);
  assert.equal((await adminRegister(env, { key }, null)).status, 401);
  assert.equal((await adminRegister(env, { key }, "bad-token")).status, 401);
  const noTokenEnv = await testEnv();
  assert.equal((await adminRegister(noTokenEnv, { key }, "admin-secret")).status, 404);
});

test("admin registers offline-signed single key and makes it available via /api/license", async () => {
  const env = await testEnv({ ADMIN_TOKEN: "admin-secret" });
  const key = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_CAKEY, 0, env);
  const res = await adminRegister(env, { key, transaction_id: "OFFLINE-TEST-123" });
  assert.equal(res.status, 200);
  const data = await res.json();
  assert.equal(data.success, true);
  assert.equal(data.registered, 1);
  assert.equal(data.keys[0].device, OLD_DEVICE);
  assert.equal(data.keys[0].key, key);

  // Verify KV storage for device lookup
  const lookupRes = await worker.fetch(
    new Request(`https://worker.test/api/license?device_id=${OLD_DEVICE}`),
    env,
    {}
  );
  assert.equal(lookupRes.status, 200);
  const lookupData = await lookupRes.json();
  assert.equal(lookupData.success, true);
  assert.equal(lookupData.key, key);

  // Verify transaction record was saved
  assert.ok(env.EXPRESSIVE_PRO_KV.map.has("tx:OFFLINE-TEST-123"));
  assert.equal([...env.EXPRESSIVE_PRO_KV.map.keys()].filter((k) => k.startsWith("admin:registered:")).length, 1);
});

test("admin registers batch of offline keys", async () => {
  const env = await testEnv({ ADMIN_TOKEN: "admin-secret" });
  const key1 = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_CAKEY, 0, env);
  const key2 = await generateProLicense(`device:${NEW_DEVICE}`, TYPE_VIP, 0, env);
  const res = await adminRegister(env, {
    keys: [
      { key: key1 },
      { key: key2, transaction_id: "TX-BATCH-2" },
    ],
  });
  assert.equal(res.status, 200);
  const data = await res.json();
  assert.equal(data.registered, 2);
  assert.equal(data.keys[0].device, OLD_DEVICE);
  assert.equal(data.keys[1].device, NEW_DEVICE);

  assert.ok(env.EXPRESSIVE_PRO_KV.map.has(`license:device:${OLD_DEVICE}`));
  assert.ok(env.EXPRESSIVE_PRO_KV.map.has(`license:device:${NEW_DEVICE}`));
  assert.ok(env.EXPRESSIVE_PRO_KV.map.has("tx:TX-BATCH-2"));
});

test("admin register rejects forged, corrupted, or expired keys", async () => {
  const env = await testEnv({ ADMIN_TOKEN: "admin-secret" });
  const otherEnv = await testEnv();
  const forgedKey = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_CAKEY, 0, otherEnv);
  const resForged = await adminRegister(env, { key: forgedKey });
  assert.equal(resForged.status, 400);

  const corruptedKey = "EXPR-PRO-BADKEY-NOTVALID";
  const resCorrupted = await adminRegister(env, { key: corruptedKey });
  assert.equal(resCorrupted.status, 400);

  // Expired key
  const expiredKey = await generateProLicense(`device:${OLD_DEVICE}`, TYPE_CAKEY, Math.floor(NOW / 1000) - 3600, env);
  const resExpired = await adminRegister(env, { key: expiredKey });
  assert.equal(resExpired.status, 400);
});


const issued = (env, token = "admin-secret", cursor) =>
  handleAdminIssued(new Request(`https://worker.test/api/admin/issued${cursor ? `?cursor=${cursor}` : ""}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  }), env);

test("admin can list issued keys newest first, and only with the token", async () => {
  const env = await testEnv({ ADMIN_TOKEN: "admin-secret" });
  assert.equal((await issued(env, "wrong")).status, 401);
  assert.equal((await issued(await testEnv(), "admin-secret")).status, 404);

  await handleAdminIssue(post("/api/admin/issue", { type: "CAKEY", device: OLD_DEVICE }, { Authorization: "Bearer admin-secret" }), env, NOW);
  await handleAdminIssue(post("/api/admin/issue", { type: "GIVEAWAY", count: 2, prefix: "xda-" }, { Authorization: "Bearer admin-secret" }), env, NOW + 60_000);
  await handleAdminIssue(post("/api/admin/issue", { type: "VIP", account: "fan@example.com" }, { Authorization: "Bearer admin-secret" }), env, NOW + 120_000);

  const data = await (await issued(env)).json();
  assert.equal(data.success, true);
  assert.deepEqual(data.records.map((r) => r.type), ["VIP", "GIVEAWAY", "CAKEY"]);
  assert.equal(data.records[1].keys.length, 2);
  assert.equal(data.records[2].keys[0].recipient, `device:${OLD_DEVICE}`);
  assert.ok(data.records[2].keys[0].key.startsWith("EXPR-PRO-"));
  assert.equal(data.cursor, null);
});

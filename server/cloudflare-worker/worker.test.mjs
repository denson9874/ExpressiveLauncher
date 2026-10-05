// Unit tests for the Pro trial endpoint. Run: node --test server/cloudflare-worker/worker.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import worker, { handleTrialRequest, TYPE_TRIAL, TRIAL_DAYS } from "./worker.js";

const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const DEVICE = "DEV-AB12-CD34";
const FINGERPRINT = "a".repeat(64);

class MemoryKv {
  constructor() { this.map = new Map(); }
  async get(key) { return this.map.has(key) ? this.map.get(key) : null; }
  async put(key, value) { this.map.set(key, value); }
}

async function testEnv() {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const pkcs8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64");
  return { EXPRESSIVE_PRO_KV: new MemoryKv(), PRO_PRIVATE_KEY_PEM: `-----BEGIN PRIVATE KEY-----\n${pkcs8}\n-----END PRIVATE KEY-----` };
}

function trialRequest(body, ip = "203.0.113.7") {
  return new Request("https://worker.test/api/trial", {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": ip },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

/** Decodes the signed payload of an EXPR-PRO key: type, expiry and recipient. */
function decodeKey(key) {
  const chars = key.replace(/^EXPR-PRO-/, "").replace(/-/g, "");
  const bytes = [];
  let buf = 0, bits = 0;
  for (const c of chars) {
    buf = (buf << 5) | ALPHABET.indexOf(c);
    bits += 5;
    if (bits >= 8) { bits -= 8; bytes.push((buf >> bits) & 0xff); }
  }
  const view = new DataView(Uint8Array.from(bytes).buffer);
  const recipientLen = bytes[16];
  return {
    type: bytes[3],
    expiresAt: view.getUint32(8, false),
    features: view.getUint32(12, false),
    recipient: new TextDecoder().decode(Uint8Array.from(bytes.slice(17, 17 + recipientLen))),
  };
}

test("issues a 7-day, device-bound trial key once per fingerprint", async () => {
  const env = await testEnv();
  const now = Date.UTC(2026, 9, 5, 12);
  const res = await handleTrialRequest(trialRequest({ device_id: DEVICE, trial_fingerprint: FINGERPRINT }), env, now);
  assert.equal(res.status, 200);
  const data = await res.json();
  const expected = Math.floor(now / 1000) + TRIAL_DAYS * 86400;
  assert.equal(data.expiresAt, expected);
  assert.deepEqual(decodeKey(data.key), { type: TYPE_TRIAL, expiresAt: expected, features: 0xffffffff, recipient: `device:${DEVICE}` });
  assert.ok(env.EXPRESSIVE_PRO_KV.map.has(`trial:fp:${FINGERPRINT}`));
});

test("re-delivers with the same expiry to a new install id during the trial", async () => {
  const env = await testEnv();
  const start = Date.UTC(2026, 9, 5, 12);
  const first = await (await handleTrialRequest(trialRequest({ device_id: DEVICE, trial_fingerprint: FINGERPRINT }), env, start)).json();
  const later = start + 2 * 86400 * 1000;
  const res = await handleTrialRequest(trialRequest({ device_id: "DEV-ZZZZ-0000", trial_fingerprint: FINGERPRINT }), env, later);
  assert.equal(res.status, 200);
  const again = await res.json();
  assert.equal(again.expiresAt, first.expiresAt, "a reinstall must not extend the trial");
  assert.equal(decodeKey(again.key).recipient, "device:DEV-ZZZZ-0000");
});

test("refuses a second trial after the first has expired", async () => {
  const env = await testEnv();
  const start = Date.UTC(2026, 9, 5, 12);
  await handleTrialRequest(trialRequest({ device_id: DEVICE, trial_fingerprint: FINGERPRINT }), env, start);
  const res = await handleTrialRequest(trialRequest({ device_id: DEVICE, trial_fingerprint: FINGERPRINT }), env, start + 8 * 86400 * 1000);
  assert.equal(res.status, 409);
  assert.equal((await res.json()).error, "trial_used");
});

test("rejects malformed identifiers and bodies", async () => {
  const env = await testEnv();
  for (const body of [{ device_id: "nope", trial_fingerprint: FINGERPRINT }, { device_id: DEVICE, trial_fingerprint: "short" }, "{not json"]) {
    const res = await handleTrialRequest(trialRequest(body), env, Date.now());
    assert.equal(res.status, 400);
  }
  assert.equal([...env.EXPRESSIVE_PRO_KV.map.keys()].filter((k) => k.startsWith("trial:fp:")).length, 0);
});

test("rate limits repeated requests from one address", async () => {
  const env = await testEnv();
  const now = Date.UTC(2026, 9, 5, 12);
  const statuses = [];
  for (let i = 0; i < 6; i++) {
    const fp = i.toString(16).padStart(64, "0");
    statuses.push((await handleTrialRequest(trialRequest({ device_id: DEVICE, trial_fingerprint: fp }), env, now)).status);
  }
  assert.deepEqual(statuses, [200, 200, 200, 200, 200, 429]);
});

test("routes POST /api/trial and leaves other endpoints untouched", async () => {
  const env = await testEnv();
  const res = await worker.fetch(trialRequest({ device_id: DEVICE, trial_fingerprint: FINGERPRINT }), env, {});
  assert.equal(res.status, 200);
  const health = await worker.fetch(new Request("https://worker.test/health"), env, {});
  assert.equal((await health.json()).status, "ok");
});

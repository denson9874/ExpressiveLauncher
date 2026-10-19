# Expressive Pro — Serverless Activation Backend

This directory contains the 100% serverless backend for **Expressive Launcher Pro** automated PayPal payment activation.

## Overview
- **Runtime**: Cloudflare Workers (Free Tier, 100,000 requests/day, zero cold starts)
- **Database**: Cloudflare Workers KV (Key-Value storage for device licenses and transactions), plus a
  SQLite-backed Durable Object for license moves
- **Cryptography**: NIST P-256 (secp256r1) ECDSA digital signatures generated via the native Web Crypto API (`crypto.subtle`).
- **Dependencies**: 0 external npm dependencies.

## Architecture & Endpoints

| Endpoint | Method | Description |
|---|---|---|
| `/` or `/health` | `GET` | Service status and uptime check |
| `/api/license?device_id=DEV-...` | `GET` | Look up issued Pro license key for a device ID (email lookups return no key) |
| `/api/paypal-webhook` | `POST` | Receives PayPal webhook events, checks their signature with PayPal, signs key, saves in KV |
| `/api/verify-donation` | `POST` | Restores a purchase by PayPal transaction ID; re-signs it for a new device if needed |
| `/api/rebind` | `POST` | `{ key, device_id }`: re-signs a device-bound key for a device whose install ID changed |
| `/api/trial` | `POST` | Issues the one 7-day trial per device fingerprint |
| `/api/admin/issue` | `POST` | Owner-only (`Authorization: Bearer <ADMIN_TOKEN>`): signs TESTER / GIVEAWAY / VIP / DEV / CAKEY keys |
| `/activate?key=EXPR-PRO-...` | `GET` | Deep link gateway (redirects to `expressive://pro/activate?key=...`) |

### Moving a license to a reset device

Keys bound to a device ID stop working when the device gets a new install ID (factory reset,
reinstall, cleared data). When the app sees that mismatch it sends the key to `/api/rebind`, and a
restore by transaction ID does the same through `/api/verify-donation`. Either way the worker signs a
copy for the new device with the same type, expiry and features. A license (counted across all its
copies) may move to 3 new devices per 30 days; asking again for a device it already moved to returns
the same key. Moves are recorded by the `LicenseLedger` Durable Object (binding `LICENSE_LEDGER`,
declared in `wrangler.toml`), which handles one move at a time so parallel requests can't exceed the
limit; `wrangler deploy` creates it on first deploy.

### Issuing keys by hand

The worker holds the only copy of the master private key, so it signs every key. With `ADMIN_TOKEN`
set on the worker and saved locally (`~/.config/expressive/admin_token` or
`EXPRESSIVE_PRO_ADMIN_TOKEN`):

```bash
python3 scripts/issue_pro_key.py create --type CAKEY --device DEV-A1B2-C3D4
python3 scripts/issue_pro_key.py batch --count 10 --type GIVEAWAY --prefix xda- --output keys.txt
```

Every request is also recorded in KV under `admin:issued:<time>-<id>`.

---

## Deployment Guide (10 Minutes)

### 1. Prerequisites
- A free [Cloudflare](https://dash.cloudflare.com) account.
- Node.js installed (`npm install -g wrangler`).

### 2. Login to Cloudflare Wrangler
```bash
wrangler login
```

### 3. Create Cloudflare KV Namespace
Run the following command to create the KV storage namespace:
```bash
wrangler kv namespace create "EXPRESSIVE_PRO_KV"
```
Copy the returned `id` into `server/cloudflare-worker/wrangler.toml`:
```toml
kv_namespaces = [
  { binding = "EXPRESSIVE_PRO_KV", id = "<YOUR_KV_NAMESPACE_ID>" }
]
```

### 4. Set Secrets
The live worker already holds `PRO_PRIVATE_KEY_PEM`, and it is the only copy of the master key:
never delete or replace it. (`wrangler deploy` keeps existing secrets.) Only a brand-new worker
needs it uploaded:
```bash
wrangler secret put PRO_PRIVATE_KEY_PEM < ~/.config/expressive/pro_master_private.pem
```

PayPal webhooks are refused until their signatures can be checked, so set these before deploying
(the webhook ID is shown next to the webhook in the PayPal Developer Dashboard):
```bash
wrangler secret put PAYPAL_CLIENT_ID
wrangler secret put PAYPAL_CLIENT_SECRET
wrangler secret put PAYPAL_WEBHOOK_ID
```

For issuing keys by hand, set a long random admin token and keep a copy locally:
```bash
openssl rand -hex 32 > ~/.config/expressive/admin_token
wrangler secret put ADMIN_TOKEN < ~/.config/expressive/admin_token
```

### 5. Deploy the Worker
```bash
cd server/cloudflare-worker
wrangler deploy
```
Your worker will be live at:
`https://expressive-pro-activation.<your-subdomain>.workers.dev`

---

## PayPal Webhook Setup

1. Open your [PayPal Developer Dashboard](https://developer.paypal.com/dashboard/).
2. Under **Apps & Credentials**, select or create your Live application (or Sandbox for testing).
3. Under **Webhooks**, click **Add Webhook**.
4. Set the **Webhook URL**:
   `https://expressive-pro-activation.<your-subdomain>.workers.dev/api/paypal-webhook`
5. Select the following event types:
   - `Payment capture completed`
   - `Checkout order approved`
   - `Payment sale completed`
6. Save the webhook.

---

## How it Works with Expressive Launcher

1. When a user taps **"Support with $4.99"** in the launcher preferences, the in-app PayPal link opens with their anonymous installation device ID:
   `https://www.paypal.com/ncp/payment/9RB3TYYQ6FWE2?custom=DEV-A1B2-C3D4&invoice_id=DEV-A1B2-C3D4`
2. Once the payment completes, PayPal sends a webhook payload containing `custom_id: "DEV-A1B2-C3D4"`.
3. The Cloudflare Worker signs an offline ECDSA P-256 license for `device:DEV-A1B2-C3D4` and stores it in KV.
4. When the user returns to Expressive Launcher and taps **"Check Payment Status"**, the launcher queries `/api/license?device_id=DEV-A1B2-C3D4`, receives the key, and instantly unlocks Expressive Pro!

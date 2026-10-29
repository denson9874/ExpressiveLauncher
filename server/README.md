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
| `/api/checkout?device_id=DEV-...` | `GET` | The app's Donate button: creates a PayPal order for Pro carrying the device ID and redirects to PayPal (falls back to the no-code link) |
| `/api/checkout/return?token=<order>` | `GET` | PayPal's return URL: captures the order, signs the device's key and shows a button that activates it in the app |
| `/api/checkout/cancel` | `GET` | PayPal's cancel URL: a "nothing was charged" page |
| `/api/verify-donation` | `POST` | Restores a purchase by PayPal transaction ID; re-signs it for a new device if needed |
| `/api/rebind` | `POST` | `{ key, device_id }`: re-signs a device-bound key for a device whose install ID changed |
| `/api/trial` | `POST` | Issues the one 7-day trial per device fingerprint |
| `/api/admin/issue` | `POST` | Owner-only (`Authorization: Bearer <ADMIN_TOKEN>`): signs TESTER / GIVEAWAY / VIP / DEV / CAKEY keys |
| `/api/admin/register` | `POST` | Owner-only (`Authorization: Bearer <ADMIN_TOKEN>`): registers offline-generated ECDSA keys to KV |
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

### Offline key generation and worker registration

To generate signed keys completely offline and optionally register them to the Cloudflare Worker:

```bash
# Generate offline and immediately register to Cloudflare KV:
python3 scripts/generate_pro_key.py create --type CAKEY --device DEV-A1B2-C3D4 --register

# Batch generate offline and register all keys:
python3 scripts/generate_pro_key.py batch --count 10 --type GIVEAWAY --prefix xda- --register

# Register an already-generated key to Cloudflare Worker:
python3 scripts/generate_pro_key.py register --key EXPR-PRO-XXXX-... [--device DEV-A1B2-C3D4]
```

### Issuing keys directly through worker

With `ADMIN_TOKEN` set on the worker and saved locally (`~/.config/expressive/admin_token` or `EXPRESSIVE_PRO_ADMIN_TOKEN`):

```bash
python3 scripts/issue_pro_key.py create --type CAKEY --device DEV-A1B2-C3D4
python3 scripts/issue_pro_key.py batch --count 10 --type GIVEAWAY --prefix xda- --output keys.txt
```

Every request is also recorded in KV under `admin:issued:<time>-<id>` or `admin:registered:<time>-<id>`.

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
   - `Payment capture denied`
   - `Payment capture refunded`
   - `Payment capture reversed`
6. Save the webhook, then store its ID with `wrangler secret put PAYPAL_WEBHOOK_ID`.

---

## How it Works with Expressive Launcher

1. When a user taps **"Donate $4.99 via PayPal"** in the Pro sheet, the launcher opens the worker's checkout with their anonymous installation device ID:
   `https://expressive-pro-activation.<your-subdomain>.workers.dev/api/checkout?device_id=DEV-A1B2-C3D4`
2. The worker creates a PayPal order for Pro carrying `custom_id: "DEV-A1B2-C3D4"` and redirects the buyer to PayPal.
3. After paying, PayPal sends the buyer back to `/api/checkout/return`. The worker captures the order, signs an offline ECDSA P-256 license for `device:DEV-A1B2-C3D4`, stores it, and shows an **Open Expressive Launcher** button that activates it. The `PAYMENT.CAPTURE.COMPLETED` webhook for the same capture finds it already issued.
4. Coming back to the launcher checks `/api/license?device_id=DEV-A1B2-C3D4` by itself (as does **"Check Activation Status"**), so Pro unlocks even if the buyer never taps the button.

If the worker can't create an order (PayPal settings missing, PayPal unavailable, too many attempts), `/api/checkout` falls back to the no-code link `https://www.paypal.com/ncp/payment/9RB3TYYQ6FWE2`, which launcher 5.0.x opens directly. PayPal sends no webhooks for that link and drops the device ID, so those purchases unlock with **"Restore with Transaction ID"** (`POST /api/verify-donation`), which checks the Transaction ID from the PayPal receipt with PayPal.

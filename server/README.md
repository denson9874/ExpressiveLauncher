# Expressive Pro — Serverless Activation Backend

This directory contains the 100% serverless backend for **Expressive Launcher Pro** automated PayPal payment activation.

## Overview
- **Runtime**: Cloudflare Workers (Free Tier, 100,000 requests/day, zero cold starts)
- **Database**: Cloudflare Workers KV (Key-Value storage for device licenses and transactions)
- **Cryptography**: NIST P-256 (secp256r1) ECDSA digital signatures generated via the native Web Crypto API (`crypto.subtle`).
- **Dependencies**: 0 external npm dependencies.

## Architecture & Endpoints

| Endpoint | Method | Description |
|---|---|---|
| `/` or `/health` | `GET` | Service status and uptime check |
| `/api/license?device_id=DEV-...` | `GET` | Look up issued Pro license key for a device ID |
| `/api/paypal-webhook` | `POST` | Receives PayPal webhook events, verifies donation, signs key, saves in KV |
| `/api/verify-donation` | `POST` | Verifies PayPal transaction ID directly and issues device key |
| `/activate?key=EXPR-PRO-...` | `GET` | Deep link gateway (redirects to `expressive://pro/activate?key=...`) |

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

### 4. Set Master Private Key Secret
Upload your master private key to the worker environment:
```bash
wrangler secret put PRO_PRIVATE_KEY_PEM < ~/.config/expressive/pro_master_private.pem
```

*(Optional: If you want direct PayPal API transaction verification)*:
```bash
wrangler secret put PAYPAL_CLIENT_ID
wrangler secret put PAYPAL_CLIENT_SECRET
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

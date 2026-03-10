# Cloudflare Worker Setup - Complete Guide

## What Was Done

✅ Created Cloudflare Worker proxy for secure API key handling
✅ Modified Android app to support both default (proxy) and custom API keys
✅ Updated UI to show API key is optional
✅ Added balance check skip for default API users

## How It Works

**Without API Key (Default):**
- App sends requests to YOUR Cloudflare Worker
- Worker adds your secret API key
- Worker forwards to Pollinations API
- User never sees your key

**With Custom API Key:**
- App sends requests directly to Pollinations API
- Uses user's own API key
- Shows their balance

## Setup Instructions

### 1. Install Wrangler (Cloudflare CLI)
```bash
npm install -g wrangler
```

### 2. Login to Cloudflare
```bash
wrangler login
```
This opens browser - login with your Cloudflare account (create one if needed at https://dash.cloudflare.com/sign-up)

### 3. Get Your Pollinations API Key
Go to https://enter.pollinations.ai and get your API key

### 4. Deploy Worker
```bash
cd /root/aivision/worker
wrangler secret put POLLINATIONS_API_KEY
# Paste your Pollinations API key when prompted

wrangler deploy
```

### 5. Update Android App
After deployment, you'll see:
```
Published aivision-proxy (X.XX sec)
  https://aivision-proxy.YOUR-SUBDOMAIN.workers.dev
```

Copy that URL and update `MainActivity.kt`:
```kotlin
private const val PROXY_URL = "https://aivision-proxy.YOUR-SUBDOMAIN.workers.dev"
```

### 6. Build and Deploy App
```bash
cd /root/aivision
./gradlew assembleRelease
```

## Testing Your Worker

```bash
curl -X POST https://aivision-proxy.YOUR-SUBDOMAIN.workers.dev \
  -H "Content-Type: application/json" \
  -d '{
    "model": "openai",
    "messages": [{
      "role": "user",
      "content": [{
        "type": "text",
        "text": "Hello, test message"
      }]
    }]
  }'
```

## Security Features

✅ API key stored as Cloudflare secret (encrypted)
✅ Never exposed in APK or network traffic
✅ CORS enabled only for POST requests
✅ Can add rate limiting by IP
✅ Can rotate keys without app update

## Free Tier Limits

- **100,000 requests per day** (3 million/month)
- 10ms CPU time per request
- No credit card required
- No cold starts

## Monitoring

View usage at: https://dash.cloudflare.com/workers

## Optional: Add Rate Limiting

Edit `worker/worker.js` to add rate limiting:
```javascript
// Add at top of fetch function
const ip = request.headers.get('CF-Connecting-IP');
// Implement rate limiting logic here
```

## Files Created

- `worker/worker.js` - Cloudflare Worker code
- `worker/wrangler.toml` - Worker configuration
- `worker/README.md` - Detailed setup instructions

## Files Modified

- `app/src/main/java/com/aivision/app/MainActivity.kt` - Added proxy support
- `app/src/main/res/values/strings.xml` - Made API key optional
- `app/src/main/res/layout/dialog_api_key.xml` - Added helper text
- `README.md` - Updated with default API info

# Cloudflare Worker Setup Instructions

## Prerequisites
1. Create a Cloudflare account at https://dash.cloudflare.com/sign-up
2. Get your Pollinations API key from https://enter.pollinations.ai

## Setup Steps

### 1. Install Wrangler CLI
```bash
npm install -g wrangler
```

### 2. Login to Cloudflare
```bash
wrangler login
```

### 3. Set Your API Key as Secret
```bash
cd worker
wrangler secret put POLLINATIONS_API_KEY
# Paste your Pollinations API key when prompted
```

### 4. Deploy Worker
```bash
wrangler deploy
```

### 5. Get Your Worker URL
After deployment, you'll see output like:
```
Published aivision-proxy (X.XX sec)
  https://aivision-proxy.YOUR-SUBDOMAIN.workers.dev
```

Copy this URL - you'll need it for the Android app.

## Update Android App

Replace `YOUR_WORKER_URL` in MainActivity.kt with your actual worker URL:
```kotlin
private const val PROXY_URL = "https://aivision-proxy.YOUR-SUBDOMAIN.workers.dev"
```

## Testing

Test your worker:
```bash
curl -X POST https://aivision-proxy.YOUR-SUBDOMAIN.workers.dev \
  -H "Content-Type: application/json" \
  -d '{"model":"openai","messages":[{"role":"user","content":[{"type":"text","text":"Hello"}]}]}'
```

## Free Tier Limits
- 100,000 requests per day
- 10ms CPU time per request
- No credit card required

## Monitoring
View usage at: https://dash.cloudflare.com/workers

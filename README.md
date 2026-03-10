# AI Vision

Modern Android app for AI-powered image analysis using Pollinations API.

## Features
- Clean, modern Material Design 3 UI
- Image selection from gallery (single or multiple)
- AI-powered image description using OpenAI model
- Default API included - works out of the box
- Optional: Use your own API key for unlimited usage
- Secure API key storage
- Smooth animations and transitions

## Setup

### Option 1: Use Default API (Recommended for most users)
1. Download and install the app
2. Select an image and tap Analyze
3. That's it! No API key needed

### Option 2: Use Your Own API Key
1. Get your API key from https://enter.pollinations.ai
2. Open the app and tap the settings icon
3. Enter your API key
4. Select an image and tap Analyze

## For Developers

### Deploy Your Own Proxy (Optional)
See `worker/README.md` for instructions on deploying your own Cloudflare Worker with your API key.

After deployment, update `PROXY_URL` in `MainActivity.kt` with your worker URL.

### Build
```bash
./gradlew assembleRelease
```

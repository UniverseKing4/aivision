# AI Vision

Modern Android app for AI-powered image analysis using Pollinations API.

## Features

### Core Functionality
- 🖼️ **Single & Multiple Image Analysis** - Analyze one or multiple images at once
- 🤖 **Multiple AI Models** - Choose from OpenAI, Gemini Flash, Claude Fast, Kimi, or Polly
- ✍️ **Custom Prompts** - Add your own prompts for tailored analysis
- 🎨 **Material Design 3** - Clean, modern UI with light/dark theme support
- 📋 **Copy Results** - One-tap copy to clipboard
- ⏱️ **Real-time Timer** - See analysis duration
- 🛑 **Stop Analysis** - Cancel ongoing analysis anytime

### API Options
- 🆓 **Default API** - Works out of the box, no setup required
  - Rate limited: 100 requests/hour, 1000 requests/day per user
  - Powered by secure Cloudflare Worker proxy
- 🔑 **Custom API Key** - Unlimited usage with your own Pollinations API key
  - Shows account balance after analysis
  - No rate limits

### User Experience
- 🌓 **Dark/Light Mode** - Toggle between themes
- 💾 **State Preservation** - Maintains state across app restarts
- 📱 **Portrait Optimized** - Designed for mobile use
- ⚡ **60s Timeout** - Handles long-running analyses
- 🔒 **Secure Storage** - API keys stored securely

## Setup

### Option 1: Use Default API (Recommended)
1. Download and install the app
2. Select one or more images from gallery
3. (Optional) Add a custom prompt
4. Tap **Analyze**
5. Done! No API key needed

**Limits:** 100 requests/hour, 1000 requests/day

### Option 2: Use Your Own API Key (Unlimited)
1. Get your API key from [Pollinations AI](https://enter.pollinations.ai)
2. Open the app and tap the **settings icon** (⚙️)
3. Tap **"Get your own API key →"** or enter your key directly
4. Tap **Save**
5. Select images and analyze with unlimited usage

## Usage

### Analyzing Images
1. Tap **"Select Image"** to choose from gallery
2. Select single or multiple images
3. (Optional) Enter a custom prompt like "Describe in detail" or "What objects are visible?"
4. Tap **"Analyze"** to start
5. Tap **"Stop"** to cancel if needed
6. View results with markdown formatting
7. Tap **copy icon** to copy results to clipboard

### Switching Models
1. Tap the **model icon** in the toolbar
2. Choose from: OpenAI, Gemini Flash, Claude Fast, Kimi, or Polly
3. Model applies to next analysis

### Changing Theme
1. Tap the **theme icon** in the toolbar
2. Switches between light and dark mode
3. Preference is saved

## For Developers

### Architecture
- **Language:** Kotlin
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34 (Android 14)
- **Key Libraries:**
  - Material Design 3
  - OkHttp (networking)
  - Coroutines (async operations)
  - Markwon (markdown rendering)
  - ViewBinding

### Project Structure
```
app/src/main/
├── java/com/aivision/app/
│   ├── MainActivity.kt          # Main activity with all logic
│   ├── AIVisionApp.kt          # Application class for theme
│   └── ImagePagerAdapter.kt    # ViewPager adapter for multiple images
├── res/
│   ├── layout/                 # XML layouts
│   ├── drawable/               # Icons and graphics
│   ├── values/                 # Strings, colors, themes
│   └── menu/                   # Toolbar menu
└── AndroidManifest.xml

worker/
├── worker.js                   # Cloudflare Worker proxy
├── wrangler.toml              # Worker configuration
└── README.md                  # Worker setup guide
```

### Build Instructions
```bash
# Debug build
./gradlew assembleDebug

# Release build (signed)
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
```

### Deploy Your Own Proxy
The app uses a Cloudflare Worker to proxy API requests securely. To deploy your own:

1. **Install Wrangler CLI:**
   ```bash
   npm install -g wrangler
   ```

2. **Login to Cloudflare:**
   ```bash
   wrangler login
   ```

3. **Deploy Worker:**
   ```bash
   cd worker
   echo "YOUR_API_KEY" | wrangler secret put POLLINATIONS_API_KEY
   wrangler deploy
   ```

4. **Update App:**
   - Copy the worker URL from deployment output
   - Update `PROXY_URL` in `MainActivity.kt`
   - Rebuild the app

See `worker/README.md` for detailed instructions.

### CI/CD
GitHub Actions automatically:
- Builds release APK on push to main
- Auto-increments version tags
- Creates GitHub releases
- Uploads APK artifacts

## Security

- ✅ API keys stored as encrypted Cloudflare secrets
- ✅ Keys never exposed in APK or network traffic
- ✅ Rate limiting prevents abuse of default API
- ✅ HTTPS for all API communications
- ✅ Secure SharedPreferences for user API keys

## Rate Limits (Default API)

| Limit Type | Value |
|------------|-------|
| Per Hour   | 100 requests |
| Per Day    | 1000 requests |
| Scope      | Per IP address |

**Note:** Custom API keys have no rate limits.

## Permissions

- `INTERNET` - Required for API calls
- `READ_MEDIA_IMAGES` - Android 13+ gallery access
- `READ_EXTERNAL_STORAGE` - Android 12 and below gallery access

## License

This project is open source. Feel free to use, modify, and distribute.

## Credits

- **AI Provider:** [Pollinations AI](https://pollinations.ai)
- **Proxy Infrastructure:** Cloudflare Workers
- **UI Framework:** Material Design 3

## Support

For issues, feature requests, or questions:
- Open an issue on GitHub
- Check `CLOUDFLARE_SETUP.md` for proxy setup help
- Review `worker/README.md` for worker configuration

# Voice Chat Capacitor App — Build Guide

A complete guide for building a hands-free voice conversation Android app. Connects to any OpenAI-compatible API (DeepSeek, Qwen, GLM, OpenRouter, etc.). Runs with the screen off. Local chat log export. No cloud dependencies.

Architecture: vanilla HTML/JS app wrapped in Capacitor for native Android deployment.

---

## What you're building

An Android APK you install on your Pixel 8. Tap "Start Call," then:

1. Phone listens through the mic continuously, screen on or off
2. When you stop talking for ~2 seconds, your words go to your AI API
3. As the API streams back its reply, the phone speaks each sentence aloud
4. If you start talking while it's speaking, TTS stops and goes back to listening
5. A persistent notification keeps the foreground service alive so the OS doesn't kill the mic when you lock the screen
6. Loop until you tap "End Call"

Plus: settings panel for API key/endpoint/model/system prompt, character switcher, "Download Logs" button that drops a `.txt` of the conversation onto your device.

---

## Critical plugin choices (read before you start)

We are using:

- **`@capgo/capacitor-speech-recognition`** — actively maintained fork of the community plugin with continuous push-to-talk handling and configurable silence windows. The plain `@capacitor-community/speech-recognition` plugin works but has known stop/restart issues on Android 13+. The Capgo fork solves them.
- **`@capawesome-team/capacitor-android-foreground-service`** — the only well-maintained foreground service plugin for Capacitor. Note: this plugin is "sponsorware" — free version available but full features behind GitHub Sponsorship. For our use case the free version is enough.
- **`@capacitor-community/text-to-speech`** — for speaking responses aloud.
- **`@capacitor/filesystem`** + **`@capacitor/share`** — for the chat log export.
- **`@capacitor/preferences`** — replaces localStorage; survives app uninstalls if you back up.

If any of these break, alternatives are listed in the Troubleshooting section.

---

## How to use this guide

1. Set up the build environment ONCE (Node, JDK, Android Studio). Skip if already done.
2. Run the terminal commands in **Step 1** to scaffold the Capacitor project.
3. Open a fresh AI chat. Paste **the entire prompt block** in **Step 2**.
4. Save the AI's output as `www/index.html` in your project.
5. Paste the AndroidManifest XML from **Step 3** into the right file.
6. Run the build commands in **Step 4**.
7. Install the APK on your Pixel 8.

If something breaks, see the **Iteration & Anti-Laziness** section.

---

## Step 0: Build environment setup (do once)

Skip whatever you already have.

**Node.js (small, fast):** Install LTS from nodejs.org. Verify: `node -v` should print v20+.

**Java JDK 17:** Capacitor needs JDK 17 specifically. Newer versions break Gradle. Install from adoptium.net (Eclipse Temurin). Verify: `java -version` should print 17.x.

**Android Studio:** Download from developer.android.com/studio. Install with default settings. After install, open it, accept all SDK license agreements, let it download Android SDK Platform 34 and Build Tools. This is the ~10GB download.

**Environment variable** (Windows): set `ANDROID_HOME` to `C:\Users\YourName\AppData\Local\Android\Sdk`. On Mac/Linux: `export ANDROID_HOME=$HOME/Library/Android/sdk` (Mac) or `export ANDROID_HOME=$HOME/Android/Sdk` (Linux). Add to your shell profile.

---

## Step 1: Scaffold the Capacitor project

Open terminal in a folder where you want the project. Run these commands one at a time and check for errors after each:

```bash
npm init -y
npm install @capacitor/core @capacitor/cli @capacitor/android
npx cap init "VoiceChat" "com.yourname.voicechat" --web-dir=www
mkdir www
```

Then install the plugins:

```bash
npm install @capgo/capacitor-speech-recognition
npm install @capawesome-team/capacitor-android-foreground-service
npm install @capacitor-community/text-to-speech
npm install @capacitor/filesystem
npm install @capacitor/share
npm install @capacitor/preferences
```

Then add the Android platform:

```bash
npx cap add android
```

You should now have a folder structure with `android/`, `www/`, `node_modules/`, and config files. The `www/` folder is empty — that's where your `index.html` goes in Step 2.

---

## Step 2: THE PROMPT

Open a fresh AI chat (Claude works best for this) and paste everything inside the block below — every word, the whole thing.

```
I'm building a hands-free voice conversation Android app using Capacitor. The single web app file will be wrapped in a native Android shell with foreground service support so the mic stays open when the screen locks. I am NOT a coder — I'm using you to build this. Follow these rules strictly. Do not deviate.

## Architecture context

The app is a single HTML file at www/index.html inside a Capacitor project. The native Android shell is already set up. These plugins are already installed and ready to import:

- @capgo/capacitor-speech-recognition (NOT the @capacitor-community version — use the Capgo fork)
- @capawesome-team/capacitor-android-foreground-service
- @capacitor-community/text-to-speech
- @capacitor/filesystem
- @capacitor/share
- @capacitor/preferences

Use these via standard ES module imports from the global Capacitor object — the user is loading them via @capacitor/core.

## What the app does

1. User taps "Start Call." App requests RECORD_AUDIO permission, starts a foreground service via the foreground-service plugin (with a persistent notification "VoiceChat Active"), then begins listening via the Capgo speech-recognition plugin in continuous mode.
2. Speech recognition emits partial results as the user speaks. App displays the partial transcript in real time.
3. When the user pauses for the configured silence threshold (default 2 seconds — measured in our JS, not relying solely on the native recognizer's own cutoff), the accumulated final transcript is sent as the user's turn.
4. App calls the configured AI API (OpenAI-compatible chat completions endpoint) with stream:true. As tokens arrive, append to the current assistant turn buffer.
5. Whenever a complete sentence appears in the buffer (ends in . ! ? \n), pass it to the TTS plugin to speak. Continue streaming.
6. While TTS is speaking, keep speech recognition running. If the recognizer fires partial results with non-whitespace content, immediately call TTS stop, treat the input as a new user turn (interruption).
7. After the assistant turn completes (or is interrupted), automatically resume listening. No buttons.
8. User taps "End Call" to stop everything: stop recognition, stop TTS, stop foreground service, return to idle.

This is a phone-call experience. NO press-to-talk. NO buttons between turns.

## Hard rules — do not violate

- ONE single HTML file at www/index.html. No build step, no bundler, no npm in the page itself.
- Vanilla JavaScript only. No React, Vue, jQuery, Tailwind, Bootstrap, or any other framework or styling library.
- Use the Capacitor plugins listed above for: speech recognition, TTS, foreground service, file save, share, preferences.
- Use fetch() with streaming (response.body.getReader() and TextDecoder) for the API. Parse the SSE stream line by line, accumulate JSON deltas.
- Settings must be saved to @capacitor/preferences (NOT localStorage). On app open, load them.
- Defaults:
    - API endpoint: https://api.deepseek.com/chat/completions
    - Model: deepseek-chat
    - System prompt: "You are a helpful conversational assistant. Keep replies natural and concise for spoken conversation. Avoid markdown formatting since replies will be read aloud."
    - Silence threshold: 2000 ms
    - Conversation history cap: 20 messages
- All defaults editable through a Settings panel (gear icon).
- Multiple system prompts: Settings includes a "Personas" section where the user can save named system prompts (e.g., "Default", "Grounding", "Roleplay"). On the main screen, a small dropdown above the call button lets the user pick the active persona before starting a call.

## Mic and lifecycle

- On Start Call: request microphone permission via the speech-recognition plugin's requestPermissions(). If denied, show a clear error.
- Then start the foreground service with type "microphone" and a persistent notification.
- Then start speech recognition.
- On End Call: stop recognition, stop TTS (speechSynthesis equivalent in the TTS plugin), stop foreground service.
- On app background (use Capacitor's App plugin appStateChange listener if helpful): keep everything running. The foreground service should keep the mic alive.

## Continuous recognition strategy

Native Android speech recognition stops itself periodically. Your code must handle this:
- Listen for the listeningState event from the speech-recognition plugin.
- When state becomes 'stopped' AND we're still in an active call, immediately call start() again.
- Track our own silence timer in JS — when partialResults stops emitting new content for `silenceThresholdMs`, treat as end-of-utterance and submit.
- If the user is interrupting TTS, do NOT submit the partial as a turn yet. The interruption logic stops TTS first; the submission logic still uses the same silence threshold.

## UI requirements

- Mobile-first, portrait
- Dark mode by default (off-black background, light text)
- Big "Start Call" / "End Call" button — at least 80px tall, centered, color changes per state
- Status indicator: "Idle" / "Listening..." / "Thinking..." / "Speaking..."
- Persona dropdown above the call button
- Scrolling transcript below: user turns one color, assistant turns another
- Settings gear icon top-right opens a panel with: API endpoint, API key, model name, silence threshold (slider), persona list (add/edit/delete), default persona
- "Download Logs" button in settings: serializes the current conversation as a readable .txt and saves it to the device's Downloads folder via @capacitor/filesystem (Directory.Documents) AND offers @capacitor/share so the user can send it elsewhere
- All tap targets at least 60px tall
- System fonts only

## API call

- Build messages array: [system prompt for active persona] + last 20 turns + current user turn
- POST to configured endpoint with: { model, messages, stream: true }
- Authorization: Bearer <api key>
- Stream the response body, accumulate, chunk into sentences for TTS

## Edge cases — handle ALL

- Mic permission denied: clear error with "open Android settings → apps → VoiceChat → permissions"
- API call fails: show actual error message and HTTP status from the response
- API returns empty: show clearly so user knows it isn't a mic issue
- Speech recognition error events: log them, attempt restart unless it's a permanent error
- TTS interrupted mid-sentence: cancel current utterance cleanly
- User taps End Call mid-speech: stop everything, no half-state
- App relaunch: settings and persona list persist via @capacitor/preferences
- Foreground service plugin returns an error: show it, don't silently fail

## Forbidden

- No login, accounts, social features
- No theme switcher, avatar uploads, conversation export to clouds
- No external fonts, stylesheets, or libraries loaded from CDNs
- No canvas animations or fancy graphics
- No "// TODO" or "// rest of code" comments
- No abbreviations like "..." or "(unchanged)" — give the COMPLETE file every response

## Code organization

One file: www/index.html. Inside, in this order:
1. <head> with title and meta viewport with viewport-fit=cover
2. <style> block with all CSS (mobile-first, dark theme)
3. <body> with UI elements
4. <script type="module"> block with all JS

JS section headers (use comment headers so it's editable later):
- Imports / plugin references
- Preferences / settings management
- Persona management
- Speech recognition setup + continuous loop
- TTS / sentence chunking
- Foreground service lifecycle
- API call (streaming)
- Conversation state
- UI event handlers
- Log export

## Output

Give me the COMPLETE www/index.html in a single code block. Every line. No abbreviations. If I ask for a change, give me the entire file again.

Begin.
```

---

## Step 3: AndroidManifest.xml configuration

After Step 2 produces your `www/index.html`, you need to edit the Android manifest to declare permissions and the foreground service type. This is what makes screen-off mic actually work.

Open `android/app/src/main/AndroidManifest.xml`. Inside the `<manifest>` tag (NOT inside `<application>`), add these permission lines if they aren't already there:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<queries>
    <package android:name="com.google.android.googlequicksearchbox" />
    <intent>
        <action android:name="android.speech.RecognitionService" />
    </intent>
</queries>
```

The `<queries>` block is critical on Android 11+ — without it, the speech recognition plugin can't find the system's recognition service and silently fails.

Then inside the `<application>` tag, find the `<service>` declaration that the foreground-service plugin adds (it'll be there after `npx cap sync`). It needs the `microphone` type. Modify it to look exactly like this:

```xml
<service
    android:name="io.capawesome.capacitorjs.plugins.foregroundservice.AndroidForegroundService"
    android:foregroundServiceType="microphone"
    android:exported="false" />
```

If the service line is auto-generated without these attributes, override it by adding `tools:replace="android:foregroundServiceType"` and add `xmlns:tools="http://schemas.android.com/tools"` to the manifest tag.

In `android/app/build.gradle`, verify these values:

```gradle
android {
    compileSdkVersion 34
    defaultConfig {
        minSdkVersion 24
        targetSdkVersion 34
    }
}
```

Lower `targetSdkVersion` than 34 means Android won't enforce the new foreground service rules but Play Store would reject the app — moot for sideload, but use 34 anyway for forward compat.

---

## Step 4: Build and install

After `www/index.html` is in place and the manifest is patched:

```bash
npx cap sync android
```

This copies your web assets into the Android project and updates plugin links. You run this every time you change `index.html` or install/uninstall a plugin.

Then either:

**Option A — open in Android Studio (easier first time):**
```bash
npx cap open android
```

In Android Studio, plug your Pixel 8 into USB, enable USB debugging on the phone (Developer Options), select your device in the run target dropdown, click the green Play button. It builds, installs, and launches the app.

**Option B — pure command line:**
```bash
cd android
./gradlew assembleDebug
```

The APK ends up at `android/app/build/outputs/apk/debug/app-debug.apk`. Transfer to your phone, tap to install (you'll need to allow "install from unknown sources" for your file manager).

---

## Iteration & Anti-Laziness — slap down lazy AI

AIs cut corners. Common failures and exact replies:

**"Here's the relevant change..."**
→ Reply: **"Give me the COMPLETE updated www/index.html. The whole thing. Every line. No abbreviations."**

**"// rest of code unchanged"**
→ Reply: **"Don't abbreviate. Paste the entire file."**

**Adds features you didn't ask for**
→ Reply: **"Remove [thing]. I didn't ask for it. Re-paste the complete file."**

**Uses a framework anyway**
→ Reply: **"You used [framework]. The prompt said vanilla JS. Rewrite, complete file."**

**Hallucinates plugin APIs**
→ Reply: **"The actual API for that plugin is on [github URL]. Use the real API. Complete file."**

**Code throws errors and AI insists it works**
→ Plug your phone into your computer, open Chrome dev tools (chrome://inspect), navigate to your app, copy the literal error from the console. Paste it verbatim. Errors aren't negotiable.

### Save working versions

Every time something works, copy `www/index.html` to `www/index-v1.html`, `v2.html`, etc. before asking for changes. When AI breaks something, you roll back instead of fighting through "no but undo what you did" loops.

### One change at a time

Don't bundle "add X, fix Y, change Z" into one request. Sequential changes mean you know which change broke what.

---

## Troubleshooting

### Mic doesn't work / no transcripts appear
- Check that Google App is installed and updated on your Pixel 8 — Android speech recognition routes through it
- Settings → Apps → VoiceChat → Permissions → Microphone enabled
- Check Chrome dev tools console for plugin errors via `chrome://inspect`
- Try Settings → Default apps → Digital assistant app → set to Google

### Mic works in foreground, dies when screen locks
- Foreground service isn't starting properly. Check Logcat for the service start log
- Verify the manifest has `FOREGROUND_SERVICE_MICROPHONE` AND the service has `foregroundServiceType="microphone"` — both required separately
- Verify a notification appears when you tap Start Call. If no notification, the foreground service didn't start
- Some Android battery optimization aggressively kills services. Settings → Battery → unrestricted for VoiceChat

### Recognizer keeps cutting off mid-thought
- Bump silence threshold in app Settings to 3000 or 4000 ms
- The native recognizer has its own silence cutoff separate from yours. The Capgo fork's continuous mode handles this by restarting; if you still see gaps, it's the restart latency. Workaround: switch to the AudioRecorder-based approach with browser Whisper (V2 territory)

### "Network error" from speech recognition
- Native Android recognizer needs internet (Google processes it server-side). Without service, listening breaks.
- V2 fix: replace recognizer with on-device Whisper via the audio-recorder plugin + transformers.js running Whisper-base in the WebView. Pixel 8's 8GB RAM handles it.

### TTS sounds robotic
- Settings → System → Languages & input → Text-to-speech → make sure Google's engine is installed and selected
- Set speech rate and pitch to taste in the app settings
- V2: swap to a better TTS API (ElevenLabs, your local Chatterbox via a small server, etc.)

### Build fails: "SDK location not found"
- `ANDROID_HOME` env var not set or not exported. Set it, restart terminal.

### Build fails: "JDK version mismatch"
- You have JDK 21 or newer. Capacitor wants JDK 17. Install JDK 17, set `JAVA_HOME` to point to it.

### Plugin install fails on `npx cap sync`
- Check you're in the project root (where `package.json` is)
- Delete `node_modules/`, run `npm install`, then `npx cap sync` again
- If a specific plugin errors, check its GitHub for Capacitor version compatibility — some plugins lag a major version behind

---

## V2 features for later (not now)

Once V1 is running and you've used it:

- **In-browser Whisper** for offline + better quality (replaces native recognizer)
- **Better TTS** via ElevenLabs API or your local Chatterbox server
- **RAG / long-term memory** so it remembers across sessions
- **SillyTavern character card import** for personality JSON
- **Cloud sync of logs** if you decide localstorage isn't enough — Dropbox is easier than Google Drive if you go this route

V1 first. Use it for a week. Then decide what's worth adding.

---

## Closing note

Capacitor is a real environment with real failure modes. The build chain has more places to break than a single HTML file would. You'll hit errors. That's normal — even experienced developers hit them. The path forward is always: read the actual error, paste it verbatim into the AI, get a real fix.

You hacked a 16k-line WordPress plugin into doing what you wanted. This is smaller than that, just with more cliff edges. Save versions obsessively, never accept "rest unchanged," paste real errors not paraphrases, and you'll have an APK on your phone.

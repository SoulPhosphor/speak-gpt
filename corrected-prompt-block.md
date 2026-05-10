# Voice Chat Capacitor App — Final Prompt Block

Paste everything inside the code block below into a fresh AI chat. The AI will build the app in milestones, not all at once.

---

```
I'm building a hands-free voice conversation Android app using Capacitor. The app will be wrapped in a native Android shell with foreground service support so the mic stays open when the screen locks. I am NOT a coder — I'm using you to build this. Follow these rules strictly. Do not deviate.

Build this in MILESTONES. Do not generate the full app at once. Each milestone must be tested and confirmed working before the next one starts. I will tell you when to move to the next milestone.

## Architecture context

The app is a single HTML file at www/index.html inside a Capacitor project. The native Android shell is already set up. These plugins are installed:

- @capgo/capacitor-speech-recognition
- @capawesome-team/capacitor-android-foreground-service
- @capacitor-community/text-to-speech
- @capacitor/filesystem
- @capacitor/preferences
- @capacitor/app
- @capacitor/clipboard
- @capawesome/capacitor-file-picker

CRITICAL: This is a NO-BUNDLER setup. The www/index.html is loaded directly by Capacitor without webpack/vite/rollup. ES module imports of npm packages will NOT work. You must access plugins via the global window.Capacitor.Plugins object or use Capacitor's built-in module loading. Before assuming any plugin access pattern, verify it works in Milestone 1.

## Native setup preflight (verify before Milestone 1)

Before generating any code, verify (or remind me to verify) that AndroidManifest.xml contains:
- INTERNET, RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS, WAKE_LOCK permissions
- The Capawesome foreground service declaration with android:foregroundServiceType="microphone"
- The required Capawesome service/receiver entries
- A <queries> block declaring com.google.android.googlequicksearchbox and the android.speech.RecognitionService intent (required on Android 11+ for the speech recognizer to be discoverable)

If any of the above is missing, tell me what to add to those files BEFORE writing any www/index.html code.

## Storage strategy (apply across all milestones)

- @capacitor/preferences: ONLY for settings, small indexes, and active conversation working state. Not a database. Don't shove giant data blobs into Preferences.
- @capacitor/filesystem: full conversation .txt files saved here.
- A "saved conversations index" lives in Preferences as a small JSON array of {filename, title, persona, connection, dateCreated} entries. The actual conversation content is in the .txt files. The index points to them.

### Save location strategy

The user wants to choose where conversations are saved. However, Android scoped storage means folder picking does not automatically grant durable writable access. So:

PRIMARY GOAL:
- User picks a save folder.
- Saved conversation .txt files are written to that folder.
- The app can read, append, rename, and delete those files later.
- The folder permission survives app relaunch.

FALLBACK:
- If durable user-chosen folder writing fails (proven during Milestone 1B), saved conversations use the app's own Directory.Documents.

Do NOT assume folder picking equals writable folder access. Milestone 1B tests this before saved conversations are built.

## Hard rules — apply to all milestones

- ONE single HTML file at www/index.html.
- Vanilla JavaScript only. No React, Vue, jQuery, Tailwind, Bootstrap, or any other framework or library.
- No login, accounts, social features, theme switchers, avatar uploads, cloud sync, external fonts, or CDN libraries.
- Mobile-first portrait UI. Dark mode (off-black background, light text). System fonts only. Tap targets at least 60px tall.
- No "// TODO" comments or vague "// rest of code unchanged" language. See output format section at the bottom for when to provide complete files vs. patches.

---

## MILESTONE 1: Plugin proof-of-life

Build a minimal www/index.html that:

1. Loads in the Capacitor WebView
2. On page load, logs to console which plugins are accessible and how (window.Capacitor.Plugins inspection)
3. Has a button "Test Permissions" that calls requestPermissions() on the speech recognition plugin and shows the result on screen
4. Has a button "Start Service" that calls startForegroundService with notification "VoiceChat Test Active" and shows success/error on screen
5. Has a button "Stop Service" that calls stopForegroundService and shows result
6. Has a button "Test TTS" that speaks "Hello, this is a test" via the TTS plugin
7. Has a button "Test Save Setting" that writes a key-value pair via Preferences and reads it back, showing both values
8. Has a button "Test File Save" that writes a small .txt to Filesystem cache directory and reports the path
9. Has a button "Test Clipboard" that calls Clipboard.write with sample text and Clipboard.read to verify
10. Has a button "Test Folder Pick" that calls the file picker plugin's pickDirectory method and shows the resulting URI on screen (just verifies picking works — the deeper write/persist test happens in Milestone 1B)

Show all results visibly on screen, not just in console — I'll be testing on a phone where I can't easily see the console.

If any plugin access pattern fails, stop and tell me what failed before continuing. Do not silently work around it.

Output: Milestone 1 file. Wait for me to confirm it works before Milestone 1B.

---

## MILESTONE 1B: Folder write proof-of-life

Before saved conversations are built, prove whether user-chosen folder storage actually works on Android with durable write access.

Build a screen with these buttons, each showing visible success/error on screen:

1. "Pick Save Folder" — uses file picker plugin's pickDirectory, saves resulting URI to Preferences
2. "Save Test File" — writes test.txt with some content into the chosen folder
3. "Read Test File" — reads test.txt back from the chosen folder, displays content
4. "Append Test File" — appends additional text to test.txt
5. "Rename Test File" — renames test.txt to test-renamed.txt in the chosen folder
6. "Delete Test File" — deletes the test file from the chosen folder
7. "Relaunch Persistence Test" — reads back the saved folder URI from Preferences and tries to read/write to it (simulates what happens after app relaunch with cached permission)

Test goal: I will run all of these on my Pixel 8, then close the app fully and relaunch it, then run them again. If everything works including after relaunch, Milestone 7 will use the chosen folder. If anything fails, Milestone 7 will fall back to Directory.Documents.

Report clearly to me which operations succeed and which fail. Do not silently work around failures.

Output: Milestone 1B file. Wait for confirmation before Milestone 2.

---

## MILESTONE 2: Screen-off speech recognition

Once Milestone 1 is confirmed working, extend the app to test continuous speech recognition with screen off.

CRITICAL ORDER: On Start, the app must:
1. Request microphone permission FIRST. If denied, show error and stop.
2. THEN start the foreground service.
3. THEN start the speech recognition plugin.

Do not start the foreground service before permission is granted — Android will reject it.

The app should:
1. Replace the test buttons with a single "Start Listening" / "Stop Listening" toggle
2. On Start: the order above
3. Speech recognition runs in continuous mode with partialResults enabled
4. Display partial transcripts live on screen as they arrive
5. When the recognizer stops itself (which it will), automatically restart it as long as the user hasn't tapped Stop
6. Track a silence timer in JS — when no new partial content has arrived for 2000ms, mark the current accumulated text as a "final utterance" and append it to a visible log on screen with timestamp
7. On Stop: stop recognition, stop foreground service

Test goal: I will tap Start, lock my phone screen, talk for several minutes with pauses, and confirm the transcripts keep flowing. If they don't, the foundation is broken and we stop here.

Output: Milestone 2 file. Wait for confirmation before Milestone 3.

---

## MILESTONE 3: TTS with sentence queue

Add TTS playback:

1. Add a text input and a "Speak" button. Tapping it sends text to TTS.
2. Implement TTS as a proper queue: only one utterance speaks at a time, new requests wait for the current one to finish.
3. Add a "Stop Speaking" button that immediately stops TTS and clears the queue.
4. Test: send three "Speak" requests in quick succession with different text. Confirm they play in order, not overlapping.

Output: Milestone 3 file.

---

## MILESTONE 4: API streaming from typed input

Add API integration without voice yet:

1. Add a Settings panel (gear icon) with: API endpoint, API key, model name. Defaults: https://api.deepseek.com/chat/completions, blank, deepseek-chat.
2. Add a text input and Send button on the main screen.
3. On Send: build messages array with [system prompt + conversation history + new user message], POST to configured endpoint with stream:true and Authorization: Bearer <key>.
4. Stream response body using response.body.getReader() and TextDecoder. Parse SSE line-by-line. Handle [DONE], handle choices[0].delta.content, handle malformed lines gracefully, show HTTP errors with status code AND response body.
5. Display assistant response as it streams.
6. Persist conversation history (capped at 100 messages, user-adjustable in settings) via Preferences.
7. Default system prompt: "You are a helpful conversational assistant. Keep replies natural and concise for spoken conversation. Avoid markdown formatting since replies will be read aloud."

Output: Milestone 4 file.

---

## MILESTONE 5: Full voice loop

Combine everything into the actual hands-free flow:

1. Replace typed input with a "Start Call" / "End Call" button.
2. On Start Call: request permissions, start foreground service, start speech recognition (in that order).
3. While listening, show partial transcript live. When silence threshold is hit, submit accumulated text as user's turn.
4. While the API is being called and streaming back, PAUSE speech recognition entirely (mic off). Status shows "Thinking..." then "Speaking..." as TTS plays.
5. As assistant tokens stream in, chunk them into sentences (end with . ! ? \n) and feed them to the TTS queue.
6. When TTS queue is empty AND stream is complete, automatically resume speech recognition.
7. NO BARGE-IN. No interruption handling. Mic is off while AI is speaking. Full cycle: listen → think → speak → listen → think → speak.
8. End Call: stop everything cleanly.

Status indicator at all times: "Idle" / "Listening..." / "Thinking..." / "Speaking..."

Output: Milestone 5 file.

---

## MILESTONE 6: Connections, personas, sampling defaults

### Connections (saved API configs)
- Settings panel section "Connections": list of saved API configs, each with label, endpoint, API key, default model
- Add/Edit/Delete each
- One marked as active
- Active connection's endpoint, key, and model used for API calls

### Personas (saved system prompts)
- Settings panel section "Personas": list of saved personas, each with label, system prompt, optional model override, optional temperature override, optional max_tokens override
- Add/Edit/Delete each
- One marked as active
- Active persona's system prompt used at start of messages array
- Persona overrides replace global defaults for the call when set

### Global sampling defaults
- Settings panel section "Sampling Defaults": temperature (default 0.7), top_p (default 1.0), max_tokens (default 2048)
- Apply unless persona overrides them

### Conversation memory
- Settings panel section "Conversation Memory": history cap, default 100 messages, options 20/50/100/200/Custom
- This controls how many recent user/assistant messages are sent to the API in each request — NOT how much is saved locally.
- Local saved transcripts always include the full conversation regardless of cap.

### Main screen dropdowns
- Persona switcher: small dropdown next to the call button showing current persona name. Tap to switch. Switching mid-conversation allowed; new persona's system prompt used from next turn forward.
- Connection switcher: similar dropdown elsewhere on main screen showing current connection label.

Output: Milestone 6 file.

---

## MILESTONE 7: Saved conversations and export

### Storage location
- If Milestone 1B confirmed durable user-chosen folder writes work: use the chosen folder for saved conversation .txt files. On first launch (or if no folder URI saved), prompt the user to pick a folder via the file picker plugin. Settings has a "Save Folder" entry showing current folder and a "Change" button.
- If Milestone 1B failed: use the app's own Directory.Documents instead. Skip the folder picker prompt entirely.
- Saved conversations index lives in Preferences as a small JSON array either way.

### File naming and auto-titles
- Settings has a toggle "Auto-generate conversation titles" — DEFAULT ON.
- If toggle is ON (default): after the first user+assistant exchange completes in a new conversation, the app makes a separate quick API call to the active connection asking the AI to summarize the conversation topic in 4-6 words. Use that as the conversation title. CRITICAL: This summary API call must run asynchronously in the background. It must NOT block the UI, and it must NOT delay the microphone from turning back on for the user's next turn.
- If toggle is OFF: use "Untitled" as the title until the user manually renames.
- If the title-generation call fails or returns garbage, fall back to "Untitled".
- Filename format: `<YYYY-MM-DD>-<title>-<persona>.txt`
  - Example: `2026-02-03-Recipe ideas-Default.txt`
  - Date is at the front in ISO 8601 format so files sort chronologically in any file manager.
  - Persona is the persona that was active when the conversation started, locked even if user switches mid-convo.
- Sanitize filename: strip or replace any characters Android filesystems don't accept (/, \, :, *, ?, ", <, >, |). Replace with spaces or remove.
- Collision handling: if a file with that exact name already exists in the folder, append `-V2`, then `-V3`, etc.
- Resume: when a saved conversation is loaded and continued, new content appends to the SAME file. Title and filename stay unchanged unless user renames.

### New Conversation button
- Button on main screen labeled "New Conversation".
- On tap: save the current conversation .txt to the active save location (chosen folder if Milestone 1B succeeded, otherwise Directory.Documents) using Filesystem, update the saved-conversations index in Preferences, then clear active conversation state to start fresh.
- Never auto-delete anything.

### Saved Conversations list
- Settings panel section "Saved Conversations": list of all saved conversations from the index, showing title, persona, date.
- Each entry has: tap-to-load, rename, delete (delete requires user confirmation).
- Tapping a conversation loads it as the active conversation. The saved .txt file is parsed back into structured user/assistant turns and restored into the active conversation state, so future API calls include that restored history (subject to the current history cap setting). Persona and connection it used originally become active. Tapping Start Call resumes from where it left off.
- Renaming: opens a text input pre-filled with the current title. On save, renames the actual .txt file in the folder AND updates the index entry. Sanitize the new name for filesystem-safe characters.
- If a saved conversation's underlying file is missing when user taps it: show "⚠️ Conversation file not found. It may have been deleted or moved." with two buttons: [Remove from list] and [Keep listed].

Output: Milestone 7 file.

---

## MILESTONE 8: Per-message buttons

On every message in the conversation transcript (user AND assistant turns), show three small buttons:

- **Copy**: copies that message's text to clipboard via the Clipboard plugin.
- **Read Aloud**: re-triggers TTS for that message (works on user messages too in case the user wants to hear what they said).
- **Retry** (assistant messages only, only on the most recent assistant message): regenerates that response. Re-sends the previous user message to the API, replaces the existing assistant message with the new response. No confirmation dialog. Only available on the most recent message — older messages don't have retry.

Output: Milestone 8 file.

---

## After Milestone 8

If everything works, V1 is done. Future enhancements (do not build now):
- Per-persona TTS voice / rate / pitch
- In-browser Whisper for offline recognition
- RAG / long-term memory across conversations
- Better Storage Access Framework support if the file picker plugin can't provide durable writable folder access

---

## Output format

- For the first version of each milestone, provide the complete www/index.html in a single code block.
- For small fixes after a milestone file exists, use PATCH MODE unless I explicitly request the full file.
- PATCH MODE means: give the exact old block to find, the exact new block to replace it with, and a short note on where it goes.
- Do not regenerate the full file for one-function or one-line fixes.
- Never use vague "rest unchanged" language unless the replacement block is precise enough to apply mechanically.

Begin with the native setup preflight check, then Milestone 1.
```

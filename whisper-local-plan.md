# SpeakGPT — Local Whisper + Hands-Free Voice Mode Plan

This plan is scoped to **modifying the existing SpeakGPT Android app** (this
repo), not building a new app from scratch. It supersedes the
Capacitor-based ideas in `voice-chat-build-guide(1).md` and
`corrected-prompt-block.md`, which describe a separate project and are
kept only as reference.

Branch: `claude/whisper-versions-research-5dVb3` (planning + CI prep).
Implementation will continue on follow-up branches.

---

## Goal

Replace Google's on-device dictation as the default STT engine with
**on-device Whisper** (whisper.cpp) so transcription quality is closer to
the paid OpenAI Whisper API, but with zero per-call cost and no internet
dependency. Then build a "phone call" voice-loop on top of it: mic →
think → speak → mic, with sensible silence and idle handling.

Not in scope for this plan: cloud sync, RAG, multi-language model
switching at runtime.

---

## Current state (so we know what we're modifying)

Today SpeakGPT has two STT engines, selected in settings:

- **Google** — `android.speech.SpeechRecognizer`, set up in
  `ChatActivity.kt:1697` (`initSpeechListener`) and the matching
  `speechListener` at `ChatActivity.kt:368`. The same pattern lives in
  `AssistantFragment.kt:460,988`.
- **Whisper (paid API)** — `MediaRecorder` writes `tmp.m4a` to the cache
  dir, then `processRecording()` sends it to OpenAI's `whisper-1`
  endpoint. See `ChatActivity.kt:1467 startWhisper`, `1539 stopWhisper`,
  `1572 processRecording`, mirrored in `AssistantFragment.kt:746,823,860`.
- The dispatch between the two engines is `onOpenAIAction("whisper", ...)`
  in `ChatActivity.kt:3172` and the symmetric Google path in
  `handleGoogleSpeechRecognition` at `ChatActivity.kt:1658`.
- The setting itself is read via `preferences?.getAudioModel()`
  (`Preferences.kt:531`).
- Personas-ish: there's one global system prompt (`getSystemMessage` at
  `Preferences.kt:477`) and one global activation prompt (`getPrompt` at
  `Preferences.kt:549`). No multi-persona switcher exists yet.
- Silence handling today is whatever the native recognizer or the user's
  manual tap provides — there is no configurable end-of-utterance window.

That gives us the seams we need without rewriting the chat surface.

---

## STT engine choice: whisper.cpp via JNI

Recommendation: add a **third audio engine** value alongside `"google"`
and `"whisper"`, e.g. `"whisper-local"`, that uses
[ggerganov/whisper.cpp](https://github.com/ggerganov/whisper.cpp)
through a small JNI wrapper.

Why whisper.cpp and not the alternatives:

- **whisper.cpp** has an actively maintained Android sample, runs purely
  on CPU+NEON (no NNAPI/TPU dependency, so behavior is predictable on
  any reasonably modern phone), and `base.en` is near real-time on
  Pixel 8.
- **TFLite Whisper** would let us use the Tensor G3's NPU but community
  ports lag behind upstream and accuracy at the same model size is
  noticeably worse.
- **Vosk** is truly offline but punctuation and accuracy are clearly
  below Whisper-base.

### Models

Offer three on-device models. The user picks which one(s) to download —
we do not pre-download anything. Files live in the app's private files
dir (`context.filesDir/whisper/`), which means they're removed if the app
is uninstalled. No external storage permission needed.

| Model              | Size    | Notes                                                  |
|--------------------|---------|--------------------------------------------------------|
| `ggml-base.en.bin` | ~140 MB | Fast default. Clearly better than Google dictation.    |
| `ggml-small.en.bin`| ~466 MB | Bigger accuracy bump. Still real-time on Pixel 8.      |
| `ggml-large-v3-turbo.bin` | ~809 MB | Max quality. Multilingual under the hood but excellent for English. ~2–3× slower than base; battery cost noticeable in long hands-free calls. |

Behavior:

- **Nothing is bundled in the APK.** APK growth is just the native lib
  (~3–8 MB).
- The settings UI lists the three models with a per-row **Download**
  button. Tap to fetch into private storage with a progress bar (MB / MB
  + Cancel). Verify SHA-256 after download; on mismatch, delete and
  prompt to retry.
- After a model is downloaded its row shows a green "Installed" badge
  and tapping it makes it the **active** model. Switching active models
  is instant — no re-download.
- A **Manage downloaded models** screen lists installed models with a
  **Delete** button per row. The currently active model can't be deleted
  until the user picks another active model (or switches the STT engine
  off "on-device").
- If the user picks "On-device Whisper" as the engine but has no model
  installed yet, the engine selector immediately routes them into the
  model picker screen with a hint banner ("Pick a model to download").

### JNI layer

- Add a Kotlin object `LocalWhisper` that owns the native handle.
- Native code is a thin C++ wrapper around whisper.cpp's `whisper_init_from_file_with_params`
  and `whisper_full`, exposed as `nativeInit(modelPath: String)`,
  `nativeTranscribe(pcm: ShortArray, sampleRate: Int): String`,
  `nativeRelease()`.
- Build via Gradle's `externalNativeBuild` (CMake). Target arm64-v8a
  only — this is a personal build for a Pixel 8, no need for other
  ABIs.
- The first `nativeInit` after process start loads the model into RAM
  (~150 MB for base, ~500 MB for small). Keep the handle alive until
  the chat activity is destroyed; don't re-init per utterance.

### Audio capture

- Use `AudioRecord` (not `MediaRecorder`) at 16 kHz mono PCM, because
  whisper.cpp wants raw 16 kHz float/short PCM and `MediaRecorder`
  only gives us encoded m4a.
- Buffer audio in-memory while the user speaks. No file on disk for the
  hot path. Only write to disk if we hit the optional "save raw audio"
  debug toggle.

---

## Hands-free voice-call mode (the auto-loop)

This is a new mode on top of the existing chat. It does not replace the
push-to-talk mic button — that stays for one-shot voice input. Hands-free
mode is opt-in via a dedicated toggle/button on the chat screen.

State machine:

```
              ┌────────┐
              │  Idle  │◄──────────────────────────┐
              └───┬────┘                           │
        user starts call                           │
                  ▼                                │
              ┌────────────┐    user pauses 5s     │
              │ Listening  │──────────────────────►│
              └───┬────────┘                       │
        utterance submitted                        │
                  ▼                                │
              ┌────────────┐                       │
              │  Thinking  │                       │
              └───┬────────┘                       │
        first token / TTS start                    │
                  ▼                                │
              ┌────────────┐  TTS queue empty &    │
              │  Speaking  │  stream complete      │
              └───┬────────┘──────────────────────►│ back to Listening
                  │                                │
        user taps Pause / End anywhere ────────────┘ (Idle)
```

Key rules:

- **No barge-in in v1.** While Speaking, mic is fully closed. The user
  can interrupt only by tapping Pause or End. (Barge-in is a v2
  feature — it's surprisingly hard to do well without echo cancellation
  on every speaker/mic combo.)
- After `Speaking` ends, mic reopens **automatically** within ~250 ms.
  Status indicator flips to "Listening…" and stays there until either
  an utterance is submitted or the idle timeout fires.
- Status indicator visible at all times: Idle / Listening / Thinking /
  Speaking / Paused.

---

## Silence and idle handling (the numbers you asked for)

Two distinct timers, both configurable in Settings but with these
defaults:

1. **End-of-utterance silence: 5 seconds.** While in `Listening`, the
   app runs a JS-style silence timer in Kotlin. Every time whisper.cpp
   gets new non-empty audio energy above a low threshold, the timer
   resets. When the timer hits 5 seconds with no qualifying audio, the
   accumulated audio buffer is finalized, transcribed, and submitted as
   the user's turn. This is what lets you breathe / pause without the
   mic cutting you off.
   - We do voice-activity detection (VAD) on the raw PCM stream, not on
     whisper.cpp output, so the timer can run while the recognizer is
     still chewing. Simple energy-based VAD is enough; we can swap in
     WebRTC VAD later if false-positives get annoying.
   - Floor: 2 s. Ceiling: 15 s. Slider in Settings.

2. **Idle hang-up: 8 minutes.** A separate "no successful utterance in
   N minutes" timer. If you start a call, say nothing useful for 8
   minutes, the app exits `Listening` and returns to `Idle` on its own,
   closing the mic. Default 8 min. Slider 1–30 min, or "Never" for users
   who really want it.
   - Resets on every successful utterance submission and on every Pause
     toggle.
   - Fires a single audible cue ("session ended") if TTS is enabled, so
     you know it stopped without looking at the screen.

3. **(Implicit third) Per-restart watchdog.** Native Android speech
   recognition stops itself every ~10–30 s; whisper.cpp doesn't have
   that problem because we own the capture loop. But we still want a
   max-utterance length (e.g. 60 s) so a stuck capture doesn't grow
   unbounded. Configurable, default 60 s.

---

## Personas (multi system-prompt)

Today `Preferences.getSystemMessage()` stores one global prompt. Plan:

- Add a new `PersonaPreferences` class that stores a list of
  `Persona { id, name, systemPrompt, optional: temperature, model
  override }` in SharedPreferences as JSON. Keep this independent of
  the existing single `systemMessage` key so we don't break older
  installs.
- One persona is marked active. The active persona's `systemPrompt`
  replaces the global system message when building the API request.
- Migration: on first launch after upgrade, create a "Default" persona
  seeded from the current `getSystemMessage()` value and mark it
  active. Leave the legacy `systemMessage` key untouched as a read-only
  fallback so anything else that reads it keeps working.
- UI: a small dropdown above the chat input (or next to the call
  button in hands-free mode) shows the active persona name. Tapping it
  opens a list with Add/Edit/Delete. Switching mid-conversation is
  allowed; the new system prompt applies from the next turn onward.
- Settings panel gets a "Personas" section that's the full
  list/add/edit/delete management UI.

---

## Re-read button

- Each assistant message in the chat already has a row of action
  buttons (copy, etc.). Add a **"Read aloud"** button to that row.
- Tapping it pipes the message text through the existing `speak()` /
  TTS pipeline that hands-free mode uses.
- Works for older messages too, not just the most recent — useful if
  you missed something three turns ago.
- If hands-free mode is currently `Listening`, tapping Read aloud
  transitions to `Speaking`, mic closes, message plays, mic reopens
  when done — same as a normal AI turn.

---

## Pause / mic-off toggle

- Dedicated "Pause" button visible only when hands-free mode is active.
- Tapping Pause: stops audio capture, cancels any pending utterance
  submission, but **keeps the conversation alive** (history, persona,
  scrollback all preserved). Status becomes "Paused".
- While Paused, the normal text input is still usable. You can type a
  message, send it, see the response (TTS still plays the response).
  The mic stays off the whole time.
- Tapping Resume puts the state machine back into `Listening`
  immediately.
- The idle-hang-up timer is **paused** while Paused. (Otherwise typing
  a long message would silently end the call.)

---

## Settings additions

The current Settings screen has a single on/off **Whisper STT** tile
(SettingsActivity.kt:623, toggle handler at 1215). With three engines and
three downloadable models, an on/off toggle no longer fits. Replace it.

### Voice-input tile (top-level Settings)

- Tile title: **Voice input**. Subtitle shows the active engine + active
  model where applicable, e.g. "On-device Whisper · base.en" or
  "Google dictation" or "OpenAI Whisper (cloud)".
- Tapping the tile opens the **Voice input engine** picker.

### Voice input engine picker (dialog)

Single-select radio list:

- ◯ Google dictation
- ◯ OpenAI Whisper (cloud, paid)
- ◯ On-device Whisper  →   (chevron — opens model picker)

Picking Google/cloud applies immediately. Picking On-device pushes the
**On-device Whisper** screen.

### On-device Whisper screen

```
← On-device Whisper

  Active model: base.en
  ────────────────────────────────────────
  ●  base.en          140 MB   [Installed]
  ◯  small.en         466 MB   [Download]
  ◯  large-v3-turbo   809 MB   [Download]

  Storage used: 140 MB
  [Manage downloaded models]
```

- Tap a non-installed row → starts download with a progress dialog.
  Cancel is allowed and removes the partial file.
- Tap an already-installed row → makes it the active model (instant).
- Bottom button opens the **Manage downloaded models** screen.

### Manage downloaded models screen

```
← Manage downloaded models

  base.en          140 MB   [Delete]
  small.en         466 MB   [Delete]

  Total: 606 MB
```

- Each row has a confirm-before-delete tap target.
- The currently active model's row says "Active — pick another to delete"
  instead of Delete.
- If the user deletes the last installed model while on-device Whisper is
  the active engine, fall back to Google dictation and show a one-time
  snackbar explaining the fallback.

### Other new settings

- **End-of-utterance silence** — slider 2–15 s, default 5 s.
- **Idle hang-up** — slider 1–30 min + "Never", default 8 min.
- **Max utterance length** — slider 15–120 s, default 60 s.
- **Personas** — list management (see above).
- **Hands-free mode default** — toggle: when opening a chat, start in
  hands-free mode automatically? Default OFF.

---

## Screen-off operation: foreground service

Hands-free mode runs a foreground service with type `microphone` for the
entire duration of an active call. This is what keeps the mic alive when
the screen locks — without it Android will kill the recognizer within
seconds of the screen turning off.

- Service started in the Listening transition out of Idle, stopped on
  End Call or Idle hang-up.
- Persistent notification while the service runs ("SpeakGPT call
  active"). Tap to bring the chat back to the foreground.
- Manifest needs `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`,
  `POST_NOTIFICATIONS`, `WAKE_LOCK`, plus the service declaration with
  `android:foregroundServiceType="microphone"`.
- Use a `PARTIAL_WAKE_LOCK` while the service is running so the CPU
  doesn't doze on us. Release it when the call ends.

---

## Files we expect to touch

Not exhaustive, but a starting map:

- `app/src/main/java/org/teslasoft/assistant/preferences/Preferences.kt`
  — add silence-window, idle-timeout, local-model-path, max-utterance
  prefs.
- New: `app/src/main/java/org/teslasoft/assistant/preferences/PersonaPreferences.kt`.
- New: `app/src/main/java/org/teslasoft/assistant/stt/LocalWhisper.kt`
  (Kotlin facade) + `app/src/main/cpp/whisper_jni.cpp` (JNI shim) +
  `app/src/main/cpp/CMakeLists.txt` (links to whisper.cpp sources
  vendored under `app/src/main/cpp/whisper/`).
- New: `app/src/main/java/org/teslasoft/assistant/stt/VoiceLoopController.kt`
  — owns the Idle/Listening/Thinking/Speaking/Paused state machine and
  the two timers.
- New: `app/src/main/java/org/teslasoft/assistant/voice/VoiceCallService.kt`
  — the foreground service that holds the mic alive across screen-off.
- `app/src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt`
  — thread the new engine into `handleWhisperSpeechRecognition` /
  `handleGoogleSpeechRecognition` dispatch, wire up the hands-free
  toggle, the Pause button, and the persona dropdown.
- `app/src/main/java/org/teslasoft/assistant/ui/fragments/AssistantFragment.kt`
  — mirror the same engine selection so the assistant floating UI
  benefits too.
- `app/src/main/AndroidManifest.xml` — add `RECORD_AUDIO`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`,
  `POST_NOTIFICATIONS`, `WAKE_LOCK` if missing, plus the
  `VoiceCallService` declaration with
  `android:foregroundServiceType="microphone"`.
- New layout XMLs for the persona dropdown, hands-free panel, and
  Settings entries.

---

## Build / size impact

- Native libs add ~3–8 MB to the APK depending on ABIs shipped.
- whisper.cpp sources vendored in-tree: ~500 KB.
- Models live in app private storage, not in the APK. APK growth is
  effectively just the native lib.
- Build requires NDK r26+ and CMake 3.22+. Update `build.gradle` as
  needed.

## CI / GitHub Actions impact

Both workflows currently install only Java 21:

- `.github/workflows/android-checks.yml` — runs on every push and on PRs
  to `main`. Builds `assembleDebug` + runs unit tests.
- `.github/workflows/release.yml` — runs on pushes to `main`. Builds and
  publishes `phosphor-shines.apk` to the `latest` GitHub release.

Once whisper.cpp lives in `app/src/main/cpp/`, Gradle's
`externalNativeBuild` (CMake) will fire during `assembleDebug` and need
two extra things on the runner:

1. **Android NDK r26d.** Installed via `nttld/setup-ndk@v1` (sets
   `ANDROID_NDK_HOME` and puts the toolchain on PATH).
2. **CMake 3.22+.** No explicit install needed — the Android Gradle
   Plugin auto-downloads it via the Android SDK manager the first time
   it sees `externalNativeBuild { cmake { ... } }` in `app/build.gradle`.

Pre-stage the NDK step in both workflows **before** vendoring any C++
sources, so the first whisper.cpp push doesn't surface a "no C++
toolchain" failure. With no native code yet, the NDK install is a no-op
that adds ~30–60 s to the run.

---

## Rollout order

0. **CI prep (this branch).** Update both workflows to install NDK r26d
   before the Gradle build, and rewrite this plan doc to match
   final scope (three models, user-picked download, delete UI, new
   Settings picker). No app code touched yet.
1. **Settings UI rewrite + model download manager.** Replace the on/off
   STT tile with the Voice input picker + On-device Whisper screen +
   Manage downloaded models screen described above. Wire the download
   manager (DownloadManager + SHA-256 verify) so models land in
   `filesDir/whisper/`. The local-Whisper *runtime* still isn't hooked
   up at this point — picking on-device falls back to cloud Whisper
   silently with a "not yet implemented" snackbar.
2. **whisper.cpp integration.** Vendor whisper.cpp under
   `app/src/main/cpp/`, add `CMakeLists.txt`, write the JNI shim, add
   `LocalWhisper.kt`, and route `getAudioModel() == "whisper-local"`
   through it in `ChatActivity.kt` + `AssistantFragment.kt`. Tapping
   the existing mic button uses on-device Whisper end-to-end.
3. **Foreground service + hands-free button**: add `VoiceCallService`,
   manifest permissions, and the hands-free toggle on the chat screen.
   Mic survives screen-off. (Most of this already exists from prior
   hands-free work — verify it still works with the new engine.)
4. **VoiceLoopController polish:** Idle → Listening → Thinking →
   Speaking → Listening state machine, 5 s end-of-utterance timer,
   status indicator. (Partial: silence/idle timers already exist.)
5. **Idle hang-up (8 min) + Pause button.**
6. **Re-read button on every assistant message.**
7. **Personas: storage, dropdown UI, settings management, migration
   from `getSystemMessage()`.**
8. **Polish**: settings sliders for the three timers, per-model SHA-256
   re-verify button in Manage screen.

Each step is testable on the Pixel 8 before moving on.

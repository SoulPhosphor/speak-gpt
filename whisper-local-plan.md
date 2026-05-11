# SpeakGPT — Local Whisper + Hands-Free Voice Mode Plan

This plan is scoped to **modifying the existing SpeakGPT Android app** (this
repo), not building a new app from scratch. It supersedes the
Capacitor-based ideas in `voice-chat-build-guide(1).md` and
`corrected-prompt-block.md`, which describe a separate project and are
kept only as reference.

Branch: `claude/speech-to-text-solution-XjfTA`.

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

### Model

- Ship **`ggml-base.en.bin`** (~140 MB) as the default. It's the smallest
  model with quality clearly better than Google dictation, and the Pixel
  8 transcribes a 10-second clip in roughly 1–2 seconds.
- Offer **`ggml-small.en.bin`** (~466 MB) as an optional upgrade in
  Settings for max quality.
- Do **not** bundle the model inside the APK. Download on first use of
  the local-whisper option, into the app's private files dir. Use a
  **blocking modal dialog** with progress bar, MB count, and Cancel
  button — the feature is unusable until the model is on disk, so
  there's no point letting the user wander off and tap the mic to find
  nothing happens.
- Verify SHA-256 after download. If verification fails, delete and
  prompt to retry inside the same dialog.

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

New entries under existing settings screen:

- **Voice input engine** — radio: Google / Whisper (cloud) / Whisper
  (on-device). Default flips to on-device once a model is downloaded.
- **On-device Whisper model** — radio: base.en (140 MB) / small.en
  (466 MB). Shows current size on disk and a Re-download button.
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

---

## Rollout order

1. **Engine swap + model download dialog**: vendor whisper.cpp, build
   the JNI shim for arm64-v8a, wire `whisper-local` into
   `getAudioModel()` and the existing dispatch, and ship the blocking
   model-download modal. After this step, tapping the existing mic
   button uses on-device Whisper end-to-end.
2. **Foreground service + hands-free button**: add `VoiceCallService`,
   manifest permissions, and the hands-free toggle on the chat screen.
   Mic survives screen-off.
3. **VoiceLoopController**: Idle → Listening → Thinking → Speaking →
   Listening state machine, 5 s end-of-utterance timer, status
   indicator.
4. **Idle hang-up (8 min) + Pause button.**
5. **Re-read button on every assistant message.**
6. **Personas: storage, dropdown UI, settings management, migration
   from `getSystemMessage()`.**
7. **Polish**: SHA-256 verify, `small.en` upgrade path, settings
   sliders for the three timers.

Each step is testable on the Pixel 8 before moving on.

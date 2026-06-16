# Phosphor Shines — AI Onboarding Manual

This project is coded entirely by AI agents. This file is the onboarding
manual: read it before touching code, and **keep it updated** when you change
anything it describes (storage schema, feature list, fragile areas, workflow).
Stale onboarding docs are worse than none.

## App summary

Android voice/chat assistant (fork of TeslaSoft SpeakGPT, now independent —
all upstream TeslaSoft services were removed). Talks to any OpenAI-compatible
chat-completions endpoint (the owner uses z.ai GLM models via a custom base
URL). Heavy emphasis on **hands-free voice conversation**: on-device Whisper
transcription (native whisper.cpp), VAD-driven mic loop, TTS readback with the
screen off. Recent additions: a persona-scoped **lorebook memory system** and
a foreground service that keeps generation alive in the background.

- Package: `org.teslasoft.assistant` (namespace) / app id `com.soulphosphor.phosphorshines`
- Single module `:app`, Kotlin + some C++ (whisper.cpp, WebRTC VAD via JNI)
- minSdk 28, target/compile SdK 36, Java 21, arm64-v8a only (owner runs a Pixel 8)
- UI: classic Views/XML (no Compose), Material 3 components, `FragmentActivity`s,
  `DialogFragment`s, `BaseAdapter` + `ListView` for most lists, RecyclerView in chat

## How development actually works here (important)

- **There is usually no local Android SDK** in agent sandboxes, and the network
  policy blocks `dl.google.com` / `maven.google.com`, so you cannot compile
  locally. **CI is the compile gate**: `.github/workflows/android-checks.yml`
  runs on every push to any branch. Push your branch, then watch the run.
  Before pushing, statically verify your work: every `R.*` reference resolves,
  imports exist, XML is well-formed, brace balance, call-site signatures.
- `.github/workflows/release.yml`: every push to `main` publishes a
  **debug-signed test prerelease** tagged `latest` (release signing secrets are
  not configured; the debug keystore is checked in and public — never treat
  debug builds as distributable). A `beta` tag/release exists from an old
  side-by-side beta channel; its lorebook feature has since been merged here.
- Work on a feature branch, push with `git push -u origin <branch>`, never
  force-push `main`. Commit messages explain *why*, in plain prose.
- The owner is not a coder. Explain changes in user terms; when a request is
  ambiguous, prefer asking over guessing on destructive/architectural choices.

## Architecture map (where things live)

- `ui/activities/ChatActivity.kt` (~4.5k lines) — the heart of the app: chat UI,
  all generation paths, voice pipeline glue, lorebook injection, auto-naming.
  Single funnel for outgoing requests: `generateResponse()` →
  `regularGPTResponse()` (every input path — typed, Google STT, local Whisper —
  flows through `generateResponse`, so cross-cutting hooks go there).
- `ui/fragments/AssistantFragment.kt` — the floating "assistant" overlay; has
  its own parallel `generateResponse()`. **Cross-cutting changes to generation
  must be applied in both places.**
- `ui/adapters/chat/ChatAdapter.kt` — message rendering (Markwon markdown,
  selectable text, copy/edit/speak buttons, bulk select).
- `service/HandsFreeService.kt` — microphone-typed foreground service for
  screen-off hands-free conversation (wake lock + notification).
- `service/GenerationForegroundService.kt` — dataSync-typed foreground service
  held only while a response streams; ref-counted via `begin()`/`end()` in the
  `try`/`finally` of both generateResponse funnels.
- `stt/` — on-device speech: `LocalWhisperEngine/Native` (whisper.cpp JNI),
  `WebRtcVadNative` + `VoiceActivityDetector` (energy VAD + libfvad), model
  download/storage. Native sources in `app/src/main/cpp/`.
- `preferences/` — all persistence (see Storage below).
- `preferences/lorebook/LoreBookStore.kt` — the only SQLite database.
- `ui/fragments/dialogs/QuickSettingsBottomSheetDialogFragment.kt` — per-chat
  settings sheet (model, endpoint, persona, activation prompt, lorebook
  checklist, sampling params).
- `ui/activities/CharactersActivity.kt` — hub of tiles: Personas, Activation
  prompts, System message, Lorebooks.
- Root docs: `Memory System Plans June 1 2026` (the lorebook/memory roadmap,
  phased; Phase 1 + multi-book are built), `whisper-local-plan.md`,
  `voice-chat-build-guide(1).md`, `ui-redesign-plan.md` (the approved UI
  overhaul spec — read it before ANY UI work).

## Storage / database choices

Everything is on-device. No cloud sync, no accounts.

1. **SharedPreferences** (the bulk of persistence, all under `preferences/`):
   - `Preferences.kt` — per-chat settings, keyed by chat id (`Hash.hash(chatName)`).
     One SharedPreferences file per chat (`settings_<chatId>`), plus global
     fallbacks. Includes the chat's checked lorebooks (`active_lorebook_ids`,
     comma-separated) and one-shot seed flags.
   - `ChatPreferences.kt` — chat list + message history (JSON in prefs).
   - `PersonaPreferences.kt` — personas, flat keys `<personaId>_<field>` where
     `personaId = Hash.hash(label)`. **Renaming a persona changes its id**
     (edit = delete + recreate); lorebook links survive only because the edit
     dialog passes every field through. Keep that invariant.
   - `ApiEndpointPreferences` (encrypted), `ActivationPromptPreferences`,
     `LogitBiasPreferences`/`LogitBiasConfigPreferences`,
     `FavoriteModelsPreferences`, `GlobalPreferences`, `EncryptedPreferences`
     (androidx security-crypto for API keys).
2. **SQLite** — `lorebook.db` via `LoreBookStore` (singleton SQLiteOpenHelper).
   Schema v3: `lorebooks` (id/name/description/tag/timestamps),
   `memory_entries` (id/lorebook_id/label/content/source_text/enabled/timestamps),
   `memory_triggers` (FK → entries, ON DELETE CASCADE). Migrations are
   additive `ALTER TABLE` steps in `onUpgrade` — always bump
   `DATABASE_VERSION`, never edit old migration blocks. The planned **vector
   memory** is expected to extend this database (that's why it's SQLite and not
   prefs) — design new tables, don't bolt blobs onto prefs.
3. **Files** — images in `getExternalFilesDir("images")`, whisper models via
   `LocalWhisperStorage`.

## Current feature list

- Multi-chat with per-chat settings (model, endpoint, sampling, persona, …),
  auto-naming of new chats (which **changes the chat id** and copies every
  per-chat preference — if you add a per-chat setting, add it to the copy block
  in `ChatActivity` after auto-naming, or it silently vanishes on rename).
  Auto-naming adopts the new id **in place** — it must never relaunch
  ChatActivity, because onDestroy kills the readback and hands-free loop.
- Any OpenAI-compatible endpoint; multiple endpoint profiles; streaming via
  `com.aallam.openai` (Ktor 2.3.12 — pinned, do not upgrade); secondary
  official `openai-java` client for function calling.
- Voice: hands-free loop (VAD listen → Whisper/Google STT → generate → TTS
  readback → re-arm), manual mic button, per-message speak button, audible
  error/done chimes, screen-off operation via foreground services.
  Voice diagnostics: with any VAD-logging toggle on (Energy, WebRTC or Silero
  — each detector has its own toggle, now in the Alert & Debug menu), every
  loop decision (mic open/close + why, readback, failures, loop stop reasons)
  is written to the persistent Event log via `ChatActivity.logVoiceEvent` —
  when adding a new loop exit path, log it there or failures become
  undiagnosable. The per-turn VAD diagnostics line (`logVadDiagnostics`)
  follows the same toggles (`voiceDiagnosticsEnabled()`), so logging off means
  no VadDiag spam. `Logger` is local-only (no telemetry); it must not be gated
  on the installation id.
  Advanced voice & debugging screen (`VoiceAdvancedSettingsActivity`, plain
  rows not tiles, reached from a full-width tile in Voice settings): VAD
  energy-gate tuning (`VadTuning` — gate on/off, min RMS, floor factor,
  ceiling, min speech duration, plus hysteresis: a two-level gate where
  speech enters at the full gate but only has to stay above gate×exit-ratio,
  default ON at 50%, with an optional speech-hold/hangover defaulting to 0 —
  built for changing-loudness rooms; the Energy detector's hysteresis is
  unit-tested in `app/src/test`; all global prefs). Three VAD methods:
  Energy, WebRTC (libfvad), and Silero — a neural detector running the
  bundled `assets/silero_vad.onnx` (pinned silero-vad v5.1.2, MIT) through
  ONNX Runtime (`onnxruntime-android` dep + proguard keep rule); Silero
  ignores the energy-gate knobs (only its probability threshold applies,
  plus shared hysteresis/speech-hold) and falls back to Energy with an
  event-log line if the runtime can't load. On-device Whisper
  decode params (greedy/beam, beam size,
  temperature, suppress-blank, single-segment, initial prompt, prev-context,
  cleanup toggle — plumbed Kotlin→JNI→whisper_full, defaults match the old
  hardcoded values), and device-TTS rate/pitch (set in both ttsPostInit
  funnels). (The voice-diagnostics logging toggles that used to live here moved
  to the Alert & Debug menu.) The VAD energy gate exists because WebRTC
  mislabels fan noise as voice, but a fixed gate also silently discarded a
  quiet voice ("voiced N, gated 0" in diagnostics) — that's why it's tunable.
- Lorebook memory system: multiple books (title/description/type-tag,
  editable in place via the cog in the book's entries screen; tag/description
  shown under that screen's header and wherever books are listed),
  memories with trigger phrases matched against the latest user message
  (whole-word, case-insensitive, light suffix folding via
  `LoreBookTriggerMatcher` — unit-tested in `app/src/test`; a trigger wrapped
  in double quotes demands that exact text instead), persona-owned core book (always active) + linked
  additional books checked per chat in Quick Settings, injection as a separate
  system message after the stable base prompt (preserves prefix caching),
  injection budget 20 entries / 6000 chars, debug view of injections.
- Personas (prompt + activation prompt + lorebooks + auto-load-last-books),
  activation prompts, custom assistant name/avatar.
- Markdown/LaTeX rendering, partial text selection, message edit/delete/copy/
  share, bulk select, image attach + DALL·E-style generation, in-app
  translator, playground, logit bias editor, AMOLED theme, onboarding flow.
- Settings → Debug is a single full-width "Alert and Debug Menu" tile opening
  `AlertDebugMenuActivity` (plain rows, mirrors `VoiceAdvancedSettingsActivity`).
  It holds, top to bottom: **Show chat errors** and **Sound alert for model
  errors** (was "Error sound") switches; a **Voice Diagnostics** section with
  the **Energy / WebRTC / Silero** VAD-logging switches (moved here from the
  Advanced voice screen — each detector has its own toggle now, so Silero's
  logs are no longer tied to WebRTC's) plus an **Audio Health** switch; then
  **Crash log** / **Event log** as "label ›" rows that open `LogsActivity`. The
  error and VAD-logging toggles are all global prefs; the logs are local-only
  and intentionally always reachable (not gated on the installation id) — don't
  reintroduce that gate when restyling. **Audio Health** is a separate
  hands-free diagnostic from VAD logging (`getAudioHealthLogging`): per-turn
  microphone input-health stats (frames, RMS/peak levels, near-silent/clipped
  counts, sample rate, channels, input route + mid-capture route changes) with
  plain-words hints, collected in `LocalWhisperEngine`'s capture loop
  (`AudioHealthMonitor`) and written to the Event log alongside any VAD line via
  `ChatActivity.logVadDiagnostics` (one combined entry when both are on).

## Coding rules

- Match the existing style: nullable `var` view fields + `findViewById`,
  `DialogFragment.newInstance(Bundle)` pattern, listener interfaces with
  default no-op methods, copyright header on every file, strings ONLY in
  `res/values/strings.xml` (other locales fall back; don't translate unless
  asked), icons as vector drawables tinted `?attr/colorPrimary`.
- Comments explain *constraints and why*, not what the next line does. This
  codebase's comments are load-bearing documentation for future agents —
  preserve and extend that.
- Singletons: `getInstance(context)` with `applicationContext` (see
  `LoreBookStore`) to avoid activity leaks.
- Per-chat state goes through `Preferences`; global state through global keys
  in `Preferences` or `GlobalPreferences`. Never raw `getSharedPreferences`
  in feature code.
- Coroutines: scopes are stored in fields and cancelled in lifecycle methods;
  follow the surrounding pattern in `ChatActivity` when adding async work.
- Anything user-visible that can fail mid-stream must restore UI state in a
  `finally` (`restoreUIState()`), and release `GenerationForegroundService`.
- Deleting an entity that other entities reference must scrub the references
  (see `PersonaPreferences.removeLoreBookFromAllPersonas`).
- Destructive UI actions (delete book/persona/chat) always get a Material
  confirm dialog.

## Do not touch / fragile

- **Ktor pinned at 2.3.12** (`app/build.gradle`): newer Ktor breaks the
  `openai-client` streaming. Don't "helpfully" upgrade.
- **TLS / OkHttp defaults**: history includes a security fix restoring default
  TLS + hostname verification. Never add custom `TrustManager`/`HostnameVerifier`.
- **The hands-free VAD/mic pipeline** (`ChatActivity` voice sections, `stt/`,
  `HandsFreeService`): the single most regression-prone area; ~10 PRs of
  hard-won fixes (mic re-arm, audio-time VAD windows, barge-in prevention,
  watchdogs). Don't refactor it incidentally; change it only when the task is
  about it, and read the commit history of the file first.
- **System-message assembly in `regularGPTResponse`**: persona prompt + system
  message are merged into ONE stable first system message specifically for
  provider prefix caching; lorebook matches go in a SEPARATE system message
  after it. Don't reorder or merge these.
- **`ChatPreferences` parse-failure handling**: chat data must be preserved,
  never wiped, on JSON parse errors (regression fixed in c72853a).
- **Native layer** (`app/src/main/cpp`, CPU gating in `NativeCpuSupport`):
  lib loading is gated on armv8.2 dotprod+fp16 to prevent SIGILL on older
  arm64 — keep the gate if you touch JNI loading.
- **Checked-in `debug.keystore`** is intentional (stable CI debug signing).
  Do not rotate/remove it; do not publish debug builds as real releases.
- **Auto-naming preference copy block** in `ChatActivity` (see feature list) —
  easy to forget, silent data loss when missed.
- Legacy/odd-named files exist (`InstructionsForDegradedTeapots…Activity`,
  `MainActivity_robo_script.json`, `experiment.json`, `desktop.ini`,
  `hub-purge.sh`) — leave them unless asked.

## Roadmap context (plan around this)

- **A UI overhaul is planned and specified in `ui-redesign-plan.md`** (owner
  approved June 2026: stay on Views/XML + Material 3, left chat-list drawer,
  preset color palettes). Any UI task must follow that plan — it contains the
  phase order, verified view-ID contracts, and the pitfall list. Don't
  over-invest in pixel-perfect tweaks to current layouts, and keep feature
  logic out of layout/adapter code where practical so the UI can be swapped
  without rewriting behavior.
- **Vector memory is planned** (see `Memory System Plans June 1 2026`,
  Phases 2+: search, summaries, embeddings, ReAct-style retrieval). The
  lorebook SQLite schema is the intended foundation — extend `lorebook.db`
  with new tables/columns via versioned migrations; keep memory access going
  through `LoreBookStore` so a retrieval layer can slot in behind one API.
- Whisper/voice work follows `whisper-local-plan.md`.

## Quick verification checklist before any push

1. `git status` clean of stray files; commit message says why.
2. All new `R.string/R.id/R.drawable/R.layout` references exist; new ids match
   the layout actually inflated.
3. Both generation funnels updated if the change is cross-cutting.
4. New per-chat preference added to the auto-naming copy block.
5. DB change → version bump + additive migration + fresh-install path.
6. Push, then confirm the `Android Checks` workflow run for your commit is green.

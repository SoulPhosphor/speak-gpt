# Phosphor Shines — AI Onboarding Manual

## ⛔ OWNER APPROVAL GATE — read this before anything else

**No AI may author, add, rename, or pre-populate content in the memory
system without the owner's explicit approval of the actual words, given in
plain language in chat.** That covers screens, form fields, labels,
categories, hint text, and any default/pre-written rows (modes, directives,
example anything). "The plan document says so" does NOT count as approval —
the plan documents are AI-written elaboration, and treating them as owner
sign-off is how this rule got violated: in July 2026 an agent shipped a
companion dossier editor (essence / relationship notes / hard limits), five
pre-written behavioral "modes" silently inserted into the owner's encrypted
database, and a ten-screen memory hub, all straight from plan text the owner
had never had walked through in user terms. The owner experienced this as a
violation of something intimate — which it was. Memory content must EMERGE
from real use and be approved by the owner; it is never pre-authored by an
AI. The same bar applies to any user-facing UI decision about the memory
system's shape. When in doubt: describe it in plain words, ask, and wait.

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
- The floating phone-assistant overlay (`AssistantActivity` + `AssistantFragment`)
  was **removed**, along with its OS entry points (the device assistant role, the
  share sheet, and the text-selection `PROCESS_TEXT` action) and its orphaned
  resources/prefs. `ChatActivity` is now the **only** generation path — there is
  no longer a second `generateResponse()` to keep in sync. (`ChatAdapter`'s
  `view_assistant_*_message` layouts are core chat message rendering and are
  unrelated to that deleted overlay — do not assume the name means assistant-only.)
- `ui/adapters/chat/ChatAdapter.kt` — message rendering (Markwon markdown,
  selectable text, copy/edit/speak buttons, bulk select).
- `service/HandsFreeService.kt` — microphone-typed foreground service for
  screen-off hands-free conversation (wake lock + notification). Its
  notification carries a **Hang Up** action (see below).
- `service/GenerationForegroundService.kt` — **mediaPlayback**-typed foreground
  service (was dataSync; switched so it can legitimately span audio readback,
  and to dodge Android 15+'s daily dataSync cap — manifest type + the
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission must stay in sync with the
  runtime type or `startForeground` throws on 14+). Ref-counted via
  `begin()`/`end()`. It is held for the response stream (begin/end in the
  `try`/`finally` of the `generateResponse` funnel) **and** extended across the
  plain (non-hands-free) read-aloud that follows: `ChatActivity`'s
  `acquireReadbackKeepAlive()`/`releaseReadbackKeepAlive()` add a second ref
  driven by real playback state (`tts.isSpeaking`/`mediaPlayer.isPlaying`) plus
  a hard timeout, so leaving the app mid-readback no longer freezes the process
  and cuts the reply off. Hands-free read-aloud is already covered by
  `HandsFreeService`, so the readback keep-alive is skipped there (no second
  bar). Both service notifications expose a **Hang Up** action that broadcasts
  `ChatActivity.ACTION_HANG_UP` (package-scoped, non-exported; `hangUpReceiver`
  registered for the activity's whole life so it fires while backgrounded) →
  runs `cancelAllAiActivity()` (the same teardown as the in-app stop control:
  stops readback + listening).
- `stt/` — on-device speech: `LocalWhisperEngine/Native` (whisper.cpp JNI),
  `WebRtcVadNative` + `VoiceActivityDetector` (energy VAD + libfvad), model
  download/storage. Native sources in `app/src/main/cpp/`.
- `preferences/` — all persistence (see Storage below).
- `preferences/lorebook/LoreBookStore.kt` — the lorebook SQLite database.
- `preferences/memory/` — the companion memory store (SQLCipher, Phase 1 of
  `memory-system-integration-plan.md`): store, seed/export codec, key
  management, persona→companion sync, backup exporter.
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
     One SharedPreferences file per chat (`settings.<chatId>`), plus global
     fallbacks. Includes the chat's checked lorebooks (`active_lorebook_ids`,
     comma-separated) and one-shot seed flags.
   - `ChatPreferences.kt` — chat list + message history (JSON in prefs).
   - **Chat content is encrypted at rest** (owner-requested): the chat list,
     per-chat message history (`chat_<id>`) and per-chat settings
     (`settings.<id>`) all go through `SecurePrefs.get(context, name)`, which
     wraps `EncryptedSharedPreferences` files named `enc.<name>` and migrates
     the old plaintext file on first access (copy → verify → clear, plaintext
     kept on any failure). NEVER call `context.getSharedPreferences` directly
     for these names — data would silently split between the plaintext and
     encrypted files. The global `settings` file (shared with
     `GlobalPreferences`) stays plaintext.
   - `PersonaPreferences.kt` — personas, flat keys `<personaId>_<field>` where
     `personaId = Hash.hash(label)`. **Renaming a persona changes its id**
     (edit = delete + recreate); lorebook links survive only because the edit
     dialog passes every field through. Keep that invariant.
   - `ApiEndpointPreferences` (encrypted), `ActivationPromptPreferences`,
     `LogitBiasPreferences`/`LogitBiasConfigPreferences`,
     `FavoriteModelsPreferences`, `GlobalPreferences`, `EncryptedPreferences`
     (androidx security-crypto for API keys).
2. **SQLite (SQLCipher)** — `lorebook.db` via `LoreBookStore` (singleton
   over `net.zetetic:sqlcipher-android`). Legacy plaintext databases are
   encrypted in place on first open by `LoreBookEncryption`
   (sqlcipher_export → verify → swap; on ANY failure the plaintext file keeps
   working with an empty password and the migration retries next start —
   lorebooks must never stop working over encryption).
   Schema v3: `lorebooks` (id/name/description/tag/timestamps),
   `memory_entries` (id/lorebook_id/label/content/source_text/enabled/timestamps),
   `memory_triggers` (FK → entries, ON DELETE CASCADE). Migrations are
   additive `ALTER TABLE` steps in `onUpgrade` — always bump
   `DATABASE_VERSION`, never edit old migration blocks. The full memory
   system does NOT extend this database (superseded assumption — see below);
   lorebooks stay the independent low-RAM tier. `ChatActivity`'s lorebook
   call sites (injection + new-chat seeding) are try/catch-guarded so a
   key/store failure degrades to "no lore this turn", never a crash.
3. **SQLCipher** — `companion_memory.db` via `preferences/memory/MemoryStore`
   (singleton over `net.zetetic:sqlcipher-android`; random 32-byte key per
   database in `EncryptedPreferences`, minted by `DatabaseKeys` — which NEVER
   mints a new key when the DB file already exists, that would brick the store).
   Schema v1.11 from `Memory System/sqlite_table_plan.md` (companions,
   memories + protection/provenance, entities, modes, directives, worlds,
   user_personas, roleplay_characters, transcripts, proposals, change_log,
   embeddings sidecar, deleted_ids tombstones; deviations documented in the
   MemoryStore header). DB v2 (July 2026) adds a machine-readable `origin`
   column ('user' default; 'archivist' reserved for Phase 6 proposals) on
   memories, companions, entities, modes and directives, so later phases can
   tell user records from archivist-proposed ones. DB v3 (July 2026, Phase 5)
   adds the **Campaign** (roleplay continuity) layer: a `campaigns` table
   (world/roleplay-character/companion FKs + status + Archivist-maintained
   `story_so_far`) and a nullable `memories.campaign_id`. Ordinary
   conversation never retrieves campaign-scoped rows —
   `activeMemoriesForScope(companionId, worldId, campaignId=null)` gained a
   `campaignId` param (null ⇒ campaign rows invisible; set ⇒ that campaign's
   state joins) and `Librarian.search` threads it through. MemoryStore also
   grew the Phase-5 hand-editor CRUD (per-record upsert/delete with
   `deleted_ids` tombstones; memory edits snapshot prior state into
   `change_log` and drop stale embeddings so a rebuild re-embeds). **The app ships and
   auto-loads NO seed/example memory data** — a fresh store starts empty and
   fills only from real conversations, persona-bootstrapped companions, and
   the user's own imported backups (owner decision July 2026, after a bundled
   example companion caused confusion; the old "Load starter template" button,
   the seed-purge button, the seed-testing switch and the bundled
   `memory_seed_template.json` were all removed). `activeMemoriesForScope` is
   the SINGLE eligibility gate Phase 4 injection must also consume: active
   status, scope match, and the companion-scoped branch requires the companion
   to be past 'draft' (an unapproved companion's memories never inject — this
   is the real protection). The Librarian applies a min-similarity floor
   (0.30) so top-k can't surface weak matches from a small store; debug-search
   labels show status/origin/provenance and include non-active memories.
   Created lazily — `MemoryStore.isProvisioned()` gates
   every hook so nothing provisions it as a side effect. Backups are
   schema-shaped JSON via `MemorySeedCodec` (unit-tested round-trip against an
   inline fixture); `MemoryExporter` writes rotating daily backups at app
   start + manual SAF export (chats ride along under `app_chats`; embeddings
   never exported). **Import** (SAF file picker → the encrypted store) restores
   the user's own exported file. **Companion records are automatic** (owner
   requirement, July 2026): the app's personas ARE the companions the user
   sees; the store's `companions` table is the memory system's continuity
   file for each persona (`app_character_id` = `Hash.hash(label)`, stable
   `companion_id` survives renames). A chat with a persona must ALWAYS
   resolve to a companion — `MemoryCompanionSync.ensureCompanionForPersona`
   creates the record on first contact (called from `TranscriptRecorder` and
   the enforcer), the full bootstrap runs automatically when the Memory
   engine is switched to "full" (tier-2 enable, per the plan), and the
   manual bootstrap button in Memory settings remains as a re-run.
   `companion=none` in capture logs is legitimate ONLY for chats with no
   persona selected or a stale persona id. The Archivist (Phase 6) maintains
   memories INSIDE companions; it never creates the companion itself — the
   record must exist as the scope anchor before anything can be filed under
   it. Persona edits sync one-way into linked companion records via the hook
   in `PersonaPreferences` (`MemoryCompanionSync`) — renames re-point
   `app_character_id` under the OLD id; keep that when touching persona save
   paths. UI: "Memory system" tile in the Characters hub →
   `MemorySettingsActivity` (status, import backup, export, persona bootstrap).
4. **Files** — images in `getExternalFilesDir("images")`, whisper models via
   `LocalWhisperStorage`, rotating memory backups in
   `getExternalFilesDir("memory_backups")`.

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
  error/done chimes (plus a distinct low `playNoSpeechSignal` two-tone when the
  loop gives up on its own — heard nothing / couldn't capture / recognizer died
  after retries; gated on the error-sound pref, played from `stopHandsFreeLoop`'s
  `notify` flag). Device-TTS readback failures funnel through
  `handleTtsReadbackError`: it logs the *factual* failure state (error code+name,
  text length vs `getMaxSpeechInputLength`, engine, voice, language) via
  `logVoiceEventAlways` (persisted even with VAD logging off) and caps consecutive
  re-inits at `TTS_MAX_ERROR_RETRIES` (3) — a reply the engine keeps rejecting
  (e.g. ERROR_INVALID_REQUEST/-8) used to re-init the engine forever and flood
  the Event log; now it gives up on that one readback and the loop continues. The
  budget resets on a clean `onDone` and at each new readback (`pronounce`,
  `onSpeakClick`). screen-off operation via foreground services (the bar with
  a **Hang Up** button — see `GenerationForegroundService`/`HandsFreeService`
  above; plain read-aloud now survives app-switch/screen-off via the readback
  keep-alive). The Event (Voice Debug) log is **never wiped on startup** —
  `MainApplication` used to call `Logger.clearEventLog` in `onCreate`, which
  Android reran on every process recreation and silently erased the log between
  sessions; clearing is user-driven only (the button in `LogsActivity`). On
  start `MainApplication` instead calls `Logger.logLastExitReason`, which uses
  `ActivityManager.getHistoricalProcessExitReasons` (API 30+) to record *why
  the previous process died* (low memory / force-stop / crash / ANR vs. clean
  exit) — a hard kill runs no code on the way out, so this after-the-fact query
  is the only trace a screen-off readback killed mid-sentence leaves; deduped by
  exit timestamp.
  Voice diagnostics: with any VAD-logging toggle on (Energy, WebRTC or Silero
  — each detector has its own toggle, in the Audio Debugging screen), every
  loop decision (mic open/close + why, readback, failures, loop stop reasons)
  is written to the persistent Event log via `ChatActivity.logVoiceEvent` —
  when adding a new loop exit path, log it there or failures become
  undiagnosable. The per-turn VAD diagnostics line (`logVadDiagnostics`)
  follows the same toggles (`voiceDiagnosticsEnabled()`), so logging off means
  no VadDiag spam. `Logger` is local-only (no telemetry); it must not be gated
  on the installation id.
  Advanced Voice Settings screen (`VoiceAdvancedSettingsActivity`, plain
  rows not tiles, reached from a full-width tile in Voice settings — the VAD
  *logging* toggles are NOT here, they're in Audio Debugging): VAD
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
  to the Audio Debugging screen.) The VAD energy gate exists because WebRTC
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
- Companion memory store, Phases 1–2 (storage + capture): encrypted `companion_memory.db`,
  user backup import (own exported file, no bundled example data), rotating +
  manual JSON export, persona→companion bootstrap and edit-sync (surface:
  Characters → Memory system). Every completed turn is captured into the transcripts queue from
  the `finally` of `generateResponse` (single funnel; best-effort, never
  disturbs a turn) via `TranscriptRecorder`. Quick Settings has two per-chat
  memory controls (both in the auto-naming copy block): "Use memory" (kill
  switch — still captures, marks rows excluded; global default in Memory
  settings) and "Don't archive" (stops capture entirely). Chat renames must
  re-point `transcripts.chat_id` (`MemoryStore.repointChat`, hooked in
  auto-naming and `ChatPreferences.editChat`). Chat-list rows show a memory
  review marker.
  Phase 3 (librarian) adds on-device semantic retrieval in
  `preferences/memory/librarian/`: a swappable `EmbeddingModel` (default
  EmbeddingGemma-300M ONNX **q4**; int8 optional, never the default), model
  catalog/download/storage cloned from the Whisper pattern, `VectorMath` +
  `Librarian` (brute-force cosine top-k, scope isolation in the SQL query,
  retrieval_policy-weighted scoring, tentative dampening, keyword fallback,
  rebuild-index + model-tag-mismatch detection). Managed from the "Librarian"
  section of Memory settings (download models, rebuild index, debug search).
  A model is a directory of files: transformer ONNX, any ONNX external-data
  companions (catalog `ExtraFile` — q4's weights live in `model_q4.onnx_data`,
  referenced by that exact name from inside the graph), and `tokenizer.json`;
  `isInstalled` requires every non-optional file, and the downloader skips
  already-complete files (partial installs repair by re-tapping Download).
  Tokenization is **pure Kotlin** (`HfTokenizer`, unit-tested): each model
  download fetches the repo's own `tokenizer.json` and it's parsed/encoded
  on-device (BPE+byte-fallback for Gemma, Unigram for a future BGE-M3 —
  model-specific prompt prefixes/pooling/dims are catalog fields, so a new
  model is a catalog entry, not code). The app must NEVER bundle or
  redistribute a Gemma-derived tokenizer artifact (owner decision July 2026 —
  release builds auto-publish to GitHub, which would make bundling a
  redistribution); do not add ONNX Runtime Extensions back for this.
  Safety net, in order: unknown tokenizer.json constructs throw at load;
  `OnnxEmbeddingModel` probes tensor names defensively; and the Librarian
  runs a one-time **semantic self-check** per installed model
  (related-vs-unrelated cosine ordering; pass marker `.selfcheck_ok` in the
  model dir, cleared on re-download) — any failure logs to MemoryLog and
  degrades to keyword search rather than indexing garbage vectors. Still
  needs on-device bring-up on the Pixel (URLs + real-graph tensor names) —
  see the Phase 3 note in the plan.
  Phase 4 (enforcer — memory now influences conversations) is built, in
  `preferences/memory/enforcer/`: a global **Memory engine** setting in
  Memory settings — none / lore books (default = classic behavior) / full
  (selectable only with an embedding model installed). At "full",
  `Enforcer.assembleTurn` builds ONE extra system message per turn on
  Dispatchers.IO in `regularGPTResponse`, after the stable first message
  (never reordered — prefix caching): model-adaptation note, companion hard
  limits, the **standing packet** (owner portrait + directives + always-load
  memories; the raw render serves the turn while a background call to the
  global **Archivist model** setting — endpoint profile + model name, shared
  with Phase 6 — compresses it into the store's meta cache), ≤2 detected
  **modes** (signal-embedding scores + keyword bonus; protective tie-break
  steady > emotional > presence; stickiness with exit phrases; suggested_mode
  from retrieved protected memories), retrieved memories with provenance
  markers and inline HANDLE WITH CARE handling (one render function —
  structurally inseparable), entity summaries, the lorebook matches rendered
  INSIDE this message as "hand-written notes" that outrank memories
  (near-duplicate memories suppressed; pairs flagged to meta
  `enforcer.contradiction_flags` for Phase 6's run report), and the scene
  from three per-chat Quick Settings selectors (world / roleplay character /
  user persona — ALL in the auto-naming copy block). Operating defaults
  (retrieval policy + five origin='system' modes) provision only into EMPTY
  tables. ANY enforcer failure degrades to the classic lorebook message plus
  one soft toast per process — never blocks generation. Tier "none" disables
  lorebook injection too. The lorebook debug screen also renders the
  enforcer's per-turn `AssemblyLog` (injected/cut + why, scores, packet
  source, modes). Pure logic (PromptAssembler, ModeSelection, NearDuplicate)
  is unit-tested. Read `memory-system-integration-plan.md` before
  touching `preferences/memory/`.
  Phase 5 (the hand-editor UI) is built in `ui/activities/memory/`: a
  **Memory manager** hub (`MemoryManagerActivity`, opened from a "Browse &
  edit" button in Memory settings) over ten areas, all on a shared framework
  — `MemoryScreenActivity` (abstract themed list scaffold) + `MemoryRowAdapter`
  + `activity_memory_list`/`view_memory_row`. Areas: Memories browser/editor
  (search/add/edit/protect/archive/delete + per-memory change-log, openable
  scoped to a world/campaign/roleplay-character), Companions list + detail
  (draft approve, participation, essence/hard-limits — **"Personas" is renamed
  "Companions" in UI strings**; the app's Personas tile still opens the persona
  editor, the memory-side companion view lives in the hub), My Personas,
  Roleplay Characters (arc read-only), Worlds + Campaigns (pages, scoped memory
  browsers, archive/delete teardown), Entities/Modes/Directives editors, and an
  Owner-profile form. All CRUD is in `MemoryStore` (per-record upsert/delete
  with tombstones; memory edits log prior state + drop stale vectors). The
  detail pages are plain `FragmentActivity` forms (copying MemorySettings'
  theme boilerplate); the list screens subclass `MemoryScreenActivity` and only
  supply data + row actions. Follow-ups deferred (see the plan's Phase 5 note):
  merge tooling, an abilities/spells column, and campaign→Quick-Settings live
  wiring.
- Markdown/LaTeX rendering, partial text selection, message edit/delete/copy/
  share, bulk select, image attach + DALL·E-style generation, in-app
  translator, playground, logit bias editor, AMOLED theme, onboarding flow.
- Debug menus are split by **audio vs non-audio** (owner's mental model: all
  audio settings live together under Voice; everything else stays with the error
  logs). Two screens:
  - **Audio Debugging** (`AudioDebuggingActivity`, plain rows) — reached from a
    **Voice Debugging** tile at the bottom of Voice & Speech (the sibling tile is
    **Advanced Voice Settings** = `VoiceAdvancedSettingsActivity`), *and* from a
    shortcut row in the Alerts/Errors/Logs screen. Holds everything
    microphone/voice: the **transcription-finished chime** (moved here from a
    tile in Voice & Speech), the **Energy / WebRTC / Silero** VAD-logging
    switches (each detector its own toggle), and the **Audio Health** switch.
  - **Alerts, Errors & Logs** (`AlertDebugMenuActivity`, plain rows; was "Alert &
    Debug") — the single full-width tile under Settings → Debug. Holds the
    NON-audio items: **Show chat errors** and **Sound alert for model errors**
    switches; a **→ Audio Debugging** shortcut row (so someone hunting for VAD
    logging here still finds it); then **Crash log** / **Event log** as "label ›"
    rows that open `LogsActivity`.
  All toggles are global prefs; the logs are local-only and intentionally always
  reachable (not gated on the installation id) — don't reintroduce that gate when
  restyling. **Audio Health** is a separate hands-free diagnostic from VAD
  logging (`getAudioHealthLogging`): per-turn
  microphone input-health stats (frames, RMS/peak levels, near-silent/clipped
  counts, sample rate, channels, input route + mid-capture route changes) with
  plain-words hints, collected in `LocalWhisperEngine`'s capture loop
  (`AudioHealthMonitor`) and written to the Event log alongside any VAD line via
  `ChatActivity.logVadDiagnostics` (one combined entry when both are on).
  Two shortcuts make this loop fast to act on: the chat's top action bar shows a
  **bug icon** (`btn_debug_log`) whenever any of those diagnostics is on
  (`ChatActivity.updateDebugLogButtonVisibility`, re-checked in `onResume`) that
  opens the Event log directly; and the Event log itself (`LogsActivity`, only
  for `type == "event"`) shows a **terminal icon** (`btn_voice_advanced`, the
  same icon as the Advanced voice tile) that jumps to
  `VoiceAdvancedSettingsActivity` — see what's wrong, then go straight to the
  knobs that fix it, without walking back through Settings.
- **Microphone routing is Bluetooth-first** (`stt/MicRouteSelector.kt`): opening
  an `AudioRecord` with `AudioSource.MIC` does NOT capture from a Bluetooth
  headset on its own — Android only routes capture to a BT SCO mic when the app
  selects it as the communication device. So `LocalWhisperEngine.startRecording`
  now takes a `Context` and, when one is passed (both the push-to-talk and
  hands-free Whisper call sites do), picks a connected BT headset over the
  built-in mic via `AudioManager.setCommunicationDevice` (Android 12+/API 31;
  below that it leaves the OS default). Re-evaluated every turn, so a headset
  that connects or drops mid-conversation is honoured on the next turn; the
  routing is released (`clearMicRouting`) on capture abort/no-speech, on
  `stopHandsFreeLoop`, and on `release`, so the headset isn't left in call mode.
  `lastMicRouteDiagnostics` records the requested device plus the actual active
  input before/after the mic opens; `ChatActivity.logMicRoute` writes it to the
  Event log under the Audio Health / VAD-logging toggles. Device labels (with
  product name) come from the shared `MicRouteSelector.label`, used by both the
  mic-route line and `AudioHealthMonitor`.

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
  provider prefix caching; lorebook matches — or, at the full memory tier, the
  enforcer's single assembled message (which contains the lore notes) — go in
  a SEPARATE system message after it. Don't reorder or merge these, and never
  inject two competing memory messages (the enforcer path and the classic lore
  path are strictly either/or per turn).
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
- **The full Companion Memory System is planned and specified in the
  `Memory System/` folder (schema v1.11)**, with the phased integration plan
  in `memory-system-integration-plan.md` — read both before any memory work.
  It supersedes the older `Memory System Plans June 1 2026` roadmap from
  Phase 2 onward and changes one earlier assumption: the new memory store is
  a **separate SQLCipher database** (`companion_memory.db`), NOT an extension
  of `lorebook.db`. Lorebooks stay untouched as the independent low-RAM tier
  (user-authored lore outranks system memories at injection). All eleven
  package documents are present; `enforcer_librarian_spec.md` and
  `prompt_assembly_template.md` are the authoritative runtime/prompt specs.
- Whisper/voice work follows `whisper-local-plan.md`.

## Quick verification checklist before any push

1. `git status` clean of stray files; commit message says why.
2. All new `R.string/R.id/R.drawable/R.layout` references exist; new ids match
   the layout actually inflated.
3. Cross-cutting request changes go through the single generation funnel
   (`generateResponse` → `regularGPTResponse`); there is no second path.
4. New per-chat preference added to the auto-naming copy block.
5. DB change → version bump + additive migration + fresh-install path.
6. Push, then confirm the `Android Checks` workflow run for your commit is green.

# Phosphor Shines — AI Onboarding Manual

## Owner rules for AI conduct (July 10 2026)

- Always apologize sincerely when you make a mistake.
- Never tell the owner what they can or cannot do. Only speak in terms of what is or isn't technically possible in the code.
- Never tell the owner what you won't do, unless there is an actual technical or Terms of Service reason for it.

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

The owner-approved memory rules live in
**`Memory System/owner_approved_rules.md`** (approved word by word in chat,
July 6 2026). That file outranks every other memory-system document,
including the integration plan and the spec package. Read it before ANY
memory work; sections it marks deferred are not to be built.

This project is coded entirely by AI agents. This file is the onboarding
manual: read it before touching code, and **keep it updated** when you change
anything it describes (storage schema, feature list, fragile areas, workflow).
Stale onboarding docs are worse than none.

## ⛔ AI session rules — read before spawning agents or asking questions

- **Model tiers: Opus, Sonnet, and Haiku only. Never spawn a Fable model
  for this project, at any reasoning effort.** When delegating to a
  subagent or choosing a session model, pick the tier that matches the
  task's difficulty — Haiku for cheap/mechanical/well-specified work,
  Sonnet as the default for ordinary feature work, Opus for the hardest
  or most fragile-area work (anything touching the voice/VAD pipeline,
  encryption/migrations, or a genuine architecture decision). Don't
  default to the biggest model out of caution, and don't use a small
  model just to save cost on something fragile.
- **Never use the pop-up question tool (e.g. `AskUserQuestion`) in this
  project.** It errors on the owner's phone client and causes real
  problems for them. When a decision needs the owner's input — including
  anything the OWNER APPROVAL GATE above requires asking about — stop
  work and write a plain chat message asking the question, then wait for
  their reply as an ordinary conversation turn. Stopping and asking in
  chat is always fine; the pop-up mechanism specifically is not.

## ⚠️ OPEN OWNER PRIORITY (recorded July 10 2026): the voice pipeline is failing in daily use

The owner reports the voice system keeps erroring in real use. This is not
a routine bug: **voice is the owner's primary way of using this app** — when
it fails they are reduced to OS dictation that garbles their words. Fixing
this outranks all other feature work until the owner says otherwise.

Standing instructions for whichever session picks this up:

1. **Start by asking the owner, in a plain chat message, what the failure
   looks like in their own words** (cuts off mid-sentence? mic never
   re-arms? error chime? wrong or missing transcription?). Do not start
   from a guess. Do not assert causes you have not verified — the owner
   has caught agents doing this and it destroys trust.
2. Get the Event log contents (the app records every loop decision,
   failure and exit reason — see the voice diagnostics notes in this
   file). Work from that evidence.
3. The voice/VAD pipeline is the most fragile area in the codebase. Read
   the commit history of the files first. Make the smallest change that
   the evidence supports. No incidental refactors.
4. It is fixed ONLY when the owner confirms it working on their own phone
   via the test build. **Never report it done on any other basis.** A
   false "done" report happened before on UI work; the owner installed
   the app and discovered nothing had been changed. That must never
   happen again.

Status July 10 2026: a full pipeline read found five code-provable
defects, fixed in commit cde5f37 (branch
`claude/memory-update-phase-7-ss5u3t`): the per-turn slowdown
accumulation (a full-history encrypt per streamed chunk + a tokenizer
rebuilt per message, both on the main thread — this contradicted and
corrects the old "no per-turn accumulation" claim, which was true only
of LocalWhisperEngine itself), an ML Kit language-detector leak per
spoken reply, a hands-free strand (transcription throw → mic never
re-arms, no cue, BT route left up), and an uncaught cloud-voice
exception that killed the process mid-readback. **The owner has NOT
confirmed anything on-device; their symptom description and Event log
are still needed, and this priority stays OPEN until they say their
voice works.**

Update July 10 2026 (evening): the owner sent their symptom description
and Event log — screen-off hands-free (their normal daily mode) (a) cut
them off mid-sentence with no long pause, and (b) failed generation with
[N3] "Unable to resolve host api.z.ai" while the phone reported
`Network: none`. The log proves both mechanisms: (a) the mid-capture
built-in→Bluetooth-SCO route switch delivered ~5s of digital dead air
(11/27 Audio Health frames near-zero) which the VAD billed as user
silence → end-of-turn fired mid-sentence; (b) nothing held Wi-Fi awake
between turns (only GenerationForegroundService had a Wi-Fi lock, held
only during responses), so the radio slept during listening and the
next request died on DNS before Wi-Fi could wake. Fixed on branch
`claude/server-connectivity-screen-off-z0d66y`: HandsFreeService now
holds a session-long Wi-Fi lock, and the Whisper capture loop pauses
the VAD clocks on digital dead-air frames (peak ≤ 4, capped at 10s per
turn so a truly dead mic still times out) and resets the detector +
silence clock on a mid-capture input-route change. Still awaiting owner
on-device confirmation — the priority stays OPEN.

Update July 11 2026: new owner report — **"I can't stop it from reading
back to me."** Code read found the stop control was a facade whenever the
reply was still streaming: `cancelAllAiActivity()` (mic-tap stop + the
notification Hang Up) stopped the *audio* but cancelled NO generation
scope — and the typed-send path ran in an anonymous CoroutineScope nothing
could reach — so the stream quietly finished and the unguarded
`pronounce()` read the whole reply aloud after the user said stop. Three
smaller holes: `pendingSpeak` (an utterance parked behind a TTS re-init)
survived a stop and played afterwards; a stop landing inside pronounce's
async ML Kit hop lost the race to `speak()`; and tapping the speaker
button of the message currently being read RESTARTED it instead of
stopping it. Fixed on branch `claude/tts-playback-control-pcokcg`: stop
now cancels every generation scope (`killAllProcesses`, incl. the new
`parseMessageScope`), a `stopReadback()` helper owns the audio teardown +
clears `pendingSpeak`, a `readbackSession` stamp (bumped on every stop,
re-checked right before text reaches the engine) closes the async-hop
races, and the speaker button is now a read/stop toggle. **Then the owner
gave the decisive observation: "The button just stayed red. Like I wasn't
hitting it."** A stop tap that reaches ANY handler changes the button
(micIdle, or at minimum a state flip) — a tap with zero visual effect
means the MAIN THREAD WAS FROZEN and Android dropped the tap before the
app saw it, while the TTS engine (its own process) kept talking. The
freeze is code-provable: `calculateCost()` → `tokenizeArray()` BPE-encoded
the ENTIRE conversation history on Dispatchers.Main (plus an O(n²)
summation), once or twice per turn, exactly when the readback starts — and
the cost grows with every exchange, matching "it works worse than when I
started" a month in. Fixed: the encode + summation now run on
Dispatchers.Default over a snapshot; only field assignments touch Main.
(`saveSettings()`'s whole-history encrypt is still on Main — deliberately
untouched, write-ordering risk; the a625894 throttle covers streaming.)
**The stop-tap logging the owner never approved is fully REMOVED (July 11
2026): the line in `cancelAllAiActivity` is byte-identical to what existed
before that day's work.** An always-on version, then a "gated" version
with extra state detail, were both added without approval and the owner
ordered them out. Standing rule: never add logging of the owner's own
button presses — in any form, gated or not — without their explicit yes
in chat first.
The owner also described their stop flow ("it prints the whole reply,
then begins reading; stop should stop readback immediately and not open
the mic"), which exposed a fifth hole: in the silent gap between the reply printing
and the audio actually starting (ML Kit hop, engine spin-up, cloud-voice
fetch) — and during engines' mid-utterance isSpeaking=false blips —
`isAiCurrentlyBusy()` returned false, so the stop tap fell through to the
mic-toggle and OPENED THE MIC while the readback then spoke over it (in
hands-free the app could transcribe its own voice as the next turn).
Fixed: `isAiCurrentlyBusy()` now also counts a committed-but-not-yet-
audible readback (`handsFreeReadbackExpected` / `readbackKeepAliveActive`
/ `pendingSpeak` / the adapter's speaking position) as busy, so a tap in
the gap is a stop, never a mic-open. Awaiting owner on-device
confirmation — not done until they say so.

## App summary

Android voice/chat assistant (fork of TeslaSoft SpeakGPT, now independent —
all upstream TeslaSoft services were removed). Talks to any OpenAI-compatible
chat-completions endpoint (the owner uses z.ai GLM models via a custom base
URL). Heavy emphasis on **hands-free voice conversation**: on-device Whisper
transcription (native whisper.cpp), VAD-driven mic loop, TTS readback with the
screen off. Recent additions: a persona-scoped **lorebook memory system** and
a foreground service that keeps generation alive in the background.

The old **telemetry/installation-id system is gone** (July 2026): the Settings
"Privacy" section and its four tiles (Delete data, Revoke authorization, Usage
and diagnostics, Get new installation ID), `DeviceInfoProvider`, the
`installation_id`/`device_info` and `consent`/`usage` prefs, and
`Logger.deleteAllLogs` were all removed — they drove servers that no longer
exist. Nothing mints or reads an installation id anymore; logs stay purely
local and are cleared only from the Logs screen. The debug-only device-info
panel and the crash report now show just the Android ID (read inline via
`Settings.Secure.ANDROID_ID`), no installation id. Stale `device_info`/`consent`
files may linger on old devices, unread.

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
  screen-off hands-free conversation (wake lock + Wi-Fi lock + notification).
  The Wi-Fi lock spans the WHOLE session (added July 10 2026): the generation
  service's Wi-Fi lock only covers each response, so between turns — screen
  off, just listening — Android could put the Wi-Fi radio to sleep and the
  next request died on DNS ("Unable to resolve host", `Network: none`). Its
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
  runs `cancelAllAiActivity()` (the same teardown as the in-app stop control).
  **Stop semantics (July 11 2026): a stop cancels the still-streaming reply
  too**, not just the audio — `cancelAllAiActivity()` runs `killAllProcesses()`
  (all generation scopes, incl. `parseMessageScope` for typed sends) and then
  `stopReadback()` (TTS + MediaPlayer + cloud-voice scope + `pendingSpeak` +
  read-aloud keep-alive + the `readbackSession` bump that invalidates any
  speak() still in an async hop). Without the generation cancel, `pronounce()`
  — which runs unconditionally when the stream completes — read the whole
  reply aloud after the user said stop. Tapping a message's speaker button
  while that message is being read is a STOP (toggle), not a restart. Don't
  re-split these paths.
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
  checklist, sampling params, the memory-scene selectors, and the per-chat
  **Apply Model Rules** toggle).
- `ui/activities/CharactersActivity.kt` — hub of tiles: Personas, My Personas,
  Activation prompts. (The System message tile moved OUT to AI System Settings
  in Stage 4; the **Lorebooks** tile moved OUT to the Memory Manager, July 8
  2026 — see below.)
- `ui/activities/MemoryManagerActivity.kt` — the **Memory Manager** (July 8
  2026), reached from the renamed **Memory Manager** tile on the main Settings
  screen. A plain chevron-row hub (house style: rows, not cards) with five
  doors: **Memory Browser** → the global memories browser; **Memory Assistant**
  → `MemoryAssistantActivity` (the built Phase 6 Archivist surface); **Lorebooks**
  → `LoreBooksListActivity` (moved here out of Characters — the lorebook screens
  themselves are unchanged, only the access point moved); **Memory Controls**
  → `MemoryControlsActivity`; **Advanced Memory Settings** →
  `AdvancedMemorySettingsActivity` (its own row directly under Memory Controls —
  moved OUT of the Memory Controls screen, owner ruling July 9 2026). NOTE:
  this is a NEW, lightweight navigation hub —
  NOT the old ten-area `ui/activities/memory/MemoryManagerActivity`, which was
  removed in the Phase-5 rework and stays removed.
- **The old combined Memory Settings screen was SPLIT (owner spec, July 9
  2026 — `Memory System/memory_settings_reorg_spec.md`, wording verbatim;
  user-facing name is "Memory Assistant", never "Archivist"):**
  `ui/activities/MemoryControlsActivity.kt` (the normal-user page: memory
  defaults, the Memory Assistant section with the max-suggestions cap
  [defaults 10 when toggled on] and the §2 card-suggestions toggle, the
  Memory Engine picker, Memory Assistant endpoint/model, Backups, Reset at
  the bottom; the Advanced Memory Settings door is NO LONGER here — it moved
  up to its own row in the Memory Manager hub, directly under Memory Controls,
  owner ruling July 9 2026);
  `ui/activities/MemoryAssistantAdvancedSettingsActivity.kt` (extraction
  tuning: temperature 0.0–2.0 w/ Reset to Recommended 0.3, Minimum
  Importance, the editable Extraction Prompt with Reset Prompt);
  `ui/activities/AdvancedMemorySettingsActivity.kt` (diagnostics: store
  status/row counts, Create Companions From Personas, the Librarian
  embedding models/rebuild index, debug search — Reset Memories is
  deliberately NOT here, one home only). `MemorySettingsActivity` is
  deleted.
- `ui/activities/AiSystemSettingsActivity.kt` — the **AI System Settings**
  screen (Stage 4), a new card on the main Settings screen. Plain
  chevron-rows (not tiles): the chat's **System prompt** (moved here out of
  Characters; still opens the unchanged `SystemMessageDialogFragment`),
  **Model Specific Rules** (opens the model-rules browser), and the global
  **Automatically Apply Model Rules** default toggle, under a top mindfulness
  hint about rule length / per-turn token cost (owner-approved words).
- `ui/activities/memory/ModelRules*` — the Model rules manager (Stage 4, §11
  Revision 5): `ModelRulesActivity` (browser on the MemoryScreenActivity
  scaffold — filter/sort by sort/model/tag/status, a per-model size readout,
  a Pending banner that's empty until Phase 6), `ModelRuleEditorActivity`
  (full-screen: rule text + live char count, model-string chips, tag chips),
  `ModelRuleTagsActivity` / `ModelRuleTagViewActivity` (the tag index + the
  tap-a-tag cross view), `ModelRuleTagChips` (the model-rule tag input, its
  OWN pool — never touches roleplay or memory tags).
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
     kept on any failure; entries that differ between the two sides are
     preserved in an encrypted `<name>_conflict_<ts>` snapshot before the
     clear — never silently dropped). NEVER call `context.getSharedPreferences`
     directly for these names — data would silently split between the
     plaintext and encrypted files. The global `settings` file (shared with
     `GlobalPreferences`) stays plaintext.
   - **Keystore-outage state machine (silent-failure audit Round 4, July 12
     2026):** when an `enc.<name>` file EXISTS but cannot be opened (Keystore
     key unavailable — transient failure, cleared credentials, restore onto
     new hardware), the file is **LOCKED**, per file — never "empty".
     `SecurePrefs` classifies every open via the pure
     `ChatStorageHealth.classify` (HEALTHY / LOCKED / LEGACY_PLAINTEXT /
     FRESH_UNENCRYPTED; the old single plaintext fallback let locked data
     masquerade as an empty chat list and then destroyed outage-era writes at
     migration). While LOCKED, reads/writes are redirected to a separate
     `outage.<name>` plaintext file — the encrypted file is never read,
     written, cleared or migrated in that state — the lock is recorded in the
     plaintext `storage_health` journal (deliberately raw prefs: it must work
     while encryption is down; state metadata only, never chat content), and
     one line per process goes to the Error Log. On the next start with a
     working key, `SecurePrefs.reconcileOutageAtStartup` (ordered BEFORE
     `RenameJournal.reconcile` — rename recovery consults the chat list this
     pass may be restoring) merges outage files back via the pure, unit-tested
     `OutageReconciler`: copy-if-absent per key; the chat list merged
     structurally by id (outage-only chats appended; processed LAST so
     histories land before their list entries); the rename journal
     union-merged; and any real conflict (non-empty differing values,
     colliding chat ids) preserves the WHOLE outage copy as an encrypted
     `<name>_recovered_<ts>` snapshot while the encrypted side stays
     presented — neither side is ever silently chosen or deleted. Snapshot
     markers in `storage_health` make every re-run idempotent (restartable at
     every boundary; unit-tested in OutageReconcilerTest). A value that
     exists but fails to DECRYPT inside an otherwise-open file
     (`getChatList`/`getChatById`) preserves the ciphertext file byte-for-byte
     into `files/storage_recovery/` before returning empty and records a
     `readfail` journal entry — a later save can no longer destroy the only
     copy. `MemoryExporter` marks exports taken during degradation
     (`export_meta.app_chats_complete: false`, absent = complete) and
     suspends backup rotation so the last complete backups survive the
     outage. No orphan/cleanup pass may touch `outage.*` files,
     `files/storage_recovery/`, or `*_recovered_*`/`*_conflict_*` snapshots.
     Surfacing lock/read-fail states in UI is an OPEN owner wording decision —
     the states are queryable (`ChatStorageHealth.lockedNames` /
     `readFailureNames` / `anyChatDataDegraded`), nothing is shown yet.
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
   conversation never retrieves campaign-scoped rows (since Stage 3 the
   isolation lives in the seven-category eligibility query — see below).
   DB v4 (July 2026,
   Phase 5 Stage 2) restructures the memory record to the owner-approved rules:
   `memories.scope` now holds the **primary scope category** (global | real_life
   | companion | project | world | campaign | rp_character), `kind` is the
   **Type** (fact|preference|event|status|instruction|lore), `status` gains
   `draft`, and a new `projects` table (§4) with `memories.project_id`.
   Loosening the scope/status CHECK constraints can't be done with ALTER, so v4
   **rebuilds** the `memories` table with foreign keys disabled for the
   migration (`onConfigure` gates FK-off on the pending version, `onOpen`
   restores them) so the drop doesn't cascade-delete the children. The
   per-memory `always_load` flag is **retired** (§10): the column stays but
   nothing reads or writes it — nothing is ever always-injected.
   DB v5 makes the named target scopes **multi-select**
   (§2): join tables `memory_worlds`/`memory_campaigns`/
   `memory_roleplay_characters`/`memory_projects` mirror `memory_companions`, so
   a memory can belong to several worlds/campaigns/RP-characters/projects
   without being duplicated. Since Stage 3.1 the retrieval eligibility query
   reads these join tables too (a memory linked to several targets is eligible
   under each); the legacy single columns remain as a **primary-target mirror**
   (first selected), display-only. The scoped-browser doors read the join
   tables. The July 8 2026 teardown bug (delete/keep decided by the mirror
   column, so a multi-target memory could be wrongly hard-deleted) is **FIXED**
   (Phase 6, July 8 2026): `deleteWorld`/`deleteCampaign`/
   `deleteRoleplayCharacter`/`deleteProject` now run join-first through
   `teardownTargetMemoriesTx` + the pure `TargetTeardownPlanner` (unit-tested
   in `app/src/test` with the owner's required cases) — shared memories always
   survive with the dead link removed and the mirror reassigned to a surviving
   owner; "also delete this card's memories" removes only sole-owned ones;
   `keepCharacterMemories` reads the character JOIN, not the mirror. Spec +
   history in `Memory System/roleplay_memory_deletion_fix.md`.
   DB v6 (July 2026, Stage 3.3) adds the **freshness-cooldown** tables:
   `injection_cooldowns` keyed `(chat_id, source_type, entry_id)` — when each
   entry last reached a prompt, per chat, `source_type` separating memories
   (`memory`) from the Stage 3.6 card entries (`card_entry`) — and
   `chat_turn_counters`, the per-chat monotonic turn clock. Chat renames must
   carry BOTH (handled inside `MemoryStore.repointChat`); memory
   edit/status/delete paths clear the entry's cooldown rows so an edited
   memory re-injects fresh.
   DB v7 (July 2026, Stage 3.6a) adds the **roleplay card + tag layer**
   (`Memory System/roleplay_cards_and_tags_spec.md` — the authoritative spec;
   read it plus its §9 agent rules before touching any of this): Zone 1 card
   columns on the existing tables (`roleplay_characters` gains
   species/char_class/core_personality/physical_description/goals_drives;
   `campaigns` gains quest_anchor/active_scene — the "bookmark", user-written
   at session end; `worlds` gains cosmology and an 'archived' status via a
   FK-off table rebuild), a `party_members` NPC roster
   (four-state fiction `status` alive/incapacitated/dead/enemy + separate
   `archived` lifecycle flag) with a `campaign_party_members` join (link, not
   ownership), one polymorphic `card_entries` table for every card's Zone 2
   sections (section keys in `CardSections`; per-section fields as nullable
   columns; parent/overlay/promotion reference columns are deliberately
   FK-less — a dangling id + `deleted_ids` tombstone is how §5's "(deleted
   card)" rendering works), and the roleplay-realm tag pool `rp_tags`
   (per-tag `auto_trigger`, default ON) + polymorphic `rp_tag_links` reaching
   card entries, whole cards, and memories. THE REALM WALL IS STRUCTURAL:
   real-life memory tags stay in `memories.tags_json` and never enter the
   rp_tag tables. Card content lives in card tables, never in memories rows;
   card retrieval is trigger-matched, never embedded (embeddings stay
   memories-only). No automatic process writes any card/entry/tag
   mid-conversation (user-confirmed dialogs are user edits); nothing ships
   pre-populated. Backups (`MemorySeedCodec`/`exportData`/`importData`) carry
   the whole card layer; card teardowns scrub entries + tag links and
   `resetAllMemoryData` empties the new tables. The UI/wiring for all of
   this (Stage 3.6b–f) is BUILT — see the feature list. §5's archive is
   status-only (`archiveWorld`/`archiveCampaign` flip status, links stay
   intact; `restoreWorld` + a one-tap Restore row action on the list
   screens' visible Archive sections undo it); true deletion warns when
   campaigns link the card, offers archive instead, and asks per-deletion
   whether to delete the card's memories too.
   DB v8 (July 2026, pre-3.6b) adds FRESH `worlds.premise_vibe` +
   `worlds.magic_rules` columns for the world core: the owner ruled (spec
   §8a addendum) that the new cards must never show, map, or migrate the
   old free-text blocks — worlds' `premise`/`rules`, roleplay characters'
   `description`/`arc`/`played_by`, campaigns' `story_so_far` are all
   DORMANT (kept only so old backups import); no data is copied into the
   card fields. The §8a addendum also holds the approved 3.6b on-screen
   wording (Zone labels, the right-aligned word count with 300/500-word
   warnings, "Promote to Party Member") — use those words verbatim.
   DB v10 (July 2026, Stage 4, §11 Revision 5) is the **Model rules** layer.
   §11 was redesigned in chat: the profile/group concept (a short-lived DB v9,
   never shipped with a way to hold data) is REPLACED by a model-string-
   primary model with tags. `model_rules` carries its own `model_strings_json`
   (the models a rule applies to) and loses `profile_id`; `model_rule_profiles`
   is dropped; `model_rule_tags` + `model_rule_tag_links` are a SEPARATE tag
   pool (plain labels, no colors — never the roleplay or memory tag realms).
   Injection matches by the chat's model string (case-insensitive contains,
   provider prefix ignored — `enforcer/ModelRuleMatcher`, unit-tested), renders
   its OWN prompt-layer block, is ON by default and gated by a global
   `getAutoApplyModelRules()` default + a per-chat `getChatApplyModelRules()`
   override (in the auto-naming copy block). Matching rules are NEVER truncated
   (§11). `status='draft'` rows are Phase-6 Archivist suggestions (the Pending
   UI is built but stays empty until Phase 6). Backups/codec carry rules, tags,
   and links. UI lives under AI System Settings (see the architecture map).
   DB v11 (July 2026, Phase 6) adds `archivist_runs` — the Archivist run
   history behind the Memory Assistant's "Recent Memory Analysis" list and its
   Rerun action (per-run: dates, status, chat/transcript ids fed, memory/rule
   draft ids created, failed chats). Device-local operational data like the
   embeddings: never exported, no tombstones, emptied by Reset memories. The
   **Archivist run engine** (backend only, `preferences/memory/archivist/`) is
   BUILT: `Archivist.analyze`/`rerun` read the eligible pending transcripts
   (live query — deleted chats filtered against the app's chat list,
   re-included chats reappear automatically), call the configured Archivist
   endpoint/model, and file every finding as a DRAFT — memory drafts via
   `insertArchivistDraftMemory` (enforces status='draft' + origin='archivist',
   never writes protection; lands in the existing Pending screen) and
   model-rule drafts via `upsertModelRule` (status='draft',
   source_model_string). `ArchivistResponseParser` (pure, unit-tested) is the
   validation gate: unknown scope/type rows are dropped and counted (never
   coerced), handling fields ignored, floods bounded. Proposed target NAMES
   only link to records that already exist (exact name match) — the Archivist
   never creates worlds/campaigns/characters/projects. **DB v13 (July 9
   2026) adds card-placement SUGGESTIONS**: roleplay drafts may carry
   `memories.suggested_card_type/_id/_section` (draft-only, FK-less, never
   exported, cleared on any status change) — the Archivist proposes a card +
   section by NAME (resolved against existing live cards only; section
   validated per card type), gated by `getArchivistCardSuggestions()` (ON by
   default — the owner's card-append toggle; its UI ships with Memory
   Controls). Suggestions pre-select the Add-to-Card and editor Link
   dropdowns and give pending rows the §7 outline (`bg_suggestion_outline`).
   **DB v14 (July 9 2026) adds `rejected_drafts`** (owner preference):
   deleting a Memory Assistant DRAFT records its exact title+content hash +
   source conversation, and a rerun will not refile that exact draft from
   that conversation — deliberately narrow, never broad similarity
   suppression (owner rule). Device-local, never exported, emptied by Reset
   memories. **Phase 6 scope limits (owner rulings, July 9 2026 — final):**
   the Memory Assistant NEVER creates or proposes worlds/campaigns from
   emergence — roleplay content with no attached target files as an
   untargeted pending draft carrying the persistent inline note "Needs
   roleplay target." (it cannot be added to a card until the user assigns a
   world/campaign/character target in the editor; Add to Card is hidden and
   the editor's Link section gated until then). The Memory Assistant NEVER
   automatically updates campaign story_so_far or Plot Ledger fields — those
   columns are DEAD legacy and must never be revived or referenced as
   current (owner ruling; card-section placement SUGGESTIONS, user-approved,
   including to plot_ledger, are the only sanctioned path). The full memory
   engine requires an embedding model installed through Advanced Memory
   Settings — refusing the switch shows the owner-worded guidance INLINE
   under the Memory Engine control (persistent; the app-wide toast ban
   applies).
   The Memory Assistant tuning prefs (July 9 spec,
   `memory_settings_reorg_spec.md`): max suggestions per conversation +
   minimum importance (both ENFORCED IN CODE in the runner), temperature
   (default 0.3), custom extraction prompt ("" = built-in). The **Memory
   Assistant screen is BUILT** (July 8 2026 evening) to the owner's approved
   wording (`memory_assistant_design.md` + `phase6_owner_answers_2026-07-08.md`)
   and drives this engine: facts block, Analyze Conversations with live
   batch-aware progress, View Pending Memories → the browser filtered to
   drafts, Recent Memory Analysis (5 rows, Rerun far right). DB v12 adds
   `archivist_runs.outcome`/`failure_reason`; run status/failure display
   implements **`Memory System/archivist_status_wording_spec.md`** verbatim
   (owner-sanctioned wording with a no-approval dispensation for tone-matched
   gaps — unique to that spec): not-ready above the disabled button,
   full/partial-failure reasons A–G via `ArchivistFailure` (mapped through
   `GenerationErrorClassifier`), Nothing To Extract / No New Memories Added /
   Run Interrupted states, and the "Some Memories Deleted Later" run-row
   badge. Archivist failure/partial-failure records ALWAYS write to the
   Memory log via `MemoryLog.logAlways` (owner rule — never gate them on the
   diagnostics toggle).
   Source is DERIVED for
   display (`provenance_source == "user_entered"` ⇒ "Entered by hand", else
   "Learned from chat"); there is no "Imported" bucket — import preserves each
   row's original source (owner decision). MemoryStore also
   grew the Phase-5 hand-editor CRUD (per-record upsert/delete with
   `deleted_ids` tombstones; memory edits snapshot prior state into
   `change_log` and drop stale embeddings so a rebuild re-embeds). **The app ships and
   auto-loads NO seed/example memory data** — a fresh store starts empty and
   fills only from real conversations, persona-bootstrapped companions, and
   the user's own imported backups (owner decision July 2026, after a bundled
   example companion caused confusion; the old "Load starter template" button,
   the seed-purge button, the seed-testing switch and the bundled
   `memory_seed_template.json` were all removed). `activeMemoriesForScope`
   (now taking a `RetrievalScope`) is the SINGLE eligibility gate injection
   consumes, rewritten in Stage 3.1 to the owner's seven-category model:
   ordinary chat sees global + real-life + the active companion's memories +
   ALL project memories (project selection boosts ranking, never gates);
   roleplay context (any of world/campaign/RP-character selected) sees global
   plus the SELECTED targets' memories, real-life and project memories are
   BLOCKED (the fiction wall, §3), and companion memories enter roleplay only
   via the narrator/GM match (the selected campaign's GM companion == the
   chat's active companion) or the global "Allow active companion memories in
   roleplay" switch in Memory settings (default OFF). Only status='active'
   rows are ever eligible, and the companion-scoped branch requires the
   companion to be past 'draft' (an unapproved companion's memories never
   inject — this is the real protection). Ranking (Stage 3.2, §12) blends
   scope-specificity boosts (campaign → RP character → world → project →
   companion → real life → global), a selected-project boost and capped tag
   hints into the relevance score — a strong preference among comparably
   relevant entries, never a trump card (§12.4). The Librarian applies a
   min-similarity floor
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
   paths. UI: the **Memory Manager** tile on the main Settings screen (renamed
   from "Memory System", July 8 2026) → `MemoryManagerActivity` (the row hub)
   → **Memory Controls** row → `MemoryControlsActivity` (defaults, assistant
   controls, engine, endpoint/model, backups, reset); the hub's separate
   **Advanced Memory Settings** row (directly under Memory Controls) →
   `AdvancedMemorySettingsActivity` holds diagnostics + bootstrap + librarian
   — July 9 2026 split, see the architecture map.
4. **Files** — images in `getExternalFilesDir("images")`, whisper models via
   `LocalWhisperStorage`, rotating memory backups in
   `getExternalFilesDir("memory_backups")`.

## Current feature list

- Multi-chat with per-chat settings (model, endpoint, sampling, persona, …),
  auto-naming of new chats (which **changes the chat id**). Renames — auto or
  manual — go through `ChatPreferences.editChat` → `ChatRenameTransaction`
  (July 11 2026): the message history and the WHOLE per-chat settings file
  are copied wholesale (write-new → verify → flip the chat-list pointer →
  clear old, all synchronous commits), so a new per-chat setting survives
  renames automatically — the old hand-maintained copy blocks in
  `ChatActivity` and `AddChatDialogFragment` are GONE (they had drifted: one
  dropped persona/lorebooks/memory scene on manual rename, the other reset
  the voice settings on auto-name, and the manual one re-derived per-chat
  tuning from the endpoint profile). When you add a per-chat key, register
  it in `PerChatSettingKeys` — `PerChatSettingKeysTest` scans
  `Preferences.kt` and fails CI when the inventory drifts. `editChat`
  returns false when the rename could not be fully applied (the chat is then
  untouched under its old name and callers keep the old id); auto-naming
  retries on later turns (max 3 attempts per screen instance) instead of
  giving up after one failure. Older doc mentions of "the auto-naming copy
  block" mean this mechanism now.
  `editChat` runs **off the main thread** at both call sites (manual rename on
  the host activity's `lifecycleScope`, auto-naming via `withContext(IO)`),
  returning to Main only for UI adoption and only if the screen is still alive;
  a per-caller guard prevents overlapping rename submissions. The cross-store
  memory re-point is made durable by a small **rename journal**
  (`RenameJournal`, encrypted prefs, outside the memory DB): `editChat` records
  the pending (oldId→newId) before touching prefs and clears it once
  `repointChat` succeeds; the prefs pointer flip is the authoritative moment,
  so if the process dies or SQLCipher fails in between, `MainApplication`'s
  startup thread calls `RenameJournal.reconcile` — which consults the live chat
  list to decide whether to finish the re-point (new id live, old gone),
  discard (old still live = rename never took; both live = old id reused;
  neither = chat deleted). `repointChat` is one atomic, idempotent DB
  transaction, so recovery can retry safely; a repeatedly-failing re-point
  keeps its journal entry and retries each start while the chat stays usable
  and pre-rename transcripts stay preserved under the old id. Any future
  orphan-row pruning MUST check `RenameJournal.hasPending` first. The pure
  reconcile decision (`RenameJournal.planReconcile`) is unit-tested at every
  boundary.
  Auto-naming adopts the new id **in place** — it must never relaunch
  ChatActivity, because onDestroy kills the readback and hands-free loop.
- **Streamed-reply completion state (Round 3, July 11 2026).** A streamed
  assistant reply is persisted incrementally, so a partial reply on disk was
  byte-identical to a finished one — a process kill / activity destroy / stop
  / error could reopen a fragment looking complete, and export, transcript
  capture, the Archivist and RAG all trusted it. Assistant message maps now
  carry an optional `state` key (`preferences/MessageCompletionState.kt`, pure
  + unit-tested): **absent = complete** (every legacy/older-build message —
  historical replies never suddenly look incomplete), `done` = complete, and
  `streaming`/`stopped`/`failed`/`interrupted` (plus any unrecognized value)
  are treated as NOT complete and preserved as-is, never silently upgraded.
  State travels in the SAME JSON blob as the text (`saveSettings`), so text
  and marker are atomic — there is no window where the text is final but the
  flag is stale. The placeholder is tagged `streaming`; each of the three
  streaming completion points sets `done`; the `generateResponse`
  CancellationException catch sets `stopped` (user stop, screen alive) or
  `interrupted` (`isFinishing`/`isDestroyed` — activity torn down); the
  Exception catch sets `failed` (+ classifier code in `stateDetail`). A hard
  process kill runs no code, so `initSettings` has a **load-time reconciler**
  that turns any stale `streaming` row into `interrupted` (idempotent) — do
  NOT rely on the process-exit log for this. `finalizeStreamingMessageState`
  only stamps a still-`streaming` row, never downgrades an already-terminal
  one. The design deliberately does NOT add a synchronous-commit save-queue
  (owner call): a lost terminal write at worst mislabels a complete reply as
  interrupted (safe direction, visible), never the reverse. Downstream: an
  unfinished reply keeps its partial text everywhere (nothing received is
  deleted), but gets an INTERNAL model-only note (`modelFacingContent`, never
  shown) so the model can't mistake it for finished; transcript capture marks
  the assistant turn `"complete": false` (absent = complete) and
  `ArchivistPrompt.renderTurns` drops `complete:false` assistant turns (the
  user's own turn beside it stays) so a fragment is never mined as fact.
  **Error prose is no longer appended into the reply text** — a `failed`
  reply's coded error lives in `errorText` and renders next to the inline
  marker (a small persistent line in the bot/classic layouts, `status_marker`;
  no toast/dialog/notification/sound) only when "Show chat errors" is on.
  **Retry replaces** the incomplete reply (unchanged); **editing** an
  incomplete reply clears the state to `done` (in-memory in `ChatAdapter` and
  on disk in `ChatPreferences.editMessage`). Marker wording lives in
  `strings.xml` (`message_state_*`) and is owner-approved copy — do not reword
  without asking.
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
  no VadDiag spam. `Logger` is local-only (no telemetry); it must never be
  gated on any consent/telemetry flag (the old installation-id gate, and the
  whole installation-id/consent system, were removed July 2026).
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
  funnels — since July 11 2026 the engine's accept/reject result is CHECKED
  (`TtsTuningPolicy`, unit-tested): a rejected application is re-applied
  exactly once ~750 ms after init and rejection/fallback is Event-logged
  ungated, success never — the Google engine can drop a setSpeechRate made
  at the instant init completes, which made whole sessions read back at the
  default rate ("suddenly talks faster"); the system-wide Accessibility
  speech rate multiplies the app's and is external/undetectable).
  (The voice-diagnostics logging toggles that used to live here moved
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
  Settings → Memory Manager → Memory Settings). Every completed turn is captured into the transcripts queue from
  the `finally` of `generateResponse` (single funnel; best-effort, never
  disturbs a turn) via `TranscriptRecorder`. Quick Settings has three per-chat
  memory controls (all in the auto-naming copy block): "Use memory" (kill
  switch — still captures, marks rows excluded; unset chats default from the
  global engine tier + the Memory settings default toggle), "Use lore books"
  (independent lore switch, July 10 2026 — see the Quick-Settings-is-God
  ruling in the Phase 4 section) and "Don't archive" (stops capture
  entirely). Chat renames must
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
  `preferences/memory/enforcer/`, and was **reworked by Stage 3.4 to the
  owner-approved rules**: a global **Memory engine** setting in
  Memory settings — none / lore books (default = classic behavior) / full
  (selectable only with an embedding model installed). **QUICK SETTINGS IS
  GOD (owner ruling, July 10 2026):** the engine picker only supplies the
  DEFAULTS; each chat has two independent Quick Settings switches — "Use
  memory in this chat" and "Use lore books in this chat" (tri-state prefs
  `memory_enabled` / `lorebooks_enabled`, both in the auto-naming copy
  block) — and an explicit per-chat switch always wins over the engine
  tier. Unset chats derive: memory ON iff engine=="full" (× the Memory
  settings default toggle); lore ON iff engine!="none". The injection
  gates in `regularGPTResponse` read ONLY the per-chat getters; the scene
  selectors in Quick Settings follow the chat's memory switch, not the
  engine. When memory injects,
  `Enforcer.assembleTurn` builds ONE extra system message per turn on
  Dispatchers.IO in `regularGPTResponse`, after the stable first message
  (never reordered — prefix caching). That message now contains ONLY what
  the rules allow: retrieved memories with provenance markers and inline
  HANDLE WITH CARE handling (one render function — structurally
  inseparable; **DORMANT since July 8 2026 — "Protected" is retired, so
  nothing is flagged protected and this branch never fires; kept only so an
  old backup carrying a protection field still renders**),
  **Instruction-type memories rendered as context rules** in
  their own "Handling rules" section (law 5 — same retrieval, distinct
  render; the split lives in `PromptAssembler` so a rule can't be filed
  among the facts), the lorebook matches rendered INSIDE this message as
  "hand-written notes" that outrank memories (near-duplicate memories
  suppressed; pairs flagged to meta `enforcer.contradiction_flags` for
  Phase 6's run report), the scene from the per-chat Quick Settings
  selectors (world / **campaign** / roleplay character / user persona —
  ALL in the auto-naming copy block, like the Project selector), and —
  since Stage 3.6d — the **roleplay card layer**: the active cards'
  Zone 1 cores ("## The scene": user-persona presentation, then the
  world/campaign/character/party cores as labeled fields, plus the party
  roster line) render FIRST inside the message, before the memories —
  cores are stable across turns, so putting them ahead of the
  turn-variable retrieval keeps the cacheable prefix as long as possible
  (providers cache up to the first divergent token even inside a
  message). Party status gates the cores: alive/incapacitated members
  inject their card; dead/enemy members shrink to a one-line "No longer
  with the party" note. Trigger-matched **Zone 2 card entries**
  (`CardRetrieval` — pure, unit-tested: an entry fires on its name, its
  section label, or an auto-trigger tag via `LoreBookTriggerMatcher`
  semantics; group headers can never fire; one-hop pull-alongs ride on
  shared tags, browse-only tags counting for the hop) render after the
  memories as "From the story's cards (user-written; these outrank
  memories that disagree)" with "connected to:" lines. Card entries
  charge the injection budget BEFORE memories (user-authored outranks;
  lore + direct card fires first, memories squeezed, pull-alongs into
  leftover only — a broad tag can never flood the prompt) and share the
  10-turn freshness cooldown under `source_type='card_entry'` (cores are
  exempt — always-on is their contract; card-entry edits clear their
  cooldown rows).
  **Retired from assembly (Stage 3.4, §15):** the standing packet (owner
  portrait + directives) and `StandingPacketManager` (including its
  background Archivist compression call), mode detection and the modes
  render (`ModeSelection` deleted), suggested_mode, the companion
  hard-limits render, the model-adaptation note, entity summaries, and the
  retrieval policy's `always_include` list — the store tables stay dormant,
  nothing reads them. (The global **Archivist model** setting — endpoint
  profile + model name — stays; Phase 6 uses it.) A selected **campaign**
  implies the rest of the scene and is the §3 narrator signal: the
  campaign's GM companion being the chat's active companion opens the
  companion-memories-in-roleplay door (the other door is the global §3
  toggle in Memory settings, default OFF). Since 3.6c the campaign's
  world **outranks** the chat's own world pick in the enforcer
  (`campaign?.worldId ?: input.worldId` — a stale per-chat pick can't
  override the campaign; there is no per-chat override, spec §2), and
  Quick Settings enforces the same model: selecting a campaign turns the
  world/character selectors into displays, a world change goes through
  the owner-approved confirmation ("Continue campaign in new world? This
  will create a permanent history note on the campaign card.", spec §8b)
  which writes a **Plot Ledger** entry on the campaign ("Started in X" on
  first assignment, "Moved to Y" after) plus an optional Active Scene
  update, and the character slot is **locked** while a campaign is
  selected (dialog: "User characters are linked to campaigns and cannot
  be changed once assigned." — a dialog, not a toast; standing owner
  ruling, §8b). The **freshness cooldown** (§10,
  Stage 3.3) suppresses re-injection of anything injected within the last
  10 turns (constant in code, per chat, persisted — see storage); every
  suppression is visible in the debug view. Operating defaults are
  the retrieval policy ONLY (the five pre-written origin='system' modes
  were deleted and are purged once at store open — owner ruling, July 6 2026).
  ANY enforcer failure degrades to the classic lorebook message plus
  one soft toast per process — never blocks generation. (Tier "none" used
  to disable lorebook injection outright; since July 10 2026 it only sets
  the per-chat lore switch's default — Quick Settings is God, see above.)
  The lorebook debug screen also renders the
  enforcer's per-turn `AssemblyLog` (the "room" the turn stood in —
  ordinary vs roleplay, which targets, which companion-memory door — plus
  injected/cut lines with scores and cooldown/budget/near-dup reasons;
  since 3.6d also the scene's core summary and per-entry card lines —
  what fired and why, what a cooldown or the budget suppressed).
  Pure logic (PromptAssembler, NearDuplicate, CardRetrieval, the
  Librarian ladder math) is unit-tested. **Prompt-layer contract (fragile):** the per-request
  system blocks are fixed and deterministic — (1) the stable persona/system
  prefix, byte-identical every turn; (2) the Stage-4 model-rules block (BUILT
  — every ACTIVE rule matching the chat's model string, deterministic order;
  absent entirely when the per-chat "Apply Model Rules" toggle is off or
  nothing matches); (3) the single assembled memory message (ALL turn-variable
  memory content lives here and only here); (4) chat history + the current
  turn. Same blocks, same order,
  same wording every turn; never two competing memory messages. Three or
  more system messages are fine — "a separate second system message" in
  older docs describes the pre-Stage-4 layout. Read
  `memory-system-integration-plan.md` before touching `preferences/memory/`.
  Phase 5 (the hand-editor UI) is built in `ui/activities/memory/`, on a
  shared framework — `MemoryScreenActivity` (abstract themed list scaffold,
  with an optional secondary action-bar slot) + `MemoryRowAdapter` +
  `activity_memory_list`/`view_memory_row`. **The Stage-1 "trust repairs"
  reshaped it to the owner's July 6 2026 rulings** (`Memory System/
  owner_approved_rules.md` + `phase5_rework_work_order.md` — read both before
  touching this):
  - **The old ten-area hub is gone; a new lightweight hub exists.** The
    Phase-5 rework deleted `ui/activities/memory/MemoryManagerActivity` (the
    ten-area hub) and stays deleted. The **Memories browser** is the single
    GLOBAL browser over all scopes/types (world/campaign/roleplay-character/
    companion scoping via intent extras); it carries a **Companions** link in
    its action bar (unscoped view only). One browser, many doors: each
    companion/world/campaign/RP-character page has a **Memories** button that
    opens this same browser pre-filtered. Since July 8 2026 the browser is
    reached from the top row of the NEW `ui/activities/MemoryManagerActivity`
    (a different, four-row navigation hub — see the architecture map); the old
    "Browse & edit" button in Memory settings is gone.
  - **Retired screens (deleted; tables + store CRUD stay dormant):** Modes,
    Directives, **Entities**, and **Owner profile**. People in the user's
    life are ordinary memories under scope/type, not "entities"; what the
    system knows about the user is Preference/Fact memories, not a profile
    form.
  - **Companion detail page** keeps only: read-only name, draft badge +
    Approve, memory-participation, Save, **Delete** (per-deletion choice to
    also delete that companion's memories), and the Memories button. The
    essence / relationship-notes / hard-limits / model-adaptations fields
    were removed (columns stay, unwritten). ("Personas" is still shown as
    "Companions" in the memory-side UI strings.)
  - **My Personas** left the memory area entirely — its tile now lives in the
    Characters hub (`CharactersActivity`) with the other identity tiles.
  - Roleplay areas (Worlds, Campaigns, Roleplay characters, and — since
    3.6 — Party Members and Tags) stay under the single **Roleplay card**
    on Settings (`RoleplayHubActivity`).
  All CRUD is in `MemoryStore` (per-record upsert/delete with tombstones;
  memory edits log prior state + drop stale vectors; `deleteCompanion` added).
  Detail pages are plain `FragmentActivity` forms; the list screens subclass
  `MemoryScreenActivity`. **Stage 2 is built** (July 2026): the memory record is
  restructured (scope categories / Type / projects / draft status, DB v4–v5 —
  see the storage section) and the UI rebuilt to the owner-approved rules:
  - The **memory editor is a full-screen page** (`MemoryEditorActivity`, not a
    pop-up — the owner's device mishandles large dialogs): title + content, a
    Type picker (six, with the §5 meanings as hint lines), an Importance picker
    (five), a primary Scope picker (seven), and — for the target-bearing scopes
    — the **"Associated <Scope>" target picker (owner rework, July 8–9
    2026)**: label + boxed "Select" dropdown on one line (`bg_dropdown_box`,
    5dp corners, box around the dropdown only — never pills, never a boxed
    whole line), selections rendered below as small-curve boxes with an × at
    the far right to remove. Projects can still be created from that dropdown
    (no other creation surface yet). A roleplay DRAFT with no target shows
    the persistent inline "Needs roleplay target." note and its Link-to-Lore-
    Card section stays hidden until a target is assigned (July 9 ruling); the
    same note appears on its browser row, where Add to Card is hidden too.
    The old `EditMemoryDialogFragment` was removed.
  - The **browser** filters (reworked July 8 2026): the old horizontal chip
    row is RETIRED (the `filter_bar` container stays in the shared scaffold,
    hidden, in case another list screen wants chips). A **three-dots
    (`ic_more_vert`) button** sits to the right of the search field and opens
    the **Memory Filters** slide-out panel (`MemoryFilterPanelActivity`, slides
    in from the right, paired slide animations). Six sections — **Sort, Scope,
    Type, Status, Source, Tags** (Title-Case labels). Sort and Source are
    single-value pickers (Source has only two real options → the owner's "if
    only two options, no multi" rule); Scope/Type/Status/Tags are multi-select:
    each selection becomes a `Chip` pill (10dp corners) with a small × to
    remove. **Reset Filters** at the bottom. Filter state is the process-wide
    `MemoryBrowserFilterState` (Sort/Source strings, the rest `MutableSet`s) so
    the panel edits it in place and the browser reloads on resume — auto-apply,
    no OK/Cancel. **Status defaults to just `active`** (fresh view shows only
    active memories; "draft" surfaces to the user as **Pending** and is one tap
    away). The search hint reads **"Search Memories"** in a softer color token
    (`memory_hint_soft`, light+dark) for the eventual theme swap.
  - The browser **row** was redesigned (July 8 2026): a **leading identity
    icon** (spans the row height) + title (17sp bold) / tags line (11sp) /
    first content line (13sp), and the trailing action is the **edit-square**
    (`ic_edit_square`, replaced the cog). Tags render as
    `Communication  ·  Technical Help  ·  Tone` — no hashtags, each capitalised,
    middle-dot separated. The **Active badge is suppressed** on rows (Active is
    the default filter, so the pill meant nothing); draft/archived/superseded
    still show their badge. The leading icon is chosen in
    `MemoryBrowserActivity.iconForScope(scope, onCard)` (drawables `ic_mem_*`),
    per the owner's **July 8 2026 decisions, which SUPERSEDE the older mapping
    in `Memory System/phase6_card_suggestions_and_icons_design.md` §5**:
    real_life → person; **global → borg (`ic_mem_global`, its OWN icon —
    global is a distinct scope from real life, §3)**; companion → partner
    (`partner_exchange`); project → draft (folded-corner page); rp_character →
    theater comedy mask; world / campaign (and any unknown) → public globe;
    **a memory that has been placed on a card → book_5** (`ic_mem_book`). The
    user's RP-character slot shares the comedy mask for now but has its OWN
    branch, ready for a future dedicated icon. **`onCard` is always false
    today** — a memory↔card link (Phase 6; `card_entries` has no source-memory
    column and `memories` has no on-card flag) is the one missing piece;
    `isOnCard()` is the single hook to teach when that flow exists, and book_5
    lights up then.
    `MemoryRowAdapter`/`MemoryRow` grew `iconRes` + `tagsLine` fields (and,
    July 8 evening, `pendingActions`/`showAddToCard` for the Pending-mode
    action words); the scaffold's pending banner is Model-rules-only now.
  - **"Protected" is retired (owner ruling, July 8 2026 — see
    `owner_approved_rules.md` Addendum §2).** It was a handling concern, not a
    scope/type: rule-like protections are now ordinary Global memories, and a
    sensitive fact keeps its care-note in its own text (memories inject whole,
    so it can't be sheared off). The **Protect/Unprotect row-menu actions were
    removed** from the browser. The `protection` column, the backup codec
    field, and the inert `HANDLE WITH CARE` enforcer render stay DORMANT for
    backup/import compatibility; the Archivist (Phase 6) must never emit a
    protection/handling field.
  - **Pending is a browser MODE now (owner design, July 8 2026 evening —
    `Memory System/phase6_owner_answers_2026-07-08.md`).** The old separate
    Pending screen (`MemoryPendingActivity`) and the "Pending memories (N) ›"
    banner are RETIRED and deleted. The browser carries a centered
    **"Memories | Pending"** word toggle at its top with a **"N Memories
    Pending"** count line under it ("One Memory Pending" singular; hidden at
    zero). Memories view = everything non-draft (archived/superseded keep
    their badges); Pending view = drafts, each row carrying bold
    **Accept / Delete / Edit** action words across its bottom — roleplay
    scopes (world/campaign/rp_character) add **Add to Card**, which opens the
    owner-worded "Accept Memory and Link to Lore Card?" pop-up (boxed 5dp
    dropdowns for the lore card + section) and **MOVES** the memory onto the
    card via `MemoryStore.convertMemoryToCardEntry` (title→entry name,
    content→description; the memory row is deleted and lives/dies with the
    card from then on — owner's D&D-sheet ruling). The editor's bottom button
    for drafts reads **"Approve All As Shown"** (save + activate + return);
    roleplay drafts also get a **"Link to Lore Card:"** boxed dropdown pair —
    picking a card + section makes approval convert instead of activate. The
    filter panel's **Status section was removed** (duplicate of the toggle;
    `MemoryBrowserFilterState.status` remains as entry-point plumbing — the
    Memory Assistant's View Pending Memories link writes `{draft}` to open
    the browser pre-switched to Pending). The scaffold's pending banner
    machinery survives only for Model rules. Dropdown house style (owner,
    same ruling): `bg_dropdown_box` — a 5dp-corner box around the DROPDOWN
    only, never the whole line, never pills.
  - **Reset memories** in Memory settings (`resetAllMemoryData` + a blunt
    confirm dialog with a "Save a backup file first" checkbox, checked by
    default) empties every memory-content table. Since July 11 2026 the
    backup gates the reset: `writeBackupNow` writes atomically (temp file →
    rename via `AtomicFileWriter`) and reads the file back, and if it
    reports failure the reset is ABORTED with a dialog — it used to proceed
    and only change the confirmation wording, destroying the store the user
    had asked to protect. The work order's "Remove
    everything imported" was intentionally NOT built (imported rows aren't
    distinguishable; owner decision).
  - **Quick Settings** gained an optional per-chat **Project** selector (§4;
    `getChatProjectId`/`setChatProjectId`, in the auto-naming copy block).
    Since Stage 3 the selection is live as a ranking *boost*, not a gate:
    project memories retrieve on relevance even with none selected (owner's
    July 6 second-pass ruling).
  **Stage 3 (retrieval engine) tasks 3.0–3.5 are BUILT (July 2026)** per
  `Memory System/rag_engine_work_order.md`: campaign→Quick-Settings wiring
  with the narrator signal (3.0), the seven-category scope eligibility
  rewrite + the "Allow active companion memories in roleplay" toggle (3.1),
  the §12 priority ladder as blended ranking boosts (3.2), the persisted
  freshness cooldown (3.3, DB v6), the enforcer rework to the approved
  rules with Instruction-memory rendering (3.4), and the project-boost
  wiring/verification (3.5) — details in the storage + Phase 4 sections
  above. **Task 3.6 was RESCOPED July 7 2026** (the old pause point is
  resolved: the owner designed and approved the full roleplay card + tag
  system — `Memory System/roleplay_cards_and_tags_spec.md` is the
  authoritative spec, incorporated into the rules as Revision 4; its
  §8a/§8b addenda hold owner rulings made DURING the build and are part
  of the approved words) **and is FULLY BUILT — all sub-tasks 3.6a–f
  (July 7 2026). Stage 3 is complete.**
  - **3.6a (schema, DB v7–v8):** the card/tag storage layer + store CRUD
    + backup coverage — see the storage section.
  - **3.6b (card editors/rosters):** full-screen two-zone cards for RP
    characters + party members (`CharacterCardActivity`, one screen,
    party mode adds Speech Style + the four-state status), worlds
    (`WorldDetailActivity`, grouped §6c sections + geography parents +
    the Promote-to-Party-Member flow — a promoted NPC's button becomes a
    roster link) and campaigns (`CampaignDetailActivity`, bookmark
    fields + party roster links + world-overlay sections), all sharing
    `CardEntryEditorActivity` (section-shaped fields: kind lists,
    quantity, geography parent picker, campaign cast/location/reliquary
    fields, world-overlay picker), `CardTagChips` (roleplay-realm tag
    input: ≥3-char filter dropdown, create-on-confirm, removable chips)
    and `CardZoneUi` (the §8a right-aligned word count, 300/500
    thresholds, red pill at 500). Zone labels/warnings use the §8a
    approved words verbatim. Party roster lives in
    `MemoryPartyMembersActivity` (status badges + archive section),
    reached from a Party Members tile in `RoleplayHubActivity`.
  - **3.6c (Quick Settings campaign behavior):** the campaign selector
    drives the scene; world change = confirmed Plot Ledger note,
    character slot locked — details in the Phase 4 section above (§8b
    approved words; dialogs, never toasts).
  - **3.6d (injection wiring):** Zone 1 cores render first in the
    assembled message, trigger-matched Zone 2 entries after the
    memories, budget-charged before them, cooldown-tracked — details in
    the Phase 4 section above.
  - **3.6e (tags screens):** `RpTagsActivity` (Tags tile in the Roleplay
    hub — the tag index, per-tag auto-trigger/browse-only switch) and
    `RpTagViewActivity` (tap a tag anywhere → the cross-card view,
    grouped by card/section categories, reaching card entries, whole
    cards and roleplay-realm memories via the read-side bridge).
  - **3.6f (deletion + archive, §5):** delete-while-linked warnings name
    the campaign(s) and offer Archive; archive is status-only and
    visible (Archive sections at the bottom of the worlds/campaigns/
    characters/party lists, one-tap Restore); true delete asks
    per-deletion about the card's memories; surviving references render
    "(archived card)" / "(deleted card)" — never a silent hole. NPC
    death is a status change, never a delete.
  Stage 4 (Model rules, §11) is **BUILT** (July 2026) — §11 was redesigned in
  chat to Revision 5 (model-string-primary + tags, ON by default; the old
  profile/group model is gone). Storage is DB v10 (see the storage section);
  the UI is the **AI System Settings** screen (new Settings card; the System
  prompt tile moved here out of Characters) + the Model rules browser / editor
  / tag screens (`ui/activities/memory/ModelRules*`, `ModelRuleTagChips`);
  injection is the model-rules prompt block gated by the global + per-chat
  "Apply Model Rules" toggles. The **Pending** UI is built but stays empty
  until **Phase 6 (Archivist)** files draft rules — that is the next phase and
  is NOT built. Still deferred: merge tooling.
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
    switches; **Memory diagnostics logging** (`getMemoryDebugLogging`), and the
    two **performance** toggles below it — **Whisper performance logging**
    (`getWhisperPerfLogging`) and **Memory usage logging** (`getMemoryUsageLogging`),
    all three **off by default**; a **→ Audio Debugging** shortcut row (so someone
    hunting for VAD logging here still finds it); then **Crash log** (a.k.a. the
    Error Log) / **Event log** (Voice Debug Log) / **Memory log** / **Performance
    Log** as "label ›" rows that open `LogsActivity`.
    The **Performance Log** is a dedicated `Logger` channel (`type == "performance"`,
    2000 entries / 7 days) fed by exactly those two toggles, kept separate from the
    Error and Voice logs so its high-volume output never evicts real crash entries
    or buries the per-turn voice trail: **Whisper performance logging** writes one
    line per on-device transcription from `LocalWhisperEngine.stopAndTranscribe`
    (audio ms / model-load ms / decode ms + a compact `MemoryDiagnostics` snapshot),
    and **Memory usage logging** drives an app-wide ~60s heartbeat plus
    `onTrimMemory`/`onLowMemory` lines from `MainApplication` (Java heap, native
    heap, total PSS, thread count, system availMem/low-memory) so a slow decode can
    be read against memory pressure and a slow RAM leak shows as a rising curve even
    while the app is idle. Footprint sampling lives in `util/MemoryDiagnostics`
    (shared by both). Added July 2026 to diagnose "Whisper suddenly slow in long
    conversations" — the transcribe path itself has no per-turn accumulation
    (native decode caps threads at 4, `no_context` defaults on), so the cause is an
    emergent system effect these logs are built to localize.
  All toggles are global prefs; the logs are local-only and intentionally always
  reachable — never add a telemetry/consent gate (the old installation-id gate,
  and the installation-id/consent system it belonged to, were removed July 2026). **Audio Health** is a separate hands-free diagnostic from VAD
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

- **NEVER use `Toast` unless the owner explicitly approves it for that exact
  spot (owner rule, July 9 2026).** Toasts vanish on their own and are
  useless with the owner's voice/accessibility setup. Favor PERSISTENT
  messages: inline status text that stays on screen, field errors set on the
  input itself, or a dialog the user dismisses. This extends the existing
  "dialogs, never toasts" ruling from the campaign work to the whole app.
  When touching a screen that still has toasts, convert them (keep the
  approved wording, change only the presentation).
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
- **Prefer full-screen activities over dialogs/pop-ups for new or
  reworked screens.** The app was originally built with ~20
  `DialogFragment`s/bottom sheets standing in for what are really full
  editing screens (see `ui-redesign-plan.md`'s inventory) — that pattern
  wastes screen space, and the owner wants it phased out over time as
  screens get touched, not necessarily all at once. When building a new
  screen or substantially reworking an existing one, default to a
  full-screen `FragmentActivity` (the pattern `MemoryEditorActivity`
  already established for the memory system) unless the content is
  genuinely dialog-shaped: a short confirm/destructive-action prompt, a
  small in-place picker, or something else where a pop-up is the logical
  fit, not just the historical default. Don't convert an existing dialog
  to full-screen as a drive-by side effect of unrelated work — that
  conversion is deliberate, incremental work, not incidental cleanup.

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
  **Capture-recovery invariants (July 11 2026, silent-failure audit round 2 —
  keep these):** every Whisper capture session is an engine-internal
  `ActiveCapture` whose teardown funnels through the idempotent
  `closeCapture()` (a once-latch makes cleanup + the typed error callback run
  exactly once per session, on every exit path — user stop, transcribe,
  abnormal loop death, stale recovery). An abnormal capture exit (read
  throw / error return / endless empty reads / loop crash / watchdog abort)
  always releases the AudioRecord + Bluetooth route, clears `isCapturing`,
  discards the buffer, and reports a typed `CaptureErrorReason` — NEVER
  surface an engine failure as silence, a null transcript, or an empty
  transcript. `startRecording` while a capture is genuinely live returns
  **false** (it used to lie with `true`); a stuck flag over a dead loop is
  recovered. VAD-driven turns carry a state-dependent wall-clock
  `CaptureWatchdog` (owner-approved July 11 2026): the configured
  silence/no-speech clocks stay authoritative; at 10 min mid-speech the turn
  is NOT cut — a soft limit arms and the turn finishes at the next natural
  pause (~1 s of non-speech); at 10 min with no speech the backup fires the
  normal no-speech ending; the 12-min absolute ceiling ends the turn through
  the NORMAL transcribe-and-submit path (recorded speech is never discarded
  by a time limit, and a watchdog-ended turn is never reported as a mic
  failure); the ONLY watchdog error is the 60 s uncollected-end-of-turn
  abort (lost callback). Manual push-to-talk is deliberately un-capped (the
  user owns stop; a cap would cut intended long dictation). ChatActivity guards every
  whisper callback with `whisperTurnToken` (late/duplicate callbacks from an
  old turn are dropped), retries mid-turn capture errors through
  `whisperCaptureErrorBudget` (2, reset only when a turn completes — separate
  from `handsFreeTurnRetries`, which covers arm failures and resets on a
  successful arm), and re-checks RECORD_AUDIO before EVERY arm and in
  onResume — a revoked permission is a named, always-logged stop, never a
  fake no-speech. Foreground-service start failures (both services, plus
  `startHandsFreeService`) always write an ungated Event-log line. Cloud
  Whisper never calls `start()` after `prepare()` fails. The pure decision
  logic lives in `stt/CaptureTurnPolicy.kt`, unit-tested in `app/src/test`.
- **System-message assembly in `regularGPTResponse`** — the prompt layers are
  FIXED and deterministic every turn: (1) persona prompt + system message
  merged into ONE stable first system message, byte-identical, specifically
  for provider prefix caching; (2) the Stage-4 model-rules block as its own
  stable layer — every ACTIVE rule matching the chat's model string, in
  deterministic order (absent entirely when the per-chat "Apply Model Rules"
  toggle is off or nothing matches); (3) lorebook matches — or, at the full memory tier, the
  enforcer's single assembled message (which contains the lore notes) — as
  the one turn-variable memory message; (4) chat history + the current turn.
  Never reorder or merge these by convenience, retrieval results, iteration
  order or timestamps; never inject two competing memory messages (the
  enforcer path and the classic lore path are strictly either/or per turn).
  Three or more system messages are fine — the memory message being "the
  second" is just the pre-Stage-4 layout, position is not the invariant;
  order and byte-stability are. (Explicit cache breakpoints are
  Anthropic-API-only; OpenAI-compatible endpoints auto-cache the longest
  identical prefix — the ordering discipline is what earns the caching.)
- **`ChatPreferences` parse-failure handling**: chat data must be preserved,
  never wiped, on JSON parse errors (regression fixed in c72853a).
- **Native layer** (`app/src/main/cpp`, CPU gating in `NativeCpuSupport`):
  lib loading is gated on armv8.2 dotprod+fp16 to prevent SIGILL on older
  arm64 — keep the gate if you touch JNI loading.
- **Checked-in `debug.keystore`** is intentional (stable CI debug signing).
  Do not rotate/remove it; do not publish debug builds as real releases.
- **Chat renames are a verified transaction** (`ChatRenameTransaction`, July
  11 2026 — see the feature list): write-new → verify → pointer flip → clear
  old, settings copied wholesale, every write a synchronous commit, run
  **off the main thread**. Never reintroduce a hand-enumerated settings copy,
  an apply()-deferred write, a clear-before-write, or a main-thread commit into
  a rename path; new per-chat keys are registered in `PerChatSettingKeys`
  (test-enforced). The memory-store re-point is a **separate store** and is NOT
  in the prefs transaction — it is journalled (`RenameJournal`) and recovered
  at startup; never assume the prefs rename and the memory re-point committed
  atomically together, and never let a future orphan sweep prune rows while
  `RenameJournal.hasPending` is true.
- **The chat-storage outage state machine** (`SecurePrefs` /
  `ChatStorageHealth` / `OutageReconciler`, Round 4 — see the storage
  section): never reintroduce a plaintext fallback for a name whose
  `enc.<name>` file exists on disk (that is the LOCKED state — the old
  fallback is exactly how locked chats read as empty and outage writes got
  destroyed), never modify/clear/delete an encrypted file that failed to
  open, never resolve an encrypted-vs-outage conflict automatically (both
  copies are preserved; the snapshot files `*_recovered_*`/`*_conflict_*`,
  the `outage.*` files and `files/storage_recovery/` are recovery data — no
  cleanup pass may touch them), and keep `reconcileOutageAtStartup` ordered
  before `RenameJournal.reconcile` in `MainApplication`. The `storage_health`
  prefs file is deliberately raw/plaintext (must work while the Keystore is
  down) and must never hold chat content.
- **`ChatActivity` handles rotation itself** (`android:configChanges`
  includes orientation/screenSize etc., July 10 2026): recreation runs
  onDestroy, which kills TTS readback and the hands-free loop — tilting the
  phone mid-conversation used to stop everything. Never remove that manifest
  attribute, and don't add orientation-dependent layouts for the chat screen
  without accounting for it.
- **New-chat companion selection — owner ruling July 11 2026 (SUPERSEDES the
  July 10 wording; "there is no other acceptable behavior"). Do NOT revert
  this — it kept regressing because the old doc enshrined the opposite.**
  `seedPersonaAndActivationDefaults` (in `ChatActivity`) decides which
  companion a brand-new empty chat opens with, in this exact order:
  1. **Default: the last-used companion** — always, this is the expected
     behavior.
  2. **Only exception — first-ever use** (no last-used companion recorded, or
     the recorded one was since deleted): open with the companion at the **top
     of the list** (`getPersonasList().first()`).
  3. **No companion exists at all:** a chat can't begin — `promptCreateFirstCompanion()`
     shows the owner-approved dialog "Please create a new companion to begin a
     chat." and opens the companion creation screen (`PersonasListActivity`
     with the `createOnStart` extra); on creation the chat adopts it and records
     it as last-used. Seeding is NOT marked done in this case so it re-runs.
  There is **no "explicit none stays none"** state any more (it was removed —
  the owner does not want new chats resting on no companion). `setLastUsedPersonaId`
  is written whenever a companion is chosen through **ANY** selection surface —
  **both Quick Settings AND the Companions list opened from Characters** (the
  July 10 rule that only Quick Settings wrote it was the bug: a companion picked
  via Characters never carried into new chats). Recording fires only on an
  explicit tap-to-select (the persona list returns `CANCELED` on back-out), so
  a mere browse is never recorded. `Preferences.hasLastUsedPersonaChoice()` is
  now unused by seeding.
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
  `prompt_assembly_template.md` were the original runtime/prompt specs but
  are now PRE-REVISION (each carries a ⚠️ banner): `owner_approved_rules.md`
  + the work orders describe the actual runtime since the July 2026
  rulings and the Stage 3.4 enforcer rework.
- **Phase 6 (Archivist + Memory Assistant) is COMPLETE (built July 8–9 2026,
  merged to `main` via PR #55, carried forward on this branch).** Before
  touching it, read the **"Addendum — modifications approved in chat, July 8
  2026"** at the end of `owner_approved_rules.md` (Protected retired, scope
  definitions tightened, the memory-UI restructure, the final row-icon
  system) and **`Memory System/memory_assistant_design.md`** for the Memory
  Assistant screen's approved layout/wording. Both outrank the older Phase 6
  text wherever they disagree. Built: the roleplay memory deletion fix, the
  **Archivist run engine** (`preferences/memory/archivist/`, DB v11–v12 run
  history + status/failure wording per `archivist_status_wording_spec.md`),
  the **Memory Assistant screen** (`MemoryAssistantActivity`, owner-approved
  wording, live batch progress, Recent Memory Analysis + Rerun), the
  **Pending review mode** in the Memories browser (Accept / Delete / Edit /
  Add to Card, the last via `convertMemoryToCardEntry`), the **card
  placement flow** (`isOnCard()`/book_5, DB v13 suggested-card-placement
  columns), and **rejected-draft tracking** (DB v14). Scope was finalized,
  not left open: the Memory Assistant never creates or proposes
  worlds/campaigns from emergence (an untargeted roleplay draft gets a
  persistent "Needs roleplay target." note instead), and it never
  automatically updates campaign `story_so_far`/Plot Ledger fields — see the
  "Phase 6 scope limits (owner rulings, July 9 2026 — final)" paragraph in
  the storage section. Remaining deferred work (Model rules merge tooling)
  is tracked separately under Stage 4, not this phase.
- **Phase 7 (Hardening + docs) is COMPLETE (July 10 2026) — a
  verification/docs pass that changed NO app code, strings, or UI** (owner
  constraint for the pass). Confirmed by code read that mid-conversation
  memory failures never block generation: the enforcer `assembleTurn` call
  in `regularGPTResponse` is try/catch-wrapped → degrades to lore-books-only
  + a `MemoryLog` line; the lorebook gather has its own catch → no-lore + a
  line; the `Librarian` degrades internally to keyword/tag matching (no
  model / self-check fail / embed fail / vector-search throw), each with a
  line — two independent degradation layers, always logged. Audited
  `troubleshooting_guide.md` symptom-by-symptom against the current UI + the
  owner rules and appended a "Phase 7 audit status" table to that file: live
  workflows all have UI homes; the ones without are pre-rules (retired
  machinery — modes / model_adaptations / Protected / handling / harvest
  dials / essence-hard-limits / mirror-drift-flags — or superseded
  aspirations: provenance-confidence display, contradiction-in-run-report).
  Two findings were SURFACED to the owner, NOT built (both would touch app
  text/UI, held for approval): (1) `notifyMemoryDegradedOnce()` still uses a
  `Toast` (`R.string.memory_degraded_notice`) — a pre-July-9-ban leftover, an
  extra courtesy notice not required by the never-blocks contract; (2) the
  two audit GAPS are new features under the memory approval gate. Phase 8
  (Android⇄Windows file-based sync) remains the only unbuilt phase.
- **The roleplay layer (cards + tags) was redesigned and owner-approved
  July 6–7 2026.** `Memory System/roleplay_cards_and_tags_spec.md` is the
  authoritative spec (four two-zone cards: user RP character, NPC party
  member, world, campaign; a roleplay-realm tag system with a hard
  real-life/roleplay wall; campaign-as-selector behavior; archive/delete
  link rules; the no-mid-conversation-writes law). It is incorporated
  into `owner_approved_rules.md` as Revision 4 and implemented by the
  RESCOPED Stage 3.6 of `Memory System/rag_engine_work_order.md` —
  **fully built July 7 2026 on branch `claude/stage-3-6-rag-engine-9f0gc2`
  (Stage 3 complete)**. The spec's §8a/§8b addenda record owner rulings
  made during the build (fresh world-core columns, dormant pre-card
  fields, campaign-selector wording, the dialogs-not-toasts rule) and
  are part of the approved words. Read the spec's §9 agent rules before
  any roleplay work.
- Whisper/voice work follows `whisper-local-plan.md`.

## Quick verification checklist before any push

1. `git status` clean of stray files; commit message says why.
2. All new `R.string/R.id/R.drawable/R.layout` references exist; new ids match
   the layout actually inflated.
3. Cross-cutting request changes go through the single generation funnel
   (`generateResponse` → `regularGPTResponse`); there is no second path.
4. New per-chat preference key registered in `PerChatSettingKeys` (renames
   copy the settings file wholesale; the unit test fails when the registry
   drifts from `Preferences.kt`).
5. DB change → version bump + additive migration + fresh-install path.
6. Push, then confirm the `Android Checks` workflow run for your commit is green.

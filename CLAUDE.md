# Phosphor Shines — AI Onboarding Manual

## ⛔ THE OWNER'S WORDS RULE — absolute, no exceptions (July 8 2026)

**Every word the app shows the user is the owner's.** If a screen, toast,
status line, error message, button, hint, or ANY other user-visible text
needs words and the owner has not supplied them, you STOP, list the exact
spots that need words, ask in plain chat, and WAIT. You do not write
"reasonable filler." You do not ship your own text and offer to fix it
later. You do not call anything "minor screen text" — there is no such
category. On July 7–8 2026 an agent shipped a dozen self-authored status
lines inside an otherwise owner-approved feature, disclosed it only inside
a long summary, and then — when the owner objected — twice told the owner
it was fine. It was not fine. The owner had spent that entire day
correcting agents for exactly this, more than twenty times, at real cost
in money and days of lost sleep. If you catch yourself typing a
user-visible sentence the owner never said: stop and ask.

**Nothing happens without the owner's permission.** Not building, not
deleting, not "dropping" a planned item, not declaring a question
"resolved," not choosing the "simplest fix." Those are the owner's
decisions. On any open point your entire job is: explain it in plain,
jargon-free words, ask one clear question, and wait for the answer as an
ordinary chat message. Presenting an already-made decision inside a
summary is a violation even when the decision looks obviously right.
When the owner says they don't understand something, that is a full
stop: re-explain simply and do not advance any work that depends on it
until they say they understand and say yes. Never argue that a violation
was acceptable — acknowledge it, fix exactly what the owner directs, and
wait.

## ⛔ ACT ONLY FROM FACT, NOT ASSUMPTION

Verify before you act. Read the actual code, the actual file, the actual
top of the document before you claim anything about it or change it. Never
delete, move, or "clean up" anything you have not personally read and
confirmed. If you are inferring instead of checking, stop and check.

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
  checklist, sampling params, the memory-scene selectors, and the per-chat
  **Apply Model Rules** toggle).
- `ui/activities/CharactersActivity.kt` — hub of tiles: Personas, Activation
  prompts, Lorebooks. (The System message tile moved OUT to AI System
  Settings in Stage 4 — see below.)
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
  a Pending banner that stays empty — model-rule drafting by the Memory
  Assistant is deferred by owner decision), `ModelRuleEditorActivity`
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
   column ('user' default; 'archivist' = Memory Assistant drafts since
   Phase 6) on
   memories, companions, entities, modes and directives, so later phases can
   tell user records from archivist-proposed ones. DB v3 (July 2026, Phase 5)
   adds the **Campaign** (roleplay continuity) layer: a `campaigns` table
   (world/roleplay-character/companion FKs + status) and a nullable
   `memories.campaign_id`. (v3 also created a `campaigns.story_so_far`
   free-text column; it was **removed in DB v12** — see below — do not
   reintroduce it. A campaign's story record is the Plot Ledger Zone 2
   section, not a summary column.) Ordinary
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
   (first selected) used only by the target teardown paths. The scoped-browser
   doors read the join tables and the target-delete paths scrub them.
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
   at session end (the `active_scene` column displays as **"Current Plot"** in
   the UI and prompt since v12); `worlds` gains cosmology and an 'archived' status via a
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
   `worlds.magic_rules` columns for the world core: the new cards must never
   show, map, or migrate the old free-text blocks — worlds' `premise`/`rules`
   and roleplay characters' `description`/`played_by` are DORMANT (kept only so
   old backups import); no data is copied into the card fields. The two
   "story so far" leftovers — roleplay characters' `arc` and campaigns'
   `story_so_far` — were **fully removed in DB v12** (owner instruction,
   July 2026): do NOT reintroduce either. A character's history is the
   **Backstory** Zone 2 section; a campaign's story record is the **Plot
   Ledger** Zone 2 section. The §8a addendum also holds the approved 3.6b
   on-screen wording (Zone labels, the right-aligned word count with
   300/500-word warnings, "Promote to Party Member") — use those words verbatim.
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
   DB v11 (July 2026, Phase 6 prep) adds `transcripts.campaign_id`, and
   transcript capture now stamps the turn's resolved scene (world / campaign /
   RP character / user persona) onto the open row — a selected campaign's own
   links outrank the chat's world/character picks, mirroring the enforcer, and
   stale ids degrade to null. A scene change closes the open transcript row
   just like a model or companion change, so each row's scene columns stay
   truthful — the Archivist reads them per ROW to hold the fiction firewall
   (rules §3) and attribute campaign state to the right continuity; pre-v11
   rows simply have a null scene and read as ordinary conversation.
   DB v12 (July 2026, owner instruction) **removes the two "story so far"
   leftovers** — `campaigns.story_so_far` and `roleplay_characters.arc` —
   via plain single-column `ALTER TABLE … DROP COLUMN` (both were
   unconstrained TEXT with no index/FK/CHECK, so the drop touches no other
   table; each drop is best-effort so a failure leaves the unused column
   rather than blocking store open). Nothing displayed, injected, or read
   either column; the record fields, codec lines, and dead UI strings went
   with them. A character's history lives in the **Backstory** Zone 2
   section, a campaign's story in the **Plot Ledger** Zone 2 section — do
   NOT add a per-card story/arc/summary field back. The same migration is
   also when `campaigns.active_scene` began displaying as **"Current Plot"**
   (label only; the column name stays `active_scene`).
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
   paths. UI: the **Memory System card on the main Settings screen** (between
   AI System Settings and Roleplay — moved OUT of the Characters hub at the
   owner's instruction; never put it back there) →
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
  Settings → Memory System). Every completed turn is captured into the transcripts queue from
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
  `preferences/memory/enforcer/`, and was **reworked by Stage 3.4 to the
  owner-approved rules**: a global **Memory engine** setting in
  Memory settings — none / lore books (default = classic behavior) / full
  (selectable only with an embedding model installed). At "full",
  `Enforcer.assembleTurn` builds ONE extra system message per turn on
  Dispatchers.IO in `regularGPTResponse`, after the stable first message
  (never reordered — prefix caching). That message now contains ONLY what
  the rules allow: retrieved memories with provenance markers and inline
  HANDLE WITH CARE handling (one render function — structurally
  inseparable), **Instruction-type memories rendered as context rules** in
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
  one soft toast per process — never blocks generation. Tier "none" disables
  lorebook injection too. The lorebook debug screen also renders the
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
  - **No hub.** `MemoryManagerActivity` was removed; the "Browse & edit"
    button in Memory settings opens the **Memories browser** directly, and it
    is the single GLOBAL browser over all scopes/types (world/campaign/
    roleplay-character/companion scoping via intent extras). It carries a
    **Companions** link in its action bar (unscoped view only). One browser,
    many doors: each companion/world/campaign/RP-character page has a
    **Memories** button/action that opens this same browser pre-filtered.
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
    — a **multi-select target picker with removable pills** (§2). Projects can
    be created from that picker (no other creation surface yet). Protection
    editing stays on the browser row menu. The old `EditMemoryDialogFragment`
    was removed.
  - The **browser** gained the full filter/sort chip row (sort + scope / type /
    status / source / tag + Reset), filtered in memory over all statuses; state
    is held statically so it survives leaving to the editor and back. Row =
    title → status → tags → first content line. The chip bar + Pending banner
    live on the shared list scaffold, hidden unless the browser turns them on.
  - The **Pending screen** (`MemoryPendingActivity`, §14): a pinned "Pending
    memories (N) ›" banner on the browser opens draft memories grouped under
    collapsible destination-scope headers with per-group select-all, checkboxes,
    Select all/none, Accept/Delete (count confirm); tap-to-edit opens the editor
    (which shows an **Accept** button for drafts = save + activate).
  - **Reset memories** in Memory settings (`resetAllMemoryData` + a blunt
    confirm dialog with a "Save a backup file first" checkbox, checked by
    default) empties every memory-content table. The work order's "Remove
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
  "Apply Model Rules" toggles. The model-rules **Pending** UI is built but
  stays empty — model-rule drafting by the assistant is deferred (see below).
  Still deferred: merge tooling.
  **Phase 6 (the Memory Assistant) first slice is BUILT** (July 7 2026, per
  `Memory System/phase6_memory_assistant_work_order.md` — the authority for
  this phase; its prep work was DB v11 scene stamping + the v12 column
  removals, see storage). ONE feature, user-facing name **"Memory
  Assistant"** (never "Archivist" on screen): a page reached from a row in
  Memory settings (next to the Archivist endpoint/model rows it consumes)
  with a manual **Analyze History** control and an **Advanced Settings**
  row — nothing runs on its own, and there is no review or report screen
  (suggestions surface in the existing browser/Pending screen). The backend
  (`preferences/memory/assistant/MemoryAssistantRunner`) reads PENDING
  transcripts oldest-first, sends each to the Archivist endpoint/model with
  the extraction prompt (`MemoryAssistantPrompt.BASELINE` — owner-approved
  words including the "and"→"&" token trim; the Advanced screen's edits are
  a pref override and Reset just clears it, so the baseline is unloseable),
  and files what survives the in-code law layer as drafts
  (`status='draft'`, `origin='archivist'`, change-log actor 'archivist',
  provenance shows "Learned from chat"). The law layer (unit-tested in
  `app/src/test/.../MemoryAssistantRunnerTest`) enforces: drafts only,
  memory suggestions only (anything else the model emits is dropped — no
  code path writes to companions/personas/cards), the fiction wall by the
  transcript's stamped scene columns (real-life scenes can't file
  world/campaign/rp_character scopes and vice versa; global-only
  companions file global only), the type whitelist, and the
  max-suggestions cap. A transcript is marked processed only after its
  drafts are filed; unparseable replies and transport failures leave rows
  pending (nothing is ever burned), and an empty result is a success.
  Advanced Settings: Temperature (real number, 0.0–2.0, clamped on
  read/save, "Recommended: 0.3" always visible), Maximum Suggestions Per
  Conversation (ships Off; cap enforced in code), and the editable
  Extraction Prompt (helper "Instructs AI how to analyze conversation
  history.", **Reset** button, confirm "Restore default extraction
  prompt?" — all the owner's exact words). Still NOT built, by owner
  decision: roleplay-card update suggestions (incl. Plot Ledger
  maintenance) and model-rule drafting — both deferred as later designed
  features, so the assistant files memory drafts ONLY.
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

- **Owner's house style for user-facing wording (owner instruction, July 7
  2026):** labels are treated like titles — every major word capitalized
  ("Extraction Prompt", "Analyze History", "Maximum Suggestions Per
  Conversation"). All wording is concise, professional, and plain: short
  and to the point, easy to understand, never wordy or chatty. Dialogs ask
  direct questions ("Restore default extraction prompt?" — not "Are you
  sure you want to…"). Buttons are single words where one word does the
  job ("Reset", not "Reset to original"). Apply this to every new label,
  button, hint, and dialog; when retouching an old screen, bring its
  wording along.
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
  `prompt_assembly_template.md` were the original runtime/prompt specs but
  are now PRE-REVISION (each carries a ⚠️ banner): `owner_approved_rules.md`
  + the work orders describe the actual runtime since the July 2026
  rulings and the Stage 3.4 enforcer rework.
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
4. New per-chat preference added to the auto-naming copy block.
5. DB change → version bump + additive migration + fresh-install path.
6. Push, then confirm the `Android Checks` workflow run for your commit is green.

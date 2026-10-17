# Chat Storage and Backup Plan (handoff)

Status: **approved direction, October 17 2026. Implementation paused for a
fresh session.** This file is the single coordinated plan and the handoff for
the next coding session. It replaces the earlier draft `chat-storage-plan.md`,
which was never committed; its content is reconciled into this file.

Read `CLAUDE.md` before starting. Its rules apply throughout: work from
`beta/new-client` (never `main`), owner approval for user-facing wording, **no
new or changed log lines without owner approval**, shared styles for any UI,
no AI attribution in commits/PRs, push and get Android Checks green, and Codex
review (`@codex review` comment on the PR) before merging.

---

## 1. Goal and owner requirements (binding)

The goal is a reliable app that keeps every conversation and handles very large
conversations and backups without losing data. This is an internal storage
upgrade, not a redesign.

1. No lost, missing, truncated, or duplicated conversations or messages.
2. No change to the existing chat interface. History, folders, messages,
   attachments, regenerated versions, bookmarks, search, and every existing
   function keep working as before. The only new screen is a temporary
   full-screen migration progress screen with a count.
3. Original chat data stays protected until migration **and** a verified
   Recovery Backup made from the new storage both succeed. Then the obsolete
   original files are removed **automatically and internally** (no user-facing
   cleanup control). Never delete data that was not migrated, verified, and
   protected.
4. No conversation made unavailable by migration. Switch to the new database
   only when every readable conversation has migrated and passed verification.
   If migration fails, the existing storage stays in use and the failure is
   reported. Never substitute an empty or incomplete chat.
5. The Recovery Backup's 64 MiB chat restriction is part of this project and
   must be removed through bounded-memory processing, together with the other
   size limits that would stop large backups from working.
6. Existing Recovery Backups must keep restoring.
7. The Human-Readable Backup must become importable by the owner's separate
   app `SoulPhosphor/claude-history-viewer` (import feature built later, in that
   repo).
8. This project runs **before** the remaining Memory Assistant repair work
   (recorded in `project-plan.md` as Feature 0).
9. Engineering decisions are made by the implementer. Only decisions that
   change functionality, user experience, or data preservation go to the owner.

## 2. Status at handoff

| Item | Where | State |
|---|---|---|
| Startup out-of-memory crash fix | merged into `beta/new-client` via PR #144 (merge commit `42ba0c8`) | **Done.** Beta APK rebuilt and published (`beta-latest`). Owner has not yet confirmed on device. |
| 200,000-character message limit | PR #145, branch `ccr-2aeea9b3-5i4mu4`, head `c29fa62` | **Not merged.** First Codex review found the Whisper auto-send gap; fixed in `c29fa62`, reply posted on the thread. Android Checks on `c29fa62` were **in progress** at handoff. Next: confirm CI green, request `@codex review` again, resolve the thread, merge if clean. |
| Stage 1 chat database (`ChatStore`) | **Not pushed as code.** Copies of the unreviewed source are preserved in `docs/handoff/stage1-unreviewed/` (outside the build) | Written, never compiled by CI, never reviewed. Next session may start from these files (move them into `app/src/main/java/org/teslasoft/assistant/preferences/chatstore/` and `app/src/androidTest/java/org/teslasoft/assistant/preferences/chatstore/`), or rewrite. |

### What PR #144 changed (already on beta)

- `util/AtomicFileWriter.writeAndVerify`: encodes once, verifies temp and
  published files against the bytes in 64 KB chunks (`fileMatches`), refuses
  malformed UTF-16 (`isWellFormedUtf16`). Atomic temp → fsync → rename kept.
- `preferences/memory/MemoryExporter`: `autoExportIfDue` and `writeBackupNow`
  also catch `OutOfMemoryError` (existing log lines reused unchanged). A failed
  backup-before-reset returns null, so Reset memories is refused with the
  existing dialog.
- `ui/activities/MemoryBackupRestoreActivity.runOffThread(containOutOfMemory)`:
  only the manual export contains `OutOfMemoryError`.
- This is **containment**. `MemoryExporter.buildExportJson` still builds the
  whole export in memory and retries every start if it can't (no crash).

### What PR #145 changes (pending)

- `util/MessageLengthLimit` (`MAX_CHARACTERS = 200_000`, code points, so an
  emoji counts as one).
- `ChatActivity.prepareTypedTurn` refuses an over-limit message first (typed,
  pasted, hardware Enter, voice-into-box); `refuseOverLimitTranscript` covers
  cloud and local Whisper auto-send; `EditMessageDialogFragment` keeps the
  dialog open on an over-limit edit.
- Owner-approved strings: `message_too_long_title`, `message_too_long_send_body`,
  `message_too_long_edit_body`.
- Attached documents are **not** counted (owner ruling). AI replies are not
  limited.

## 3. Investigation findings (why this is needed)

### Chat storage today

- Each chat's full message list is one Gson JSON string under key `"chat"` in a
  per-chat EncryptedSharedPreferences file `enc.chat_<id>`
  (`preferences/SecurePrefs.kt`). The same file also holds other keys:
  `ChatSearchIndexManager.SEARCH_REVISION_KEY`,
  `SEARCH_PROJECTION_FINGERPRINT_KEY`, `ChatPreferences.CHAT_CONTENT_MODIFIED_AT_KEY`,
  and possibly others. All must be preserved.
- The chat list is one JSON array under key `"data"` in `enc.chat_list`; that
  file also holds folders (`ChatNavigationRepository.FOLDERS_KEY`) and other
  keys. Per-chat settings live in `settings.<id>` (small; stay where they are).
- `ChatPreferences.getChatByIdResult` (~line 372) reads the whole string and
  Gson-parses it into `ArrayList<HashMap<String, Any>>` (numbers become
  Double). EncryptedSharedPreferences also caches the whole encrypted XML.
  Several full copies of a chat are in memory to open it.
- `ChatPreferences.saveChatHistory` (~line 495) serializes the whole list and
  rewrites the file on every save.
- `ChatPreferences.getChatListResult(includeFirstMessage = true)` parses
  **every** chat's full history to compute `first_message` and
  `no_good_reply` for the list.
- Locked/corrupt handling is a careful state machine (`ChatStorageHealth`,
  `SecurePrefs` lock policy, `DamagedChatLog`, `DamagedChatRepair`,
  `preserveCorruptData`, `OutageReconciler`). The new store must keep the same
  guarantees: never show locked/corrupt as empty, never write over the only
  copy.

### Message shape

A message is a free-form map. Known keys include `message`, `isBot`,
`messageTime`, `message_id` (UUID on many messages;
`SearchableMessageProjection.MESSAGE_ID_KEY`), `variants` (JSON string of
regenerated versions), `canonicalVariant`, `displayVariant`,
`responseModel`, `responseProvider`, `reasoningText` (+ format, level,
summary, tokens), `responseTokens`, `visibleTextTokenEstimate`,
`imageConfirmationCard`, `imageProgressCard`, completion state
(`MessageCompletionState.KEY_STATE`), `activeMemoryAttribution`, and include
JSON (`preferences/includes/ChatInclude`: document `fullText`,
`condensedText`, `artifactLine`, image/PDF hashes and metadata). Unknown keys
exist and must survive. Messages are addressed by **position** throughout the
current code (`editMessage(position)`, `deleteMessage(position)`, adapter
positions).

### Readers and writers of chat storage (all must move)

Direct callers of `getChatByIdResult` / `getChatById` / `saveChatHistory` /
`importChatHistoryJson` / `editMessage` / `deleteMessage` /
`commitPendingConversation`, or direct `chat_<id>` / `chat_list` access:

- `ui/activities/ChatActivity.kt` (15k lines; holds whole-history collections,
  `canonicalConversationSnapshot`, request building)
- `ui/adapters/chat/ChatAdapter.kt` (positional edit/delete, variants)
- `ui/adapters/ChatListAdapter.kt`, `ui/fragments/tabs/ChatsListFragment.kt`,
  `ui/drawer/ChatListDetailsReader.kt`,
  `ui/fragments/dialogs/ConversationPreviewSheet.kt`,
  `ui/activities/ConversationSummaryActivity.kt`, `ui/activities/MainActivity.kt`
- `conversation/NewConversationCoordinator.kt`
- `preferences/chatsearch/ChatSearchIndexManager.kt` (derived FTS index in
  `chat_search.db`)
- `preferences/memory/TranscriptRecorder.kt` (`backfillExistingChats` reads
  every chat at startup), `preferences/memory/MemoryExporter.kt`
- `preferences/damaged/DamagedChatRepair.kt`
- `preferences/username/DefaultUsernameFill.kt`
- `imagegen/GeneratedImageFiles.kt`, `imagegen/ImageGenerationJobRegistry.kt`,
  `preferences/generatedimages/GeneratedImageCatalogBackfill.kt`
- `preferences/chatnavigation/ChatNavigationRepository.kt` (folders)
- `preferences/ChatRenameTransaction.kt`, `preferences/SnapshotRegistry.kt`,
  `preferences/OutageReconciler.kt`, `preferences/ChatStorageHealth.kt`
- Backup/restore: `preferences/backup/ChatSnapshotManifest.kt`,
  `preferences/backup/RecoveryBackupManager.kt` (older on-device recovery
  backup of `enc.chat_*` files, `BackupType.CHATS`),
  `preferences/backup/ChatRestoreManager.kt`,
  `preferences/backup/ChatSetReplacementCoordinator.kt` (the single boundary for
  "authoritative chat set replaced"), `preferences/backup/portable/*`
  (`ChatLogicalSerializer`, `ChatRestoreParticipant`,
  `PortableChatRestoreCoordinator`, `UnifiedPortableRestore`,
  `PortableRecoverySemanticValidator`, `PdfAttachmentPortableBackup`,
  `LegacyChatConversion`, `ConvertedChatRecoveryArchive`),
  `preferences/backup/readable/ReadableChatBackup.kt` + `ReadableChatFormats.kt`

### Backups today

- **Recovery Backup** (portable encrypted package, magic `PHOSBKP2`,
  `PortablePackageFormat`, `PackageCrypto`): `ChatLogicalSerializer.serializeV2`
  builds every chat into one `JSONObject` and `toString()`;
  `PortableRecoveryWriter` (~line 334) writes it as `chats.json`;
  `PortableRecoverySemanticValidator` (~line 134) re-reads it whole with
  `readText()` and parses it. Body encryption already streams
  (`PackageCrypto.encryptBody`).
- **Limits** (`PortableRecoveryLimits`): `CHATS_JSON_BYTES` 64 MiB,
  `GENERATED_IMAGE_CATALOG_BYTES` 32 MiB, `MODEL_ENDPOINT_SETTINGS_BYTES` 8 MiB,
  `APP_SETTINGS_BYTES` 16 MiB, `COMPANION_ROLEPLAY_ARCHIVE_BYTES` 512 MiB,
  `DATABASE_BYTES` 512 MiB, `IMAGE_ASSET_BYTES` 64 MiB,
  `COMPLETE_PACKAGE_BYTES` 1 GiB (`PackageCrypto.MAX_PACKAGE_BYTES`).
- Over the chat cap the writer fails as `Reason.SNAPSHOT_FAILED`, shown in
  `RecoveryBackupActivity` (~line 654) as `recovery_fail_snapshot`: "One or
  more databases could not be backed up." — misleading for a size problem.
- Restore (`ChatRestoreParticipant` ~line 80, `UnifiedPortableRestore`) reads
  `chats.json` whole. `ChatRestoreManager` / `RecoveryBackupManager` read ZIP
  entries with `readBytes()`.
- **Human-Readable Backup** (`ReadableChatBackup`): ZIP with one entry per chat
  (text and/or JSON via `ReadableChatFormats.formatText` / `formatJson`:
  `name`, `messages`, `folder_name`), incremental via a per-chat fingerprint
  over the whole messages JSON. Per-chat memory, not per-message.
- **Memory export** (`MemoryExporter.buildExportJson`, `MemorySeedCodec`): one
  JSON string with memories plus all chats under `app_chats`. Caused this
  week's crash (export was ~32–64 million characters).
- The owner's export size indicates the chat set is near or over 64 MiB, so the
  Recovery Backup may already be failing on the owner's device.

### Existing infrastructure to reuse

- SQLCipher (`net.zetetic.database.sqlcipher`) as used by `MemoryStore`,
  `LoreBookStore`, `GeneratedImageCatalogStore`, `ChatSearchStore`.
- `preferences/memory/DatabaseKeys` (one key slot per DB; never mints a key
  for an existing DB). Add `KEY_CHATS = "chats_db_key_hex"`.
- `preferences/backup/CorruptionErrorHandlers.Cipher` (never deletes on
  corruption; flags `DatabaseHealthState`). `BackupType.CHATS` already exists.
- `DirectDatabaseRestoreCoordinator`, `DatabaseHealthState`,
  `DatabaseRepairManager` patterns for authoritative DB health.
- `util/AtomicFileWriter`, `PreferenceCommitRollback`.
- The CI instrumented-test job (`.github/workflows/android-checks.yml`,
  "Recovery instrumentation and fault matrix") runs a listed set of
  `androidTest` classes on an x86_64 emulator; new instrumented test classes
  must be added to that list.

### Places that already hold message text (not usable as the store)

- `chat_search.db` (`ChatSearchStore`): one row per message, but a disposable,
  rebuildable derived index holding text only.
- `transcripts` table in `companion_memory.db`: text copies of turns for the
  Memory Assistant; wiped by Reset memories.

### Claude History Viewer (`SoulPhosphor/claude-history-viewer`)

Python desktop app, SQLite FTS5. Imports Claude and ChatGPT exports, detects
the source by content (`build_db.py` `detect_provider`, `parse_backup`
~line 1297), conversation identity `(id, provider)` in newer schema, but the
`conversations` key is still `id`-only in places (cross-provider collisions are
skipped). Reads files whole with `json.load`. No SpeakGPT support yet.

## 4. Architecture (one coordinated system)

### 4.1 Chat database `chats.db`

Dedicated SQLCipher database, separate from `companion_memory.db` (different
reset/restore lifecycle) and `chat_search.db` (disposable). Key
`DatabaseKeys.KEY_CHATS`. Before it is authoritative, a corrupt file is closed
and the staging DB discarded and rebuilt (it holds no unique data). After it is
authoritative, use `CorruptionErrorHandlers.Cipher(context, BackupType.CHATS)`
semantics: flag degraded, close, never delete; locked key → existing
locked-storage screen.

Schema v1 (as written in the stage 1 draft):

- `meta(key, value)` — includes `schema` and a random `store_id` (so revision
  numbers from a replaced/restored DB are never confused with this one).
- `chats(chat_id PK, list_order, list_row_json, prefs_json, readable, revision)`
  — `list_row_json` is the chat-list row exactly as stored; `prefs_json` holds
  every other key of `enc.chat_<id>` with its type; `revision` increments on
  every change to that chat.
- `messages(row_id PK, chat_id FK ON DELETE CASCADE, seq, message_id, is_bot,
  payload_json NULL, UNIQUE(chat_id, seq))` — `payload_json` is the message
  map exactly as Gson stores it today (all fields, including variants,
  includes, unknown keys).
- `message_parts(message_row FK CASCADE, part_index, part_text)` — payloads over
  128K characters are stored in ordered parts (never splitting a surrogate
  pair) so no row outgrows the cursor window. Always read back as one message.
- `list_state(key, value_json)` — the other `enc.chat_list` keys verbatim
  (folders and their order, etc.).

API (stage 1 draft): `putChat/getChat/listChats/deleteChat/revision`,
`putListState/getListState`, `messageCount`, `readPage(offset, limit)`,
`readBefore(beforeSeq, limit)` (chat-screen paging), `messageAt(position)`,
`findByMessageId`, `positionOf(seq)`, `forEachMessage` (page-at-a-time
streaming), `appendMessage`, `replaceMessage(seq)`, `deleteMessage(seq)`,
`deleteAfter(seq)` (Regenerate / Make Current), `replaceAllMessages(Sequence)`
(all-or-nothing, consumes rows lazily), `inTransaction`.

**Design choice:** payloads are stored verbatim rather than normalized into
columns, so migration can be verified byte-for-byte and nothing is lost. Only
lookup fields (`message_id`, `is_bot`) are extracted.

**Documents** (PR 7): include document text moves out of the message payload
into `include_text_parts(include_id, part_index, text)` linked by include ID,
with the payload keeping a reference; text is loaded only when viewed,
condensed, or sent. Request building for a book-length document still
assembles the request in memory; the existing `request_prepare_failed` check
stays.

### 4.2 Shared interfaces (build once, used by every later PR)

Define these in the database PR so later PRs don't rewrite each other:

1. **`ChatStore`** (above) — the only code that touches `chats.db`.
2. **`ChatSource`** — a read interface with two implementations:
   `LegacyChatSource` (EncryptedSharedPreferences, used by migration and by the
   app until the switch) and `DatabaseChatSource` (`ChatStore`). Operations:
   list chats (metadata only, no message parsing), per-chat state
   (OK/EMPTY/MISSING/LOCKED/CORRUPT, matching `ChatStorageHealth.ReadState`),
   stream messages as raw JSON payload strings in order, read per-chat stored
   keys. Migration, backups, readable export, memory export, search index and
   transcript backfill all read through this, so they work before and after the
   switch without being rewritten.
3. **Streaming JSON codec** — a `JsonWriter`/`JsonReader`-based (Gson streaming)
   writer and reader for the Recovery Backup chat payload and the readable
   per-chat JSON, which consume/produce one message at a time.
4. **`ChatStorageAuthority`** — single flag (stored durably, read once per
   process) saying whether legacy files or `chats.db` are authoritative.
   `ChatPreferences` routes through it.

### 4.3 Migration

- Runs at app start before any chat UI loads, behind a full-screen progress
  screen ("n of N"), non-dismissible, closes itself. Wording is drafted with
  the build and shown to the owner in that PR (owner approved drafting it).
  Must use shared styles (`ui-style-guide.md`).
- Gate: `MainActivity`/`ChatActivity`/any chat entry point redirect to the
  progress screen while migration is running, so no edits happen mid-move.
  Background chat writers (image generation jobs, transcript capture,
  scheduled backups) must wait or be blocked during migration.
- One chat at a time: read legacy `chat` string (unavoidably whole: the
  EncryptedSharedPreferences value is one AEAD ciphertext), parse it with a
  **streaming** Gson `JsonReader` element by element, write each element's
  JSON (`JsonElement.toString()`, preserves number text) as a row inside one
  transaction per chat, plus `list_row_json`, `prefs_json`.
- Verify per chat: stream the legacy string again and compare each element's
  JSON with the stored payload in order, count included; compare list row and
  prefs. Record per-chat progress durably (`meta` keys); a chat not marked
  verified is deleted from the staging DB and redone on resume.
- Locked / corrupt / failed chats (today's non-readable states) are recorded
  `readable = 0` with no messages, keep their legacy file, and keep behaving as
  today (existing damaged-chat flows operate on the legacy file). They don't
  block the switch.
- A readable chat that fails to copy or verify (including one too large to read
  even this way) **blocks the switch**: the app keeps using legacy storage
  unchanged, and migration retries next start. Reporting the failure needs a
  log line → **owner approval of the exact log text first** (CLAUDE.md).
- Switch: only when every readable chat is verified, set
  `ChatStorageAuthority` in one durable write. After the switch the legacy
  files are never read again for chats that moved.
- The chat search index (`chat_search.db`) is rebuilt from `DatabaseChatSource`
  after the switch (it is disposable).

### 4.4 Original-file removal

Automatic, internal, no UI. Only when **all** hold: switch done and every moved
chat verified; a Recovery Backup created **after** the switch from `chats.db`
completed and passed verification (finished-package reopen + semantic
validation); the file belongs to a moved, verified chat. Locked / corrupt /
unmoved chats' files are never removed. Removal runs on a later start, one file
at a time, journaled so interruption resumes. Because a qualifying Recovery
Backup requires the 64 MiB limit to be gone, removal comes after the backup
rebuild.

### 4.5 Recovery Backup rebuild

- Keep `chats.json` as one logical payload but **write it streaming** from
  `ChatSource` (one message at a time) straight into the staged file; never
  build the JSON in memory.
- Introduce an explicit chats payload version (new `schemaVersion` for the
  `chats-json` artifact, e.g. 3) only if the representation changes (for
  example, document text carried as separate entries). Restore keeps reading
  versions 1 and 2 (`LegacyChatConversion`, current v2) via the streaming
  reader.
- Semantic validation streams the file; it checks structure, counts, IDs,
  references (PDF assets via `PdfAttachmentPortableBackup.requiredHashes`
  equivalents computed while streaming), not just bytes.
- Restore streams `chats.json` into a **staged** `chats.db`, validates it, then
  swaps through `ChatSetReplacementCoordinator`, preserving existing merge
  planning (`ChatMergePlanner`, `ChatRestorePlanner`) and coordinated rollback
  across selected restore categories. Neither the restore plan nor the
  current-data rollback snapshot may be built whole in memory (store them in
  staged DB tables).
- Capture consistency: keep `PortableBackupCaptureStability` /
  `PortableBackupMutationTokens`; with `chats.db`, capture from a single read
  transaction or a `VACUUM INTO`-style snapshot so chats, settings and
  attachments come from one moment.
- **Limits:** remove `CHATS_JSON_BYTES` as a memory ceiling. Replace with:
  free-space check before writing (staged size estimate × safety factor, using
  `ImportStorageGuard`-style reserve), and bounded parsing protections against
  malformed archives (per-entry maximums sized for real data, ZIP expansion
  ratio checks). Review `COMPLETE_PACKAGE_BYTES` (1 GiB) and
  `DATABASE_BYTES` (512 MiB) against realistic sizes; raise or remove where
  they only reflected memory use, keeping malformed-archive guards.
- **Errors:** add distinct failure reasons (not enough storage; exceeded a
  supported size; chat storage unavailable; verification failed) instead of
  `SNAPSHOT_FAILED` / "databases could not be backed up". New wording →
  **owner approval**, following the existing error structure (what failed,
  why, what was preserved, what to do).
- Success is published and baselines advanced only after verification.
  Failure keeps existing backups and blocks any backup-dependent reset.

### 4.6 Human-Readable Backup

- Keep current behavior: title-based filenames, folder options, text/JSON
  choice, speaker naming, Include Thinking, no token accounting.
- JSON entries become **versioned per-chat JSON**, written streaming:
  `format` (e.g. `"phosphor-readable-chat"`), `format_version`, `app`,
  `chat_id`, `name`, `folder_name`, `exported_at`, and `messages` with
  `message_id` (existing UUID when present, else stable derived ID), explicit
  `index`, `role`, speaker name, `text`, `timestamp` (only when stored; never
  invented), regenerated versions, thinking (when enabled), attachment
  references. Text format stays as is.
- Incremental selection uses `chats.revision` + `store_id` + export options +
  format version + locale + folder/name changes instead of whole-history
  fingerprints.
- Viewer side (separate repo, later): add a `speakgpt` provider detected by
  `format`, identity `(chat_id, "speakgpt")`, message identity by
  `message_id`, transactional incremental import that preserves viewer
  annotations, streaming parse. Importing never deletes anything on the phone.

### 4.7 Memory Assistant dependencies

- `TranscriptRecorder.recordTurn` captures turns into `companion_memory.db`
  independently of chat storage — unchanged.
- `TranscriptRecorder.backfillExistingChats` (startup) must read through
  `ChatSource` page by page and must not run during migration.
- Archivist bookmarks (`analysis_chat_bookmarks`, `analysis_chat_ranges`)
  reference transcripts by chat ID, not message positions — unaffected. Chat
  IDs are preserved by migration.
- `MemoryExporter` (`app_chats`): rewrite `buildExportJson` to stream to the
  file through `ChatSource` and the streaming codec; then remove the
  OOM-containment-as-workaround dependency (keep the catch as defense).
- Reset memories must never touch `chats.db`.

### 4.8 200,000-character limit and storage

The limit bounds user-typed message payloads, so ordinary message rows are
bounded. AI replies, regenerated variants, and document text are not bounded by
it; `message_parts` and the document text parts handle them. Existing stored
messages over the limit migrate unchanged.

## 5. PR sequence

Each PR: branch from current `beta/new-client`, Android Checks green,
`@codex review`, address findings, merge. No PR changes user-visible behavior
except where stated.

| # | PR | Depends on | Contents | Done when |
|---|---|---|---|---|
| 0 | Finish PR #145 (message limit) | — | CI green on `c29fa62`, Codex re-review, merge | merged |
| 1 | Plan in repo | — | This file + `project-plan.md` Feature 0 (branch `docs/chat-storage-handoff`) | merged |
| 2 | Chat database + interfaces | 1 | `ChatStore`, `DatabaseKeys.KEY_CHATS`, `ChatSource` + `LegacyChatSource`, `ChatStorageAuthority` (off), streaming JSON codec; instrumented tests added to the CI list | tests green; app behavior unchanged |
| 3 | Migration engine (switch kept off) | 2 | Copy + verify + durable progress + resume; damaged chats; progress screen (wording to owner); migration gating | interruption/resume, damaged, locked, large-chat tests green; switch still off |
| 4 | Switch + compatibility layer | 3 | `ChatPreferences` and all listed callers on `DatabaseChatSource`/`ChatStore` via the authority flag; list metadata without parsing histories; search index rebuild; all backup/restore paths reading/writing via the new store (old serializers through `ChatSource`); turn the switch on | every listed caller converted; full app regression on device by owner |
| 5 | Paged chat screen and sending | 4 | `ChatActivity`/`ChatAdapter` page by page (newest first, load older on scroll, jump-to-message for search/bookmarks/summary), positional ops mapped to `seq`, request builder reads newest-first only what it needs | memory tests on huge chat; no UI change |
| 6 | Search index, list, summaries, transcript backfill paged | 4 | incremental search updates by revision; paged readers | tests |
| 7 | Documents stored separately | 4 | include text parts; payload reference; migration of existing include text | tests incl. book-length documents |
| 8 | Recovery Backup rebuild | 4 (7 if payload version changes) | streaming write/validate/restore, staged restore DB, limits replaced, distinct errors (wording to owner) | 64 MiB+ chat set backs up and restores on emulator; old v1/v2 packages restore |
| 9 | Readable Backup + memory export streaming | 2, 4 | versioned per-chat JSON, revision-based incremental, streaming `MemoryExporter` | tests; format documented for the viewer |
| 10 | Original-file removal | 4, 8 | automatic journaled removal after verified post-switch Recovery Backup | tests incl. interruption; damaged files never removed |
| — | Viewer importer | 9 | in `claude-history-viewer` repo | separate project |

## 6. Testing requirements (all PRs, as relevant)

- One giant chat (tens of thousands of messages), messages at the 200,000 limit,
  a single huge AI reply, book-length documents, many chats.
- Unicode/emoji, regenerated variants, attachments (documents, images, PDFs),
  bookmarks, folders, pinned chats, unknown fields.
- Migration interrupted at every step; resume without duplicates; partial chat
  never authoritative.
- Locked key, corrupt chat, corrupt database, low storage, concurrent edits
  (blocked during migration).
- Recovery Backup over 64 MiB: create, verify, restore; every older package
  version restores; restore rollback.
- Peak heap measured on a 256 MB heap during migration, opening, scrolling,
  sending, editing, backup, and restore, with headroom.
- Owner on-device confirmation after PRs 4, 5, and 8.

## 7. Unresolved technical risks

- Migration of an already oversized legacy chat: one AEAD ciphertext plus its
  plaintext must fit in memory at once. Mitigated by migrating first, alone,
  with streaming parse; a chat that still cannot be read blocks the switch
  (requirement 4) and needs a separate recovery path.
- `ChatActivity` is ~15k lines with whole-history assumptions; PR 5 is the
  largest and riskiest code change.
- Restore through `ChatSetReplacementCoordinator` and the older on-device
  `RecoveryBackupManager` (which snapshots `enc.chat_*` files) must both be
  ported to `chats.db` in PR 4 or chats restored after the switch would be
  ignored.
- Model request building for huge documents remains in-memory.
- The owner's chat set may already exceed 64 MiB, so Recovery Backup may be
  failing now; originals cannot be removed until PR 8 lands.

## 8. Instructions for the next session

1. Read `CLAUDE.md`, then this file. Do not start from scratch.
2. Check PR #145: if Android Checks on `c29fa62` are green, comment
   `@codex review`; on a clean review resolve the one open thread
   (`PRRT_kwDOSZQRks6tlexq`, Whisper auto-send, already fixed in `c29fa62`)
   and merge into `beta/new-client`.
3. Merge this documentation PR (after owner OK or as directed).
4. Start PR 2 from `beta/new-client`. The unreviewed stage 1 draft is in
   `docs/handoff/stage1-unreviewed/`; move it into the source tree, add the
   test class to the CI instrumented list, then add the interfaces from §4.2.
5. Report to the owner briefly, with structure, only decisions that affect
   functionality, user experience, or data. Ask before adding any log line.
6. Do not keep the session alive polling; check CI when needed.

# Chat Storage and Backup Plan (handoff)

Status: **approved direction, October 17 2026. Sequence revised October 18
2026 (owner ruling, see §5).** This file is the single coordinated plan and the
handoff for the next coding session. It replaces the earlier draft
`chat-storage-plan.md`, which was never committed; its content is reconciled
into this file.

**Owner ruling, October 18 2026:** the new Recovery Backup and restore system
must be implemented and tested before the new chat database becomes
authoritative for existing conversations. Migration activation stays disabled
until the storage, backup, verification, and restore paths are all ready. The
PR sequence is ordered so that no PR builds a temporary path that a later PR
immediately rewrites.

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
| Startup out-of-memory crash fix | PR #144, merged into `beta/new-client` | Done. |
| 200,000-character message limit | PR #145, merged into `beta/new-client` | Done. |
| Stage 1, this plan | PR #146, merged | Done. |
| Stages 2 and 3, storage foundations and Recovery Backup write | PR #148, merged into `beta/new-client` (replaced #147) | Done in code. **Owner on-device check pending:** run one full Recovery Backup on the beta. |
| Stages 4–13 | not started | Next: session A (stage 4). See §8. |

**Interim restore limit (until stage 4).** After stage 3, Recovery Backups of
any size can be created and verified, but the current restore engine still
reads the chats artifact whole. It refuses a chats artifact over 64 MiB with
the existing "too large" message
(`PortableRecoveryLimits.WHOLE_READ_RESTORE_CHATS_JSON_BYTES`). Stage 4 removes
this limit. Chats in the app are not affected; smaller backups restore as
before.

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

### Storage audit (October 18 2026)

A complete search of `beta/new-client` for every chat read and write
(including background work) was done before stage 3. Results:

**Additional callers** (not in the list above; all list-level reads or
journal work through `ChatPreferences`, so they move with the compatibility
layer): `imagegen/ImageUsageRecorder.kt`, `preferences/RenameJournal.kt`,
`preferences/chatdeletion/ChatDeletionCoordinator.kt`,
`preferences/damaged/DamagedChatBackupReader.kt`,
`preferences/damaged/DamagedChatLog.kt`, `tts/api/TtsUsage.kt`,
`ui/adapters/SearchResultAdapter.kt`,
`ui/fragments/dialogs/AddChatDialogFragment.kt`,
`preferences/memory/archivist/Archivist.kt` (calls `getChatList` **with**
previews, which today parses every history),
`preferences/backup/ChatRestorePlanner.kt`,
`preferences/backup/portable/PortableBackupMutationTokens.kt` (digests the
whole `shared_prefs` tree, including chat files).

**Code that bypasses `ChatPreferences`** and touches chat files directly
(each must move to the compatibility layer in stage 6):

- `NewConversationCoordinator` writes `chat_<id>` (`"chat" = "[]"`) when a
  conversation is created, and clears it when one is abandoned.
- `ChatNavigationRepository` writes the list rows (`"data"`) and `folders`
  **in one commit** (moves, folder deletion). The database layer must
  offer the same all-or-nothing update of list rows and list state.
- `MainActivity` writes an empty `"data"` for a new user.
- `ChatSearchIndexManager` reads `SEARCH_REVISION_KEY` straight from
  `chat_<id>`.
- `ChatLogicalSerializer` reads `CHAT_CONTENT_MODIFIED_AT_KEY` straight from
  `chat_<id>` (replaced in stage 3).
- `DamagedChatRepair`, `SnapshotRegistry`, `OutageReconciler` and
  `SecurePrefs` copy, scan and restore the encrypted files themselves.
  These stay on legacy files: they serve unmoved (damaged/locked) chats, and
  recovered chats are then imported (§4.3).

**Chat identity (owner ruling, October 18 2026).** Stable chat identity was
settled in August and September (Phase 8; `phase-8-regression-audit.md`,
"Phase 8.1 repository-wide chat identity audit"; commit "Preserve chat IDs
on rename and use stored IDs for direct lookups"). This project keeps it
exactly and redoes none of it:

- New chats have a stored random UUID `"id"`, and renaming never changes
  it. Folders have stable UUIDs independent of their names.
- Older chat-list rows without a stored `"id"` take their ID from the title
  through `ChatPreferences.storedChatId` (the explicit `"id"`, else
  `Hash.hash(name)`). This fallback is **intentional legacy compatibility**.
  Migration never writes an `"id"` into such a row and never reassigns an
  identity; list rows are copied verbatim.
- Every reader gets a chat's identity only from `storedChatId` (Phase 8
  source contracts enforce this). `LegacyChatSource` and migration use it;
  the database keeps the invariant **`chats.chat_id` = `storedChatId` of its
  stored list row**, checked by migration verification and the integrity
  check.
- Renaming an older no-ID chat moves it to the new title's ID, as today. The
  compatibility layer keeps the existing contract and its steps: settings
  copied and verified first; then the history move and the list-row pointer
  flip as **one database transaction** (`ChatStore.renameChatId` plus the
  row update, PR 2), replacing the file-level `ChatRenameTransaction` ladder
  only for the history and list; then the existing follow-up moves
  unchanged (`ImageGenerationJobRegistry.rename`,
  `GeneratedImageCatalogStore.repointOriginChat`, attachment image and PDF
  directories, `MemoryStore.repointChat`), with `RenameJournal` still
  recording the move and retrying the memory re-point at startup.
- A rename-journal entry still pending blocks migration (see "Startup
  order"), so no chat is mid-move when it is copied.

**How chats reach `beta/new-client`, and their identities (investigated
October 18 2026, read-only).** The beta is a separate installation
(`com.soulphosphor.phosphorshines.beta`), so it began with no chats. Every
way a chat-list row can be created there stores an explicit `"id"`:

| Path | Identity written |
|---|---|
| Created in the beta (`NewConversationCoordinator.allocateUniqueId`, also used by chat-file import) | new random UUID |
| Imported from an old export through the legacy converter (`LegacyChatConverterActivity` → `LegacyChatConversion` → `ChatLogicalImportPlan`) | a genuine UUID is kept; a title-derived ID is replaced by a new UUID |
| Restored from a Recovery Backup (`PortableChatRestorePlan`, v1 and v2) | the declared ID is written into `"id"`: a UUID, or a title-derived hash **frozen as an explicit ID** |

The title-hash creation path (`addChat`) is gone and guarded by a Phase 8
source contract. So the beta should hold **no row without a stored `"id"`**.
A chat restored with a frozen hash ID keeps it permanently, and renaming it
never moves it, because `editChat` moves only rows without `"id"`. Those IDs
are stable even though they are not in UUID form, and changing them would
be a reassignment, which is ruled out.

The owner's chats were most likely transferred on **September 7 2026**. The
converter's UUID assignment landed the day before (commit "Convert legacy
chats without touching live storage", September 6), and the September 7
code already replaced title-derived IDs with new UUIDs, so chats transferred
that way should all hold UUIDs. Which exact build ran the transfer is not
recorded in the repository; the on-device counts-only check (owner approval
needed later) can confirm it.

**Conclusion: no additional identity migration is needed.** Migration copies
identities as they are. The no-`"id"` fallback and its rename move path stay
supported in both storages as compatibility (and `ChatStore.renameChatId`
remains their database side), but on the beta they are not expected to
occur. Migration verification and the PR 8 end-to-end suite include a
chat list with UUID, frozen-hash and no-`"id"` rows to prove each keeps
its identity and references.

**Restore freezes an absent ID (existing behavior, kept).** Recovery restore
writes each chat's declared ID into `"id"` in every backup format, v3
included (`PortableChatRestorePlan`). A no-`"id"` row therefore comes back
with its title hash frozen as an explicit ID: the same identity, the same
references, but a later rename no longer moves it. This is the Phase 8
restore behavior and is not changed; v3 does not record whether `"id"` was
absent. The stage 8 comparison treats an absent `"id"` and an explicit
`"id"` equal to the title hash as the same identity for restored rows.

**Background work.** The app is one process, but chat storage is reached
without any chat screen open: WorkManager (`AutoBackupWorker`,
`DefaultUsernameFill`), foreground services (generation, image generation
and its job registry, summarizer, memory analysis, restore) and the
startup maintenance pipeline (`MainApplication`). Pausing writers during
migration is therefore a **process-wide gate** in the compatibility layer,
not a screen redirect: while migration runs, chat writes wait (or a worker
defers and retries), and nothing writes chat storage from outside the gate.

**Pending conversations.** A new conversation has a `chat_<id>` file and a
`pending_conversation_journal` entry **before** it is in the chat list, so a
list-driven migration would miss it. Migration runs only after
`NewConversationCoordinator.recoverPendingCommits` has committed or
abandoned every pending conversation, and refuses to start while one
remains.

**Startup order.** `MainApplication` already runs ordered recovery steps:
chat-restore resume, generated-image and companion restore recovery,
outage reconciliation, chat-deletion recovery, `RenameJournal.reconcile`,
pending-conversation recovery, `DefaultUsernameFill`, folder schema
migration, then search, transcript backfill and memory export. Migration
runs **after the folder schema migration and before search**, and only when
every earlier step has settled (no chat-restore recovery pending, no outage
file, no deletion or rename journal entry, no pending conversation);
otherwise it is skipped this start and retried next start.

**Orphan files.** `chat_<id>` files that no list row or pending entry names
(left by old failures) are not migrated and are never removed by stage 13.

**Older backups.** The raw-file chat backup (`RecoveryBackupManager`,
`BackupType.CHATS`) is no longer created anywhere. Restore runs only through
the portable path, which writes converted archives into
`ChatRestoreManager`; older packages reach it through `LegacyChatConversion`.
Stage 4 replaces that engine with the commit targets.

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
locked-storage screen. Detection alone is not enough: **stage 4** extends the
health check, `DatabaseRepairManager` (which today rejects `BackupType.CHATS`)
and the existing repair flow to `chats.db`, so a damaged database can be
replaced from a verified Recovery Backup through the same restore path, and
stage 8 tests corruption → repair before activation. Any new wording for
that flow is approved by the owner before code.

Schema v1 (PR 2):

- `meta(key, value)` — includes `schema` and a random `store_id` (so revision
  numbers from a replaced/restored DB are never confused with this one), the
  saved-list marker `list_saved`, and `revision_counter`, the store's
  **generation**. Every change to the store advances the generation in the
  same transaction: chat create, update, rename and delete; every message,
  part and document-text write or delete; every `list_state` (folders) write;
  and marking the list saved. `ChatStore.generation()` reads it. Two reads
  with the same `store_id` and generation saw the same content, which is what
  capture tokens and backup receipts rely on. Chat revisions are drawn from
  the same counter.
- `chats(chat_id PK, list_order, list_row_json, prefs_json, readable, revision,
  history_saved)` — `list_row_json` is the chat-list row exactly as stored;
  `prefs_json` holds every other key of `enc.chat_<id>` with its type;
  `revision` is owned by the database and increments on every change to that
  chat, including list-row, preference and readability updates; `history_saved` keeps a
  never-saved history (MISSING) distinct from a saved empty one (EMPTY).
- `messages(row_id PK, chat_id FK ON DELETE CASCADE, seq, message_id, is_bot,
  payload_json NULL, UNIQUE(chat_id, seq))` — `payload_json` is the message
  map exactly as Gson stores it today (all fields, including variants,
  includes, unknown keys).
- `message_parts(message_row FK CASCADE, part_index, part_text)` — payloads over
  128K characters are stored in ordered parts (never splitting a surrogate
  pair) so no row outgrows the cursor window. Always read back as one message.
- `list_state(key, value_json)` — the other `enc.chat_list` keys verbatim
  (folders and their order, etc.).

API (PR 2): `putChat/getChat/listChats/deleteChat/revision`,
`putListState/getListState`, `messageCount`, `readPage(offset, limit)`,
`readBefore(beforeSeq, limit)` (chat-screen paging), `messageAt(position)`,
`findByMessageId`, `positionOf(seq)`, `forEachMessage` (page-at-a-time
streaming), `appendMessage`, `replaceMessage(seq)`, `deleteMessage(seq)`,
`deleteAfter(seq)` (Regenerate / Make Current), `replaceAllMessages(Sequence)`
(all-or-nothing, consumes rows lazily), `inTransaction`.

**Design choice:** payloads are stored verbatim rather than normalized into
columns, so migration can be verified byte-for-byte and nothing is lost. Only
lookup fields (`message_id`, `is_bot`) are extracted.

**Documents:** include document text is stored outside the message payload in
`include_text_parts(message_row, include_id, part_index, part_text)`, owned by
its message row (removed with the message) and addressed by message plus
include ID, with the payload keeping a reference. Because parts belong to one
message, copies of a message in different chats (for example a restore's Keep
Both) never share document text. This table is part of schema v1 (PR 2) and
migration writes it directly, so `chats.db` needs **no schema upgrade after
activation**. `DatabaseChatSource` reassembles the full payload whenever a
caller asks for it (backup, verification, the compatibility layer), so the
backup format does not depend on this storage detail. A later PR (§5, PR 12)
only changes screens to load document text on demand (when viewed, condensed,
or sent). Request building for a book-length document still assembles the
request in memory; the existing `request_prepare_failed` check stays.

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
   `ChatPreferences` routes through it. Until the activation PR, no production
   code path can set it to the database; instrumented tests may set it on a
   test install so the database paths are exercised.
5. **Staged chat database** — create a `chats.db` file in a staging location,
   fill it, validate it, or discard it. Migration, restore, and backup capture
   all use this one helper. It never publishes a stage; publication belongs
   only to the database commit target (item 6, PR 4).
6. **Chat-set commit target** (built in PR 4, with restore) — the step that
   makes a validated staged chat set authoritative. Two implementations:
   *database swap* (publish the staged `chats.db` through the existing
   crash-safe, journaled `DirectDatabaseRestoreCoordinator`, extended to
   chats, rather than a second swap mechanism) and *legacy write* (write each
   chat from the staged database into `enc.chat_<id>` / `enc.chat_list`, the
   way restore writes today). Per-chat settings (`settings.<id>`) are not in
   `chats.db` under either storage: a restore stages the backup's typed
   settings beside the staged database, and **both** targets apply them and
   roll them back with the chats, as `ConvertedChatRecoveryArchive` does today.
   The legacy target is **not temporary**: legacy
   storage stays authoritative on any device where migration has not
   succeeded (requirement 4), so restore must be able to land there. It is
   retired only with the legacy store itself.
7. **Change token** — `ChatSource` exposes a per-chat change token for
   incremental exports: legacy = the existing whole-history fingerprint,
   database = `store_id` + `revision`. Callers never care which.

Because backup, restore, readable export and memory export are written against
these interfaces, they are built once, tested against both storages, and do not
change when the database becomes authoritative.

### 4.3 Migration

- Preconditions, placement, the process-wide write gate and pending
  conversations: see "Storage audit" in §3.
- Runs at app start before any chat UI loads, behind a full-screen progress
  screen ("n of N"), non-dismissible, closes itself. The wording is proposed
  to the owner and approved **before** it is written into any file
  (`CLAUDE.md` §3 approval gate). Must use shared styles (`ui-style-guide.md`).
- **Activation is a separate step.** The migration engine, progress screen
  and gating are built and tested with the trigger disabled; nothing runs
  migration on a device until the activation PR (§5, PR 9).
- Gate: `MainActivity`/`ChatActivity`/any chat entry point redirect to the
  progress screen while migration is running, so no edits happen mid-move.
  Background chat writers (image generation jobs, transcript capture,
  scheduled backups) must wait or be blocked during migration.
- One chat at a time: read legacy `chat` string (unavoidably whole: the
  EncryptedSharedPreferences value is one AEAD ciphertext), and read it
  through `LegacyChatSource`, which copies each message token by token from
  that text (no `JsonElement` tree; number text preserved) straight into the
  staged row's parts via `copyPayloadTo`, inside one transaction per chat,
  plus `list_row_json`, `prefs_json`. Verification compares the same way,
  part by part, never building either side whole.
- Verify per chat: stream the legacy string again and compare each element's
  JSON with the payload `DatabaseChatSource` returns for it in order (the
  **reassembled** payload, document text put back from `include_text_parts`,
  never the stored reference form), count included; compare list row and
  prefs. Record per-chat progress durably (`meta` keys), each verified marker
  storing tokens over that chat's **complete** legacy state (history token,
  a fingerprint of its other `chat_<id>` values, and its list row), plus
  tokens for the list and list values. On resume, a chat not marked verified **or whose legacy
  token has changed** since it was verified (an edit or restore between
  attempts) is deleted from the staging DB and redone. When the list token
  has changed, staged chats whose IDs are no longer in the current list
  (deleted between attempts) are removed from the staging DB as well. The
  switch re-checks every token immediately before it happens.
- Locked / corrupt / failed chat **histories** (today's non-readable states,
  for chats the readable chat list names) are recorded `readable = 0` with no
  messages, keep their legacy file, and keep behaving as today (existing
  damaged-chat flows operate on the legacy file). They don't block the switch.
  When such a file becomes readable again (the lock clears) or
  `DamagedChatRepair` recovers it, its **complete** state (history and every
  other `chat_<id>` value, which a locked file could not provide at
  migration) is imported into the authoritative row and verified in one
  transaction before the repair counts as complete, so it reads normally and
  is included in backups (stage 6).
- A chat **list** that is not readable (LOCKED, CORRUPT or FAILED), or list
  values (folders and the like) that cannot be read, **blocks migration**:
  without the list the chats, their rows, folders and order cannot be
  enumerated, and an apparently empty database must never become
  authoritative.
- A readable chat that fails to copy or verify (including one too large to read
  even this way) **blocks the switch**: the app keeps using legacy storage
  unchanged, and migration retries next start. Reporting the failure needs a
  log line → **owner approval of the exact log text first** (CLAUDE.md).
- Switch: only when every readable chat is verified, set
  `ChatStorageAuthority` in one durable write. After the switch the legacy
  files are never read again for chats that moved. The switch code exists
  from PR 5 but stays unreachable until the activation PR, which may land
  only after the readiness gate in §5 is met.
- The chat search index (`chat_search.db`) is rebuilt from `DatabaseChatSource`
  after the switch (it is disposable).

### 4.4 Original-file removal

Automatic, internal, no UI. Only when **all** hold: switch done and every moved
chat verified; a Recovery Backup created **after** the switch from `chats.db`
completed and passed verification (finished-package reopen + semantic
validation); the file belongs to a moved, verified chat. Locked / corrupt /
unmoved chats' files are never removed. Removal runs on a later start, one file
at a time, journaled so interruption resumes. The backup rebuild (PRs 3–4)
lands before activation, so the first post-activation Recovery Backup can
qualify.

### 4.5 Recovery Backup rebuild

- Built **before activation** and read through `ChatSource`, so it backs up
  the current legacy storage first (the owner gets a working backup of
  existing conversations over 64 MiB before anything migrates) and backs up
  `chats.db` after activation without changes.
- Keep `chats.json` as one logical payload but **write it streaming** from
  `ChatSource` straight into the staged file; never build the JSON in memory.
  Each message is written with `ChatSource.Message.copyPayloadTo` (PR 2), part
  by part, so even one very large message is never held whole; the reader
  likewise stores a large payload into the staged database in parts.
- **Format decision:** the chats payload carries each message's full payload
  verbatim, exactly as v2 does. Internal storage details (message parts,
  document text parts) are reassembled by `ChatSource` and never appear in the
  backup format. PR 3 first **audits every key** in `chat_<id>` and
  `chat_list` and every `chats.db` field: v2 already carries list rows,
  messages, `settings.<id>`, referenced folders and the content-modified
  time; derived keys (search revision and fingerprints) are rebuilt, not
  backed up. v2 cannot represent `history_saved` (it writes MISSING and
  EMPTY both as an empty `messages` array), so **PR 3 introduces
  `schemaVersion` 3**, carrying `history_saved` and any other user-data
  `prefs_json` / `list_state` entries the audit finds, so a database backup
  restores losslessly. Restore keeps reading versions 1 and 2
  (`LegacyChatConversion`, current v2) via the streaming reader, and reads
  v3.
- Semantic validation streams the file; it checks structure, counts, IDs,
  references (PDF assets via `PdfAttachmentPortableBackup.requiredHashes`
  equivalents computed while streaming), not just bytes.
- **Message-ID uniqueness is per chat.** Today `PortableChatRestorePlan`
  rejects a message ID repeated anywhere in the set, but a Keep Both restore
  copies a chat with its message IDs unchanged, so the next backup would fail
  verification. PR 3 scopes the check to each chat (after confirming no
  reader relies on store-wide uniqueness; if one does, Keep Both remaps the
  copied IDs and their references instead), with a Keep Both → backup →
  verify round-trip test.
- Restore streams `chats.json` into a **staged** `chats.db`, validates it, then
  commits through `ChatSetReplacementCoordinator` using the commit target for
  whichever storage is authoritative (§4.2 item 6), preserving existing merge
  planning (`ChatMergePlanner`, `ChatRestorePlanner`) and coordinated rollback
  across selected restore categories. Neither the restore plan nor the
  current-data rollback snapshot may be built whole in memory (store them in
  staged DB tables, or copy legacy files byte-for-byte without parsing).
  Today `ChatRestoreParticipant.stage` builds the current chat set whole via
  `serializeV2` for rollback; that is replaced.
- The older on-device restore (`ChatRestoreManager`, `RecoveryBackupManager`)
  moves onto the same staged-database and commit-target path in the same PR.
- Capture consistency: keep `PortableBackupCaptureStability` /
  `PortableBackupMutationTokens`, and **add the chat storage's generation to
  the mutation tokens** (the `chats.db` store-wide revision counter and
  `store_id`; on legacy storage the existing chat-file tokens). A change to
  chats, `settings.<id>` or attachments between the before and after tokens
  refuses or retries the capture, so a database snapshot never ships beside
  newer external state.
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
- **Backup receipt (PR 3):** every Recovery Backup that passes verification
  records a durable receipt naming the storage it was captured from (legacy
  or database), the database `store_id` when it was the database, and when it
  was made, plus the database generation captured (the store-wide revision
  counter). Recovering an unmoved chat (§4.3) records the generation of that
  import. Original-file removal (§4.4, PR 13) trusts only a receipt from the
  database with the current `store_id` whose captured generation is at or
  after the last recovery import, so a recovered chat's file is never removed
  before a verified backup contains it, so a backup made between activation
  and PR 13 still qualifies and a pre-activation backup never does, without
  changing the backup path after activation.
- Success is published and baselines advanced only after verification.
  Failure keeps existing backups and blocks any backup-dependent reset.

### 4.6 Human-Readable Backup

- Keep current behavior: title-based filenames, folder options, text/JSON
  choice, speaker naming, Include Thinking, no token accounting.
- **Both formats stream.** The text format keeps its exact output, but
  `ReadableChatFormats.formatText` and the ZIP entry are written
  incrementally from `ChatSource` (headers, then each message), never built
  as one `String` or `ByteArray`.
- JSON entries become **versioned per-chat JSON**, written streaming:
  `format` (e.g. `"phosphor-readable-chat"`), `format_version`, `app`,
  `chat_id`, `name`, `folder_name`, `exported_at`, and `messages` with
  `message_id` (existing UUID when present, else stable derived ID), explicit
  `index`, `role`, speaker name, `text`, `timestamp` (only when stored; never
  invented), regenerated versions, thinking (when enabled), attachment
  references. Text format stays as is.
- Incremental selection uses the `ChatSource` change token (§4.2 item 7;
  `store_id` + `chats.revision` on the database) + export options + format
  version + locale + **time zone** + **speaker names** (the chat username
  and current companion; rendered timestamps and labels depend on them, as
  in today's `ReadableChatFormats.renderFingerprint`) + folder/name changes. On legacy storage the token is the
  existing whole-history fingerprint, so the same code works before and after
  activation.
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

## 5. PR sequence (revised October 18 2026)

Each PR: branch from current `beta/new-client`, Android Checks green,
`@codex review`, address findings, merge when the owner directs. No PR changes
user-visible behavior except where stated.

**Stages and PR boundaries (owner ruling, October 18 2026).** The numbers below
are stages in dependency order, not a required PR count. The implementer may
combine closely related stages into one PR when that reduces duplicated work
or simplifies integration, while keeping each PR reviewable and testable.
These boundaries stay separate regardless:

- Activation (stage 9) is its own PR, merged only after the readiness gate.
- Original-file removal (stage 13) is its own PR, after activation.
- The Recovery Backup writer (stage 3) lands before restore (stage 4) so the
  owner can confirm on device that current data backs up.

Planned grouping: stages 11 and 12 (paged readers and on-demand document
text) form one PR; other groupings are decided as each stage is reached.

### Why this order

The owner ruled that the new backup and restore system must be built and
tested before the database becomes authoritative. The earlier order (switch in
PR 4, backup rebuild in PR 8) would also have forced PR 4 to port every backup
and restore path onto the database through the old whole-in-memory
serializers, and PR 8 to rewrite them again. In the revised order:

1. **Foundations first** (PR 2): the database, `ChatSource`, the staged
   database (create, validate, discard), the change token and the streaming
   list reader. Every later PR is written once against these.
2. **Backup and restore next** (PRs 3–4, restore adding the commit targets), built through `ChatSource` and the
   commit targets. They work on today's legacy storage immediately (the owner
   gets a working backup of conversations over 64 MiB before anything
   migrates) and are tested against the database in instrumented tests. They
   need no rewrite at activation.
3. **Migration and the compatibility layer** (PRs 5–6) are built and tested
   with activation disabled.
4. **Readiness verification** (PR 8) proves the whole cycle end to end.
5. **Activation** (PR 9) is a small PR that only turns migration and the switch
   on.
6. **Performance and cleanup** (PRs 10–13) follow activation. None of them
   changes the database schema or the backup format.

### Sequence

| # | PR | Depends on | Contents | Done when |
|---|---|---|---|---|
| 0 | Finish PR #145 (message limit) | — | CI green on `c29fa62` (done), Codex re-review, merge | merged |
| 1 | Plan in repo | — | This file + `project-plan.md` Feature 0 | merged |
| 2 | Storage foundations (in #148 with stage 3) | 1 | `ChatStore` with final schema v1 (incl. `message_parts`, `include_text_parts`, chat ID moves), `DatabaseKeys.KEY_CHATS`, `ChatSource` + `LegacyChatSource` + `DatabaseChatSource`, change token, `ChatStorageAuthority` (no production path can turn it on), staged-database helper, Gson-compatible streaming list reader; instrumented tests added to the CI list | tests green on both storages; app behavior unchanged |
| 3 | Recovery Backup write + verification | 2 | Key audit; streaming `chats.json` writer and reader for backup format `schemaVersion` 3 (§4.5), from `ChatSource`; streaming semantic validation; capture consistency; `CHATS_JSON_BYTES` and other memory-only ceilings replaced by free-space checks and malformed-archive guards; distinct failure reasons (wording approved by owner **before** code); backup receipt (§4.5) | over-64 MiB chat set backs up and verifies on emulator from legacy storage and from a test database; v3 output carries everything `serializeV2` carries plus `history_saved` (compared on small sets); MISSING and EMPTY histories round-trip distinctly; **owner confirms on device that a full backup of current data succeeds** |
| 4 | Recovery restore | 2, 3 | Streaming reader (v1 and v2) into a staged `chats.db`; validation of the staged set; merge planning and rollback snapshot without whole-set memory; both commit targets (§4.2 item 6); commit via `ChatSetReplacementCoordinator` to either target; `chats.db` health check and repair from a verified backup (§4.1); `ChatRestoreManager` / `RecoveryBackupManager` on the same path | over-64 MiB round trip on legacy target and on database target; every older package version restores; rollback at each step; existing restore UI unchanged |
| 5 | Migration engine (activation off) | 2, 4 | Copy + verify + durable progress + resume; the verified staged `chats.db` is published through PR 4's database commit target (the journaled `DirectDatabaseRestoreCoordinator` path), never a separate swap; document text moved into `include_text_parts`, with `DatabaseChatSource` reassembling full payloads; damaged/locked chats; startup placement and preconditions, process-wide write gate, pending conversations settled first (§3 storage audit); progress screen (wording approved by owner **before** code); switch code present but unreachable | interruption/resume, damaged, locked, large-chat, low-storage tests green; nothing runs on device |
| 6 | Compatibility layer (activation off) | 2 | Every caller in the §3 storage audit, including the direct-file ones (`NewConversationCoordinator`, `ChatNavigationRepository` with one-transaction list and folder writes, `MainActivity`, `ChatSearchIndexManager`); rename ID moves; list previews from the first message only; repaired or unlocked unmoved chats imported into the authoritative row (§4.3); `ChatPreferences` and every listed caller (§3) read and write through `ChatSource`/`ChatStore` by authority; list metadata without parsing histories; search index, transcript backfill, image catalog, damaged-chat flows through the same layer | caller tests pass with authority = legacy (production) and authority = database (test); app behavior unchanged |
| 7 | Readable Backup + memory export streaming | 2 | Versioned per-chat JSON, change-token incremental selection, streaming `MemoryExporter` | tests on both storages; format documented for the viewer |
| 8 | Activation readiness verification | 3–7 | End-to-end instrumented suite: legacy data → migrate → Recovery Backup from database → verify → restore into a clean install and into the legacy target → compare with the original legacy content; restore of a pre-migration backup after migration; backup receipts name the right source and `store_id` before and after migration; heap measured on a 256 MB heap through the whole cycle | all green; readiness gate met |
| 9 | Activation | 8 | Turn on the migration trigger and the switch; fresh installs start on the database | owner made a new Recovery Backup with PR 3's system before installing; **owner confirms on device** |
| 10 | Paged chat screen and sending | 9 | `ChatActivity`/`ChatAdapter` page by page (newest first, load older on scroll, jump-to-message for search/bookmarks/summary), positional ops mapped to `seq`, request builder reads only what it needs | memory tests on huge chat; no UI change; **owner confirms on device** |
| 11 | Search index, list, summaries, transcript backfill paged | 9 | Incremental search updates by revision; paged readers | tests |
| 12 | Documents loaded on demand | 9 | Screens and request building load include text from `include_text_parts` only when needed (storage already in place from PR 2/5) | tests incl. book-length documents |
| 13 | Original-file removal | 9 | Automatic journaled removal after the first verified post-activation Recovery Backup, proven by its receipt (§4.4, §4.5) | tests incl. interruption; damaged files never removed |
| — | Viewer importer | 7 | in `claude-history-viewer` repo | separate project |

PRs 3, 6 and 7 depend only on PR 2; PR 4 depends on PR 3, and PR 5 on PR 4
(it publishes through PR 4's database commit target). Otherwise they may be
built in any order after PR 2; the readiness gate is what matters.

### Readiness gate (required before PR 9)

Activation may not be merged until all of these hold:

- PRs 2–8 merged and Android Checks green on `beta/new-client`.
- Recovery Backup over 64 MiB creates, verifies and restores on the emulator
  from both storages, and every older package version restores.
- Migration interruption/resume, damaged/locked chat, and low-storage tests
  pass.
- The end-to-end suite in PR 8 passes, including restore of a pre-migration
  backup after migration.
- The owner has confirmed on device that the new Recovery Backup succeeds on
  current data (PR 3).

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
  version restores; restore rollback. Run against both storages.
- Peak heap measured on a 256 MB heap during migration, opening, scrolling,
  sending, editing, backup, and restore, with headroom.
- Owner on-device confirmation after PRs 3, 9, and 10.

## 7. Unresolved technical risks

- Migration of an already oversized legacy chat: one AEAD ciphertext plus its
  plaintext must fit in memory at once. Mitigated by migrating first, alone,
  with streaming parse; a chat that still cannot be read blocks the switch
  (requirement 4) and needs a separate recovery path. The same limit applies
  to backing up such a chat from legacy storage in PR 3 (each legacy chat is
  read whole); the streaming writer removes every other copy.
- Restoring into legacy storage writes each chat as one whole value, as today;
  a restored chat too large for that cannot land on the legacy target. It can
  land on the database target after activation.
- `ChatActivity` is ~15k lines with whole-history assumptions; PR 10 is the
  largest and riskiest code change. Until then the compatibility layer gives
  it whole histories exactly as today.
- Model request building for huge documents remains in-memory.
- The owner's chat set may already exceed 64 MiB, so Recovery Backup may be
  failing now; PR 3 fixes that on current storage, before migration.

## 8. Session guide (one session per stage group)

The project is split across separate sessions so no session runs out of
context partway through a stage. Each session takes the group below, finishes
it (merged into `beta/new-client`), and stops.

### Rules for every session

1. Read `CLAUDE.md`, then this file. Work only on `beta/new-client`; never
   touch `main`.
2. Branch from current `beta/new-client`. Inspect the current code before
   changing it; the earlier stages' code is the source of truth, not this
   file's summaries.
3. User-facing wording, new log lines, and any change to what the user sees
   need owner approval **before** any code. Ask in chat, in the decision
   format from `CLAUDE.md`, and stop until answered.
4. Develop with self-review and Android Checks. The local environment cannot
   compile the app; CI is the compile check. Push validated, grouped changes,
   not one fix at a time.
5. **Codex review limits.** Request Codex (`@codex review` comment) only once
   the PR is complete and CI is green. Gather all of its findings and fix them
   in one push. Request at most one re-review. If findings remain after that,
   bring them to the owner as a short list with a recommendation instead of
   starting another round. Never request Codex after every fix, and never
   leave review rounds running overnight.
6. Do not set up PR watching, polling or scheduled check-ins the owner did not
   ask for. Check CI only while a run is in progress.
7. Report briefly and with structure. Bring only approvals and real blockers
   to the owner.
8. **End of session handoff (required).** When the session's work is merged,
   or the session must stop early, update §2 (status) and this section with
   what was done and what remains. Then give the owner, in chat, the exact
   message to paste into the next session and when to start it (for example,
   "after you confirm the backup on your phone"). Do this without being asked.

### Session groups

| Session | Stages | Start when | Needs owner approval before code |
|---|---|---|---|
| A | 4: Recovery restore (streaming, both commit targets, `chats.db` repair) | #146 and #148 are merged and the owner has confirmed on device that a full Recovery Backup succeeds | Any new or changed restore message or log line |
| B | 5: Migration engine, activation off | A merged | The migration progress screen wording and layout; any log line |
| C | 6: Compatibility layer, activation off | B merged (may run before B if the owner prefers; it depends only on stage 2) | Any log line |
| D | 7: Human-Readable Backup and memory export streaming | Any time after stage 2; usually after C | Any change to what the user sees; any log line |
| E | 8: Readiness verification | A–D merged | None expected (tests only) |
| F | 9: Activation (its own PR) | E merged and the readiness gate in §5 met | Owner makes a new Recovery Backup before installing; owner confirms on device |
| G | 10: Paged chat screen and sending | F merged and confirmed | Owner confirms on device |
| H | 11 and 12: paged search, list, summaries; documents on demand (one PR) | F merged | Any log line |
| I | 13: Original-file removal (its own PR) | F merged and a verified post-activation Recovery Backup exists | Any log line |

The storage work may pause between any two sessions so the owner can take
other work first. Nothing in the app is at risk while it is paused; the only
open limit is the interim restore limit in §2 until session A is done.

### Message to start the next session

> Continue the chat storage plan on `beta/new-client`, session X
> (stages N). Read `CLAUDE.md` and `chat-storage-and-backup-plan.md` first,
> follow §8 of the plan, and at the end give me the handoff message for the
> next session.

Replace X and N with the next row of the table above.

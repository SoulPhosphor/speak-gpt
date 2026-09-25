# Restore Handoff — 2026-09-25

**Status:** Handoff record for the old-client → Beta data restore work.
Documentation only. Nothing here authorizes new code beyond what is marked
approved. Owner decisions in the active conversation outrank this file.

---

## 1. Branch and commit state

| Ref | Commit | Notes |
|---|---|---|
| `beta/new-client` | `cbcdd46` | Fast-forwarded from `dd66418`. Publishes the `beta-latest` Beta APK. |
| `claude/backup-restore-failure-1oiysd` | this handoff commit | `cbcdd46` plus this handoff file only. |
| `claude/backup-restore-page-layout-efmyqq` | `dd66418` | Starting point of this work. Unchanged. |
| `main` (old client) | `6a5348c` | Unchanged. |

Commits added on top of `dd66418`:

| Commit | What it changed |
|---|---|
| `2a4f360` | Stage 1: selection-aware restore validation (code, strings, tests). |
| `5c95a80` | "Not a valid recovery backup" message (code, one string). |
| `cbcdd46` | `phase-11-backup-restore-copy.md` updated with approved wording (docs only). |

CI (GitHub Actions):

- Android Checks runs 2427 (`2a4f360`), 2428 (`5c95a80`), 2429 and 2430
  (`cbcdd46`): **all green**.
- Side-by-side Beta APK run 37 (`cbcdd46`): **green**; `beta-latest` was
  replaced with this build.

On-device result: **not working.** See §5. CI green does not mean the restore
works on the owner's device.

---

## 2. What was actually changed (implemented, on `cbcdd46`)

### Stage 1 — selection-aware restore

- Before the user's category selection is applied, **Restore From Backup** now
  checks only whole-package integrity: staged artifacts, known types, size
  limits, and inventory/manifest agreement.
  (`PortableRecoverySemanticValidator.validatePackage`, called from
  `UnifiedPortableRestoreCoordinator.decode` and
  `DatabaseRestoreManager.preparePortableDecoded`.)
- The full semantic gate `PortableRecoverySemanticValidator.validate` is still
  used when a backup is **written** (`PortableRecoveryWriter`).
- `UnifiedPortableRestore.build` reads each **selected** category on its own
  (`parseBackup`). A category whose data is missing, rejected by its reader, or
  whose declared manifest count does not match is set aside as a
  `CategoryFailure`. Other selected categories continue.
- Planning is retried without a selected category that cannot be planned
  against current device data (`blame` map in `captureLiveState` and
  `planFinalState`). A failure with no attributable category still fails the
  whole restore.
- Missing chat generated images and missing identity profile pictures are
  **reported**, not fatal (`PortableRestoreFinalState.missingChatImages`,
  `missingIdentityImages`).
- Every selected category failing → `BuildResult.NothingRestorable` →
  `PortableRestoreOutcome.SelectedDataFailure` → **Restoration Failed**.
- The apply transaction is **unchanged and still all-or-nothing** across the
  categories that reached apply. A write/rollback failure fails the restore.
- The confirmation dialog lists only categories that will actually be
  restored.
- Result dialogs: **Restoration Complete** (title only), **Restoration Partly
  Successful** (sentences, blank line, one problem per line, scrolls),
  **Restoration Failed**. Theme `App.MaterialAlertDialog`, button
  `@string/btn_ok` ("Okay").
- One Error Log entry (`crash` channel, tag `PortableRestore`, level
  `warning`) is written **only after a successful restore that had problem
  lines**. The log form of a missing chat image includes the stored file name.
- The restore outcome file limit was raised to 4 MB so long problem lists
  survive the mandatory restart.
- Tests: `UnifiedPortableRestoreSelectionTest` (new),
  `PortableRestoreFinalStateTest` and `PortableRestoreOutcomeTest` (updated).

### Not-a-backup message

- A file that is not a PHOSBKP2 Recovery Backup package now shows
  "This file is not a valid recovery backup. Please try again with a valid
  file." (`backup_err_not_recovery_backup`).
- Other known header errors (too large, unsupported protection) now show their
  existing specific messages instead of "damaged". An unreadable copy still
  shows `backup_err_damaged`.

### Stage 1 limitation (by design)

Stage 1 does **not** recover individual records. If a selected category's
reader rejects its container because one record is bad, the whole category is
not restored and is reported with the reader's reason.

---

## 3. Approved wording not yet implemented (Stage 2)

Also recorded in `phase-11-backup-restore-copy.md`, "Record-level lines".

Formats:

- Skipped record: `Chat "Evening Walk" was not restored: its ID is invalid`
- Optional link dropped, target failed:
  `Campaign "Northreach" was restored without its world "Aldra", which could not be restored`
- Optional link dropped, target not in backup, name known:
  `Companion "Aria" was restored without its lorebook "Old Tales", which is not in the backup`
- Optional link dropped, target not in backup, name unknown:
  `Campaign "Northreach" was restored without its world, which is not in the backup`
- Chat without its folder:
  `Chat "Evening Walk" was restored without its folder "Work", which is not in the backup`
  (unnamed form when the folder name is unknown)
- Record without its own name, described by parent:
  `A trigger phrase for lorebook entry "Tavern" was not restored: its entry could not be restored`
- Memory: named by its first 10 words.
- Setting: named by its **internal setting key exactly as stored**. No display
  names (owner ruling: do not expand Stage 2 to create them).
- Coupled settings:
  `Setting group "TTS provider and voice" was not restored: its value is not valid`
- Whole-category failure keeps the Stage 1 punctuation:
  `%1$s could not be restored. Reason: %2$s`

Reasons:

| Situation | Reason |
|---|---|
| Invalid ID | its ID is invalid |
| Chat ID does not match its saved data | its ID doesn't match its saved data |
| Duplicate ID with different data | another item has the same ID with different content |
| Invalid message ID inside a chat | one of its messages has an invalid ID |
| Duplicate message ID | one of its messages has the same ID as another message |
| Credential found | it contains a credential, which backups may not include |
| Invalid setting value | its value is not valid |
| Malformed field in a record | one of its details is missing or not valid |
| Unsupported setting type | this version can't restore this kind of setting |
| Unsupported format | it uses a format this version can't read |
| Missing or bad image | its image file is missing or doesn't match |
| Parent was in the backup but failed | its <parent> could not be restored |
| Parent is not in the backup | its <parent> is not in the backup |
| Unknown corruption (fallback only) | its data is damaged |

An exact duplicate record (same ID, identical content) produces **no message**.

### Proposed wording that was NOT approved

- "The data currently on this device could not be read." (proposed as the
  replacement for the misleading reason in §5.2). **Not approved.**

---

## 4. Approved behavior that is incomplete

1. **Chats must be restorable from the old client's Recovery Backup with Chats
   selected.** Owner requirement. Not working (see §5).
2. **Stage 2 — record-level recovery** (owner principle, approved):
   - restore at the smallest safely recoverable unit;
   - never skip a whole category merely because its container also holds other
     record types (the companion/roleplay archive must be restored record by
     record when it can be opened and enumerated);
   - a missing profile image never causes the identity to be skipped;
   - a malformed record never causes unrelated valid records to be skipped;
   - a container is unavailable only when it cannot be opened or parsed enough
     to enumerate its contents;
   - optional dependency missing → restore the main record, omit only the link,
     report it;
   - hard dependency missing → skip only the dependent record, report it;
   - use the specific reason whenever known; "its data is damaged" is fallback
     only.
   Three failure kinds must stay distinct: whole-package integrity failure,
   category-container failure, individual-record failure.
3. **Checks required before building parts of Stage 2** (agreed, not done):
   - Settings: audit every setting key for coupled groups before any
     per-setting skipping.
   - Lorebook entry without its book: verify how the app treats a bookless
     entry before deciding hard vs. optional.
   - Card entry without its owning card: verify against the card screens.
   - Companion profile ↔ `companions` table row: verify whether either can
     stand alone.
4. **Owner test gate:** Stage 2 starts only after the owner confirms Stage 1 on
   device. That confirmation has **not** happened.
5. Proposed Stage 2 build order (Settings, Model Endpoints, identity archive,
   Chats, Lorebooks, Memories/Rules, Images) was proposed, **not confirmed**.

Verified schema facts for Stage 2 (from `MemoryStore` / `LoreBookStore`):

- Hard (DB-enforced or format-enforced): companion name history → companion;
  campaign party rows → campaign and party member; `rp_tag_links` → tag;
  lorebook triggers → entry; endpoint favorite → endpoint (codec-enforced);
  memory link/supersession rows → memory.
- Optional/soft: campaign → world / character / companion (nullable);
  card entry → parent entry / world entry / party member (deliberately soft,
  rendered as "(deleted card)").
- Unverified: card entry → owning card; lorebook entry → book;
  companion profile ↔ companion row.

---

## 5. Known restore failures still unresolved

### 5.1 Owner device test of `beta-latest` (`cbcdd46`) failed

- Restore From Backup, old-client Recovery Backup, **Chats and Generated
  Images unticked**, other categories ticked.
- Result: failure; the reason shown was "The app could not safely prepare the
  backup data." (`portable_restore_reason_staging_failed`).
- **The category named in the failure line was not captured.** With Chats and
  Generated Images unticked, chats and the gallery are not read, so the failing
  category is one of the others that were ticked.
- Possible code paths for that reason string:
  1. `CategoryFailureReason.CURRENT_DATA_UNAVAILABLE` — current device data for
     that category could not be read in `captureLiveState` or
     `planFinalState` (e.g. current identities export, shared memory store,
     lorebooks, model endpoint capture, app settings capture, profile images).
  2. `SelectedCategoryRestoreTransaction.Failure.STAGING_FAILED` — a
     participant's `stage()` failed after confirmation.
- Cause **not determined**.

### 5.2 Misleading reason text (introduced in Stage 1)

`PortableRestoreIssueText` maps `CURRENT_DATA_UNAVAILABLE` to
`portable_restore_reason_staging_failed` ("The app could not safely prepare
the backup data."). That describes the wrong thing: the problem is data
already on the device, not the backup. The same string is also used for the
transaction `STAGING_FAILED`, so the two causes cannot be told apart.
Replacement wording is **not approved** (see §3).

### 5.3 Chats cannot yet be restored

Not verified working on device. Known obstacles:

- Chats restore reads current chats (`ChatLogicalSerializer.serializeV2` +
  `PortableChatRestorePlan.parse`) and the current generated-image catalog
  (`GeneratedImageCatalogStore.exportSnapshot`). If either fails, Chats is set
  aside (§5.2 message).
- The old client's chat list can hold several rows with the same legacy ID
  (see §5.6). `PortableChatRestorePlan.parse` rejects rows with the same ID and
  different list data (`DUPLICATE_CHAT_ID`); the whole Chats category is then
  not restored (Stage 1 limitation).
- **Duplicate risk:** chats previously brought in through Convert Legacy Chats
  were given **new random UUIDs** (`ChatLogicalImportPlan` →
  `allocateUuid`). Restoring the same chats from the old Recovery Backup in
  Merge mode would add them a second time. See §6 and §7.

### 5.4 Convert Legacy Chats output has no restore entry point

`LegacyChatConverterActivity` saves a plain chat-recovery ZIP
(`ConvertedChatRecoveryArchive`). Its on-screen instruction says to restore it
with Restore From Backup, which only reads PHOSBKP2 packages and now reports
"This file is not a valid recovery backup". The file-based chat-archive restore
UI was removed on 2026-09-09 (`4e6f43e`); `RestoreForegroundService.start` has
no callers.

### 5.5 Old-client Portable Copy JSON cannot be restored

`Export Portable Copy` on `main` writes `companion-memory-export-v1` JSON.
The Beta's JSON import (`importSeedFromUri`) is hidden, restored memory only,
and never chats. The correct old-client export is **Create Recovery Backup**
(PHOSBKP2).

### 5.6 Old-client legacy chat IDs

On `main`, a chat's ID is `SHA-256(name at creation)`. Chats created with an
empty name all received `e3b0c442…b855` (SHA-256 of ""). Chat history is
stored per ID, so those rows **share one history file**; deleting one row
clears the shared history. Distinct conversations under that ID were already
lost on the old device before export.

---

## 6. Chat identity rule (owner requirement)

**Chat UUIDs are immutable. They must never be regenerated during export,
conversion, restore, merge, or migration.**

- A chat keeps the ID it has. Restore and merge must carry it unchanged.
- `PortableChatRestorePlan` / `ChatLogicalSerializer` v2 already keep a legacy
  64-hex ID as-is ("not replaced with a generated UUID").
- **Existing code that conflicts:** the temporary legacy converter
  (`ChatLogicalImportPlan`) assigns a **new random UUID** to every chat whose
  ID is a legacy hash. Do not extend or reuse this behavior. Any future work on
  legacy IDs needs an explicit owner ruling first.

## 7. Heuristic matching is not approved

Matching or de-duplicating chats (or any records) by **name or content** is
**not approved**. It was proposed on 2026-09-25 to avoid duplicate chats from
the converter; it has no approval. Identity is by ID only.

## 7a. Recorded defect: Convert Legacy Chats changes chat IDs

**Status:** confirmed defect, separate from the Stage 1 diagnostics work. Not
fixed. It did not cause the failed Stage 1 test (Chats were unticked).

- **Requirement:** Convert Legacy Chats must keep each chat's existing legacy
  ID (the 64-character hash) instead of assigning a new UUID (§6).
- **Where:** `ChatLogicalImportPlan.parse` gives every chat whose ID is not a
  canonical UUID a new random UUID through `allocateUuid()`; it is called from
  `LegacyChatConversion` (Convert Legacy Chats).
- **Not a fix:** matching by name, content, first message, timestamp, or any
  similarity (§7).
- Rows sharing one legacy ID do not have independently addressable
  history/settings storage on the old client. The backup cannot reconstruct
  separate histories that the source storage no longer distinguishes.

---

## 8. Areas with insufficient error logging

Adding or changing any log line requires owner approval first (CLAUDE.md §8).

**Update:** the owner approved restore failure diagnostics. On
`claude/backup-restore-failure-1oiysd`, every Restoration Failed result now
writes one Error Log entry (`crash` channel, tag `PortableRestore`, level
`error`), and the Restoration Partly Successful entry lists the internal
reason for each category not restored. Each line gives the restore step, the
categories involved (all categories a shared participant covers), the
internal reason code and non-content detail (table/column names, counts,
value types, error types). Record IDs, names, prompts, messages, memory text
and credentials are never logged. User-facing restore wording is unchanged.
The table below is the pre-change state.

| Area | What is lost today |
|---|---|
| `UnifiedPortableRestore` category set-aside | The internal reason string (e.g. "current identities are unavailable", "lorebooks could not be planned") is discarded; only the reason enum reaches the dialog. |
| `BuildResult.NothingRestorable` / Restoration Failed | Nothing is written to the Error Log. Stage 1 logs only after a successful restore with problems. |
| Transaction `STAGING_FAILED` / `APPLY_FAILED` | Only the category key survives; which participant step failed and why is not recorded. |
| Pre-selection package check (`validatePackage`) | Invalid / too large is not logged. |
| Generated-image catalog state during restore | LOCKED / CORRUPT / NEEDS_RECOVERY / UNAVAILABLE is not logged. |
| `PortableChatRestorePlan` rejection | The `detail` string (e.g. which ID appears twice) is discarded. |
| `CompanionBackupValidator` | `Damaged` / `WrongFile` / `NewerFormat` carries no detail. |
| `ModelEndpointPortableCodec` / `AppSettingsPortableCodec` | Endpoint rejection detail strings are discarded; the settings codec returns a bare `Invalid`. |

Existing logging that does help: `ChatLogicalSerializer` writes
"Recovery backup failed (LIST|HISTORY|SETTINGS): …" to the Error Log when
current chats cannot be read.

---

## 9. Discussed only (not implemented, not approved)

- Old-client export change to write shared-ID chat rows once.
- New-client JSON (`companion-memory-export-v1`) importer.
- Fixing Convert Legacy Chats to hand its output to the chat restore.
- Logging the internal failure cause (§8).
- Replacement reason wording (§3).
- Name/content matching of converted chats (§7, not approved).

## 10. Key files

- `app/src/main/java/org/teslasoft/assistant/preferences/backup/portable/`
  - `UnifiedPortableRestore.kt` — `build`, `parseBackup`,
    `captureLiveState`, `planFinalState`
  - `UnifiedPortableRestoreCoordinator.kt` — decode, preflight, apply, log
  - `PortableRecoverySemanticValidator.kt` — `validatePackage`, `validate`,
    `recordCounts`
  - `PortableRestoreFinalState.kt` — missing-reference reporting
  - `PortableRestoreIssueText.kt` — problem-line text
  - `PortableRestoreOutcome.kt` — persisted result
  - `PortableChatRestorePlan.kt`, `ChatLogicalImportPlan.kt`,
    `ChatLogicalSerializer.kt`, `ConvertedChatRecoveryArchive.kt`,
    `ChatRestoreParticipant.kt`
- `app/src/main/java/org/teslasoft/assistant/ui/PortableRestoreOutcomeFlow.kt`
- `app/src/main/java/org/teslasoft/assistant/ui/activities/MemoryBackupRestoreActivity.kt`
- `phase-11-backup-restore-copy.md` — approved restore wording

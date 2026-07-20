# Database Health & Backups — Build Plan (Rounds 0–5), Revision 2

**Status: PLAN AWAITING OWNER APPROVAL. Nothing here is built.**
Revision 2, July 20 2026 — incorporates the second external review (11
points + the two-systems architecture requirement). Written against `main`
(through `2585a36`) and the owner-directed design in
`memory_health_round5_phase1_design.md` §15 (§15 outranks §1–§14 of that
document wherever they disagree).

Read before building any round: `CLAUDE.md` (fragile areas, no-Toast rule,
CI-only compile gate, owner on-device confirmation rule),
`Memory System/owner_approved_rules.md`, and the §15 sections cited per
round. Owner-approved verbatim strings live in §15.12 — implement them
exactly. The formerly-proposed changes to approved strings were all ruled
on by the owner on July 20 2026 (see "Owner confirmations — RESOLVED");
the only words still to be written are the Round 4 wording list, and the
only open choices are under "Decisions still open."

## The two systems (architectural requirement — keep them separate)

**1. Portable Export / Import (JSON).** The existing user-selected JSON
export (`MemorySeedCodec` + the `app_chats` envelope). Key-independent by
design: no Android database files, no SharedPreferences internals, no
SQLCipher keys, no Keystore dependence. It is FOR: reinstalls, moving to
another phone, future Windows compatibility/synchronization (Phase 8), and
import into another compatible Soul Phosphor client. It is the long-term
synchronization contract. It is NOT legacy and must keep working and keep
being developed. Current honest status: the export already carries the
structured memory data AND chats; import currently restores memory-system
data only; **portable chat import/merge is not yet implemented — it is
intended future capability, not abandoned scope** (Round 5).

**2. Automatic Recovery Backups.** The per-type snapshots this plan builds.
Same-installation recovery copies for fast rollback after corruption; they
MAY depend on this installation's encryption keys. Always labeled as
recovery backups, never as portable exports.

The Backup & Restore screen must make the distinction legible to a tired
user: recovery backups (automatic + `Create Backup`) vs
`Export Portable Copy` / `Import Portable Copy`. The portable export's UI
must warn plainly that the exported file is readable and may contain chats
and memories. Building recovery snapshots must not remove or degrade the
existing JSON export ability.

Verified facts this plan builds on (checked against `main`, July 20):
- THREE databases: `companion_memory.db` (SQLCipher), `lorebook.db`
  (SQLCipher), `profile_images.db` (plain SQLite, deliberately unencrypted —
  do not add SQLCipher to it). Chats are EncryptedSharedPreferences files,
  not a database. SQLCipher dependency is `net.zetetic:sqlcipher-android:
  4.16.0` (verified in `app/build.gradle`).
- The **Memory Backup & Restore screen already exists**
  (`MemoryBackupRestoreActivity`, Memory Manager hub row, July 18) holding
  the auto-backup toggle, import/export, last-backup status, and Reset.
  This plan's controls are ADDED to that screen — no new screen.
- The **startup freeze (ANR) fix has NOT landed**: `MainApplication` still
  runs `Logger.logLastExitReason` (Keystore + encrypted prefs) on the main
  thread; `MainActivity`/`ChatActivity` still run init reads on the UI
  thread behind a fake background `Thread { runOnUiThread { … } }`.
- Backup today is the single combined JSON export
  (`MemoryExporter.autoExportIfDue`, startup-triggered, 24 h throttle,
  keep-5), covering the memory DB + chats. Its enabled flag and
  last-export timestamp live INSIDE the memory database
  (`META_AUTO_EXPORT_ENABLED` / `META_LAST_AUTO_EXPORT_AT`) — the current
  combined export depends on the memory database being healthy. The new
  recovery-backup system must NOT repeat that dependency.

---

## Round 0 — Unfreeze the app (independent; never waits)

**This round is independent of everything below. It must not wait for the
backup/recovery rounds or any of their open decisions.** Another session
has PROPOSED this fix; check for an existing branch/PR before writing
anything — do not duplicate. If it has landed, verify and skip.

1. Move `logLastExitReason` + its encrypted log writes into the existing
   startup background thread (ordered before housekeeping steps that log).
2. Run `MainActivity.preInit()`'s secure-storage/API-key reads and
   `ChatActivity`'s `initChatId()`/`initSettings()` genuinely off the UI
   thread; splash/loader stays up until the result posts back. Round-4
   ordering is INVARIANT: locked-check decision → (locked screen | Welcome |
   chat list), decided off-thread, dispatched on Main, no reordering.
3. Voice-diagnostic log writes through one background single-thread writer;
   crash-handler writes stay synchronous.
4. Touch nothing else: no mic/VAD, no encryption format, no keys, no
   migrations.

**Exit:** CI green + the owner confirms on their phone that launch no
longer freezes. (Owner rule: not "done" on any other basis.)

## Round 1 — Stop the every-launch waste (§15.3, §15.4)

Startup-performance round; no user-facing changes.

1. **Crash-triggered integrity checking:** the housekeeping thread consults
   the already-computed last-exit reason; `PRAGMA integrity_check` runs
   only when the previous exit was abnormal (crash/ANR/kill) or a
   check/repair is pending (Round 3 flag). Clean exit → skip. All three
   databases.
2. **One-time chores latch:** the outage-file scan and legacy snapshot
   discovery record "done" and stop rescanning every launch (respecting:
   no touching recovery artifacts, defer while `RenameJournal.hasPending`).
3. **The recovery-backup controller's state lives OUTSIDE all protected
   databases** in an independent plain prefs file (same rationale as
   `storage_health`): enabled setting, selected backup-folder URI
   (Round 2), and per-type last-attempt / last-success / consecutive-
   failure counters. **Each recorded failure carries its CATEGORY** (see
   Round 2 item 5) — never a bare "backup failed." One damaged store can
   never disable backup attempts or status tracking for the others.

**Exit:** CI green; Event/Error logs show integrity checks only after
abnormal exits; startup housekeeping measurably does less on a clean start.

## Round 2 — Per-type recovery backups + user-chosen location (§15.13, §15.16, §15.9 order)

1. **User-chosen backup folder (requirement, not convenience):** automatic
   recovery backups write to a folder the USER selects via the Storage
   Access Framework folder picker (a **tree URI, not necessarily a normal
   filesystem path**), with the permission persisted
   (`takePersistableUriPermission`) so future runs keep writing there. The
   Backup & Restore screen gets `Choose Backup Folder` / `Change Backup
   Folder` showing the current location as selectable text. Until chosen, a
   default app folder is used — the app-private default must never be the
   ONLY destination. The user likewise chooses the file for import/restore.
2. **Trigger, stated honestly:** backups are **checked when the app starts
   (and, if cheap, when it returns to the foreground), throttled to at most
   one success per 24 h.** This is NOT guaranteed background scheduling —
   if the app isn't opened, no backup happens that day. The UI and docs
   must not promise "daily" as a guarantee. (A WorkManager-scheduled
   variant is a possible later improvement, not promised here.)
3. **Four independent artifacts per run**, same timestamp: memory DB,
   lorebook DB, **Profile Image Catalog**, chats. Rotation keep-5 **per
   type**; the last known-good of a type is never deleted until its
   replacement is FINAL (see item 4); one type failing never touches
   another's files.
   - **Profile Image Catalog scope (honest labeling, owner ruling):** this
     artifact is `profile_images.db` ONLY — the catalog. **The JPEG image
     files themselves are NOT backed up** (owner decision: the catalog is
     the record; a damaged catalog can also be rebuilt from the files).
     Every label and status line says "Profile image catalog," and no
     wording may imply the pictures themselves are protected.
4. **SAF-safe snapshot pipeline (per artifact):**
   a. Create the snapshot in **controlled internal staging storage** first.
      Databases: an encrypted consistent snapshot — **verify that
      `VACUUM INTO` works correctly under the installed
      `sqlcipher-android` 4.16.0 with a targeted integration test before
      relying on it; if not proven, use the supported `sqlcipher_export`
      path.** Never a raw file copy of a live database. Chats: see item 6.
   b. Verify the staged snapshot (databases: open + integrity-check the
      snapshot; chats: manifest/hash validation, item 6).
   c. Copy the staged file through `ContentResolver` into a **temporary
      document** in the chosen folder; close it; **reopen it and verify**
      (size/hash); only then finalize it and run rotation. **Do not assume
      provider rename/move operations are atomic or even supported.**
   d. Only after the destination copy is reopened-and-verified may the
      oldest rotation candidate be deleted. Last-known-good survives every
      failure mode.
5. **Failure categories (load-bearing — gates Round 3):** every failure is
   classified and counted per type as one of:
   - **source** failure — the database/chat storage could not be read or
     snapshotted, or its snapshot failed verification;
   - **destination-permission** failure — folder moved/deleted/
     disconnected/permission revoked;
   - **destination-write** failure — out of space / IO error writing;
   - **verify** failure — the completed destination file failed re-read
     verification.
   **A source failure is a HEALTH SIGNAL, not confirmed corruption.** It
   triggers the appropriate store-specific check (item 7); only a check
   that CONFIRMS damage escalates further — a transient source read or
   snapshot failure alone never marks a store corrupt; it is recorded with
   its category and retried per the backup-failure policy.
   Destination/verify failures lead to Change Backup Folder / Retry /
   dismiss — they must NEVER mark the source degraded or suggest repair.
   A permission-revoked or missing folder keeps all existing backups
   intact and prompts the user to Change Backup Folder.
6. **Chats artifact — transactionally consistent:** the snapshot is taken
   under the same coordination normal chat mutations use
   (`ChatPreferences.CHAT_LIST_LOCK`; lock order rules respected) so the
   chat list, histories, and per-chat settings are ONE consistent set. The
   archive contains a **versioned manifest, the expected file set, and
   per-file hashes** — a readable ZIP alone is not sufficient verification.
   Locked chat storage pauses ONLY the chats artifact (Round-4 policy);
   a degraded database pauses ONLY its own artifact (A1's "unavailable to
   use or save"). The chats artifact is the **encrypted-file archive by
   default** (recovery copies are same-installation; the portable JSON
   export is the readable path — no plaintext automatic backup).
7. **Detection synergy (checked, not assumed):** a source-category failure
   during a backup run triggers the store-specific health check without
   waiting for a crash — **routed by data type**: memory / lorebook /
   profile-image-catalog source failure → that database's integrity check
   (→ the Round-3 repair flow only if the check confirms damage); chat
   source failure → the existing Round-4 chat-storage health/lock
   machinery or the chat-recovery flow (Round 3 item 5) — chats are not a
   database and never enter the database repair dialogs. A check that
   finds nothing wrong → categorized failure recorded, retry per policy.
8. **Backup & Restore screen additions** (simple rows, house style — no
   cards/tiles), §15.9's approved order preserved: `Check Database
   Integrity` ABOVE the backup button; helper text under Check Database
   Integrity: it checks memory, lorebooks, and the profile image catalog —
   **it does not check chat files**; one **compact status row per type**
   (current result + last successful date/time when relevant, `Month D,
   YYYY, H:MM AM/PM`), e.g.:
   > `Memory: Backup failed today. Last good backup: July 19, 2026, 2:30 PM`
   > `Lorebooks: Backed up July 20, 2026, 2:30 PM`
   > `Chats: Backed up July 20, 2026, 2:31 PM`
   > `Profile image catalog: Backed up July 20, 2026, 2:31 PM`
   (**APPROVED by owner, July 20 2026:** compact rows replace the old
   two-line failed/success layout, and the backup button is named
   **`Create Backup`** — not "Create Database Backup", not "Create Backup
   Now".)
9. The existing portable JSON export/import stays untouched and clearly
   separated on the screen (`Export Portable Copy` / `Import Portable
   Copy`, with the readable-content warning — labels are Round 4 wording).

**Exit:** CI green; owner picks a folder on their phone, sees four files
appear in it, sees the per-type status rows, and last-known-good survives
an induced destination failure.

## Round 3 — Detection → dialog → repair/replace (§15.2 family, §15.6, A1–A6, B8, §15.15)

1. **Per-database degraded flag** (plain prefs, survives restarts — B10):
   blocks reads AND writes for that store, pauses its backup artifact,
   hard-disables the Archivist (A3), shows the A2 banner per new chat until
   repaired. **The flag is set only by CONFIRMED damage** — a failed
   integrity check or a mid-session corruption exception. A source backup
   failure is only the trigger to RUN that check (Round 2 items 5/7);
   destination/verify backup failures never touch this flag at all.
2. **Preserve-the-original law (before ANY repair):** stop access to the
   affected store; preserve the damaged database AND its WAL/journal
   sidecar files (quarantine copy, renamed with date, indexed in
   `SnapshotRegistry`). **Repair works on a separate staged file — never
   in-place on the only copy.** The staged result is verified before it
   replaces the active database, and the preserved original is KEPT even
   when automatic repair succeeds.
3. **Startup path:** crash-triggered check fails → bounded staged salvage
   attempt → success: `Database Repaired` dialog (A1 variant); failure:
   `Database Problem Found!` (A1) with `Repair | Revert to Last Good
   Database | Cancel` (owner, July 20: `Cancel`, never `Not Now` — no
   dialog button anywhere uses "Not Now"); `Cancel` → degraded mode, never
   force-close.
4. **Revert path (§15.2b + §15.6):** verify newest recovery backup, walk
   older until one passes; A5 confirm (backup's date, the loss noun for
   that database, damaged file kept); nothing usable → fresh empty DB + A6
   (`Database Recovery Failed`, preserved path, `Open File Location`).
5. **Same-device chat recovery restore (NOT deferred; always
   user-confirmed, never silent):** restoring the chats snapshot is a
   wholesale REPLACEMENT of chat storage. Required sequence:
   validate the complete archive (manifest + hashes) before touching
   anything → extract into staging → verify the staged chat set → pause
   chat writes → preserve the current files (quarantine +
   `SnapshotRegistry`) → journaled replacement (restartable if the process
   dies mid-swap) → invalidate `SecurePrefs`/cache handles → clear the
   relevant lock state only after successful validation → controlled app
   restart when required. Boundaries: repairs damaged chat FILES on this
   phone; cannot cure a lost Keystore key (Round-4 lock system owns that
   case). Honors `RenameJournal.hasPending` and the lock-order rules.
6. **Mid-session path (§15.2c):** corruption exceptions caught at the
   store layer → degraded flag → distinct audio cue (hands-free sessions
   ONLY) → A2 banner. No new dialog.
7. **Manual check (B8):** one press checks all three databases →
   `Database Check Complete` / `Database Check Incomplete` with a
   per-database line each (chat files are NOT checked by this button —
   helper text says so); a failed database routes into the same A1 flow.
8. **Backup-failure dialog (3 consecutive failures of a type):** shape
   depends on category (**APPROVED by owner, July 20 2026 — supersedes the
   five-button A4 layout**):
   - destination/verify failures → a storage dialog with exactly
     `Change Backup Folder | Retry | Cancel` (owner: `Cancel`, not
     `Not Now`), the current folder shown as selectable text, and
     `Open Backup Folder` as a secondary text action (never another
     primary button);
   - a source failure → no storage dialog; run the store-specific check
     and route by type (Round 2 item 7): a database whose check confirms
     damage → the repair flow (item 3); a chat source failure → the
     chat-storage health/lock or chat-recovery flow (item 5); damage NOT
     confirmed → categorized failure + retry, no dialog beyond the status
     row.
   Final body text for the storage dialog is Round 4 wording.
9. **Profile Image Catalog specifics (§15.16):** auto-repair = rebuild the
   catalog by rescanning the image files — but per item 2, the damaged
   catalog file is still preserved first, and the rebuild is always
   disclosed (`Database Repaired` dialog), never silent. Same banner +
   repair/replace; no feature-disable beyond the gallery itself.
10. **Error Log health lines (§15.15):** once per transition, written
    regardless of the diagnostics toggle, to the Error Log, timestamp
    rendered in red in `LogsActivity`.

**Exit:** pure logic (flag transitions, failure-category classification,
backup-walk selection, 3-strike counting, staged-repair planner, chat-swap
journal) unit-tested in `app/src/test`; CI green; dialogs verified by the
owner on-device. Any debug-only trigger to preview these dialogs needs the
owner's explicit yes first.

## Round 4 — Wording completion (owner copy, then wire it)

Strings that do not exist yet or now need owner (re-)approval:
1. A2/A3-style banner text naming the **profile image catalog**.
2. Buttons on the B8 result (damage found → likely `Repair | Revert to
   Last Good Database`; incomplete → likely `Try Again | View Error Log`).
3. ~~Compact status rows~~ — **APPROVED** (format per Round 2 item 8);
   only the chats row's placement remains to confirm.
4. ~~Button rename~~ — **RESOLVED: `Create Backup`** (owner, July 20).
5. The category-split backup-failure dialog's **body text** (structure +
   buttons `Change Backup Folder | Retry | Cancel` are APPROVED; the
   sentence(s) above them still need owner words).
6. **Chat-recovery wording set:** chat backup unavailable/damaged state;
   restore confirmation naming the exact backup date and time, warning the
   current chat collection will be REPLACED, stating current files are
   preserved; restore success; restore failure; restart-required message +
   action; optional `Choose Another Backup` action.
7. Portable-copy labels + the readable-content warning
   (`Export Portable Copy` / `Import Portable Copy`).
8. Helper text under `Check Database Integrity` (checks the three
   databases; does not check chat files).
9. In-progress/status text under the backup button; the Memory Backup &
   Restore row's missing subtitle (owner may supply or leave).

Each lands only after the owner approves the words in chat.

## Round 5 — Deferred (explicitly NOT in this plan's scope)

- **Portable chat IMPORT/merge** (into an existing collection, or from
  another device): intended future capability of the portable JSON system —
  needs its own design conversation (duplicate/merge rules). Same-device
  recovery restore is NOT deferred (Round 3 item 5).
- **The §1–§14 machinery** of the design doc (health episodes store,
  counters, reindex sweeper, contradiction lifecycle, run filter stats /
  DB v15): Rounds 1–3 build only the thin slice they need (degraded flags,
  categorized failure counters, transition-only Error-Log lines). §15.15
  supersedes §4.2's log-channel choice; B13 closes §14.1.
- **Guaranteed background backup scheduling** (WorkManager): possible
  later improvement; not promised.

---

## Owner confirmations — RESOLVED July 20 2026

1. **A4 replacement: APPROVED.** Category-split design (Round 3 item 8);
   storage-dialog buttons are `Change Backup Folder | Retry | Cancel`
   (owner: `Cancel`, not `Not Now`). Body text still Round 4.
2. **Backup button name: `Create Backup`** (owner's exact choice — not
   "Create Database Backup", not "Create Backup Now").
3. **Compact per-type status rows: APPROVED**, replacing the two-line
   failed-above-success layout (§15.9 items 3–4).

## Decisions still open (unchanged)

1. **(Round 3) B8 result buttons** (labels above are suggestions).
2. **(Round 3) Debug preview trigger** for the new dialogs: allowed or not.

(The former "chats backup format" decision is CLOSED: encrypted-file
archive for automatic recovery; the portable JSON export is the readable
path — no plaintext automatic backups.)

## Known build risks (stated so they don't surprise anyone)

- **SAF provider quirks:** tree-URI destinations may not support atomic
  rename/move; copies go staged-internal → ContentResolver → close →
  reopen → verify → finalize. A moved/revoked folder is a
  destination-permission failure: keep all backups, prompt Change Backup
  Folder.
- **`VACUUM INTO` under SQLCipher 4.16.0 must be proven** by a targeted
  integration test before use; otherwise use `sqlcipher_export`.
- **`Open Backup Folder` / `Open File Location`** are best-effort
  conveniences (no universal Android intent); the path is always shown as
  selectable text; their unreliability never blocks choosing/using a
  folder.
- **Chat-storage replacement swap** must coordinate with `SecurePrefs`'
  cached handles and the Round-4 lock state machine (invalidate-and-reopen
  or controlled restart), honor `RenameJournal.hasPending`, and follow the
  documented lock order.
- **"Repair" of SQLCipher databases is salvage, not magic:** a bounded
  recover-what-reads pass into a staged file. Repair failure is a normal,
  designed outcome (→ revert path); no round may promise more.
- **Round 0 overlap:** another session owns the freeze-fix proposal.
  Verify current `main` and open branches before Round 0 writes a line.

# Database Health & Backups — Build Plan (Rounds 0–5)

**Status: PLAN AWAITING OWNER APPROVAL. Nothing here is built.**
Written July 20 2026 against `main` (through the profile-images/UI merges,
`2585a36`) and the owner-directed design in
`memory_health_round5_phase1_design.md` §15 (all §15 decisions resolved;
§15 outranks §1–§14 of that document wherever they disagree).

Read before building any round: `CLAUDE.md` (fragile areas, no-Toast rule,
CI-only compile gate, owner on-device confirmation rule),
`Memory System/owner_approved_rules.md`, and the §15 sections cited per
round. All user-facing strings referenced here are ALREADY owner-approved
verbatim in §15.12 unless a round's "wording needed" list says otherwise —
implement them exactly; never invent or reword copy.

Verified facts this plan builds on (checked against `main`, July 20):
- THREE databases: `companion_memory.db` (SQLCipher), `lorebook.db`
  (SQLCipher), `profile_images.db` (plain SQLite, deliberately unencrypted —
  do not add SQLCipher to it). Chats are EncryptedSharedPreferences files,
  not a database.
- The **Memory Backup & Restore screen already exists**
  (`MemoryBackupRestoreActivity`, Memory Manager hub row, July 18) holding
  the auto-backup toggle, import/export, last-backup status, and Reset.
  §15's new controls are ADDED to this screen — no new screen is created.
- The **startup freeze (ANR) fix has NOT landed**: `MainApplication` still
  runs `Logger.logLastExitReason` (Keystore + encrypted prefs) on the main
  thread, and `MainActivity`/`ChatActivity` still run their init reads on
  the UI thread behind a fake background `Thread { runOnUiThread { … } }`.
- Backup today is still the single combined JSON export
  (`MemoryExporter.autoExportIfDue`, daily, keep-5), covering the memory DB
  + chats only. Its enabled setting and last-export timestamp live INSIDE
  the memory database (`META_AUTO_EXPORT_ENABLED` /
  `META_LAST_AUTO_EXPORT_AT`) — the current combined export therefore
  depends on the memory database being healthy. The new independent backup
  system must NOT repeat that dependency (Round 1).

---

## Round 0 — Unfreeze the app (prerequisite)

The owner's phone currently freezes at launch (ANR: main thread blocked on
Keystore). Another session has PROPOSED this fix; **check for an existing
branch/PR before writing anything — do not duplicate work.** If it has
landed by the time this round starts, verify and skip.

1. Move `logLastExitReason` + its encrypted log writes into the existing
   startup background thread (ordered before the housekeeping steps that
   may log).
2. Run `MainActivity.preInit()`'s secure-storage/API-key reads and
   `ChatActivity`'s `initChatId()`/`initSettings()` genuinely off the UI
   thread; splash/loader stays up until the result posts back. The Round-4
   ordering is INVARIANT: locked-check decision → (locked screen | Welcome |
   chat list), decided off-thread, dispatched on Main, no reordering.
3. Voice-diagnostic log writes go through one background single-thread
   writer; crash-handler writes stay synchronous.
4. Touch nothing else: no mic/VAD, no encryption format, no keys, no
   migrations.

**Exit:** CI green + the owner confirms on their phone that launch no longer
freezes. (Owner rule: not "done" on any other basis.)

## Round 1 — Stop the every-launch waste (§15.3, §15.4)

Startup-performance round; no user-facing changes.

1. **Crash-triggered integrity checking:** the housekeeping thread consults
   the already-computed last-exit reason; the `PRAGMA integrity_check` runs
   only when the previous exit was abnormal (crash/ANR/kill) or a
   check/repair is pending (flag from Round 3's degraded state). Clean exit
   → skip. Applies to all three databases.
2. **One-time chores latch:** the outage-file scan and legacy snapshot
   discovery record "done" and stop rescanning every launch (respecting the
   rules: no touching recovery artifacts, defer while
   `RenameJournal.hasPending`).
3. **The new backup controller's state lives OUTSIDE all protected
   databases** in an independent plain prefs file (same rationale as
   `storage_health`): the enabled setting, the selected backup-folder URI
   (Round 2), and per-type last-attempt / last-success / consecutive-
   failure counters. One damaged store can never disable backup attempts
   or status tracking for the others — the dependency the current combined
   export has on the memory DB is not repeated.

**Exit:** CI green; Event/Error logs show integrity checks only after
abnormal exits; startup housekeeping measurably does less on a clean start.

## Round 2 — Per-type backups + user-chosen location (§15.13, §15.16 backup half, §15.9 order)

1. **User-chosen backup folder (requirement, not convenience):** the
   automatic backups write to a folder the USER selects via Android's
   Storage Access Framework folder picker, with the folder permission
   persisted so future automatic runs keep writing there. The Backup &
   Restore screen gets a **`Choose Backup Folder`** / **`Change Backup
   Folder`** control that shows the currently selected location. Until the
   user has chosen, a default app folder (`App Backups/`) is used — but the
   app-private default must never be the ONLY destination. The user can
   likewise choose the file used for an import or restore.
2. **Four independent artifacts per run**, same timestamp: memory,
   lorebook, profile-images catalog, chats. Temp-write → verify → rotate
   keep-5 **per type**; the last known-good of a type is never deleted
   until its replacement verifies; one type failing never touches another's
   files.
3. **Consistent snapshots:** database copies via SQLite `VACUUM INTO`
   (works under SQLCipher; output stays encrypted; never a raw copy of a
   live db file). Chats: an archive of the encrypted per-chat files
   (list + histories + per-chat settings), verified after write. Locked
   chat storage pauses ONLY the chats artifact (Round-4 policy); a degraded
   database (Round 3 flag) pauses ONLY its own artifact (A1's "unavailable
   to use or save").
4. **Detection synergy (free):** a VACUUM/verify failure during the daily
   run is itself a corruption signal — it feeds the per-type failure counter
   (→ A4 at 3 strikes) and can raise the Round-3 flow without waiting for a
   crash.
5. **Backup & Restore screen additions**, in §15.9's approved order:
   `Check Database Integrity` button ABOVE `Create Database Backup`; status
   text under each; per-type status lines (`Month D, YYYY, H:MM AM/PM`,
   12-hour); per-type failed-line ABOVE its success line; the
   Choose/Change Backup Folder control with the selected location shown.
   Chats' status line placement: this screen unless the owner says
   otherwise (wording list, Round 4).
6. The existing manual JSON export/import stays untouched (it remains the
   cross-device path; see Decisions).
7. **Designed together with same-device chat recovery (Round 3 item 4):**
   the chats artifact's format is chosen so that restoring it wholesale on
   this device is straightforward — backup format and recovery restore are
   one design, not two.

**Exit:** CI green; owner picks a folder on their phone, sees four files
appear in it, and sees live status lines.

## Round 3 — Detection → dialog → repair/replace (§15.2 family, §15.6, A1–A6, B8, §15.15)

1. **Per-database degraded flag** (plain prefs, survives restarts — B10):
   blocks reads AND writes for that store, pauses its backup, hard-disables
   the Archivist (A3 note + working `Repair` / `Revert to Last Good
   Database` buttons), shows the A2 banner per new chat until repaired.
2. **Startup path:** crash-triggered check fails → bounded auto-repair
   attempt (SQLite salvage/recover) → success: `Database Repaired` dialog
   (A1 variant); failure: `Database Problem Found!` (A1) with
   `Repair | Revert to Last Good Database | Not Now`; `Not Now` → degraded
   mode, never force-close.
3. **Revert path (§15.2b + §15.6):** verify newest backup, walk older until
   one passes; A5 confirm (names the backup's date, the loss noun for that
   database, and that the damaged file is kept); quarantine the corrupt file
   renamed with date into the recovery folder (indexed in
   `SnapshotRegistry`, never auto-deleted); nothing usable → fresh empty DB
   + A6 (`Database Recovery Failed`, shows the preserved path,
   `Open File Location`).
4. **Same-device chat recovery restore (NOT deferred):** restoring the
   verified chats snapshot is a wholesale REPLACEMENT of chat storage —
   current/damaged files preserved first (quarantine + `SnapshotRegistry`,
   same pattern as the databases) — and needs NO merge/duplicate policy.
   Boundaries: it repairs damaged chat FILES on this phone; it cannot cure
   a lost Keystore key (the snapshot is encrypted with the same key — that
   case stays governed by the Round-4 lock system). Implementation care:
   replacement must coordinate with the live `SecurePrefs` caches and the
   Round-4 lock state machine (invalidate/reopen, or restart the process
   after the swap), and respects `RenameJournal.hasPending`. Only manual /
   cross-device import into an EXISTING chat collection (merge rules)
   remains deferred to Round 5.
4. **Mid-session path (§15.2c):** corruption exceptions caught at the store
   layer → flag degraded → distinct audio cue (hands-free sessions ONLY,
   distinct from the existing chimes) → A2 banner. No new dialog.
5. **Manual check (B8):** one press checks all three databases →
   `Database Check Complete` / `Database Check Incomplete` with a
   per-database line each; failure routes into the same A1 flow.
6. **A4 (3 consecutive auto-backup failures):** the approved two-stage
   dialog with inline check; **custom dialog layout required** (five
   actions exceed a standard Material dialog); shows the backup folder path
   + `Open Backup Folder`.
7. **Profile images specifics (§15.16):** auto-repair = rebuild catalog by
   rescanning the image files (always disclosed via the Repaired dialog,
   never silent); same banner + repair/replace; NO feature-disable beyond
   the gallery itself (chat and memory unaffected).
8. **Error Log health lines (§15.15):** once per transition, written
   regardless of the diagnostics toggle, to the Error Log, with the
   timestamp rendered in red in `LogsActivity`.

**Exit:** pure logic (flag transitions, backup-walk selection, 3-strike
counting, rebuild-from-files planner) unit-tested in `app/src/test`; CI
green; dialogs verified by the owner on-device. Real-corruption testing
cannot be staged on the owner's phone — any debug-only trigger to preview
these dialogs needs the owner's explicit yes first.

## Round 4 — Wording completion (owner copy, then wire it)

Strings that do NOT exist yet (everything else is already approved
verbatim — do not touch it):
1. A2/A3-style banner text naming the **profile images** database.
2. Buttons on the B8 result (damage found → likely
   `Repair | Revert to Last Good Database`; incomplete → likely
   `Try Again | View Error Log`) — owner confirms labels.
3. Chats backup status line text + its placement confirmation.
4. In-progress/status text under `Create Database Backup`.
5. The Memory Backup & Restore row still has no approved subtitle
   (existing note in strings.xml) — owner may supply one or leave it.

Each lands only after the owner approves the words in chat.

## Round 5 — Deferred (explicitly NOT in this plan's scope)

- **Manual / cross-device chat IMPORT into an existing chat collection**
  (merge and duplicate rules): needs its own design conversation.
  Same-device recovery restore of the automatic chats snapshot is NOT
  deferred — it ships with Rounds 2–3 (replacement restore, no merge
  policy needed).
- **The §1–§14 machinery** of the design doc (health episodes store,
  counters, reindex sweeper, contradiction lifecycle, run filter stats /
  DB v15): Rounds 1–3 build only the thin slice they need (degraded flags,
  per-type failure counters, transition-only Error-Log lines). The full
  model stays design-only unless the owner asks for it. §15.15 supersedes
  §4.2's log-channel choice; B13 closes §14.1.
- **Cross-device restore:** on-device snapshots restore on the same phone
  only (keys live in this phone's Keystore/EncryptedPreferences); the
  manual JSON export remains the cross-device path. A Keystore-loss event
  is already governed by the Round-4 lock system, not backups.

---

## Decisions the owner has NOT yet made (needed at the marked rounds)

1. **(Round 2) Chats daily-backup format:** encrypted-file archive
   (private at rest; restores on this phone only) — RECOMMENDED — vs
   readable JSON (restores anywhere; plaintext on disk). The manual JSON
   export continues to exist either way.
2. **(Round 3) B8 result buttons** and **(Round 4) the wording list** above.
3. **(Round 3) Debug preview trigger** for the new dialogs: allowed or not.

## Known build risks (stated so they don't surprise anyone)

- **`Open Backup Folder` / `Open File Location` are optional convenience,
  not the primary requirement:** the requirement is the SAF user-chosen
  folder (Round 2 item 1), which works regardless. Android has no reliable,
  universal intent to open a folder in a file manager, so these buttons are
  best-effort + ALWAYS show the path as selectable text as fallback. Their
  unreliability never blocks choosing or using a backup folder.
- **Chat-storage replacement swap (Round 3 item 4):** swapping the
  encrypted chat files under a running app must coordinate with
  `SecurePrefs`' cached handles and the Round-4 lock state machine —
  invalidate-and-reopen or a controlled process restart after the swap;
  honor `RenameJournal.hasPending` and the lock-order rules before
  touching files.
- **A4's five actions** require a custom dialog layout (house style permits
  custom dialogs; not a standard 3-button MaterialAlertDialog).
- **"Repair" of SQLCipher databases** is salvage, not magic: a bounded
  recover-what-reads pass. The design already treats repair failure as
  normal (→ revert path); no round may promise more than salvage.
- **Round 0 overlap:** another session owns the freeze fix proposal.
  Verify current `main` and open branches before Round 0 writes a line.

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
  + chats only. Its daily throttle lives INSIDE the memory database
  (`META_LAST_AUTO_EXPORT_AT`) — a broken memory DB breaks scheduling for
  everything (fixed in Round 1).

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
3. **Backup scheduling state moves OUT of the memory DB** into a plain
   prefs file (same rationale as `storage_health`: scheduling must work
   while any one store is broken). Per-type last-run/last-success/
   consecutive-failure counters live here (used by Rounds 2–3).

**Exit:** CI green; Event/Error logs show integrity checks only after
abnormal exits; startup housekeeping measurably does less on a clean start.

## Round 2 — Per-type backups (§15.13, §15.16 backup half, §15.9 order)

1. **`App Backups/` folder** with four independent artifacts per run, same
   timestamp: memory, lorebook, profile-images catalog, chats. Temp-write →
   verify → rotate keep-5 **per type**; the last known-good of a type is
   never deleted until its replacement verifies; one type failing never
   touches another's files.
2. **Consistent snapshots:** database copies via SQLite `VACUUM INTO` (works
   under SQLCipher; output stays encrypted; never a raw copy of a live db
   file). Chats: an archive of the encrypted per-chat files. Locked chat
   storage pauses ONLY the chats artifact (Round-4 policy); a degraded
   database (Round 3 flag) pauses ONLY its own artifact (A1's
   "unavailable to use or save").
3. **Detection synergy (free):** a VACUUM/verify failure during the daily
   run is itself a corruption signal — it feeds the per-type failure counter
   (→ A4 at 3 strikes) and can raise the Round-3 flow without waiting for a
   crash.
4. **Backup & Restore screen additions**, in §15.9's approved order:
   `Check Database Integrity` button ABOVE `Create Database Backup`; status
   text under each; per-type status lines (`Month D, YYYY, H:MM AM/PM`,
   12-hour); per-type failed-line ABOVE its success line. Chats' status
   line placement: this screen unless the owner says otherwise (wording
   list, Round 4).
5. The existing manual JSON export/import stays untouched (it remains the
   cross-device path; see Decisions).

**Exit:** CI green; owner sees four files appear in `App Backups/` and live
status lines on their phone.

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

- **Chat RESTORE** (import/merge of a chats backup): backups exist from
  Round 2, but putting chats back has no owner-designed merge semantics
  yet. Needs its own design conversation.
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

- **`Open Backup Folder` / `Open File Location`:** Android has no reliable,
  universal intent to open a folder in a file manager. Implement
  best-effort intent + ALWAYS show the path as selectable text as fallback.
  The buttons stay (approved wording); the fallback keeps them honest.
- **A4's five actions** require a custom dialog layout (house style permits
  custom dialogs; not a standard 3-button MaterialAlertDialog).
- **"Repair" of SQLCipher databases** is salvage, not magic: a bounded
  recover-what-reads pass. The design already treats repair failure as
  normal (→ revert path); no round may promise more than salvage.
- **Round 0 overlap:** another session owns the freeze fix proposal.
  Verify current `main` and open branches before Round 0 writes a line.

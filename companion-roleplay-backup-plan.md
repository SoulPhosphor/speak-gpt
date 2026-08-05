# Companion & Roleplay Backup — Implementation Plan

Owner-approved plan (August 5, 2026). All product decisions, behavior rules,
and user-facing wording in this document are approved. Implementation must
match this document; any deviation that changes behavior or wording requires
owner approval first.

## 1. Purpose

Disaster recovery for companion and roleplay configuration. If a phone dies
or the app is reinstalled, the user imports one backup file and recovers
their companions, personas, prompts, and roleplay setup without complicated
setup. This is one backup category with no per-item selection.

Memories, lorebooks, and lore entries are deliberately **not** in this
backup — the owner classifies lorebooks as memory-tier data. A separate
memory backup (and possibly a future whole-package backup with categories)
covers those. Users who do not use the memory system must not be forced to
carry or overwrite it to protect their companions.

## 2. What the backup contains

### 2.1 Companion profiles (app settings storage, `personas` preferences)

Per companion, all stored fields:

- stable id
- name (label)
- instructions (the prompt)
- activation prompt link (id)
- core lorebook link (id) — reference only, plus the lorebook's **name**
  captured at export time so a restore report can name a missing lorebook
- additional lorebook links (ids) — reference only, plus names as above
- auto-load-last-lorebooks setting and its last-used lorebook id bookkeeping
- avatar picture reference (Profile Images hash)

### 2.2 Activation prompts (app settings storage, `activation_prompts`)

Per prompt: stable id, label, prompt text.

### 2.3 System prompts library (app settings storage, `system_prompts`)

The full ordered list (id, title, body per entry) and which entry is
selected. On restore, the effective prompt is re-mirrored into the global
system message exactly as the library normally does after any change.

### 2.4 Roleplay structure (companion memory database)

Only these record sets, with all their columns:

- `companions` and `companion_name_history`
- `user_personas` (My Personas)
- `roleplay_characters`
- `worlds`
- `campaigns`
- `party_members` and `campaign_party_members` (which members belong to
  which campaign)
- `card_entries` (all Zone 2 card entries for all four card types)
- `rp_tags`, and `rp_tag_links` rows whose target is a card or card entry.
  Tag links whose target is a **memory** are not exported (memories are not
  in this backup); their restore-time handling is defined in §6.3.

### 2.5 Profile images

The `profile_<hash>.jpg` files referenced by any included record (companion
profile avatar refs, `user_personas.image_ref`,
`roleplay_characters.image_ref`). These are locally owned by the app. No
other media. The global Default User Image setting is a general app setting
and is not included.

### 2.6 Explicitly excluded

Memories and every memory-to-anything join row; memory types; Memory
Assistant data of any kind (transcripts, runs, analysis state, drafts,
pending "Lorebook Memories" suggestions, archivist settings, proposals);
modes; directives; owner profile; lorebooks, lore entries, and triggers;
model rules; conversations and chat history; API settings and provider
profiles; general app settings; embeddings, cooldowns, tombstones, and all
device-local operational tables. Never a raw database file. No API keys or
tokens anywhere in the file.

## 3. Backup file format

One ZIP archive. Suggested file name: `companion-backup-YYYY-MM-DD.zip`
(date of export). Contents:

- `backup.json` — the manifest, human-readable JSON:
  - `format`: `"companion-roleplay-backup"` (file-type marker)
  - `format_version`: `1`
  - `app_version`: from the package info
  - `exported_at`: ISO timestamp
  - one section per §2 record set, records carried with their stable ids
    and all relationship ids
  - `images`: list of `{hash, file}` entries pointing into `images/`
- `images/` — the referenced `profile_<hash>.jpg` files.

Always a ZIP, even with zero images: one file type to recognize and
validate, one code path. The format survives database schema changes
because restore maps JSON fields to whatever the current schema is —
exactly why a raw database copy is forbidden.

If the memory store is not provisioned (user never opted in) the roleplay
section exports empty; the rest of the backup still works.

## 4. User interface

One new section on the existing Backup screen (`MemoryBackupRestoreActivity`),
placed directly **after Portable Data Copy** (owner-approved placement; the
class-doc section order comment is updated in the same change). No redesign
of the screen; the section reuses the screen's existing section, button,
and status-text styles.

- Section title: **Companion & Roleplay Backup**
- Description: **Back up or restore companions, personas, prompts, and
  related roleplay data.**
- Two buttons: **Download Backup** and **Upload Backup**
- Download Backup opens the system document picker
  (`CreateDocument("application/zip")`) so the user chooses where to save.
- Upload Backup opens the system document picker (`OpenDocument`) to select
  an existing backup file.
- While working: both buttons disable and a rotating progress indicator
  shows with the status label **Backing up…** or **Restoring…** (existing
  screen pattern). Work runs off the interface thread.
- No toasts anywhere (standing owner rule for this screen): results are
  persistent inline status text or Material dialogs.
- No background service in this version: the payload is text plus a handful
  of JPEGs — seconds of work. The snapshot-and-transaction design (§6) means
  an interruption leaves data either fully restored or fully untouched.

## 5. Export flow (Download Backup)

1. Collect all §2 data. Lorebook link names are read from the lorebook
   store at this moment purely for the manifest; lorebook content is never
   read into the backup.
2. Build `backup.json`, copy referenced image files into the archive.
   A referenced image whose file is missing on the source device is simply
   absent (the reference itself is still carried; restore reproduces the
   source state).
3. Write the ZIP to the user-chosen location via the document picker.
4. Success: inline status **Backup saved to \<folder\>.** (existing
   friendly-location formatting; never a raw URI). Failure: the approved
   save-failure dialog (§8).

## 6. Restore flow (Upload Backup)

### 6.1 Validate before touching anything

1. Read the selected file completely.
2. Validate: ZIP readable → `backup.json` present and parseable → `format`
   marker matches → `format_version` is supported (≤ current) → required
   sections structurally sound → every image listed in the manifest exists
   in the archive.
3. Any validation failure shows the matching error dialog from §8 and
   changes **nothing**.

### 6.2 Confirm replacement

If any companion or roleplay data covered by this backup already exists on
the device, show one confirmation dialog (wording in §9). Cancel makes no
changes. If nothing exists (fresh install), no confirmation is needed.

### 6.3 Apply — all-or-nothing

Order and rollback protection:

1. **Images first** (additive): copy image files into the profile images
   folder and catalog them, tracking which files this restore added. Adding
   never overwrites different content (hash-named files are content-stable).
2. **Memory database in one transaction**: delete the existing §2.4 record
   sets, insert the backup's records, with foreign-key enforcement deferred
   to commit inside the transaction. Provision the store first if the
   backup contains roleplay records and the store does not exist yet; if it
   contains none, do not provision.
3. **App settings last, staged**: capture the current values of every key
   about to change (companion profiles, activation prompts, system prompts
   library and selection), then write the backup's values, then re-mirror
   the effective system prompt into the global system message.
4. **On any failure at any step**: the database transaction rolls back
   automatically; captured settings values are written back; files this
   restore added are removed (best-effort — a leftover hash-named file is
   harmless and reconciliation already handles orphans). The failed-restore
   dialog (§8) states the specific reason. No partial restores.
5. Invalidate cached store instances so reopened stores see the restored
   data, and refresh affected screens (§7).

### 6.4 Relationship resolution rules

Memories are never deleted or modified by this restore. Records outside
this backup that point at replaced structure follow one principle, applied
in the same transaction: **a link that resolves after restore is kept; a
link that no longer resolves is removed.** When the backup comes from the
same install (the normal disaster-recovery case), ids match and every link
survives.

| Reference | Target exists after restore | Target missing |
| --- | --- | --- |
| Companion profile → lorebook (core and additional) | reconnected | link removed, **reported to the user by lorebook name** (§9) |
| Companion profile → activation prompt | always reconnects (prompts are in the backup) | — |
| Last-used-lorebook bookkeeping ids | kept | dropped silently (invisible bookkeeping) |
| Memory join rows (`memory_companions`, `memory_worlds`, `memory_campaigns`, `memory_roleplay_characters`) | kept | join row removed; the memory itself is untouched |
| Memories' primary-target mirror columns (`world_id`, `campaign_id`, `roleplay_character_id`) | kept | cleared (display-only mirror; join tables are the source of truth) |
| Roleplay tag links to memories (existing device rows) | kept when the tag id survives the replace | removed with the tag |
| Transcript context ids (`companion_id`, `world_id`, `roleplay_character_id`, `user_persona_id`) | kept | cleared (nullable context; transcript content untouched) |
| Active selections (`app_state`) | kept | cleared |

Only the lorebook-link removals are user-visible in the report — they are
the one connection the user manages by name on the companion screen. The
rest are internal wiring following the same approved principle.

### 6.5 Result

- Smooth restore, no removed lorebook links: persistent inline success
  status (§9). No dialog.
- Restore succeeded but lorebook links were removed: the stay-until-
  dismissed report dialog (§9), matching the screen's existing import-report
  pattern.
- Failure: the matching error dialog (§8); data reverted.

## 7. Screens refreshed after restore

Characters list, activation prompts list, system prompts list, My Personas,
roleplay card screens (worlds, campaigns, characters, party members), and
the Backup screen's own status line. Implementation refreshes via the
existing change-listener / on-resume paths; no new refresh framework.

## 8. Error wording (approved, exact)

Each error names the cause the app actually knows (owner rule: errors tell
the truth; no umbrella messages). All are dialogs.

- Wrong file: "This file is not a companion backup. Choose a file that was
  created with Download Backup. No changes were made."
- Newer format: "This backup was created by a newer version of the app and
  can't be read by this one. Update the app, then try again. No changes
  were made."
- Damaged: "This backup file is damaged or incomplete and can't be read
  safely. No changes were made."
- Failed mid-restore: "The restore could not be completed: [specific
  reason]. Your existing data was restored to its previous state."
- Save failed: "The backup could not be saved: [specific reason]."

## 9. All other user-facing wording (approved, exact)

- Section title: "Companion & Roleplay Backup"
- Section description: "Back up or restore companions, personas, prompts,
  and related roleplay data."
- Buttons: "Download Backup" / "Upload Backup"
- Progress labels: "Backing up…" / "Restoring…"
- Default file name: `companion-backup-YYYY-MM-DD.zip`
- Replace confirmation — title: "Replace companion and roleplay data?"
- Replace confirmation — body: "This backup will replace your existing
  companions, personas, system prompts, activation prompts, and roleplay
  cards with the contents of the backup file. Memories, lorebooks,
  conversations, API settings, and other app settings will not be changed."
- Replace confirmation — buttons: "Replace" / "Cancel"
- Success (inline): "Backup restored. Companions, personas, prompts, and
  roleplay cards are ready to use."
- Export success (inline): "Backup saved to \<folder\>."
- Report dialog — title: "Restore complete"
- Report dialog — body: "Companions, personas, prompts, and roleplay cards
  were restored. These lorebook connections were removed because the
  lorebooks do not exist on this device:" followed by one line per removed
  link ("\<Companion\> — \<lorebook name\>"), then: "You can reconnect a
  lorebook in each companion's settings at any time."

"Lorebook" is always one word. The app's name never appears in any string
(standing owner ruling).

## 10. Build order (single phase)

All product decisions are made; no approval gates remain inside the build.
Ordered steps on the `claude/companion-backup-restore-lm2jct` branch:

1. **Serializer + export**: manifest builder, ZIP writer, image collection,
   lorebook-name capture; unit tests for round-trip and field coverage.
2. **Validator + restore engine**: full pre-validation, the §6.3 staged
   apply with snapshot/rollback, the §6.4 resolution rules; unit tests for
   each rejection cause, rollback on injected failure, and every resolution
   rule (kept vs removed).
3. **UI section**: the new section, pickers, progress states, dialogs,
   inline statuses, screen refreshes.
4. **CI green** on the Android Checks workflow, then owner device testing.
   The restore is not reported as verified until the owner confirms it on
   the test device.

## 11. Non-goals (unchanged from the work order)

No encryption, password protection, cloud backup, Google Drive integration,
automatic/scheduled backups, per-item selection, multiple backup profiles,
synchronization, account systems, conversation-history backup, unrelated
refactoring, or a generic backup framework. The eventual whole-package
backup with categories is a separate future effort.
